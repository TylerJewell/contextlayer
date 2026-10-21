package io.akka.contextlayer.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.contextlayer.application.HumanEventEntity;
import io.akka.contextlayer.application.HumanEventNotifier;
import io.akka.contextlayer.application.Verdicts;
import io.akka.contextlayer.auth.Caller;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.HumanEvents;
import io.akka.contextlayer.governance.Refusal;
import io.akka.contextlayer.governance.Reviewer;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A person's decision on a change that happens at once (a release, a stage, a pack going live, an
 * identity link) rather than waiting in a queue. The decision is recorded as a human event first,
 * under the caller's verified authority and with the control's rules (rationale, independence)
 * applied; only then is the change made, and the event records whether it was.
 */
final class GateDecisions {
  private GateDecisions() {}

  /** Raises and decides the event; throws the HTTP error the control's refusal maps to. */
  static String decide(
      ComponentClient client,
      Caller caller,
      String orgId,
      String controlId,
      String eventId,
      String subjectKind,
      String summary,
      String assistantId,
      Map<String, String> detail,
      String question,
      String proposedBy,
      String action,
      String rationale) {
    long now = System.currentTimeMillis();
    var raised = HumanEvents.raised(ControlCatalog.current(), controlId, eventId, orgId, subjectKind, summary,
        assistantId, immediate(detail), "change-request", "decision on " + subjectKind, summary, question, List.of(), proposedBy,
        now);
    client.forEventSourcedEntity(eventId).method(HumanEventEntity::raise).invoke(raised);
    var answer = client.forEventSourcedEntity(eventId).method(HumanEventEntity::decide)
        .invoke(new HumanEventEntity.Decide(Reviewer.of(caller, false), action, rationale, List.of(), 0, now));
    if (answer.refusal() != null) {
      new Verdicts(client).humanRefusal(answer.event(), caller.subject(), "decide", answer.refusal(), false);
      throw toException(answer.refusal());
    }
    return eventId;
  }

  /**
   * Refuses a decision a control's rule turns down before an event is raised for it, and records the refusal under
   * the control.
   */
  static RuntimeException refuse(
      ComponentClient client, Caller caller, String orgId, String controlId, String subject, String reason) {
    new Verdicts(client).humanRefusal(orgId, controlId, subject, caller.subject(), reason);
    return HttpException.badRequest(reason);
  }

  /** Marks an event decided as it is raised, so nobody is told to come and decide it. */
  static Map<String, String> immediate(Map<String, String> detail) {
    var marked = new java.util.HashMap<>(detail);
    marked.put(HumanEventNotifier.IMMEDIATE, "true");
    return marked;
  }

  /** Makes the change the decision allows, then records on the event whether it took effect. */
  static <T> T applying(ComponentClient client, String eventId, String note, Supplier<T> change) {
    T result;
    try {
      result = change.get();
    } catch (RuntimeException e) {
      client.forEventSourcedEntity(eventId).method(HumanEventEntity::markApplyFailed)
          .invoke(new HumanEventEntity.Applied(String.valueOf(e.getMessage()), System.currentTimeMillis()));
      throw e;
    }
    client.forEventSourcedEntity(eventId).method(HumanEventEntity::markApplied)
        .invoke(new HumanEventEntity.Applied(note, System.currentTimeMillis()));
    return result;
  }

  static RuntimeException toException(Refusal refusal) {
    return switch (refusal.kind()) {
      case NOT_FOUND -> HttpException.notFound();
      case FORBIDDEN -> HttpException.forbidden(refusal.message());
      case CONFLICT -> HttpException.error(StatusCodes.CONFLICT, refusal.message());
      case INVALID -> HttpException.error(StatusCodes.UNPROCESSABLE_ENTITY, refusal.message());
    };
  }
}
