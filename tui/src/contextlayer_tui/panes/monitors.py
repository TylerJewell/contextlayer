"""Watchers: questions that run on a schedule and open cases."""

from __future__ import annotations

from typing import Any

from rich.console import Group, RenderableType
from rich.text import Text
from textual.binding import Binding

from ..api import ApiError
from ..format import DIM, OK, WARN, ago, clip, dot
from .base import Pane


def every(minutes: int) -> str:
    if minutes % 1440 == 0:
        return f"{minutes // 1440} d"
    if minutes % 60 == 0:
        return f"{minutes // 60} hr"
    return f"{minutes} min"


class MonitorsPane(Pane):
    columns = ("", "Watcher", "State", "Every", "Handled by", "Runs", "Last run", "Result")
    empty_text = "No watchers. Ask a question and press F2 to watch it."
    BINDINGS = [Binding("n", "run_now", "Run now"), Binding("e", "toggle", "Pause / resume")]

    async def load(self) -> None:
        monitors = await self.api.monitors()
        rows = []
        for m in monitors:
            state = "OPEN" if m["enabled"] else "INACTIVE"
            rows.append((m["monitorId"], m, (dot(state), f"[bold]{m['name']}[/]", "running" if m["enabled"] else "paused",
                                             every(m["intervalMinutes"]), m["assistantId"] or "a person", f"{m['runCount']:,}",
                                             ago(m["lastRunAt"]), clip(m["lastRunNote"] or "—", 44))))
        self.fill(rows)

    def describe(self, m: dict[str, Any]) -> RenderableType:
        incomplete = m["lastRunNote"].startswith("incomplete")
        lines: list[RenderableType] = [
            Text.from_markup(f"[bold]{m['name']}[/]   " + (f"[{OK}]● running[/]" if m["enabled"] else f"[{DIM}]○ paused[/]")),
            Text.from_markup(f"[{DIM}]every {every(m['intervalMinutes'])} · handled by "
                             f"{m['assistantId'] or 'a person'} · {m['runCount']:,} runs[/]"),
            Text(),
            Text(f"Last run {ago(m['lastRunAt'])}: {m['lastRunNote'] or '—'}"),
        ]
        if incomplete:
            lines += [Text.from_markup(f"[{WARN}]An incomplete run can open cases but never closes one: a row missing "
                                       f"only because a source failed has not stopped matching.[/]")]
        lines += [Text(), Text.from_markup("[bold]n[/] run now     [bold]e[/] pause or resume")]
        return Group(*lines)

    async def action_run_now(self) -> None:
        m = self.selected
        if m is None:
            return
        try:
            result = await self.api.monitor_run(m["monitorId"])
        except ApiError as error:
            self.fail(error)
            return
        if result.get("status") == "SKIPPED":
            self.app.notify(f"{m['name']} is paused, so nothing ran.", severity="warning")
        else:
            self.app.notify(f"Running {m['name']} now.")
        await self.reload()

    async def action_toggle(self) -> None:
        m = self.selected
        if m is None:
            return
        try:
            await self.api.monitor_set_enabled(m["monitorId"], not m["enabled"])
        except ApiError as error:
            self.fail(error)
            return
        self.app.notify(f"{m['name']} is {'paused' if m['enabled'] else 'running again'}.")
        await self.reload()
