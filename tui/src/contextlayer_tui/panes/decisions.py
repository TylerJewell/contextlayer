"""Decisions: things that wait for a person, with the rules a control puts on how they are decided."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from rich.console import Group, RenderableType
from rich.markup import escape
from rich.table import Table
from rich.text import Text
from textual.binding import Binding

from .. import format as fmt
from ..api import ApiError
from ..format import DIM, INFO, WARN, badge, clip
from ..modals import ConfirmModal, PromptModal
from .base import Pane


def _millis(iso: str | None) -> int:
    return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000) if iso else 0


class DecisionsPane(Pane):
    columns = ("Wants to", "Playbook", "State", "Due", "Held by")
    empty_text = "Nothing is waiting for a person."
    BINDINGS = [
        Binding("a", "decide(True)", "Approve"),
        Binding("x", "decide(False)", "Reject"),
        Binding("e", "escalate", "Escalate"),
        Binding("b", "send_back", "Send back"),
        Binding("r", "release", "Release", show=False),
        Binding("w", "withdraw", "Withdraw", show=False),
    ]

    async def load(self) -> None:
        events = await self.api.human_events()
        rows = [(e["id"], e, (f"[bold]{clip(e['subject']['summary'], 44)}[/]", e["subject"].get("agent") or "—",
                              badge(e["state"]), f"{badge(e['sla']['state'], words=False)} {fmt.until(_millis(e['sla']['dueAt']))}",
                              (e.get("holder") or {}).get("subject", "—")))
                for e in events]
        self.fill(rows)

    @property
    def count(self) -> int | None:
        return len(self.items)

    # ---- what the person sees

    def mine(self, event: dict[str, Any]) -> bool:
        return (event.get("holder") or {}).get("subject") == self.api.subject

    def seconds_open(self, event: dict[str, Any]) -> int:
        return max(0, (fmt.now_ms() - _millis(event.get("claimedAt"))) // 1000)

    def describe(self, e: dict[str, Any]) -> RenderableType:
        needed = e["minimumReviewSeconds"]
        lines: list[RenderableType] = [
            Text.from_markup(f"[bold]{e['subject']['summary']}[/]   {badge(e['state'])}"),
            Text.from_markup(f"[{DIM}]{e['control']['name']}[/]"),
            Text(),
            Text.from_markup(f"[bold]{e['ask']['question']}[/]"),
            Text(f"Why it is waiting: {e['trigger']['reason'] or e['trigger']['rule']}."),
        ]
        payload = (e["subject"].get("detail") or {}).get("payload")
        if payload:
            lines.append(Text.from_markup(f"[bold]It would send:[/] {escape(payload)}"))
        asker = e["routing"]["independentOf"]
        if asker:
            lines.append(Text.from_markup(f"[{DIM}]Requested by {', '.join(asker)}, who cannot decide it.[/]"))
        if e["ask"]["checks"]:
            lines += [Text(), Text("Before you decide, check:")]
            lines += [Text(f"  ☐ {c['text']}") for c in e["ask"]["checks"]]

        sla = e["sla"]
        lines += [
            Text(),
            Text.from_markup(f"Due {fmt.clock(_millis(sla['dueAt']))}   {badge(sla['state'])}   "
                             f"[{DIM}]if nobody decides: {sla['default'] or sla['timeoutBehaviour'].lower().replace('_', ' ')}[/]"),
            Text.from_markup(f"Needs a person with the role: [bold]{', '.join(e['routing']['roles'])}[/]"
                             f"   [{DIM}]level {e['escalation']['level'] + 1} of {len(e['escalation']['chain'])}[/]"),
        ]
        if needed:
            lines.append(Text.from_markup(f"[{INFO}]This control expects at least {needed} seconds of review"
                                          f"{', and a written reason' if e['rationaleRequired'] else ''}.[/]"))
        if self.mine(e):
            held = self.seconds_open(e)
            lines.append(Text.from_markup(f"[bold]You have had this open for {held} seconds.[/]"
                                          + (f" [{WARN}]Keep reading.[/]" if held < needed else "")))

        history = Table(title="What has happened to it", title_justify="left", box=None, expand=True, show_header=False)
        history.add_column(style=DIM)
        history.add_column()
        for step in e["history"][-5:]:
            history.add_row(fmt.clock(_millis(step["at"])), f"{step['actor']}: {step['note']}")
        lines += [Text(), history, Text(), self.keys(e)]
        return Group(*lines)

    def keys(self, e: dict[str, Any]) -> Text:
        if e["state"] == "CHANGES_REQUESTED":
            return Text.from_markup(f"[{WARN}]With its requester for changes. [bold]w[/] withdraw[/]")
        if not self.mine(e):
            return Text.from_markup("[bold]a[/] open it to review (you decide on the next press)"
                                    "     [bold]e[/] escalate     [bold]b[/] send back")
        return Text.from_markup("[bold]a[/] approve   [bold]x[/] reject   [bold]e[/] escalate   "
                                "[bold]b[/] send back   [bold]r[/] release it")

    # ---- what the person does

    async def _call(self, work, done: str) -> None:
        try:
            await work
        except ApiError as error:
            self.fail(error)
            return
        self.app.notify(done)
        await self.reload()

    async def action_decide(self, approve: bool) -> None:
        e = self.selected
        if e is None:
            return
        if not self.mine(e):
            # Opening it is when you are shown it; the minimum review time counts from here.
            await self._call(self.api.human_claim(e["id"]),
                             f"Opened for you. Read it, then press {'a' if approve else 'x'} again to decide.")
            return
        needed = e["minimumReviewSeconds"]
        held = self.seconds_open(e)
        if held < needed:
            message = (f"You have had this open for {held} seconds and the control expects {needed}.\n\n"
                       "You may decide now; it will be recorded as below the minimum review time.")
            self.app.push_screen(ConfirmModal("Decide this quickly?", message, yes="Decide anyway"),
                                 lambda ok: self._ask_why(e, approve) if ok else None)
        else:
            self._ask_why(e, approve)

    def _ask_why(self, e: dict[str, Any], approve: bool) -> None:
        verb = "Approve" if approve else "Reject"

        def done(why: str | None) -> None:
            if why is not None:
                self.run_worker(self._call(self.api.human_decide(e["id"], "APPROVE" if approve else "DENY", why),
                                           "Approved. It will be sent once." if approve else "Rejected. It will not run."))

        self.app.push_screen(PromptModal(f"{verb}: {clip(e['subject']['summary'], 50)}",
                                         "Why? This goes on the record with your name and role.",
                                         required=e["rationaleRequired"]), done)

    async def action_escalate(self) -> None:
        self._prompt_then("Escalate", "Why does this need someone more senior?",
                          lambda e, text: self.api.human_escalate(e["id"], text), "Escalated.")

    async def action_send_back(self) -> None:
        self._prompt_then("Send back", "What does the requester need to change or explain?",
                          lambda e, text: self.api.human_send_back(e["id"], text), "Sent back to the requester.")

    async def action_withdraw(self) -> None:
        self._prompt_then("Withdraw", "Why are you withdrawing it?",
                          lambda e, text: self.api.human_withdraw(e["id"], text), "Withdrawn. The action will not run.")

    async def action_release(self) -> None:
        e = self.selected
        if e is not None:
            await self._call(self.api.human_release(e["id"]), "Released. Someone else can take it.")

    def _prompt_then(self, title: str, label: str, call, done: str) -> None:
        e = self.selected
        if e is None:
            return

        def answered(text: str | None) -> None:
            if text is not None:
                self.run_worker(self._call(call(e, text), done))

        self.app.push_screen(PromptModal(f"{title}: {clip(e['subject']['summary'], 50)}", label), answered)
