# Context Layer

A governed context layer built on the Akka SDK. It answers questions across sources, says how current each answer is, and keeps people in charge of identity, access and actions.

| Folder | What it is |
|---|---|
| `service/` | The Akka service (port 9162, Neo4j for the entity graph). Start with `service/README.md` and `service/docs/`. |
| `cli/` | The `akka-context` command line client, written in Go. |
| `tui/` | A terminal interface built with Textual, with a built-in demo mode. |
| `claims-system/` | A small demo upstream service (port 9163) that the service reads from. |
| `contract/` | Signs the service's control catalog as a Governance Control Contract and verifies it. Plain Java, no Akka. |

## Building

The service depends on the local snapshot `io.akka:service-auth:0.1.0-SNAPSHOT`, which is built from the `akka/nexus` repository. It will not build without that artifact, and its parent poms, installed in the local Maven repository:

```bash
git clone https://github.com/akka/nexus.git
cd nexus
mvn install -N                                  # the root pom, which service-auth's pom names as its parent
mvn install -pl service-auth -am -DskipTests    # service-auth and the parents it needs
```

Then `mvn test` in `service/` runs the unit tests; the ones that need Neo4j are skipped when no server is reachable.

The command line builds with `go build -o akka-context ./cmd/akka-context` in `cli/`.

`contract/` needs `io.akka:governance-contract:0.1.0-SNAPSHOT` from the same repository. Build it on its own, not
through the nexus reactor, which does not list it:

```bash
cd nexus/governance-contract && mvn install -DskipTests
```

## The signed control contract

`service/src/main/resources/governance/controls.yaml` is the catalog the service runs.
`service/src/main/resources/governance/contract/` is that catalog signed: the risk team's key signs off the
controls and the publisher's key signs the whole, as an OCI layout. The service checks that every part hashes as
declared and compares each signed control with the one it runs. Its test fails when a control in `controls.yaml`
differs from the signed one, so **re-sign after every change to `controls.yaml`**:

```bash
cd contract
# once: private keys outside the repository, public trust in contract/trust
mvn -q exec:java -Dexec.args="keys --keys C:/Users/<you>/.akka/contextlayer/contract-keys --trust trust --risk-team <name>"
# after each change to controls.yaml
mvn -q exec:java -Dexec.args="sign --controls ../service/src/main/resources/governance/controls.yaml --layout ../service/src/main/resources/governance/contract --keys C:/Users/<you>/.akka/contextlayer/contract-keys --trust trust --commit <sha>"
# what an auditor runs: needs a JVM, the layout and contract/trust, nothing else
mvn -q exec:java -Dexec.args="verify --layout ../service/src/main/resources/governance/contract --trust trust"
```

Give Java paths in Windows form (`C:/...`). A Git Bash path such as `/c/Users/...` is read by Java as `C:\c\Users\...`.

Without `--tsa URL` the signature is dated by a timestamp authority made by `keys`, so it shows when the signer says
it signed. Name an independent RFC 3161 authority with `--tsa` for a date an outsider can rely on.

Tests that use Neo4j expect a local container. The password in the poms and docs is a local development value only.
