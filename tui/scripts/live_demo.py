"""Put a small, believable organization into a running service, so the console has something real to show.

It starts two pretend sources on this machine (claims and wealth), then, through the service's
own API, connects them, creates an assistant with a playbook (in shadow), and starts a monitor.

Start the service first, allowing local sources and giving it a development credential:

    CONTEXTLAYER_ALLOWED_HOSTS=localhost  CONTEXTLAYER_DEV_SECRET=demo-token  NEO4J_PASSWORD=...

Then:   uv run python scripts/live_demo.py            (leave it running; press Ctrl+C to stop the sources)
        uv run contextlayer-tui --org demo --user dan.m --roles operator,reviewer,approver

The assistant's playbook asks a model to label each case. Without OPENAI_API_KEY on the service, that
step fails and the case timeline says so, which is also worth seeing.
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx

from contextlayer_tui.api import dev_token

CLAIMS = {"items": [
    {"claimId": "CL-889", "client": "Pat Lee", "email": "pat@example.com", "name": "Pat Lee", "dob": "1980-01-02", "status": "OPEN", "daysOpen": 41},
    {"claimId": "CL-871", "client": "Sam Ortiz", "email": "sam.o@claims.example", "name": "Sam Ortiz", "dob": "1990-05-05", "status": "OPEN", "daysOpen": 36},
    {"claimId": "CL-860", "client": "Dana Wu", "email": "dana@example.com", "name": "Dana Wu", "dob": "1975-03-03", "status": "OPEN", "daysOpen": 33},
    {"claimId": "CL-802", "client": "Lee Chan", "email": "lee@example.com", "name": "Lee Chan", "dob": "1968-07-19", "status": "CLOSED", "daysOpen": 12},
]}
ACCOUNTS = {"items": [
    {"accountId": "W-1", "email": "pat@example.com", "name": "Pat Lee", "dob": "1980-01-02", "balance": 412000},
    {"accountId": "W-2", "email": "sam@wealth.example", "name": "Sam Ortiz", "dob": "1990-05-05", "balance": 288500},
    {"accountId": "W-3", "email": "dana@example.com", "name": "Dana Wu", "dob": "1975-03-03", "balance": 301200},
    {"accountId": "W-4", "email": "lee@example.com", "name": "Lee Chan", "dob": "1968-07-19", "balance": 9000},
]}

PLAYBOOK = {"steps": [
    {"id": "judge", "type": "classify", "instruction": "How badly is this client's stalled claim affecting them?",
     "fields": ["Claim.daysOpen"], "labels": ["BLOCKED", "DEGRADED", "MINOR"], "store": "impact", "next": "route"},
    {"id": "route", "type": "branch", "var": "impact", "cases": {"BLOCKED": "note", "DEGRADED": "note"}, "otherwise": "end"},
    {"id": "note", "type": "annotate", "text": "impact judged {impact}", "next": "end"},
]}


def serve(port: int, path: str, operation: str, data: dict) -> ThreadingHTTPServer:
    origin = f"http://localhost:{port}"
    spec = {"openapi": "3.0.0", "servers": [{"url": origin}], "paths": {path: {"get": {"operationId": operation}}}}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - the name http.server looks for
            body = spec if self.path.startswith("/openapi.json") else data if self.path.startswith(path) else None
            payload = json.dumps(body if body is not None else {}).encode()
            self.send_response(200 if body is not None else 404)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args) -> None:  # keep the terminal quiet
            pass

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def pack(name: str, port: int, entity: str, operation: str, id_field: str) -> dict:
    return {
        "name": name, "version": "1.0.0",
        "source": {"kind": "openapi", "ref": f"http://localhost:{port}/openapi.json"},
        "credentialRef": f"vault://demo/{name}",
        "entities": [{
            "type": entity, "operation": operation, "recordsPath": "/items", "idField": id_field,
            "identityAttributes": ["email", "name", "dob"], "filters": [],
            "freshness": {"policy": "CACHED", "ttlMinutes": 15},
            "paging": {"style": "none", "maxPageSize": 100},
            "rate": {"callsPerMinute": 600},
        }],
        "actions": [],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:9162")
    parser.add_argument("--org", default="demo")
    args = parser.parse_args()

    serve(19181, "/claims", "listClaims", CLAIMS)
    serve(19182, "/accounts", "listAccounts", ACCOUNTS)
    base = f"{args.url}/v1/context"

    roles = {"priya.k": ["operator"], "dan.m": ["operator", "reviewer", "approver"]}

    def call(user: str, path: str, body: dict | None = None) -> dict:
        token = dev_token(user, roles[user], args.org)
        response = httpx.post(base + path, json=body if body is not None else {},
                              headers={"Authorization": f"Bearer {token}"}, timeout=30)
        if response.status_code >= 400:
            raise SystemExit(f"{path}: {response.status_code} {response.text}")
        return response.json() if response.content else {}

    for name, port, entity, operation, id_field in (
        ("claims", 19181, "Claim", "listClaims", "claimId"),
        ("wealth", 19182, "Account", "listAccounts", "accountId"),
    ):
        version = call("priya.k", f"/connectors/{name}/versions", pack(name, port, entity, operation, id_field))
        call("dan.m", f"/connectors/{name}/versions/{version['hash']}/activate",
             {"mode": "READ_ONLY", "rationale": "the source is read-only and the credential is scoped to reads"})
        print(f"connected {name} (imported by priya.k, activated by dan.m, read-only)")

    call("dan.m", "/assistants/claims-watch", {"purpose": "Make sure no claim over 30 days stalls on our side"})
    release = call("dan.m", "/assistants/claims-watch/releases", {"definition": json.dumps(PLAYBOOK)})
    call("dan.m", f"/assistants/claims-watch/releases/{release['releaseId']}/approve",
         {"rationale": "the playbook only labels and annotates"})
    call("dan.m", "/assistants/claims-watch/stage", {"stage": "SHADOW", "rationale": "record what it would do first"})
    print("assistant claims-watch is in shadow with an approved release")

    time.sleep(3)  # let the connector listing catch up before the monitor's first run
    question = {
        "entityType": "Claim",
        "where": [{"field": "status", "op": "EQ", "value": "OPEN"}, {"field": "daysOpen", "op": "GT", "value": 30}],
        "links": [{"entityType": "Account", "where": [{"field": "balance", "op": "GT", "value": 250000}]}],
        "returnFields": ["Claim.claimId", "Claim.client", "Claim.daysOpen", "Account.balance"], "limit": 100,
    }
    call("dan.m", "/monitors/stalled-claims", {
        "name": "stalled-claims", "question": question, "keyField": "Claim.claimId", "intervalMinutes": 15,
        "assistantId": "claims-watch", "routeTo": "Claims Team B"})
    print("monitor stalled-claims started")
    print(f"\nOpen the console:  uv run contextlayer-tui --url {args.url} --org {args.org} --user dan.m --roles operator,reviewer,approver")
    print("Sources are running on ports 19181 and 19182. Press Ctrl+C to stop them.")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("stopped")


if __name__ == "__main__":
    main()
