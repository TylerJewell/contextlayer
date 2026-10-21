# What was measured at scale

How the entity store behaves with a lot of records. The test is `ScaleStoreTest`. It writes hundreds of thousands of nodes, so it
runs only when asked:

    mvn test -Dtest=ScaleStoreTest -Dscale=true -Dscale.claims=1000000 -Dscale.accounts=500000

It needs a Neo4j server and writes `target/scale-report.json`. It does not measure the Akka entities, the views or the service
instances; see the last section.

## How to read the numbers

One machine, with Neo4j 5 Community in a container beside the test (2 GB heap, 1 GB page cache) and one connection. They show how cost
grows and where it sits. They are not what a deployment will measure, and they say nothing about concurrent load or a network
between the service and the database.

## Results

Claims are the base type, each with three field values, a status of one of three, and a relationship to an account. Accounts are
the type they point at.

| | 200,000 claims, 100,000 accounts | 1,000,000 claims, 500,000 accounts |
|---|---|---|
| Load accounts | 8.7 s | 35 s |
| Load claims | 13.6 s (14,700 a second) | 70 s (14,200 a second) |
| Relate every claim to its account | 10 s | 52 s |
| Question on two fields, first 100 rows | 30 ms | 86 ms |
| Question for one record by its claim id | 73 ms | 356 ms |
| Question across a relationship, with a condition on the account | 60 ms | 110 ms |
| Schema shown to the model | 655 ms | 1.0 s |
| One pushed change, median | 7 ms | 7 ms |

What they show:

- **Loading is linear**, about 14,000 records a second in batches. A pushed change takes about 7 ms on one connection whatever the
  size, so a stream of changes is limited by the connection and by the per-project entities that order them, not by the graph.
- **A lookup by the record key is an index lookup.** A condition on any other field scans the type, and the cost grows with the
  type: a question for one claim by its id, which is a field and not the key, went from 73 ms to 356 ms for five times the records.
  A question that stops at a hundred rows ends early, which is why the first is fast. If a project asks often about one field, an
  index on it is the first thing to add.
- **Following a relationship is cheap.** The question that crossed one stayed near a tenth of a second at a million records,
  because it starts from the records that match and follows edges rather than joining the types.
- **The schema shown to the model costs more as the project grows** but is sampled, so it grows slowly: 1.0 s at a million claims.
  It is read each time a question is drafted in words.

## What this found and fixed

- A relationship finds its target by type and id from any source, and the key the store has, led by the source, could not serve that.
  Relating 200,000 claims did not finish in twenty minutes. With an index on the project, type and id it took ten seconds.
- Deleting a project in one transaction ran out of the database's transaction memory at 300,000 nodes. It now deletes in batches of ten
  thousand, a label at a time.
- The schema walked every relationship of a type to list their names. It now samples like the fields do.
- A database with a heap too small for the load fails with an out of memory error from the server. The container above gave Neo4j 2 GB.

## Not measured

- **The Akka side.** Most entities are per record, per control or per decision and scale by id. Two take every write of their kind for
  a project: the integrity chain, which orders evidence, and the change feed, which announces changes. One entity's throughput is the
  limit on evidence recorded and changes announced per project, and that has not been measured. If it matters for a project, the
  chain can order batches of records instead of one at a time.
- **Concurrent questions and writes together.**
- **Several service instances** against one database.
- **Links** at scale. Each is an entity, and a record offers its keys to the clusters on every write.
- **A real network** between the service and the database, which adds to every number above.
