"""Checks the console's expectations against a real running service.

Skipped unless CONTEXTLAYER_LIVE_URL is set. Seed the service first with scripts/live_demo.py, then:

    CONTEXTLAYER_LIVE_URL=http://localhost:9162 CONTEXTLAYER_LIVE_ORG=demo uv run pytest tests/test_live.py

Each check names the fields a screen reads, so a change in the service's responses fails here with the
name of what went missing instead of breaking a screen.
"""

import os

import pytest

from contextlayer_tui.api import HttpApi, dev_token

URL = os.environ.get("CONTEXTLAYER_LIVE_URL")
ORG = os.environ.get("CONTEXTLAYER_LIVE_ORG", "demo")

pytestmark = pytest.mark.skipif(not URL, reason="CONTEXTLAYER_LIVE_URL is not set")


@pytest.fixture
async def api():
    client = HttpApi(URL, ORG, dev_token("contract-check", ["operator"], ORG))
    yield client
    await client.aclose()


def has(item: dict, *keys: str) -> None:
    missing = [k for k in keys if k not in item]
    assert not missing, f"the service no longer sends {missing} in {sorted(item)}"


async def test_connectors_carry_what_the_systems_screen_reads(api):
    connectors = await api.connectors()
    assert connectors, "seed the service with scripts/live_demo.py first"
    for c in connectors:
        has(c, "name", "mode", "activeVersion", "importedBy", "entities", "actions")
        for e in c["entities"]:
            has(e, "type", "policy", "ttlMinutes", "lastOutcome", "lastRefreshedAt", "stored", "warnings")
        for a in c["actions"]:
            has(a, "name", "risk")


async def test_assistants_carry_what_the_assistants_screen_reads(api):
    assistants = await api.assistants()
    assert assistants
    for a in assistants:
        has(a, "assistantId", "purpose", "owner", "stage", "releaseCount", "approvedCount")
    detail = await api.assistant(assistants[0]["assistantId"])
    has(detail, "assistantId", "purpose", "owner", "stage", "runningReleaseId", "releases")
    for r in detail["releases"]:
        has(r, "releaseId", "approved", "approvedBy")
    for item in await api.assistant_actions(assistants[0]["assistantId"]):
        has(item, "actionKey", "actionType", "status")


async def test_monitors_carry_what_the_monitors_screen_reads(api):
    monitors = await api.monitors()
    assert monitors
    for m in monitors:
        has(m, "monitorId", "name", "enabled", "intervalMinutes", "assistantId", "runCount", "lastRunAt", "lastRunNote")


async def test_a_question_comes_back_with_rows_and_a_freshness_report(api):
    answer = await api.ask({
        "entityType": "Claim",
        "where": [{"field": "status", "op": "EQ", "value": "OPEN"}],
        "links": [], "returnFields": ["Claim.claimId", "Claim.daysOpen"], "limit": 50})
    has(answer, "rows", "unconfirmedLeftOut", "sources")
    assert answer["rows"], "the demo claims should be readable"
    for s in answer["sources"]:
        has(s, "pack", "entityType", "freshness", "lastOutcome", "dataAsOf", "warnings")


async def test_the_identity_queue_and_the_decisions_are_lists_the_screens_can_read(api):
    for cluster in await api.identity_review():
        has(cluster, "rule", "key", "confidence", "memberCount")
    for event in await api.human_events():
        has(event, "id", "state", "control", "subject", "trigger", "ask", "routing", "holder", "sla", "escalation",
            "allowedActions", "rationaleRequired", "minimumReviewSeconds", "claimedAt", "decisions", "history")
        has(event["subject"], "summary", "agent")
        has(event["ask"], "question", "checks")
        has(event["routing"], "roles", "independentOf")
        has(event["sla"], "dueAt", "state", "timeoutBehaviour", "default")
        has(event["escalation"], "level", "chain")


async def test_governance_carries_what_the_governance_screen_reads(api):
    catalog = await api.controls()
    has(catalog, "systemId", "digest", "controls")
    assert catalog["controls"]
    for c in catalog["controls"]:
        has(c, "rowId", "name", "mechanism", "lifecycle", "corpusRef", "summary", "proofEvent", "boundary", "human")
    report = await api.compliance()
    has(report, "catalogDigest", "asOf", "freshnessDays", "controls")
    assert report["catalogDigest"] == catalog["digest"]
    for c in report["controls"]:
        has(c, "controlId", "status", "why", "enforcing", "upheld", "breached", "flagged", "lastEnforcingAt")


async def test_cases_carry_what_the_cases_screen_reads(api):
    for status in (None, "OPEN"):
        for c in await api.cases(status):
            has(c, "caseId", "monitorId", "key", "status", "routeTo", "openedAt")
    cases = await api.cases()
    if not cases:
        pytest.skip("no case yet: the monitor has not found a row")
    detail = await api.case(cases[0]["caseId"])
    has(detail, "caseId", "monitorId", "monitorVersion", "key", "status", "routeTo", "assistantId", "evidence",
        "sourceNotes", "timeline")
    for item in detail["timeline"]:
        has(item, "at", "actor", "text")


async def test_a_refusal_comes_back_as_words_a_person_can_read(api):
    from contextlayer_tui.api import ApiError

    with pytest.raises(ApiError) as caught:
        await api.ask({"entityType": "claim", "where": [], "links": [], "returnFields": ["Claim.claimId"], "limit": 5})
    assert caught.value.status == 400
    assert "entityType" in caught.value.message


async def test_halts_carry_what_the_assistants_screen_reads(api):
    for halt in await api.halts():
        has(halt, "scope", "target", "halted", "by", "eventId")
