"""Cases: what monitors found, the evidence, and the timeline of everything that happened."""

from __future__ import annotations

from functools import partial
from typing import Any

from rich.console import Group, RenderableType
from rich.table import Table
from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.widgets import Select

from ..api import ApiError
from ..format import DIM, INFO, ago, badge, cell, clock, dot
from ..modals import PromptModal
from .base import Pane

FILTERS = [("Open", "OPEN"), ("No longer matching", "NO_LONGER_MATCHING"), ("Resolved", "RESOLVED"),
           ("Dismissed", "DISMISSED"), ("All", "ALL")]


class CasesPane(Pane):
    columns = ("", "Row", "Monitor", "Routed to", "Opened", "State")
    empty_text = "No cases in this view."
    BINDINGS = [Binding("r", "close('resolve')", "Resolve"), Binding("x", "close('dismiss')", "Dismiss"),
                Binding("n", "annotate", "Add note")]

    def above_list(self) -> ComposeResult:
        yield Select(FILTERS, value="OPEN", allow_blank=False, id="filter")

    @property
    def status_filter(self) -> str | None:
        value = self.query_one("#filter", Select).value
        return None if value in ("ALL", Select.BLANK) else str(value)

    async def load(self) -> None:
        summaries = await self.api.cases(self.status_filter)
        rows = [(c["caseId"], c, (dot(c["status"]), f"[bold]{c['key']}[/]", c["monitorId"], c["routeTo"] or "—",
                                  ago(c["openedAt"]), badge(c["status"])))
                for c in summaries]
        self.fill(rows)

    @on(Select.Changed, "#filter")
    async def _filter_changed(self) -> None:
        await self.reload()

    @property
    def count(self) -> int | None:
        return len(self.items) if self.status_filter == "OPEN" else None

    def show_detail(self) -> None:
        # The list holds summaries; the evidence and timeline come from the case itself.
        if self.selected is None:
            self.query_one("#detail").update("")  # type: ignore[attr-defined]
            return
        self.run_worker(partial(self._load_detail, self.selected["caseId"]), exclusive=True, group="case-detail")

    async def _load_detail(self, case_id: str) -> None:
        try:
            case = await self.api.case(case_id)
        except ApiError as error:
            self.fail(error)
            return
        if self.selected is not None and self.selected["caseId"] == case_id:
            self.query_one("#detail").update(self.describe(case))  # type: ignore[attr-defined]

    def describe(self, case: dict[str, Any]) -> RenderableType:
        head = Text.from_markup(f"[bold]{case['key']}[/]   {badge(case['status'])}")
        sub = Text.from_markup(f"[{DIM}]found by monitor {case['monitorId']} (v{case['monitorVersion']})"
                               f" · routed to {case['routeTo'] or '—'}"
                               + (f" · handled by assistant {case['assistantId']}" if case['assistantId'] else "") + "[/]")
        evidence = Table(title="Evidence", title_justify="left", box=None, expand=True, show_header=False)
        evidence.add_column(style=DIM, no_wrap=True)
        evidence.add_column()
        for name, value in sorted(case["evidence"].items()):
            evidence.add_row(name, cell(value))
        notes = Text("\n".join(f"  {n}" for n in case["sourceNotes"]), style=DIM)

        timeline = Text()
        timeline.append("What happened\n", style="italic")
        for item in case["timeline"]:
            timeline.append(f"{clock(item['at'])}  ", style=DIM)
            timeline.append(item["actor"], style=INFO)
            timeline.append(f"\n    {item['text']}\n")
        closed = case["status"] in ("RESOLVED", "DISMISSED")
        hint = Text.from_markup(f"[{DIM}]This case is closed.[/]" if closed
                                else "[bold]r[/] resolve   [bold]x[/] dismiss   [bold]n[/] add a note")
        return Group(head, sub, Text(), evidence, Text("Sources read", style=DIM), notes, Text(), timeline, Text(), hint)

    # ---- actions

    async def action_close(self, how: str) -> None:
        case = self.selected
        if case is None:
            return
        verb = "Resolve" if how == "resolve" else "Dismiss"

        def done(note: str | None) -> None:
            if note is not None:
                self.run_worker(self._close(case["caseId"], how, note))

        self.app.push_screen(PromptModal(f"{verb} case {case['key']}", "What happened? This goes on the timeline.",
                                         required=False), done)

    async def _close(self, case_id: str, how: str, note: str) -> None:
        try:
            if how == "resolve":
                await self.api.case_resolve(case_id, note or "resolved")
            else:
                await self.api.case_dismiss(case_id, note or "dismissed")
        except ApiError as error:
            self.fail(error)
            return
        await self.reload()

    async def action_annotate(self) -> None:
        case = self.selected
        if case is None:
            return

        def done(text: str | None) -> None:
            if text:
                self.run_worker(self._annotate(case["caseId"], text))

        self.app.push_screen(PromptModal(f"Note on {case['key']}", "Add a line to the timeline."), done)

    async def _annotate(self, case_id: str, text: str) -> None:
        try:
            await self.api.case_annotate(case_id, text)
        except ApiError as error:
            self.fail(error)
            return
        self.show_detail()
