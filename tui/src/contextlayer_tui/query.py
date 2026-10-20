"""The small language the Ask screen understands, turned into the service's question shape.

    Show      Claim
    Where     status = OPEN and daysOpen > 30
    Link      Account where balance > 250000 ; Client
    Return    Claim.claimId, Account.balance

Conditions are joined with `and`. Operators: = != > >= < <=. A value is a number, true/false, or
text (quote it if it has spaces). Nothing here is sent as query text: the service takes the parts
as data and refuses anything it does not understand.
"""

from __future__ import annotations

import re
from typing import Any

OPERATORS = {"=": "EQ", "==": "EQ", "!=": "NEQ", ">": "GT", ">=": "GTE", "<": "LT", "<=": "LTE"}

_CONDITION = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(>=|<=|!=|==|=|>|<)\s*(.+?)\s*$")
_AND = re.compile(r"\s+and\s+", re.IGNORECASE)
_TYPE = re.compile(r"^[A-Z][A-Za-z0-9]*$")
_REFERENCE = re.compile(r"^[A-Z][A-Za-z0-9]*\.[A-Za-z_][A-Za-z0-9_]*$")


class ParseError(ValueError):
    """The text is not a question; the message says which part and why."""


def parse_value(text: str) -> Any:
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        return text[1:-1]
    if text.lower() in ("true", "false"):
        return text.lower() == "true"
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        return text


def parse_conditions(text: str) -> list[dict[str, Any]]:
    """`status = OPEN and daysOpen > 30` -> a list of {field, op, value}."""
    text = text.strip()
    if not text:
        return []
    out = []
    for part in _AND.split(text):
        match = _CONDITION.match(part)
        if not match:
            raise ParseError(f"cannot read the condition '{part.strip()}'; write it like  daysOpen > 30")
        field, operator, value = match.groups()
        out.append({"field": field, "op": OPERATORS[operator], "value": parse_value(value)})
    return out


_LINK_HEAD = re.compile(
    r"^(?P<type>\S+)(?:\s+by\s+(?P<via>\w+))?(?P<back>\s+backwards)?(?:\s+up\s+to\s+(?P<depth>\d+)\s+steps?)?$", re.IGNORECASE)


def parse_links(text: str) -> list[dict[str, Any]]:
    """`Account where balance > 250000 ; Claim by HAS_ACCOUNT backwards up to 2 steps` -> a list of links.

    Without `by`, a link is to records that are the same party. With it, the link follows the named relationship, forwards
    unless `backwards`, for one step unless `up to N steps`.
    """
    links = []
    for part in filter(None, (p.strip() for p in text.split(";"))):
        pieces = re.split(r"\s+where\s+", part, maxsplit=1, flags=re.IGNORECASE)
        head = _LINK_HEAD.match(pieces[0].strip())
        tail = pieces[1] if len(pieces) > 1 else ""
        if head is None or not _TYPE.match(head["type"]):
            raise ParseError(f"'{pieces[0].strip()}' is not an entity type; types start with a capital letter, and may be followed by 'by NAME'")
        link: dict[str, Any] = {"entityType": head["type"], "where": parse_conditions(tail)}
        if head["via"]:
            link.update(via=head["via"], direction="IN" if head["back"] else "OUT", depth=int(head["depth"] or 1))
        elif head["back"] or head["depth"]:
            raise ParseError("'backwards' and 'up to N steps' go with a relationship: write 'by NAME' first")
        links.append(link)
    return links


def parse_returns(text: str) -> list[str]:
    fields = [f.strip() for f in text.split(",") if f.strip()]
    if not fields:
        raise ParseError("say what to return, for example  Claim.claimId, Account.balance")
    for field in fields:
        if not _REFERENCE.match(field):
            raise ParseError(f"'{field}' must be written Type.field")
    return fields


def build_question(entity_type: str, where: str, links: str, returns: str, limit: str) -> dict[str, Any]:
    entity_type = entity_type.strip()
    if not _TYPE.match(entity_type):
        raise ParseError("Show: an entity type such as Claim (starts with a capital letter)")
    try:
        parsed_limit = int(limit.strip() or "100")
    except ValueError as error:
        raise ParseError("Limit must be a whole number") from error
    if not 1 <= parsed_limit <= 1000:
        raise ParseError("Limit must be between 1 and 1000")
    return {
        "entityType": entity_type,
        "where": parse_conditions(where),
        "links": parse_links(links),
        "returnFields": parse_returns(returns),
        "limit": parsed_limit,
    }


def describe(question: dict[str, Any]) -> str:
    """The question as the sentence a person would say."""
    parts = [f"{question['entityType']}"]
    if question.get("where"):
        parts.append("where " + " and ".join(_condition_text(c) for c in question["where"]))
    for link in question.get("links", []):
        text = f"linked to {link['entityType']}" + _via_text(link)
        if link.get("where"):
            text += " where " + " and ".join(_condition_text(c) for c in link["where"])
        parts.append(text)
    return " ".join(parts)


def _via_text(link: dict[str, Any]) -> str:
    """The relationship a link follows, in the words the Ask form uses, or nothing for a link by identity."""
    if not link.get("via"):
        return ""
    text = f" by {link['via']}"
    if link.get("direction") == "IN":
        text += " backwards"
    if link.get("depth", 1) > 1:
        text += f" up to {link['depth']} steps"
    return text


def _condition_text(c: dict[str, Any]) -> str:
    symbol = {v: k for k, v in OPERATORS.items() if k not in ("==",)}[c["op"]]
    value = c["value"]
    if isinstance(value, str) and (not value or value != value.strip() or " " in value
                                   or value.lower() in ("true", "false") or _looks_numeric(value)):
        value = f'"{value}"'
    elif isinstance(value, bool):
        value = str(value).lower()
    return f"{c['field']} {symbol} {value}"


def _looks_numeric(text: str) -> bool:
    try:
        float(text)
    except ValueError:
        return False
    return True


def to_fields(question: dict[str, Any]) -> dict[str, str]:
    """A drafted question as the text of the Ask form's fields, so a person can read and change it."""
    links = []
    for link in question.get("links", []):
        text = link["entityType"] + _via_text(link)
        if link.get("where"):
            text += " where " + " and ".join(_condition_text(c) for c in link["where"])
        links.append(text)
    return {
        "show": question["entityType"],
        "where": " and ".join(_condition_text(c) for c in question.get("where", [])),
        "link": " ; ".join(links),
        "ret": ", ".join(question["returnFields"]),
        "limit": str(question.get("limit", 100)),
    }
