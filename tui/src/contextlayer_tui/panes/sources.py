"""Sources: each connector, how it is switched, and how its last read went."""

from __future__ import annotations

from typing import Any

from rich.console import Group, RenderableType
from rich.table import Table
from rich.text import Text
from textual.binding import Binding

from ..api import ApiError
from ..format import DIM, WARN, ago, badge, clip, dot, risk
from .base import Pane

_EXPLAIN_POLICY = {
    "MAINTAINED": "kept current in the background",
    "CACHED": "read again when older than {ttl} min",
    "ON_DEMAND": "read for every question",
}


class SourcesPane(Pane):
    columns = ("", "Source", "Version", "Mode", "Entities", "Last read")
    empty_text = "No connectors yet. Import a connector pack to connect a source."
    BINDINGS = [Binding("f", "refresh_entities", "Read now")]

    async def load(self) -> None:
        connectors = await self.api.connectors()
        rows = []
        for c in connectors:
            outcomes = [e["lastOutcome"] for e in c["entities"]]
            worst = ("FAILED" if "FAILED" in outcomes else "PARTIAL" if "PARTIAL" in outcomes
                     else "NEVER" if "NEVER" in outcomes or not outcomes else "OK")
            health = "INACTIVE" if c["mode"] == "INACTIVE" else worst
            entities = "  ".join(f"{e['type']} {dot(e['lastOutcome'])}" for e in c["entities"]) or "[grey58]—[/]"
            last = max((e["lastRefreshedAt"] for e in c["entities"]), default=0)
            rows.append((c["name"], c, (dot(health), f"[bold]{c['name']}[/]", c["activeVersion"] or "—",
                                        badge(c["mode"]), entities, ago(last))))
        self.fill(rows)

    def describe(self, c: dict[str, Any]) -> RenderableType:
        head = Text.from_markup(f"[bold]{c['name']}[/]  {c['activeVersion'] or ''}   {badge(c['mode'])}")
        by = Text.from_markup(f"[{DIM}]imported by {c['importedBy'] or '—'}[/]")

        entities = Table(title="What it provides", title_justify="left", expand=True, box=None, pad_edge=False)
        for name in ("Type", "Freshness", "Last read", "Stored", "Result"):
            entities.add_column(name)
        for e in c["entities"]:
            policy = _EXPLAIN_POLICY.get(e["policy"], e["policy"]).format(ttl=e["ttlMinutes"])
            entities.add_row(e["type"], policy, ago(e["lastRefreshedAt"]), f"{e['stored']:,}", badge(e["lastOutcome"]))
        if not c["entities"]:
            entities.add_row("[grey58]nothing active[/]", "", "", "", "")

        parts: list[RenderableType] = [head, by, Text(), entities]
        warnings = [(e["type"], w) for e in c["entities"] for w in e["warnings"]]
        if warnings:
            parts += [Text(), Text.from_markup(f"[{WARN}]Warnings[/]")]
            parts += [Text.from_markup(f"  [{WARN}]▲[/] {e}: {clip(w, 90)}") for e, w in warnings]
        if c["actions"]:
            actions = Table(title="What playbooks may ask it to do", title_justify="left", expand=True, box=None)
            actions.add_column("Action")
            actions.add_column("How")
            for a in c["actions"]:
                actions.add_row(a["name"], risk(a["risk"]))
            parts += [Text(), actions]
            if c["mode"] != "LIVE":
                parts.append(Text.from_markup(f"[{DIM}]These stay unavailable until the connector is live.[/]"))
        parts += [Text(), Text.from_markup(f"[{DIM}]The credential is kept by reference and is never shown here.[/]")]
        return Group(*parts)

    async def action_refresh_entities(self) -> None:
        c = self.selected
        if c is None:
            return
        if c["mode"] == "INACTIVE" or not c["entities"]:
            self.app.notify(f"{c['name']} is not active, so there is nothing to read.", severity="warning")
            return
        failed = 0
        for entity in c["entities"]:
            try:
                result = await self.api.refresh_entity(c["name"], entity["type"])
                failed += result["outcome"] == "FAILED"
            except ApiError as error:
                self.fail(error)
                failed += 1
        await self.reload()
        if failed:
            self.app.notify(f"{failed} of {len(c['entities'])} reads failed. See Warnings.", severity="warning")
        else:
            self.app.notify(f"Read {c['name']} just now.", severity="information")
