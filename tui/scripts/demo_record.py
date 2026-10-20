"""Records the product demo: drives the real console against a running service and a real model.

    1. start the service (Gemini key set, CONTEXTLAYER_ALLOWED_HOSTS=localhost, CONTEXTLAYER_DEV_SECRET=demo-token)
    2. uv run python scripts/demo_record.py --out demo          # captures frames + beat list (a few minutes)
    3. uv run python scripts/demo_build.py --out demo           # narration, then the video

Each beat is one sentence-group of narration over one or more frames. Nothing is mocked: the cases,
the model's labels, the approvals, the request the CRM receives and the compliance report are the
service's own. Waiting for the service is cut from the film and says so in the caption.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
import uuid
from pathlib import Path

import httpx
import resvg_py
from textual.widgets import DataTable, Input

from contextlayer_tui.api import HttpApi, dev_token
from contextlayer_tui.app import ContextLayerApp
from live_demo import pack, serve
from live_e2e import capture

W, H, CAPTION_H = 1920, 1080, 84
FONTS = ["C:/Windows/Fonts"]
SIZE = (160, 42)

CLAIMS = {"items": [
    {"claimId": "CL-889", "client": "Pat Lee", "email": "pat@example.com", "name": "Pat Lee", "dob": "1980-01-02",
     "status": "OPEN", "daysOpen": 41, "waitingOn": "us"},
    {"claimId": "CL-871", "client": "Sam Ortiz", "email": "sam.o@claims.example", "name": "Sam Ortiz", "dob": "1990-05-05",
     "status": "OPEN", "daysOpen": 36, "waitingOn": "client"},
    {"claimId": "CL-860", "client": "Dana Wu", "email": "dana@example.com", "name": "Dana Wu", "dob": "1975-03-03",
     "status": "OPEN", "daysOpen": 33, "waitingOn": "us"},
    {"claimId": "CL-802", "client": "Lee Chan", "email": "lee@example.com", "name": "Lee Chan", "dob": "1968-07-19",
     "status": "CLOSED", "daysOpen": 12, "waitingOn": "client"},
]}
ACCOUNTS = {"items": [
    {"accountId": "W-1", "email": "pat@example.com", "name": "Pat Lee", "dob": "1980-01-02", "balance": 412000},
    {"accountId": "W-2", "email": "sam@wealth.example", "name": "Sam Ortiz", "dob": "1990-05-05", "balance": 288500},
    {"accountId": "W-3", "email": "dana@example.com", "name": "Dana Wu", "dob": "1975-03-03", "balance": 301200},
    {"accountId": "W-4", "email": "lee@example.com", "name": "Lee Chan", "dob": "1968-07-19", "balance": 9000},
]}

INSTRUCTION = ("Decide how badly this client's open insurance claim is affecting them. Apply these rules in order and "
               "stop at the first that fits. 1. MINOR: the claim is under 14 days old, or it is moving, or we are waiting "
               "on the client or a third party. 2. BLOCKED: the claim cannot move until we act and it has been open more "
               "than 30 days. 3. DEGRADED: anything else.")
PLAYBOOK = {"steps": [
    {"id": "judge", "type": "classify", "instruction": INSTRUCTION, "fields": ["Claim.daysOpen", "Claim.waitingOn"],
     "labels": ["BLOCKED", "DEGRADED", "MINOR"], "store": "impact", "next": "route"},
    {"id": "route", "type": "branch", "var": "impact", "cases": {"BLOCKED": "act"}, "otherwise": "end"},
    {"id": "act", "type": "act", "actionType": "crm.addNote",
     "payload": "{\"claimId\":\"{Claim.claimId}\",\"text\":\"Escalated by claims-watch: judged {impact}\"}", "next": "end"},
]}

# ---- drawing


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def render(svg: str, path: Path) -> None:
    png = resvg_py.svg_to_bytes(svg_string=svg, font_dirs=FONTS, dpi=96)
    path.write_bytes(bytes(png))


def compose(tui_svg: str, caption: str) -> str:
    inner = re.sub(r"^<\?xml[^>]*>\s*", "", tui_svg)
    vw, vh = (float(v) for v in re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', inner).groups())
    area_h = H - CAPTION_H
    scale = min(W / vw, area_h / vh)
    iw, ih = vw * scale, vh * scale
    x, y = (W - iw) / 2, (area_h - ih) / 2
    inner = inner.replace("<svg ", f'<svg x="{x:.1f}" y="{y:.1f}" width="{iw:.1f}" height="{ih:.1f}" ', 1)
    bar = (f'<rect x="0" y="{H - CAPTION_H}" width="{W}" height="{CAPTION_H}" fill="#0f1b2d"/>'
           f'<rect x="0" y="{H - CAPTION_H}" width="{W}" height="3" fill="#2dd4bf"/>'
           f'<text x="{W / 2}" y="{H - CAPTION_H / 2 + 12}" text-anchor="middle" font-family="Arial" font-size="34" '
           f'fill="#e6edf7">{esc(caption)}</text>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
            f'<rect width="{W}" height="{H}" fill="#0b1220"/>{inner}{bar}</svg>')


def slide(title: str, lines: list[str], footer: str = "") -> str:
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
           '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/>'
           '<stop offset="1" stop-color="#12304a"/></linearGradient></defs>',
           f'<rect width="{W}" height="{H}" fill="url(#g)"/>',
           f'<rect x="140" y="170" width="10" height="130" fill="#2dd4bf"/>',
           f'<text x="180" y="262" font-family="Arial" font-weight="bold" font-size="84" fill="#ffffff">{esc(title)}</text>']
    y = 420
    for line in lines:
        out.append(f'<text x="180" y="{y}" font-family="Arial" font-size="46" fill="#cfe3f5">{esc(line)}</text>')
        y += 78
    if footer:
        out.append(f'<text x="180" y="{H - 110}" font-family="Arial" font-size="34" fill="#2dd4bf">{esc(footer)}</text>')
    out.append("</svg>")
    return "".join(out)


def flow_slide() -> str:
    boxes = [("Systems", "claims · wealth · CRM"), ("Identity", "a person confirms\nthe links"),
             ("Monitors", "watch a question,\nopen cases"), ("Assistant", "a model labels\neach case"),
             ("Approval", "a person decides\nrisky actions"), ("Action", "sent once, to\nthe CRM"),
             ("Evidence", "every step\nis recorded")]
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
           '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/>'
           '<stop offset="1" stop-color="#12304a"/></linearGradient></defs>',
           f'<rect width="{W}" height="{H}" fill="url(#g)"/>',
           '<rect x="140" y="110" width="10" height="100" fill="#2dd4bf"/>',
           '<text x="180" y="185" font-family="Arial" font-weight="bold" font-size="72" fill="#ffffff">'
           'A governed context layer</text>']
    bw, gap, x0, y0 = 210, 40, 100, 420
    for i, (name, sub) in enumerate(boxes):
        x = x0 + i * (bw + gap)
        human = name in ("Identity", "Approval")
        out.append(f'<rect x="{x}" y="{y0}" width="{bw}" height="190" rx="18" fill="{"#134e4a" if human else "#16314d"}" '
                   f'stroke="{"#2dd4bf" if human else "#3b6a93"}" stroke-width="3"/>')
        out.append(f'<text x="{x + bw / 2}" y="{y0 + 62}" text-anchor="middle" font-family="Arial" font-weight="bold" '
                   f'font-size="34" fill="#ffffff">{name}</text>')
        for j, part in enumerate(sub.split("\n")):
            out.append(f'<text x="{x + bw / 2}" y="{y0 + 112 + j * 34}" text-anchor="middle" font-family="Arial" '
                       f'font-size="26" fill="#cfe3f5">{esc(part)}</text>')
        if i < len(boxes) - 1:
            out.append(f'<text x="{x + bw + gap / 2}" y="{y0 + 105}" text-anchor="middle" font-family="Arial" '
                       f'font-size="40" fill="#2dd4bf">›</text>')
    out.append('<text x="180" y="760" font-family="Arial" font-size="40" fill="#cfe3f5">Green boxes are people. '
               'Nothing risky happens without one.</text>')
    out.append('<text x="180" y="830" font-family="Arial" font-size="40" fill="#cfe3f5">'
               'What you will see is live: a real model, real approvals, real requests.</text>')
    out.append("</svg>")
    return "".join(out)


# ---- the recording


class Film:
    def __init__(self, out: Path) -> None:
        self.out = out
        (out / "frames").mkdir(parents=True, exist_ok=True)
        self.beats: list[dict] = []
        self.count = 0

    def beat(self, name: str, narration: str) -> None:
        self.beats.append({"name": name, "narration": narration, "frames": []})

    def add(self, svg: str, caption: str = "") -> None:
        self.count += 1
        path = self.out / "frames" / f"{self.count:03d}.png"
        render(svg, path)
        self.beats[-1]["frames"].append({"path": str(path.relative_to(self.out)), "caption": caption})

    def save(self) -> None:
        (self.out / "beats.json").write_text(json.dumps(self.beats, indent=2), encoding="utf-8")


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:9162")
    parser.add_argument("--out", default="demo")
    args = parser.parse_args()
    out = Path(args.out)
    org = f"acme-{uuid.uuid4().hex[:5]}"
    film = Film(out)
    notes: list[dict] = []
    hooks: list[dict] = []

    # ---- the pretend outside world, and the organization set up in the service
    serve(19181, "/claims", "listClaims", CLAIMS)
    serve(19182, "/accounts", "listAccounts", ACCOUNTS)
    capture(19183, notes, {"openapi": "3.0.0", "servers": [{"url": "http://localhost:19183"}],
                           "paths": {"/claims/{claimId}/notes": {"post": {"operationId": "addNote"}}}})
    capture(19184, hooks)
    roles = {"priya.k": ["operator"], "dan.m": ["operator", "reviewer", "approver"], "root": ["admin"]}

    def call(user: str, path: str, body: dict | None = None) -> dict:
        token = dev_token(user, roles[user], org)
        response = httpx.post(f"{args.url}/v1/context{path}", json=body or {}, headers={"Authorization": f"Bearer {token}"},
                              timeout=60)
        if response.status_code >= 400:
            raise SystemExit(f"{path}: {response.status_code} {response.text}")
        return response.json() if response.content else {}

    for name, port, entity, op, idf in (("claims", 19181, "Claim", "listClaims", "claimId"),
                                         ("wealth", 19182, "Account", "listAccounts", "accountId")):
        v = call("priya.k", f"/connectors/{name}/versions", pack(name, port, entity, op, idf))
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
    print(f"organization {org} is ready; recording")

    api = HttpApi(args.url, org, dev_token("dan.m", roles["dan.m"], org))
    app = ContextLayerApp(api, org=org, org_title="Acme Insurance (live service)")

    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause(1.0)

        async def shot(caption: str) -> None:
            await pilot.pause(0.5)
            film.add(compose(app.export_screenshot(), caption))

        def table(pane: str) -> DataTable:
            return app.query_one(f"#{pane}-pane #table", DataTable)

        async def give(text: str, caption: str) -> None:
            """Types the one-line answer a decision asks for, shows it, and sends it."""
            await pilot.pause(0.5)
            app.screen.query_one("#answer", Input).value = text
            await shot(caption)
            await pilot.click("#ok")
            await pilot.pause(1.2)

        # ---- opening slides
        film.beat("slide-problem", "Companies run on dozens of systems that disagree about who a customer is. "
                  "AI assistants could help, but only if they can see across those systems, and only if a person stays in charge.")
        film.add(slide("A governed context layer",
                       ["Ask across the systems you already run.", "Let AI assistants do the legwork.",
                        "Keep a person in charge, and be able to prove it."], "Built on the Akka SDK"))
        film.beat("slide-how", "This is a governed context layer. It joins records through links a person has confirmed. "
                  "Monitors watch for problems, and an assistant judges each case with a model. Risky actions wait for human approval, "
                  "and every step leaves evidence. What you will see is live, with a real model.")
        film.add(flow_slide())

        # ---- systems
        film.beat("systems", "Three systems are connected. Claims and the wealth platform are read-only. "
                  "The C R M is the only one an assistant can write to. Let's read the first two now.")
        await shot("Systems: what is connected, and how each last read went")
        app.action_goto("systems")
        await pilot.pause(0.5)
        await pilot.press("f")
        await pilot.pause(1.5)
        await pilot.press("down", "f")
        await pilot.pause(2.0)
        await shot("Press f to read a system now")

        # ---- ask
        film.beat("ask", "Now a question across systems: open claims over thirty days, whose client holds more than "
                  "two hundred fifty thousand. Two rows come back. But the report warns that one more would match, "
                  "through a link still waiting for a person. It is left out, not guessed.")
        app.action_goto("ask")
        await pilot.pause(0.6)
        await shot("Ask: claims over 30 days whose client holds more than $250,000")
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await shot("Two rows, and a warning: one more would match through an unconfirmed link")

        # ---- identity
        film.beat("identity", "Here is that link: two records that share a name and a birth date, but not an email. "
                  "I open it and read the evidence, and the console records how long I looked. Then I say they are the same person, "
                  "and give a reason, which goes on the record under my name and role.")
        app.action_goto("identity")
        await pilot.pause(0.8)
        await shot("Identity: two records that may be one person")
        await pilot.press("s")
        await pilot.pause(0.6)
        await shot("First press opens it: the review clock starts")
        await asyncio.sleep(6.5)
        app.query_one("#identity-pane").show_detail()
        await shot("Read it. The console is timing the review")
        await pilot.press("s")
        await pilot.pause(0.6)
        await give("Same name, birth date and employer; different email only because of a mailbox change", "A reason is required, and goes on the record")
        await shot("Linked as one person")

        # ---- ask again
        film.beat("ask-again", "Ask again, and the third row is now included.")
        app.action_goto("ask")
        await asyncio.sleep(3)
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await shot("Three rows. Nothing is left out any more")

        # ---- watch
        film.beat("watch", "I turn the question into a monitor. Every hour, new matches open a case, "
                  "handled by this assistant, and routed to Claims Team B.")
        app.query_one("#ask-return", Input).value = "Claim.claimId, Claim.daysOpen, Claim.waitingOn, Account.balance"
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await pilot.press("f2")
        await pilot.pause(0.6)
        app.screen.query_one("#name", Input).value = "stalled-claims"
        app.screen.query_one("#every", Input).value = "60"
        app.screen.query_one("#assistant", Input).value = "claims-watch"
        app.screen.query_one("#route", Input).value = "Claims Team B"
        await shot("Watch it: run every hour, assistant claims-watch, route to Claims Team B")
        await pilot.click("#ok")
        await pilot.pause(1.0)

        # ---- cut: wait for the service
        print("waiting for cases, model labels and approvals ...")
        deadline = time.time() + 150
        while time.time() < deadline:
            await app.refresh_all(quiet=True)
            cases = len(app.query_one("#cases-pane").items)
            waiting = len(app.query_one("#approvals-pane").items)
            if cases >= 3 and waiting >= 2:
                break
            await asyncio.sleep(3)
        await asyncio.sleep(2)
        await app.refresh_all(quiet=True)

        # ---- cases
        film.beat("cases", "A minute later the cases are open and Claims Team B has been told. For each one the assistant "
                  "asked the model to judge the facts, with personal data masked, and the answer must be one of the allowed labels. "
                  "Two are blocked on us. One is waiting on the client, so the assistant leaves it alone. "
                  "For the blocked ones it asked to add a note in the C R M, a risky action, so it stopped and waited for a person.")
        app.action_goto("cases")
        await pilot.pause(0.8)
        grid = table("cases")
        for i in range(grid.row_count):
            if "CL-889" in str(grid.get_row_at(i)[1]):
                grid.move_cursor(row=i)
        await pilot.pause(1.2)
        await shot("A minute later: three cases, the team notified, the model's label, and an action waiting")

        # ---- approvals
        film.beat("approvals", "This is the approval. It says what is asked, what to check, and how long the control expects me to spend. "
                  "First I open it, then I read. If I decided too quickly, the console would say so and the decision would be recorded as rushed. "
                  "I approve, with a reason. Only now is the action sent, once, with an idempotency key.")
        app.action_goto("approvals")
        await pilot.pause(0.8)
        await shot("Approvals: what is asked, who asked, what to check, and what the control expects")
        await pilot.press("a")
        await pilot.pause(0.6)
        await shot("Press a to open it. The clock starts")
        await asyncio.sleep(11)
        app.query_one("#approvals-pane").show_detail()
        await shot("Read it. After the minimum review time, deciding is not flagged")
        await pilot.press("a")
        await pilot.pause(0.6)
        await give("The note is reversible and the label matches the facts", "Approve, with a reason")
        await shot("Approved. It goes to the CRM once")
        deadline = time.time() + 40
        while time.time() < deadline and not notes:
            await asyncio.sleep(1)
        await asyncio.sleep(2)

        film.beat("result", "The case shows the approval and that the action succeeded, and the C R M received exactly one request.")
        app.action_goto("cases")
        await pilot.pause(1.5)
        await app.refresh_all(quiet=True)
        await pilot.pause(1.0)
        if notes:  # show the case the request was for
            claim = notes[0]["path"].split("/")[2]
            grid = table("cases")
            for i in range(grid.row_count):
                if claim in str(grid.get_row_at(i)[1]):
                    grid.move_cursor(row=i)
            await pilot.pause(2.0)
        sent = f"POST {notes[0]['path']}  Idempotency-Key {notes[0]['key'][-16:]}" if notes else "no request"
        await shot(f"Succeeded. The CRM received {len(notes)} request: {sent}")

        # ---- halt
        film.beat("halt", "If anything looks wrong, one approver can halt the assistant. Everything it asks for is refused until two different approvers restart it.")
        app.action_goto("assistants")
        await pilot.pause(1.0)
        await shot("Assistants: stage, releases, and what it has done")
        await pilot.press("h")
        await give("Testing the stop switch for the demo", "One approver can halt an assistant at once")
        await pilot.pause(1.5)
        await shot("Halted: nothing it asks for is done. Restarting needs two approvers")

        # ---- governance
        film.beat("governance", "Governance lists every control, and whether the evidence says it is working. "
                  "Masking personal data, checking labels, approvals, review time, and who may halt. "
                  "Only enforcing evidence counts, so a rehearsal or a shadow run never counts as proof, and all of it can be exported as an audit trail.")
        app.action_goto("governance")
        await pilot.pause(1.5)
        await shot("Governance: each control, and what its evidence says")
        await pilot.press("down", "down")
        await pilot.pause(0.8)
        await shot("What it does, what proves it, and who decides")

    film.beat("closing", "Ask across systems. Let assistants act. Keep a person in charge, and prove it.")
    film.add(slide("Ask. Act. Prove it.", ["Identity links a person confirms.", "Assistants judged by a real model, masked and checked.",
                                           "Risky actions held for approval, sent once.", "Every decision recorded with who, why, and how long."],
                   "Governed by design"))
    film.save()
    print(f"recorded {film.count} frames in {len(film.beats)} beats -> {out / 'beats.json'}")


if __name__ == "__main__":
    asyncio.run(main())
