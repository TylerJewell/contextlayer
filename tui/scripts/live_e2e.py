"""Runs the whole chain against a running service and a real model, then prints what happened.

    monitor finds stalled claims -> case opens -> the team is told (webhook) -> an assistant's playbook
    asks the model to label the case -> a risky action waits for an approver, who is told (webhook) ->
    the approver decides -> the action is sent to a pretend CRM -> the evidence says so.

Start the service with a model key and local sources allowed:

    GOOGLE_AI_GEMINI_API_KEY=...  CONTEXTLAYER_ALLOWED_HOSTS=localhost  CONTEXTLAYER_DEV_SECRET=demo-token  NEO4J_PASSWORD=...

Then:   uv run python scripts/live_e2e.py [--url http://localhost:9162] [--org e2e-<random>]
"""

from __future__ import annotations

import argparse
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx

from contextlayer_tui.api import dev_token
from live_demo import pack, serve

CLAIMS = {"items": [
    {"claimId": "CL-889", "email": "pat@example.com", "name": "Pat Lee", "dob": "1980-01-02", "status": "OPEN",
     "daysOpen": 41, "waitingOn": "us", "latestNote": "Invoice from pat@example.com sent on 3 Sept. Nobody has replied."},
    {"claimId": "CL-871", "email": "sam.o@claims.example", "name": "Sam Ortiz", "dob": "1990-05-05", "status": "OPEN",
     "daysOpen": 36, "waitingOn": "client", "latestNote": "We asked for the repair invoice twice; it is coming from the garage."},
    {"claimId": "CL-860", "email": "dana@example.com", "name": "Dana Wu", "dob": "1975-03-03", "status": "OPEN",
     "daysOpen": 33, "waitingOn": "us", "latestNote": "Adjuster report was due two weeks ago and has not been filed."},
]}
ACCOUNTS = {"items": [{"accountId": "W-1", "email": "pat@example.com", "name": "Pat Lee", "dob": "1980-01-02", "balance": 412000}]}

INSTRUCTION = ("Decide how badly this client's open insurance claim is affecting them. Apply these rules in order and "
               "stop at the first that fits. 1. MINOR: the claim is under 14 days old, or it is moving, or we are waiting "
               "on the client or a third party. 2. BLOCKED: the claim cannot move until we act and it has been open more "
               "than 30 days. 3. DEGRADED: anything else.")
PLAYBOOK = {"steps": [
    {"id": "judge", "type": "classify", "instruction": INSTRUCTION,
     "fields": ["Claim.daysOpen", "Claim.waitingOn", "Claim.latestNote"], "labels": ["BLOCKED", "DEGRADED", "MINOR"],
     "store": "impact", "next": "route"},
    {"id": "route", "type": "branch", "var": "impact", "cases": {"BLOCKED": "act"}, "otherwise": "end"},
    {"id": "act", "type": "act", "actionType": "crm.addNote",
     "payload": "{\"claimId\":\"{Claim.claimId}\",\"text\":\"Escalated: claim judged {impact}\"}", "next": "end"},
]}

received_notes: list[dict] = []
received_hooks: list[dict] = []


def capture(port: int, sink: list[dict], spec: dict | None = None) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            payload = json.dumps(spec or {}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", 0))
            sink.append({"path": self.path, "body": json.loads(self.rfile.read(length) or b"{}"),
                         "key": self.headers.get("Idempotency-Key")})
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

        def log_message(self, *args) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:9162")
    parser.add_argument("--org", default=f"e2e-{uuid.uuid4().hex[:6]}")
    parser.add_argument("--wait", type=int, default=120, help="seconds to wait for the chain to finish")
    args = parser.parse_args()
    org, base = args.org, f"{args.url}/v1/context"

    serve(19181, "/claims", "listClaims", CLAIMS)
    serve(19182, "/accounts", "listAccounts", ACCOUNTS)
    crm_spec = {"openapi": "3.0.0", "servers": [{"url": "http://localhost:19183"}],
                "paths": {"/claims/{claimId}/notes": {"post": {"operationId": "addNote"}}}}
    capture(19183, received_notes, crm_spec)
    capture(19184, received_hooks)

    roles = {"priya.k": ["operator"], "dan.m": ["operator", "reviewer", "approver"], "bob": ["approver"], "root": ["admin"]}

    def call(user: str, path: str, body: dict | None = None, method: str = "POST", root: bool = False) -> dict:
        token = dev_token(user, roles[user], org)
        url = (args.url + path) if root else (base + path)
        headers = {"Authorization": f"Bearer {token}"}
        response = httpx.request(method, url, json=body if method == "POST" else None, headers=headers, timeout=60)
        if response.status_code >= 400:
            raise SystemExit(f"{method} {path}: {response.status_code} {response.text}")
        return response.json() if response.content else {}

    print(f"organization {org}")
    for name, port, entity, op, idf in (("claims", 19181, "Claim", "listClaims", "claimId"),
                                         ("wealth", 19182, "Account", "listAccounts", "accountId")):
        version = call("priya.k", f"/connectors/{name}/versions", pack(name, port, entity, op, idf))
        call("dan.m", f"/connectors/{name}/versions/{version['hash']}/activate",
             {"mode": "READ_ONLY", "rationale": "read-only source"})
    crm = {"name": "crm", "version": "1.0.0", "source": {"kind": "openapi", "ref": "http://localhost:19183/openapi.json"},
           "credentialRef": "vault://demo/crm", "entities": [],
           "actions": [{"name": "addNote", "operation": "addNote", "risk": "NEEDS_APPROVAL"}]}
    version = call("priya.k", "/connectors/crm/versions", crm)
    call("dan.m", f"/connectors/crm/versions/{version['hash']}/activate", {"mode": "LIVE", "rationale": "CRM notes are reversible"})
    for route in ("Claims Team B", "role:approver"):
        call("root", f"/routes/{route.replace(':', '%3A').replace(' ', '%20')}",
             {"url": "http://localhost:19184/hook", "format": "json"})
    print("connected: claims, wealth (read-only), crm (live action); routes set up")

    call("dan.m", "/assistants/claims-watch", {"purpose": "Escalate claims that are stuck on our side"})
    release = call("dan.m", "/assistants/claims-watch/releases", {"definition": json.dumps(PLAYBOOK)})
    call("dan.m", f"/assistants/claims-watch/releases/{release['releaseId']}/approve", {"rationale": "labels, then one reversible note"})
    call("dan.m", "/assistants/claims-watch/stage", {"stage": "LIVE", "rationale": "shadow report was fine"})
    time.sleep(3)
    question = {"entityType": "Claim", "where": [{"field": "status", "op": "EQ", "value": "OPEN"},
                                                  {"field": "daysOpen", "op": "GT", "value": 30}],
                "links": [], "returnFields": ["Claim.claimId", "Claim.daysOpen", "Claim.waitingOn", "Claim.latestNote"], "limit": 100}
    call("dan.m", "/monitors/stalled", {"name": "stalled", "question": question, "keyField": "Claim.claimId",
                                         "intervalMinutes": 60, "assistantId": "claims-watch", "routeTo": "Claims Team B"})
    print("monitor started; waiting for cases, the model, and approvals ...")

    # Approve whatever waits for a person, once it has been open long enough to count as reviewed.
    deadline, decided, opened = time.time() + args.wait, set(), {}
    while time.time() < deadline:
        events = httpx.get(args.url + "/v1/context/oversight/events?state=pending", headers={
            "Authorization": f"Bearer {dev_token('bob', roles['bob'], org)}"}, timeout=30).json().get("events", [])
        for event in events:
            if event["id"] in decided or event["subject"]["kind"] != "action":
                continue
            if event["id"] not in opened:
                call("bob", f"/v1/context/oversight/events/{event['id']}/claim", {}, root=True)
                opened[event["id"]] = time.time()
            elif time.time() - opened[event["id"]] > event["minimumReviewSeconds"] + 1:
                call("bob", f"/v1/context/oversight/events/{event['id']}/decision",
                     {"action": "APPROVE", "rationale": "the note is reversible and the label is right"}, root=True)
                decided.add(event["id"])
        if len(received_notes) >= 1 and len(decided) >= 1 and len(received_notes) >= len(decided):
            time.sleep(5)
            break
        time.sleep(2)

    print("\n== cases and what the model decided")
    for case in call("dan.m", "/cases", method="GET")["items"]:
        detail = call("dan.m", f"/cases/{case['caseId']}", method="GET")
        print(f"  {case['key']}:")
        for entry in detail["timeline"]:
            print(f"      {entry['actor']}: {entry['text']}")
    print("\n== messages the receiver got")
    for h in received_hooks:
        print(f"  {h['body']['type']}: {h['body']['title']}  ->  {h['body']['route']}")
    print("\n== requests the CRM got")
    for n in received_notes:
        print(f"  POST {n['path']}  {n['body']}  Idempotency-Key={n['key']}")
    print("\n== where each control stands")
    for c in call("dan.m", "/governance/compliance", method="GET")["controls"]:
        print(f"  {c['status']:<12} {c['upheld']:>3} upheld {c['breached']:>2} breached  {c['name']}")


if __name__ == "__main__":
    main()
