import pytest

from contextlayer_tui import format as fmt
from contextlayer_tui.app import ContextLayerApp
from contextlayer_tui.demo import DemoApi

STANDARD_ROLES = ["operator", "reviewer", "approver"]


class Clock:
    """A clock the test moves by hand, so a review that takes ten seconds does not take ten seconds."""

    def __init__(self, start: int) -> None:
        self.now = start

    def advance(self, seconds: int) -> None:
        self.now += seconds * 1000


@pytest.fixture
def clock(monkeypatch) -> Clock:
    clock = Clock(fmt.now_ms())
    monkeypatch.setattr(fmt, "now_ms", lambda: clock.now)
    return clock


def app_for(subject: str, roles: list[str]) -> ContextLayerApp:
    return ContextLayerApp(DemoApi(subject=subject, roles=roles), org="acme", org_title="Acme Insurance (sample data)")


@pytest.fixture
def demo_app(clock) -> ContextLayerApp:
    return app_for("tyler", STANDARD_ROLES)


@pytest.fixture
def make_app(clock):
    return app_for
