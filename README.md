# Context Layer

A governed context layer built on the Akka SDK. It answers questions across source systems, says how current each answer is, and keeps people in charge of identity, access and actions.

| Folder | What it is |
|---|---|
| `service/` | The Akka service (port 9162, Neo4j for the entity graph). Start with `service/README.md` and `service/docs/`. |
| `cli/` | The `akka-context` command line client, written in Go. |
| `tui/` | A terminal interface built with Textual, with a built-in demo mode. |
| `claims-system/` | A small demo upstream service (port 9163) that the service reads from. |

## Building

The service depends on the local snapshot `io.akka:service-auth:0.1.0-SNAPSHOT`, which is built from the Akka platform repository. It will not build without that artifact installed in the local Maven repository.

Tests that use Neo4j expect a local container. The password in the poms and docs is a local development value only.
