"""The playbook editor: the steps drawn as a flow on the left, the selected step's form on the right, and what the
service says is wrong underneath. Saving adds a new release, which runs only once the playbook's owner approves it."""

from __future__ import annotations

import copy
from typing import Any

from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen, Screen
from textual.timer import Timer
from textual.widgets import Button, Footer, Input, Label, Select, Static

from . import playbook as pb
from .api import ApiError
from .format import BAD, DIM, OK
from .modals import ConfirmModal

CHECK_DELAY = 0.4  # seconds after the last keystroke before the service is asked to check


class StepKindModal(ModalScreen[str | None]):
    """Asks which kind of step to add."""

    BINDINGS = [Binding("escape", "cancel", "Cancel")]

    def compose(self) -> ComposeResult:
        with Vertical(classes="dialog"):
            yield Static("Add a step", classes="dialog-title")
            yield Static("Judge: a model picks a label.  Choose: go one way or another by a label.\n"
                         "Act: ask to change something in a source.  Note: write on the case timeline.",
                         classes="dialog-body")
            with Horizontal(classes="dialog-buttons"):
                for kind, name in pb.KINDS.items():
                    yield Button(name, id=f"kind-{kind}")
                yield Button("Cancel", id="cancel")

    @on(Button.Pressed)
    def _pressed(self, event: Button.Pressed) -> None:
        button = event.button.id or ""
        self.dismiss(button.removeprefix("kind-") if button.startswith("kind-") else None)

    def action_cancel(self) -> None:
        self.dismiss(None)


class PlaybookEditor(Screen[str | None]):
    """Edits a copy of a playbook's steps. Dismisses with the id of the release it saved, or None."""

    DEFAULT_CSS = """
    PlaybookEditor { layout: vertical; }
    PlaybookEditor #editor-head { height: auto; padding: 0 1; background: $boost; }
    PlaybookEditor #editor-body { height: 1fr; }
    PlaybookEditor #graph-scroll { width: 52; border-right: solid $primary-darken-2; }
    PlaybookEditor #graph { padding: 0 1; }
    PlaybookEditor #form { padding: 0 2; }
    PlaybookEditor #form Label { margin-top: 1; color: $text-muted; }
    PlaybookEditor #problems { height: auto; max-height: 8; padding: 0 1; background: $boost; }
    """

    BINDINGS = [
        Binding("pageup", "select(-1)", "Step above"),
        Binding("pagedown", "select(1)", "Step below"),
        Binding("ctrl+n", "add", "Add step"),
        Binding("ctrl+d", "delete", "Delete step"),
        Binding("ctrl+s", "save", "Save as release"),
        Binding("escape", "close", "Close"),
    ]

    def __init__(self, assistant_id: str, steps: list[dict[str, Any]], based_on: str,
                 schema: list[dict[str, Any]], actions: dict[str, str]) -> None:
        super().__init__()
        self.assistant_id = assistant_id
        self.steps = copy.deepcopy(steps)
        self.saved_definition = pb.definition(self.steps)
        self.based_on = based_on
        self.schema = schema
        self.actions = actions  # action name -> risk class, from the live connectors
        self.selected = 0
        self.problems: list[str] = []
        self._check_timer: Timer | None = None

    # ---- layout

    def compose(self) -> ComposeResult:
        source = f"starting from release {self.based_on}" if self.based_on else "a new playbook"
        yield Static(Text.from_markup(f"[bold]Playbook {self.assistant_id}[/]   [{DIM}]editing {source}; saving adds a "
                                      "release that runs only once the playbook's owner approves it[/]"), id="editor-head")
        with Horizontal(id="editor-body"):
            with VerticalScroll(id="graph-scroll"):
                yield Static(id="graph")
            yield VerticalScroll(id="form")
        yield Static(id="problems")
        yield Footer()

    async def on_mount(self) -> None:
        await self._show_form()
        self._redraw()
        self._check_soon()

    @property
    def api(self):  # noqa: ANN201 - the app owns the client
        return self.app.api  # type: ignore[attr-defined]

    @property
    def dirty(self) -> bool:
        return pb.definition(self.steps) != self.saved_definition

    def _redraw(self) -> None:
        self.query_one("#graph", Static).update(pb.draw(self.steps, self.selected, self.actions))
        if self.problems:
            text = Text("Not yet a playbook the service accepts:\n", style=f"bold {BAD}")
            for problem in self.problems:
                text.append(f"  • {problem}\n", style=BAD)
        else:
            text = Text("✔ The service accepts this playbook. ", style=f"bold {OK}")
            text.append_text(pb.summary(self.steps))
        self.query_one("#problems", Static).update(text)

    # ---- the form for the selected step

    async def _show_form(self) -> None:
        form = self.query_one("#form", VerticalScroll)
        await form.remove_children()
        if not self.steps:
            await form.mount(Static(Text("No steps. Press ctrl+n to add one.", style=DIM)))
            return
        step = self.steps[self.selected]
        kind = step.get("type", "")
        widgets: list[Any] = [Static(Text.from_markup(f"[bold]{pb.KINDS.get(kind, kind)} step[/]")),
                              Label("Step id (lowercase letters, digits and hyphens)"),
                              Input(step.get("id", ""), id="f-id")]
        targets = [(s["id"], s["id"]) for s in self.steps if s.get("id") and s is not step] + [("end", pb.END)]
        if kind == "classify":
            widgets += [Label("What to ask the model"), Input(step.get("instruction", ""), id="f-instruction"),
                        Label("What it may see (Type.field, separated by commas)"),
                        Input(", ".join(step.get("fields") or []), id="f-fields"),
                        Static(Text(self._fields_hint(), style=DIM)),
                        Label("Labels it must choose from (capitals, separated by spaces)"),
                        Input(" ".join(step.get("labels") or []), id="f-labels"),
                        Label("Keep the label as"), Input(step.get("store", ""), id="f-store"),
                        Label("Then"), self._target_select("f-next", step.get("next"), targets)]
        elif kind == "branch":
            stored = sorted({s.get("store") for s in self.steps if s.get("type") == "classify" and s.get("store")})
            widgets += [Label("Choose by the label kept as" + (f" ({', '.join(stored)})" if stored else "")),
                        Input(step.get("var", ""), id="f-var"),
                        Label("Where each label goes (LABEL=step, separated by commas)"),
                        Input(", ".join(f"{k}={v}" for k, v in dict(step.get("cases") or {}).items()), id="f-cases"),
                        Label("Otherwise"), self._target_select("f-otherwise", step.get("otherwise"), targets)]
        elif kind == "act":
            options = [(f"{name}  ({risk.lower().replace('_', ' ')})", name) for name, risk in sorted(self.actions.items())]
            if step.get("actionType") and step["actionType"] not in self.actions:
                options.append((f"{step['actionType']}  (no live connector offers it)", step["actionType"]))
            widgets += [Label("Ask to"),
                        Select(options, value=step.get("actionType") or Select.BLANK, id="f-actionType", allow_blank=True),
                        Label("With ({Type.field} and {label} are filled in)"), Input(step.get("payload", ""), id="f-payload"),
                        Label("Then"), self._target_select("f-next", step.get("next"), targets)]
        elif kind == "annotate":
            widgets += [Label("Write on the case timeline ({Type.field} and {label} are filled in)"),
                        Input(step.get("text", ""), id="f-text"),
                        Label("Then"), self._target_select("f-next", step.get("next"), targets)]
        await form.mount(*widgets)

    @staticmethod
    def _target_select(widget_id: str, value: str | None, targets: list[tuple[str, str]]) -> Select:
        known = {v for _, v in targets}
        options = targets + ([(f"{value} (no such step)", value)] if value and value not in known else [])
        return Select(options, value=value or pb.END, id=widget_id, allow_blank=False)

    def _fields_hint(self) -> str:
        names = [f"{t['entityType']}.{f['name']}" for t in self.schema for f in t["fields"]]
        return ("You can name: " + ", ".join(names)) if names else "No data has been read yet, so no fields are known."

    # ---- editing

    @on(Input.Changed)
    def _input_changed(self, event: Input.Changed) -> None:
        if not self.steps or not event.input.id or not event.input.id.startswith("f-"):
            return
        step, name, value = self.steps[self.selected], event.input.id[2:], event.value
        if name == "id":
            old = step.get("id")
            step["id"] = value.strip()
            for other in self.steps:  # the steps that went to it follow it
                for key in ("next", "otherwise"):
                    if other.get(key) == old:
                        other[key] = step["id"]
                cases = other.get("cases") or {}
                for label, target in list(cases.items()):
                    if target == old:
                        cases[label] = step["id"]
        elif name == "fields":
            step["fields"] = [f.strip() for f in value.split(",") if f.strip()]
        elif name == "labels":
            step["labels"] = [label.strip() for label in value.replace(",", " ").split() if label.strip()]
        elif name == "cases":
            cases = {}
            for part in value.split(","):
                label, _, target = part.partition("=")
                if label.strip():
                    cases[label.strip()] = target.strip()
            step["cases"] = cases
        else:
            step[name] = value
        self._changed()

    @on(Select.Changed)
    def _select_changed(self, event: Select.Changed) -> None:
        if not self.steps or not event.select.id or not event.select.id.startswith("f-"):
            return
        value = event.value
        self.steps[self.selected][event.select.id[2:]] = "" if value is Select.BLANK else str(value)
        self._changed()

    def _changed(self) -> None:
        self._redraw()
        self._check_soon()

    def _check_soon(self) -> None:
        if self._check_timer is not None:
            self._check_timer.stop()
        self._check_timer = self.set_timer(CHECK_DELAY, self._check)

    async def _check(self) -> None:
        try:
            self.problems = await self.api.check_release(self.assistant_id, pb.definition(self.steps))
        except ApiError as error:
            self.problems = [f"the service could not check it: {error.message}"]
        self._redraw()

    # ---- actions

    async def action_select(self, step: int) -> None:
        if not self.steps:
            return
        target = max(0, min(len(self.steps) - 1, self.selected + step))
        if target != self.selected:
            self.selected = target
            await self._show_form()
            self._redraw()

    def action_add(self) -> None:
        def chosen(kind: str | None) -> None:
            if kind:
                self.run_worker(self._add(kind))

        self.app.push_screen(StepKindModal(), chosen)

    async def _add(self, kind: str) -> None:
        at = self.selected + 1 if self.steps else 0
        step_id = pb.free_id(self.steps, kind)
        current = self.steps[self.selected] if self.steps else None
        # The new step goes where the selected one went, and the selected one now goes to it.
        following = current.get("next", pb.END) if current and current.get("type") != "branch" else pb.END
        self.steps.insert(at, pb.new_step(kind, step_id, following or pb.END))
        if current is not None and current.get("type") != "branch":
            current["next"] = step_id
        self.selected = at
        await self._show_form()
        self._changed()

    def action_delete(self) -> None:
        if not self.steps:
            return
        step = self.steps[self.selected]

        def confirmed(ok: bool | None) -> None:
            if ok:
                self.run_worker(self._delete())

        self.app.push_screen(ConfirmModal(f"Delete step {step.get('id')}?",
                                          "Steps that went to it will go where it went.", yes="Delete"), confirmed)

    async def _delete(self) -> None:
        step = self.steps.pop(self.selected)
        onward = pb.targets(step)[-1] if step.get("type") == "branch" else step.get("next", pb.END)
        for other in self.steps:
            for key in ("next", "otherwise"):
                if other.get(key) == step.get("id"):
                    other[key] = onward
            cases = other.get("cases") or {}
            for label, target in list(cases.items()):
                if target == step.get("id"):
                    cases[label] = onward
        self.selected = max(0, min(self.selected, len(self.steps) - 1))
        await self._show_form()
        self._changed()

    async def action_save(self) -> None:
        if not self.dirty:
            self.app.notify("Nothing has changed since the release this started from.", severity="warning")
            return
        await self._check()
        if self.problems:
            self.app.notify("Fix what is listed at the bottom first.", title="Not saved", severity="error")
            return
        try:
            release = await self.api.add_release(self.assistant_id, pb.definition(self.steps))
        except ApiError as error:
            self.app.notify(error.message, title="The service said no", severity="error", timeout=8)
            return
        self.saved_definition = pb.definition(self.steps)
        self.app.notify(f"Saved as release {release['releaseId']}. It runs once the playbook's owner approves it "
                        "(Playbooks tab, a).", timeout=8)
        self.dismiss(release["releaseId"])

    def action_close(self) -> None:
        if not self.dirty:
            self.dismiss(None)
            return
        self.app.push_screen(ConfirmModal("Leave without saving?", "Your changes to this playbook will be lost.",
                                          yes="Leave"), lambda ok: self.dismiss(None) if ok else None)
