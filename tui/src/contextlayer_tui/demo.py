"""Sample data and a client that behaves like the service, for `--demo` and for tests.

It follows the same rules the real service does, so what a screen shows here is what it would show
there: a risky action waits for approval, a closed case takes no more notes, an unconfirmed identity
link is left out of answers until a person confirms it, and a source that fails gives stale data
that says so.
"""

from __future__ import annotations

import copy
import itertools
from datetime import datetime, timezone
from typing import Any

from . import format as fmt
from .api import ApiError

_MINUTE = 60_000
_HOUR = 60 * _MINUTE

APPROVAL_ID = "demo:he:action:claims-watch:pb-9c01a2b3"

# (row id, name, mechanism, lifecycle, corpus ref, summary, proof event, boundary, human policy or None)
_HUMAN = {"flavor": "human-system-authorization", "mode": "HITL", "roles": ["approver"], "reviewMode": "SINGLE",
          "required": 1, "slaTarget": "PT24H", "rationaleRequired": True, "minimumReviewSeconds": 10,
          "enforceMinimum": False, "allowedActions": ["APPROVE", "DENY", "ESCALATE", "REQUEST_MORE_INFO"]}
_CONTROLS = [
    ("custom-cl0001", "Personal data is masked before a model call", "sanitizer", "preventative",
     "iso-42001.a-7-4.sanitizer", "Email, phone, card, IBAN and IP address values are masked in text sent to a model.",
     "GuardrailDecisionRecorded", "BEFORE_LLM_CALL", None),
    ("custom-cl0002", "A model may only answer with a label the playbook allows", "guardrail", "preventative",
     "aws-agentic-scoping.scope2-human-approval", "A model's answer outside the playbook's labels is refused.",
     "GuardrailDecisionRecorded", "WORKFLOW_STEP", None),
    ("custom-cl0003", "An assistant action above the autonomy limit needs a person's approval", "human", "preventative",
     "aws-agentic-scoping.scope2-human-approval", "Medium and high risk actions wait for a person who did not request them.",
     "HumanDecisionRecorded", "WORKFLOW_STEP", _HUMAN),
    ("custom-cl0004", "Identity links that join records are confirmed by a reviewer", "human", "preventative",
     "eu-ai-act.art14.4.d", "Records of different types are joined only through links a reviewer confirmed.",
     "HumanDecisionRecorded", "STATE_TRANSITION", {**_HUMAN, "roles": ["reviewer"], "minimumReviewSeconds": 0}),
    ("custom-cl0005", "An assistant release and its stage changes are approved by its owner", "human", "preventative",
     "aicpa-soc2.cc8-1.change-authorization", "A release becomes runnable, and an assistant changes stage, only on its owner's decision.",
     "HumanDecisionRecorded", "STATE_TRANSITION", {**_HUMAN, "minimumReviewSeconds": 0}),
    ("custom-cl0006", "A connector pack goes live only on someone else's activation", "human", "preventative",
     "aicpa-soc2.cc8-1.change-authorization", "The person who imported a pack version cannot be the one who activates it.",
     "HumanDecisionRecorded", "STATE_TRANSITION", {**_HUMAN, "minimumReviewSeconds": 0}),
    ("custom-cl0007", "Actions the assistant may never take are refused by policy", "gate", "preventative",
     "aws-agentic-scoping.scope2-human-approval", "Policy refuses never-by-assistant actions, and any action from an assistant not live.",
     "ControlEvaluationRecorded", "WORKFLOW_STEP", None),
    ("custom-cl0008", "Rushed human decisions are counted", "eval", "detective", "eu-ai-act.art14.5.rubber-stamp-rate",
     "A decision submitted before the control's minimum review time is flagged below-minimum.",
     "HumanDecisionRecorded", "STATE_TRANSITION", None),
    ("custom-cl0009", "Calls to a source go only to allowed hosts", "gate", "preventative", "eu-ai-act.art15.4",
     "A source call to a host outside the egress allow list is refused before it is sent.",
     "ControlEvaluationRecorded", "BEFORE_TOOL_CALL", None),
    ("custom-cl0010", "Jailbreak-like text sent to a model is detected", "guardrail", "detective",
     "owasp-agent-control-standard.prompt-injection.content-eval",
     "Reported only, so its records are advisory and do not count as proof until it blocks.",
     "GuardrailDecisionRecorded", "BEFORE_LLM_CALL", None),
]
# control -> (status, upheld, breached, flagged)
_BASELINE = {
    "custom-cl0001": ("SATISFIED", 41, 0, 0), "custom-cl0002": ("SATISFIED", 38, 0, 0),
    "custom-cl0003": ("SATISFIED", 12, 0, 3), "custom-cl0004": ("SATISFIED", 7, 0, 0),
    "custom-cl0005": ("SATISFIED", 3, 0, 0), "custom-cl0006": ("SATISFIED", 2, 0, 0),
    "custom-cl0007": ("SATISFIED", 20, 0, 0), "custom-cl0008": ("FAILING", 9, 3, 3),
    "custom-cl0009": ("SATISFIED", 31, 0, 0), "custom-cl0010": ("NO_EVIDENCE", 0, 0, 0),
}


def _now() -> int:
    return fmt.now_ms()


def _iso(millis: int) -> str:
    return datetime.fromtimestamp(millis / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")


class DemoApi:
    label = "demo data"
    change_interval = 3  # seconds between the changes the demo feed pushes

    def __init__(self, subject: str = "you", roles: list[str] | None = None) -> None:
        self.subject = subject
        self.roles = list(roles) if roles is not None else ["operator", "reviewer", "approver"]
        now = _now()
        self._ids = itertools.count(1)
        self.stale_sources: set[str] = {"wealth"}  # sources that fail on read in this demo

        self._claims = [
            {"claimId": "CL-889", "client": "Pat Lee", "email": "pat@example.com", "status": "OPEN", "daysOpen": 41, "accountId": "W-1"},
            {"claimId": "CL-871", "client": "Sam Ortiz", "email": "sam.o@claims.example", "status": "OPEN", "daysOpen": 36, "accountId": "W-2"},
            {"claimId": "CL-860", "client": "Dana Wu", "email": "dana@example.com", "status": "OPEN", "daysOpen": 33, "accountId": "W-3"},
            {"claimId": "CL-802", "client": "Lee Chan", "email": "lee@example.com", "status": "CLOSED", "daysOpen": 12, "accountId": "W-4"},
        ]
        self._accounts = [
            {"accountId": "W-1", "owner": "Pat Lee", "email": "pat@example.com", "balance": 412000},
            {"accountId": "W-2", "owner": "Sam Ortiz", "email": "sam@wealth.example", "balance": 288500},
            {"accountId": "W-3", "owner": "Dana Wu", "email": "dana@example.com", "balance": 301200},
            {"accountId": "W-4", "owner": "Lee Chan", "email": "lee@example.com", "balance": 9000},
        ]
        # Claim -> Account links the platform trusts. Sam's is waiting for review.
        self._confirmed_links = {("CL-889", "W-1"), ("CL-860", "W-3"), ("CL-802", "W-4")}
        self._pending_links = {("CL-871", "W-2")}

        self._clusters = [
            {"rule": "name-dob", "key": "sam ortiz~1990-05-05", "confidence": 0.7, "memberCount": 2,
             "status": "PENDING_REVIEW", "members": ["claims / Claim CL-871", "wealth / Account W-2"]},
            {"rule": "name-dob", "key": "robin park~1985-11-30", "confidence": 0.7, "memberCount": 2,
             "status": "PENDING_REVIEW", "members": ["crm / Client C-77", "contact-center / Caller 5521"]},
            {"rule": "name-dob", "key": "alex kim~1972-02-14", "confidence": 0.7, "memberCount": 3,
             "status": "PENDING_REVIEW", "members": ["crm / Client C-12", "claims / Claim CL-690", "wealth / Account W-9"]},
        ]

        self._connectors = [
            self._connector("claims", "LIVE", "2.1.0", "priya.k", [("Claim", "CACHED", 15, "OK", now - 4 * _MINUTE, 4)],
                            [("addNote", "UNATTENDED"), ("attachDocument", "NEEDS_APPROVAL")]),
            self._connector("crm", "READ_ONLY", "1.4.2", "dan.m",
                            [("Client", "CACHED", 15, "OK", now - 11 * _MINUTE, 1280), ("Note", "ON_DEMAND", 0, "OK", now - 11 * _MINUTE, 5402)], []),
            self._connector("wealth", "READ_ONLY", "1.0.0", "dan.m",
                            [("Account", "ON_DEMAND", 0, "PARTIAL", now - 120 * _MINUTE, 4)], [],
                            warnings=["stopped: the declared call rate (60/min) was reached"]),
            self._connector("contact-center", "INACTIVE", "0.9.0", "priya.k", [], []),
        ]

        self._cases: dict[str, dict[str, Any]] = {}
        self._open_case("CL-889", "Pat Lee", 41, 412000, "OPEN", now - 62 * _MINUTE, with_playbook=True)
        self._open_case("CL-860", "Dana Wu", 33, 301200, "OPEN", now - 25 * 60 * _MINUTE)
        self._open_case("CL-802", "Lee Chan", 12, 9000, "NO_LONGER_MATCHING", now - 3 * 24 * 60 * _MINUTE)
        self._open_case("CL-755", "Jo Rivera", 38, 280000, "RESOLVED", now - 6 * 24 * 60 * _MINUTE)

        self._approval_case = self._case_id("CL-889")
        self._events: dict[str, dict[str, Any]] = {APPROVAL_ID: self._approval_event(now)}
        self._standing = {k: list(v) for k, v in _BASELINE.items()}
        self._waived: set[str] = set()
        self._documents: dict[str, dict[str, Any]] = {}  # trust documents by name, such as the override procedure
        # Who accepted which control. One control already has an acceptance and an assertion with a
        # document still missing, so the screen shows what falling between the claims looks like.
        self._accepted: dict[str, dict[str, Any]] = {
            "custom-cl0003": {"by": "dana", "at": _iso(now - 3 * 24 * 60 * _MINUTE), "rationale": "the system owner takes this on"}}
        self._halted: dict[str, dict[str, Any]] = {}
        self._assistants = {
            "claims-watch": {
                "assistantId": "claims-watch", "purpose": "Make sure no claim over 30 days stalls on our side",
                "owner": "dan.m", "stage": "SHADOW",
                "releases": [{"releaseId": "7f3a91c0", "approved": True, "approvedBy": "dan.m"},
                             {"releaseId": "9c01d4e8", "approved": False, "approvedBy": ""}],
                "runningReleaseId": "9c01d4e8",
            },
            "renewal-nudge": {
                "assistantId": "renewal-nudge", "purpose": "Remind advisors of policies renewing within 60 days",
                "owner": "priya.k", "stage": "DRAFT",
                "releases": [{"releaseId": "b12e07aa", "approved": False, "approvedBy": ""}], "runningReleaseId": "",
            },
        }
        self._shadow = [
            {"assistantId": "claims-watch", "actionKey": f"pb-{i:04d}", "actionType": t, "status": s,
             "reason": "", "decidedBy": "", "releaseId": "7f3a91c0"}
            for i, (t, s) in enumerate(
                [("claims.addNote", "WOULD_DO")] * 5 + [("claims.attachDocument", "WOULD_DO")] * 3
                + [("claims.attachDocument", "DENIED")])
        ]
        self._monitors = {
            "stalled-claims": {"monitorId": "stalled-claims", "name": "stalled-claims", "enabled": True,
                               "intervalMinutes": 15, "assistantId": "claims-watch", "runCount": 212,
                               "lastRunAt": now - 6 * _MINUTE, "lastRunNote": "incomplete: wealth/Account was STALE"},
            "renewals-60": {"monitorId": "renewals-60", "name": "renewals-60", "enabled": False,
                            "intervalMinutes": 1440, "assistantId": "", "runCount": 9,
                            "lastRunAt": now - 4 * 24 * 60 * _MINUTE, "lastRunNote": "complete"},
        }

    # ---- build helpers

    @staticmethod
    def _connector(name, mode, version, imported_by, entities, actions, warnings=None):
        return {
            "name": name, "mode": mode, "activeHash": f"{abs(hash(name)) % 10**8:08x}", "activeVersion": version,
            "importedBy": imported_by,
            "entities": [{"type": t, "policy": p, "ttlMinutes": ttl, "lastOutcome": o, "lastRefreshedAt": at,
                          "stored": n, "warnings": list(warnings or []) if o != "OK" else []}
                         for t, p, ttl, o, at, n in entities],
            "actions": [{"name": n, "risk": r} for n, r in actions],
        }

    @staticmethod
    def _approval_event(now: int) -> dict[str, Any]:
        """The one request waiting for a person: an assistant wants to attach a document, asked for by priya.k."""
        raised = now - 3 * _MINUTE
        return {
            "id": APPROVAL_ID, "state": "OPEN", "pending": True,
            "control": {"rowId": "custom-cl0003", "name": _CONTROLS[2][1], "corpusRef": _CONTROLS[2][4]},
            "flavor": "human-system-authorization", "mode": "HITL", "blocking": True,
            "subject": {"kind": "action", "summary": "Run claims.attachDocument for assistant claims-watch",
                        "service": "contextlayer", "agent": "claims-watch",
                        "detail": {"payload": "{\"claimId\":\"CL-889\",\"text\":\"Escalated by claims-watch: judged BLOCKED\"}"}},
            "trigger": {"detector": "workflow", "rule": "risk=NEEDS_APPROVAL", "reason": "a person must approve this action"},
            "ask": {"question": "Should assistant claims-watch be allowed to run claims.attachDocument?",
                    "checks": [{"text": "The action is what the assistant meant to do.", "section": "subject"},
                               {"text": "The payload is within what the assistant may change.", "section": "subject"}]},
            "routing": {"roles": ["approver"], "reviewMode": "SINGLE", "required": 1, "independentOf": ["priya.k"]},
            "holder": None,
            "sla": {"target": "PT24H", "dueAt": _iso(raised + 24 * _HOUR), "state": "running",
                    "timeoutBehaviour": "RESUME_WITH_DEFAULT", "default": "DENY"},
            "escalation": {"level": 0, "chain": ["approver", "admin"]},
            "allowedActions": ["APPROVE", "DENY", "ESCALATE", "REQUEST_MORE_INFO"],
            "rationaleRequired": True, "minimumReviewSeconds": 10, "claimedAt": None, "decisions": [],
            "history": [{"at": _iso(raised), "state": "OPEN", "actor": "system", "note": "routed to approver"}],
            "_actionKey": "pb-9c01a2b3", "_actionType": "claims.attachDocument",
        }

    @staticmethod
    def _case_id(claim_id: str) -> str:
        return f"demo:stalled-claims:{claim_id.lower().replace('-', '')}:r1"

    def _open_case(self, claim_id, client, days, balance, status, opened_at, with_playbook=False) -> None:
        case_id = self._case_id(claim_id)
        timeline = [{"at": opened_at, "actor": "monitor stalled-claims",
                     "text": f"opened for {claim_id}, routed to Claims Team B"}]
        if with_playbook:
            timeline += [
                {"at": opened_at + 4_000, "actor": "assistant claims-watch", "text": "started release 7f3a91c0 (SHADOW)"},
                {"at": opened_at + 9_000, "actor": "assistant claims-watch", "text": "judged impact = BLOCKED (1 field(s) shown to the model, ledger:demo-interaction-1)"},
                {"at": opened_at + 9_500, "actor": "assistant claims-watch",
                 "text": "requested claims.attachDocument: AWAITING_APPROVAL (a person must approve this action)"},
            ]
        if status == "NO_LONGER_MATCHING":
            timeline.append({"at": opened_at + 40 * _MINUTE, "actor": "monitor stalled-claims", "text": "the row no longer matches"})
        if status == "RESOLVED":
            timeline.append({"at": opened_at + 90 * _MINUTE, "actor": "priya.k", "text": "resolved: form attached by phone"})
        self._cases[case_id] = {
            "caseId": case_id, "monitorId": "stalled-claims", "monitorVersion": 3, "key": claim_id, "status": status,
            "routeTo": "Claims Team B", "assistantId": "claims-watch" if with_playbook else "",
            "evidence": {"Claim.claimId": claim_id, "Claim.daysOpen": days, "Account.balance": balance, "client": client},
            "sourceNotes": ["claims/Claim: REFRESHED_NOW", "wealth/Account: STALE"], "timeline": timeline, "openedAt": opened_at,
        }

    def _note(self, case: dict[str, Any], actor: str, text: str) -> None:
        case["timeline"].append({"at": _now(), "actor": actor, "text": text})

    # ---- Api

    async def connectors(self) -> list[dict[str, Any]]:
        return copy.deepcopy(self._connectors)

    async def refresh_entity(self, pack: str, entity_type: str) -> dict[str, Any]:
        for connector in self._connectors:
            if connector["name"] != pack:
                continue
            if connector["mode"] == "INACTIVE":
                raise ApiError(f"the connector {pack} is not active", 400)
            for entity in connector["entities"]:
                if entity["type"] == entity_type:
                    failing = pack in self.stale_sources
                    entity.update(lastOutcome="FAILED" if failing else "OK", lastRefreshedAt=_now(),
                                  warnings=["call failed: the source answered HTTP 500"] if failing else [])
                    return {"pack": pack, "entityType": entity_type, "outcome": entity["lastOutcome"],
                            "refreshedAt": entity["lastRefreshedAt"], "stored": entity["stored"],
                            "skippedWithoutId": 0, "warnings": entity["warnings"]}
        raise ApiError(f"the connector {pack} does not declare {entity_type}", 400)

    async def identity_review(self) -> list[dict[str, Any]]:
        return [{k: v for k, v in copy.deepcopy(c).items() if not k.startswith("_")}
                for c in self._clusters if c["status"] == "PENDING_REVIEW"]

    def _needs_why(self, why: str) -> None:
        if not why.strip():
            raise ApiError("a rationale is required", 422)

    def _holds(self, *roles: str) -> bool:
        return "admin" in self.roles or any(r in self.roles for r in roles)

    def _evidence(self, control: str, flagged: bool = False, breached: bool = False) -> None:
        """A decision leaves evidence: the control upheld, and the rushed-decision control counts a quick one."""
        row = self._standing[control]
        row[1] += 1
        row[3] += int(flagged)
        row[0] = "FAILING" if row[2] else "SATISFIED"
        if breached:
            rushed = self._standing["custom-cl0008"]
            rushed[2] += 1
            rushed[3] += 1
            rushed[0] = "FAILING"

    async def identity_open(self, rule: str, key: str) -> dict[str, Any]:
        if not self._holds("reviewer"):
            raise ApiError("requires permission REVIEW", 403)
        for cluster in self._clusters:
            if cluster["rule"] == rule and cluster["key"] == key and cluster["status"] == "PENDING_REVIEW":
                holder = cluster.get("_holder")
                if holder and holder != self.subject:
                    raise ApiError("claimed by another reviewer", 409)
                cluster["_holder"], cluster["_openedAt"] = self.subject, _now()
                return {"eventId": f"demo:he:identity:{key}", "minimumReviewSeconds": 5, "openedAt": cluster["_openedAt"]}
        raise ApiError("cluster is not awaiting review", 400)

    async def identity_decide(self, rule: str, key: str, same_person: bool, rationale: str) -> dict[str, Any]:
        self._needs_why(rationale)
        if not self._holds("reviewer"):
            raise ApiError("requires permission REVIEW", 403)
        for cluster in self._clusters:
            if cluster["rule"] == rule and cluster["key"] == key and cluster["status"] == "PENDING_REVIEW":
                if cluster.get("_holder") not in (None, self.subject):
                    raise ApiError("claimed by another reviewer", 409)
                rushed = (_now() - cluster.get("_openedAt", _now())) < 5000
                cluster["status"] = "CONFIRMED" if same_person else "REJECTED"
                self._evidence("custom-cl0004", flagged=rushed)
                if rushed:
                    self._evidence("custom-cl0008", flagged=True, breached=True)
                if same_person and key.startswith("sam ortiz"):
                    self._confirmed_links |= self._pending_links
                    self._pending_links = set()
                if not same_person and key.startswith("sam ortiz"):
                    self._pending_links = set()
                return {k: v for k, v in copy.deepcopy(cluster).items() if not k.startswith("_")}
        raise ApiError("cluster is not awaiting review", 400)

    async def ask(self, question: dict[str, Any]) -> dict[str, Any]:
        if question["entityType"] != "Claim":
            raise ApiError("this demo only holds Claim and Account data", 400)
        rows, left_out = [], 0
        for claim in self._claims:
            if not all(self._matches(claim, c) for c in question.get("where", [])):
                continue
            accounts = [a for a in self._accounts if (claim["claimId"], a["accountId"]) in self._confirmed_links]
            pending = [a for a in self._accounts if (claim["claimId"], a["accountId"]) in self._pending_links]
            if any(link.get("via") for link in question.get("links", [])):
                # A relationship the source declared needs no person to confirm it, unlike a match by identity.
                accounts = [a for a in self._accounts if a["accountId"] == claim["accountId"]]
                pending = []
            for link in question.get("links", []):
                accounts = [a for a in accounts if all(self._matches(a, c) for c in link.get("where", []))]
                pending = [a for a in pending if all(self._matches(a, c) for c in link.get("where", []))]
            if question.get("links"):
                if not accounts:
                    left_out += 1 if pending else 0
                    continue
            else:
                accounts = [None]
            for account in accounts:
                rows.append({f: self._field(claim, account, f) for f in question["returnFields"]})
        sources = [
            {"pack": "claims", "entityType": "Claim", "freshness": "REFRESHED_NOW", "lastOutcome": "OK",
             "dataAsOf": _now(), "warnings": []},
        ]
        if question.get("links"):
            stale = "wealth" in self.stale_sources
            sources.append({"pack": "wealth", "entityType": "Account", "freshness": "STALE" if stale else "REFRESHED_NOW",
                            "lastOutcome": "FAILED" if stale else "OK", "dataAsOf": _now() - (120 * _MINUTE if stale else 0),
                            "warnings": ["call failed: the source answered HTTP 500", "using data from the last successful read"] if stale else []})
        return {"rows": rows[: question.get("limit", 100)], "unconfirmedLeftOut": left_out, "sources": sources}

    async def draft_question(self, text: str) -> dict[str, Any]:
        """A stand-in for the model: it understands one kind of request, and asks about anything else."""
        words = text.lower()
        if "claim" not in words:
            return {"question": None, "explanation": "", "problems": [],
                    "clarification": "This sample only holds claims and accounts. Which claims do you mean?"}
        where = [{"field": "status", "op": "EQ", "value": "OPEN"}] if "open" in words or "stalled" in words else []
        where.append({"field": "daysOpen", "op": "GT", "value": 30})
        links = [{"entityType": "Account", "where": [{"field": "balance", "op": "GT", "value": 250000}]}]             if any(w in words for w in ("wealth", "client", "account", "250")) else []
        if links and "account" in words:  # the source declares which account a claim is on, so follow that
            links[0].update(via="HAS_ACCOUNT", direction="OUT", depth=1)
        returns = ["Claim.claimId", "Claim.daysOpen"] + (["Account.balance"] if links else [])
        return {"question": {"entityType": "Claim", "where": where, "links": links, "returnFields": returns, "limit": 100},
                "explanation": "Open claims over 30 days" + (" whose " + ("account" if "account" in words else "client") + " holds more than 250,000." if links else "."),
                "clarification": "", "problems": []}

    @staticmethod
    def _matches(row: dict[str, Any], c: dict[str, Any]) -> bool:
        value = row.get(c["field"])
        want = c["value"]
        try:
            return {"EQ": value == want, "NEQ": value != want, "GT": value > want, "GTE": value >= want,
                    "LT": value < want, "LTE": value <= want}[c["op"]]
        except TypeError:
            return False

    @staticmethod
    def _field(claim: dict[str, Any], account: dict[str, Any] | None, reference: str) -> Any:
        kind, name = reference.split(".", 1)
        source = claim if kind == "Claim" else account
        return None if source is None else source.get(name)

    async def cases(self, status: str | None = None) -> list[dict[str, Any]]:
        items = [c for c in self._cases.values() if status is None or c["status"] == status]
        items.sort(key=lambda c: -c["openedAt"])
        return [{"caseId": c["caseId"], "monitorId": c["monitorId"], "key": c["key"], "status": c["status"],
                 "routeTo": c["routeTo"], "openedAt": c["openedAt"]} for c in items]

    async def case(self, case_id: str) -> dict[str, Any]:
        if case_id not in self._cases:
            raise ApiError("not found", 404)
        return copy.deepcopy(self._cases[case_id])

    def _closeable(self, case_id: str) -> dict[str, Any]:
        case = self._cases.get(case_id)
        if case is None:
            raise ApiError("not found", 404)
        if case["status"] in ("RESOLVED", "DISMISSED"):
            raise ApiError(f"case is already {case['status']}", 400)
        return case

    async def case_resolve(self, case_id: str, note: str) -> dict[str, Any]:
        case = self._closeable(case_id)
        case["status"] = "RESOLVED"
        self._note(case, "you", f"resolved: {note}")
        return copy.deepcopy(case)

    async def case_dismiss(self, case_id: str, note: str) -> dict[str, Any]:
        case = self._closeable(case_id)
        case["status"] = "DISMISSED"
        self._note(case, "you", f"dismissed: {note}")
        return copy.deepcopy(case)

    async def case_annotate(self, case_id: str, text: str) -> dict[str, Any]:
        if not text.strip():
            raise ApiError("a note is required", 400)
        case = self._closeable(case_id)
        self._note(case, "you", text)
        return copy.deepcopy(case)

    # ---- things waiting for a person: the same rules the service applies

    def _event(self, event_id: str) -> dict[str, Any]:
        event = self._events.get(event_id)
        if event is None:
            raise ApiError("event not found", 404)
        return event

    @staticmethod
    def _shown(event: dict[str, Any]) -> dict[str, Any]:
        return {k: copy.deepcopy(v) for k, v in event.items() if not k.startswith("_")}

    def _step(self, event: dict[str, Any], note: str, state: str | None = None) -> None:
        if state:
            event["state"] = state
            event["pending"] = state in ("OPEN", "CLAIMED", "ESCALATED", "CHANGES_REQUESTED", "AWAITING_SECOND")
        event["history"].append({"at": _iso(_now()), "state": event["state"], "actor": self.subject, "note": note})

    def _may_act(self, event: dict[str, Any]) -> None:
        if not self._holds(*event["routing"]["roles"]):
            raise ApiError(f"requires one of the roles {event['routing']['roles']}", 403)
        if not event["pending"]:
            raise ApiError(f"the event is {event['state']}, not pending", 409)

    async def human_events(self) -> list[dict[str, Any]]:
        return [self._shown(e) for e in self._events.values() if e["pending"]]

    async def human_claim(self, event_id: str) -> dict[str, Any]:
        event = self._event(event_id)
        self._may_act(event)
        holder = (event["holder"] or {}).get("subject")
        if holder and holder != self.subject:
            raise ApiError("claimed by another reviewer", 409)
        now = _now()
        event["holder"] = {"subject": self.subject, "roles": self.roles, "since": _iso(now)}
        event["claimedAt"] = _iso(now)
        self._step(event, "claimed", "CLAIMED")
        return self._shown(event)

    async def human_release(self, event_id: str) -> dict[str, Any]:
        event = self._event(event_id)
        if (event["holder"] or {}).get("subject") != self.subject:
            raise ApiError("the event is not claimed by you", 409)
        event["holder"] = None
        self._step(event, "released", "ESCALATED" if event["escalation"]["level"] else "OPEN")
        return self._shown(event)

    async def halts(self) -> list[dict[str, Any]]:
        return [{"scope": "assistant", "target": a, "halted": a in self._halted, "since": None,
                 "by": self._halted.get(a, {}).get("by"), "eventId": None, "safeStateChecks": []}
                for a in sorted(self._assistants)]

    async def halt(self, assistant_id: str, lift: bool, reason: str) -> dict[str, Any]:
        if not self._holds("reviewer"):
            raise ApiError("requires permission REVIEW", 403)
        if assistant_id not in self._assistants:
            raise ApiError("assistant not found", 404)
        if not reason.strip():
            raise ApiError("a reason is required", 422)
        if lift and assistant_id not in self._halted:
            raise ApiError("the assistant is not halted", 409)
        if not lift and assistant_id in self._halted:
            raise ApiError("the assistant is already halted", 409)
        verb = "Restart" if lift else "Halt"
        now = _now()
        event = {
            "id": f"demo:he:halt:{assistant_id}:{now}", "state": "OPEN", "pending": True,
            "control": {"rowId": "custom-cl0012" if lift else "custom-cl0011", "name": _CONTROLS[0][1]},
            "flavor": "human-halt", "mode": "HALT", "blocking": True,
            "subject": {"kind": "system", "summary": f"{verb} assistant {assistant_id}", "service": "contextlayer",
                        "agent": assistant_id},
            "trigger": {"detector": "person", "rule": verb.lower() + " requested", "reason": reason},
            "ask": {"question": f"Should assistant {assistant_id} be {'allowed to act again' if lift else 'stopped'}?",
                    "checks": [{"text": "The cause of the halt is understood." if lift else "Stopping it now is better than letting it continue.",
                                "section": "subject"}]},
            "routing": {"roles": ["approver"], "reviewMode": "TWO_PERSON" if lift else "SINGLE",
                        "required": 2 if lift else 1, "independentOf": []},
            "holder": None,
            "sla": {"target": "PT1H", "dueAt": _iso(now + _HOUR), "state": "running",
                    "timeoutBehaviour": "RAISE_TIMEOUT", "default": None},
            "escalation": {"level": 0, "chain": ["approver", "admin"]},
            "allowedActions": ["APPROVE", "DENY", "ESCALATE"], "rationaleRequired": True, "minimumReviewSeconds": 0,
            "claimedAt": None, "decisions": [],
            "history": [{"at": _iso(now), "state": "OPEN", "actor": self.subject, "note": f"{verb.lower()} requested"}],
            "_halt": (assistant_id, lift),
        }
        self._events[event["id"]] = event
        return self._shown(event)

    def _decide_halt(self, event: dict[str, Any], action: str, rationale: str) -> dict[str, Any]:
        self._needs_why(rationale)
        if any(d["reviewer"]["subject"] == self.subject for d in event["decisions"]):
            raise ApiError("you have already voted on this event", 409)
        event["decisions"].append({"reviewer": {"subject": self.subject, "roles": self.roles}, "action": action,
                                   "rationale": rationale, "reviewSeconds": 0, "belowMinimum": False})
        assistant, lift = event["_halt"]
        if action == "DENY":
            self._step(event, f"DENY by {self.subject}", "APPLIED")
        elif len(event["decisions"]) < event["routing"]["required"]:
            self._step(event, f"APPROVE by {self.subject}; needs a second person", "AWAITING_SECOND")
        else:
            self._step(event, f"APPROVE by {self.subject}", "APPLIED")
            if lift:
                self._halted.pop(assistant, None)
            else:
                self._halted[assistant] = {"by": self.subject}
        return self._shown(event)

    async def human_decide(self, event_id: str, action: str, rationale: str) -> dict[str, Any]:
        event = self._event(event_id)
        self._may_act(event)
        if "_halt" in event:
            return self._decide_halt(event, action, rationale)
        if action not in event["allowedActions"]:
            raise ApiError(f"this control does not allow {action}", 422)
        if self.subject in event["routing"]["independentOf"]:
            raise ApiError("the person who proposed this cannot decide it", 403)
        if (event["holder"] or {}).get("subject") != self.subject:
            raise ApiError("claim the event first", 409)
        self._needs_why(rationale)
        claimed = datetime.fromisoformat(event["claimedAt"].replace("Z", "+00:00")).timestamp() * 1000
        seconds = max(0, int((_now() - claimed) // 1000))
        rushed = seconds < event["minimumReviewSeconds"]
        event["decisions"].append({"reviewer": {"subject": self.subject, "roles": self.roles}, "action": action,
                                   "rationale": rationale, "reviewSeconds": seconds, "belowMinimum": rushed})
        self._step(event, f"{action} by {self.subject}" + (" (below the minimum review time)" if rushed else ""), "APPLIED")
        self._evidence("custom-cl0003", flagged=rushed)
        if rushed:
            self._evidence("custom-cl0008", flagged=True, breached=True)
        case = self._cases.get(self._approval_case)
        if case is not None:
            who = "you"
            name = f"assistant {event['subject']['agent']}"
            if action == "APPROVE":
                self._note(case, name, f"{event['_actionType']} approved by {who}")
                self._note(case, name, f"{event['_actionType']} succeeded")
            else:
                self._note(case, name, f"{event['_actionType']} rejected by {who}: {rationale}")
        return self._shown(event)

    async def human_escalate(self, event_id: str, reason: str) -> dict[str, Any]:
        event = self._event(event_id)
        self._may_act(event)
        if not reason.strip():
            raise ApiError("a reason is required", 422)
        chain = event["escalation"]["chain"]
        if event["escalation"]["level"] + 1 >= len(chain):
            raise ApiError("the event is already with the last level", 422)
        event["escalation"]["level"] += 1
        event["routing"]["roles"] = [chain[event["escalation"]["level"]]]
        event["holder"] = None
        self._step(event, f"escalated to {chain[event['escalation']['level']]}: {reason}", "ESCALATED")
        return self._shown(event)

    async def human_send_back(self, event_id: str, requested: str) -> dict[str, Any]:
        event = self._event(event_id)
        self._may_act(event)
        if not requested.strip():
            raise ApiError("say what changes are requested", 422)
        event["holder"] = None
        self._step(event, f"sent back: {requested}", "CHANGES_REQUESTED")
        return self._shown(event)

    async def human_withdraw(self, event_id: str, reason: str) -> dict[str, Any]:
        event = self._event(event_id)
        if self.subject not in event["routing"]["independentOf"] and "admin" not in self.roles:
            raise ApiError("only the proposer can withdraw", 403)
        self._step(event, f"withdrawn: {reason}", "WITHDRAWN")
        return self._shown(event)

    async def assistants(self) -> list[dict[str, Any]]:
        return [{"assistantId": a["assistantId"], "purpose": a["purpose"], "owner": a["owner"], "stage": a["stage"],
                 "releaseCount": len(a["releases"]), "approvedCount": sum(r["approved"] for r in a["releases"])}
                for a in self._assistants.values()]

    async def assistant(self, assistant_id: str) -> dict[str, Any]:
        if assistant_id not in self._assistants:
            raise ApiError("not found", 404)
        return copy.deepcopy(self._assistants[assistant_id])

    async def assistant_actions(self, assistant_id: str) -> list[dict[str, Any]]:
        return [copy.deepcopy(a) for a in self._shadow if a["assistantId"] == assistant_id]

    async def assistant_approve_release(self, assistant_id: str, release_id: str, rationale: str) -> dict[str, Any]:
        self._needs_why(rationale)
        assistant = self._assistants[assistant_id]
        for release in assistant["releases"]:
            if release["releaseId"] == release_id:
                release["approved"], release["approvedBy"] = True, "you"
        self._evidence("custom-cl0005")
        return copy.deepcopy(assistant)

    async def assistant_set_stage(self, assistant_id: str, stage: str, rationale: str) -> dict[str, Any]:
        self._needs_why(rationale)
        assistant = self._assistants[assistant_id]
        approved = [r for r in assistant["releases"] if r["approved"]]
        if stage == "SHADOW" and not assistant["releases"]:
            raise ApiError("shadow mode needs at least one release", 400)
        if stage == "LIVE" and not approved:
            raise ApiError("live mode needs an approved release", 400)
        assistant["stage"] = stage
        return copy.deepcopy(assistant)

    async def monitors(self) -> list[dict[str, Any]]:
        return copy.deepcopy(list(self._monitors.values()))

    async def monitor_run(self, monitor_id: str) -> dict[str, Any]:
        monitor = self._monitors[monitor_id]
        if not monitor["enabled"]:
            return {"runId": f"demo:{monitor_id}", "status": "SKIPPED"}
        monitor["runCount"] += 1
        monitor["lastRunAt"] = _now()
        monitor["lastRunNote"] = "complete"
        return {"runId": f"demo:{monitor_id}:t{_now()}", "status": "STARTED"}

    async def monitor_set_enabled(self, monitor_id: str, enabled: bool) -> dict[str, Any]:
        self._monitors[monitor_id]["enabled"] = enabled
        return copy.deepcopy(self._monitors[monitor_id])

    async def create_monitor(self, monitor_id: str, body: dict[str, Any]) -> dict[str, Any]:
        if monitor_id in self._monitors:
            raise ApiError("monitor already exists", 400)
        if body["keyField"] not in body["question"]["returnFields"]:
            raise ApiError("keyField must be one of the question's return fields", 400)
        self._monitors[monitor_id] = {
            "monitorId": monitor_id, "name": body["name"], "enabled": True, "intervalMinutes": body["intervalMinutes"],
            "assistantId": body.get("assistantId", ""), "runCount": 0, "lastRunAt": 0, "lastRunNote": ""}
        return copy.deepcopy(self._monitors[monitor_id])

    # ---- governance

    async def controls(self) -> dict[str, Any]:
        return {"systemId": "contextlayer-akka", "digest": "sha256:" + "ab12" * 16,
                "controls": [{"rowId": r, "name": n, "mechanism": m, "lifecycle": lc, "corpusRef": ref, "summary": s,
                              "proofEvent": pe, "boundary": b, "human": h}
                             for r, n, m, lc, ref, s, pe, b, h in _CONTROLS]}

    async def accept_control(self, control_id: str, rationale: str) -> dict[str, Any]:
        if not self._holds("approver"):
            raise ApiError("requires permission APPROVE", 403)
        if control_id not in self._standing:
            raise ApiError("not found", 404)
        self._needs_why(rationale)
        self._accepted[control_id] = {"by": self.subject, "at": _iso(_now()), "rationale": rationale}
        return self._acceptance(control_id)

    def _acceptance(self, control_id: str) -> dict[str, Any]:
        a = self._accepted.get(control_id)
        if a is None:
            return {"accepted": False, "current": False, "by": None, "authority": None, "at": None,
                    "catalogDigest": None, "rationale": None}
        return {"accepted": True, "current": True, "by": a["by"], "authority": "idp=demo;roles=approver",
                "at": a["at"], "catalogDigest": "sha256:" + "ab12" * 16, "rationale": a["rationale"]}

    async def documents(self) -> list[dict[str, Any]]:
        return [{"name": n, "kind": d["kind"], "ref": d["ref"], "asOf": d["asOf"], "digest": None, "by": d["by"], "versions": 1}
                for n, d in sorted(self._documents.items())]

    async def register_document(self, name: str, kind: str, ref: str, as_of: str) -> dict[str, Any]:
        if "admin" not in self.roles:
            raise ApiError("requires permission ADMINISTER", 403)
        self._needs_why(ref)
        self._documents[name] = {"kind": kind, "ref": ref, "asOf": as_of, "by": self.subject}
        return {"version": 1, "name": name, "kind": kind, "ref": ref, "asOf": as_of}

    async def audit(self, question: str) -> dict[str, Any]:
        """A stand-in for the two models: fixed questions chosen by keyword, with facts that come from this sample's own data."""
        words = question.lower()
        supported = ["CONTROL_STATUS: where one control stands", "GAPS: which controls fall short", "INTEGRITY: whether evidence can be altered"]
        if any(w in words for w in ("tamper", "alter", "integrity", "change the evidence")):
            facts = [{"text": "The evidence ledger is HashChained: each record carries a digest chained to the one before it.", "refs": ["store:evidence-ledger"]},
                     {"text": "The human events, the control claims and the entity graph are NoIntegrity stores: they have no digest. Each decision and each claim is copied into the evidence ledger.",
                      "refs": ["store:human-events", "store:control-claims", "store:entity-graph"]},
                     {"text": "The evidence ledger holds 42 records. A check of 42 of them, the most recent, found none altered.", "refs": ["store:evidence-ledger"]}]
            limits = ["Someone who can rewrite the whole ledger and its head together would not be detected, unless the head was kept somewhere they cannot write."]
            summary = ("The evidence ledger is chained, so an altered record would be found. The other stores have no digest, though each decision is copied into the ledger. "
                       "A check of 42 records found none altered. The records do not show whether someone could rewrite the ledger and its head together.")
            return self._answer("INTEGRITY", "", facts, limits, summary)
        if any(w in words for w in ("gap", "missing", "short")):
            compliance = await self.compliance()
            g = compliance["gaps"]
            facts = [{"text": f"{len(g[k])} control(s) are {what}: {', '.join(g[k])}.", "refs": [f"control:{i}" for i in g[k]]}
                     for k, what in (("unaccepted", "not accepted for the project"), ("acceptedWithoutAssertion", "accepted but with nothing asserted about their evidence"),
                                     ("coveragePartial", "asserted but with expected documents missing or too old")) if g[k]]
            summary = "; ".join(f["text"].rstrip(".") for f in facts) + "."
            return self._answer("GAPS", "", facts, ["These checks look at the claims and at enforcing records. They do not judge whether a control is the right one."], summary)
        if any(w in words for w in ("override", "approve", "approval", "sign", "assistant action")):
            c = next(x for x in (await self.compliance())["controls"] if x["controlId"] == "custom-cl0003")
            doc = self._documents.get("override procedure")
            facts = [{"text": "Control custom-cl0003 (An assistant action above the autonomy limit needs a person's approval) is a human control that acts at WORKFLOW_STEP and is proved by HumanDecisionRecorded records.", "refs": ["control:custom-cl0003"]},
                     {"text": f"Its standing is {c['status']}: {c['why']}. In the last 90 days it ran {c['enforcing']} time(s) as an enforcing control: {c['upheld']} upheld, {c['breached']} breached, {c['flagged']} flagged.", "refs": ["control:custom-cl0003"]},
                     {"text": "It was accepted by dana. The reason given: the system owner takes this on", "refs": ["control:custom-cl0003", "person:dana"]},
                     {"text": "Revision 1 of the assertion about its evidence was made by erin: HumanDecisionRecorded records count as evidence. The reason given: human reviews are the evidence", "refs": ["control:custom-cl0003", "person:erin"]},
                     {"text": "Coverage of that assertion is " + ("FULL" if doc else "PARTIAL") + ".", "refs": ["control:custom-cl0003"]}]
            limits = [] if doc else ["Still needed: override procedure: none attached."]
            summary = (f"Control custom-cl0003 is {c['status']}. Dana accepted it and Erin said human reviews are the evidence. "
                       + ("The override procedure is attached, so the evidence is fully covered." if doc else "The override procedure is still needed, so the evidence is only partly covered."))
            return self._answer("CONTROL_STATUS", "custom-cl0003", facts, limits, summary)
        return {"intent": "", "controlId": "", "periodDays": 0, "facts": [], "limits": [], "summary": "", "summaryChecked": False, "unsupported": [],
                "clarification": "I can answer the questions listed. Which of them do you mean?", "supported": supported}

    @staticmethod
    def _answer(intent: str, control: str, facts: list, limits: list, summary: str) -> dict[str, Any]:
        return {"intent": intent, "controlId": control, "periodDays": 90, "facts": facts, "limits": limits, "summary": summary,
                "summaryChecked": True, "unsupported": [], "clarification": "", "supported": []}

    async def integrity(self) -> dict[str, Any]:
        return {"scheme": "HashChained", "count": 42, "head": "sha256:" + "c0de" * 16, "from": 1, "checked": 42,
                "intact": True, "problems": [], "next": None}

    async def interaction(self, interaction_id: str) -> dict[str, Any]:
        if interaction_id != "demo-interaction-1":
            raise ApiError("not found", 404)
        return {"interactionId": interaction_id, "agent": "classifier-agent", "at": "2026-10-20T09:00:09Z",
                "systemMessage": "You label business records. Is the claim blocked by something only the customer can provide?\n\n"
                                 "Allowed labels: BLOCKED, MOVING. Choose exactly one.",
                "input": "notes: waiting on the customer's signed form; customer ******************* has not replied",
                "reply": "{\"label\":\"BLOCKED\",\"reason\":\"The claim waits on a form only the customer can sign.\"}",
                "inputTokens": 212, "outputTokens": 24, "failure": "",
                "verdicts": [{"evaluator": "label-allowed-evaluator", "controlId": "custom-cl0002", "outcome": "VERDICT",
                              "passed": True, "label": "label-allowed", "explanation": "answered BLOCKED, one of BLOCKED, MOVING",
                              "at": "2026-10-20T09:00:10Z"}]}

    async def record_stores(self) -> list[dict[str, Any]]:
        return [{"id": "evidence-ledger", "holds": "Evidence that controls ran", "scheme": "HashChained",
                 "note": "Chained; check it at /governance/integrity.", "records": 42, "head": "sha256:" + "c0de" * 16},
                {"id": "human-events", "holds": "Human reviews and their decisions", "scheme": "NoIntegrity",
                 "note": "No digest. Each decision is copied to the chained ledger.", "records": None, "head": None}]

    async def domains(self) -> dict[str, Any]:
        return {"governed": True, "domains": [
            {"name": "claims", "description": "Claims handling", "entityTypes": ["Claim"], "readerRoles": ["operator", "approver"],
             "readerPeople": [], "changedBy": "root", "changedAt": 0},
            {"name": "wealth", "description": "Client wealth", "entityTypes": ["Account"], "readerRoles": ["approver"],
             "readerPeople": ["carol"], "changedBy": "root", "changedAt": 0}], "joins": []}

    async def changes(self):  # noqa: ANN201 - an async generator
        import asyncio
        events = [("upsert", "Claim", "CL-889"), ("upsert", "Claim", "CL-871"), ("delete", "Claim", "CL-640"),
                  ("upsert", "Account", "W-17")]
        n = 0
        while True:
            await asyncio.sleep(self.change_interval)
            op, kind, entity_id = events[n % len(events)]
            n += 1
            yield {"pack": "claims-feed", "entityType": kind, "entityId": entity_id, "op": op, "at": _now()}

    async def compliance(self) -> dict[str, Any]:
        now = _now()
        controls = []
        for row_id, name, mechanism, *_ in _CONTROLS:
            status, upheld, breached, flagged = self._standing[row_id]
            waived = row_id in self._waived
            controls.append({
                "controlId": row_id, "name": name, "mechanism": mechanism,
                "status": "WAIVED" if waived else status,
                "why": "waived by an admin" if waived else {"SATISFIED": "upheld", "NO_EVIDENCE": "no evidence yet",
                                                            "FAILING": f"{breached} breach or error in the last 7 days"}[status],
                "enforcing": upheld + breached, "upheld": upheld, "breached": breached, "flagged": flagged,
                "lastEnforcingAt": _iso(now - 20 * _MINUTE) if upheld else None,
                "acceptance": self._acceptance(row_id), "assertion": self._assertion(row_id),
                "coverage": self._coverage(row_id), "contract": "SIGNED"})
        gaps = {
            "unaccepted": [c["controlId"] for c in controls if not c["acceptance"]["accepted"]],
            "acceptedWithoutAssertion": [c["controlId"] for c in controls
                                         if c["acceptance"]["accepted"] and not c["assertion"]["asserted"]],
            "assertedWithoutEvidence": [c["controlId"] for c in controls if c["assertion"]["asserted"] and c["enforcing"] == 0],
            "coveragePartial": [c["controlId"] for c in controls
                                if c["assertion"]["asserted"] and c["coverage"]["coverage"] != "FULL"],
            "notAsSigned": []}
        contract = {"present": True, "intact": True, "problems": [], "digest": "sha256:" + "5a9e" * 16, "revision": 1,
                    "systemId": "contextlayer-akka", "corpusVersion": "2026-09", "createdAt": _iso(now - 3 * 24 * 60 * _MINUTE),
                    "signOffRole": "risk-team", "signedOffBy": "CN=contextlayer-risk-team,OU=risk-team,O=contextlayer",
                    "changed": [], "unsigned": [], "notRunning": []}
        return {"catalogDigest": "sha256:" + "ab12" * 16, "asOf": _iso(now), "freshnessDays": 7, "controls": controls,
                "gaps": gaps, "contract": contract}

    def _assertion(self, control_id: str) -> dict[str, Any]:
        if control_id != "custom-cl0003":
            return {"asserted": False, "revision": 0, "evidenceKind": None, "by": None, "authority": None, "at": None,
                    "basisDigest": None, "rationale": None, "expects": []}
        return {"asserted": True, "revision": 1, "evidenceKind": "HumanDecisionRecorded", "by": "erin",
                "authority": "idp=demo;roles=admin", "at": _iso(_now() - 2 * 24 * 60 * _MINUTE),
                "basisDigest": "sha256:" + "ab12" * 16, "rationale": "human reviews are the evidence",
                "expects": [{"name": "override procedure", "maxAgeDays": 365}]}

    def _coverage(self, control_id: str) -> dict[str, Any]:
        if control_id != "custom-cl0003":
            return {"coverage": "NONE", "unmet": ["no assertion has been made about what counts as evidence"]}
        if "override procedure" in self._documents:
            return {"coverage": "FULL", "unmet": []}
        return {"coverage": "PARTIAL", "unmet": ["override procedure: none attached"]}

    async def waive(self, control_id: str, reason: str) -> dict[str, Any]:
        if "admin" not in self.roles:
            raise ApiError("requires permission ADMINISTER", 403)
        if control_id not in self._standing:
            raise ApiError("not found", 404)
        self._needs_why(reason)
        self._waived.add(control_id)
        return {"controlId": control_id, "waived": True, "reason": reason, "by": self.subject, "until": None}

    async def lift_waiver(self, control_id: str) -> dict[str, Any]:
        if "admin" not in self.roles:
            raise ApiError("requires permission ADMINISTER", 403)
        if control_id not in self._waived:
            raise ApiError("there is no waiver to lift", 400)
        self._waived.discard(control_id)
        return {"controlId": control_id, "waived": False, "reason": "", "by": self.subject, "until": None}
