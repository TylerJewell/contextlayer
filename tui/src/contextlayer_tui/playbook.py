"""A playbook as the console shows and edits it: its steps, drawn top to bottom as a flow of boxes.

The service stores a playbook as JSON and decides whether it is valid; this module only reads, draws and writes it.
The step types keep the service's names (classify, branch, act, annotate) and are shown as Judge, Choose, Act and Note.
"""

from __future__ import annotations

import json
import textwrap
from typing import Any

from rich.text import Text

from .format import DIM, INFO, OK, WARN

KINDS = {"classify": "Judge", "branch": "Choose", "act": "Act", "annotate": "Note"}
END = "end"
BOX = 46  # the width of a step's box, borders included

# The fields each step type keeps when written back; anything else a step carries is dropped.
FIELDS = {
    "classify": ("instruction", "fields", "labels", "store", "next"),
    "branch": ("var", "cases", "otherwise"),
    "act": ("actionType", "payload", "next"),
    "annotate": ("text", "next"),
}


def parse(definition: str | None) -> list[dict[str, Any]]:
    """The steps of a definition, or none when it is empty or not a playbook."""
    if not definition:
        return []
    try:
        steps = json.loads(definition).get("steps")
    except (ValueError, AttributeError):
        return []
    return [dict(s) for s in steps if isinstance(s, dict)] if isinstance(steps, list) else []


def definition(steps: list[dict[str, Any]]) -> str:
    """The steps as the service takes them: each with its id, type and the fields of that type."""
    out = []
    for step in steps:
        kept = {"id": step.get("id", ""), "type": step.get("type", "")}
        for name in FIELDS.get(step.get("type", ""), ()):
            if name in step:
                kept[name] = step[name]
        out.append(kept)
    return json.dumps({"steps": out})


def new_step(kind: str, step_id: str, next_id: str) -> dict[str, Any]:
    """A step of the given type with every field it needs present, ready to be filled in."""
    return {
        "classify": {"id": step_id, "type": kind, "instruction": "", "fields": [], "labels": [], "store": "", "next": next_id},
        "branch": {"id": step_id, "type": kind, "var": "", "cases": {}, "otherwise": next_id},
        "act": {"id": step_id, "type": kind, "actionType": "", "payload": "", "next": next_id},
        "annotate": {"id": step_id, "type": kind, "text": "", "next": next_id},
    }[kind]


def free_id(steps: list[dict[str, Any]], kind: str) -> str:
    """A step id not yet used, named for the step type."""
    base = KINDS[kind].lower()
    taken = {s.get("id") for s in steps}
    n = 1
    while f"{base}-{n}" in taken:
        n += 1
    return f"{base}-{n}"


def targets(step: dict[str, Any]) -> list[str]:
    """Where a step can go next, in the order the box lists them."""
    if step.get("type") == "branch":
        return [*dict(step.get("cases") or {}).values(), step.get("otherwise") or END]
    return [step.get("next") or END]


def draw(steps: list[dict[str, Any]], selected: int | None = None, risks: dict[str, str] | None = None) -> Text:
    """The steps as boxes from top to bottom. A step that goes straight on to the box below is joined to it by an
    arrow; any other target is named under the box. The selected step's box is drawn in bold."""
    out = Text()
    if not steps:
        out.append("No steps yet.", style=DIM)
        return out
    for i, step in enumerate(steps):
        style = f"bold {WARN}" if i == selected else INFO
        kind = KINDS.get(step.get("type", ""), step.get("type", "?"))
        title = f" {i + 1} {step.get('id', '')} "
        out.append("┌─" + title + "─" * max(1, BOX - 4 - len(title) - len(kind) - 2) + f" {kind} ┐\n", style=style)
        for line in _body(step, risks or {}):
            for wrapped in textwrap.wrap(line, BOX - 4) or [""]:
                out.append("│ ", style=style)
                out.append(wrapped.ljust(BOX - 4))
                out.append(" │\n", style=style)
        out.append("└" + "─" * (BOX - 2) + "┘\n", style=style)
        following = steps[i + 1].get("id") if i + 1 < len(steps) else None
        if step.get("type") == "branch":
            out.append("\n")
        elif targets(step)[0] == END:
            out.append(" " * (BOX // 2 - 2) + "■ end\n\n", style=DIM)
        elif targets(step)[0] == following:
            out.append(" " * (BOX // 2) + "│\n" + " " * (BOX // 2) + "▼\n", style=DIM)
        else:
            out.append(" " * (BOX // 2 - 2) + f"↳ {targets(step)[0]}\n\n", style=DIM)
    return out


def _body(step: dict[str, Any], risks: dict[str, str]) -> list[str]:
    kind = step.get("type")
    if kind == "classify":
        return [step.get("instruction") or "(no instruction yet)",
                "sees " + (", ".join(step.get("fields") or []) or "nothing yet"),
                f"→ {step.get('store') or '?'}: " + (" ".join(step.get("labels") or []) or "no labels yet")]
    if kind == "branch":
        lines = [f"by {step.get('var') or '?'}"]
        lines += [f"  {label} → {target}" for label, target in dict(step.get("cases") or {}).items()]
        lines.append(f"  otherwise → {step.get('otherwise') or END}")
        return lines
    if kind == "act":
        action = step.get("actionType") or "(no action yet)"
        risk = risks.get(action)
        return [action + (f"  [{risk.lower().replace('_', ' ')}]" if risk else ""), f"with {step.get('payload') or '(nothing)'}"]
    if kind == "annotate":
        return [f"“{step.get('text') or ''}”"]
    return [f"unknown step type {kind}"]


def summary(steps: list[dict[str, Any]]) -> Text:
    """One line about a playbook: how many steps, and what it can do to a source."""
    acts = sorted({s.get("actionType") for s in steps if s.get("type") == "act" and s.get("actionType")})
    text = Text(f"{len(steps)} step(s). ", style=DIM)
    text.append("It may ask to: " + ", ".join(acts) if acts else "It changes nothing in any source.", style=OK if not acts else DIM)
    return text
