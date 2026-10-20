package io.akka.contextlayer.governance;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Builds the event that raises a human control for a particular situation. */
public final class HumanEvents {
  private HumanEvents() {}

  /**
   * @param eventId the entity id, which also names the event to the outside
   * @param proposedBy the person who caused the situation, or {@code system:<part>} when no person did
   */
  public static HumanEventEvent.Raised raised(
      ControlCatalog catalog,
      String controlId,
      String eventId,
      String orgId,
      String subjectKind,
      String summary,
      String assistantId,
      Map<String, String> detail,
      String detector,
      String rule,
      String reason,
      String question,
      List<String> checks,
      String proposedBy,
      long now) {
    var control = catalog.require(controlId);
    if (control.human() == null) throw new IllegalStateException(controlId + " is not a human control");
    long due = now + Duration.parse(control.human().slaTarget()).toMillis();
    return new HumanEventEvent.Raised(eventId, orgId, controlId, catalog.digest(), control.human(), subjectKind,
        summary, assistantId, detail, detector, rule, reason, question, checks, proposedBy, now, due);
  }
}
