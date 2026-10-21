package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.HumanEvent;
import io.akka.contextlayer.governance.HumanEvents;
import java.util.List;
import java.util.Map;

/**
 * The human event behind an action whose outcome is unknown: did it take effect? A reviewer who did not request the
 * action checks the target system and answers APPROVE (it happened) or DENY (it did not); the ledger records the
 * answer when the decision is applied. The service never retries the action.
 */
public final class UnknownOutcomes {
  private UnknownOutcomes() {}

  /** Marks the event in its detail, beside the action approval that shares its subject kind. */
  public static final String OPERATION = "resolve-unknown";

  public static String eventId(String orgId, String assistantId, String actionKey) {
    return ScopedId.of(orgId, "he", "unknown", assistantId, actionKey);
  }

  public static boolean isOne(HumanEvent event) {
    return "action".equals(event.subjectKind()) && OPERATION.equals(event.detail().get("operation"));
  }

  /** Raises the question. Raising it again leaves the first, so a retried step is safe. */
  public static String raise(
      ComponentClient client, TimerScheduler timers, String orgId, String assistantId, String actionKey, String actionType,
      String requestedBy, String approvedBy) {
    var id = eventId(orgId, assistantId, actionKey);
    var raised = HumanEvents.raised(ControlCatalog.current(), Controls.UNKNOWN_OUTCOME_RESOLUTION, id, orgId,
        "action", "Did " + actionType + " of playbook " + assistantId + " take effect?", assistantId,
        Map.of("actionKey", actionKey, "actionType", actionType, "operation", OPERATION,
            "approvedBy", approvedBy == null ? "" : approvedBy),
        "workflow", "outcome-unknown", "the call did not complete, so whether the target system applied it is unknown",
        "Check the target system: did " + actionType + " (" + actionKey + ") take effect? Approve if it did, deny if not.",
        List.of("I looked at the target system itself, not at this service's records."),
        requestedBy == null || requestedBy.isBlank() ? "system:actions" : requestedBy, System.currentTimeMillis());
    var answer = client.forEventSourcedEntity(id).method(HumanEventEntity::raise).invoke(raised);
    if (answer.event().raisedAt() == raised.at()) HumanEventTimedAction.schedule(timers, client, id, raised.dueAt());
    return id;
  }
}
