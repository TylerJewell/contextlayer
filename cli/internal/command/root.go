// Package command builds the akka-context command tree.
//
// Conventions follow the akka CLI: plural nouns, -o text or json, results on stdout and notes on
// stderr, --force as the only way past a prompt, and exit codes that stay binary.
package command

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/akka/context/cli/internal/client"
	"github.com/spf13/cobra"
)

type env struct {
	in     io.Reader
	out    io.Writer
	errOut io.Writer

	url     string
	output  string
	force   bool
	timeout time.Duration
}

// New returns the root command reading from in and writing to out and errOut.
func New(in io.Reader, out, errOut io.Writer) *cobra.Command {
	e := &env{in: in, out: out, errOut: errOut}
	root := &cobra.Command{
		Use:           "akka-context",
		Short:         "Ask questions of governed context and act on what needs a person",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.SetOut(out)
	root.SetErr(errOut)
	flags := root.PersistentFlags()
	flags.StringVar(&e.url, "url", firstSet(os.Getenv("CONTEXT_URL"), "http://localhost:9162"), "address of the context service")
	flags.StringVarP(&e.output, "output", "o", "text", "text, json or json-compact")
	flags.BoolVar(&e.force, "force", false, "do not ask before a guarded command")
	flags.DurationVar(&e.timeout, "timeout", 30*time.Second, "how long to wait for the service")
	root.AddCommand(e.assistants(), e.approvals(), e.ask(), e.watch(), e.changes(), e.cases(), e.domains(), e.halts(), e.controls(), e.compliance(), e.integrity(), e.interaction(), e.contract(), e.recordStores(), e.rte(), e.audit(), e.documents(), e.routes())
	return root
}

func firstSet(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// token is read the way the akka CLI reads it: the access token as it stands.
func token() string { return firstSet(os.Getenv("AKKA_ACCESS_TOKEN"), os.Getenv("AKKA_TOKEN")) }

// run does one call and prints a failure as a sentence before returning it.
func (e *env) run(method, path string, body any) (any, error) {
	result, err := client.New(e.url, token(), e.timeout).Do(method, path, body)
	if err != nil {
		fmt.Fprintln(e.errOut, "error: "+err.Error())
	}
	return result, err
}

// emit prints a result as JSON when asked for it, and reports whether it did.
func (e *env) emit(value any) bool {
	switch e.output {
	case "json":
		raw, _ := json.MarshalIndent(value, "", "  ")
		fmt.Fprintln(e.out, string(raw))
		return true
	case "json-compact":
		raw, _ := json.Marshal(value)
		fmt.Fprintln(e.out, string(raw))
		return true
	}
	return false
}

// say prints a sentence saying what a write did.
func (e *env) say(format string, args ...any) {
	fmt.Fprintf(e.out, format+"\n", args...)
}

// confirm asks before a guarded command. With no terminal on stdin it refuses rather than assume.
func (e *env) confirm(question string) bool {
	if e.force {
		return true
	}
	if file, ok := e.in.(*os.File); ok {
		if info, err := file.Stat(); err != nil || info.Mode()&os.ModeCharDevice == 0 {
			fmt.Fprintln(e.errOut, "error: this needs confirmation and there is no terminal; use --force to state the decision")
			return false
		}
	}
	fmt.Fprintf(e.errOut, "%s [y/N] ", question)
	line, _ := bufio.NewReader(e.in).ReadString('\n')
	answer := strings.ToLower(strings.TrimSpace(line))
	return answer == "y" || answer == "yes"
}

// table prints rows under a header.
func (e *env) table(header []string, rows [][]string) {
	w := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(w, strings.ToUpper(strings.Join(header, "\t")))
	for _, row := range rows {
		fmt.Fprintln(w, strings.Join(row, "\t"))
	}
	w.Flush()
}

// field reads a nested value by dotted path from decoded JSON, or "" when it is not there.
func field(value any, path string) string {
	current := value
	for _, part := range strings.Split(path, ".") {
		object, ok := current.(map[string]any)
		if !ok {
			return ""
		}
		current = object[part]
	}
	switch v := current.(type) {
	case nil:
		return ""
	case string:
		return v
	case float64:
		if v == float64(int64(v)) {
			return fmt.Sprintf("%d", int64(v))
		}
		return fmt.Sprintf("%g", v)
	case bool:
		return fmt.Sprintf("%t", v)
	default:
		raw, _ := json.Marshal(v)
		return string(raw)
	}
}

// items reads a list from a field of a decoded response.
func items(value any, key string) []any {
	object, _ := value.(map[string]any)
	list, _ := object[key].([]any)
	return list
}

// listing prints the rows of a list response under the given columns, each a dotted field path.
func (e *env) listing(result any, key string, columns ...string) {
	if e.emit(result) {
		return
	}
	var rows [][]string
	for _, row := range items(result, key) {
		cells := make([]string, len(columns))
		for i, column := range columns {
			cells[i] = field(row, column)
		}
		rows = append(rows, cells)
	}
	e.table(columns, rows)
}
