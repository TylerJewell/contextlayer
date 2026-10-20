package command

import (
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"sort"
	"strings"

	"github.com/spf13/cobra"
)

func (e *env) playbooks() *cobra.Command {
	return &cobra.Command{
		Use: "playbooks",
		// The "assistants" alias keeps scripts that use that name working.
		Aliases: []string{"assistants"},
		Short:   "List the playbooks",
		Args:    cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/assistants", nil)
			if err != nil {
				return err
			}
			e.listing(result, "items", "assistantId", "purpose", "owner", "stage")
			return nil
		},
	}
}

func (e *env) decisions() *cobra.Command {
	// The "approvals" alias keeps scripts that use that name working.
	group := &cobra.Command{Use: "decisions", Aliases: []string{"approvals"}, Short: "Things that cannot happen until a person says yes or no"}
	group.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List what is waiting for a decision",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/oversight/events?state=pending", nil)
			if err != nil {
				return err
			}
			e.listing(result, "events", "id", "state", "subject.kind", "subject.summary", "sla.dueAt")
			return nil
		},
	})
	group.AddCommand(&cobra.Command{
		Use:   "get ID",
		Short: "Show one event with what the reviewer is asked to check",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/oversight/events/"+url.PathEscape(args[0]), nil)
			if err != nil {
				return err
			}
			if e.emit(result) {
				return nil
			}
			for _, row := range [][2]string{
				{"id", "id"}, {"state", "state"}, {"control", "control.name"}, {"summary", "subject.summary"},
				{"asked", "ask.question"}, {"due", "sla.dueAt"}, {"held by", "holder.subject"},
				{"minimum review seconds", "minimumReviewSeconds"},
			} {
				fmt.Fprintf(e.out, "%-24s %s\n", row[0]+":", field(result, row[1]))
			}
			return nil
		},
	})
	group.AddCommand(&cobra.Command{
		Use:   "claim ID",
		Short: "Take an event, which is when its review time starts",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if _, err := e.run("POST", "/oversight/events/"+url.PathEscape(args[0])+"/claim", map[string]any{}); err != nil {
				return err
			}
			e.say("Claimed %s. Review it, then decide it.", args[0])
			return nil
		},
	})
	var approve, reject bool
	var rationale string
	decide := &cobra.Command{
		Use:   "decide ID",
		Short: "Approve or reject a claimed event, with a reason",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if approve == reject {
				return fmt.Errorf("say --approve or --reject")
			}
			if strings.TrimSpace(rationale) == "" {
				return fmt.Errorf("a decision needs --rationale")
			}
			action := "DENY"
			if approve {
				action = "APPROVE"
			}
			if !e.confirm(fmt.Sprintf("%s %s?", strings.ToLower(action), args[0])) {
				return fmt.Errorf("not confirmed")
			}
			if _, err := e.run("POST", "/oversight/events/"+url.PathEscape(args[0])+"/decision",
				map[string]any{"action": action, "rationale": rationale}); err != nil {
				return err
			}
			e.say("Recorded %s for %s.", strings.ToLower(action), args[0])
			return nil
		},
	}
	decide.Flags().BoolVar(&approve, "approve", false, "approve")
	decide.Flags().BoolVar(&reject, "reject", false, "reject")
	decide.Flags().StringVar(&rationale, "rationale", "", "why, in words")
	group.AddCommand(decide)
	return group
}

func (e *env) ask() *cobra.Command {
	var words, file string
	var run bool
	command := &cobra.Command{
		Use:   "ask",
		Short: "Ask a question of the data, in words or as a question document",
		Long: "With --words, the service drafts a structured question from a request in words. It does not run it:\n" +
			"read the draft, then run it with --run or save it with -o json and pass it back with -f.",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			if (words == "") == (file == "") {
				return fmt.Errorf("give --words or -f, not both and not neither")
			}
			var question any
			if words != "" {
				drafted, err := e.run("POST", "/questions/draft", map[string]any{"text": words})
				if err != nil {
					return err
				}
				question = drafted.(map[string]any)["question"]
				if question == nil {
					return e.noDraft(drafted)
				}
				if !run {
					return e.showDraft(drafted)
				}
				e.show(drafted)
				if !e.confirm("Run this question?") {
					return fmt.Errorf("not confirmed")
				}
			} else {
				var err error
				if question, err = readDocument(e.in, file); err != nil {
					return err
				}
			}
			answer, err := e.run("POST", "/questions", question)
			if err != nil {
				return err
			}
			return e.showAnswer(answer)
		},
	}
	command.Flags().StringVar(&words, "words", "", "the request in words")
	command.Flags().StringVarP(&file, "file", "f", "", "a question document, or - for standard input")
	command.Flags().BoolVar(&run, "run", false, "run the drafted question after showing it")
	return command
}

func readDocument(in io.Reader, file string) (any, error) {
	var raw []byte
	var err error
	if file == "-" {
		raw, err = io.ReadAll(in)
	} else {
		raw, err = os.ReadFile(file)
	}
	if err != nil {
		return nil, err
	}
	var document any
	if err := json.Unmarshal(raw, &document); err != nil {
		return nil, fmt.Errorf("%s is not a JSON document: %w", file, err)
	}
	if object, ok := document.(map[string]any); ok {
		if inner, ok := object["question"].(map[string]any); ok {
			return inner, nil
		}
	}
	return document, nil
}

func (e *env) noDraft(drafted any) error {
	if e.emit(drafted) {
		return fmt.Errorf("no question was drafted")
	}
	if text := field(drafted, "clarification"); text != "" {
		fmt.Fprintln(e.out, "The service needs to know: "+text)
		return nil
	}
	fmt.Fprintln(e.out, "No question could be drafted:")
	for _, problem := range items(drafted, "problems") {
		fmt.Fprintf(e.out, "  - %v\n", problem)
	}
	return fmt.Errorf("no question was drafted")
}

func (e *env) showDraft(drafted any) error {
	if e.emit(drafted) {
		return nil
	}
	e.show(drafted)
	return nil
}

func (e *env) show(drafted any) {
	if e.output != "text" {
		return
	}
	fmt.Fprintln(e.out, field(drafted, "explanation"))
	question, _ := drafted.(map[string]any)["question"].(map[string]any)
	fmt.Fprintf(e.out, "  type:    %s\n", field(question, "entityType"))
	for _, c := range items(question, "where") {
		fmt.Fprintf(e.out, "  where:   %s %s %s\n", field(c, "field"), field(c, "op"), field(c, "value"))
	}
	for _, link := range items(question, "links") {
		via := ""
		if name := field(link, "via"); name != "" {
			via = fmt.Sprintf(" by %s (%s, %s step(s))", name, strings.ToLower(field(link, "direction")), field(link, "depth"))
		}
		fmt.Fprintf(e.out, "  linked:  %s%s\n", field(link, "entityType"), via)
		for _, c := range items(link, "where") {
			fmt.Fprintf(e.out, "           %s %s %s\n", field(c, "field"), field(c, "op"), field(c, "value"))
		}
	}
	var returns []string
	for _, r := range items(question, "returnFields") {
		returns = append(returns, fmt.Sprint(r))
	}
	fmt.Fprintf(e.out, "  returns: %s\n", strings.Join(returns, ", "))
}

func (e *env) showAnswer(answer any) error {
	if e.emit(answer) {
		return nil
	}
	rows := items(answer, "rows")
	columns := map[string]bool{}
	for _, row := range rows {
		if object, ok := row.(map[string]any); ok {
			for key := range object {
				columns[key] = true
			}
		}
	}
	names := make([]string, 0, len(columns))
	for name := range columns {
		names = append(names, name)
	}
	sort.Strings(names)
	var cells [][]string
	for _, row := range rows {
		line := make([]string, len(names))
		for i, name := range names {
			line[i] = field(map[string]any{"v": row.(map[string]any)[name]}, "v")
		}
		cells = append(cells, line)
	}
	if len(rows) == 0 {
		fmt.Fprintln(e.out, "No rows.")
	} else {
		e.table(names, cells)
	}
	for _, source := range items(answer, "sources") {
		if freshness := field(source, "freshness"); freshness == "STALE" || freshness == "UNAVAILABLE" {
			fmt.Fprintf(e.errOut, "%s/%s is %s", field(source, "pack"), field(source, "entityType"), strings.ToLower(freshness))
			for _, warning := range items(source, "warnings") {
				fmt.Fprintf(e.errOut, "; %v", warning)
			}
			fmt.Fprintln(e.errOut)
		}
	}
	if left := field(answer, "unconfirmedLeftOut"); left != "" && left != "0" {
		fmt.Fprintf(e.errOut, "%s possible matches were left out because a person has not confirmed them.\n", left)
	}
	return nil
}

func (e *env) cases() *cobra.Command {
	group := &cobra.Command{Use: "cases", Short: "Cases opened by monitors"}
	var status string
	list := &cobra.Command{
		Use:   "list",
		Short: "List cases",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			path := "/cases"
			if status != "" {
				path = "/cases/status/" + url.PathEscape(strings.ToUpper(status))
			}
			result, err := e.run("GET", path, nil)
			if err != nil {
				return err
			}
			e.listing(result, "items", "caseId", "monitorId", "key", "status", "routeTo")
			return nil
		},
	}
	list.Flags().StringVar(&status, "status", "", "only cases in this status, such as open")
	group.AddCommand(list)
	group.AddCommand(&cobra.Command{
		Use:   "get ID",
		Short: "Show a case with its timeline",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/cases/"+url.PathEscape(args[0]), nil)
			if err != nil {
				return err
			}
			if e.emit(result) {
				return nil
			}
			for _, row := range [][2]string{{"case", "caseId"}, {"monitor", "monitorId"}, {"key", "key"}, {"status", "status"}, {"routed to", "routeTo"}} {
				fmt.Fprintf(e.out, "%-10s %s\n", row[0]+":", field(result, row[1]))
			}
			for _, entry := range items(result, "timeline") {
				fmt.Fprintf(e.out, "  %s  %s\n", field(entry, "actor"), field(entry, "text"))
			}
			return nil
		},
	})
	return group
}

func (e *env) halts() *cobra.Command {
	group := &cobra.Command{Use: "halts", Short: "Stop a playbook from acting, and let it act again"}
	group.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "Show every playbook and whether it is halted",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/oversight/halts", nil)
			if err != nil {
				return err
			}
			e.listing(result, "halts", "target", "halted", "by", "since")
			return nil
		},
	})
	for _, lift := range []bool{false, true} {
		var reason string
		verb, doing, done := "halt", "halt", "Halt requested for"
		if lift {
			verb, doing, done = "restart", "restart", "Restart requested for"
		}
		command := &cobra.Command{
			Use:   verb + " PLAYBOOK",
			Short: fmt.Sprintf("Ask to %s a playbook", doing),
			Args:  cobra.ExactArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				if strings.TrimSpace(reason) == "" {
					return fmt.Errorf("a %s needs --reason", doing)
				}
				if !e.confirm(fmt.Sprintf("%s %s?", doing, args[0])) {
					return fmt.Errorf("not confirmed")
				}
				result, err := e.run("POST", "/oversight/halts", map[string]any{
					"scope": "assistant", "target": args[0], "lift": lift, "reason": reason})
				if err != nil {
					return err
				}
				if e.emit(result) {
					return nil
				}
				e.say("%s %s. Event %s; some halts and restarts wait for a second person.", done, args[0], field(result, "id"))
				return nil
			},
		}
		command.Flags().StringVar(&reason, "reason", "", "why, in words")
		group.AddCommand(command)
	}
	return group
}

func (e *env) controls() *cobra.Command {
	command := &cobra.Command{
		Use:   "controls",
		Short: "List the controls this service is held to",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/governance/controls", nil)
			if err != nil {
				return err
			}
			e.listing(result, "controls", "rowId", "name", "mechanism", "lifecycle")
			return nil
		},
	}
	e.controlClaims(command)
	return command
}

func (e *env) compliance() *cobra.Command {
	return &cobra.Command{
		Use:   "compliance",
		Short: "Show where each control stands, from the evidence recorded",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/governance/compliance", nil)
			if err != nil {
				return err
			}
			e.listing(result, "controls", "controlId", "name", "status", "enforcing", "acceptance.accepted", "assertion.revision", "coverage.coverage")
			if e.output == "text" {
				gaps := result.(map[string]any)["gaps"]
				for _, gap := range [][2]string{{"unaccepted", "Not accepted"}, {"acceptedWithoutAssertion", "Accepted, nothing asserted about its evidence"},
					{"assertedWithoutEvidence", "Asserted, no enforcing records"}, {"coveragePartial", "Asserted, backing documents missing or too old"}} {
					if ids := items(gaps, gap[0]); len(ids) > 0 {
						fmt.Fprintf(e.out, "%s: %v\n", gap[1], ids)
					}
				}
			}
			return nil
		},
	}
}
