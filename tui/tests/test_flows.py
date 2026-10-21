"""What a person can do in the console, against the demo service."""

from textual.widgets import Button, DataTable, Input, Select, Static, TabbedContent

from helpers import SIZE, text_of


def table(app, pane: str) -> DataTable:
    return app.query_one(f"#{pane}-pane #table", DataTable)


def detail(app, pane: str) -> str:
    return text_of(app.query_one(f"#{pane}-pane #detail", Static))


async def ask(app, pilot) -> None:
    app.action_goto("ask")
    await pilot.pause()
    await pilot.press("ctrl+r")
    await pilot.pause(0.3)


def results(app) -> list[str]:
    grid = app.query_one("#results", DataTable)
    return [str(grid.get_row_at(i)[0]) for i in range(grid.row_count)]


def notices(app) -> list[str]:
    return [str(n.message) for n in app._notifications]


async def give_reason(app, pilot, text: str = "checked and fine", settle: float = 0.5) -> None:
    """Answers the one-line question every decision now asks: why."""
    await pilot.pause(0.3)
    app.screen.query_one("#answer", Input).value = text
    await pilot.click("#ok")
    await pilot.pause(settle)


# ---- identity


async def review(app, pilot, clock, key: str, reason: str = "checked", take: int = 10, settle: float = 0.5) -> None:
    """Opens the selected cluster, reads it for `take` seconds, then decides it."""
    await pilot.press(key)  # the first press opens it
    await pilot.pause(0.3)
    clock.advance(take)
    await pilot.press(key)
    if take >= 5:
        await give_reason(app, pilot, reason, settle)


async def test_confirming_a_link_removes_it_from_the_queue_and_changes_later_answers(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await ask(demo_app, pilot)
        summary = text_of(demo_app.query_one("#summary", Static))
        assert "1 more would match" in summary
        assert "CL-871" not in results(demo_app)

        demo_app.action_goto("identity")
        await pilot.pause()
        assert table(demo_app, "identity").row_count == 3
        await review(demo_app, pilot, clock, "s", "same date of birth and employer")  # the first item is Sam Ortiz
        assert table(demo_app, "identity").row_count == 2

        await ask(demo_app, pilot)
        assert "CL-871" in results(demo_app)
        assert "would match" not in text_of(demo_app.query_one("#summary", Static))


async def test_calling_them_different_people_keeps_the_record_out_for_good(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("identity")
        await pilot.pause()
        await review(demo_app, pilot, clock, "d", "different employers")
        await ask(demo_app, pilot)
        assert "CL-871" not in results(demo_app)
        assert "would match" not in text_of(demo_app.query_one("#summary", Static))


async def test_an_identity_decision_needs_a_reason_and_without_one_nothing_changes(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("identity")
        await pilot.pause()
        await review(demo_app, pilot, clock, "s", "   ", settle=0.2)
        assert "type something" in text_of(demo_app.screen.query_one("#problem", Static))
        await pilot.press("escape")
        await pilot.pause(0.3)
        assert table(demo_app, "identity").row_count == 3


async def test_someone_without_the_reviewer_role_is_refused_and_told_why(make_app):
    app = make_app("olivia", ["operator"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("identity")
        await pilot.pause()
        await pilot.press("s")
        await pilot.pause(0.4)
        assert any("REVIEW" in n for n in notices(app))
        assert table(app, "identity").row_count == 3


# ---- ask


async def test_the_answer_says_where_it_came_from_and_flags_stale_data(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await ask(demo_app, pilot)
        report = text_of(demo_app.query_one("#report", Static))
        assert "claims / Claim" in report and "wealth / Account" in report and "stale" in report
        assert "using data from the last successful read" in report
        assert "older data" in text_of(demo_app.query_one("#summary", Static))
        assert results(demo_app) == ["CL-889", "CL-860"]


async def test_a_question_that_cannot_be_read_shows_why_and_asks_nothing(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("ask")
        await pilot.pause()
        demo_app.query_one("#ask-where", Input).value = "daysOpen about 30"
        await pilot.press("ctrl+r")
        await pilot.pause(0.2)
        assert "daysOpen" in text_of(demo_app.query_one("#ask-problem", Static))
        assert demo_app.query_one("#results", DataTable).row_count == 0


async def test_the_service_refusing_a_question_is_shown_in_place(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("ask")
        await pilot.pause()
        demo_app.query_one("#ask-show", Input).value = "Policy"
        await pilot.press("ctrl+r")
        await pilot.pause(0.3)
        assert "only holds Claim and Account" in text_of(demo_app.query_one("#ask-problem", Static))


async def test_watching_a_question_creates_a_monitor_that_then_appears_in_the_list(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await ask(demo_app, pilot)
        assert not demo_app.query_one("#watch", Button).disabled
        await pilot.press("f2")
        await pilot.pause(0.3)
        demo_app.screen.query_one("#name", Input).value = "wealthy stalled"
        await pilot.click("#ok")
        await pilot.pause(0.5)
        demo_app.action_goto("monitors")
        await pilot.pause()
        assert table(demo_app, "monitors").row_count == 3


async def test_watching_needs_a_name(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await ask(demo_app, pilot)
        await pilot.press("f2")
        await pilot.pause(0.3)
        await pilot.click("#ok")
        await pilot.pause(0.2)
        assert "name" in text_of(demo_app.screen.query_one("#problem", Static)).lower()


# ---- cases


async def test_resolving_a_case_records_who_and_why_and_takes_it_off_the_open_list(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("cases")
        await pilot.pause(0.3)
        assert table(demo_app, "cases").row_count == 2
        assert "AWAITING_APPROVAL" in detail(demo_app, "cases")

        await pilot.press("r")
        await pilot.pause(0.3)
        demo_app.screen.query_one("#answer", Input).value = "attached by phone"
        await pilot.click("#ok")
        await pilot.pause(0.5)
        assert table(demo_app, "cases").row_count == 1

        demo_app.query_one("#filter", Select).value = "RESOLVED"
        await pilot.pause(0.5)
        grid = table(demo_app, "cases")
        keys = [str(grid.get_row_at(i)[1]) for i in range(grid.row_count)]
        assert any("CL-889" in k for k in keys)


async def test_a_case_shows_its_evidence_and_the_story_of_what_happened(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("cases")
        await pilot.pause(0.5)
        text = detail(demo_app, "cases")
        for expected in ("Evidence", "412,000", "What happened", "judged impact = BLOCKED", "playbook claims-watch"):
            assert expected in text


async def test_a_closed_case_offers_no_actions(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("cases")
        await pilot.pause()
        demo_app.query_one("#filter", Select).value = "RESOLVED"
        await pilot.pause(0.5)
        assert "This case is closed" in detail(demo_app, "cases")


async def test_cancelling_the_dialog_changes_nothing(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("cases")
        await pilot.pause(0.3)
        await pilot.press("r")
        await pilot.pause(0.3)
        await pilot.press("escape")
        await pilot.pause(0.3)
        assert table(demo_app, "cases").row_count == 2


# ---- decisions


async def open_approval(app, pilot) -> None:
    """Goes to the approval and opens it for review; the first press is when you are shown it."""
    app.action_goto("decisions")
    await pilot.pause()
    assert table(app, "decisions").row_count == 1
    await pilot.press("a")
    await pilot.pause(0.4)


async def test_the_approval_says_what_is_asked_who_asked_and_what_the_control_expects(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("decisions")
        await pilot.pause(0.3)
        text = detail(demo_app, "decisions")
        for expected in ("Should playbook claims-watch be allowed", "It would send", "CL-889",
                         "Requested by priya.k, who cannot decide it",
                         "The payload is within what the playbook may change", "at least 10 seconds",
                         "a written reason", "approver"):
            assert expected in text


async def test_pressing_approve_opens_the_event_first_and_the_next_press_decides(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await open_approval(demo_app, pilot)
        assert table(demo_app, "decisions").row_count == 1  # opened, not decided
        assert "tyler" in str(table(demo_app, "decisions").get_row_at(0)[4])
        assert "You have had this open" in detail(demo_app, "decisions")

        clock.advance(25)
        await pilot.press("a")
        await give_reason(demo_app, pilot, "routine attachment")
        assert table(demo_app, "decisions").row_count == 0
        demo_app.action_goto("cases")
        await pilot.pause(0.5)
        text = detail(demo_app, "cases")
        assert "approved by you" in text and "succeeded" in text


async def test_deciding_before_the_minimum_review_time_asks_first_and_counts_as_rushed(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await open_approval(demo_app, pilot)
        clock.advance(3)
        await pilot.press("a")
        await pilot.pause(0.3)
        assert "Decide this quickly" in text_of(demo_app.screen.query_one(".dialog-title", Static))
        await pilot.click("#yes")
        await give_reason(demo_app, pilot, "I know this one")
        assert table(demo_app, "decisions").row_count == 0

        demo_app.action_goto("governance")
        await pilot.pause(0.5)
        grid = table(demo_app, "governance")
        row = {str(grid.get_row_at(i)[0]): grid.get_row_at(i) for i in range(grid.row_count)}
        rushed = next(v for k, v in row.items() if "Rubber-Stamp Detector" in k)
        assert str(rushed[4]) == "4"  # three before, and this one


async def test_declining_the_rushed_warning_leaves_the_approval_waiting(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await open_approval(demo_app, pilot)
        clock.advance(2)
        await pilot.press("a")
        await pilot.pause(0.3)
        await pilot.click("#no")
        await pilot.pause(0.3)
        assert table(demo_app, "decisions").row_count == 1


async def test_rejecting_an_action_asks_why_and_records_it(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("decisions")
        await pilot.pause()
        await pilot.press("x")  # opens it
        await pilot.pause(0.4)
        clock.advance(30)
        await pilot.press("x")
        await give_reason(demo_app, pilot, "wrong client")
        assert table(demo_app, "decisions").row_count == 0
        demo_app.action_goto("cases")
        await pilot.pause(0.5)
        assert "rejected by you: wrong client" in detail(demo_app, "cases")


async def test_a_decision_without_a_reason_is_not_taken(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await open_approval(demo_app, pilot)
        clock.advance(30)
        await pilot.press("a")
        await give_reason(demo_app, pilot, " ", settle=0.2)
        assert "type something" in text_of(demo_app.screen.query_one("#problem", Static))
        await pilot.press("escape")
        await pilot.pause(0.3)
        assert table(demo_app, "decisions").row_count == 1


async def test_the_person_who_asked_for_it_cannot_approve_it(make_app, clock):
    app = make_app("priya.k", ["operator", "approver"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await open_approval(app, pilot)
        clock.advance(30)
        await pilot.press("a")
        await give_reason(app, pilot)
        assert any("cannot decide it" in n for n in notices(app))
        assert table(app, "decisions").row_count == 1


async def test_someone_without_the_approver_role_cannot_even_open_it(make_app, clock):
    app = make_app("olivia", ["operator", "reviewer"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("decisions")
        await pilot.pause()
        await pilot.press("a")
        await pilot.pause(0.4)
        assert any("roles" in n for n in notices(app))


async def test_escalating_moves_it_up_and_an_approver_can_no_longer_take_it(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("decisions")
        await pilot.pause()
        await pilot.press("e")
        await give_reason(demo_app, pilot, "needs an admin to look at the payload")
        text = detail(demo_app, "decisions")
        assert "escalated" in text and "admin" in text
        await pilot.press("a")
        await pilot.pause(0.4)
        assert any("roles" in n for n in notices(demo_app))


async def test_sending_it_back_hands_it_to_the_requester(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("decisions")
        await pilot.pause()
        await pilot.press("b")
        await give_reason(demo_app, pilot, "which document?")
        text = detail(demo_app, "decisions")
        assert "sent back" in text and "With its requester" in text


async def test_releasing_it_lets_someone_else_take_it(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        await open_approval(demo_app, pilot)
        await pilot.press("r")
        await pilot.pause(0.4)
        assert str(table(demo_app, "decisions").get_row_at(0)[4]) == "—"


# ---- playbooks


async def test_an_assistant_is_promoted_one_stage_at_a_time_and_going_live_asks_first(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        assert "SHADOW" in detail(demo_app, "playbooks")
        await pilot.press("p")
        await pilot.pause(0.3)
        assert "Put claims-watch live" in text_of(demo_app.screen.query_one(".dialog-title", Static))
        await pilot.press("n")
        await pilot.pause(0.3)
        assert "SHADOW" in detail(demo_app, "playbooks")

        await pilot.press("p")
        await pilot.pause(0.3)
        await pilot.click("#yes")
        await give_reason(demo_app, pilot, "the shadow report looked right", settle=0.6)
        assert "live" in detail(demo_app, "playbooks")


async def test_going_live_without_an_approved_release_is_refused_and_the_refusal_is_shown(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.3)
        await pilot.press("down")
        await pilot.pause(0.5)
        assert "renewal-nudge" in detail(demo_app, "playbooks")
        await pilot.press("p")  # draft -> shadow is allowed: it has a release
        await give_reason(demo_app, pilot, "start recording", settle=0.4)
        await pilot.press("p")  # shadow -> live needs an approved release
        await pilot.pause(0.3)
        await pilot.click("#yes")
        await give_reason(demo_app, pilot, "go live")
        assert any("approved release" in n for n in notices(demo_app))
        assert "SHADOW" in detail(demo_app, "playbooks")


async def test_the_shadow_report_counts_what_it_would_have_done(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        text = detail(demo_app, "playbooks")
        assert "What it would have done" in text and "sends nothing" in text


async def test_approving_the_newest_release_marks_it_approved(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        assert "not approved" in detail(demo_app, "playbooks")
        await pilot.press("a")
        await give_reason(demo_app, pilot, "the playbook only annotates", settle=0.6)
        assert "not approved" not in detail(demo_app, "playbooks")


# ---- monitors and sources


async def test_a_paused_monitor_does_not_run_when_asked_to(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("monitors")
        await pilot.pause()
        await pilot.press("down")  # renewals-60 is paused
        await pilot.pause(0.2)
        await pilot.press("n")
        await pilot.pause(0.3)
        assert any("paused" in n for n in notices(demo_app))


async def test_pausing_and_resuming_a_monitor(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("monitors")
        await pilot.pause()
        assert "running" in detail(demo_app, "monitors")
        await pilot.press("e")
        await pilot.pause(0.4)
        assert "paused" in detail(demo_app, "monitors")


async def test_reading_a_failing_connector_reports_the_failure(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("sources")
        await pilot.pause()
        await pilot.press("down", "down")  # wealth fails in the demo
        await pilot.pause(0.3)
        await pilot.press("f")
        await pilot.pause(0.5)
        assert "HTTP 500" in detail(demo_app, "sources")


async def test_an_inactive_connector_says_there_is_nothing_to_read(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("sources")
        await pilot.pause()
        await pilot.press("down", "down", "down")
        await pilot.pause(0.3)
        await pilot.press("f")
        await pilot.pause(0.3)
        assert any("not active" in n for n in notices(demo_app))


async def test_number_keys_switch_tabs(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.query_one("#sources-pane #table", DataTable).focus()
        await pilot.press("4")
        await pilot.pause()
        assert demo_app.query_one("#tabs", TabbedContent).active == "cases"


# ---- governance


def standing(app) -> dict[str, tuple[str, ...]]:
    grid = table(app, "governance")
    return {str(grid.get_row_at(i)[0]): tuple(str(c) for c in grid.get_row_at(i)[1:]) for i in range(grid.row_count)}


async def test_governance_lists_every_control_with_where_it_stands(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        rows = standing(demo_app)
        assert len(rows) == 57
        rushed = next(v for k, v in rows.items() if "Rubber-Stamp Detector" in k)
        assert "failing" in rushed[1]
        jailbreak = next(v for k, v in rows.items() if "Jailbreak Detector" in k)
        assert "no evidence yet" in jailbreak[1]


async def test_a_control_says_what_it_does_what_proves_it_and_who_decides(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("down", "down")  # the approval control
        await pilot.pause(0.4)
        text = detail(demo_app, "governance")
        for expected in ("Risky Action Approval", "human-system-authorization", "proved by HumanDecisionRecorded", "aws-agentic-scoping",
                         "approver", "at least 10 s of review", "flagged, not enforced"):
            assert expected in text


async def test_a_control_with_no_enforcing_evidence_says_shadow_and_test_records_do_not_count(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press(*["down"] * 9)
        await pilot.pause(0.4)
        assert "Only enforcing evidence counts" in detail(demo_app, "governance")


async def test_an_admin_can_waive_a_control_and_lift_the_waiver(make_app):
    app = make_app("root", ["admin"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press(*["down"] * 9)
        await pilot.press("w")
        await give_reason(app, pilot, "report-only by design")
        assert any("waived" in c[1] for c in standing(app).values())
        await pilot.press("l")
        await pilot.pause(0.5)
        assert not any("waived" in c[1] for c in standing(app).values())


async def test_someone_who_is_not_an_admin_cannot_waive(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("w")
        await give_reason(demo_app, pilot, "no")
        assert any("ADMINISTER" in n for n in notices(demo_app))


async def test_deciding_an_identity_before_the_minimum_review_time_asks_first(demo_app, clock):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("identity")
        await pilot.pause()
        await pilot.press("s")
        await pilot.pause(0.3)
        assert "You have had this open" in detail(demo_app, "identity")
        clock.advance(2)
        await pilot.press("s")
        await pilot.pause(0.3)
        assert "Decide this quickly" in text_of(demo_app.screen.query_one(".dialog-title", Static))
        await pilot.click("#yes")
        await give_reason(demo_app, pilot, "I know them")
        assert table(demo_app, "identity").row_count == 2

        demo_app.action_goto("governance")
        await pilot.pause(0.5)
        rushed = next(v for k, v in standing(demo_app).items() if "Rubber-Stamp Detector" in k)
        assert rushed[3] == "4"


async def test_someone_else_cannot_take_a_cluster_you_have_open(make_app, clock):
    app = make_app("rita", ["reviewer"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("identity")
        await pilot.pause()
        await pilot.press("s")
        await pilot.pause(0.3)
        app.api.subject = "rex"  # a second reviewer at the same service, with a console of their own
        app.query_one("#identity-pane").opened.clear()
        await pilot.press("d")
        await pilot.pause(0.3)
        assert any("claimed by another" in n for n in notices(app))


# ---- halting a playbook


async def test_an_approver_halts_an_assistant_at_once_and_it_shows_as_halted(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        assert "HALTED" not in detail(demo_app, "playbooks")
        await pilot.press("h")
        await give_reason(demo_app, pilot, "it is attaching to the wrong client", settle=0.8)
        assert "HALTED" in detail(demo_app, "playbooks")
        assert any("is halted" in n for n in notices(demo_app))


async def test_someone_who_cannot_approve_can_only_ask_for_a_halt(make_app):
    app = make_app("olivia", ["operator", "reviewer"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("playbooks")
        await pilot.pause(0.5)
        await pilot.press("h")
        await give_reason(app, pilot, "something looks wrong", settle=0.8)
        assert any("takes effect when an approver agrees" in n for n in notices(app))
        assert "HALTED" not in detail(app, "playbooks")


async def test_restarting_needs_a_second_approver(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("playbooks")
        await pilot.pause(0.5)
        await pilot.press("h")
        await give_reason(demo_app, pilot, "stop", settle=0.8)
        await pilot.press("h")
        await give_reason(demo_app, pilot, "cause fixed", settle=0.8)
        assert any("a second approver is needed" in n for n in notices(demo_app))
        assert "HALTED" in detail(demo_app, "playbooks")

        demo_app.api.subject = "carol"  # the second approver, at their own console
        demo_app.action_goto("decisions")
        await pilot.pause(0.5)
        grid = table(demo_app, "decisions")
        rows = [str(grid.get_row_at(i)[0]) for i in range(grid.row_count)]
        restart = next(i for i, r in enumerate(rows) if "Restart" in r)
        grid.move_cursor(row=restart)
        await pilot.pause(0.3)
        await pilot.press("a")  # opens it
        await pilot.pause(0.4)
        await pilot.press("a")
        await give_reason(demo_app, pilot, "agreed")
        demo_app.action_goto("playbooks")
        await pilot.pause(0.8)
        assert "HALTED" not in detail(demo_app, "playbooks")


# ---- asking in words


async def test_a_request_in_words_fills_the_form_and_runs_nothing_until_the_person_does(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("ask")
        await pilot.pause(0.3)
        words = demo_app.query_one("#ask-words", Input)
        words.focus()
        words.value = "open claims over a month for clients with a lot of money"
        demo_app.query_one("#ask-where", Input).value = ""
        await pilot.press("enter")
        await pilot.pause(0.6)

        assert demo_app.query_one("#ask-where", Input).value == "status = OPEN and daysOpen > 30"
        assert demo_app.query_one("#ask-link", Input).value == "Account where balance > 250000"
        assert "Drafted" in text_of(demo_app.query_one("#ask-explain", Static))
        assert demo_app.query_one("#results", DataTable).row_count == 0  # drafting asked nothing of the data

        await pilot.press("ctrl+r")
        await pilot.pause(0.5)
        assert demo_app.query_one("#results", DataTable).row_count == 2


async def test_when_the_model_needs_to_know_more_it_says_so_and_changes_nothing(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("ask")
        await pilot.pause(0.3)
        words = demo_app.query_one("#ask-words", Input)
        words.focus()
        words.value = "the weather in Boston"
        await pilot.press("enter")
        await pilot.pause(0.5)
        assert "The model needs to know" in text_of(demo_app.query_one("#ask-problem", Static))
        assert demo_app.query_one("#ask-show", Input).value == "Claim"


async def test_pressing_enter_on_an_empty_request_asks_what_to_know(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("ask")
        await pilot.pause(0.3)
        demo_app.query_one("#ask-words", Input).focus()
        await pilot.press("enter")
        await pilot.pause(0.3)
        assert "Say what you want to know" in text_of(demo_app.query_one("#ask-problem", Static))


# ---- who said what, and the ledger


async def test_a_control_shows_who_accepted_it_and_who_said_what_counts_as_evidence(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("down", "down")  # the approval control
        await pilot.pause(0.4)
        text = detail(demo_app, "governance")
        for expected in ("Accepted by dana", "Asserted by erin", "HumanDecisionRecorded counts as evidence",
                         "expects override procedure", "Coverage: partial", "missing: override procedure: none attached"):
            assert expected in text
        row = standing(demo_app)
        approval = next(v for k, v in row.items() if "Risky Action Approval" in k)
        assert approval[5] == "yes" and approval[6] == "partial"


async def test_a_control_nobody_accepted_says_so(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        text = detail(demo_app, "governance")
        assert "Nobody has accepted this control" in text and "Nobody has said what counts as evidence" in text


async def test_an_approver_accepts_a_control_with_a_reason(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("a")
        await give_reason(demo_app, pilot, "the owner takes this on")
        assert "Accepted by tyler" in detail(demo_app, "governance")


async def test_checking_the_ledger_says_how_many_records_and_to_keep_the_head(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("i")
        await pilot.pause(0.4)
        notices = " ".join(str(n.message) for n in demo_app._notifications)
        assert "42 records, none altered" in notices and "Keep this head" in notices


# ---- domains and the live feed


async def test_domains_lists_what_each_holds_and_who_reads_it(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("domains")
        await pilot.pause(0.4)
        grid = table(demo_app, "domains")
        assert grid.row_count == 2
        text = detail(demo_app, "domains")
        assert "Holds: Claim" in text and "No other domain may be linked" in text


async def test_live_shows_changes_as_they_are_pushed_newest_first(make_app):
    app = make_app("tyler", ["operator", "reviewer", "approver"])
    app.api.change_interval = 0.05
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("live")
        grid = table(app, "live")
        for _ in range(60):
            await pilot.pause(0.1)
            if grid.row_count >= 3:
                break
        assert grid.row_count >= 3
        first = [str(c) for c in grid.get_row_at(0)]
        assert first[2] in ("Claim", "Account") and first[4] == "claims-feed"


# ---- relationships, documents and questions about the controls


async def test_a_request_that_names_an_account_follows_the_relationship_the_source_declared(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("ask")
        await pilot.pause(0.3)
        words = demo_app.query_one("#ask-words", Input)
        words.focus()
        words.value = "open claims over a month on accounts holding over 250000"
        await pilot.press("enter")
        await pilot.pause(0.6)

        assert demo_app.query_one("#ask-link", Input).value == "Account by HAS_ACCOUNT where balance > 250000"

        await pilot.press("ctrl+r")
        await pilot.pause(0.6)
        assert demo_app.query_one("#results", DataTable).row_count == 3  # the unconfirmed identity link is not needed
        assert "unconfirmed" not in text_of(demo_app.query_one("#summary", Static)).lower()


async def test_a_question_about_the_controls_is_answered_with_the_facts_and_what_is_not_shown(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("q")
        await give_reason(demo_app, pilot, "Can a person override the playbook, and who signed that off?")
        text = detail(demo_app, "governance")
        for expected in ("Dana accepted it", "Facts", "accepted by dana", "Not shown by the records",
                         "Still needed: override procedure: none attached"):
            assert expected in text


async def test_a_question_that_fits_none_of_the_listed_ones_is_handed_back_with_the_list(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("q")
        await give_reason(demo_app, pilot, "What is the weather in Boston?")
        text = detail(demo_app, "governance")
        assert "Which of them do you mean?" in text and "GAPS" in text


async def test_registering_the_expected_document_completes_the_coverage(make_app):
    app = make_app("root", ["admin"])
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("down", "down")  # the approval control
        await pilot.pause(0.4)
        assert "override procedure, no older than 365 days: not held" in detail(app, "governance")

        await pilot.press("r")
        await give_reason(app, pilot, "https://docs.example/override-procedure")

        text = detail(app, "governance")
        assert "held, last current" in text and "Coverage: full" in text
        approval = next(v for k, v in standing(app).items() if "Risky Action Approval" in k)
        assert approval[6] == "full"


async def test_attaching_a_document_to_a_control_with_nothing_asserted_says_so(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.4)
        await pilot.press("r")
        await pilot.pause(0.4)
        notices = " ".join(str(n.message) for n in demo_app._notifications)
        assert "Nothing is asserted about this control" in notices


# ---- glossary


async def test_g_opens_the_glossary_and_shows_a_plain_definition(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.query_one("#sources-pane #table", DataTable).focus()
        await pilot.press("g")
        await pilot.pause()
        assert demo_app.query_one("#tabs", TabbedContent).active == "glossary"
        detail = text_of(demo_app.query_one("#glossary-pane #detail", Static))
        assert "Why it matters" in detail


def test_every_glossary_term_is_defined_once_in_full():
    from contextlayer_tui.panes.glossary import TERMS

    names = [term for term, *_ in TERMS]
    assert len(names) == len(set(names))
    assert all(area and meaning and why for _, area, meaning, why in TERMS)


# ---- the runtime's record


async def test_m_on_a_case_shows_the_model_call_and_the_runtime_verdict_on_it(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("cases")
        await pilot.pause(0.3)
        await pilot.press("m")
        await pilot.pause(0.3)
        shown = " ".join(text_of(s) for s in demo_app.screen.query(Static))
        assert "classifier-agent" in shown and "Allowed labels: BLOCKED, MOVING" in shown
        assert "custom-cl0002" in shown and "passed" in shown
        await pilot.press("escape")
        await pilot.pause()
        assert demo_app.query_one("#tabs", TabbedContent).active == "cases"


async def test_each_control_says_whether_the_signed_contract_holds_it(demo_app):
    async with demo_app.run_test(size=SIZE) as pilot:
        await pilot.pause()
        demo_app.action_goto("governance")
        await pilot.pause(0.3)
        assert "In the bundle in force (bundle v1, signed by dan.m@acme.example)" in detail(demo_app, "governance")
