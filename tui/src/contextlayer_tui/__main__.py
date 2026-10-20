"""`contextlayer-tui` — open the console on a running service, or on sample data with `--demo`."""

from __future__ import annotations

import argparse
import getpass
import os

from .api import HttpApi, dev_token
from .app import ContextLayerApp
from .demo import DemoApi

DEFAULT_ROLES = "operator,reviewer,approver"


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="contextlayer-tui", description="A console for the context layer service.")
    parser.add_argument("--url", default="http://localhost:9162", help="the service address (default: %(default)s)")
    parser.add_argument("--org", default="demo", help="the organization to work in (default: %(default)s)")
    parser.add_argument("--token", default=os.environ.get("CONTEXTLAYER_TOKEN"),
                        help="a bearer token from your identity provider (default: $CONTEXTLAYER_TOKEN)")
    parser.add_argument("--user", default=None,
                        help="local development only, without --token: who you are (default: your login name)")
    parser.add_argument("--roles", default=DEFAULT_ROLES,
                        help="local development only, without --token: roles to claim (default: %(default)s)")
    parser.add_argument("--demo", action="store_true", help="use built-in sample data instead of a service")
    args = parser.parse_args(argv)

    user = args.user or getpass.getuser()
    roles = [r.strip() for r in args.roles.split(",") if r.strip()]
    if args.demo:
        app = ContextLayerApp(DemoApi(subject=user, roles=roles), org="acme", org_title="Acme Insurance (sample data)")
    else:
        # Without a token, make an unsigned one: a service running locally accepts it, a deployed one refuses it.
        token = args.token or dev_token(user, roles, args.org)
        app = ContextLayerApp(HttpApi(args.url, args.org, token), org=args.org)
    app.run()


if __name__ == "__main__":
    main()
