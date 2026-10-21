# Implementation plan notes

Records the choices the Akka constitution requires to be justified.

## External dependency: Neo4j Java driver (Bolt)

**What:** `org.neo4j.driver:neo4j-java-driver`, talking to a Neo4j 5 server.

**Why not Akka primitives alone:** the entity store must answer questions that join entities of
different types through confirmed links (REQUIREMENTS QRY-01, ENT-02). Akka Views
project a single entity type per table and cannot traverse relationships between entity
instances. Modelling the same traversals in views would mean a hand-built join index per
question shape.

**Constraints kept:**
- Questions are structured (entity types, filters, links), never raw Cypher from users. The store
  compiles them into parameterized Cypher, always scoped by organization id.
- Neo4j Community Edition has a single database, so organizations are isolated by an `orgId`
  property on every node and a mandatory `orgId` parameter on every statement, enforced in one
  class and covered by cross-organization tests. Per-organization databases need Enterprise
  Edition; the licence decision is open (design O-1).
- The store holds projections and links only. Sources remain the system of record.

**Test approach:** tests that touch the store need a Neo4j server. They read its address from
`NEO4J_URI` (default `bolt://localhost:7687`) and `NEO4J_PASSWORD`, and are skipped with a clear
message when no server is reachable. Models are mocked in every test.

## Standard library HTTP client

Source reads use the JDK `java.net.http.HttpClient`, so no HTTP dependency is added.

## External dependency: Jackson YAML

**What:** `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`, at the same Jackson release as
the SDK (2.22.3).

**Why not Akka primitives alone:** the control catalog is authored as `governance/controls.yaml`,
the format the governance tooling reads and diffs. The SDK ships Jackson for JSON only, so parsing
YAML needs this module (it brings SnakeYAML). It reads one file at startup and is used nowhere else.

## Decision: the entity store stays Neo4j

Considered and rejected: holding source records in key-value or event-sourced entities and answering
questions with multi-table views. Views are written at development time, so a user or a model could
not compose a new question at run time; joins have a fixed number of hops, so ownership chains and
related-party questions cannot be asked; grouped queries only collect rows, so per-group counts and
totals cannot be asked. Those are the questions the product exists for.

What this does and does not mean for the platform:
- The graph is derived. The decisions (links, reviews, approvals, evidence) are
  event-sourced and are the durable record; sources stay the system of record for the data.
  Losing the graph means rebuilding it, not losing information. What the platform has to provide is
  availability and a rebuild path, not a second source of truth.
- Neo4j Community Edition is licensed GPLv3 and has no clustering, one database, and no online backup.
  The service talks to it over the network through the Apache-licensed driver. Running it is not a
  problem; shipping it as part of the platform needs a licence review, and a customer who needs
  high availability or hard tenant isolation needs Enterprise.
