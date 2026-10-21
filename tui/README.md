# contextlayer-tui

A console for the context layer service, built with [Textual](https://textual.textualize.io/). It
shows what the service knows and lets a person do the things only a person should: say whether two
records are one person, approve or reject what a playbook wants to do, close cases, and move a
playbook from draft to shadow to live. Every decision asks for a reason and goes on the record with
your name and role, and a Governance tab shows whether the evidence says each control is working.

## Try it

    uv run contextlayer-tui --demo

`--demo` runs on built-in sample data (no service needed) and follows the same rules the real service
does: a risky action waits for approval, an unconfirmed link is left out of answers, a source
that fails gives stale data that says so.

Against a running service:

    uv run contextlayer-tui --url https://context.example.com --org acme --token "$TOKEN"

You sign in with a bearer token from your identity provider (`--token`, or `$CONTEXTLAYER_TOKEN`). The
service checks it; the console reads it only to show your name and roles in the banner. What you may do
follows from the roles in the token: `viewer` looks, `operator` asks and builds, `reviewer` judges
links and cases, `approver` authorizes, `admin` also waives controls.

Against a service running on your own machine, where the runtime does not check signatures, leave the
token out and say who you are:

    uv run contextlayer-tui --org demo --user dan.m --roles operator,reviewer,approver

That makes an unsigned token. A deployed service refuses it.

## The tabs

| Key | Tab | What it is for |
|---|---|---|
| 1 | Sources | Each connected source, its mode, and how its last read went. `f` reads it now. |
| 2 | Links | Records that may be the same person or thing, matched on a key their connectors declare. `s` same, `d` different. |
| 3 | Ask | Ask across sources. `ctrl+r` runs it, `F2` watches it: it becomes a watcher, and new matches open cases. The report beside the answer shows how fresh each source was. |
| 4 | Cases | What watchers found, the evidence, and the timeline. `r` resolve, `x` dismiss, `n` note, `m` the model call behind it and the runtime's verdicts on it. |
| 5 | Decisions | Things that cannot happen until a person says yes or no: actions, releases, stages, connectors, halts. `a` opens one, and the next `a` decides it; `x` rejects, `e` escalates, `b` sends back, `r` releases. Deciding before the control's minimum review time asks first and is recorded as rushed. |
| 6 | Playbooks | Stage, releases, the steps of the newest release, and what a shadow playbook would have done. `p` promote, `b` step back, `a` approve a release, `e` edit the steps, `n` new playbook. |
| 7 | Watchers | Questions that run on a schedule and open cases. `n` run now, `e` pause or resume. |
| 8 | Governance | Each control, whether its evidence upholds it, and what proves it. `w` waive, `l` lift a waiver (admin). |
| g | Glossary | Every term the console uses, in plain words, and why it matters. Needs no service. |

`F5` refreshes everything. Each tab also reloads when you switch to it. A background refresh runs every
15 seconds and only changes the banner if the service goes away.

## The playbook editor

`e` on the Playbooks tab opens the newest release's steps; `n` starts a new playbook. The steps are drawn as boxes
from top to bottom, and the form beside them edits the selected one:

| Step | What it does |
|---|---|
| Judge | A model picks one of the labels, seeing only the fields named |
| Choose | Goes to a step by the label a Judge step kept |
| Act | Asks to change something in a source; the list offers what live connectors allow, with each action's risk |
| Note | Writes on the case timeline |

`PgUp`/`PgDn` move between steps, `ctrl+n` adds one after the selected step, `ctrl+d` deletes it (steps that went to
it go where it went), and `ctrl+s` saves. Every edit is checked by the service as you type, and what it would refuse is
listed at the bottom. Saving adds a release; it runs only once the playbook's owner approves it.

## The question language

    Show     Claim
    Where    status = OPEN and daysOpen > 30
    Linked   Account where balance > 250000 ; Client
    Return   Claim.claimId, Account.balance

Nothing is sent as query text. The service takes the parts as data and refuses anything it does not
understand, and it only ever joins records through confirmed links.

## Against a real service with real-looking data

Start the service with local sources allowed and a development credential, then seed it:

    CONTEXTLAYER_ALLOWED_HOSTS=localhost CONTEXTLAYER_DEV_SECRET=demo-token NEO4J_PASSWORD=... (start the service)
    uv run python scripts/live_demo.py        # leave running; it serves two pretend sources
    uv run contextlayer-tui --org demo --user dan.m --roles operator,reviewer,approver

Without `GOOGLE_AI_GEMINI_API_KEY` on the service, the playbook's judge step fails and the case timeline says so.

## Tests

    uv run pytest                      # 66 tests, no service needed
    CONTEXTLAYER_LIVE_URL=http://localhost:9162 uv run pytest tests/test_live.py   # contract check on a real service

The screen tests drive the real app with Textual's test pilot against the demo service. The live test
names every field a screen reads, so a change in the service's responses fails with what went missing.
