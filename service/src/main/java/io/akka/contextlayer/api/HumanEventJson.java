package io.akka.contextlayer.api;

import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.HumanDecision;
import io.akka.contextlayer.governance.HumanEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link HumanEvent} in the shape of the human events contract. Built as maps so the contract's
 * field names, and its nulls, are exactly as written there and the internal state can change without
 * moving the wire format. Times are ISO-8601.
 */
final class HumanEventJson {
  private HumanEventJson() {}

  static Map<String, Object> of(HumanEvent e, long now) {
    var control = ControlCatalog.current().find(e.controlId());
    var policy = e.policy();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", e.id());
    out.put("state", e.effectiveState(now).name());
    out.put("pending", e.pending());
    out.put("shadow", false);
    out.put("strength", "prevent");
    out.put("control", map("rowId", e.controlId(), "name", control.map(c -> c.name()).orElse(e.controlId()),
        "corpusRef", control.map(c -> c.corpusRef()).orElse(null), "revision", e.catalogDigest()));
    out.put("flavor", policy.flavor());
    out.put("mode", policy.mode());
    out.put("blocking", policy.blocking());
    out.put("subject", map("kind", e.subjectKind(), "summary", e.summary(), "service", "contextlayer",
        "agent", e.assistantId(), "component", null, "sessionId", null, "flowId", null, "traceId", null,
        "workflowId", null, "detail", shownDetail(e.detail())));
    out.put("trigger", map("detector", e.detector(), "rule", e.rule(), "reason", e.reason(), "values", List.of(),
        "conditions", List.of(), "detectedAt", iso(e.raisedAt()), "occurrences", 1));
    var checks = new ArrayList<Map<String, Object>>();
    for (var check : e.checks()) checks.add(map("text", check, "section", "subject"));
    out.put("ask", map("question", e.question(), "checks", checks));
    out.put("environment", "local");
    out.put("owner", map("kind", "service", "service", "contextlayer"));
    out.put("routing", map("queue", e.currentRoles().isEmpty() ? "" : e.currentRoles().get(0), "roles", e.currentRoles(),
        "groups", List.of(), "attestations", List.of(), "reviewMode", policy.reviewMode(), "required", policy.required(),
        "independentOf", policy.independence().contains("proposer") && !e.proposedBySystem()
            ? List.of(e.proposedBy()) : List.of()));
    out.put("accountableOwner", policy.accountableOwner());
    out.put("holder", e.holder().isBlank() || e.effectiveState(now) != e.state() ? null
        : map("subject", e.holder(), "roles", e.holderRoles(), "since", iso(e.claimedAt()),
            "expiresAt", iso(e.claimExpiresAt())));
    out.put("sla", map("target", policy.slaTarget(), "startedAt", iso(e.raisedAt()), "dueAt", iso(e.dueAt()),
        "state", e.slaState(now), "timeoutBehaviour", policy.timeoutBehaviour(), "default", policy.timeoutDefault()));
    out.put("escalation", map("level", e.level(), "chain", policy.escalationChain()));
    out.put("allowedActions", policy.allowedActions());
    out.put("effects", Map.of());
    out.put("sticky", List.of());
    out.put("rationaleRequired", policy.rationaleRequired());
    out.put("minimumReviewSeconds", policy.minimumReviewSeconds());
    out.put("slaExtensions", e.history().stream().filter(step -> step.note().startsWith("deadline extended"))
        .map(step -> map("by", step.actor(), "at", step.at() == 0 ? null : iso(step.at()), "note", step.note())).toList());
    out.put("aiRecommendation", null);
    out.put("context", map("intendedUse", control.map(c -> c.summary()).orElse(null), "limitations", List.of()));
    out.put("presentedAt", iso(e.claimedAt()));
    out.put("claimedAt", iso(e.claimedAt()));
    out.put("decisions", e.decisions().stream().map(HumanEventJson::decision).toList());
    out.put("outcome", e.pending() ? null : map("action", e.outcomeAction(), "sticky", null, "agreedWithAi", null,
        "conditions", List.of(), "standingDecisionId", null, "slaOutcome", e.slaOutcome(), "appliedAt", null,
        "applied", e.applied().isBlank() ? null : e.applied(), "applyError", e.applyError().isBlank() ? null : e.applyError()));
    out.put("verdictId", null);
    out.put("conclusion", null);
    out.put("related", List.of());
    out.put("history", e.history().stream()
        .map(s -> map("at", iso(s.at()), "state", s.state(), "actor", s.actor(), "note", s.note())).toList());
    out.put("evidence", List.of(map("kind", "evidence-log", "ref", e.id())));
    out.put("simulated", false);
    return out;
  }

  /** Detail keys starting with an underscore are for the service's own use and are never shown. */
  private static Map<String, String> shownDetail(Map<String, String> detail) {
    var shown = new LinkedHashMap<String, String>();
    detail.forEach((k, v) -> {
      if (!k.startsWith("_")) shown.put(k, v);
    });
    return shown;
  }

  static Map<String, Object> decision(HumanDecision d) {
    return map("reviewer", map("subject", d.reviewer().subject(), "roles", d.reviewer().roles(),
        "identityProvider", d.reviewer().identityProvider(), "rehearsal", d.reviewer().rehearsal()),
        "action", d.action(), "rationale", d.rationale(), "qualified", true, "independent", d.independent(),
        "edits", null, "sticky", null, "presentedAt", iso(d.presentedAt()), "submittedAt", iso(d.submittedAt()),
        "reviewSeconds", d.reviewSeconds(), "belowMinimum", d.belowMinimum(), "checks", d.checks(),
        "authority", d.authority());
  }

  private static String iso(long millis) {
    return millis <= 0 ? null : Instant.ofEpochMilli(millis).toString();
  }

  /** A map that keeps its order and, unlike Map.of, allows the nulls the contract uses. */
  private static Map<String, Object> map(Object... keysAndValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keysAndValues.length; i += 2) out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    return out;
  }
}
