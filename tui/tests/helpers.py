"""Shared by the screen tests."""

import io

from rich.console import Console
from textual.widgets import Static

SIZE = (150, 46)


def text_of(widget: Static, width: int = 110) -> str:
    """The words a Static currently shows, without colours, laid out as they would be on screen."""
    visual = widget.render()
    plain = getattr(visual, "plain", None)
    if plain is not None:
        return plain
    console = Console(file=io.StringIO(), width=width, record=True, force_terminal=False, color_system=None)
    console.print(getattr(visual, "_renderable", visual))
    return console.export_text()
