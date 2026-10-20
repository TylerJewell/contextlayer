"""Ask a question across the connected sources, and see how fresh each source was."""

from __future__ import annotations

import re
from typing import Any

from rich.console import Group, RenderableType
from rich.table import Table
from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Button, DataTable, Input, Label, Static

from ..api import ApiError
from ..format import BAD, DIM, INFO, OK, WARN, ago, badge, cell
from ..modals import MonitorModal
from ..query import ParseError, build_question, describe, to_fields

DEFAULTS = {
    "show": "Claim",
    "where": "status = OPEN and daysOpen > 30",
    "link": "Account where balance > 250000",
    "ret": "Claim.claimId, Claim.daysOpen, Account.balance",
    "limit": "100",
}


class AskPane(Vertical):
    BINDINGS = [Binding("ctrl+r", "run", "Run question"), Binding("f2", "watch", "Watch it"),
                Binding("ctrl+d", "draft", "Draft from words")]

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.question: dict[str, Any] | None = None
        self.answer: dict[str, Any] | None = None

    def compose(self) -> ComposeResult:
        with Horizontal(classes="ask-top"):
            with Vertical(classes="ask-form"):
                yield Static("Ask across the connected sources. Join conditions with [bold]and[/]; "
                             "use = != > >= < <=. Separate links with ;", classes="hint")
                yield from self._row("In words", "ask-words", "", "claims over 30 days for wealthy clients, then press Enter")
                yield Static("", id="ask-explain", classes="hint")
                yield from self._row("Show", "ask-show", DEFAULTS["show"], "Claim")
                yield from self._row("Where", "ask-where", DEFAULTS["where"], "status = OPEN and daysOpen > 30")
                yield from self._row("Linked to", "ask-link", DEFAULTS["link"], "Account where balance > 250000")
                yield from self._row("Return", "ask-return", DEFAULTS["ret"], "Claim.claimId, Account.balance")
                with Horizontal(classes="ask-row"):
                    yield Label("Limit", classes="row-label")
                    yield Input(DEFAULTS["limit"], id="ask-limit", classes="short", compact=True)
                    yield Button("Run  ctrl+r", variant="primary", id="run", compact=True)
                    yield Button("Watch it  F2", id="watch", disabled=True, compact=True)
                yield Static("", id="ask-problem", classes="problem")
            with VerticalScroll(classes="ask-report"):
                yield Static("[grey58]Run a question to see where its answer came from.[/]", id="report")
        yield Static("", id="summary", classes="summary")
        yield DataTable(cursor_type="row", zebra_stripes=True, id="results")

    @staticmethod
    def _row(label: str, id_: str, value: str, placeholder: str) -> ComposeResult:
        with Horizontal(classes="ask-row"):
            yield Label(label, classes="row-label")
            yield Input(value, id=id_, placeholder=placeholder, compact=True)

    # ---- running

    @on(Input.Submitted)
    async def _submitted(self, event: Input.Submitted) -> None:
        if event.input.id == "ask-words":
            await self.action_draft()
        else:
            await self.action_run()

    async def action_draft(self) -> None:
        """Turns what is typed in words into the form's fields. It does not run anything: read it, then run it."""
        problem = self.query_one("#ask-problem", Static)
        explain = self.query_one("#ask-explain", Static)
        text = self._text("#ask-words").strip()
        if not text:
            problem.update(f"[{WARN}]Say what you want to know in the first box, then press Enter.[/]")
            return
        problem.update(f"[{DIM}]Asking the model to draft a question… it sees the shape of the data, never records.[/]")
        explain.update("")
        try:
            drafted = await self.app.api.draft_question(text)  # type: ignore[attr-defined]
        except ApiError as error:
            problem.update(f"[{BAD}]{error.message}[/]")
            return
        if drafted.get("clarification"):
            problem.update(f"[{WARN}]The model needs to know: {drafted['clarification']}[/]")
            return
        if drafted.get("problems") or not drafted.get("question"):
            reasons = "; ".join(drafted.get("problems") or ["no question could be drafted"])
            problem.update(f"[{BAD}]Could not draft a question: {reasons}[/]")
            return
        fields = to_fields(drafted["question"])
        for field_id, key in (("#ask-show", "show"), ("#ask-where", "where"), ("#ask-link", "link"),
                              ("#ask-return", "ret"), ("#ask-limit", "limit")):
            self.query_one(field_id, Input).value = fields[key]
        problem.update("")
        explain.update(f"[{INFO}]Drafted: {drafted.get('explanation') or 'read the fields below'}[/]")
        self.app.notify("Read the question, change anything, then press ctrl+r to run it.", title="Drafted", timeout=6)
        self.query_one("#ask-return", Input).focus()

    @on(Button.Pressed, "#run")
    async def _run_pressed(self) -> None:
        await self.action_run()

    @on(Button.Pressed, "#watch")
    async def _watch_pressed(self) -> None:
        await self.action_watch()

    def _text(self, id_: str) -> str:
        return self.query_one(id_, Input).value

    async def action_run(self) -> None:
        problem = self.query_one("#ask-problem", Static)
        try:
            question = build_question(self._text("#ask-show"), self._text("#ask-where"), self._text("#ask-link"),
                                      self._text("#ask-return"), self._text("#ask-limit"))
        except ParseError as error:
            problem.update(f"[{BAD}]{error}[/]")
            return
        problem.update("")
        self.query_one("#summary", Static).update(f"[{DIM}]Asking…[/]")
        try:
            answer = await self.app.api.ask(question)  # type: ignore[attr-defined]
        except ApiError as error:
            self.query_one("#summary", Static).update("")
            problem.update(f"[{BAD}]{error.message}[/]")
            return
        self.question, self.answer = question, answer
        self.query_one("#watch", Button).disabled = False
        self.show(question, answer)

    def show(self, question: dict[str, Any], answer: dict[str, Any]) -> None:
        fields = question["returnFields"]
        table = self.query_one("#results", DataTable)
        table.clear(columns=True)
        table.add_columns(*fields)
        for row in answer["rows"]:
            table.add_row(*(cell(row.get(f)) for f in fields))

        rows = len(answer["rows"])
        left_out = answer["unconfirmedLeftOut"]
        summary = [f"[bold]{rows} {'row' if rows == 1 else 'rows'}[/]  [{DIM}]{describe(question)}[/]"]
        if left_out:
            summary.append(f"[{WARN}]▲ {left_out} more would match through links still waiting for review"
                           f" (Identity tab); they are left out.[/]")
        if any(s["freshness"] in ("STALE", "UNAVAILABLE") for s in answer["sources"]):
            summary.append(f"[{WARN}]▲ Part of this answer used older data or could not be read. See the report.[/]")
        self.query_one("#summary", Static).update("\n".join(summary))
        self.query_one("#report", Static).update(self.report(answer))

    @staticmethod
    def report(answer: dict[str, Any]) -> RenderableType:
        table = Table(title="Where the answer came from", title_justify="left", box=None, expand=True)
        for name in ("Source", "How fresh", "Data as of"):
            table.add_column(name)
        notes: list[RenderableType] = []
        for s in answer["sources"]:
            table.add_row(f"{s['pack'] or '—'} / {s['entityType']}", badge(s["freshness"]), ago(s["dataAsOf"]))
            for w in s["warnings"]:
                colour = BAD if s["freshness"] == "UNAVAILABLE" else WARN
                notes.append(Text.from_markup(f"  [{colour}]▲[/] {s['entityType']}: {w}"))
        if not notes:
            notes.append(Text.from_markup(f"  [{OK}]Every source answered.[/]"))
        return Group(table, *notes)

    # ---- watching

    async def action_watch(self) -> None:
        if self.question is None:
            self.app.notify("Run the question first.", severity="warning")
            return

        def done(result: dict | None) -> None:
            if result is not None:
                self.run_worker(self._create_monitor(result))

        self.app.push_screen(MonitorModal(self.question["returnFields"]), done)

    async def _create_monitor(self, form: dict[str, Any]) -> None:
        monitor_id = re.sub(r"[^a-z0-9-]+", "-", form["name"].lower()).strip("-") or "monitor"
        body = {"name": form["name"], "question": self.question, "keyField": form["keyField"],
                "intervalMinutes": form["intervalMinutes"], "assistantId": form["assistantId"],
                "routeTo": form["routeTo"]}
        try:
            await self.app.api.create_monitor(monitor_id, body)  # type: ignore[attr-defined]
        except ApiError as error:
            self.app.notify(error.message, title="The service said no", severity="error", timeout=8)
            return
        self.app.notify(f"Watching '{form['name']}'. New matches open cases.")
        await self.app.refresh_all()  # type: ignore[attr-defined]
