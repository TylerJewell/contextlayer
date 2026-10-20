"""The client's side of the contract: where it sends things, who it says it is, how it reports refusals."""

import json

import httpx
import pytest
from textual.widgets import Static

from contextlayer_tui.api import ApiError, HttpApi, dev_token, read_token
from contextlayer_tui.app import ContextLayerApp
from helpers import SIZE, text_of

TOKEN = dev_token("tyler", ["operator", "approver"], "acme")


def client_for(handler) -> HttpApi:
    transport = httpx.MockTransport(handler)
    http = httpx.AsyncClient(transport=transport, base_url="http://svc", headers={"Authorization": f"Bearer {TOKEN}"})
    return HttpApi("http://svc", "acme", TOKEN, client=http)


def app_for(api: HttpApi) -> ContextLayerApp:
    return ContextLayerApp(api, org="acme")


def test_a_dev_token_names_who_you_are_and_what_you_may_do():
    subject, roles = read_token(dev_token("tyler", ["operator", "approver"], "acme"))
    assert (subject, roles) == ("tyler", ["operator", "approver"])
    claims = json.loads(__import__("base64").urlsafe_b64decode(TOKEN.split(".")[1] + "=="))
    assert claims["orgs"] == ["acme"] and claims["iss"] == "contextlayer-tui-dev"


def test_something_that_is_not_a_token_is_refused_plainly():
    with pytest.raises(ApiError, match="does not look like a token"):
        read_token("not-a-token")


async def test_requests_go_to_the_organizations_paths_with_the_bearer_token():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path, request.headers["authorization"]))
        return httpx.Response(200, json={"items": [], "events": []})

    api = client_for(handler)
    await api.connectors()
    await api.cases()
    await api.cases("OPEN")
    await api.identity_review()
    await api.assistants()
    await api.monitors()

    assert [(m, p) for m, p, _ in seen] == [
        ("GET", "/v1/context/connectors"),
        ("GET", "/v1/context/cases"),
        ("GET", "/v1/context/cases/status/OPEN"),
        ("GET", "/v1/context/identity/review"),
        ("GET", "/v1/context/assistants"),
        ("GET", "/v1/context/monitors"),
    ]
    assert {auth for _, _, auth in seen} == {f"Bearer {TOKEN}"}
    assert (api.subject, api.roles) == ("tyler", ["operator", "approver"])


async def test_things_waiting_for_a_person_use_the_oversight_routes():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path, request.url.query.decode(), request.headers.get("x-org-id"),
                     request.content.decode() if request.content else ""))
        return httpx.Response(200, json={"events": []})

    api = client_for(handler)
    await api.human_events()
    await api.human_claim("acme:he:action:a1:k1")
    await api.human_release("acme:he:action:a1:k1")
    await api.human_decide("acme:he:action:a1:k1", "APPROVE", "routine")
    await api.human_escalate("acme:he:action:a1:k1", "needs an admin")
    await api.human_send_back("acme:he:action:a1:k1", "which record?")
    await api.human_withdraw("acme:he:action:a1:k1", "changed my mind")

    assert [(m, p, q) for m, p, q, _, _ in seen] == [
        ("GET", "/v1/context/oversight/events", "state=pending"),
        ("POST", "/v1/context/oversight/events/acme:he:action:a1:k1/claim", ""),
        ("POST", "/v1/context/oversight/events/acme:he:action:a1:k1/release", ""),
        ("POST", "/v1/context/oversight/events/acme:he:action:a1:k1/decision", ""),
        ("POST", "/v1/context/oversight/events/acme:he:action:a1:k1/escalate", ""),
        ("POST", "/v1/context/oversight/events/acme:he:action:a1:k1/send-back", ""),
        ("POST", "/v1/context/oversight/events/acme:he:action:a1:k1/withdraw", ""),
    ]
    decision = json.loads(seen[3][4])
    assert decision == {"action": "APPROVE", "rationale": "routine"}
    assert json.loads(seen[4][4]) == {"reason": "needs an admin"}
    assert json.loads(seen[5][4]) == {"requested": "which record?"}


async def test_decisions_post_the_bodies_the_service_expects_including_the_reason():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, request.content.decode() if request.content else ""))
        return httpx.Response(200, json={})

    api = client_for(handler)
    await api.identity_decide("name-dob", "sam~1990", True, "same employer")
    await api.identity_decide("name-dob", "sam~1990", False, "different employers")
    await api.case_resolve("org:m:h:r1", "done")
    await api.case_annotate("org:m:h:r1", "called")
    await api.assistant_approve_release("claims-watch", "r1", "only annotates")
    await api.assistant_set_stage("claims-watch", "LIVE", "shadow report looked right")
    await api.monitor_set_enabled("m1", False)
    await api.refresh_entity("claims", "Claim")
    await api.waive("custom-cl0009", "internal hosts only")
    await api.lift_waiver("custom-cl0009")

    assert [p for p, _ in seen] == [
        "/v1/context/identity/confirm",
        "/v1/context/identity/reject",
        "/v1/context/cases/org:m:h:r1/resolve",
        "/v1/context/cases/org:m:h:r1/annotate",
        "/v1/context/assistants/claims-watch/releases/r1/approve",
        "/v1/context/assistants/claims-watch/stage",
        "/v1/context/monitors/m1/disable",
        "/v1/context/connectors/claims/entities/Claim/refresh",
        "/v1/context/governance/controls/custom-cl0009/waiver",
        "/v1/context/governance/controls/custom-cl0009/waiver/lift",
    ]
    bodies = dict(seen)
    assert json.loads(bodies["/v1/context/identity/confirm"]) == {
        "rule": "name-dob", "key": "sam~1990", "rationale": "same employer"}
    assert json.loads(bodies["/v1/context/assistants/claims-watch/stage"]) == {
        "stage": "LIVE", "rationale": "shadow report looked right"}
    assert json.loads(bodies["/v1/context/assistants/claims-watch/releases/r1/approve"]) == {
        "rationale": "only annotates"}


async def test_a_refusal_carries_the_services_words_and_status():
    api = client_for(lambda request: httpx.Response(400, text="only the accountable owner can approve a release"))
    with pytest.raises(ApiError) as caught:
        await api.assistant_approve_release("a1", "r1", "why")
    assert caught.value.message == "only the accountable owner can approve a release"
    assert caught.value.status == 400


async def test_a_json_refusal_from_the_oversight_routes_shows_its_error_text():
    api = client_for(lambda request: httpx.Response(409, json={"error": "claimed by another reviewer", "state": "CLAIMED"}))
    with pytest.raises(ApiError) as caught:
        await api.human_claim("acme:he:x:y:z")
    assert caught.value.message == "claimed by another reviewer"
    assert caught.value.status == 409


async def test_a_rejected_token_is_explained_without_echoing_the_service():
    api = client_for(lambda request: httpx.Response(401, text="Authorization is required"))
    with pytest.raises(ApiError, match="not signed in, or your token was refused"):
        await api.connectors()


async def test_a_service_that_cannot_be_reached_is_reported_plainly():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(ApiError, match="cannot reach the service at http://svc"):
        await client_for(handler).connectors()


async def test_a_slow_service_is_reported_plainly():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(ApiError, match="took too long"):
        await client_for(handler).connectors()


async def test_the_console_survives_an_unreachable_service_and_says_so_in_the_banner():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    app = app_for(client_for(handler))
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause(0.3)
        assert "cannot reach" in text_of(app.query_one("#banner", Static))
        assert app.reachable is False
        assert app.query_one("#systems-pane").items == {}


async def test_the_banner_recovers_when_the_service_comes_back():
    state = {"up": False}

    def handler(request: httpx.Request) -> httpx.Response:
        if not state["up"]:
            raise httpx.ConnectError("refused", request=request)
        return httpx.Response(200, json={"items": [], "events": [], "controls": [], "digest": "sha256:x", "domains": [], "joins": [], "documents": []})

    app = app_for(client_for(handler))
    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause(0.3)
        assert app.reachable is False
        state["up"] = True
        await app.refresh_all()
        await pilot.pause(0.2)
        assert app.reachable is True
        assert "connected" in text_of(app.query_one("#banner", Static))


async def test_opening_a_cluster_for_review_posts_the_rule_and_key():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"eventId": "e", "minimumReviewSeconds": 5, "openedAt": 1})

    opened = await client_for(handler).identity_open("name-dob", "sam~1990")

    assert seen == [("/v1/context/identity/open", {"rule": "name-dob", "key": "sam~1990"})]
    assert opened["minimumReviewSeconds"] == 5


async def test_halting_asks_through_the_oversight_routes_and_listing_reads_the_scopes():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path, request.content.decode() if request.content else ""))
        return httpx.Response(200, json={"halts": [{"target": "a1", "halted": True}]})

    api = client_for(handler)
    halts = await api.halts()
    await api.halt("a1", False, "wrong client")

    assert halts[0]["halted"] is True
    assert seen[0][:2] == ("GET", "/v1/context/oversight/halts")
    assert seen[1][:2] == ("POST", "/v1/context/oversight/halts")
    assert json.loads(seen[1][2]) == {"scope": "assistant", "target": "a1", "lift": False, "reason": "wrong client"}


async def test_drafting_sends_the_words_and_returns_the_draft():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"question": None, "explanation": "", "clarification": "which?", "problems": []})

    draft = await client_for(handler).draft_question("the bad ones")

    assert seen == [("/v1/context/questions/draft", {"text": "the bad ones"})]
    assert draft["clarification"] == "which?"
