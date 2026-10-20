# API contracts

What a client of the context service can rely on. Every route is under `/v1/context`. The Go CLI
(`contextlayer-cli`) and the terminal console (`contextlayer-tui`) are clients of exactly this.

## Conventions

- **Project.** A deployment serves one project. No route names it. Everything is that project's.
- **Sign-in.** A bearer token on every request. A request with no token is refused with 400 by the runtime, before
  any route runs.
- **Permission.** Each route needs one of five permissions: `read`, `operate`, `review`, `approve`, `administer`
  (platform names `project.governedContext.<verb>`). A caller who lacks it gets 403 with
  `requires permission context.<verb>`.
- **Errors.** A rule saying no is a 400 with the reason as text. A refused decision (such as a person approving their
  own request) is 403 or 409 with the reason. Something unexpected is a 500 that says only that an error occurred.
- **Time.** Instants are ISO-8601 strings. Dates are `yyyy-mm-dd`.
- **Lists** are `{"items": [...]}` unless a route names another field.
- **Streams** are server-sent events. Each event is one JSON object in `data:`. The runtime sends a heartbeat when idle.
  A stream is tied to one instance, so a client reconnects on its own and reads the whole answer again.

## Assistants and actions

| Route | Permission | Body | Answer |
|---|---|---|---|
| `GET /assistants` | read | | `{items:[{assistantId, purpose, owner, stage, releaseCount, approvedCount}]}` |
| `POST /assistants/{id}` | operate | `{purpose}` | assistant |
| `GET /assistants/{id}` | read | | `{assistantId, purpose, owner, stage, runningReleaseId, releases:[{releaseId, approved, approvedBy, definition}]}` |
| `POST /assistants/{id}/releases` | operate | `{definition}` | `{releaseId}` |
| `POST /assistants/{id}/releases/check` | operate | `{definition}` | `{problems:[...]}`: what adding it as a release would refuse; empty when it would be accepted. Nothing is kept |
| `GET /schema` | read | | `{types:[{entityType, fields:[{name, kind}]}]}`: the types and fields a playbook step may name, in the domains the caller may read. Never a value |
| `POST /assistants/{id}/releases/{releaseId}/approve` | approve | `{rationale}` | assistant |
| `POST /assistants/{id}/stage` | approve | `{stage: DRAFT\|SHADOW\|LIVE, rationale}` | assistant |
| `POST /assistants/{id}/actions/{key}` | operate | `{actionType, payload}` | `{actionKey, status}` |
| `GET /assistants/{id}/actions/{key}` | read | | action: `{assistantId, actionKey, actionType, status, reason, decidedBy, releaseId}` |
| `GET /assistants/{id}/actions` | read | | `{items:[action]}` |
| `GET /approvals` | read | | actions waiting for a person |
| `POST /assistants/{id}/actions/{key}/approve` | approve | `{rationale}` | action |
| `POST /assistants/{id}/actions/{key}/reject` | approve | `{reason}` | action |
| `POST /assistants/{id}/actions/{key}/resolve` | review | `{succeeded, note}` | action |

An action sent to a system ends in `SUCCEEDED`, `FAILED` (refused or unformable) or `OUTCOME_UNKNOWN` (a server error,
timeout or cut connection after sending). An unknown outcome is never retried; a person resolves it. Whoever requested an
action cannot approve it.

## Connectors and freshness

| Route | Permission | Body | Answer |
|---|---|---|---|
| `GET /connectors` | read | | each connector with the state of its last read of every type |
| `POST /connectors/{name}/versions` | operate | pack | `{hash}` |
| `POST /connectors/{name}/versions/{hash}/activate` | approve | `{mode: READ_ONLY\|LIVE, rationale}` | `{name, mode, activeHash, versions}` |
| `POST /connectors/{name}/deactivate` | approve | | connector |
| `GET /connectors/{name}` | read | | connector |
| `POST /connectors/{name}/entities/{type}/refresh` | operate | | `{pack, entityType, outcome, refreshedAt, stored, skippedWithoutId, warnings}` |
| `GET /connectors/{name}/entities/{type}/refresh-status` | read | | the same |

A pack is `{name, version, source:{kind, ref}, credentialRef, entities:[...], actions:[...]}`. An entity may also list
`relations:[{name, field, targetType}]`, relationships its records have to records of another type, named in questions (see Questions).
`source.kind` is `openapi`,
`mcp`, `database`, `file` or `stream`; a stream needs no `credentialRef`. An entity is `{type, operation, recordsPath,
idField, identityAttributes, filters, freshness:{policy, ttlMinutes}, paging, rate}`.

| `freshness.policy` | How data gets in | `ttlMinutes` |
|---|---|---|
| `ON_DEMAND` | every question reads the source | not used |
| `CACHED` | a question reads if the last read is older than the limit | longest age accepted |
| `MAINTAINED` | read on a schedule that starts when the connector goes live | how often |
| `STREAMED` | the source pushes changes; with no `operation`, nothing is read | longest silence before stale |

## Records and identity

| Route | Permission | Body | Answer |
|---|---|---|---|
| `POST /records` | operate | `{entityType, source, recordId, attributes}` | `{matches:[{rule, status, memberCount}]}` |
| `GET /identity/review` | read | | `{items:[{rule, key, confidence, memberCount, status, members}]}` |
| `POST /identity/open` | review | `{rule, key, rationale}` | `{eventId, minimumReviewSeconds, openedAt}` |
| `POST /identity/confirm` | review | `{rule, key, rationale}` | cluster |
| `POST /identity/reject` | review | `{rule, key, rationale}` | cluster |

A link between records is used in answers only once a person has confirmed it where the match was not certain; the
answer says how many possible matches were left out.

## Questions

A question is `{entityType, where:[{field, op, value}], links:[{entityType, where, via, direction, depth}], returnFields:["Type.field"], limit}`.
`op` is `EQ`, `NEQ`, `GT`, `GTE`, `LT` or `LTE`. A link without `via` brings in another type's records that are the same
real-world party. With `via` it follows the named relationship: `direction` `OUT` (default) or `IN`, `depth` 1 to 3 (default 1).

| Route | Permission | Body | Answer |
|---|---|---|---|
| `POST /questions` | operate | question | `{rows, unconfirmedLeftOut, sources:[{pack, entityType, freshness, lastOutcome, dataAsOf, warnings}]}` |
| `POST /questions/draft` | operate | `{text}` | `{question, explanation, clarification, problems}` |
| `POST /streams/watch` | operate | question | stream of `{event: "snapshot", rows, total, sources}` then `{event: "changed", added, removed, total}` |

`freshness` in `sources` is `REFRESHED_NOW`, `WITHIN_POLICY`, `MAINTAINED`, `STREAMED`, `STALE` or `UNAVAILABLE`. A draft is
never run by the service; `question` is null when the model asked for clarification or no valid draft was reached.

## Streams

| Route | Permission | Body | Answer |
|---|---|---|---|
| `POST /streams/changes` | operate | `{changes:[{op, entityType, entityId, version, attributes}]}` | `{applied, alreadyHeld, noConnector, problems}` |
| `GET /streams/changes?types=A,B` | read | | stream of `{pack, entityType, entityId, op, at}` |

`op` is `upsert`, `delete` or `heartbeat`. `version` orders the changes to one record; a change is applied only if it is later
than the version held. At most 500 changes a call. Another Akka service in the project can publish the same changes as the
service stream `entity_changes` instead.

## Monitors and cases

| Route | Permission | Body | Answer |
|---|---|---|---|
| `GET /monitors`, `GET /monitors/{id}` | read | | monitor |
| `POST /monitors/{id}` | operate | `{name, question, keyField, intervalMinutes, assistantId, routeTo, onChange}` | monitor |
| `POST /monitors/{id}/definition` | operate | the same | monitor |
| `POST /monitors/{id}/enable`, `/disable`, `/run` | operate | | monitor, or `{runId, status}` |
| `GET /cases`, `GET /cases/status/{status}` | read | | `{items:[{caseId, monitorId, key, status, routeTo, openedAt}]}` |
| `GET /cases/{id}` | read | | case with `evidence`, `sourceNotes` and `timeline:[{at, actor, text}]` |
| `POST /cases/{id}/resolve`, `/dismiss` | review | `{note}` | case |
| `POST /cases/{id}/annotate` | operate | `{text}` | case |

A monitor is a standing question whose output is a case. With `onChange` it also runs within about five seconds of a change to
data it reads; the interval then only makes sure a run happens when no change is seen.

## Notifications

| Route | Permission | Body | Answer |
|---|---|---|---|
| `POST /routes/{name}` | administer | `{url, format: json\|slack\|email, credentialRef}`; for `email`, `url` is `mailto:` and one to ten addresses | `{name, enabled, destination, format, hasCredential}` |
| `GET /routes/{name}` | read | | the same |
| `POST /routes/{name}/disable` | administer | | the same |
| `POST /routes/{name}/test` | administer | | `{items:[{route, type, subject, status, detail, at}]}` |
| `GET /notifications` | read | | `{items:[{route, type, subject, status, detail, at}]}` |

A route name is a team name or `role:<role>`. A message names what and where to look, never a record, with personal data masked.

## Human events and halts

The `/oversight` routes serve the console's human events contract: `GET /oversight/events?state=pending`,
`GET /oversight/events/{id}`, and `POST` to `claim`, `release`, `decision`, `escalate`, `send-back`, `resubmit`, `withdraw`
and `extend` under `/oversight/events/{id}/`. Reading needs `read`; acting needs `review`. A decision is
`{action: APPROVE|DENY|ESCALATE|REQUEST_MORE_INFO, rationale}`. The reviewer is always the caller, never a field of the body.

| Route | Permission | Body | Answer |
|---|---|---|---|
| `GET /oversight/halts` | read | | `{halts:[{scope:"assistant", target, halted, since, by, eventId}]}` |
| `POST /oversight/halts` | review | `{scope:"assistant", target, lift, reason}` | the human event raised |

A halt is one approver; a restart needs two. Reopen, flags and standing decisions answer 501 by design.

## Governance

| Route | Permission | Answer |
|---|---|---|
| `GET /governance/controls` | read | `{systemId, digest, controls:[{rowId, name, mechanism, lifecycle, corpusRef, summary, proofEvent, boundary, human}]}` |
| `GET /governance/evidence?control=&since=` | read | `{catalogDigest, count, truncated, evidence:[...]}` |
| `GET /governance/compliance` | read | `{catalogDigest, asOf, freshnessDays, controls:[standing], gaps}` |
| `GET /governance/controls/{id}/claims` | read | `{acceptance, assertion, earlier, attachments, coverage}` |
| `POST /governance/controls/{id}/acceptance` | approve | `{rationale}` |
| `POST /governance/controls/{id}/acceptance/withdraw` | approve | |
| `POST /governance/controls/{id}/assertion` | administer | `{evidenceKind, expects:[{name, maxAgeDays}], rationale}` |
| `POST /governance/controls/{id}/documents` | administer | `{name, ref, asOf}` |
| `POST /governance/controls/{id}/waiver`, `/waiver/lift` | administer | `{reason, until}` |
| `GET /governance/integrity?from=&limit=` | read | `{scheme, count, head, from, checked, intact, problems, next}` |
| `POST /governance/documents/{name}` | administer | `{kind, ref, asOf, content}`: registers a version; `kind` is `procedure`, `policy`, `test-report`, `mapping` or `other`; only the digest of `content` is kept |
| `GET /governance/documents`, `GET /governance/documents/{name}` | read | the newest version of each, or every version of one |
| `POST /governance/ask` | read | `{text}` answer: `{intent, controlId, periodDays, facts:[{text, refs}], limits, summary, summaryChecked, unsupported, clarification, supported}` |
| `GET /governance/interactions/{interactionId}` | read | `{interactionId, agent, at, systemMessage, input, reply, inputTokens, outputTokens, failure, verdicts[{evaluator, controlId, outcome, passed, label, explanation, at}]}`: the runtime ledger's record of a model call named by a `ledger:` reference, and the runtime's verdicts on it; 404 for an interaction of another project |
| `GET /governance/contract` | read | `{present, intact, problems, digest, revision, systemId, corpusVersion, createdAt, signOffRole, signedOffBy, changed, unsigned, notRunning}`: the bundle in force (the newest signed in the Akka local console, in `governance/signed/`), whether its hashes and sign-offs check out, and how the running controls compare with it; `revision` is the bundle's version. The compliance report carries the same as `contract`, each control's `contract` (`SIGNED`, `CHANGED` or `UNSIGNED`), and `gaps.notAsSigned` |
| `GET /governance/rte?limit=` | read | RDF Turtle (`text/turtle`) in the Runtime Evidence vocabulary; the most recent `limit` records, at most 5000 |
| `GET /governance/record-stores` | read | `{stores:[{id, holds, scheme, note, records, head}]}` |

A standing is `{controlId, name, mechanism, status, why, enforcing, upheld, breached, flagged, lastEnforcingAt, acceptance,
assertion, coverage}`. `status` is `SATISFIED`, `FAILING`, `UNRESOLVED`, `STALE`, `NO_EVIDENCE` or `WAIVED`, from enforcing
evidence only. `acceptance` (the binding), `assertion` (what counts as evidence) and `coverage` are separate claims with their own
authors. `gaps` is `{unaccepted, acceptedWithoutAssertion, assertedWithoutEvidence, coveragePartial}`, each a list of control ids.
`scheme` is `HashChained` or `NoIntegrity`. Keep `head` where the service cannot write.

## Data domains

| Route | Permission | Body | Answer |
|---|---|---|---|
| `GET /domains` | read | | `{governed, domains:[{name, description, entityTypes, readerRoles, readerPeople, changedBy, changedAt}], joins:[{a, b, approvedBy, rationale, at}]}` |
| `POST /domains/{name}` | administer | `{description, entityTypes, readerRoles, readerPeople}` | domain |
| `POST /domains/{name}/remove` | administer | | list |
| `POST /join-rules` | approve | `{a, b, rationale}` | list |
| `POST /join-rules/revoke` | administer | `{a, b}` | list |

With no domains every question is allowed. With one, a question reads only types in a domain its asker is granted, and links two
domains only under an approved join rule. A refusal is a 403 listing every reason. The approver of a join rule is someone other
than whoever last changed either domain.
