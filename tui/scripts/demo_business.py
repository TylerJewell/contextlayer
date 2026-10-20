"""Records the longer demo for business viewers: the real console over sample data, with plain-language narration.

    uv run python scripts/demo_business.py --out demo-business        # frames and the beat list
    uv run python scripts/demo_build.py --out demo-business --name contextlayer-business --crf 23 --rate -1

The console runs on its sample data, which the opening slide and the banner both say. Nothing in
the film is shown as a customer or a real record. Each beat is a few sentences over one or more
frames; the build step speaks them and times the frames to the speech.
"""

from __future__ import annotations

import argparse
import asyncio
import re
from pathlib import Path

from textual.widgets import Input

from contextlayer_tui.app import ContextLayerApp
from contextlayer_tui.demo import DemoApi
from demo_record import Film, esc, render, slide

W, H, CAPTION_H = 1920, 1080, 96
SIZE = (128, 36)  # a smaller grid scales up, so the text stays readable on a phone

BG = '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/><stop offset="1" stop-color="#12304a"/></linearGradient></defs>'


def frame(tui_svg: str, caption: str) -> str:
    inner = re.sub(r"^<\?xml[^>]*>\s*", "", tui_svg)
    vw, vh = (float(v) for v in re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', inner).groups())
    area_h = H - CAPTION_H
    scale = min(W / vw, area_h / vh)
    iw, ih = vw * scale, vh * scale
    inner = inner.replace("<svg ", f'<svg x="{(W - iw) / 2:.1f}" y="{(area_h - ih) / 2:.1f}" width="{iw:.1f}" height="{ih:.1f}" ', 1)
    bar = (f'<rect x="0" y="{H - CAPTION_H}" width="{W}" height="{CAPTION_H}" fill="#0f1b2d"/>'
           f'<rect x="0" y="{H - CAPTION_H}" width="{W}" height="3" fill="#2dd4bf"/>'
           f'<text x="{W / 2}" y="{H - CAPTION_H / 2 + 14}" text-anchor="middle" font-family="Arial" font-size="40" '
           f'fill="#e6edf7">{esc(caption)}</text>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
            f'<rect width="{W}" height="{H}" fill="#0b1220"/>{inner}{bar}</svg>')


def boxes_slide(title: str, boxes: list[tuple[str, str, bool]], notes: list[str]) -> str:
    """A row of boxes with a short line each. A highlighted box is a person."""
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">', BG,
           f'<rect width="{W}" height="{H}" fill="url(#g)"/>', '<rect x="140" y="110" width="10" height="100" fill="#2dd4bf"/>',
           f'<text x="180" y="185" font-family="Arial" font-weight="bold" font-size="72" fill="#ffffff">{esc(title)}</text>']
    n = len(boxes)
    gap = 36
    bw = (W - 200 - gap * (n - 1)) / n
    for i, (name, sub, person) in enumerate(boxes):
        x = 100 + i * (bw + gap)
        out.append(f'<rect x="{x:.0f}" y="400" width="{bw:.0f}" height="230" rx="18" fill="{"#134e4a" if person else "#16314d"}" '
                   f'stroke="{"#2dd4bf" if person else "#3b6a93"}" stroke-width="3"/>')
        out.append(f'<text x="{x + bw / 2:.0f}" y="465" text-anchor="middle" font-family="Arial" font-weight="bold" font-size="38" fill="#ffffff">{esc(name)}</text>')
        for j, part in enumerate(sub.split("\n")):
            out.append(f'<text x="{x + bw / 2:.0f}" y="{520 + j * 38}" text-anchor="middle" font-family="Arial" font-size="29" fill="#cfe3f5">{esc(part)}</text>')
    y = 760
    for line in notes:
        out.append(f'<text x="180" y="{y}" font-family="Arial" font-size="40" fill="#cfe3f5">{esc(line)}</text>')
        y += 66
    out.append("</svg>")
    return "".join(out)


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="demo-business")
    args = parser.parse_args()
    out = Path(args.out)
    film = Film(out)

    api = DemoApi(subject="maya", roles=["operator", "reviewer", "approver", "admin"])
    api.change_interval = 1.6
    app = ContextLayerApp(api, org="acme", org_title="Acme Insurance (sample data)")

    async with app.run_test(size=SIZE) as pilot:
        await pilot.pause(1.0)

        async def shot(caption: str, wait: float = 0.5) -> None:
            await pilot.pause(wait)
            film.add(frame(app.export_screenshot(), caption))

        async def give(text: str, caption: str) -> None:
            await pilot.pause(0.5)
            app.screen.query_one("#answer", Input).value = text
            await shot(caption)
            await pilot.click("#ok")
            await pilot.pause(1.0)

        # ---- opening
        film.beat("title", "This is a short tour of a governed context layer. It lets an organisation use its own information with "
                  "artificial intelligence, while a person stays in charge and every step leaves a receipt. "
                  "Everything you will see uses sample data. There are no real customers in this film.")
        film.add(slide("Trusted context for AI", ["Use your own information with AI,", "keep a person in charge,", "and keep the receipts."],
                       "A tour on sample data. No real customers appear."))

        film.beat("problem", "Here is the problem. A customer's information lives in many systems, and those systems disagree about who "
                  "the customer is. An assistant that guesses across them can make costly mistakes, and an auditor will later ask who "
                  "approved what, and how you know. This product answers three questions: what do we know, who decided, and can we prove it.")
        film.add(slide("Three questions an auditor asks", ["What do we know, and how current is it?", "Who decided, and on what authority?",
                                                           "Can you prove nothing was changed afterwards?"], "This product answers all three."))

        film.beat("flow", "Here is how the pieces fit. Systems feed in records. A person confirms which records are the same customer. "
                  "Watchers look for problems and open cases. An assistant can suggest an action, but a person approves anything risky. "
                  "And every step is written down. The green boxes are people. Nothing risky happens without one.")
        film.add(boxes_slide("How it fits together", [
            ("Systems", "claims, wealth,\nthe CRM", False), ("People confirm", "which records are\nthe same customer", True),
            ("Watchers", "look for problems,\nopen cases", False), ("Assistant", "suggests an\naction", False),
            ("People approve", "anything risky", True), ("Receipts", "every step is\nwritten down", False)],
            ["Green boxes are people.", "Nothing risky happens without one."]))

        # ---- systems
        film.beat("systems", "Start with where the information lives. This screen lists every connected system and how its last read went. "
                  "Claims and the wealth platform are read only. The customer relationship system is the only one an assistant may write to. "
                  "Notice that one system failed its last read, and the screen says so. A failed system is never shown as empty, because "
                  "an empty answer and an unknown answer mean very different things to a decision maker.")
        app.action_goto("systems")
        await shot("Systems: what is connected, and how each last read went", 1.0)

        film.beat("freshness", "How current is the data? There are four ways it can arrive. It can be read when someone asks. "
                  "It can be remembered for a set time. It can be refreshed on a schedule. Or the source can push each change the moment "
                  "it happens. Every answer says which of these it used, and when, so nobody mistakes old information for fresh.")
        film.add(boxes_slide("How current is the data?", [
            ("When asked", "read at the moment\nof the question", False), ("Remembered", "reused for a\nset time", False),
            ("On a schedule", "refreshed every\nfew minutes", False), ("Pushed", "the source tells us\nthe moment it changes", False)],
            ["Every answer says which one it used, and when.", "A source that goes quiet is reported as stale."]))

        film.beat("live", "Here is the pushed kind. A claims system is sending changes as they happen. A claim is updated, another is "
                  "removed, an account is changed, and each appears here within a second. There is no waiting for the nightly refresh. "
                  "A question left open on this data would update the same way, showing what was added to the answer and what dropped out.")
        app.action_goto("live")
        await pilot.pause(6.5)
        await shot("Live: changes arrive from the systems as they happen")

        # ---- ask, identity
        film.beat("ask", "Now a question across systems: which open claims are older than thirty days, where the client holds more than "
                  "two hundred and fifty thousand? Two rows come back. But look at the warning. One more claim would match, through a link "
                  "between records that a person has not yet confirmed. It is left out of the answer, and the answer says so. It is not guessed.")
        app.action_goto("ask")
        await pilot.pause(0.6)
        await shot("Ask: claims over 30 days whose client holds over $250,000")
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await shot("Two rows, and a warning: one more would match through an unconfirmed link")

        film.beat("identity", "That link is a question of identity. Two records share a name and a birth date, but not an email address. "
                  "Are they the same person? The system will not decide that on its own. A person opens the evidence and reads it, and the "
                  "screen times the review. Then they decide, and they must give a reason, which goes on the record with their name and role.")
        app.action_goto("identity")
        await pilot.pause(0.8)
        await shot("Identity: two records that may be one person")
        await pilot.press("s")
        await pilot.pause(0.6)
        await shot("The first press opens it, and the review clock starts")
        await asyncio.sleep(6.5)
        app.query_one("#identity-pane").show_detail()
        await shot("A person reads the evidence while the clock runs")
        await pilot.press("s")
        await pilot.pause(0.6)
        await give("Same name, birth date and employer; the email differs because of a mailbox change", "A reason is required, and goes on the record")
        await shot("Linked as one person")

        film.beat("ask-again", "Ask the same question again, and the third claim is now in the answer, because a person has confirmed the link. "
                  "Nothing is left out any more.")
        app.action_goto("ask")
        await asyncio.sleep(2.5)
        await pilot.press("ctrl+r")
        await pilot.pause(2.5)
        await shot("Three rows. Nothing is left out any more")

        film.beat("words", "You do not have to know how the data is organised. Type a request in plain words, and the system drafts the "
                  "question for you. The artificial intelligence sees only the shape of the data, such as the names of the fields. "
                  "It never sees a customer record. And it does not run anything. You read the draft, and you decide whether to run it.")
        words = app.query_one("#ask-words", Input)
        words.focus()
        words.value = "stalled claims over a month for wealthy clients"
        await pilot.pause(0.3)
        await pilot.press("enter")
        await pilot.pause(1.0)
        await shot("In plain words: the system drafts the question. A person reads it and runs it")

        film.beat("relationships", "Some links need no person at all. A claims system can say outright which account a claim is on. That is a relationship the "
                  "source declares, so a question can follow it by name, and it can follow it backwards, or through several steps. Ask for claims on accounts "
                  "holding more than two hundred and fifty thousand, and three come back, with no warning, because nobody had to guess. "
                  "The difference is who vouches for the link: the source system, or a person who confirmed a match.")
        words.value = "open claims over a month on accounts holding over 250000"
        await pilot.pause(0.3)
        await pilot.press("enter")
        await pilot.pause(1.0)
        await shot("A link the source declares: follow HAS_ACCOUNT. No person has to confirm it")
        await pilot.press("ctrl+r")
        await pilot.pause(2.0)
        await shot("Three rows and no warning: the source vouches for this link")

        # ---- domains
        film.beat("domains", "Not everyone should see everything. Information is grouped into domains, here claims and wealth, and each domain "
                  "says who may read it. A question that tries to read a domain you are not granted is refused, with the reason. "
                  "And joining two domains in one question needs a separate approval, from someone other than the person who set the domains up. "
                  "Joining claims to wealth is exactly the kind of thing a privacy officer wants to sign off.")
        app.action_goto("domains")
        await pilot.pause(0.8)
        await shot("Domains: who may read each group of information")
        await pilot.press("down")
        await shot("Linking two domains in one question needs its own approval")

        # ---- cases
        film.beat("cases", "A watcher asks a question on a schedule, or whenever the data changes, and opens a case for every new match. "
                  "Each case shows the evidence behind it, and who has been told. Here are two open cases, each routed to the right team.")
        app.action_goto("cases")
        await pilot.pause(0.8)
        await shot("Cases: what the watchers found, with the evidence and who was told")

        # ---- approvals
        film.beat("approvals", "Now an assistant wants to act. It has proposed adding a note to a customer record. Because that is a change, "
                  "it waits for a person. The approval says what is asked, who asked, what to check, and how long review should take. "
                  "I open it, then I read. If I decided too fast, the decision would be recorded as rushed. "
                  "The person who asked can never be the person who approves. I approve, and give a reason.")
        app.action_goto("approvals")
        await pilot.pause(0.8)
        await shot("Approvals: what is asked, who asked, what to check")
        await pilot.press("a")
        await pilot.pause(0.6)
        await shot("Opening it starts the review clock")
        await asyncio.sleep(11)
        app.query_one("#approvals-pane").show_detail()
        await shot("After the minimum time, deciding is not flagged as rushed")
        await pilot.press("a")
        await pilot.pause(0.6)
        await give("The note is reversible and matches the facts", "Approve, with a reason")
        await shot("Approved. Only now is the action sent, once")

        # ---- assistants
        film.beat("halt", "If anything looks wrong, one approver can stop an assistant at once. Everything it asks for is refused. "
                  "Restarting it takes two different approvers, so the decision to start again is never made alone.")
        app.action_goto("assistants")
        await pilot.pause(1.0)
        await shot("Assistants: each one is drafted, then shadowed, then live")
        await pilot.press("h")
        await give("Stopping it while we check the last batch", "One approver can stop an assistant at once")
        await pilot.pause(1.0)
        await shot("Halted. Nothing it asks for is done. Restarting needs two approvers")

        # ---- governance
        film.beat("governance", "Now the part an auditor cares about. Governance lists every rule the system enforces, and what the evidence says "
                  "about whether each one is working. Only evidence from rules that were actually enforced counts. "
                  "A rehearsal, or a trial run, never counts as proof.")
        app.action_goto("governance")
        await pilot.pause(1.2)
        await shot("Governance: every rule, and what its evidence says")

        film.beat("two-people", "Here is an important detail. For this rule, one person accepted the obligation, and a different person said "
                  "which records count as evidence for it. These are two separate claims, kept apart, each with its own name. "
                  "The system also says what is still missing. The override procedure document has not been attached, so the evidence is only "
                  "partly covered. It does not say compliant when it is not.")
        await pilot.press("down", "down")
        await pilot.pause(0.8)
        await shot("Who accepted the rule, who said what counts as evidence, and what is missing")

        film.beat("audit", "Now ask a question the way an auditor would. A model only chooses which of a fixed list of questions this is, and which control it is about. "
                  "It is shown no records. The facts are then worked out from what was recorded, each with the record it came from. "
                  "And what the records do not show is stated as plainly as what they do. Here the answer says who accepted the rule, who said what counts as evidence, "
                  "and that the override procedure is still missing. A second model puts it in words, and if its words claimed anything the facts do not say, "
                  "such as a number or a verdict, they would be replaced by the facts themselves.")
        await pilot.press("q")
        await give("Can a person override the assistant, and who signed that off?", "Ask in plain words. The answer is built from recorded facts")
        await pilot.pause(0.8)
        await shot("The answer, the facts and where each came from, and what the records do not show")

        film.beat("documents", "The missing document is the override procedure. Someone registers where it is and the date it was last current. "
                  "The system keeps only a fingerprint of its content, so the document can later be shown to be the one registered, and never holds the document itself. "
                  "One document can back several rules, and the newest date counts. Coverage now turns full, and the same question gets a different answer.")
        await pilot.press("escape")
        app.query_one("#governance-pane").show_detail()
        await pilot.press("r")
        await give("https://policies.example/override-procedure-v4", "Register the document: where it is, and that it is current today")
        await pilot.pause(0.8)
        await shot("Coverage is full: the document is held, and current")
        await pilot.press("q")
        await give("Can a person override the assistant, and who signed that off?", "Ask again: the answer has changed with the records")
        await pilot.pause(0.8)
        await shot("The same question, now answered with full coverage and nothing missing")

        film.beat("ledger", "Finally, can anyone change the receipts afterwards? The evidence is chained, like a ledger where each page "
                  "carries a fingerprint of the page before. Checking it tells you the records are intact, and gives you a fingerprint to "
                  "keep somewhere the system cannot write. If anything is ever altered, the check names exactly which record.")
        await pilot.press("i")
        await pilot.pause(1.2)
        await shot("The ledger check: intact, with a fingerprint to keep elsewhere", 0.5)

    film.beat("closing", "To sum up. Your teams can ask questions across systems and see how current each answer is. Changes arrive as they happen. "
              "People decide who is who, who may see what, and what an assistant may do. And every step leaves a receipt you can check. "
              "That is how you let artificial intelligence use your information, with a person in charge.")
    film.add(slide("What you get", ["Answers across systems, with how current they are.", "Links vouched for by a source, or by a person.",
                                    "People decide identity, access and actions.", "Receipts you can check, and gaps shown honestly."],
                   "Built on the Akka platform"))
    film.save()
    print(f"{film.count} frames, {len(film.beats)} beats in {out}")


if __name__ == "__main__":
    asyncio.run(main())
