from textual.widgets import DataTable, Static, TabbedContent

from contextlayer_tui.app import TABS
from helpers import SIZE, text_of


async def test_every_tab_draws_with_the_demo_data(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        for tab_id, _, _ in TABS:
            demo_app.action_goto(tab_id)
            await pilot.pause()
            assert demo_app.query_one("#tabs", TabbedContent).active == tab_id


async def test_the_banner_names_the_organization_and_the_connection(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        banner = text_of(demo_app.query_one("#banner", Static))
        assert "Acme Insurance" in banner and "connected" in banner and "tyler" in banner
        assert "operator, reviewer, approver" in banner


async def test_tab_labels_carry_the_counts_that_need_attention(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        tabs = demo_app.query_one("#tabs", TabbedContent)
        assert "(3)" in str(tabs.get_tab("identity").label)
        assert "(2)" in str(tabs.get_tab("cases").label)
        assert "(1)" in str(tabs.get_tab("decisions").label)
        assert "(1)" in str(tabs.get_tab("governance").label)  # one control is failing


async def test_systems_lists_every_connector_and_marks_the_inactive_one(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        table = demo_app.query_one("#sources-pane #table", DataTable)
        assert table.row_count == 4
        detail = text_of(demo_app.query_one("#sources-pane #detail", Static))
        assert "claims" in detail and "What it provides" in detail


async def test_every_tab_says_what_the_thing_it_holds_is(demo_app):
    from contextlayer_tui.app import DEFINITIONS

    assert set(DEFINITIONS) == {tab_id for tab_id, _, _ in TABS}
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause()
        assert text_of(demo_app.query_one("#playbooks-definition", Static)).startswith("A playbook is ")
