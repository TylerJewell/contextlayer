"""Records the end-to-end demo for Akka engineers: the real console against a running service and a real model,
with slides on what the system uses from Akka Verify, its controls, and the gaps found.

    1. start the service (model key set, CONTEXTLAYER_ALLOWED_HOSTS=localhost, CONTEXTLAYER_DEV_SECRET=demo-token)
    2. uv run python scripts/demo_engineering.py --out demo-engineering
    3. uv run python scripts/demo_build.py --out demo-engineering --name contextlayer-engineering

Nothing on screen is mocked: the links, the model's labels, the decisions, the request the CRM receives and the
compliance report are the service's own. The control slides are drawn from service/governance/controls.yaml when
the demo is recorded, so they list the controls the service runs.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
import uuid
from pathlib import Path

import httpx
import yaml
from demo_record import ACCOUNTS, CLAIMS, PLAYBOOK, Film, H, W, compose, esc, slide
from live_demo import pack, serve
from live_e2e import capture
from textual.widgets import DataTable, Input

from contextlayer_tui.api import HttpApi, dev_token
from contextlayer_tui.app import ContextLayerApp

SIZE = (160, 42)
CATALOG = Path(__file__).resolve().parents[2] / "service" / "governance" / "controls.yaml"

# Two ERPs that hold the same parts under the same SKU: a link is about any object, not only people.
EAST = {"items": [{"itemId": "E-7781", "sku": "AB-100", "description": "Pump seal kit", "onHand": 14},
                  {"itemId": "E-7790", "sku": "CD-220", "description": "Bearing, 40 mm", "onHand": 3}]}
WEST = {"items": [{"itemId": "P-100", "sku": "ab-100 ", "description": "Seal kit for pump", "onHand": 2},
                  {"itemId": "P-310", "sku": "EF-310", "description": "Gasket set", "onHand": 40}]}

BG = ('<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/>'
      '<stop offset="1" stop-color="#12304a"/></linearGradient></defs>'
      f'<rect width="{W}" height="{H}" fill="url(#g)"/>')


def header(title: str) -> str:
    return ('<rect x="110" y="70" width="10" height="90" fill="#2dd4bf"/>'
            f'<text x="145" y="140" font-family="Arial" font-weight="bold" font-size="62" fill="#ffffff">{esc(title)}</text>')


def text(x: float, y: float, value: str, size: int = 26, fill: str = "#cfe3f5", bold: bool = False) -> str:
    weight = ' font-weight="bold"' if bold else ""
    return f'<text x="{x}" y="{y}" font-family="Arial" font-size="{size}"{weight} fill="{fill}">{esc(value)}</text>'


def svg(*parts: str) -> str:
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">{BG}{"".join(parts)}</svg>'


def flow_slide() -> str:
    boxes = [("Sources", "claims · wealth\nCRM · two ERPs"), ("Links", "a person confirms\nwhat is the same"),
             ("Watchers", "a question that\nopens cases"), ("Playbook", "a model labels\neach case"),
             ("Decision", "a person decides\nrisky actions"), ("Action", "sent once, to\nthe CRM"),
             ("Evidence", "every control\nrecords a verdict")]
    parts = [header("contextlayer: one Akka system, end to end")]
    bw, gap, x0, y0 = 210, 40, 100, 360
    for i, (name, sub) in enumerate(boxes):
        x = x0 + i * (bw + gap)
        human = name in ("Links", "Decision")
        parts.append(f'<rect x="{x}" y="{y0}" width="{bw}" height="190" rx="18" fill="{"#134e4a" if human else "#16314d"}" '
                     f'stroke="{"#2dd4bf" if human else "#3b6a93"}" stroke-width="3"/>')
        parts.append(f'<text x="{x + bw / 2}" y="{y0 + 62}" text-anchor="middle" font-family="Arial" font-weight="bold" '
                     f'font-size="34" fill="#ffffff">{name}</text>')
        for j, line in enumerate(sub.split("\n")):
            parts.append(f'<text x="{x + bw / 2}" y="{y0 + 112 + j * 34}" text-anchor="middle" font-family="Arial" '
                         f'font-size="26" fill="#cfe3f5">{esc(line)}</text>')
        if i < len(boxes) - 1:
            parts.append(f'<text x="{x + bw + gap / 2}" y="{y0 + 105}" text-anchor="middle" font-family="Arial" '
                         f'font-size="40" fill="#2dd4bf">›</text>')
    lines = ["57 controls, each one Verify mechanism and one flavor, signed as the system's control contract.",
             "Green boxes are people. Every enforcement point records a verdict under its control's id.",
             "Live: the Akka SDK service, Neo4j, a real model, real requests to pretend sources."]
    for i, line in enumerate(lines):
        parts.append(text(145, 690 + i * 64, line, 36))
    return svg(*parts)


def verify_slide() -> str:
    left = [("Runtime governance (Akka SDK 3.7)", [
                "Predefined sanitizers with control-id, on all four agents",
                "ModelCallSimilarityGuard: report-only jailbreak detection",
                "A custom ModelCallGuardrail: the question model sees shape only",
                "Evaluator components with control-id, judged from the ledger",
                "LedgerClient: every model call's evidence names its ledger record"]),
            ("Human oversight", [
                "The console's human events contract at /v1/context/oversight",
                "Every decision a human event: roles, independence, rationale,",
                "  minimum review time, SLA, escalation, rehearsal"])]
    right = [("Controls and contract", [
                 "Verify's five mechanisms and their flavors as each row's sub-type",
                 "Guardrail hooks, sanitizer / eval / gate configuration blocks",
                 "The console's controls draft, decided and signed (Ed25519)",
                 "The signed bundle (OCI layout), checked by the release at start",
                 "A Verify control pack: 40 corpus selections, 57 pack controls"]),
             ("Evidence", [
                 "A verdict per control in its mechanism's shape: PASS / BLOCK,",
                 "  masked, finding, gate; refusals recorded as well as passes",
                 "Compliance per control, an RDF export in the RTE vocabulary"])]
    parts = [header("What we used from Akka Verify")]
    for col, groups in ((145, left), (1000, right)):
        y = 240
        for title, items in groups:
            parts.append(text(col, y, title, 34, "#2dd4bf", True))
            y += 50
            for item in items:
                parts.append(text(col + 10, y, item, 26))
                y += 42
            y += 34
    return svg(*parts)


def controls_slide(rows: list[dict]) -> str:
    by = {}
    for r in rows:
        by.setdefault(r["mechanism"], {}).setdefault(r["flavor"], []).append(r)
    columns = [["sanitizer", "eval", "gate"], ["guardrail"], ["human"]]
    parts = [header(f"{len(rows)} controls, Verify's five mechanisms")]
    xs = [145, 760, 1360]
    for x, mechanisms in zip(xs, columns):
        y = 225
        for mechanism in mechanisms:
            flavors = by.get(mechanism, {})
            count = sum(len(v) for v in flavors.values())
            parts.append(text(x, y, f"{mechanism}  ({count})", 30, "#ffffff", True))
            y += 36
            for flavor, items in flavors.items():
                parts.append(text(x + 8, y, flavor, 21, "#2dd4bf"))
                y += 28
                for r in sorted(items, key=lambda r: r["row_id"]):
                    parts.append(text(x + 20, y, f"{r['row_id'].removeprefix('custom-cl')}  {r['name']}", 21))
                    y += 26
            y += 14
    return svg(*parts)


def gaps_slide() -> str:
    groups = [
        ("Verify's control schema (asks 23 to 26)", [
            "Gates run before release only: added runs_at: at-deployment (the release checks itself once)",
            "Guardrail hooks are agent-lifecycle only: 19 guardrails here are not; 5 are not calls at all",
            "No eval flavor for an evidence-integrity check; no sanitizer point for people or messages"]),
        ("SDK and runtime", [
            "An AgentResponseGuardrail cannot see the request, so label and wording checks block in service code",
            "Sanitizer passes are not in the ledger; an application cannot write the ledger; no listing by project",
            "The platform does not load or hold a release to its signed bundle; sign-off keys are trust: local"]),
        ("Constructs Verify does not have", [
            "Case, Watcher, Playbook, Ask, Data domain and Link: built here, each recording verdicts",
            "Its own hash-chained evidence ledger and compliance calculation, beside the console's",
            "Verify's monitoring windows (rate, drift) are better than watchers' match-now triggers"]),
    ]
    parts = [header("Gaps we found")]
    y = 245
    for title, items in groups:
        parts.append(text(145, y, title, 34, "#2dd4bf", True))
        y += 52
        for item in items:
            parts.append(text(160, y, "·  " + item, 28))
            y += 46
        y += 36
    return svg(*parts)


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:9162")
    parser.add_argument("--out", default="demo-engineering")
    args = parser.parse_args()
    out = Path(args.out)
    org = f"acme-{uuid.uuid4().hex[:5]}"
    film = Film(out)
    notes: list[dict] = []
    hooks: list[dict] = []
    rows = yaml.safe_load(CATALOG.read_text(encoding="utf-8"))["rows"]

    # ---- the pretend outside world, and the organization set up in the service
    serve(19181, "/claims", "listClaims", CLAIMS)
    serve(19182, "/accounts", "listAccounts", ACCOUNTS)
    serve(19185, "/items", "listItems", EAST)
    serve(19186, "/items", "listItems", WEST)
    capture(19183, notes, {"openapi": "3.0.0", "servers": [{"url": "http://localhost:19183"}],
                           "paths": {"/claims/{claimId}/notes": {"post": {"operationId": "addNote"}}}})
    capture(19184, hooks)
    roles = {"priya.k": ["operator"], "dan.m": ["operator", "reviewer", "approver"], "rita.s": ["reviewer"],
             "root": ["admin"]}

    def call(user: str, path: str, body: dict | None = None, method: str = "POST") -> dict:
        token = dev_token(user, roles[user], org)
        response = httpx.request(method, f"{args.url}/v1/context{path}", json=body if method == "POST" else None,
                                 headers={"Authorization": f"Bearer {token}"}, timeout=60)
        if response.status_code >= 400:
            raise SystemExit(f"{path}: {response.status_code} {response.text}")
        return response.json() if response.content else {}

    def item_pack(name: str, port: int) -> dict:
        p = pack(name, port, "Item", "listItems", "itemId")
        p["entities"][0]["identityAttributes"] = ["sku"]  # the key these records match on
        return p

    for name, connector in (("claims", pack("claims", 19181, "Claim", "listClaims", "claimId")),
                            ("wealth", pack("wealth", 19182, "Account", "listAccounts", "accountId")),
                            ("erp-east", item_pack("erp-east", 19185)), ("erp-west", item_pack("erp-west", 19186))):
        v = call("priya.k", f"/connectors/{name}/versions", connector)
        call("dan.m", f"/connectors/{name}/versions/{v['hash']}/activate", {"mode": "READ_ONLY", "rationale": "read-only source"})
    crm = {"name": "crm", "version": "1.0.0", "source": {"kind": "openapi", "ref": "http://localhost:19183/openapi.json"},
           "credentialRef": "vault://demo/crm", "entities": [],
           "actions": [{"name": "addNote", "operation": "addNote", "risk": "NEEDS_APPROVAL"}]}
    v = call("priya.k", "/connectors/crm/versions", crm)
    call("dan.m", f"/connectors/crm/versions/{v['hash']}/activate", {"mode": "LIVE", "rationale": "CRM notes are reversible"})
    for route in ("Claims Team B", "role:approver"):
        call("root", f"/routes/{route.replace(':', '%3A').replace(' ', '%20')}", {"url": "http://localhost:19184/hook", "format": "json"})
    call("dan.m", "/assistants/claims-watch", {"purpose": "Escalate claims that are stuck on our side"})
    release = call("dan.m", "/assistants/claims-watch/releases", {"definition": json.dumps(PLAYBOOK)})
    call("dan.m", f"/assistants/claims-watch/releases/{release['releaseId']}/approve", {"rationale": "labels, then one reversible note"})
    call("dan.m", "/assistants/claims-watch/stage", {"stage": "LIVE", "rationale": "shadow report was fine"})

    # Every link waits for a reviewer. The email links were confirmed this morning by rita.s, taking her time over
    # each; Sam's name and birth date match, and the SKU shared by the two ERPs, are left for the film.
    for name, entity in (("claims", "Claim"), ("wealth", "Account"), ("erp-east", "Item"), ("erp-west", "Item")):
        call("priya.k", f"/connectors/{name}/entities/{entity}/refresh")
    deadline = time.time() + 30
    email: list[dict] = []
    while time.time() < deadline:
        email = [c for c in call("rita.s", "/identity/review", method="GET")["items"] if c["rule"] == "email"]
        if len(email) >= 3:
            break
        await asyncio.sleep(1)
    for c in email:
        call("rita.s", "/identity/open", {"rule": c["rule"], "key": c["key"], "rationale": ""})
    await asyncio.sleep(6)
    for c in email:
        call("rita.s", "/identity/confirm", {"rule": c["rule"], "key": c["key"],
                                             "rationale": "same email in both systems, and the names agree"})
    print(f"organization {org} is ready ({len(email)} email links confirmed); recording")

    api = HttpApi(args.url, org, dev_token("dan.m", roles["dan.m"], org))
    app = ContextLayerApp(api, org=org, org_title="Acme Insurance (live service)")

    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause(1.0)

        async def shot(caption: str) -> None:
            await pilot.pause(0.5)
            film.add(compose(app.export_screenshot(), caption))

        def table(pane: str) -> DataTable:
            return app.query_one(f"#{pane}-pane #table", DataTable)

        def select(pane: str, contains: str) -> None:
            grid = table(pane)
            for i in range(grid.row_count):
                if any(contains in str(cell) for cell in grid.get_row_at(i)):
                    grid.move_cursor(row=i)
                    return

        async def give(answer: str, caption: str) -> None:
            await pilot.pause(0.5)
            app.screen.query_one("#answer", Input).value = answer
            await shot(caption)
            await pilot.click("#ok")
            await pilot.pause(1.2)

        # ---- opening slides
        film.beat("slide-title", "This is contextlayer, built as the reference case for Akka Verify. It is one Akka system: "
                  "an S D K service whose fifty-seven controls each have one Verify mechanism and one flavor, "
                  "decided and signed in the local console as its control contract.")
        film.add(slide("contextlayer on Akka Verify",
                       ["One Akka system, governed end to end.", "57 controls: one mechanism, one flavor each.",
                        "Signed in the console; the release checks it at start."], "For Akka engineering · October 2026"))
        film.beat("slide-flow", "It reads sources, links records that are the same person or thing once a person confirms it, "
                  "and watchers open cases that a playbook judges with a model. Risky actions wait for a decision. "
                  "Every enforcement point records a verdict under its control's id. Everything you will see is live.")
        film.add(flow_slide())

        # ---- sources
        film.beat("sources", "Five sources are connected. Claims, wealth and two E R P catalogs are read-only; "
                  "the C R M is the only one a playbook can write to. Each connector declares the keys its records match on: "
                  "email, name and birth date for people, and S K U for the parts in the E R Ps.")
        app.action_goto("sources")
        await pilot.pause(1.0)
        await shot("Sources: five connectors; each declares the keys its records match on")

        # ---- ask
        film.beat("ask", "A question across sources: open claims over thirty days whose client holds more than two hundred fifty thousand. "
                  "Two rows come back, through email links a reviewer confirmed this morning. The report warns that one more would match "
                  "through a link nobody has reviewed yet. It is left out, not guessed.")
        app.action_goto("ask")
        await pilot.pause(0.6)
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await shot("Two rows, and a warning: one more would match through a link not yet reviewed")

        # ---- links
        film.beat("links", "The Links tab holds what is waiting. Sam's claim and account share only a name and birth date. "
                  "And the two E R Ps hold one part under the same S K U: a link is about any object, not only a person. "
                  "I open Sam's link, the review clock starts, I read it, and I confirm it with a reason. "
                  "Nobody who loaded the records may confirm them, and every decision is a verdict under control four.")
        app.action_goto("identity")
        await pilot.pause(0.8)
        select("identity", "sku")
        await pilot.pause(0.6)
        await shot("Links: one part under the same SKU in two ERPs, waiting for a person")
        select("identity", "name-dob")
        await pilot.press("s")
        await pilot.pause(0.6)
        await shot("Sam's name and birth date match: the first press opens it, and the review clock starts")
        await asyncio.sleep(6.5)
        app.query_one("#identity-pane").show_detail()
        await pilot.press("s")
        await pilot.pause(0.6)
        await give("Same name, birth date and employer; the email differs because of a mailbox change",
                   "A reason is required, and goes on the record under my name and role")
        await shot("Linked as the same")

        film.beat("ask-again", "Ask again, and the third row is included.")
        app.action_goto("ask")
        await asyncio.sleep(3)
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await shot("Three rows. Nothing is left out any more")

        # ---- watcher
        film.beat("watch", "I turn the question into a watcher. Every hour, and whenever its data changes, "
                  "a new match opens a case, handled by the claims-watch playbook and routed to Claims Team B.")
        app.query_one("#ask-return", Input).value = "Claim.claimId, Claim.daysOpen, Claim.waitingOn, Account.balance"
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await pilot.press("f2")
        await pilot.pause(0.6)
        app.screen.query_one("#name", Input).value = "stalled-claims"
        app.screen.query_one("#every", Input).value = "60"
        app.screen.query_one("#assistant", Input).value = "claims-watch"
        app.screen.query_one("#route", Input).value = "Claims Team B"
        await shot("Watch it: a watcher that runs every hour, handled by claims-watch, routed to Claims Team B")
        await pilot.click("#ok")
        await pilot.pause(1.0)

        print("waiting for cases, model labels and decisions ...")
        deadline = time.time() + 180
        while time.time() < deadline:
            await app.refresh_all(quiet=True)
            if len(app.query_one("#cases-pane").items) >= 3 and len(app.query_one("#decisions-pane").items) >= 2:
                break
            await asyncio.sleep(3)
        await asyncio.sleep(2)
        await app.refresh_all(quiet=True)

        # ---- cases
        film.beat("cases", "A minute later the cases are open and the team has been told. For each case the playbook showed the model "
                  "only the two fields its step lists, with personal data masked, and the answer had to be one of the offered labels. "
                  "Each of those is its own control and recorded its own verdict. For the cases blocked on us it asked to add a note "
                  "in the C R M, a risky action, so it stopped and waited for a person.")
        app.action_goto("cases")
        await pilot.pause(0.8)
        select("cases", "CL-889")
        await pilot.pause(1.2)
        await shot("Cases: the watcher's finds, the model's label, and an action waiting for a decision")

        # ---- decisions
        film.beat("decisions", "This is the decision. It says what is asked, who asked, what to check, and how long the control expects. "
                  "The person who requested an action may not decide it, or even claim it. I open it, read, and approve with a reason. "
                  "Only now is the action sent, once, with an idempotency key.")
        app.action_goto("decisions")
        await pilot.pause(0.8)
        select("decisions", "crm.addNote")  # link reviews wait here too; this one is the action
        await pilot.pause(0.6)
        await shot("Decisions: what is asked, who asked, what to check, and what the control expects")
        await pilot.press("a")
        await pilot.pause(0.6)
        await asyncio.sleep(11)
        select("decisions", "crm.addNote")
        app.query_one("#decisions-pane").show_detail()
        await shot("Opened and read: after the minimum review time, the decision is not flagged as rushed")
        await pilot.press("a")
        await pilot.pause(0.6)
        await give("The note is reversible and the label matches the facts", "Approve, with a reason")
        deadline = time.time() + 40
        while time.time() < deadline and not notes:
            await asyncio.sleep(1)
        await asyncio.sleep(2)

        film.beat("result", "The case shows the decision and that the action succeeded. The C R M received exactly one request.")
        app.action_goto("cases")
        await pilot.pause(1.5)
        await app.refresh_all(quiet=True)
        await pilot.pause(1.0)
        if notes:
            select("cases", notes[0]["path"].split("/")[2])
            await pilot.pause(2.0)
        sent = f"POST {notes[0]['path']}  Idempotency-Key {notes[0]['key'][-16:]}" if notes else "no request"
        await shot(f"Succeeded. The CRM received {len(notes)} request: {sent}")

        # ---- halt
        film.beat("halt", "One approver can halt the playbook at once. From then on its actions are refused, approvals that land "
                  "after the halt do not run, and its runs stop: that enforcement is its own guardrail. Restarting takes two approvers.")
        app.action_goto("playbooks")
        await pilot.pause(1.0)
        await pilot.press("h")
        await give("Testing the stop switch for the demo", "One approver can halt a playbook at once")
        await pilot.pause(1.5)
        await shot("Halted: nothing it asks for is done. Restarting needs two different approvers")

        # ---- governance
        film.beat("governance", "Governance lists all fifty-seven controls with where their evidence stands, and the contract: "
                  "bundle one, signed in the console, every control running exactly as signed. Each control names its Verify "
                  "mechanism and flavor; a guardrail also names its hook.")
        app.action_goto("governance")
        await pilot.pause(1.5)
        await shot("Governance: 57 controls, their evidence, and the signed contract in force")
        await pilot.press(*["down"] * 8)
        await pilot.pause(0.8)
        await shot("Allowed Hosts Only: a guardrail, guardrail-tool-permission, at before-tool-call")
        await pilot.press(*["down"] * 48)
        await pilot.pause(0.8)
        await shot("Draft Matches Signed Bundle: a gate, run before release and once when the release starts")

    # ---- closing slides
    film.beat("slide-verify", "What we used from Akka Verify: the runtime's sanitizers, guardrails and evaluators, each bound to a control id; "
              "the ledger; the console's human events contract; Verify's five mechanisms and their flavors as each control's sub-type; "
              "the controls draft and its signed bundle, which the release checks when it starts; and a Verify control pack.")
    film.add(verify_slide())
    film.beat("slide-controls", "These are the fifty-seven controls: five sanitizers, twenty-six guardrails, seventeen human controls, "
              "seven evals and two gates. A control that did two things became two controls, each with its own id.")
    film.add(controls_slide(rows))
    film.beat("slide-gaps", "And the gaps. Verify's gates run only before release, so we propose gates that run once when a release starts. "
              "Guardrail hooks cover only an agent's lifecycle, and nineteen of our guardrails are not on an agent. "
              "In the S D K, a response guardrail cannot see its request, and an application cannot write to the ledger. "
              "The platform does not yet hold a release to its signed bundle. And cases, watchers, playbooks, asks, data domains "
              "and links are constructs Verify does not have yet.")
    film.add(gaps_slide())
    film.beat("closing", "One Akka system, fifty-seven controls, a signed contract, and a verdict for every decision it makes.")
    film.add(slide("Govern it. Sign it. Prove it.", ["Every control: one Verify mechanism, one flavor.",
                                                     "Every enforcement point: a verdict under its id.",
                                                     "The contract signed in the console, checked at start."],
                   "contextlayer · Akka Verify reference system"))
    film.save()
    print(f"recorded {film.count} frames in {len(film.beats)} beats -> {out / 'beats.json'}")


if __name__ == "__main__":
    asyncio.run(main())
