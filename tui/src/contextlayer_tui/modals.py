"""Small dialogs: a question that needs a typed answer, a yes/no, the save-as-monitor form, and a page to read."""

from __future__ import annotations

from rich.console import RenderableType
from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, Static


class PromptModal(ModalScreen[str | None]):
    """Asks for one line of text. Returns it, or None when cancelled."""

    BINDINGS = [Binding("escape", "cancel", "Cancel")]

    def __init__(self, title: str, label: str, placeholder: str = "", required: bool = True) -> None:
        super().__init__()
        self._title, self._label, self._placeholder, self._required = title, label, placeholder, required

    def compose(self) -> ComposeResult:
        with Vertical(classes="dialog"):
            yield Static(self._title, classes="dialog-title")
            yield Label(self._label)
            yield Input(placeholder=self._placeholder, id="answer")
            yield Static("", id="problem", classes="problem")
            with Horizontal(classes="dialog-buttons"):
                yield Button("OK", variant="primary", id="ok")
                yield Button("Cancel", id="cancel")

    def on_mount(self) -> None:
        self.query_one("#answer", Input).focus()

    def _finish(self) -> None:
        text = self.query_one("#answer", Input).value.strip()
        if self._required and not text:
            self.query_one("#problem", Static).update("Please type something.")
            return
        self.dismiss(text)

    @on(Input.Submitted)
    def _submitted(self) -> None:
        self._finish()

    @on(Button.Pressed, "#ok")
    def _ok(self) -> None:
        self._finish()

    @on(Button.Pressed, "#cancel")
    def action_cancel(self) -> None:
        self.dismiss(None)


class ConfirmModal(ModalScreen[bool]):
    """A yes/no question. Returns True only for yes."""

    BINDINGS = [Binding("escape", "no", "No"), Binding("y", "yes", "Yes"), Binding("n", "no", "No")]

    def __init__(self, title: str, message: str, yes: str = "Yes") -> None:
        super().__init__()
        self._title, self._message, self._yes = title, message, yes

    def compose(self) -> ComposeResult:
        with Vertical(classes="dialog"):
            yield Static(self._title, classes="dialog-title")
            yield Static(self._message, classes="dialog-body")
            with Horizontal(classes="dialog-buttons"):
                yield Button(self._yes, variant="primary", id="yes")
                yield Button("Cancel", id="no")

    def on_mount(self) -> None:
        self.query_one("#no", Button).focus()

    @on(Button.Pressed, "#yes")
    def action_yes(self) -> None:
        self.dismiss(True)

    @on(Button.Pressed, "#no")
    def action_no(self) -> None:
        self.dismiss(False)


class MonitorModal(ModalScreen[dict | None]):
    """Collects what is needed to run the current question on a schedule."""

    BINDINGS = [Binding("escape", "cancel", "Cancel")]

    def __init__(self, return_fields: list[str]) -> None:
        super().__init__()
        self._return_fields = return_fields

    def compose(self) -> ComposeResult:
        with Vertical(classes="dialog wide"):
            yield Static("Watch this question", classes="dialog-title")
            yield Static("Run it on a schedule and open a case for each row that newly matches.", classes="dialog-body")
            yield Label("Name")
            yield Input(placeholder="stalled-claims", id="name")
            yield Label(f"Row key (one of: {', '.join(self._return_fields)})")
            yield Input(value=self._return_fields[0] if self._return_fields else "", id="key")
            yield Label("Run every (minutes)")
            yield Input(value="15", id="every")
            yield Label("Playbook that handles new cases (optional)")
            yield Input(placeholder="claims-watch", id="assistant")
            yield Label("Route cases to")
            yield Input(placeholder="Claims Team B", id="route")
            yield Static("", id="problem", classes="problem")
            with Horizontal(classes="dialog-buttons"):
                yield Button("Start watching", variant="primary", id="ok")
                yield Button("Cancel", id="cancel")

    def on_mount(self) -> None:
        self.query_one("#name", Input).focus()

    def _value(self, id_: str) -> str:
        return self.query_one(f"#{id_}", Input).value.strip()

    @on(Button.Pressed, "#ok")
    def _ok(self) -> None:
        name, key, every = self._value("name"), self._value("key"), self._value("every")
        problem = self.query_one("#problem", Static)
        if not name:
            problem.update("Give the monitor a name.")
        elif key not in self._return_fields:
            problem.update("The row key must be one of the returned fields.")
        elif not every.isdigit() or int(every) < 1:
            problem.update("Every: a whole number of minutes, at least 1.")
        else:
            self.dismiss({"name": name, "keyField": key, "intervalMinutes": int(every),
                          "assistantId": self._value("assistant"), "routeTo": self._value("route")})

    @on(Button.Pressed, "#cancel")
    def action_cancel(self) -> None:
        self.dismiss(None)


class InfoModal(ModalScreen[None]):
    """Shows something to read. Any of escape, enter or the button closes it."""

    BINDINGS = [Binding("escape", "close", "Close"), Binding("enter", "close", "Close")]

    def __init__(self, title: str, body: RenderableType) -> None:
        super().__init__()
        self._title, self._body = title, body

    def compose(self) -> ComposeResult:
        with Vertical(classes="dialog wide"):
            yield Static(self._title, classes="dialog-title")
            with VerticalScroll(classes="dialog-body"):
                yield Static(self._body)
            with Horizontal(classes="dialog-buttons"):
                yield Button("Close", variant="primary", id="close")

    def on_mount(self) -> None:
        self.query_one("#close", Button).focus()

    @on(Button.Pressed, "#close")
    def action_close(self) -> None:
        self.dismiss(None)
