# Asks of other teams

Each ask says what is needed, why, and what this service does until it is done.

## 1. Register the permissions

**Needed.** The five permissions in `PLATFORM.md` (`project.governedContext.read`, `operate`,
`review`, `approve`, `administer`) registered in the platform permission set, with the roles that
hold them.

**Why.** Without them, `service-auth` can only use its role rule, which admits every holder of project
access to every route. That includes approving an action. The service still refuses a person approving
their own request, but it cannot separate who may approve from who may only look.

**Also needed for data domains.** A domain grant to a role such as operator can only separate callers once
callers hold different permissions. With the open rule every caller holds all five, so every caller is an
administrator and role grants admit nobody who is not named.

**Until then.** The service runs on the open rule locally and on the control-project rule in a
region. In both, a caller holds every permission once admitted.

## 2. Neo4j in the platform

**Needed.** A Neo4j installation available to a project, with credentials supplied to the service
through its environment (`NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD`).

**Why.** Questions are composed at run time and follow identity links across records and types.
Views are fixed when the service is built, join a fixed number of hops, and group only with
`collect()`, so they cannot answer these questions.

**Open point.** Neo4j Community is licensed under the GPL and has no clustering and one database. Bundling
it with the platform, high availability, and separating projects in one installation need a licence
review or Enterprise.

## 3. A command group in the Akka CLI

**Needed.** `akka context ...` commands in the CLI, mounted the way `akka optimize` is. The commands in
`contextlayer-cli` are the first cut: `assistants`, `decisions`, `ask`, `cases`, `halts`, `controls`
and `compliance`.

**Until then.** `akka-context` runs on its own and takes its token from `AKKA_ACCESS_TOKEN`.

## 4. Console pages

**Needed.** The console reads `/v1/context/oversight/events` already, because that is the human
events contract. A Context page for questions, cases and connectors would read the same routes the
CLI does.

## 5. Choose the publishing service per deployment

**Needed.** The service that this one consumes changes from is named in the code (`claims-system`), because
a consumer's source is part of its annotation. A deployment should be able to name it, and name more than one.
An environment variable in the annotation does this, but local runs then need the variable set.

**Until then.** The name is fixed, and a second source needs a second consumer class.

## 6. Keep the ledger head where this service cannot write

**Needed.** The chain of evidence records is tamper-evident, but whoever can rewrite the whole journal can rewrite the
chain and its head together. A place the service cannot write, such as the platform ledger or a store in another project,
should take the head (`/governance/integrity`) on a schedule, so a later check can show that nothing before it changed.

**Until then.** The head is reported and has to be kept by hand.

## 7. Governance on the platform ledger

**Needed.** When the verify model ships, evidence records, verdicts and the signed bundle move to the
platform ledger. This service keeps its own evidence log and compliance report until then. The control
catalog already uses stable control ids so that the move changes where records are written and not
what they say.

## 8. Record each sanitizer pass in the runtime ledger

**Needed.** A record per sanitizer pass, carrying its `control-id` and what kinds it masked, as evaluations and
tool-call guardrail decisions already are.

**Why.** Control `custom-cl0001` is enforced by the runtime's sanitizers on every agent, but a sanitizer leaves no
record, so the runtime cannot show that it ran. `SanitizerClient` returns only the masked text, so a caller cannot
record the counts either.

**Until then.** Before the one model call whose input comes from records (the playbook's classify step), the service
masks the same kinds itself with `PersonalDataMasker`, counts what it masked and records that as evidence; the
runtime's sanitizers then mask again at the model boundary.
