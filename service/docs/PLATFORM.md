# The context service in the platform

This service is written to become a shared service of the Akka platform, deployed the same way as
Optimize. This page says what that means for the code, and what the platform still has to provide.

## Shape

- **One project per deployment.** A deployment serves the project it runs in. Everything it stores
  belongs to that project, so no route names a project or an organization. The project id comes from
  `contextlayer.project-id`, which the platform sets through `KALIX_META_PROJECT`. A local run uses
  `local`.
- **Routes** are under `/v1/context`. The human events contract for the console is under
  `/v1/context/oversight`.
- **Sign-in** is the platform's. Every endpoint carries `@JWT(validate = BEARER_TOKEN)` and
  `@Acl` open to the internet principal, so the runtime checks the token and the service decides what
  the caller may do.
- **Authorization** is the `service-auth` library that Optimize uses. The service asks for one of
  five permissions per route. A deployment that knows its project admits only callers who hold
  access to that project; a local run admits any authenticated caller.

## Permissions

| Permission | Allows |
|---|---|
| `project.governedContext.read` | Look at playbooks, connectors, cases, watchers, evidence and compliance |
| `project.governedContext.operate` | Feed records in, ask questions, request actions, run watchers, build playbooks and connectors |
| `project.governedContext.review` | Judge links, cases, human events and unknown action outcomes |
| `project.governedContext.approve` | Authorize an action, a release, a stage change or a connector |
| `project.governedContext.administer` | Waive controls and set where notifications go |

The four-eyes rule does not depend on these names. Whoever asked for something can never be the one
who approves it, whatever they hold.

The roles shown in human events (`viewer`, `operator`, `reviewer`, `approver`, `admin`) are not
stored anywhere. They are worked out from the permissions a caller holds, so a control that routes
to `role:approver` reaches whoever holds the approve permission.

## Evidence and its integrity

The service keeps its own evidence ledger and chains it (see `README.md`). When the verify model ships, the ledger,
verdicts and signed bundles are the platform's, and this service writes to them instead. The control ids, the
separation of acceptance from assertion, and the record-store declarations are written to carry over unchanged.

## Data domains

Who may read what is decided by data domains kept in the service (see `README.md`), not by a platform
permission per domain. A grant names roles, which are worked out from the five permissions above, and people.
Until the permissions are registered (ask 1), every caller the platform rule admits holds all five, so the only
role any caller has is `admin`, and a domain separates callers only by the people it names.

## Live data

A source can push its changes (see `README.md`). The service reads the service stream `entity_changes` of
the publishing service in the same project, and tells connected clients through a notification stream of a
per-project entity. Notices are not stored; the stream is the way to follow it, and the data itself is always
read from the graph.

## Storage

- Decisions, evidence, cases, halts and notification routes are event sourced entities and key value
  entities in the service. The platform keeps them durable.
- The entity graph is in Neo4j, because questions are composed at run time and followed across
  records, which views cannot do (see `PLAN.md`). The graph can be rebuilt from the sources.
  Neo4j Community has one database, so a deployment's project is stored as `orgId` on every node and
  statement.

## What the platform still has to provide

These are recorded in `asks.md`, because they belong to other teams.
