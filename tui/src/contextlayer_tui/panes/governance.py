"""Governance: each control the service enforces, and whether its evidence shows it working."""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

from rich.console import Group, RenderableType
from rich.text import Text
from textual.binding import Binding

from ..api import ApiError
from ..format import BAD, DIM, INFO, OK, WARN, badge, clip, clock, now_ms
from ..modals import PromptModal
from .base import Pane


def _accepted(s: dict[str, Any]) -> str:
    a = s.get("acceptance")
    if a is None or not a["accepted"]:
        return f"[{WARN}]no[/]"
    return "yes" if a["current"] else f"[{WARN}]old[/]"


def _claim(s: dict[str, Any]) -> str:
    a = s.get("assertion")
    if a is None or not a["asserted"]:
        return f"[{DIM}]none[/]"
    return (s.get("coverage") or {}).get("coverage", "").lower()


def _millis(iso: str | None) -> int:
    return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000) if iso else 0


class GovernancePane(Pane):
    columns = ("Control", "Kind", "Status", "Upheld", "Breached", "Flagged", "Accepted", "Evidence claim")
    empty_text = "No controls are declared."
    BINDINGS = [Binding("w", "waive", "Waive"), Binding("l", "lift", "Lift waiver"),
                Binding("a", "accept", "Accept"), Binding("i", "integrity", "Check ledger"),
                Binding("q", "ask", "Ask"), Binding("r", "attach", "Attach document")]

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.digest = ""
        self.ledger = ""  # what the last ledger check found, shown over every control until the next one
        self.answer = ""  # the last answer to a question about the controls, shown over every control until the next one
        self.documents: dict[str, dict[str, Any]] = {}

    async def load(self) -> None:
        catalog = await self.api.controls()
        standing = {c["controlId"]: c for c in (await self.api.compliance())["controls"]}
        self.digest = catalog["digest"]
        self.documents = {d["name"]: d for d in await self.api.documents()}
        rows = []
        for control in catalog["controls"]:
            s = standing.get(control["rowId"], {})
            merged = {**control, **{k: v for k, v in s.items() if k not in ("name", "mechanism")}}
            rows.append((control["rowId"], merged, (f"[bold]{clip(control['name'], 52)}[/]", control["mechanism"],
                                                      badge(s.get("status", "NO_EVIDENCE")), str(s.get("upheld", 0)),
                                                      str(s.get("breached", 0)), str(s.get("flagged", 0)),
                                                      _accepted(s), _claim(s))))
        self.fill(rows)

    @property
    def count(self) -> int | None:
        failing = sum(1 for c in self.items.values() if c.get("status") == "FAILING")
        return failing or None

    def describe(self, c: dict[str, Any]) -> RenderableType:
        status = c.get("status", "NO_EVIDENCE")
        lines: list[RenderableType] = [*([Text.from_markup(self.answer), Text()] if self.answer else []),
            *([Text.from_markup(self.ledger), Text()] if self.ledger else []),
            Text.from_markup(f"[bold]{c['name']}[/]"),
            Text.from_markup(f"{badge(status)}   [{DIM}]{c.get('why', '')}[/]"),
            Text(),
            Text(c["summary"] or ""),
            *self.claims_block(c),
            Text(),
            Text.from_markup(f"[{DIM}]{c['mechanism']} control · {c['lifecycle']} · acts at {c['boundary']}[/]"),
            Text.from_markup(f"[{DIM}]proved by {c['proofEvent']} · catalog {self.digest[:19]}…[/]"),
        ]
        if c.get("corpusRef"):
            lines.append(Text.from_markup(f"[{DIM}]answers {c['corpusRef']}[/]"))
        if c.get("enforcing") is not None:
            last = _millis(c.get("lastEnforcingAt"))
            lines += [Text(), Text(f"In the last week it ran {c['enforcing']} time(s): {c['upheld']} upheld, "
                                   f"{c['breached']} breached, {c['flagged']} flagged."),
                      Text(f"Last enforcing: {clock(last) if last else 'never'}.")]
        human = c.get("human")
        if human:
            lines += [Text(), Text.from_markup(
                f"[{INFO}]A person decides: {', '.join(human['roles'])} · reason "
                f"{'required' if human['rationaleRequired'] else 'optional'} · at least "
                f"{human['minimumReviewSeconds']} s of review"
                f"{' (enforced)' if human['enforceMinimum'] else ' (flagged, not enforced)'}[/]")]
        if status == "NO_EVIDENCE":
            lines += [Text(), Text.from_markup(
                f"[{WARN}]Only enforcing evidence counts. Shadow, advisory and test records prove nothing.[/]")]
        lines += [Text(), Text.from_markup("[bold]w[/] waive   [bold]l[/] lift a waiver   [bold]a[/] accept   "
                                           f"[bold]i[/] check the ledger     [{DIM}](waiving needs the admin role, accepting the approver)[/]")]
        return Group(*lines)

    def claims_block(self, c: dict[str, Any]) -> list[RenderableType]:
        """Who said what about the control. The acceptance and the assertion are different claims by different people."""
        acceptance, assertion, coverage = c.get("acceptance"), c.get("assertion"), c.get("coverage")
        if acceptance is None:
            return []
        lines: list[RenderableType] = [Text(), Text.from_markup("[bold]Who said what[/]")]
        if acceptance["accepted"]:
            stale = "" if acceptance["current"] else f" [{WARN}](of an earlier version of the catalog)[/]"
            lines.append(Text.from_markup(f"Accepted by [bold]{acceptance['by']}[/]{stale}: {clip(acceptance['rationale'], 70)}"))
        else:
            lines.append(Text.from_markup(f"[{WARN}]Nobody has accepted this control for the project.[/]"))
        if assertion["asserted"]:
            lines.append(Text.from_markup(f"Asserted by [bold]{assertion['by']}[/] (revision {assertion['revision']}): "
                                          f"{assertion['evidenceKind']} counts as evidence. {clip(assertion['rationale'], 60)}"))
            for expected in assertion["expects"]:
                held = self.documents.get(expected["name"])
                state = f"[{OK}]held, last current {held['asOf']}[/]" if held else f"[{WARN}]not held[/]"
                lines.append(Text.from_markup(f"[{DIM}]expects {expected['name']}, no older than {expected['maxAgeDays']} days:[/] {state}"))
        else:
            lines.append(Text.from_markup(f"[{WARN}]Nobody has said what counts as evidence for it.[/]"))
        if assertion["asserted"]:
            lines.append(Text.from_markup(f"Coverage: [bold]{coverage['coverage'].lower()}[/]"))
            for unmet in coverage["unmet"]:
                lines.append(Text.from_markup(f"[{WARN}]  missing: {unmet}[/]"))
        return lines

    # ---- actions

    async def action_waive(self) -> None:
        c = self.selected
        if c is None:
            return

        def done(reason: str | None) -> None:
            if reason is not None:
                self.run_worker(self._call(self.api.waive(c["rowId"], reason), f"{clip(c['name'], 40)} is waived."))

        self.app.push_screen(PromptModal(f"Waive: {clip(c['name'], 50)}",
                                         "Why? A waiver goes on the record with your name and role."), done)

    async def action_accept(self) -> None:
        c = self.selected
        if c is None:
            return

        def done(reason: str | None) -> None:
            if reason is not None:
                self.run_worker(self._call(self.api.accept_control(c["rowId"], reason), f"{clip(c['name'], 40)} is accepted."))

        self.app.push_screen(PromptModal(f"Accept: {clip(c['name'], 50)}",
                                         "Why? This says the project takes the control on. It does not say what counts as evidence."), done)

    async def action_ask(self) -> None:
        def done(question: str | None) -> None:
            if question is not None:
                self.run_worker(self._answer_question(question))

        self.app.push_screen(PromptModal("Ask about the controls", "What do you want to know?",
                                         "can a person override the assistant, and who signed that off?"), done)

    async def _answer_question(self, question: str) -> None:
        try:
            reply = await self.api.audit(question)
        except ApiError as error:
            self.fail(error)
            return
        if reply["clarification"]:
            self.answer = (f"[bold]{clip(question, 70)}[/]\n{reply['clarification']}\n"
                           + "\n".join(f"[{DIM}]  {clip(s, 80)}[/]" for s in reply["supported"]))
        else:
            facts = "\n".join(f"[{DIM}]  • {clip(f['text'], 96)}  [{', '.join(f['refs'][:2])}][/]" for f in reply["facts"][:6])
            limits = "\n".join(f"[{WARN}]  ○ {clip(limit, 96)}[/]" for limit in reply["limits"][:4])
            note = "" if reply["summaryChecked"] else f"\n[{WARN}]The wording claimed more than the facts, so these are the facts.[/]"
            self.answer = (f"[bold]{clip(question, 70)}[/]\n{reply['summary']}{note}\n\n[bold]Facts[/]\n{facts}"
                           + (f"\n\n[bold]Not shown by the records[/]\n{limits}" if limits else ""))
        self.show_detail()

    async def action_attach(self) -> None:
        c = self.selected
        if c is None or not (c.get("assertion") or {}).get("asserted"):
            self.app.notify("Nothing is asserted about this control, so there is no document to attach.", severity="warning")
            return
        missing = [e for e in c["assertion"]["expects"] if e["name"] not in self.documents]
        if not missing:
            self.app.notify("Every document this control expects is held.")
            return
        name = missing[0]["name"]

        def done(ref: str | None) -> None:
            if ref is not None:
                self.run_worker(self._call(self.api.register_document(name, "procedure" if "procedure" in name else "other", ref, date.today().isoformat()),
                                           f"{name} is registered."))

        self.app.push_screen(PromptModal(f"Register: {name}", "Where is it? A link or a path. The date it was last current is today."), done)

    async def action_integrity(self) -> None:
        try:
            report = await self.api.integrity()
        except ApiError as error:
            self.fail(error)
            return
        head = (report.get("head") or "")[:26]
        if report["intact"]:
            self.ledger = (f"[{OK}]Ledger checked {clock(now_ms())}: {report['count']} records, none altered.[/] "
                           f"[{DIM}]Keep this fingerprint where the service cannot write: {head}…[/]")
        else:
            self.ledger = f"[{BAD}]Ledger checked {clock(now_ms())}: altered. {'; '.join(report['problems'][:2])}[/]"
        self.show_detail()
        if report["intact"]:
            self.app.notify(f"{report['count']} records, none altered. Keep this head somewhere the service cannot write: {head}…",
                            title="The evidence ledger is intact", timeout=12)
        else:
            self.app.notify("; ".join(report["problems"][:3]), title="The evidence ledger was altered", severity="error", timeout=20)

    async def action_lift(self) -> None:
        c = self.selected
        if c is not None:
            await self._call(self.api.lift_waiver(c["rowId"]), "Waiver lifted.")

    async def _call(self, work, done: str) -> None:
        try:
            await work
        except ApiError as error:
            self.fail(error)
            return
        self.app.notify(done)
        await self.reload()
