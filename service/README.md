# contextlayer-akka

A governed context layer on the Akka SDK: staged assistants with approved releases, an
idempotent action ledger with human approval, connector packs for source systems, a graph entity
store, cross-source identity linking with a review queue, and structured questions that report how
fresh each source was. Built from the requirements and design in
`C:\Users\tyler\context\clean-room\implementer\`.

Port: 9162 (registered in `C:\Users\tyler\CLAUDE.md`).

## What exists

| Requirement | Component |
|---|---|
| Assistants, releases, Draft/Shadow/Live (AST-01..03) | `AssistantEntity`, `domain/Assistant` |
| Risk classes, approvals, expiry, unknown outcomes (AST-04..06) | `ActionLedgerEntity`, `ActionWorkflow`, `domain/ActionPolicy`, `ActionCatalog` |
| Identity links, confidence, review, reversal (ENT-02..04) | `IdentityEntity`, `domain/IdentityRules`, `IdentityCluster`, `IdentityGraphConsumer` |
| Audit trail (AUD-01..02) | Event journal of every entity; `ActionLedgerView`, `IdentityReviewView` |
| Organization isolation (ISO-01) | `ScopedId` prefixes every entity id; every graph statement carries the organization id |
| Single outbound path (OPS-04) | `ActionExecutor` for actions, `HttpSourceCaller` for reads, `EgressPolicy` allow-list |
| Connector packs: import, review, read-only first, rollback (CON-01..05) | `ConnectorPackEntity`, `domain/ConnectorPack`, `PackValidator`, `PackHash` |
| Bounded source reads, credentials by reference (CON-02, CON-03, QRY-05) | `HttpSourceCaller`, `SecretProvider`, `CallRateLimiter` |
| Entity store (ENT-01, ENT-05) | `EntityStore`, `Neo4jEntityStore`, `RecordFlattener` |
| Structured questions with a freshness report (QRY-01..05) | `domain/StructuredQuestion`, `Neo4jQueryCompiler`, `QuestionService`, `EntityRefresher`, `RefreshStatusEntity` |
| Monitors, cases, case timeline (MON-01..03) | `MonitorEntity`, `MonitorRunWorkflow`, `MonitorTimedAction`, `CaseEntity`, `CaseView`; a run that could not read every source never closes a case |
| Model step with declared outputs and field allow-list (AI-01..03) | `ClassifierAgent`; `Playbook` classify steps; an answer outside the allowed labels fails the step |
| Assistant work as validated data, no code (DESIGN D-05) | `domain/Playbook`, `PlaybookWorkflow`; releases are validated as playbooks when added |

## Running with Neo4j

Tests that touch the store, and the service itself, need a Neo4j 5 server:

    docker run -d --name contextlayer-neo4j -p 7687:7687 -p 7474:7474 \
      -e NEO4J_AUTH=neo4j/localdevpass123 neo4j:5-community

The tests need nothing more. For loads of a few hundred thousand records or more, give Neo4j a heap of 2 GB or more
(`-e NEO4J_server_memory_heap_max__size=2G`); see `docs/SCALE.md`.

To run the service locally with those settings, use `scripts/run-local.ps1` (it sets `NEO4J_PASSWORD`, `NEO4J_URI` and `NEO4J_USER` and runs
`mvn compile exec:java`). The Akka MCP tools start a service without passing environment variables, so a service started by them cannot
sign in to Neo4j; a failed sign-in repeated a few times also locks the account for a short while.

Set `NEO4J_PASSWORD` (and `NEO4J_URI`, `NEO4J_USER` if they differ) when starting the service. The
store tests default to the password above and skip with a message when no server is reachable.
Neo4j Community has one database, so projects are separated by an `orgId` on every node and
statement (see `docs/PLAN.md`).

How the service is put together: `docs/ARCHITECTURE.md`. What was measured at scale: `docs/SCALE.md`. How the service fits the platform, and what it still needs from other teams: `docs/PLATFORM.md` and
`docs/asks.md`. What a client can rely on, route by route: `docs/contracts.md`. A command line for it is in `../contextlayer-cli`.

## Governance

Every route needs a bearer token (`@JWT`); the runtime checks its signature and expiry. What a caller
may do is decided by the platform rule in `service-auth`: a deployment that knows its project (the
platform sets `KALIX_META_PROJECT`) admits only callers who hold access to that project, and a local
run admits any authenticated caller. The service asks for five permissions, `project.governedContext.`
then `read`, `operate`, `review`, `approve` or `administer`, and turns the ones a caller holds into a role
used to route human events: `viewer` reads, `operator` asks and builds, `reviewer` judges identity links
and cases, `approver` authorizes (actions, releases, stages, packs), `admin` does everything and waives
controls. A subject starting `system:` is refused, because that names the service's own parts.
Local runs and tests accept unsigned (`alg: none`) tokens.

A deployment serves one project, and everything it stores belongs to that project. All routes are
under `/v1/context`.

- `governance/controls.yaml` is the control catalog: stable `row_id`s, the event that proves each,
  and for human controls the policy (roles, SLA, rationale, minimum review time, escalation chain).
  Its digest is stamped on every evidence record. `GET /v1/context/governance/controls`.
- Risky action approvals are human events: raised when an action needs a person, claimed (which is
  when review time starts), decided with a required rationale by someone other than the requester,
  escalated or sent back. Identity reviews are the same: a cluster that needs a person raises an event
  (keyed by the set of records, so a changed cluster closes the old question and asks a new one), the
  reviewer opens it (`POST .../identity/open`), then confirms or rejects, and the link changes when the
  decision is applied. An unanswered review times out after its SLA and leaves the link unconfirmed.
  Releases, stages and pack activations are recorded as decided events under the caller's roles. Decisions below the minimum review time are flagged.
- `/v1/context/oversight/events...` serves the console's human events contract. `reviewer.rehearsal` is recorded as a rehearsal and
  changes nothing. Reopen, flags and standing decisions answer 501: a closed event was already acted on
  (raise a new one), and a standing approval would bypass the four-eyes rule.
- An event's deadline can be extended once, by at most seven days, with a reason. Identity reviews
  that confirmed links already cover are left out of the oversight queue, as in the review queue.
- Halts: `POST /oversight/halts` raises an event to halt an assistant (one approver decides) or to
  restart it (two different approvers). While halted its actions are refused, playbooks stop, and an
  action approved after the halt does not run. `GET /oversight/halts` lists each assistant's state.
- Evidence is derived from what happened and is append-only. `GET .../governance/evidence` exports it;
  `GET .../governance/compliance` reports per control: satisfied, failing, unresolved, stale,
  no-evidence or waived (admin only, `POST .../controls/{id}/waiver`). Shadow, advisory and test
  records never count as proof.
- Model calls from the classifier: personal data is masked in the service (and again by the runtime's
  sanitizers), the answer must be an allowed label, a report-only jailbreak guardrail is configured,
  and evidence links to the runtime's ledger interaction when the runtime reports one.

Not built: exit conditions (not wanted), signed bundles, ledger-over-HTTP evidence from the runtime,
verdict records as objects of their own, flags on outputs.

## Actions and notifications

- **Actions reach the system.** An approved action of a connector pack is sent as the OpenAPI
  operation the pack names (POST, PUT, PATCH or DELETE), with the pack's credential and an
  `Idempotency-Key` that is the same for every attempt. Path placeholders come from the payload.
  A refused or unformable request is `FAILED` (nothing happened); a 2xx is `SUCCEEDED`; a 5xx, a
  timeout or a cut connection after sending is `OUTCOME_UNKNOWN`, which is never retried and goes to a
  person. The host must be on the egress list. Only a LIVE connector offers actions.
- **Teams are told.** `POST /v1/context/routes/{name}` (admin) sets where a route's messages go: a JSON
  post, or a Slack incoming webhook. A monitor's `routeTo` is a route name, and approvals go to
  `role:<role>`. A new case, an approval that needs someone and an escalation each send one message
  naming what and where to look, never a record's contents, with personal data masked. Every
  attempt is recorded (`GET /v1/context/notifications`): delivered, failed with why, or no route set
  up. `POST .../routes/{name}/test` checks a route before a case depends on it. A route can also be one to ten email
  addresses (format `email`, address `mailto:a@example.com,b@example.com`), sent through the one mail server the
  deployment is configured with (`contextlayer.notifications.email`: `CONTEXTLAYER_SMTP_HOST`, `_PORT`, `_SECURITY` of none,
  starttls or ssl, `_USER`, `_PASSWORD_REF` and `_FROM`). The mail server must be on the egress list, the message is masked and
  carries ids only, and with no server configured every email delivery fails and the record says that email is not set up.
- **Model:** Gemini by default (`GOOGLE_AI_GEMINI_API_KEY`). Quality on a labelled set is in
  `docs/MODEL-EVAL.md`. `scripts/live_e2e.py` in contextlayer-tui runs the whole chain against a real
  model.

## Asking in words

`POST /v1/context/questions/draft` with `{"text": "open claims over a month for wealthy clients"}`
returns a question to read, not an answer: the model is shown the shape of the data (types, fields,
kinds, and the categories of fields that hold a handful of them) and never a record, its draft is
checked against that shape and the question rules (once more with the reasons if it fails), and a
person runs it. A request the data cannot answer, or that is ambiguous, comes back as a question
for the person. Each draft is recorded as evidence for control `custom-cl0013`. Quality with a real
model: `docs/MODEL-EVAL.md`.

## Freshness and live data

Each entity type in a connector says how it stays current (`freshness.policy`):

| Policy | How the data gets in | `ttlMinutes` means |
|---|---|---|
| `ON_DEMAND` | Every question reads the source first | not used |
| `CACHED` | A question reads the source if the last read is older than the limit | the longest age of a read a question accepts |
| `MAINTAINED` | The service reads the source on a schedule that starts when the connector goes live | how often it reads |
| `STREAMED` | The source pushes each change, and nothing is read | the longest silence before the data is reported stale |

Every answer says how each source was treated: `REFRESHED_NOW`, `WITHIN_POLICY`, `MAINTAINED`, `STREAMED`,
`STALE` or `UNAVAILABLE`. A scheduled source that has missed two reads, or whose last read failed, is `STALE`.
A pushed source that has been silent for longer than its limit is `STALE`, and one that has sent nothing yet
is `UNAVAILABLE`.

**Pushed data.** A pack with `source.kind` of `stream` and a type with `freshness.policy` of `STREAMED` and no
`operation` is fed only by what its source sends. Two ways in, with the same meaning (`EntityChange`: upsert,
delete, heartbeat, each with the source's own version number so a late or repeated change never undoes a newer one):

- **Another Akka service** publishes a service stream `entity_changes` and the service consumes it. The stream
  starts from the publisher's current state, so there is no separate load. Changes that arrive before a
  connector for them is live are held, not dropped. `claims-system` is a small publisher for demonstrations.
- **Any other system** posts to `POST /v1/context/streams/changes` with an `operate` token, such as a webhook
  from a third party.

**Watching.** `GET /v1/context/streams/changes?types=Claim` is a server-sent event stream that tells a
watcher as records change. `POST /v1/context/streams/watch` with a question is a standing question: the answer
first, then the rows added to it and removed from it as the data it reads changes. A monitor is the same thing
with a case as its output: set `onChange` on a monitor and it also runs shortly after the data it reads changes
(its interval then only makes sure a run happens when no change is seen). Notices go to whoever is connected at
that moment and are not replayed, so a watcher that reconnects gets the whole answer again.

## Relationships

Records link in two ways. A **link by identity** finds records of different systems that are the same real-world party,
through links a person has confirmed. A **relationship** is one a source declares between its own record types: the entity
in a connector pack lists `relations`, each a name, the field that holds the id (or a list of ids) of the records it points at,
and the target type. For example a claim can declare `HAS_ACCOUNT` from its `accountId` field to `Account`.

A question follows a relationship by naming it in a link: `{"entityType": "Account", "via": "HAS_ACCOUNT", "direction": "OUT",
"depth": 1, "where": [...]}`. `direction` is `OUT` (as declared) or `IN` (backwards), and `depth` is one to three steps. Without `via`
the link is by identity. A question can mix both, and every type it touches is checked against the asker's data domains.

Records can arrive in any order. A relationship to a record not held yet is kept and joined when the record arrives, and one
whose target is deleted is kept so the target can come back. A changed field moves the relationship. The schema shown to the
model for asking in words lists each type's relationships, and a draft naming one that does not run between those types is
refused. A relationship never crosses projects.

## Who said what, and whether it can be altered

Four things are kept apart, because different people say them and each can be wrong on its own:

- **The control** is what the service enforces (`governance/controls.yaml`).
- **The acceptance** is the binding: someone with authority says this project takes the control on, at a version of
  the catalog (`POST /v1/context/governance/controls/{id}/acceptance`, approve permission). It goes stale when the
  catalog changes. It says nothing about what counts as evidence.
- **The assertion** is a separate claim, with its own author: which kind of record counts as evidence for the control, and
  which documents it is expected to be backed by, such as an override procedure no older than a year
  (`POST .../assertion`, then `POST .../documents` to attach each, with the date it was last current).
  Each assertion is a new revision and the earlier ones stay. `GET .../claims` shows all of it.
- **The evidence** is what enforcing controls recorded. Only this says a control is working.

Coverage is a value, not a judgement: `FULL` when every expected document is attached and not too old, `PARTIAL` with
what is missing named, `NONE` when nothing has been asserted. The compliance report lists the controls that fall
between the claims (`gaps`): not accepted, accepted with nothing asserted, asserted with no enforcing records, and
asserted with documents missing or too old. Accepting, asserting and attaching are themselves evidence, recorded with
their author, and never count as the control running.

**Tamper-evidence.** The evidence ledger is hash chained: each record has a digest of its content, and the digests are
chained in the order they were taken in, so changing, removing or reordering a record changes every digest after it.
`GET /v1/context/governance/integrity` checks the chain a stretch at a time and reports each problem. It finds a record
altered after it was written and a link that is missing or altered. It does not protect against someone who can rewrite the whole
chain and its head together, so the head it reports is meant to be kept somewhere that person cannot write.
`GET /v1/context/governance/record-stores` says, for each store, whether it is `HashChained` or `NoIntegrity`:
the human events and the control claims are not, and each change to them is copied to the chained ledger.

**Documents.** What backs a control's evidence but is not a runtime record, such as an override procedure, is kept by the project, by name:
`POST /v1/context/governance/documents/{name}` registers a version with its kind, where it is and the date it was last current, and, if the
content is given, the SHA-256 of it (the content itself is not kept, so the document can later be shown to be the one registered).
An assertion expects documents by name and age, so one document backs every control that expects it, and the newest date counts.
`GET /v1/context/governance/documents` lists them and `.../documents/{name}` shows every version.

**Questions about the controls.** `POST /v1/context/governance/ask` with `{"text": "..."}` answers an auditor's question, such as what
evidence there is that a person can override the assistant and whether it is tamper-evident. It is built so the answer cannot say
more than the records do. A model only chooses which of a fixed list of questions was asked (control status, evidence for a
control, human decisions, gaps, integrity) and which control and period it is about, and is shown no records. The choice is
checked against the catalog. The facts are then computed by fixed templates from recorded data, each with the records it comes
from, and what the records do not show is stated as a limit, so an absence is a result and not an inference. A second model,
which sees only the facts, words them; its words must contain no number or control id the facts lack and no verdict such as
"compliant" or "no gaps" that the facts do not state, or they are replaced by the facts in plain form and the replacement is
recorded as evidence for control `custom-cl0016`. A question that fits no listed one is handed back with the list.

**Export.** `GET /v1/context/governance/rte` writes the project as RDF Turtle in the Runtime Evidence vocabulary (`rte`),
which builds on W3C PROV and the Data Privacy Vocabulary, for loading into a graph that joins evidence from many systems.
Each control is an obligation under the project's policy (the catalog) with a binding; an acceptance attributes the binding
to a person; each assertion is its own node with its own author, derived from the binding and linked to the revision before
it, and states coverage; each evidence record is an activity of its class with an outcome, the person or software that acted,
and a record in the evidence ledger with its position in the chain and its digest. The classes follow the control:
human decisions are `HumanReview` (approve is `Approved`, deny is `Rejected`), halts are `SystemHalt`, masking is
`Sanitization`, label, jailbreak and draft checks are `GuardrailIntervention`, domain access is `AccessDecision`, and
the other gates are `ControlDecision`. Records of accepting, asserting and attaching are claims and are left out of the
activities. The export states records and assertions and does not join them: a record is evidence only through an assertion.

## Data domains

A data domain groups entity types that are governed together, such as `claims` (Claim) and `wealth` (Account).
An administrator defines it with the roles and people who may read it (`POST /v1/context/domains/{name}`,
`GET /v1/context/domains`). A type is in at most one domain.

A project with no domains allows every question. Once it has one:

- A question may read only types that are in a domain the asker is granted. A type in no domain cannot be read
  until it is assigned. A role grant matches the roles that describe what the caller holds, so an administrator
  is not an operator unless the domain says so.
- A question that links records of two domains needs an approved join rule for that pair
  (`POST /v1/context/join-rules`). The approval is a decision under control `custom-cl0014`, made by someone
  other than whoever last changed either domain. The rule links the domains; it does not grant reading either.
- Asking in words shows the model only the types the asker may read. Standing questions, the change feed and
  monitors apply the same rules when they are set up or opened.
- Every decision is evidence for control `custom-cl0015`: who asked, what they read, and why they were refused.

A monitor reads for whoever set it up. It is checked when it is set up, and again at every run against the roles its owner
had then, so it stops, with a recorded refusal and no run, when the domains change and the owner is no longer granted what it
reads. A change in the owner's roles after set-up is not seen until the monitor is set up again.

## Not built yet

An MCP endpoint (the Akka CLI already exposes the service through its own MCP tools), per-organization
model routing. The rate limiter is per service instance. Freshness is reported per source, not per
row. A person who resolves a case whose row still matches does not get it reopened.

## Models

The default model is Gemini (`gemini-2.5-flash`, set in `application.conf`), read from `GOOGLE_AI_GEMINI_API_KEY`.
Every test except the two live evaluations uses a mocked model (`TestModelProvider`), so building and testing
need no key. The live evaluations run only with `-Dlive.model=true` and are described in `docs/MODEL-EVAL.md`.

## Run the tests

`mvn verify`. The end-to-end tests need Neo4j (above).
