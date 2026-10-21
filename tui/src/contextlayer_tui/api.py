"""The service's HTTP API, as the console uses it.

`Api` is the contract the screens depend on. `HttpApi` talks to a running service; the demo
client in `demo.py` implements the same methods over sample data, so every screen can be shown
and tested without a service.

Who you are comes from a bearer token. The service checks it; the console only reads it to show
your name and roles. `dev_token` makes an unsigned token, which a service running locally accepts
and a deployed one does not.
"""

from __future__ import annotations

import base64
import json
from datetime import datetime, timedelta, timezone
from collections.abc import AsyncIterator
from typing import Any, Protocol

import httpx

# How long a waiver granted from the console lasts; the service allows at most 365 days.
WAIVER_DAYS = 90


class ApiError(Exception):
    """The service refused a request or could not be reached. `message` is fit to show a person."""

    def __init__(self, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.status = status


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def dev_token(subject: str, roles: list[str], org: str) -> str:
    """An unsigned token for a service running locally, where the runtime skips signature checks."""
    header = _b64(json.dumps({"alg": "none"}).encode())
    claims = {"iss": "contextlayer-tui-dev", "sub": subject, "roles": roles, "orgs": [org]}
    return f"{header}.{_b64(json.dumps(claims).encode())}."


def read_token(token: str) -> tuple[str, list[str]]:
    """The subject and roles a token claims. Not verified: this only labels the banner."""
    try:
        payload = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except (IndexError, ValueError):
        raise ApiError("that does not look like a token") from None
    roles = claims.get("roles") or []
    return str(claims.get("sub", "unknown")), [roles] if isinstance(roles, str) else [str(r) for r in roles]


class Api(Protocol):
    """Everything the console asks of the service."""

    label: str
    subject: str
    roles: list[str]

    async def connectors(self) -> list[dict[str, Any]]: ...
    async def refresh_entity(self, pack: str, entity_type: str) -> dict[str, Any]: ...
    async def identity_review(self) -> list[dict[str, Any]]: ...
    async def identity_open(self, rule: str, key: str) -> dict[str, Any]: ...
    async def identity_decide(self, rule: str, key: str, same_person: bool, rationale: str) -> dict[str, Any]: ...
    async def ask(self, question: dict[str, Any]) -> dict[str, Any]: ...
    async def draft_question(self, text: str) -> dict[str, Any]: ...
    async def cases(self, status: str | None = None) -> list[dict[str, Any]]: ...
    async def case(self, case_id: str) -> dict[str, Any]: ...
    async def case_resolve(self, case_id: str, note: str) -> dict[str, Any]: ...
    async def case_dismiss(self, case_id: str, note: str) -> dict[str, Any]: ...
    async def case_annotate(self, case_id: str, text: str) -> dict[str, Any]: ...
    async def human_events(self) -> list[dict[str, Any]]: ...
    async def human_claim(self, event_id: str) -> dict[str, Any]: ...
    async def human_release(self, event_id: str) -> dict[str, Any]: ...
    async def human_decide(self, event_id: str, action: str, rationale: str) -> dict[str, Any]: ...
    async def human_escalate(self, event_id: str, reason: str) -> dict[str, Any]: ...
    async def human_send_back(self, event_id: str, requested: str) -> dict[str, Any]: ...
    async def human_withdraw(self, event_id: str, reason: str) -> dict[str, Any]: ...
    async def halts(self) -> list[dict[str, Any]]: ...
    async def halt(self, assistant_id: str, lift: bool, reason: str) -> dict[str, Any]: ...
    async def assistants(self) -> list[dict[str, Any]]: ...
    async def assistant(self, assistant_id: str) -> dict[str, Any]: ...
    async def assistant_actions(self, assistant_id: str) -> list[dict[str, Any]]: ...
    async def assistant_approve_release(self, assistant_id: str, release_id: str, rationale: str) -> dict[str, Any]: ...
    async def create_assistant(self, assistant_id: str, purpose: str) -> dict[str, Any]:
        return await self._send("POST", f"/assistants/{assistant_id}", {"purpose": purpose})

    async def add_release(self, assistant_id: str, definition: str) -> dict[str, Any]:
        return await self._send("POST", f"/assistants/{assistant_id}/releases", {"definition": definition})

    async def check_release(self, assistant_id: str, definition: str) -> list[str]:
        return (await self._send("POST", f"/assistants/{assistant_id}/releases/check", {"definition": definition}))["problems"]

    async def schema(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/schema"))["types"]

    async def assistant_set_stage(self, assistant_id: str, stage: str, rationale: str) -> dict[str, Any]: ...
    async def create_assistant(self, assistant_id: str, purpose: str) -> dict[str, Any]: ...
    async def add_release(self, assistant_id: str, definition: str) -> dict[str, Any]: ...
    async def check_release(self, assistant_id: str, definition: str) -> list[str]: ...
    async def schema(self) -> list[dict[str, Any]]: ...
    async def monitors(self) -> list[dict[str, Any]]: ...
    async def monitor_run(self, monitor_id: str) -> dict[str, Any]: ...
    async def monitor_set_enabled(self, monitor_id: str, enabled: bool) -> dict[str, Any]: ...
    async def create_monitor(self, monitor_id: str, body: dict[str, Any]) -> dict[str, Any]: ...
    async def controls(self) -> dict[str, Any]: ...
    async def compliance(self) -> dict[str, Any]: ...
    async def waive(self, control_id: str, reason: str) -> dict[str, Any]: ...
    async def lift_waiver(self, control_id: str) -> dict[str, Any]: ...
    async def accept_control(self, control_id: str, rationale: str) -> dict[str, Any]: ...
    async def integrity(self) -> dict[str, Any]: ...
    async def interaction(self, interaction_id: str) -> dict[str, Any]: ...
    async def audit(self, question: str) -> dict[str, Any]: ...
    async def documents(self) -> list[dict[str, Any]]: ...
    async def register_document(self, name: str, kind: str, ref: str, as_of: str) -> dict[str, Any]: ...
    async def record_stores(self) -> list[dict[str, Any]]: ...
    async def domains(self) -> dict[str, Any]: ...
    def changes(self) -> AsyncIterator[dict[str, Any]]: ...


class HttpApi:
    """The real service, called with a bearer token."""

    def __init__(self, base_url: str, org: str, token: str, client: httpx.AsyncClient | None = None) -> None:
        self.label = base_url
        self._org = org
        self.subject, self.roles = read_token(token)
        self._http = client or httpx.AsyncClient(
            base_url=base_url, headers={"Authorization": f"Bearer {token}"}, timeout=httpx.Timeout(30.0, connect=5.0)
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    # ---- plumbing

    def _path(self, tail: str) -> str:
        return f"/v1/context{tail}"

    async def _send(self, method: str, tail: str, body: Any = None, *, oversight: bool = False) -> Any:
        """Calls the service. The service belongs to one project, so no route names it."""
        path = self._path(f"/oversight{tail}" if oversight else tail)
        try:
            response = await self._http.request(method, path, json=body)
        except httpx.ConnectError as error:
            raise ApiError(f"cannot reach the service at {self.label}") from error
        except httpx.TimeoutException as error:
            raise ApiError("the service took too long to answer") from error
        if response.status_code >= 400:
            raise ApiError(self._words(response), response.status_code)
        return response.json() if response.content else {}

    @staticmethod
    def _words(response: httpx.Response) -> str:
        """What the service said, in a form fit to show: the error text of a JSON answer, or the plain text."""
        if response.status_code == 401:
            return "you are not signed in, or your token was refused"
        text = response.text.strip()
        try:
            body = response.json()
        except ValueError:
            body = None
        if isinstance(body, dict) and body.get("error"):
            text = str(body["error"])
        elif isinstance(body, str):
            text = body
        return text.strip('"') or f"the service answered HTTP {response.status_code}"

    # ---- connectors

    async def connectors(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/connectors"))["items"]

    async def refresh_entity(self, pack: str, entity_type: str) -> dict[str, Any]:
        return await self._send("POST", f"/connectors/{pack}/entities/{entity_type}/refresh")

    # ---- identity

    async def identity_review(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/identity/review"))["items"]

    async def identity_open(self, rule: str, key: str) -> dict[str, Any]:
        return await self._send("POST", "/identity/open", {"rule": rule, "key": key})

    async def identity_decide(self, rule: str, key: str, same_person: bool, rationale: str) -> dict[str, Any]:
        return await self._send("POST", "/identity/confirm" if same_person else "/identity/reject",
                                {"rule": rule, "key": key, "rationale": rationale})

    # ---- questions

    async def ask(self, question: dict[str, Any]) -> dict[str, Any]:
        return await self._send("POST", "/questions", question)

    async def draft_question(self, text: str) -> dict[str, Any]:
        """A request in words turned into a question to read and run. The model sees the shape of the data, not records."""
        return await self._send("POST", "/questions/draft", {"text": text})

    # ---- cases

    async def cases(self, status: str | None = None) -> list[dict[str, Any]]:
        tail = f"/cases/status/{status}" if status else "/cases"
        return (await self._send("GET", tail))["items"]

    async def case(self, case_id: str) -> dict[str, Any]:
        return await self._send("GET", f"/cases/{case_id}")

    async def case_resolve(self, case_id: str, note: str) -> dict[str, Any]:
        return await self._send("POST", f"/cases/{case_id}/resolve", {"note": note})

    async def case_dismiss(self, case_id: str, note: str) -> dict[str, Any]:
        return await self._send("POST", f"/cases/{case_id}/dismiss", {"note": note})

    async def case_annotate(self, case_id: str, text: str) -> dict[str, Any]:
        return await self._send("POST", f"/cases/{case_id}/annotate", {"text": text})

    # ---- things waiting for a person

    async def human_events(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/events?state=pending", oversight=True))["events"]

    async def human_claim(self, event_id: str) -> dict[str, Any]:
        return await self._send("POST", f"/events/{event_id}/claim", {}, oversight=True)

    async def human_release(self, event_id: str) -> dict[str, Any]:
        return await self._send("POST", f"/events/{event_id}/release", {}, oversight=True)

    async def human_decide(self, event_id: str, action: str, rationale: str) -> dict[str, Any]:
        return await self._send("POST", f"/events/{event_id}/decision",
                                {"action": action, "rationale": rationale}, oversight=True)

    async def human_escalate(self, event_id: str, reason: str) -> dict[str, Any]:
        return await self._send("POST", f"/events/{event_id}/escalate", {"reason": reason}, oversight=True)

    async def human_send_back(self, event_id: str, requested: str) -> dict[str, Any]:
        return await self._send("POST", f"/events/{event_id}/send-back", {"requested": requested}, oversight=True)

    async def human_withdraw(self, event_id: str, reason: str) -> dict[str, Any]:
        return await self._send("POST", f"/events/{event_id}/withdraw", {"reason": reason}, oversight=True)

    async def halts(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/halts", oversight=True))["halts"]

    async def halt(self, assistant_id: str, lift: bool, reason: str) -> dict[str, Any]:
        """Asks for an assistant to be halted, or restarted. This raises the event; deciding it makes it so."""
        return await self._send("POST", "/halts", {"scope": "assistant", "target": assistant_id, "lift": lift,
                                                   "reason": reason}, oversight=True)

    # ---- assistants

    async def assistants(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/assistants"))["items"]

    async def assistant(self, assistant_id: str) -> dict[str, Any]:
        return await self._send("GET", f"/assistants/{assistant_id}")

    async def assistant_actions(self, assistant_id: str) -> list[dict[str, Any]]:
        return (await self._send("GET", f"/assistants/{assistant_id}/actions"))["items"]

    async def assistant_approve_release(self, assistant_id: str, release_id: str, rationale: str) -> dict[str, Any]:
        return await self._send("POST", f"/assistants/{assistant_id}/releases/{release_id}/approve",
                                {"rationale": rationale})

    async def assistant_set_stage(self, assistant_id: str, stage: str, rationale: str) -> dict[str, Any]:
        return await self._send("POST", f"/assistants/{assistant_id}/stage", {"stage": stage, "rationale": rationale})

    # ---- monitors

    async def monitors(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/monitors"))["items"]

    async def monitor_run(self, monitor_id: str) -> dict[str, Any]:
        return await self._send("POST", f"/monitors/{monitor_id}/run", {})

    async def monitor_set_enabled(self, monitor_id: str, enabled: bool) -> dict[str, Any]:
        return await self._send("POST", f"/monitors/{monitor_id}/" + ("enable" if enabled else "disable"), {})

    async def create_monitor(self, monitor_id: str, body: dict[str, Any]) -> dict[str, Any]:
        return await self._send("POST", f"/monitors/{monitor_id}", body)

    # ---- governance

    async def controls(self) -> dict[str, Any]:
        return await self._send("GET", "/governance/controls")

    async def compliance(self) -> dict[str, Any]:
        return await self._send("GET", "/governance/compliance")

    async def waive(self, control_id: str, reason: str) -> dict[str, Any]:
        """A waiver must end; the console grants one for WAIVER_DAYS, and it can be lifted sooner."""
        until = (datetime.now(timezone.utc) + timedelta(days=WAIVER_DAYS)).isoformat().replace("+00:00", "Z")
        return await self._send("POST", f"/governance/controls/{control_id}/waiver", {"reason": reason, "until": until})

    async def lift_waiver(self, control_id: str) -> dict[str, Any]:
        return await self._send("POST", f"/governance/controls/{control_id}/waiver/lift", {})

    async def accept_control(self, control_id: str, rationale: str) -> dict[str, Any]:
        return await self._send("POST", f"/governance/controls/{control_id}/acceptance", {"rationale": rationale})

    async def integrity(self) -> dict[str, Any]:
        return await self._send("GET", "/governance/integrity")

    async def interaction(self, interaction_id: str) -> dict[str, Any]:
        return await self._send("GET", f"/governance/interactions/{interaction_id}")

    async def record_stores(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/governance/record-stores"))["stores"]

    async def domains(self) -> dict[str, Any]:
        return await self._send("GET", "/domains")

    async def changes(self) -> AsyncIterator[dict[str, Any]]:
        """Records as they change, until the connection ends. Raises ApiError if it cannot be opened."""
        try:
            async with self._http.stream("GET", self._path("/streams/changes"),
                                         headers={"Accept": "text/event-stream"},
                                         timeout=httpx.Timeout(None, connect=5.0)) as response:
                if response.status_code >= 400:
                    await response.aread()
                    raise ApiError(self._words(response), response.status_code)
                async for line in response.aiter_lines():
                    if line.startswith("data:"):
                        try:
                            yield json.loads(line[len("data:"):])
                        except ValueError:
                            continue
        except httpx.TransportError as error:
            raise ApiError(f"the change feed at {self.label} was cut off") from error

    async def audit(self, question: str) -> dict[str, Any]:
        return await self._send("POST", "/governance/ask", {"text": question})

    async def documents(self) -> list[dict[str, Any]]:
        return (await self._send("GET", "/governance/documents"))["documents"]

    async def register_document(self, name: str, kind: str, ref: str, as_of: str) -> dict[str, Any]:
        return await self._send("POST", f"/governance/documents/{name}", {"kind": kind, "ref": ref, "asOf": as_of})
