"""Domains: which entity types are governed together, and who may read them."""

from __future__ import annotations

from typing import Any

from rich.console import Group, RenderableType
from rich.text import Text

from ..format import DIM, INFO, WARN, clip
from .base import Pane


class DomainsPane(Pane):
    columns = ("Domain", "Types", "Readers")
    empty_text = "No data domains are defined, so every question is allowed."

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.joins: list[dict[str, Any]] = []

    async def load(self) -> None:
        listing = await self.api.domains()
        self.joins = listing["joins"]
        rows = [(d["name"], d, (f"[bold]{d['name']}[/]", ", ".join(d["entityTypes"]), _readers(d)))
                for d in listing["domains"]]
        self.fill(rows)

    @property
    def count(self) -> int | None:
        return len(self.items) or None

    def describe(self, d: dict[str, Any]) -> RenderableType:
        lines: list[RenderableType] = [
            Text.from_markup(f"[bold]{d['name']}[/]   [{DIM}]{clip(d['description'], 60)}[/]"),
            Text(),
            Text(f"Holds: {', '.join(d['entityTypes'])}"),
            Text(f"Readable by roles: {', '.join(d['readerRoles']) or 'none'}"),
            Text(f"Readable by people: {', '.join(d['readerPeople']) or 'none'}"),
            Text.from_markup(f"[{DIM}]last changed by {d['changedBy']}[/]"),
            Text(),
        ]
        linked = [j for j in self.joins if d["name"] in (j["a"], j["b"])]
        if linked:
            for j in linked:
                other = j["b"] if j["a"] == d["name"] else j["a"]
                lines.append(Text.from_markup(f"[{INFO}]May be linked with {other}[/] (approved by {j['approvedBy']}: {clip(j['rationale'], 50)})"))
        else:
            lines.append(Text.from_markup(f"[{WARN}]No other domain may be linked with this one in a question.[/]"))
        lines += [Text(), Text.from_markup(f"[{DIM}]Domains are changed with the command line: akka-context domains[/]")]
        return Group(*lines)


def _readers(d: dict[str, Any]) -> str:
    parts = list(d["readerRoles"]) + [f"@{p}" for p in d["readerPeople"]]
    return ", ".join(parts) or "nobody"
