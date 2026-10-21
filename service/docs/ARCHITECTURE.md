# Reference architecture

How the context service is put together, where each kind of data lives, how it scales, and what it trusts. Every claim about
behaviour here is one the tests exercise. `PLATFORM.md` says how it fits the Akka platform, `contracts.md` lists every route, and
`SCALE.md` says what was measured.

## The shape

One service per project, written with the Akka SDK. Callers reach it through HTTP routes under `/v1/context`. A person uses the
terminal console or the command line, an agent uses the routes, and another Akka service or any other source pushes data in.

```
 sources --------read--> connectors --+
 Akka services --stream--> consumer ---+--> entity graph (Neo4j) <--- questions, watchers, followed questions
 other sources --webhook--------------+          ^                          |
                                                 |                          v
 people ---- links, approvals, domains ------> event sourced entities ---> evidence ledger (hash chained)
```

## Where data lives, and why

| Data | Held in | Why |
|---|---|---|
| Records read or pushed from sources, and the links between them | Neo4j, one node per record, `orgId` on every node and statement | Questions are composed at run time and follow links and relationships across record types. Views are fixed when the service is built, so they cannot do this. The graph can be rebuilt from the sources, so it is not a record of what happened. |
| Decisions, evidence, cases, halts, claims, documents, domains | Event sourced entities and key value entities | The platform keeps them durable, and the journal is the history. |
| Lists and lookups over those | Views | Eventually consistent. A route that must see its own write reads the entity, not a view. |
| What changed, for clients following it | A notification stream of a per-project entity | Real time and not stored. A client that reconnects reads the answer again. |
| Timers | Akka timers, unique by name | Watchers, scheduled reads, and the window that batches watcher runs after a change. |

Neo4j Community has one database, so projects are separated by a property and every compiled statement carries the project id.

## The paths data takes in

- **Pulled.** A connector pack describes how to call a source. A type is read when asked (`ON_DEMAND`), reused for a set time
  (`CACHED`), or read on a schedule that starts when the connector goes live (`MAINTAINED`).
- **Pushed.** A type with `STREAMED` freshness is fed by what its source sends: another Akka service publishing the
  `entity_changes` stream, or any system posting to `/streams/changes`. Each change carries the source's version, so a repeat or
  a late change never undoes a newer one. Changes that arrive before a connector for them is live are held, not dropped.
- **Related.** A type can declare relationships by naming the field that holds the id of the record it points at. A reference
  to a record not held yet is kept and joined when the record arrives.

All three end in the same place: the record is written to the graph, offered to the links, and, if it changed
something a watcher or a followed question reads, announced.

## Questions

A question is data: a base type, conditions, and links by match (the same person or thing) or by named relationship. It cannot express a write, every
part is bounded, and it compiles to one read-only parameterized statement, so it can be checked before it runs. A question in
words is drafted by a model that sees only the shape of the data and never a record; a person reads the draft and runs it.

Before it runs, a question is checked against the project's data domains. Every answer says how current each source was.

## Governance

- **Controls** are in `governance/controls.yaml` with stable ids. Every decision and every refusal is evidence under a control.
- **People decide** what must not be decided by software: which records are one person, which actions go ahead, which playbooks
  stop, who may read which domain, and whether two domains may be linked. Each decision records a reason and the authority it
  was made under, and whoever asked cannot approve.
- **Claims are kept apart.** The acceptance of a control, the assertion of what counts as its evidence, and the documents that
  back it have different authors and are separate records. Coverage is computed from them. A gap is reported as a result.
- **Evidence is chained.** Each record's digest is chained to the one before it, and the chain can be checked a stretch at a time.
  The head is meant to be kept where the service cannot write.
- **Questions about the controls** are answered from facts computed by fixed templates, worded by a model whose words are checked
  against the facts.
- **Export.** The project can be written as RDF in the Runtime Evidence vocabulary for loading into a graph that joins evidence
  from many systems.

## Scaling

- **Stateless service instances.** State is in entities and the graph, so instances can be added behind the platform's routing. A
  follower's stream is tied to the instance it connected to and reconnects on its own.
- **Entities scale by id.** Most are per record, per control, per project or per decision. Two are per project and take every
  write of their kind: the integrity chain, which orders evidence, and the change feed, which publishes. Their throughput is one
  entity's, which is the limit on evidence recorded and changes announced per project. The scale test measures the store, not
  these.
- **The graph is the large part.** Records are written in batches and keyed by project, type, source and id, so lookups by key
  use an index. Conditions on other fields scan the type, and the scale test shows how that grows. An index on a field a project
  asks about often is the first thing to add.
- **A question is bounded.** At most three links of at most three steps, a limit on rows, and a time budget for reading sources.
- **Reads of sources are rate limited** per entity type, per service instance.

## What it trusts, and what it does not

- **Callers.** The platform checks the token and decides which of five permissions a caller holds in the project. A role is worked
  out from what a caller holds. A subject that starts `system:` is refused, because it names the service's own parts.
- **Sources.** What a source returns is data. It never reaches a model except through the masking and shape checks, and a pushed
  change for a type no connector declares is dropped.
- **Models.** A model chooses, drafts or words. It never runs a question, approves an action, or states a fact the records do not.
  Every model call has its personal data masked first, and its output is checked against what was allowed.
- **Egress.** A call to a source, a webhook or a mail server must be to a host on the allowed list.
- **Its own store.** Only the evidence ledger is tamper-evident. The human events, the claims, the documents and the graph are not,
  and each says so. Someone who can rewrite the whole ledger and its head together is not detected unless the head is kept elsewhere.

## Failure behaviour

- A source that cannot be read leaves the data as it was and is reported `STALE` or `UNAVAILABLE`. It is never shown as empty.
- An action whose outcome is unknown is never retried. A person resolves it.
- A notification that cannot be delivered is recorded with why, and does not stop what caused it.
- A watcher whose owner is no longer granted what it reads stops, with a recorded refusal.
- A stream consumer holds at a change it cannot place until a connector is live, and retries, so nothing is lost.
