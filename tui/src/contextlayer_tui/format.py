"""How things are written on screen: ages, status badges, and one colour language.

Green is healthy or done, amber needs a look, red failed or refused, blue is in progress or a
person's decision, dim is inactive or not yet.
"""

from __future__ import annotations

import time
from datetime import datetime
from typing import Any

from rich.markup import escape

OK, WARN, BAD, INFO, DIM = "green", "yellow", "red", "dodger_blue1", "grey58"


def now_ms() -> int:
    return int(time.time() * 1000)


def ago(at_ms: int | None, now: int | None = None) -> str:
    """'4 min ago', '2 hr ago', '3 d ago'; a dash when it never happened."""
    if not at_ms:
        return "—"
    seconds = max(0, ((now if now is not None else now_ms()) - at_ms) // 1000)
    if seconds < 45:
        return "just now"
    if seconds < 90 * 60:
        return f"{max(1, round(seconds / 60))} min ago"
    if seconds < 36 * 3600:
        return f"{round(seconds / 3600)} hr ago"
    return f"{round(seconds / 86400)} d ago"


def until(at_ms: int | None, now: int | None = None) -> str:
    """'in 3 hr', 'in 20 min', '2 hr overdue': how long to a deadline."""
    if not at_ms:
        return "—"
    seconds = ((now if now is not None else now_ms()) - at_ms) // 1000
    late = seconds > 0
    seconds = abs(seconds)
    if seconds < 90 * 60:
        span = f"{max(1, round(seconds / 60))} min"
    elif seconds < 36 * 3600:
        span = f"{round(seconds / 3600)} hr"
    else:
        span = f"{round(seconds / 86400)} d"
    return f"{span} overdue" if late else f"in {span}"


def clock(at_ms: int) -> str:
    return datetime.fromtimestamp(at_ms / 1000).strftime("%b %d %H:%M")


def clip(text: Any, width: int) -> str:
    text = str(text)
    return text if len(text) <= width else text[: width - 1] + "…"


def tag(text: str, colour: str, bold: bool = False) -> str:
    return f"[{'bold ' if bold else ''}{colour}]{escape(text)}[/]"


_STATUS: dict[str, tuple[str, str, str]] = {
    # status -> (symbol, colour, words)
    "OK": ("●", OK, "healthy"),
    "PARTIAL": ("▲", WARN, "partial"),
    "FAILED": ("✖", BAD, "failed"),
    "NEVER": ("○", DIM, "not read yet"),
    "OPEN": ("●", INFO, "open"),
    "NO_LONGER_MATCHING": ("◐", WARN, "no longer matches"),
    "RESOLVED": ("✔", OK, "resolved"),
    "DISMISSED": ("–", DIM, "dismissed"),
    "AWAITING_APPROVAL": ("⏳", INFO, "awaiting approval"),
    "WOULD_DO": ("◌", INFO, "would do"),
    "DENIED": ("✖", BAD, "denied"),
    "SUCCEEDED": ("✔", OK, "succeeded"),
    "REJECTED": ("✖", BAD, "rejected"),
    "OUTCOME_UNKNOWN": ("?", WARN, "outcome unknown"),
    "PENDING_REVIEW": ("●", WARN, "needs review"),
    "CONFIRMED": ("✔", OK, "confirmed"),
    "REFRESHED_NOW": ("●", OK, "read just now"),
    "WITHIN_POLICY": ("●", OK, "recent enough"),
    "MAINTAINED": ("●", OK, "kept current"),
    "STALE": ("▲", WARN, "stale"),
    "UNAVAILABLE": ("✖", BAD, "unavailable"),
    "DRAFT": ("○", DIM, "draft"),
    "SHADOW": ("◐", INFO, "shadow"),
    "LIVE": ("●", OK, "live"),
    "READ_ONLY": ("◐", INFO, "read-only"),
    "INACTIVE": ("○", DIM, "not active"),
    # things waiting for a person
    "CLAIMED": ("◉", INFO, "being reviewed"),
    "AWAITING_SECOND": ("◐", INFO, "needs a second person"),
    "ESCALATED": ("▲", WARN, "escalated"),
    "CHANGES_REQUESTED": ("↩", WARN, "sent back"),
    "DECIDED": ("✔", OK, "decided"),
    "APPLIED": ("✔", OK, "done"),
    "TIMED_OUT": ("✖", BAD, "timed out"),
    "WITHDRAWN": ("–", DIM, "withdrawn"),
    "running": ("●", OK, "on time"),
    "at-risk": ("▲", WARN, "due soon"),
    "overdue": ("✖", BAD, "overdue"),
    # where a control stands
    "SATISFIED": ("●", OK, "satisfied"),
    "FAILING": ("✖", BAD, "failing"),
    "UNRESOLVED": ("▲", WARN, "unresolved"),
    "NO_EVIDENCE": ("○", DIM, "no evidence yet"),
    "WAIVED": ("–", INFO, "waived"),
}


def badge(status: str, words: bool = True) -> str:
    symbol, colour, label = _STATUS.get(status, ("•", DIM, status.lower().replace("_", " ")))
    return f"[{colour}]{symbol}[/]" + (f" [{colour}]{escape(label)}[/]" if words else "")


def dot(status: str) -> str:
    return badge(status, words=False)


_RISK = {"UNATTENDED": ("runs unattended", OK), "NEEDS_APPROVAL": ("needs approval", INFO),
         "NEVER_BY_ASSISTANT": ("never by a playbook", BAD)}


def risk(name: str) -> str:
    label, colour = _RISK.get(name, (name.lower(), DIM))
    return tag(label, colour)


def pct(confidence: float) -> str:
    return f"{round(confidence * 100)}%"


def money(value: Any) -> str:
    return f"${value:,.0f}" if isinstance(value, (int, float)) and not isinstance(value, bool) else str(value)


def cell(value: Any) -> str:
    """A result cell: numbers get thousands separators, missing values a dash."""
    if value is None:
        return "[grey58]—[/]"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, int):
        return f"{value:,}"
    return escape(str(value))
