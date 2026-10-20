# Context Layer

A governed context layer built on the Akka SDK. It answers questions across sources, says how current each answer is, and keeps people in charge of identity, access and actions.

It is also a working reference for Akka Verify: it uses Verify's runtime pieces end to end, and where Verify has no construct for something it needed, it built one. Those constructs, and what they could mean for Verify, are set out below.

| Folder | What it is |
|---|---|
| `service/` | The system: the Akka service (port 9162, Neo4j for the entity graph) and its controls. Start with `service/README.md` and `service/docs/`. |
| `cli/` | The `akka-context` command line client, written in Go. |
| `tui/` | A terminal interface built with Textual, with a built-in demo mode (`--demo`). |
| `claims-system/` | A small demo service (port 9163): a source the system reads from, not part of the system. |

## The system

In the Akka local console's terms this is one **system**: an AI application someone is accountable for, with one id
stamped on its controls, verdicts, traces and spend. "Project" means only an Akka platform project.

| Part | Here |
|---|---|
| Id | `contextlayer` |
| Folder | `service/`: the `pom.xml`, the code, and `service/governance/` |
| Components | The service and its four agents (classifier, question drafter, compliance intent, compliance summary) |
| Controls | `service/governance/controls.yaml`, decided and signed in the console as bundles; the newest bundle is in force |
| Not part of it | `claims-system/`, which is a **source** the system reads |

The local console governs the system (controls, signing, compliance, human oversight); the TUI and the CLI operate it
(sources, asks, cases, playbooks, decisions). The console's Human page and the TUI's Decisions tab read the same human
events, from the service's `/v1/context/oversight` routes.

## The concepts

Each tab of the TUI holds one of these. Sources and identity are particular to this integration domain; the others are
governance constructs that apply to any system.

| Concept | What it is |
|---|---|
| **Source** | A system of record the context layer reads, such as claims or the CRM, through a connector that says how to read it. |
| **Identity link** | A judgement that records from different sources are the same person; a reviewer confirms it before any question may join them. |
| **Ask** | A question put to the records of every connected source at once, in words or as conditions. It never changes anything, it is checked against data domains before it runs, and its answer says how up to date each source was. A model may draft it from words, seeing only the shape of the data; a person runs it. |
| **Case** | A situation a monitor found for a person to look into, with its evidence, notes and a timeline of everything that followed. Nothing waits on it. |
| **Decision** | Something that cannot happen until a person says yes or no, with a reason: a playbook's action, a release or stage change, a connector going live, a halt or restart. Whoever asked for an action cannot approve it. |
| **Playbook** | Fixed steps, approved by the playbook's owner, run on each case a monitor opens for it: a model judges (choosing only from offered labels), a path is chosen, actions are requested, notes are written. Releases go draft → shadow → live, and a playbook can be halted at once and restarted only by two approvers. |
| **Monitor** | A saved question run on a schedule; each new record that matches opens a case. A monitor notices; a playbook acts on what was noticed. |
| **Control** | A rule the system promises to keep, with a stable id that every piece of evidence about it carries. |
| **Bundle** | The system's controls as decided and signed in the console; the newest is in force. |
| **Data domain** | A group of record types that share one rule about who may read them; two domains are linked in one question only under an approved join. |
| **Live** | Records as their sources change them, shown as they arrive. |

A case and a decision both need a person, for different reasons: a case asks someone to look into a situation, and
nothing waits on it; a decision asks whether something may happen, and that thing waits. A playbook's action inside a
case becomes a decision.

## What it uses from Akka

**Akka SDK components** (SDK 3.7.0)

| Component | Used for |
|---|---|
| 14 event sourced entities | Cases, playbooks, actions, monitors, identity clusters, human events, evidence and the integrity chain, control claims, documents, domains, deliveries, connector packs |
| 5 key value entities | Halts, waivers, refresh status, notification routes, the change feed |
| 10 views | Lists over the above |
| 3 workflows | An action from request to outcome (a durable `thenPause` while it waits for a person), a monitor run, a playbook run |
| 4 agents | Classifier, question drafter, and two for questions about the controls |
| 8 consumers, 3 timed actions | Reactions, notifications, review deadlines, scheduled reads |
| 9 HTTP endpoints | `@JWT` and `@Acl` on each |
| Service streams | `claims-system` publishes `entity_changes`; the service consumes it |
| `NotificationPublisher` | The live change feed |
| TestKit, `TestModelProvider` | Tests, with every model mocked |

**Akka Verify in the SDK**

| Feature | Used for |
|---|---|
| Predefined sanitizers with `control-id` | Personal data masked for all four agents (`custom-cl0001`) |
| `ModelCallSimilarityGuard` | Report-only jailbreak detection on all four agents (`custom-cl0010`) |
| A custom `ModelCallGuardrail` | `QuestionShapeGuardrail` refuses a drafting call shown more than the shape of the data (`custom-cl0013`) |
| `Evaluator` components with `control-id` | Verdicts in the runtime ledger on every classifier and compliance-summary interaction (`custom-cl0002`, `custom-cl0016`) |
| `LedgerClient`, `withDetailedReply` | Every model call's evidence and case timeline entry names its ledger record; `GET /v1/context/governance/interactions/{id}` reads it back with its verdicts |
| `EvaluatorTestKit`, `TestLedgerClient` | Tests of the evaluators |

**Akka platform and tooling**

| Item | Used for |
|---|---|
| `service-auth` (Optimize) | Five permissions, roles worked out from them, and the platform's admission rule |
| `KALIX_META_PROJECT` | The project a deployment serves |
| `akka specify init` | Installs the Akka context that makes `service/` a system |
| The local console's system model and bundle profile | Controls draft, decisions and signed bundles |
| The console's human events contract | Served at `/v1/context/oversight` |

## Where it deviates from a typical Akka project or Verify effort

**Infrastructure that is not Akka's**

| Deviation | Why | What Akka has instead |
|---|---|---|
| Neo4j entity graph, with its own compiler from questions to Cypher; projects kept apart by an `orgId` property | Questions are composed at run time and follow links across records | Views, fixed at build time. No graph store. Neo4j Community is GPL (see `service/docs/asks.md`) |
| Connector packs (OpenAPI) read sources with the JDK `HttpClient`, with an egress allow list and a per-instance rate limiter | Sources are outside systems described as data | No source-connector model; MCP and agent tools were not used for this |
| Notification delivery: webhook, Slack, SMTP | Tell teams about cases and decisions | None in the SDK |
| A development secret provider reading configuration | Credentials for sources | Platform secrets arrive as environment variables; not wired in |
| A separate TUI (Textual) and Go CLI | A local operator console | Akka console pages and an `akka context` command group, both still asks |

**Concepts it implements that Akka or Verify do not have**

| Deviation | The gap |
|---|---|
| An evidence ledger of its own, hash-chained | The runtime ledger holds model calls, evaluations and tool-call guardrail decisions; an application cannot write records to it |
| Human-decision machinery: requests, claiming, SLA, escalation, four-eyes, minimum review time | The SDK has no primitive for requesting a human decision. The wait itself is native (a workflow pause) |
| Halt and a two-approver restart | No scoped halt with a human principal |
| Playbooks as data, run by one generic workflow | Workflows and autonomous agents are code; there is no governed automation defined as data |
| A compliance calculation of its own: acceptance kept apart from assertion, waivers, backing documents | The console computes compliance from bundles and verdicts; two calculations exist side by side |
| Data domains: who may read which types, and which may be linked | Nothing native; beyond `service-auth`'s five permissions |
| Identity resolution: clusters confirmed by reviewers | Nothing native |
| Questions about the controls answered by two models over facts computed by templates | Nothing native |
| An RDF export in the Runtime Evidence vocabulary | Nothing native |

**Using Verify's features in a non-standard way**

| Deviation | Why |
|---|---|
| Personal data is masked by the service as well as by the runtime's sanitizers | The runtime records no sanitizer pass, so the service masks and counts what it masked as evidence |
| `custom-cl0002` and `custom-cl0016` are checked in service code before the answer is used, and judged again by evaluators afterwards | A guardrail may block, but an `AgentResponseGuardrail` sees only the reply, not the labels or facts the model was given, and an evaluator cannot be started from code |
| Controls are officer rows that list their corpus controls under `implements`, not corpus rows | A corpus row's definition in a bundle is the corpus control, so the runtime settings the service enforces would drop out; and the corpus is not public |
| The service checks its own bundle in force | So it can report at run time whether it runs what was signed. A sign-off's key is self-asserted (`trust: "local"`) |
| `custom-cl0003`, `0007`, `0009`, `0015` are enforced only in service code | There is no runtime mechanism for them; the bundle carries them as officer rows |

Not a deviation but a limit: the integrity chain and the change feed are one entity per project, which bounds how fast
evidence is recorded and changes announced. It has not been measured.

## Proposals for Akka Verify

### More core constructs

Verify has the human event; this repo adds the constructs around it. Each emits evidence under a control id.

| Construct | In Verify today | What this repo adds |
|---|---|---|
| **Decision** | Native: the human event, "one situation, one subject, one control", which closes with exactly one verdict | Nothing; it implements the console's human events contract |
| **Case** | Partly: a non-blocking human event | A container for one situation: the evidence, notes, a playbook run and the decisions that follow a detection |
| **Monitor** | Partly: human-monitoring controls whose triggers fire on runtime behaviour over a window; nothing yet evaluates them | A standing question on a schedule or on change, over any records, that opens a case. Runtime triggers become one kind of monitor |
| **Playbook** | Absent | Governed automation: fixed steps, approved releases, draft → shadow → live, halt and two-person restart, a model only for bounded judgements |
| **Ask** | Partly: questions about the controls | A governed read: read-only, checked against data domains, says how current each source was, drafted by a model that never sees records, run by a person |
| **Domain** | Absent; the risk survey's data types decide applicability only | Who may read which data, and which data may be combined, with joins approved by someone else. Applies to retrieval, tools and any agent that reads |
| **Live** | Partly: Activity, meaning traces and conversations | What is happening now across the system, data changes included |

They form one chain:

```
Domain  →  Ask  →  Monitor  →  Case  →  Playbook  →  Decision  →  Verdict
(what may    (governed  (standing    (the         (governed      (human      (under a
 be read)     read)      question)    situation)   automation)    event)      control)
                                          ↑
                                     Live (what is happening now)
```

Two things Verify does better, which this repo should adopt: every human event closes with a verdict under a control
(a case here closes with a note, under no control), and monitors fire on windows (a rate or a drift over time), where
this repo's fire only on a record that matches now.

### The corpus: what is required, apart from how a system meets it

A corpus control today holds the obligation (the article text, penalty and sources), when it applies (rules over the
risk survey's facts) and a generic suggestion for meeting it (`akka_runtime`). A system that adopts it signs that whole
record as the control's definition, so the bundle says what the law requires but not how this system meets it.

| | Today | Proposed |
|---|---|---|
| A row's obligation | One corpus reference, or none for an officer row | Zero or more pinned corpus references: one control can meet several obligations |
| A row's implementation | The corpus's generic suggestion for corpus rows, the officer's text for officer rows | Always the system's own: mechanism, runtime configuration, proof event, evidence |
| The corpus's `akka_runtime` | Copied in as the definition | A starting template the implementation is drafted from |
| Corpus and officer rows | Two shapes | One shape; an officer row is one with no obligation references |

The bundle would then state both what is required and how this system does it; a control could show under every
regulation it meets; and a change to an obligation's text would be told apart from a change in how the system meets it.
This repo's officer rows with `implements` are a local stand-in for that shape.

### Asks of the SDK

| Ask | Why |
|---|---|
| Give an `AgentResponseGuardrail` the request it answers | So checks like `custom-cl0002` and `custom-cl0016` can block in a guardrail instead of in service code |
| Read access to components from a guardrail | A guardrail is built with only its name, config and a classifier client |
| Record each sanitizer pass in the ledger, with its `control-id` | So the runtime can show masking happened, and the service need not mask again to record it |
| Let application code write records to the runtime ledger | So evidence of human decisions and gates sits beside the runtime's own |

The asks of other Akka teams (permissions, Neo4j, the CLI group, console pages) are in `service/docs/asks.md`.

## Building

The service depends on the local snapshot `io.akka:service-auth:0.1.0-SNAPSHOT`, which is built from the `akka/nexus` repository. It will not build without that artifact, and its parent poms, installed in the local Maven repository:

```bash
git clone https://github.com/akka/nexus.git
cd nexus
mvn install -N                                  # the root pom, which service-auth's pom names as its parent
mvn install -pl service-auth -am -DskipTests    # service-auth and the parents it needs
```

`akka/nexus` is internal to the Akka organisation. Without it, `cli/`, `tui/` (including `--demo`) and `claims-system/`
still build.

Then `mvn test` in `service/` runs the unit tests; the ones that need Neo4j are skipped when no server is reachable.

The command line builds with `go build -o akka-context ./cmd/akka-context` in `cli/`.

Tests that use Neo4j expect a local container. The password in the poms and docs is a local development value only.

## The system's controls

`service/governance/controls.yaml` is the system's controls draft, in the form the Akka local console reads. Every
control is an officer row: everything the service needs to enforce it is in the row, and the regulations and
frameworks it meets are listed under `implements` as corpus ids. The service runs the controls of this draft.

Controls are decided and signed in the console. Each signing writes the next bundle into `service/governance/signed/`
(an OCI layout, the console's local profile of the Governance Control Contract), and the newest bundle is in force.
The service checks the bundle in force (every part hashes as declared, every sign-off verifies) and reports, per
control, whether it runs it exactly as signed: in the compliance report, at `GET /v1/context/governance/contract`,
with `akka-context contract` and in the TUI's Governance tab. Once a bundle is committed, the service's tests fail when
`controls.yaml` and the bundle in force disagree, so **sign again after changing a control**.

To manage the system from the console:

1. Install the Akka context in the system folder, which is what makes the console treat it as a system. The files it
   adds (`akka-context/`, `.akka/`, `CLAUDE.md`, `.claude/`) are not committed.

   ```bash
   cd service && akka specify init . --skip-token-check
   ```
2. In the console, add the system (System menu, "Add a system…") with the path of `service/`.
3. Under Controls, decide each control (approve, or waive with a reason) and sign. Signing needs a console built from
   the branch that carries signed bundles; it signs as your `git config user.email` with an Ed25519 key it keeps in
   `~/.akka/local/console/keys/`.
4. Commit `service/governance/controls.yaml` and `service/governance/signed/`.

A sign-off carries the key that made it, and nothing yet says whose key that is (`trust: "local"`); binding keys to
people and adding a publisher signature and trusted time are the console profile's open product work.
