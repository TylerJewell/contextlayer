# akka-context

The command line for the governed context service.

```bash
go install ./cmd/akka-context
export AKKA_ACCESS_TOKEN=...        # the token, as the akka CLI reads it
export CONTEXT_URL=http://localhost:9162
akka-context assistants
akka-context ask --words "open claims over a month for wealthy clients"
akka-context ask --words "open claims" --run
akka-context watch -f question.json   # the answer, then rows added and removed as data changes
akka-context changes --types Claim    # records as they change
akka-context domains set claims --types Claim --roles operator
akka-context domains joins approve claims wealth --rationale "triage needs both"
akka-context controls claims custom-cl0003   # who accepted it, who asserted what counts as its evidence
akka-context controls assert custom-cl0003 --kind HumanDecisionRecorded --expect "override procedure:365" --rationale "..."
akka-context integrity                         # is the evidence ledger unaltered, and what is its head
akka-context approvals list
akka-context approvals claim ID
akka-context approvals decide ID --approve --rationale "the payload is routine"
akka-context halts halt ASSISTANT --reason "wrong customers"
akka-context compliance
```

## Conventions

They follow the akka CLI.

- `-o text` is the default. `-o json` and `-o json-compact` return the service body.
- Results go to standard output and notes to standard error.
- A write says what happened in a sentence.
- A command that changes what happens (`approvals decide`, `halts`, running a drafted question)
  asks first. `--force` states the decision. With no terminal on standard input the command refuses
  rather than assume.
- Exit codes are binary. A refusal exits 1.

## Asking in words

`ask --words` shows the question the service drafted and runs nothing. The drafting model sees the
shape of the data and never a record. Read the draft, then run it with `--run`, or save it with
`-o json` and pass it back with `-f`.

The service address is a root. The CLI adds `/v1/context`.
