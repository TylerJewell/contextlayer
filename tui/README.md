# contextlayer-tui

A console for the context layer service, built with [Textual](https://textual.textualize.io/). It
shows what the service knows and lets a person do the things only a person should: say whether two
records are one person, approve or reject what an assistant wants to do, close cases, and move an
assistant from draft to shadow to live. Every decision asks for a reason and goes on the record with
your name and role, and a Governance tab shows whether the evidence says each control is working.

## Try it

    uv run contextlayer-tui --demo

`--demo` runs on built-in sample data (no service needed) and follows the same rules the real service
does: a risky action waits for approval, an unconfirmed identity link is left out of answers, a source
that fails gives stale data that says so.

Against a running service:

    uv run contextlayer-tui --url https://context.example.com --org acme --token "$TOKEN"

You sign in with a bearer token from your identity provider (`--token`, or `$CONTEXTLAYER_TOKEN`). The
service checks it; the console reads it only to show your name and roles in the banner. What you may do
follows from the roles in the token: `viewer` looks, `operator` asks and builds, `reviewer` judges
identity links and cases, `approver` authorizes, `admin` also waives controls.

Against a service running on your own machine, where the runtime does not check signatures, leave the
token out and say who you are:

    uv run contextlayer-tui --org demo --user dan.m --roles operator,reviewer,approver

That makes an unsigned token. A deployed service refuses it.

## The tabs

| Key | Tab | What it is for |
|---|---|---|
| 1 | Sources | Each connected source, its mode, and how its last read went. `f` reads it now. |
| 2 | Identity | Records that may be one person. `s` same person, `d` different people. |
| 3 | Ask | Ask across sources. `ctrl+r` runs it, `F2` watches it as a monitor. The report beside the answer shows how fresh each source was. |
| 4 | Cases | What monitors found, the evidence, and the timeline. `r` resolve, `x` dismiss, `n` note, `m` the model call behind it and the runtime's verdicts on it. |
| 5 | Approvals | Things waiting for a person. `a` opens one, and the next `a` decides it; `x` rejects, `e` escalates, `b` sends back, `r` releases. Deciding before the control's minimum review time asks first and is recorded as rushed. |
| 6 | Assistants | Stage, releases, and what a shadow assistant would have done. `p` promote, `b` step back, `a` approve a release. |
| 7 | Monitors | Questions that run on a schedule. `n` run now, `e` pause or resume. |
| 8 | Governance | Each control, whether its evidence upholds it, and what proves it. `w` waive, `l` lift a waiver (admin). |
| g | Glossary | Every term the console uses, in plain words, and why it matters. Needs no service. |

`F5` refreshes everything. Each tab also reloads when you switch to it. A background refresh runs every
15 seconds and only changes the banner if the service goes away.

## The question language

    Show     Claim
    Where    status = OPEN and daysOpen > 30
    Linked   Account where balance > 250000 ; Client
    Return   Claim.claimId, Account.balance

Nothing is sent as query text. The service takes the parts as data and refuses anything it does not
understand, and it only ever joins records through confirmed identity.

## Against a real service with real-looking data

Start the service with local sources allowed and a development credential, then seed it:

    CONTEXTLAYER_ALLOWED_HOSTS=localhost CONTEXTLAYER_DEV_SECRET=demo-token NEO4J_PASSWORD=... (start the service)
    uv run python scripts/live_demo.py        # leave running; it serves two pretend sources
    uv run contextlayer-tui --org demo --user dan.m --roles operator,reviewer,approver

Without `OPENAI_API_KEY` on the service, the assistant's model step fails and the case timeline says so.

## Tests

    uv run pytest                      # 66 tests, no service needed
    CONTEXTLAYER_LIVE_URL=http://localhost:9162 uv run pytest tests/test_live.py   # contract check on a real service

The screen tests drive the real app with Textual's test pilot against the demo service. The live test
names every field a screen reads, so a change in the service's responses fails with what went missing.
