"""What every tab shares: a list on the left, a detail view on the right, and one way to fail."""

from __future__ import annotations

from typing import Any

from rich.console import RenderableType
from textual import on
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import DataTable, Static

from ..api import ApiError


class Pane(Vertical):
    """A tab. Subclasses set `columns`, fill the table in `load`, and draw one item in `describe`."""

    columns: tuple[str, ...] = ()
    empty_text = "Nothing here yet."

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.items: dict[str, dict[str, Any]] = {}

    # ---- layout

    def compose(self) -> ComposeResult:
        with Horizontal(classes="split"):
            with Vertical(classes="list"):
                yield from self.above_list()
                table = DataTable(cursor_type="row", zebra_stripes=True, id="table")
                yield table
                yield Static("", classes="empty", id="empty")
            with VerticalScroll(classes="detail"):
                yield Static("", id="detail")

    def above_list(self) -> ComposeResult:
        """Widgets that sit over the table, such as a filter."""
        yield from ()

    def on_mount(self) -> None:
        self.table.add_columns(*self.columns)

    @property
    def table(self) -> DataTable:
        return self.query_one("#table", DataTable)

    @property
    def api(self):  # noqa: ANN201 - the app owns the client
        return self.app.api  # type: ignore[attr-defined]

    # ---- data

    async def reload(self, quiet: bool = False) -> bool:
        """Fetch and redraw; returns whether the service answered.

        A failure is shown to the person and never crashes the console. A background refresh is
        quiet: it only changes the banner, so a service that is down does not raise a notice every
        few seconds.
        """
        try:
            await self.load()
        except ApiError as error:
            if not quiet:
                self.fail(error)
            return False
        return True

    async def load(self) -> None:
        raise NotImplementedError

    def fill(self, rows: list[tuple[str, dict[str, Any], tuple[Any, ...]]]) -> None:
        """Replace the table's rows, keeping the cursor on the same item when it is still there."""
        keep = self.selected_key
        self.items = {key: item for key, item, _ in rows}
        self.table.clear()
        for key, _, cells in rows:
            self.table.add_row(*cells, key=key)
        self.query_one("#empty", Static).update("" if rows else f"[grey58]{self.empty_text}[/]")
        if rows:
            index = next((i for i, (key, _, _) in enumerate(rows) if key == keep), 0)
            self.table.move_cursor(row=index)
        self.show_detail()

    @property
    def selected_key(self) -> str | None:
        if self.table.row_count == 0:
            return None
        try:
            return self.table.coordinate_to_cell_key(self.table.cursor_coordinate).row_key.value
        except Exception:  # an empty or mid-redraw table has no cursor row
            return None

    @property
    def selected(self) -> dict[str, Any] | None:
        key = self.selected_key
        return self.items.get(key) if key is not None else None

    @on(DataTable.RowHighlighted)
    def _highlighted(self, event: DataTable.RowHighlighted) -> None:
        event.stop()
        self.show_detail()

    def show_detail(self) -> None:
        item = self.selected
        text: RenderableType = self.describe(item) if item is not None else ""
        self.query_one("#detail", Static).update(text)

    def describe(self, item: dict[str, Any]) -> RenderableType:
        raise NotImplementedError

    # ---- failures and counts

    def fail(self, error: ApiError) -> None:
        self.app.notify(error.message, title="The service said no", severity="error", timeout=8)

    @property
    def count(self) -> int | None:
        """A number for the tab label, or None for none."""
        return None
