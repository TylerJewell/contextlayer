"""Live: records as they change, pushed by the systems that own them."""

from __future__ import annotations

import asyncio
from typing import Any

from rich.console import Group, RenderableType
from rich.text import Text
from textual.app import ComposeResult

from ..api import ApiError
from ..format import DIM, OK, WARN, clock
from .base import Pane

MAX_KEPT = 200


class LivePane(Pane):
    """Newest change first. The list is fed by the change feed, not by the refresh timer."""

    columns = ("Time", "Change", "Type", "Record", "From")
    empty_text = "Waiting for a change. Records pushed by a connected system appear here as they arrive."

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.rows: list[tuple[str, dict[str, Any], tuple[Any, ...]]] = []
        self.seen = 0
        self.connected = False

    def on_mount(self) -> None:
        # Pane adds the columns in its own on_mount, which Textual runs as well.
        self.run_worker(self._follow(), exclusive=True, group="changes")

    async def load(self) -> None:
        # The feed writes the table itself; a refresh has nothing to fetch.
        return None

    @property
    def count(self) -> int | None:
        return self.seen or None

    async def _follow(self) -> None:
        delay = 1
        while True:
            try:
                async for change in self.api.changes():
                    self.connected = True
                    delay = 1
                    self._take(change)
            except ApiError:
                pass
            self.connected = False
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30)

    def _take(self, change: dict[str, Any]) -> None:
        self.seen += 1
        key = f"c{self.seen}"
        colour = WARN if change["op"] == "delete" else OK
        record = change.get("entityId") or "(read whole)"
        cells = (clock(change["at"]), f"[{colour}]{change['op']}[/]", change["entityType"], record, change["pack"])
        self.rows.insert(0, (key, change, cells))
        del self.rows[MAX_KEPT:]
        self.fill(list(self.rows))
        self.table.move_cursor(row=0)  # the newest, not wherever the cursor was
        self.show_detail()

    def describe(self, change: dict[str, Any]) -> RenderableType:
        what = {"upsert": "was written", "delete": "was removed", "refreshed": "was read whole on its schedule"}.get(change["op"], change["op"])
        return Group(
            Text.from_markup(f"[bold]{change['entityType']}[/] {change.get('entityId') or ''} {what}"),
            Text.from_markup(f"[{DIM}]pushed through {change['pack']} at {clock(change['at'])}[/]"),
            Text(),
            Text.from_markup(f"[{DIM}]{'Connected to the change feed.' if self.connected else 'Reconnecting to the change feed.'}[/]"),
        )
