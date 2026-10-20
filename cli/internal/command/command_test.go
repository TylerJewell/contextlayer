package command

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

type seen struct {
	method, path, auth string
	body               map[string]any
}

// serve answers each route with the body given for "METHOD path" and records what arrived.
func serve(t *testing.T, answers map[string]string) (*httptest.Server, *[]seen) {
	t.Helper()
	var calls []seen
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := seen{method: r.Method, path: r.URL.RequestURI(), auth: r.Header.Get("Authorization")}
		if raw, _ := io.ReadAll(r.Body); len(raw) > 0 {
			_ = json.Unmarshal(raw, &call.body)
		}
		calls = append(calls, call)
		answer, ok := answers[r.Method+" "+r.URL.RequestURI()]
		if !ok {
			w.WriteHeader(404)
			return
		}
		status := 200
		if strings.HasPrefix(answer, "!") {
			status, answer = 403, answer[1:]
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(answer))
	}))
	t.Cleanup(server.Close)
	return server, &calls
}

func execute(t *testing.T, server *httptest.Server, args ...string) (string, string, error) {
	t.Helper()
	return executeWith(t, strings.NewReader(""), server, args...)
}

func executeWith(t *testing.T, in io.Reader, server *httptest.Server, args ...string) (string, string, error) {
	t.Helper()
	t.Setenv("AKKA_ACCESS_TOKEN", "tok")
	var out, errOut bytes.Buffer
	root := New(in, &out, &errOut)
	root.SetArgs(append([]string{"--url", server.URL}, args...))
	err := root.Execute()
	return out.String(), errOut.String(), err
}

func TestAListShowsTheColumnsOfTheRowsTheServiceReturned(t *testing.T) {
	server, calls := serve(t, map[string]string{
		"GET /v1/context/assistants": `{"items":[{"assistantId":"a1","purpose":"triage","owner":"alice","stage":"LIVE"}]}`,
	})

	out, _, err := execute(t, server, "playbooks")

	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "ASSISTANTID") || !strings.Contains(out, "a1") || !strings.Contains(out, "triage") {
		t.Fatalf("unexpected table:\n%s", out)
	}
	if (*calls)[0].auth != "Bearer tok" {
		t.Fatalf("the token was not sent: %q", (*calls)[0].auth)
	}
}

func TestJSONOutputIsTheServiceBody(t *testing.T) {
	server, _ := serve(t, map[string]string{"GET /v1/context/controls-none": `{}`, "GET /v1/context/governance/controls": `{"controls":[{"rowId":"c1"}]}`})

	out, _, err := execute(t, server, "controls", "-o", "json-compact")

	if err != nil || strings.TrimSpace(out) != `{"controls":[{"rowId":"c1"}]}` {
		t.Fatalf("got %q, %v", out, err)
	}
}

func TestARefusalIsOneSentenceOnStandardError(t *testing.T) {
	server, _ := serve(t, map[string]string{"GET /v1/context/assistants": `!{"error":"requires permission context.read"}`})

	out, errOut, err := execute(t, server, "playbooks")

	if err == nil || out != "" || strings.TrimSpace(errOut) != "error: requires permission context.read" {
		t.Fatalf("out=%q err=%q error=%v", out, errOut, err)
	}
}

func TestDecidingNeedsAReasonAndAChoiceBeforeAnythingIsSent(t *testing.T) {
	server, calls := serve(t, nil)

	if _, _, err := execute(t, server, "decisions", "decide", "e1", "--approve"); err == nil {
		t.Fatal("a decision without a rationale was accepted")
	}
	if _, _, err := execute(t, server, "decisions", "decide", "e1", "--rationale", "fine"); err == nil {
		t.Fatal("a decision without a choice was accepted")
	}
	if len(*calls) != 0 {
		t.Fatalf("calls were made: %v", *calls)
	}
}

func TestADecisionWithNoTerminalAndNoForceIsNotMade(t *testing.T) {
	server, calls := serve(t, nil)

	// A pipe is what a script gives a command: standard input that is not a terminal.
	pipe, _, _ := os.Pipe()

	_, errOut, err := executeWith(t, pipe, server, "decisions", "decide", "e1", "--approve", "--rationale", "fine")

	if err == nil || !strings.Contains(errOut, "use --force") || len(*calls) != 0 {
		t.Fatalf("err=%v errOut=%q calls=%v", err, errOut, *calls)
	}
}

func TestAForcedDecisionSendsTheActionAndTheRationale(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/oversight/events/e1/decision": `{}`})

	out, _, err := execute(t, server, "decisions", "decide", "e1", "--approve", "--rationale", "routine", "--force")

	if err != nil || !strings.Contains(out, "Recorded approve for e1.") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	body := (*calls)[0].body
	if body["action"] != "APPROVE" || body["rationale"] != "routine" {
		t.Fatalf("body: %v", body)
	}
}

func TestRejectingSendsTheActionTheControlAllows(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/oversight/events/e1/decision": `{}`})

	if _, _, err := execute(t, server, "decisions", "decide", "e1", "--reject", "--rationale", "wrong customer", "--force"); err != nil {
		t.Fatal(err)
	}
	if got := (*calls)[0].body["action"]; got != "DENY" {
		t.Fatalf("sent %v, but the control allows APPROVE, DENY, ESCALATE and REQUEST_MORE_INFO", got)
	}
}

func TestAskInWordsShowsTheDraftAndRunsNothingUnlessAsked(t *testing.T) {
	draft := `{"question":{"entityType":"Claim","where":[{"field":"status","op":"EQ","value":"OPEN"}],"links":[],"returnFields":["Claim.claimId"],"limit":100},
	  "explanation":"Open claims.","clarification":"","problems":[]}`
	server, calls := serve(t, map[string]string{"POST /v1/context/questions/draft": draft})

	out, _, err := execute(t, server, "ask", "--words", "open claims")

	if err != nil || !strings.Contains(out, "Open claims.") || !strings.Contains(out, "status EQ OPEN") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	if len(*calls) != 1 {
		t.Fatalf("the question was run without --run: %v", *calls)
	}
}

func TestAskInWordsWithRunPrintsTheRows(t *testing.T) {
	draft := `{"question":{"entityType":"Claim","where":[],"links":[],"returnFields":["Claim.claimId"],"limit":100},"explanation":"All claims.","clarification":"","problems":[]}`
	server, calls := serve(t, map[string]string{
		"POST /v1/context/questions/draft": draft,
		"POST /v1/context/questions":       `{"rows":[{"Claim.claimId":"CL-1"}],"unconfirmedLeftOut":2,"sources":[]}`,
	})

	out, errOut, err := execute(t, server, "ask", "--words", "claims", "--run", "--force")

	if err != nil || !strings.Contains(out, "CL-1") || !strings.Contains(errOut, "2 possible matches") {
		t.Fatalf("out=%q errOut=%q err=%v", out, errOut, err)
	}
	if got := (*calls)[1].body["entityType"]; got != "Claim" {
		t.Fatalf("the drafted question was not the one run: %v", (*calls)[1].body)
	}
}

func TestAnAnswerWithNoRowsSaysWhyWhenASourceWasNotCurrent(t *testing.T) {
	server, _ := serve(t, map[string]string{
		"POST /v1/context/questions": `{"rows":[],"unconfirmedLeftOut":0,"sources":[{"pack":"feed","entityType":"Claim","freshness":"UNAVAILABLE","warnings":["nothing has been received from the source yet"]}]}`,
	})

	out, errOut, err := execute(t, server, "ask", "-f", writeQuestion(t))

	if err != nil || !strings.Contains(out, "No rows.") || !strings.Contains(errOut, "feed/Claim is unavailable; nothing has been received") {
		t.Fatalf("out=%q errOut=%q err=%v", out, errOut, err)
	}
}

func writeQuestion(t *testing.T) string {
	t.Helper()
	path := t.TempDir() + "/q.json"
	if err := os.WriteFile(path, []byte(`{"entityType":"Claim","where":[],"links":[],"returnFields":["Claim.claimId"],"limit":10}`), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestAClarificationIsShownAsTheQuestionBackToThePerson(t *testing.T) {
	server, _ := serve(t, map[string]string{"POST /v1/context/questions/draft": `{"question":null,"explanation":"","clarification":"Which claims?","problems":[]}`})

	out, _, err := execute(t, server, "ask", "--words", "the bad ones")

	if err != nil || !strings.Contains(out, "The service needs to know: Which claims?") {
		t.Fatalf("out=%q err=%v", out, err)
	}
}

func TestADomainNeedsTypesAndSomeoneWhoMayReadIt(t *testing.T) {
	server, calls := serve(t, nil)

	if _, _, err := execute(t, server, "domains", "set", "claims", "--roles", "operator"); err == nil {
		t.Fatal("a domain with no types was accepted")
	}
	if _, _, err := execute(t, server, "domains", "set", "claims", "--types", "Claim"); err == nil {
		t.Fatal("a domain nobody may read was accepted")
	}
	if len(*calls) != 0 {
		t.Fatalf("calls were made: %v", *calls)
	}
}

func TestSettingADomainSendsItsTypesAndReaders(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/domains/claims": `{}`})

	out, _, err := execute(t, server, "domains", "set", "claims", "--types", "Claim,Note", "--roles", "operator", "--people", "carol")

	if err != nil || !strings.Contains(out, "Data domain claims now holds Claim, Note") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	body := (*calls)[0].body
	if types, _ := body["entityTypes"].([]any); len(types) != 2 {
		t.Fatalf("body: %v", body)
	}
}

func TestAJoinApprovalNeedsAReasonAndAConfirmation(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/join-rules": `{}`})

	if _, _, err := execute(t, server, "domains", "joins", "approve", "claims", "wealth", "--force"); err == nil {
		t.Fatal("an approval with no rationale was accepted")
	}
	out, _, err := execute(t, server, "domains", "joins", "approve", "claims", "wealth", "--rationale", "triage", "--force")

	if err != nil || !strings.Contains(out, "may now link claims and wealth") || len(*calls) != 1 {
		t.Fatalf("out=%q err=%v calls=%v", out, err, *calls)
	}
}

func TestAnAcceptanceAndAnAssertionAreSeparateCallsWithTheirOwnReasons(t *testing.T) {
	server, calls := serve(t, map[string]string{
		"POST /v1/context/governance/controls/custom-cl0003/acceptance": `{}`,
		"POST /v1/context/governance/controls/custom-cl0003/assertion":  `{"revision":2}`,
	})

	if _, _, err := execute(t, server, "controls", "accept", "custom-cl0003", "--force"); err == nil {
		t.Fatal("an acceptance with no rationale was accepted")
	}
	if _, _, err := execute(t, server, "controls", "assert", "custom-cl0003", "--rationale", "why", "--force"); err == nil {
		t.Fatal("an assertion with no kind was accepted")
	}
	out, _, err := execute(t, server, "controls", "assert", "custom-cl0003", "--kind", "HumanDecisionRecorded", "--expect", "override procedure:365", "--rationale", "why")

	if err != nil || !strings.Contains(out, "Asserted revision 2") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	expects, _ := (*calls)[0].body["expects"].([]any)
	if len(expects) != 1 {
		t.Fatalf("body: %v", (*calls)[0].body)
	}
}

func TestAnExpectedDocumentNeedsANameAndDays(t *testing.T) {
	server, calls := serve(t, nil)

	_, _, err := execute(t, server, "controls", "assert", "custom-cl0003", "--kind", "K", "--expect", "no days here", "--rationale", "why")

	if err == nil || len(*calls) != 0 {
		t.Fatalf("err=%v calls=%v", err, *calls)
	}
}

func TestAnIntegrityProblemFailsTheCommand(t *testing.T) {
	server, _ := serve(t, map[string]string{
		"GET /v1/context/governance/integrity?from=1&limit=1000": `{"scheme":"HashChained","count":3,"head":"sha256:x","from":1,"checked":3,"intact":false,"problems":["record p:ev:1 (link 1) was altered after it was chained"],"next":null}`,
	})

	out, _, err := execute(t, server, "integrity")

	if err == nil || !strings.Contains(out, "problem: record p:ev:1") {
		t.Fatalf("out=%q err=%v", out, err)
	}
}

func TestTheExportIsWrittenAsItCameWithoutBeingTreatedAsJSON(t *testing.T) {
	server, _ := serve(t, map[string]string{"GET /v1/context/governance/rte?limit=500": "@prefix rte: <https://w3id.org/rte#> .\nex:system a ai:AISystem .\n"})

	out, _, err := execute(t, server, "rte")

	if err != nil || !strings.HasPrefix(out, "@prefix rte:") || !strings.Contains(out, "ex:system a ai:AISystem") {
		t.Fatalf("out=%q err=%v", out, err)
	}
}

func TestAnAuditAnswerShowsTheFactsWithTheirRecordsAndWhatIsNotShown(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/governance/ask": `{"intent":"CONTROL_STATUS","summary":"Dana accepted it.","summaryChecked":true,
	  "facts":[{"text":"It was accepted by dana.","refs":["control:custom-cl0003","person:dana"]}],"limits":["Nobody has asserted anything."],"clarification":""}`})

	out, errOut, err := execute(t, server, "audit", "who accepted the approval control?")

	if err != nil || !strings.Contains(out, "Dana accepted it.") || !strings.Contains(out, "It was accepted by dana.  [control:custom-cl0003, person:dana]") ||
		!strings.Contains(out, "The records do not show:") || errOut != "" {
		t.Fatalf("out=%q errOut=%q err=%v", out, errOut, err)
	}
	if (*calls)[0].body["text"] != "who accepted the approval control?" {
		t.Fatalf("body: %v", (*calls)[0].body)
	}
}

func TestAnAnswerWhoseWordingWasReplacedSaysSo(t *testing.T) {
	server, _ := serve(t, map[string]string{"POST /v1/context/governance/ask": `{"summary":"plain facts","summaryChecked":false,"facts":[],"limits":[],"clarification":""}`})

	_, errOut, err := execute(t, server, "audit", "are we compliant?")

	if err != nil || !strings.Contains(errOut, "claimed more than the facts say") {
		t.Fatalf("errOut=%q err=%v", errOut, err)
	}
}

func TestAQuestionThatCouldNotBePlacedListsWhatCanBeAsked(t *testing.T) {
	server, _ := serve(t, map[string]string{"POST /v1/context/governance/ask": `{"clarification":"Which control do you mean?","supported":["GAPS: where claims fall short"],"facts":[],"limits":[]}`})

	out, _, err := execute(t, server, "audit", "hmm")

	if err != nil || !strings.Contains(out, "Which control do you mean?") || !strings.Contains(out, "GAPS: where claims fall short") {
		t.Fatalf("out=%q err=%v", out, err)
	}
}

func TestRegisteringADocumentSendsTheDigestSourceNotJustTheReference(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/governance/documents/override-procedure": `{"version":2}`})
	file := t.TempDir() + "/op.txt"
	if err := os.WriteFile(file, []byte("step one"), 0o600); err != nil {
		t.Fatal(err)
	}

	out, _, err := execute(t, server, "documents", "add", "override-procedure", "--kind", "procedure", "--ref", "https://docs/op", "--as-of", "2026-10-01", "--file", file)

	if err != nil || !strings.Contains(out, "Registered version 2 of override-procedure") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	if body := (*calls)[0].body; body["content"] != "step one" || body["kind"] != "procedure" {
		t.Fatalf("body: %v", body)
	}
}

func TestADocumentNeedsWhereItIsAndWhenItWasCurrent(t *testing.T) {
	server, calls := serve(t, nil)

	if _, _, err := execute(t, server, "documents", "add", "override-procedure", "--ref", "https://docs/op"); err == nil || len(*calls) != 0 {
		t.Fatalf("err=%v calls=%v", err, *calls)
	}
}

func TestAnEmailRouteIsSetFromAddressesAndNeitherOrBothSourcesIsRefused(t *testing.T) {
	server, calls := serve(t, map[string]string{"POST /v1/context/routes/Claims%20Team%20B": `{"format":"email","destination":"mailto:a@example.com,b@example.com"}`})

	out, _, err := execute(t, server, "routes", "set", "Claims Team B", "--email", "a@example.com,b@example.com")

	if err != nil || !strings.Contains(out, "Claims Team B now sends email to mailto:a@example.com,b@example.com.") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	if body := (*calls)[0].body; body["format"] != "email" || body["url"] != "mailto:a@example.com,b@example.com" {
		t.Fatalf("body: %v", body)
	}
	if _, _, err := execute(t, server, "routes", "set", "x"); err == nil {
		t.Fatal("a route with nowhere to send was accepted")
	}
	if _, _, err := execute(t, server, "routes", "set", "x", "--address", "https://h", "--email", "a@example.com"); err == nil {
		t.Fatal("a route with two places to send was accepted")
	}
}

func TestHaltNeedsAReason(t *testing.T) {
	server, calls := serve(t, nil)

	if _, _, err := execute(t, server, "halts", "halt", "a1", "--force"); err == nil || len(*calls) != 0 {
		t.Fatalf("err=%v calls=%v", err, *calls)
	}
}

func TestAModelCallIsReadFromTheLedgerReferenceAsWritten(t *testing.T) {
	server, calls := serve(t, map[string]string{"GET /v1/context/governance/interactions/i-42": `{"agent":"classifier-agent",
		"at":"t","inputTokens":10,"outputTokens":2,"systemMessage":"label it","input":"text","reply":"{\"label\":\"MAJOR\"}",
		"failure":"","verdicts":[{"controlId":"custom-cl0002","passed":true,"evaluator":"label-allowed-evaluator",
		"explanation":"answered MAJOR"}]}`})

	out, _, err := execute(t, server, "interaction", "ledger:i-42")

	if err != nil || len(*calls) != 1 {
		t.Fatalf("got %v, calls %v", err, *calls)
	}
	if !strings.Contains(out, "custom-cl0002 passed=true label-allowed-evaluator: answered MAJOR") || !strings.Contains(out, "label it") {
		t.Fatalf("got %q", out)
	}
}

func TestTheContractSaysWhichControlsDriftedFromTheSignedOnes(t *testing.T) {
	server, _ := serve(t, map[string]string{"GET /v1/context/governance/contract": `{"present":true,"intact":true,
		"problems":[],"digest":"sha256:ab","revision":2,"systemId":"contextlayer","corpusVersion":"2026-09",
		"createdAt":"t","signOffRole":"risk-team","signedOffBy":"CN=r","changed":["custom-cl0008"],"unsigned":[],"notRunning":[]}`})

	out, _, err := execute(t, server, "contract")

	if err != nil || !strings.Contains(out, "custom-cl0008 runs differently from how it was signed") || !strings.Contains(out, "bundle v2") {
		t.Fatalf("got %q, %v", out, err)
	}
}

func TestApprovalsStillNamesTheDecisionsCommand(t *testing.T) {
	server, calls := serve(t, map[string]string{"GET /v1/context/oversight/events?state=pending": `{"events":[]}`})

	if _, _, err := execute(t, server, "approvals", "list"); err != nil || len(*calls) != 1 {
		t.Fatalf("got %v, calls %v", err, *calls)
	}
}

func TestAssistantsStillNamesThePlaybooksCommand(t *testing.T) {
	server, calls := serve(t, map[string]string{"GET /v1/context/assistants": `{"items":[]}`})

	if _, _, err := execute(t, server, "assistants"); err != nil || len(*calls) != 1 {
		t.Fatalf("got %v, calls %v", err, *calls)
	}
}
