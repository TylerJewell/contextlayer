"""Playbooks: who is accountable, which release runs, and the road from draft to live."""

from __future__ import annotations

from collections import Counter
from functools import partial
from typing import Any

from rich.console import Group, RenderableType
from rich.table import Table
from rich.text import Text
from textual.binding import Binding

from ..api import ApiError
from ..format import BAD, DIM, INFO, OK, WARN, badge, clip, dot
from .. import playbook as pb
from ..editor import PlaybookEditor
from ..modals import ConfirmModal, PromptModal
from .base import Pane

STAGES = ["DRAFT", "SHADOW", "LIVE"]


class PlaybooksPane(Pane):
    columns = ("", "Playbook", "Owner", "Stage", "Releases", "Purpose")
    empty_text = "No playbooks yet."
    BINDINGS = [Binding("p", "move(1)", "Promote"), Binding("b", "move(-1)", "Step back"),
                Binding("a", "approve", "Approve release"), Binding("h", "halt", "Halt or restart"),
                Binding("e", "edit", "Edit steps"), Binding("n", "new", "New playbook")]

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.detail: dict[str, Any] | None = None
        self.activity: list[dict[str, Any]] = []
        self.halted: set[str] = set()

    async def load(self) -> None:
        assistants = await self.api.assistants()
        rows = [(a["assistantId"], a, (dot(a["stage"]), f"[bold]{a['assistantId']}[/]", a["owner"], badge(a["stage"]),
                                       f"{a['approvedCount']}/{a['releaseCount']} approved", clip(a["purpose"], 30)))
                for a in assistants]
        self.fill(rows)

    def show_detail(self) -> None:
        if self.selected is None:
            self.query_one("#detail").update("")  # type: ignore[attr-defined]
            return
        self.run_worker(partial(self._load_detail, self.selected["assistantId"]), exclusive=True, group="assistant-detail")

    async def _load_detail(self, assistant_id: str) -> None:
        try:
            detail = await self.api.assistant(assistant_id)
            activity = await self.api.assistant_actions(assistant_id)
            self.halted = {h["target"] for h in await self.api.halts() if h["halted"]}
        except ApiError as error:
            self.fail(error)
            return
        if self.selected is not None and self.selected["assistantId"] == assistant_id:
            self.detail, self.activity = detail, activity
            self.query_one("#detail").update(self.describe(detail))  # type: ignore[attr-defined]

    @staticmethod
    def stepper(stage: str) -> Text:
        text = Text()
        for i, name in enumerate(STAGES):
            if i:
                text.append("  ──▶  ", style=DIM)
            text.append(f" {name} ", style="reverse bold" if name == stage else DIM)
        return text

    def describe(self, a: dict[str, Any]) -> RenderableType:
        head = Text.from_markup(f"[bold]{a['assistantId']}[/]   {badge(a['stage'])}")
        if a["assistantId"] in self.halted:
            head.append_text(Text.from_markup(f"   [bold {BAD}]HALTED[/]  [{DIM}]nothing it asks for is done[/]"))
        sub = Text.from_markup(f"[{DIM}]accountable: {a['owner']}[/]")
        purpose = Text(a["purpose"])

        releases = Table(title="Releases", title_justify="left", box=None, expand=True)
        for name in ("Release", "State", "Approved by"):
            releases.add_column(name)
        for r in a["releases"]:
            running = r["releaseId"] == a.get("runningReleaseId")
            releases.add_row(r["releaseId"] + (" ◀ running" if running else ""),
                             f"[{OK}]approved[/]" if r["approved"] else f"[{WARN}]not approved[/]", r["approvedBy"] or "—")

        parts: list[RenderableType] = [head, sub, Text(), purpose, Text(), self.stepper(a["stage"]), Text(), releases]
        newest = a["releases"][-1] if a["releases"] else None
        if newest is not None:
            steps = pb.parse(newest.get("definition"))
            state = "running" if newest["releaseId"] == a.get("runningReleaseId") else (
                "approved" if newest["approved"] else "waiting for its owner's approval")
            parts += [Text(), Text.from_markup(f"[bold]Steps of release {newest['releaseId']}[/]  [{DIM}]{state}[/]"),
                      pb.summary(steps), Text(), pb.draw(steps)]
        parts += [Text(), self.activity_report(a["stage"])]
        parts += [Text(), Text.from_markup(
            "[bold]p[/] promote   [bold]b[/] step back   [bold]a[/] approve the newest release   "
            "[bold]h[/] halt or restart   "
            f"[{DIM}](only {a['owner']} may)[/]"),
            Text.from_markup("[bold]e[/] edit the steps as a new release   [bold]n[/] new playbook")]
        return Group(*parts)

    def activity_report(self, stage: str) -> RenderableType:
        counts = Counter(item["status"] for item in self.activity)
        if not self.activity:
            return Text.from_markup(f"[{DIM}]No actions requested yet.[/]")
        title = "What it would have done" if stage == "SHADOW" else "What it has done"
        table = Table(title=title, title_justify="left", box=None, expand=True, show_header=False)
        table.add_column()
        table.add_column(justify="right")
        for status, n in counts.most_common():
            table.add_row(badge(status), str(n))
        sent = counts.get("SUCCEEDED", 0)
        note = ("Shadow mode records these and sends nothing." if stage == "SHADOW"
                else f"{sent} sent; risky actions wait for a person.")
        return Group(table, Text(note, style=INFO))

    # ---- actions

    async def action_move(self, step: int) -> None:
        a = self.detail if self.detail and self.selected and self.detail["assistantId"] == self.selected["assistantId"] else None
        if a is None:
            return
        target_index = STAGES.index(a["stage"]) + step
        if not 0 <= target_index < len(STAGES):
            self.app.notify("It is already at the end of the road." if step > 0 else "It is already a draft.", severity="warning")
            return
        target = STAGES[target_index]
        if target == "LIVE":
            running = a.get("runningReleaseId") or "its approved release"
            message = (f"Release {running} will start acting. Unattended actions will run; risky ones still wait"
                       f" for a person.\n\nOnly {a['owner']} can do this.")
            self.app.push_screen(ConfirmModal(f"Put {a['assistantId']} live?", message, yes="Go live"),
                                 lambda ok: self._ask_why_stage(a["assistantId"], target) if ok else None)
        else:
            self._ask_why_stage(a["assistantId"], target)

    def _ask_why_stage(self, assistant_id: str, stage: str) -> None:
        def answered(why: str | None) -> None:
            if why is not None:
                self.run_worker(self._set_stage(assistant_id, stage, why))

        self.app.push_screen(PromptModal(f"Move {assistant_id} to {stage.lower()}",
                                         "Why? This goes on the record with your name and role."), answered)

    async def _set_stage(self, assistant_id: str, stage: str, why: str) -> None:
        try:
            await self.api.assistant_set_stage(assistant_id, stage, why)
        except ApiError as error:
            self.fail(error)
            return
        self.app.notify(f"{assistant_id} is now {stage.lower()}.")
        await self.reload()

    async def action_approve(self) -> None:
        a = self.detail
        if a is None or self.selected is None or a["assistantId"] != self.selected["assistantId"]:
            return
        pending = [r for r in a["releases"] if not r["approved"]]
        if not pending:
            self.app.notify("Every release is already approved.", severity="information")
            return
        release = pending[-1]["releaseId"]

        def answered(why: str | None) -> None:
            if why is not None:
                self.run_worker(self._approve(a["assistantId"], release, why))

        self.app.push_screen(PromptModal(f"Approve release {release}",
                                         "Why? This goes on the record with your name and role."), answered)

    async def _approve(self, assistant_id: str, release_id: str, why: str) -> None:
        try:
            await self.api.assistant_approve_release(assistant_id, release_id, why)
        except ApiError as error:
            self.fail(error)
            return
        self.app.notify(f"Release {release_id} approved.")
        await self.reload()

    async def action_halt(self) -> None:
        a = self.detail if self.detail and self.selected and self.detail["assistantId"] == self.selected["assistantId"] else None
        if a is None:
            return
        lift = a["assistantId"] in self.halted
        title = f"{'Restart' if lift else 'Halt'} {a['assistantId']}"
        label = ("Why is it safe to restart? Restarting needs two approvers." if lift
                 else "Why stop it? One approver can halt it at once.")

        def answered(why: str | None) -> None:
            if why is not None:
                self.run_worker(self._halt(a["assistantId"], lift, why))

        self.app.push_screen(PromptModal(title, label), answered)

    async def _halt(self, assistant_id: str, lift: bool, why: str) -> None:
        try:
            event = await self.api.halt(assistant_id, lift, why)
        except ApiError as error:
            self.fail(error)
            return
        # Asking and approving are separate acts. Someone who may approve gives their approval now.
        try:
            await self.api.human_decide(event["id"], "APPROVE", why)
        except ApiError as error:
            self.app.notify(f"Requested. It takes effect when an approver agrees ({error.message}).", severity="warning")
        else:
            self.app.notify(f"{assistant_id} restart: one approval given, a second approver is needed." if lift
                            else f"{assistant_id} is halted.")
        self.show_detail()
        await self.reload()

    # ---- editing

    async def action_edit(self) -> None:
        a = self.detail if self.detail and self.selected and self.detail["assistantId"] == self.selected["assistantId"] else None
        if a is None:
            return
        newest = a["releases"][-1] if a["releases"] else None
        await self._open_editor(a["assistantId"], pb.parse(newest.get("definition")) if newest else [],
                                newest["releaseId"] if newest else "")

    def action_new(self) -> None:
        def named(playbook_id: str | None) -> None:
            if playbook_id:
                self.app.push_screen(PromptModal(f"New playbook {playbook_id}", "What is it for? One sentence."),
                                     partial(purposed, playbook_id))

        def purposed(playbook_id: str, purpose: str | None) -> None:
            if purpose:
                self.run_worker(self._create(playbook_id.strip().lower(), purpose))

        self.app.push_screen(PromptModal("New playbook", "Its name (lowercase letters, digits and hyphens)",
                                         placeholder="claims-watch"), named)

    async def _create(self, playbook_id: str, purpose: str) -> None:
        try:
            await self.api.create_assistant(playbook_id, purpose)
        except ApiError as error:
            self.fail(error)
            return
        await self.reload()
        await self._open_editor(playbook_id, [pb.new_step("annotate", "note-1", pb.END)], "")

    async def _open_editor(self, playbook_id: str, steps: list[dict[str, Any]], based_on: str) -> None:
        try:
            schema = await self.api.schema()
            connectors = await self.api.connectors()
        except ApiError as error:
            self.fail(error)
            return
        actions = {action["name"]: action["risk"] for c in connectors if c["mode"] != "INACTIVE" for action in c["actions"]}

        def closed(release_id: str | None) -> None:
            if release_id:
                self.run_worker(self.reload())

        self.app.push_screen(PlaybookEditor(playbook_id, steps, based_on, schema, actions), closed)
