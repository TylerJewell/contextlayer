package command

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/akka/context/cli/internal/client"
	"github.com/spf13/cobra"
)

// controlClaims adds to the controls command what people have said about a control: who accepted it
// and who asserted what counts as its evidence, which are separate claims by separate people.
func (e *env) controlClaims(controls *cobra.Command) {
	controls.AddCommand(&cobra.Command{
		Use:   "claims CONTROL",
		Short: "Show who accepted a control and who asserted what counts as its evidence",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/governance/controls/"+url.PathEscape(args[0])+"/claims", nil)
			if err != nil {
				return err
			}
			if e.emit(result) {
				return nil
			}
			if field(result, "acceptance.accepted") == "true" {
				current := ""
				if field(result, "acceptance.current") != "true" {
					current = " (of an earlier version of the catalog)"
				}
				fmt.Fprintf(e.out, "Accepted by %s on %s%s: %s\n", field(result, "acceptance.by"), field(result, "acceptance.at"), current, field(result, "acceptance.rationale"))
			} else {
				fmt.Fprintln(e.out, "Not accepted.")
			}
			if field(result, "assertion.asserted") == "true" {
				fmt.Fprintf(e.out, "Asserted by %s (revision %s): %s counts as evidence. %s\n", field(result, "assertion.by"),
					field(result, "assertion.revision"), field(result, "assertion.evidenceKind"), field(result, "assertion.rationale"))
			} else {
				fmt.Fprintln(e.out, "Nothing asserted about what counts as evidence.")
			}
			fmt.Fprintf(e.out, "Coverage: %s\n", field(result, "coverage.coverage"))
			for _, unmet := range items(result.(map[string]any)["coverage"], "unmet") {
				fmt.Fprintf(e.out, "  missing: %v\n", unmet)
			}
			return nil
		},
	})

	var acceptRationale string
	accept := &cobra.Command{
		Use:   "accept CONTROL",
		Short: "Accept a control for this project, at the current version of the catalog",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(acceptRationale) == "" {
				return fmt.Errorf("an acceptance needs --rationale")
			}
			if !e.confirm(fmt.Sprintf("Accept %s for this project?", args[0])) {
				return fmt.Errorf("not confirmed")
			}
			if _, err := e.run("POST", "/governance/controls/"+url.PathEscape(args[0])+"/acceptance", map[string]any{"rationale": acceptRationale}); err != nil {
				return err
			}
			e.say("Accepted %s. This says the project took it on; it does not say what counts as its evidence.", args[0])
			return nil
		},
	}
	accept.Flags().StringVar(&acceptRationale, "rationale", "", "why, in words")
	controls.AddCommand(accept)

	var kind, assertRationale string
	var expects []string
	assert := &cobra.Command{
		Use:   "assert CONTROL",
		Short: "Assert which kind of record counts as evidence for a control, and what else it needs",
		Long: "Each assertion is a new revision with its own author. --expect names a document the evidence is\n" +
			"expected to be backed by, as name:maxAgeDays, such as \"override procedure:365\".",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if kind == "" || strings.TrimSpace(assertRationale) == "" {
				return fmt.Errorf("an assertion needs --kind and --rationale")
			}
			var parsed []map[string]any
			for _, expected := range expects {
				i := strings.LastIndex(expected, ":")
				days, err := strconv.Atoi(strings.TrimSpace(expected[i+1:]))
				if i < 1 || err != nil {
					return fmt.Errorf("--expect %q is not name:maxAgeDays", expected)
				}
				parsed = append(parsed, map[string]any{"name": strings.TrimSpace(expected[:i]), "maxAgeDays": days})
			}
			result, err := e.run("POST", "/governance/controls/"+url.PathEscape(args[0])+"/assertion",
				map[string]any{"evidenceKind": kind, "expects": parsed, "rationale": assertRationale})
			if err != nil {
				return err
			}
			e.say("Asserted revision %s: %s counts as evidence for %s.", field(result, "revision"), kind, args[0])
			return nil
		},
	}
	assert.Flags().StringVar(&kind, "kind", "", "the proof event of the records that count, such as HumanDecisionRecorded")
	assert.Flags().StringVar(&assertRationale, "rationale", "", "why, in words")
	assert.Flags().StringArrayVar(&expects, "expect", nil, "a document the evidence needs, as name:maxAgeDays")
	controls.AddCommand(assert)

	var name, ref, asOf string
	attach := &cobra.Command{
		Use:   "attach CONTROL",
		Short: "Attach a document an assertion expects, with the date it was last current",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if name == "" || ref == "" || asOf == "" {
				return fmt.Errorf("attaching needs --name, --ref and --as-of")
			}
			result, err := e.run("POST", "/governance/controls/"+url.PathEscape(args[0])+"/documents",
				map[string]any{"name": name, "ref": ref, "asOf": asOf})
			if err != nil {
				return err
			}
			e.say("Attached %s. Coverage is now %s.", name, field(result, "coverage"))
			return nil
		},
	}
	attach.Flags().StringVar(&name, "name", "", "the expected document, as named in the assertion")
	attach.Flags().StringVar(&ref, "ref", "", "where the document is")
	attach.Flags().StringVar(&asOf, "as-of", "", "the date it was last current, yyyy-mm-dd")
	controls.AddCommand(attach)
}

func (e *env) integrity() *cobra.Command {
	var from, limit int
	command := &cobra.Command{
		Use:   "integrity",
		Short: "Check that the evidence ledger has not been altered",
		Long: "Checks the chain of evidence records a stretch at a time. Keep the head it prints somewhere the\n" +
			"service cannot write: it is what lets you show later that nothing before it was changed.",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			path := fmt.Sprintf("/governance/integrity?from=%d&limit=%d", from, limit)
			result, err := e.run("GET", path, nil)
			if err != nil {
				return err
			}
			if e.emit(result) {
				if field(result, "intact") != "true" {
					return fmt.Errorf("the chain is not intact")
				}
				return nil
			}
			fmt.Fprintf(e.out, "%s: %s records, checked %s from %s\n", field(result, "scheme"), field(result, "count"), field(result, "checked"), field(result, "from"))
			fmt.Fprintf(e.out, "head: %s\n", field(result, "head"))
			if field(result, "intact") == "true" {
				fmt.Fprintln(e.out, "Intact for the part checked.")
				if next := field(result, "next"); next != "" {
					fmt.Fprintf(e.out, "More to check: --from %s\n", next)
				}
				return nil
			}
			for _, problem := range items(result, "problems") {
				fmt.Fprintf(e.out, "  problem: %v\n", problem)
			}
			return fmt.Errorf("the chain is not intact")
		},
	}
	command.Flags().IntVar(&from, "from", 1, "the first link to check")
	command.Flags().IntVar(&limit, "limit", 1000, "how many links to check")
	return command
}

func (e *env) recordStores() *cobra.Command {
	return &cobra.Command{
		Use:   "record-stores",
		Short: "Show what holds the records, and whether each is tamper-evident",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/governance/record-stores", nil)
			if err != nil {
				return err
			}
			e.listing(result, "stores", "id", "scheme", "records", "holds")
			return nil
		},
	}
}

func (e *env) rte() *cobra.Command {
	var limit int
	command := &cobra.Command{
		Use:   "rte",
		Short: "Export what the project knows in the Runtime Evidence vocabulary, as Turtle",
		Long: "Writes RDF Turtle to standard output: the controls as obligations, the acceptances and assertions as\n" +
			"separate attributed claims, and the most recent chained evidence records as activities with their position\n" +
			"in the ledger. Redirect it to a file to load it into a graph.",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			text, err := client.New(e.url, token(), e.timeout).Text("GET", fmt.Sprintf("/governance/rte?limit=%d", limit))
			if err != nil {
				fmt.Fprintln(e.errOut, "error: "+err.Error())
				return err
			}
			fmt.Fprint(e.out, text)
			return nil
		},
	}
	command.Flags().IntVar(&limit, "limit", 500, "how many of the most recent evidence records to include")
	return command
}

func (e *env) audit() *cobra.Command {
	return &cobra.Command{
		Use:   "audit QUESTION",
		Short: "Ask a question about the controls and get an answer from recorded facts",
		Long: "A model only chooses which of a fixed list of questions was asked. The facts come from recorded data, and a second\n" +
			"model words them; its words are checked against the facts, and replaced by the facts when they claim more.\n" +
			"The facts and the records they come from are always shown.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("POST", "/governance/ask", map[string]any{"text": args[0]})
			if err != nil {
				return err
			}
			if e.emit(result) {
				return nil
			}
			if question := field(result, "clarification"); question != "" {
				fmt.Fprintln(e.out, question)
				for _, supported := range items(result, "supported") {
					fmt.Fprintf(e.out, "  %v\n", supported)
				}
				return nil
			}
			fmt.Fprintln(e.out, field(result, "summary"))
			if field(result, "summaryChecked") != "true" {
				fmt.Fprintln(e.errOut, "note: the model's wording claimed more than the facts say, so the facts are given as they are.")
			}
			fmt.Fprintln(e.out, "\nFacts:")
			for _, fact := range items(result, "facts") {
				fmt.Fprintf(e.out, "  - %s  [%s]\n", field(fact, "text"), strings.Join(stringsOf(items(fact, "refs")), ", "))
			}
			if limits := items(result, "limits"); len(limits) > 0 {
				fmt.Fprintln(e.out, "\nThe records do not show:")
				for _, limit := range limits {
					fmt.Fprintf(e.out, "  - %v\n", limit)
				}
			}
			return nil
		},
	}
}

func stringsOf(values []any) []string {
	out := make([]string, 0, len(values))
	for _, v := range values {
		out = append(out, fmt.Sprint(v))
	}
	return out
}

func (e *env) documents() *cobra.Command {
	group := &cobra.Command{Use: "documents", Short: "The documents that back a control's evidence: procedures, policies, test reports"}
	group.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List the project's documents, each as its newest version",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/governance/documents", nil)
			if err != nil {
				return err
			}
			e.listing(result, "documents", "name", "kind", "asOf", "versions", "ref")
			return nil
		},
	})
	group.AddCommand(&cobra.Command{
		Use:   "get NAME",
		Short: "Show every version of a document",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/governance/documents/"+url.PathEscape(args[0]), nil)
			if err != nil {
				return err
			}
			e.listing(result, "versions", "version", "kind", "asOf", "by", "digest", "ref")
			return nil
		},
	})
	var kind, ref, asOf, file string
	add := &cobra.Command{
		Use:   "add NAME",
		Short: "Register a version of a document, with the date it was last current",
		Long: "With --file, the file is read here and only its SHA-256 is sent and kept. Anyone holding the document can compute the\n" +
			"same digest to show it is the one that was registered. A control's coverage is judged by the newest version's date.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if ref == "" || asOf == "" {
				return fmt.Errorf("registering a document needs --ref and --as-of")
			}
			body := map[string]any{"kind": kind, "ref": ref, "asOf": asOf}
			if file != "" {
				content, err := os.ReadFile(file)
				if err != nil {
					return err
				}
				body["content"] = string(content)
			}
			result, err := e.run("POST", "/governance/documents/"+url.PathEscape(args[0]), body)
			if err != nil {
				return err
			}
			e.say("Registered version %s of %s, last current %s.", field(result, "version"), args[0], asOf)
			return nil
		},
	}
	add.Flags().StringVar(&kind, "kind", "other", "procedure, policy, test-report, mapping or other")
	add.Flags().StringVar(&ref, "ref", "", "where the document is")
	add.Flags().StringVar(&asOf, "as-of", "", "the date this version was last current, yyyy-mm-dd")
	add.Flags().StringVar(&file, "file", "", "a local copy, whose SHA-256 is recorded")
	group.AddCommand(add)
	return group
}

func (e *env) routes() *cobra.Command {
	group := &cobra.Command{Use: "routes", Short: "Where a team is told when something needs it"}
	var address, format, credential string
	var emails []string
	set := &cobra.Command{
		Use:   "set NAME",
		Short: "Set where a route's messages go: a web address, or email addresses",
		Long: "A route is named for a team, or role:<role>. Give --email for one to ten addresses, or --address for a web address that\n" +
			"takes a JSON post (--format json) or a Slack incoming webhook (--format slack).",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if (address == "") == (len(emails) == 0) {
				return fmt.Errorf("give --address or --email, not both and not neither")
			}
			body := map[string]any{"format": format, "credentialRef": credential}
			if len(emails) > 0 {
				body["format"] = "email"
				body["url"] = "mailto:" + strings.Join(emails, ",")
			} else {
				body["url"] = address
			}
			result, err := e.run("POST", "/routes/"+pathName(args[0]), body)
			if err != nil {
				return err
			}
			e.say("%s now sends %s to %s.", args[0], field(result, "format"), field(result, "destination"))
			return nil
		},
	}
	set.Flags().StringVar(&address, "address", "", "a web address that takes the message")
	set.Flags().StringSliceVar(&emails, "email", nil, "email addresses, up to ten")
	set.Flags().StringVar(&format, "format", "json", "json or slack, for a web address")
	set.Flags().StringVar(&credential, "credential-ref", "", "a reference to the bearer token for a web address")
	group.AddCommand(set)
	group.AddCommand(&cobra.Command{
		Use:   "test NAME",
		Short: "Send a test message and say whether it was delivered",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("POST", "/routes/"+pathName(args[0])+"/test", map[string]any{})
			if err != nil {
				return err
			}
			e.listing(result, "items", "route", "status", "detail")
			return nil
		},
	})
	return group
}

// pathName writes a route name as one path segment.
func pathName(name string) string {
	return strings.ReplaceAll(url.PathEscape(name), ":", "%3A")
}
