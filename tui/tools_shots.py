"""Render the demo console to PNGs, for looking at the design. Not part of the product."""
import asyncio
import sys

import resvg_py

from contextlayer_tui.app import TABS, ContextLayerApp
from contextlayer_tui.api import HttpApi, dev_token
from contextlayer_tui.demo import DemoApi

OUT = sys.argv[1]


def save(app, name: str) -> None:
    svg = app.export_screenshot(title=name)
    png = resvg_py.svg_to_bytes(svg_string=svg, font_dirs=["C:/Windows/Fonts"], dpi=110)
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(bytes(png))


async def main() -> None:
    live = len(sys.argv) > 2
    roles = ["operator", "reviewer", "approver"]
    api = HttpApi(sys.argv[2], "demo", dev_token("dan.m", roles, "demo")) if live else DemoApi("tyler", roles)
    app = ContextLayerApp(api, "demo" if live else "acme", "Live service" if live else "Acme Insurance (sample data)")
    async with app.run_test(size=(150, 46)) as pilot:
        await pilot.pause()
        for tab_id, _, _ in TABS:
            app.action_goto(tab_id)
            await pilot.pause(0.3)
            if tab_id == "ask":
                await pilot.press("ctrl+r")
                await pilot.pause(0.5)
            save(app, ("live-" if live else "") + tab_id)
        app.action_goto("cases")
        await pilot.pause(0.3)
        await pilot.press("r")
        await pilot.pause(0.3)
        save(app, "modal-resolve")
        await pilot.press("escape")
        app.action_goto("ask")
        await pilot.pause(0.3)
        await pilot.press("f2")
        await pilot.pause(0.3)
        save(app, "modal-watch")


asyncio.run(main())
