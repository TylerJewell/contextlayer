package command

import (
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/akka/context/cli/internal/client"
	"github.com/spf13/cobra"
)

func (e *env) changes() *cobra.Command {
	var types []string
	command := &cobra.Command{
		Use:   "changes",
		Short: "Show records as they change, until interrupted",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			path := "/streams/changes"
			if len(types) > 0 {
				path += "?types=" + url.QueryEscape(strings.Join(types, ","))
			}
			return e.follow("GET", path, nil, func(event map[string]any) {
				at := time.UnixMilli(int64(number(event["at"]))).Format("15:04:05")
				id := field(event, "entityId")
				if id == "" {
					id = "(read whole)"
				}
				fmt.Fprintf(e.out, "%s  %-8s %-10s %s  via %s\n", at, field(event, "op"), field(event, "entityType"), id, field(event, "pack"))
			})
		},
	}
	command.Flags().StringSliceVar(&types, "types", nil, "only these entity types, such as Claim,Account")
	return command
}

func (e *env) watch() *cobra.Command {
	var words, file string
	command := &cobra.Command{
		Use:   "watch",
		Short: "Keep a question open and see what changes in its answer",
		Long: "Shows the answer now, then each row added to it and removed from it as the data it reads changes.\n" +
			"A monitor is the same thing with a case as its output. Stops when interrupted.",
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
				e.show(drafted)
				if !e.confirm("Watch this question?") {
					return fmt.Errorf("not confirmed")
				}
			} else {
				var err error
				if question, err = readDocument(e.in, file); err != nil {
					return err
				}
			}
			return e.follow("POST", "/streams/watch", question, func(event map[string]any) {
				switch field(event, "event") {
				case "snapshot":
					fmt.Fprintf(e.out, "Now: %s rows\n", field(event, "total"))
					e.rowsTable(listOf(event["rows"]))
					for _, source := range listOf(event["sources"]) {
						if freshness := field(source, "freshness"); freshness == "STALE" || freshness == "UNAVAILABLE" {
							fmt.Fprintf(e.errOut, "%s/%s is %s\n", field(source, "pack"), field(source, "entityType"), strings.ToLower(freshness))
						}
					}
				case "changed":
					at := time.Now().Format("15:04:05")
					for _, row := range listOf(event["added"]) {
						fmt.Fprintf(e.out, "%s  + %s\n", at, rowText(row))
					}
					for _, row := range listOf(event["removed"]) {
						fmt.Fprintf(e.out, "%s  - %s\n", at, rowText(row))
					}
					fmt.Fprintf(e.out, "%s    %s rows now\n", at, field(event, "total"))
				}
			})
		},
	}
	command.Flags().StringVar(&words, "words", "", "the request in words")
	command.Flags().StringVarP(&file, "file", "f", "", "a question document, or - for standard input")
	return command
}

// follow reads a stream until it ends. In a JSON output mode each event is printed as it arrives.
func (e *env) follow(method, path string, body any, show func(event map[string]any)) error {
	err := client.New(e.url, token(), e.timeout).Stream(method, path, body, func(data string) bool {
		var event map[string]any
		if json.Unmarshal([]byte(data), &event) != nil {
			return true
		}
		if e.output == "json" || e.output == "json-compact" {
			fmt.Fprintln(e.out, data)
		} else {
			show(event)
		}
		return true
	})
	if err != nil {
		fmt.Fprintln(e.errOut, "error: "+err.Error())
	}
	return err
}

func number(value any) float64 {
	n, _ := value.(float64)
	return n
}

func listOf(value any) []any {
	list, _ := value.([]any)
	return list
}

// rowText writes a row as key=value pairs in a fixed order.
func rowText(row any) string {
	object, _ := row.(map[string]any)
	keys := make([]string, 0, len(object))
	for key := range object {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		parts = append(parts, key+"="+field(map[string]any{"v": object[key]}, "v"))
	}
	return strings.Join(parts, "  ")
}

func (e *env) rowsTable(rows []any) {
	if len(rows) == 0 {
		fmt.Fprintln(e.out, "No rows.")
		return
	}
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
	cells := make([][]string, 0, len(rows))
	for _, row := range rows {
		line := make([]string, len(names))
		for i, name := range names {
			line[i] = field(map[string]any{"v": row.(map[string]any)[name]}, "v")
		}
		cells = append(cells, line)
	}
	e.table(names, cells)
}
