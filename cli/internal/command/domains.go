package command

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/spf13/cobra"
)

func (e *env) domains() *cobra.Command {
	group := &cobra.Command{
		Use:   "domains",
		Short: "Data domains: who may ask about which entity types, and which domains may be linked",
	}
	group.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List the data domains and the approved links between them",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := e.run("GET", "/domains", nil)
			if err != nil {
				return err
			}
			if e.emit(result) {
				return nil
			}
			if field(result, "governed") != "true" {
				fmt.Fprintln(e.out, "No data domains are defined, so every question is allowed.")
				return nil
			}
			e.listing(result, "domains", "name", "entityTypes", "readerRoles", "readerPeople", "changedBy")
			if joins := items(result, "joins"); len(joins) > 0 {
				fmt.Fprintln(e.out)
				e.listing(result, "joins", "a", "b", "approvedBy", "rationale")
			}
			return nil
		},
	})

	var description string
	var types, roles, people []string
	set := &cobra.Command{
		Use:   "set NAME",
		Short: "Create a data domain or replace its definition",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(types) == 0 {
				return fmt.Errorf("a data domain needs --types")
			}
			if len(roles) == 0 && len(people) == 0 {
				return fmt.Errorf("a data domain needs --roles or --people to say who may read it")
			}
			if _, err := e.run("POST", "/domains/"+url.PathEscape(args[0]), map[string]any{
				"description": description, "entityTypes": types, "readerRoles": roles, "readerPeople": people}); err != nil {
				return err
			}
			e.say("Data domain %s now holds %s, readable by %s.", args[0], strings.Join(types, ", "), readers(roles, people))
			return nil
		},
	}
	set.Flags().StringVar(&description, "description", "", "what the domain holds")
	set.Flags().StringSliceVar(&types, "types", nil, "entity types in the domain, such as Claim,Note")
	set.Flags().StringSliceVar(&roles, "roles", nil, "roles that may read it: viewer, operator, reviewer, approver, admin")
	set.Flags().StringSliceVar(&people, "people", nil, "people (subjects) who may read it")
	group.AddCommand(set)

	group.AddCommand(&cobra.Command{
		Use:   "remove NAME",
		Short: "Remove a data domain and the join rules that name it",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !e.confirm(fmt.Sprintf("Remove the %s data domain? Its types become unreadable until assigned elsewhere.", args[0])) {
				return fmt.Errorf("not confirmed")
			}
			if _, err := e.run("POST", "/domains/"+url.PathEscape(args[0])+"/remove", map[string]any{}); err != nil {
				return err
			}
			e.say("Removed the %s data domain.", args[0])
			return nil
		},
	})
	group.AddCommand(e.joins())
	return group
}

func (e *env) joins() *cobra.Command {
	group := &cobra.Command{Use: "joins", Short: "Approve or revoke linking two data domains in one question"}
	var rationale string
	approve := &cobra.Command{
		Use:   "approve A B",
		Short: "Approve linking two data domains",
		Long: "A question that reads types from both domains is refused until this is approved. It is a decision recorded\n" +
			"with its reason, and it has to be made by someone other than whoever last changed either domain.",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(rationale) == "" {
				return fmt.Errorf("an approval needs --rationale")
			}
			if !e.confirm(fmt.Sprintf("Allow questions to link %s and %s?", args[0], args[1])) {
				return fmt.Errorf("not confirmed")
			}
			if _, err := e.run("POST", "/join-rules", map[string]any{"a": args[0], "b": args[1], "rationale": rationale}); err != nil {
				return err
			}
			e.say("Questions may now link %s and %s, for people who may read both.", args[0], args[1])
			return nil
		},
	}
	approve.Flags().StringVar(&rationale, "rationale", "", "why, in words")
	group.AddCommand(approve)
	group.AddCommand(&cobra.Command{
		Use:   "revoke A B",
		Short: "Stop allowing questions to link two data domains",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			if _, err := e.run("POST", "/join-rules/revoke", map[string]any{"a": args[0], "b": args[1]}); err != nil {
				return err
			}
			e.say("Questions can no longer link %s and %s.", args[0], args[1])
			return nil
		},
	})
	return group
}

func readers(roles, people []string) string {
	var parts []string
	if len(roles) > 0 {
		parts = append(parts, "roles "+strings.Join(roles, ", "))
	}
	if len(people) > 0 {
		parts = append(parts, "people "+strings.Join(people, ", "))
	}
	return strings.Join(parts, " and ")
}
