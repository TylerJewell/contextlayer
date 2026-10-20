// Command akka-context is the command line for the governed context service.
package main

import (
	"fmt"
	"os"

	"github.com/akka/context/cli/internal/command"
)

func main() {
	if err := command.New(os.Stdin, os.Stdout, os.Stderr).Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
