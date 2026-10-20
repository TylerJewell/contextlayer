"""The console: a banner, eleven tabs, and one footer that shows the keys for where you are."""

from __future__ import annotations

import asyncio

from textual import events
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.widgets import Footer, Static, TabbedContent, TabPane

from .api import Api
from .panes.decisions import DecisionsPane
from .panes.ask import AskPane
from .panes.playbooks import PlaybooksPane
from .panes.base import Pane
from .panes.cases import CasesPane
from .panes.domains import DomainsPane
from .panes.glossary import GlossaryPane
from .panes.governance import GovernancePane
from .panes.identity import IdentityPane
from .panes.live import LivePane
from .panes.monitors import MonitorsPane
from .panes.sources import SourcesPane

TABS: list[tuple[str, str, type]] = [
    ("sources", "Sources", SourcesPane),
    ("identity", "Identity", IdentityPane),
    ("ask", "Ask", AskPane),
    ("cases", "Cases", CasesPane),
    ("decisions", "Decisions", DecisionsPane),
    ("playbooks", "Playbooks", PlaybooksPane),
    ("monitors", "Monitors", MonitorsPane),
    ("governance", "Governance", GovernancePane),
    ("domains", "Domains", DomainsPane),
    ("live", "Live", LivePane),
    ("glossary", "Glossary", GlossaryPane),
]

# One sentence under the tab bar for each tab: what the thing the tab holds is.
DEFINITIONS = {
    "sources": "A source is a system of record the context layer reads, such as claims or the CRM, through a "
               "connector that says how to read it.",
    "identity": "An identity link says records from different sources are the same person; a reviewer confirms it "
                "before any question may join them.",
    "ask": "An ask is a question you put to the records of every connected source at once, in words or as conditions; "
           "it never changes anything, and its answer says how up to date each source was.",
    "cases": "A case is something a monitor found for a person to look into; nothing waits on it until someone "
             "resolves or dismisses it.",
    "decisions": "A decision is something that cannot happen until a person says yes or no, with a reason; whoever "
                 "asked for an action cannot approve it.",
    "playbooks": "A playbook is a set of fixed steps, approved by its owner, run on each case a monitor opens for it: "
                 "a model judges, a path is chosen, actions are requested.",
    "monitors": "A monitor is a saved question run on a schedule; each new record that matches opens a case.",
    "governance": "A control is a rule the service promises to keep; its evidence shows whether it is kept, and the "
                  "signed contract shows which rules were agreed.",
    "domains": "A data domain is a group of record types that share one rule about who may read them; two are linked "
               "in a question only under an approved join.",
    "live": "A streamed change is a record a source pushed as it changed, shown the moment it arrives.",
    "glossary": "The glossary defines, in plain words, every term the console uses.",
}

REFRESH_SECONDS = 15


class ContextLayerApp(App):
    CSS_PATH = "console.tcss"
    TITLE = "Context Layer"
    BINDINGS = [
        Binding("f5", "refresh", "Refresh"),
        Binding("q", "quit", "Quit"),
        # The first ten tabs take the number keys; the glossary has its own key.
        *[Binding(str((i + 1) % 10), f"goto('{tab_id}')", label, show=False) for i, (tab_id, label, _) in enumerate(TABS[:10])],
        Binding("g", "goto('glossary')", "Glossary"),
    ]

    def __init__(self, api: Api, org: str, org_title: str | None = None) -> None:
        super().__init__()
        self.api = api
        self.org = org
        self.org_title = org_title or org
        self.reachable = True

    def compose(self) -> ComposeResult:
        yield Static(id="banner")
        with TabbedContent(initial="sources", id="tabs"):
            for tab_id, label, pane_class in TABS:
                with TabPane(label, id=tab_id):
                    yield Static(DEFINITIONS[tab_id], classes="definition", id=f"{tab_id}-definition")
                    yield pane_class(id=f"{tab_id}-pane")
        yield Footer()

    async def on_mount(self) -> None:
        self.draw_banner()
        await self.refresh_all()
        self.set_interval(REFRESH_SECONDS, self.refresh_quietly)

    # ---- refreshing

    def panes(self) -> list[Pane]:
        return [p for p in self.query(Pane)]

    async def refresh_all(self, quiet: bool = False) -> None:
        results = await asyncio.gather(*(p.reload(quiet=quiet) for p in self.panes()))
        self.reachable = all(results)
        self.draw_banner()
        self.draw_tab_counts()

    async def refresh_quietly(self) -> None:
        await self.refresh_all(quiet=True)

    async def action_refresh(self) -> None:
        await self.refresh_all()
        if self.reachable:
            self.notify("Up to date.", timeout=2)

    def draw_banner(self) -> None:
        link = (f"[green]●[/] connected to {self.api.label}" if self.reachable
                else f"[red]✖ cannot reach {self.api.label}[/]")
        self.query_one("#banner", Static).update(
            f"[bold]◆ Context Layer[/]   [grey70]{self.org_title}[/]   [grey50]org {self.org} · you are {self.api.subject} ({', '.join(self.api.roles) or 'no roles'})[/]"
            f"        {link}")

    def draw_tab_counts(self) -> None:
        tabs = self.query_one("#tabs", TabbedContent)
        for tab_id, label, _ in TABS:
            pane = self.query_one(f"#{tab_id}-pane")
            count = pane.count if isinstance(pane, Pane) else None
            tabs.get_tab(tab_id).label = f"{label} ({count})" if count else label

    def action_goto(self, tab_id: str) -> None:
        self.query_one("#tabs", TabbedContent).active = tab_id
        pane = self.query_one(f"#{tab_id}-pane")
        target = pane.query("DataTable, Input")
        if target:
            target.first().focus()

    async def on_tabbed_content_tab_activated(self, event: TabbedContent.TabActivated) -> None:
        event.stop()
        pane_id = f"{event.pane.id}-pane"
        pane = self.query_one(f"#{pane_id}")
        if isinstance(pane, Pane):
            # Whatever another tab did a moment ago (an approval, a closed case) shows up straight away.
            await pane.reload(quiet=True)
        focusable = pane.query("DataTable, Input")
        if focusable:
            focusable.first().focus()

    async def on_unmount(self, event: events.Unmount) -> None:
        close = getattr(self.api, "aclose", None)
        if close is not None:
            await close()
