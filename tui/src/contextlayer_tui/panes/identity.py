"""Identity review: records the platform thinks may be one person, waiting for a person to say."""

from __future__ import annotations

from typing import Any

from rich.console import Group, RenderableType
from rich.text import Text
from textual.binding import Binding

from .. import format as fmt
from ..api import ApiError
from ..format import DIM, INFO, WARN, pct
from ..modals import ConfirmModal, PromptModal
from .base import Pane


class IdentityPane(Pane):
    columns = ("Matched on", "Key", "Confidence", "Records")
    empty_text = "Nothing is waiting for review."
    BINDINGS = [Binding("s", "decide(True)", "Same person"), Binding("d", "decide(False)", "Different people")]

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        # key -> (when it was opened, the minimum review seconds): what you have been shown, and since when.
        self.opened: dict[str, tuple[int, int]] = {}

    async def load(self) -> None:
        clusters = await self.api.identity_review()
        rows = [(f"{c['rule']}|{c['key']}", c, (c["rule"], c["key"], pct(c["confidence"]), str(c["memberCount"])))
                for c in clusters]
        self.opened = {k: v for k, v in self.opened.items() if any(k == key for key, _, _ in rows)}
        self.fill(rows)

    @property
    def count(self) -> int | None:
        return len(self.items)

    def describe(self, c: dict[str, Any]) -> RenderableType:
        lines: list[RenderableType] = [
            Text.from_markup(f"[bold]Possible match[/]   [{WARN}]{pct(c['confidence'])} sure[/]"),
            Text.from_markup(f"[{DIM}]matched on {c['rule']}: {c['key']}[/]"),
            Text(),
            Text("These records share the same details:"),
        ]
        members = c.get("members") or [f"{c['memberCount']} records"]
        lines += [Text.from_markup(f"  • {m}") for m in members]
        key = f"{c['rule']}|{c['key']}"
        if key in self.opened:
            seconds = max(0, (fmt.now_ms() - self.opened[key][0]) // 1000)
            lines += [Text(), Text.from_markup(f"[bold]You have had this open for {seconds} seconds.[/]"
                                               + (f" [{WARN}]Keep reading.[/]" if seconds < self.opened[key][1] else ""))]
        else:
            lines += [Text(), Text.from_markup(f"[{INFO}]The first press opens it for you; the next one decides. "
                                               "Your review time is recorded.[/]")]
        lines += [
            Text(),
            Text("The platform will not use this link until you decide. Answers that would depend on it are"
                 " left out and counted."),
            Text(),
            Text.from_markup("[bold]s[/] same person     [bold]d[/] different people"),
            Text.from_markup(f"[{DIM}]You can undo a link later; everything built on it is checked again.[/]"),
        ]
        return Group(*lines)

    async def action_decide(self, same_person: bool) -> None:
        c = self.selected
        if c is None:
            return
        key = f"{c['rule']}|{c['key']}"
        if key not in self.opened:
            try:
                opened = await self.api.identity_open(c["rule"], c["key"])
            except ApiError as error:
                self.fail(error)
                return
            self.opened[key] = (fmt.now_ms(), opened["minimumReviewSeconds"])
            self.app.notify("Opened for you. Read it, then press the same key again to decide.")
            self.show_detail()
            return
        held = max(0, (fmt.now_ms() - self.opened[key][0]) // 1000)
        needed = self.opened[key][1]
        if held < needed:
            message = (f"You have had this open for {held} seconds and the control expects {needed}.\n\n"
                       "You may decide now; it will be recorded as below the minimum review time.")
            self.app.push_screen(ConfirmModal("Decide this quickly?", message, yes="Decide anyway"),
                                 lambda ok: self._ask_why(c, same_person) if ok else None)
        else:
            self._ask_why(c, same_person)

    def _ask_why(self, c: dict[str, Any], same_person: bool) -> None:
        def answered(why: str | None) -> None:
            if why is not None:
                self.run_worker(self._decide(c, same_person, why))

        self.app.push_screen(PromptModal("Same person?" if same_person else "Different people?",
                                         "Why? This goes on the record with your name and role."), answered)

    async def _decide(self, c: dict[str, Any], same_person: bool, why: str) -> None:
        try:
            await self.api.identity_decide(c["rule"], c["key"], same_person, why)
        except ApiError as error:
            self.fail(error)
            return
        self.opened.pop(f"{c['rule']}|{c['key']}", None)
        self.app.notify("Linked as one person." if same_person else "Kept as different people.")
        await self.reload()
