"""The playbook flow and the editor, driven against the demo service."""

from textual.widgets import Input, Static

from contextlayer_tui import playbook as pb
from contextlayer_tui.editor import PlaybookEditor
from helpers import SIZE, text_of


def detail(app) -> str:
    return text_of(app.query_one("#playbooks-pane #detail", Static), width=150)


async def open_editor(app, pilot) -> PlaybookEditor:
    app.action_goto("playbooks")
    await pilot.pause(0.5)
    await pilot.press("e")
    await pilot.pause(0.8)
    assert isinstance(app.screen, PlaybookEditor)
    return app.screen


def problems(app) -> str:
    return text_of(app.screen.query_one("#problems", Static), width=150)


# ---- drawing


def test_steps_that_follow_each_other_are_joined_and_other_targets_are_named():
    steps = pb.parse(
        '{"steps":[{"id":"judge","type":"classify","instruction":"How bad?","fields":["Claim.daysOpen"],'
        '"labels":["BAD","FINE"],"store":"impact","next":"route"},'
        '{"id":"route","type":"branch","var":"impact","cases":{"BAD":"act"},"otherwise":"end"},'
        '{"id":"act","type":"act","actionType":"record.update","payload":"x","next":"end"}]}')

    drawn = pb.draw(steps, selected=1, risks={"record.update": "NEEDS_APPROVAL"}).plain

    assert "1 judge" in drawn and "Judge" in drawn and "Choose" in drawn
    assert "▼" in drawn  # judge goes straight on to route
    assert "BAD → act" in drawn and "otherwise → end" in drawn
    assert "record.update  [needs approval]" in drawn
    assert "■ end" in drawn


def test_a_definition_keeps_only_the_fields_of_each_step_type():
    steps = [{"id": "n", "type": "annotate", "text": "hi", "next": "end", "labels": ["X"]}]

    assert pb.definition(steps) == '{"steps": [{"id": "n", "type": "annotate", "text": "hi", "next": "end"}]}'


# ---- the tab


async def test_the_playbooks_tab_draws_the_steps_of_the_newest_release(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        text = detail(demo_app)
        assert "Steps of release 9c01d4e8" in text
        assert "Is the claim blocked by something" in text  # wrapped inside its box
        assert "It may ask to: claims.attachDocument" in text


# ---- the editor


async def test_adding_a_note_and_saving_makes_a_release_that_waits_for_approval(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        editor = await open_editor(demo_app, pilot)
        assert [s["id"] for s in editor.steps] == ["judge", "route", "attach", "note"]

        await pilot.press("ctrl+n")
        await pilot.pause(0.3)
        await pilot.click("#kind-annotate")
        await pilot.pause(0.5)
        assert [s["id"] for s in editor.steps] == ["judge", "note-1", "route", "attach", "note"]
        assert editor.steps[0]["next"] == "note-1" and editor.steps[1]["next"] == "route"

        editor.query_one("#f-text", Input).value = "judged {impact}; told the team"
        await pilot.pause(0.8)
        assert "The service accepts this playbook" in problems(demo_app)

        await pilot.press("ctrl+s")
        await pilot.pause(0.8)
        assert not isinstance(demo_app.screen, PlaybookEditor)
        releases = (await demo_app.api.assistant("claims-watch"))["releases"]
        assert len(releases) == 3 and not releases[-1]["approved"]
        assert "told the team" in releases[-1]["definition"]


async def test_a_playbook_the_service_refuses_is_shown_and_not_saved(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        editor = await open_editor(demo_app, pilot)

        editor.query_one("#f-labels", Input).value = "BLOCKED"
        await pilot.pause(0.8)
        assert "labels must be 2 to 12 names in capitals" in problems(demo_app)

        await pilot.press("ctrl+s")
        await pilot.pause(0.5)
        assert isinstance(demo_app.screen, PlaybookEditor)
        assert len((await demo_app.api.assistant("claims-watch"))["releases"]) == 2


async def test_renaming_a_step_carries_the_steps_that_go_to_it(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        editor = await open_editor(demo_app, pilot)
        await pilot.press("pagedown")
        await pilot.pause(0.3)
        assert editor.steps[editor.selected]["id"] == "route"

        editor.query_one("#f-id", Input).value = "decide"
        await pilot.pause(0.5)
        assert editor.steps[0]["next"] == "decide"
        assert "The service accepts this playbook" in problems(demo_app)


async def test_a_new_playbook_starts_with_one_note_and_saves_as_its_first_release(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        await pilot.press("n")
        await pilot.pause(0.3)
        demo_app.screen.query_one("#answer", Input).value = "late-renewals"
        await pilot.click("#ok")
        await pilot.pause(0.3)
        demo_app.screen.query_one("#answer", Input).value = "Flag renewals that slipped past their date"
        await pilot.click("#ok")
        await pilot.pause(1.0)
        editor = demo_app.screen
        assert isinstance(editor, PlaybookEditor) and editor.assistant_id == "late-renewals"

        editor.query_one("#f-text", Input).value = "renewal is late"
        await pilot.pause(0.8)
        await pilot.press("ctrl+s")
        await pilot.pause(0.8)
        created = await demo_app.api.assistant("late-renewals")
        assert created["stage"] == "DRAFT" and len(created["releases"]) == 1
