import pytest

from contextlayer_tui.query import (
    ParseError,
    build_question,
    describe,
    parse_conditions,
    parse_links,
    parse_returns,
    parse_value,
    to_fields,
)


def test_conditions_are_joined_with_and_and_keep_their_types():
    assert parse_conditions("status = OPEN and daysOpen > 30") == [
        {"field": "status", "op": "EQ", "value": "OPEN"},
        {"field": "daysOpen", "op": "GT", "value": 30},
    ]


def test_every_operator_maps_to_the_services_name():
    ops = {"=": "EQ", "!=": "NEQ", ">": "GT", ">=": "GTE", "<": "LT", "<=": "LTE"}
    for symbol, name in ops.items():
        assert parse_conditions(f"a {symbol} 1")[0]["op"] == name


def test_values_are_numbers_booleans_or_text_and_quotes_keep_spaces():
    assert parse_value("41") == 41
    assert parse_value("4.5") == 4.5
    assert parse_value("true") is True
    assert parse_value("OPEN") == "OPEN"
    assert parse_value('"Claims Team B"') == "Claims Team B"
    assert parse_conditions("name = 'Pat Lee'")[0]["value"] == "Pat Lee"


def test_a_value_containing_the_word_and_stays_whole_when_quoted_only_at_the_edges():
    # "and" splits conditions only when surrounded by spaces, so a value like "brand" survives.
    assert parse_conditions("name = brand")[0]["value"] == "brand"


def test_an_unreadable_condition_says_which_one():
    with pytest.raises(ParseError, match="daysOpen"):
        parse_conditions("status = OPEN and daysOpen about 30")


def test_empty_text_means_no_conditions():
    assert parse_conditions("  ") == []


def test_links_carry_their_own_conditions():
    assert parse_links("Account where balance > 250000 ; Client") == [
        {"entityType": "Account", "where": [{"field": "balance", "op": "GT", "value": 250000}]},
        {"entityType": "Client", "where": []},
    ]


def test_a_link_to_something_that_is_not_a_type_is_refused():
    with pytest.raises(ParseError, match="capital"):
        parse_links("account where balance > 1")


def test_returns_must_be_written_type_dot_field():
    assert parse_returns("Claim.claimId, Account.balance") == ["Claim.claimId", "Account.balance"]
    with pytest.raises(ParseError, match="Type.field"):
        parse_returns("claimId")
    with pytest.raises(ParseError, match="say what to return"):
        parse_returns(" , ")


def test_a_whole_question_is_built_and_described():
    q = build_question("Claim", "status = OPEN and daysOpen > 30", "Account where balance > 250000",
                       "Claim.claimId, Account.balance", "50")
    assert q["entityType"] == "Claim" and q["limit"] == 50
    assert q["links"][0]["entityType"] == "Account"
    assert describe(q) == "Claim where status = OPEN and daysOpen > 30 linked to Account where balance > 250000"


def test_the_limit_is_bounded_and_defaults():
    assert build_question("Claim", "", "", "Claim.id", "")["limit"] == 100
    with pytest.raises(ParseError, match="between"):
        build_question("Claim", "", "", "Claim.id", "5000")
    with pytest.raises(ParseError, match="whole number"):
        build_question("Claim", "", "", "Claim.id", "many")


def test_the_entity_type_is_checked():
    with pytest.raises(ParseError, match="Show"):
        build_question("claim", "", "", "Claim.id", "10")


def test_a_drafted_question_written_into_the_form_reads_back_as_the_same_question():
    from contextlayer_tui.query import to_fields

    question = {
        "entityType": "Claim",
        "where": [{"field": "status", "op": "EQ", "value": "OPEN"}, {"field": "daysOpen", "op": "GT", "value": 30},
                  {"field": "region", "op": "EQ", "value": "north east"}, {"field": "code", "op": "EQ", "value": "007"},
                  {"field": "urgent", "op": "EQ", "value": True}],
        "links": [{"entityType": "Account", "where": [{"field": "balance", "op": "GTE", "value": 250000.5}]},
                  {"entityType": "Client", "where": []}],
        "returnFields": ["Claim.claimId", "Account.balance"],
        "limit": 50,
    }
    fields = to_fields(question)

    assert fields["where"] == 'status = OPEN and daysOpen > 30 and region = "north east" and code = "007" and urgent = true'
    assert fields["link"] == "Account where balance >= 250000.5 ; Client"
    assert build_question(fields["show"], fields["where"], fields["link"], fields["ret"], fields["limit"]) == question


def test_a_link_can_follow_a_named_relationship_forwards_or_backwards_for_several_steps():
    links = parse_links("Account by HAS_ACCOUNT where balance > 250000 ; Claim by HAS_ACCOUNT backwards ; Branch by HELD_AT up to 2 steps")

    assert links[0] == {"entityType": "Account", "where": [{"field": "balance", "op": "GT", "value": 250000}],
                        "via": "HAS_ACCOUNT", "direction": "OUT", "depth": 1}
    assert links[1]["direction"] == "IN" and links[1]["via"] == "HAS_ACCOUNT"
    assert links[2]["depth"] == 2


def test_a_link_with_no_relationship_stays_a_link_by_identity():
    assert parse_links("Account where balance > 1") == [
        {"entityType": "Account", "where": [{"field": "balance", "op": "GT", "value": 1}]}]


def test_backwards_and_steps_need_a_relationship_to_go_with():
    with pytest.raises(ParseError):
        parse_links("Account backwards")


def test_a_drafted_question_that_follows_a_relationship_is_shown_in_the_form_as_it_will_run():
    question = {"entityType": "Claim", "where": [], "limit": 50, "returnFields": ["Claim.claimId"],
                "links": [{"entityType": "Account", "where": [], "via": "HAS_ACCOUNT", "direction": "IN", "depth": 2}]}

    fields = to_fields(question)

    assert fields["link"] == "Account by HAS_ACCOUNT backwards up to 2 steps"
    assert parse_links(fields["link"])[0]["via"] == "HAS_ACCOUNT"
    assert "linked to Account by HAS_ACCOUNT backwards up to 2 steps" in describe(question)
