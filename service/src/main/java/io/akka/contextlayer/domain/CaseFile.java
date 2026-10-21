package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A work item opened when a monitor finds a row that newly matches. It carries the evidence the
 * monitor saw and a timeline of everything that happened to it (REQUIREMENTS MON-02, AUD-01).
 */
public record CaseFile(
    String orgId,
    String caseId,
    String monitorId,
    int monitorVersion,
    String key,
    Map<String, Object> evidence,
    List<String> sourceNotes,
    String routeTo,
    String assistantId,
    Status status,
    List<TimelineItem> timeline,
    boolean exists) {

  public enum Status { OPEN, NO_LONGER_MATCHING, RESOLVED, DISMISSED }

  public record TimelineItem(long at, String actor, String text) {}

  public static CaseFile empty() {
    return new CaseFile("", "", "", 0, "", Map.of(), List.of(), "", "", Status.OPEN, List.of(), false);
  }

  public boolean isClosed() {
    return status == Status.RESOLVED || status == Status.DISMISSED;
  }

  public Optional<String> validateNoLongerMatching() {
    if (!exists) return Optional.of("case not found");
    if (status != Status.OPEN) return Optional.of("case is " + status);
    return Optional.empty();
  }

  public Optional<String> validateClose(String by) {
    if (!exists) return Optional.of("case not found");
    if (isClosed()) return Optional.of("case is already " + status);
    if (by == null || by.isBlank()) return Optional.of("who is closing the case is required");
    return Optional.empty();
  }

  public Optional<String> validateAnnotate(String actor, String text) {
    if (!exists) return Optional.of("case not found");
    if (isClosed()) return Optional.of("case is already " + status);
    if (actor == null || actor.isBlank()) return Optional.of("who is adding the note is required");
    if (text == null || text.isBlank()) return Optional.of("a note is required");
    return Optional.empty();
  }

  public CaseFile apply(CaseEvent event) {
    return switch (event) {
      case CaseEvent.Opened e -> new CaseFile(e.orgId(), e.caseId(), e.monitorId(), e.monitorVersion(), e.key(),
          e.evidence(), e.sourceNotes(), e.routeTo(), e.assistantId(), Status.OPEN,
          List.of(new TimelineItem(e.at(), "watcher " + e.monitorId(),
              "opened for " + e.key() + (e.routeTo().isBlank() ? "" : ", routed to " + e.routeTo()))),
          true);
      case CaseEvent.NoLongerMatching e -> with(Status.NO_LONGER_MATCHING,
          new TimelineItem(e.at(), "watcher " + monitorId, "the row no longer matches"));
      case CaseEvent.Resolved e -> with(Status.RESOLVED, new TimelineItem(e.at(), e.by(), "resolved: " + e.note()));
      case CaseEvent.Dismissed e -> with(Status.DISMISSED, new TimelineItem(e.at(), e.by(), "dismissed: " + e.note()));
      case CaseEvent.Annotated e -> with(status, new TimelineItem(e.at(), e.actor(), e.text()));
    };
  }

  private CaseFile with(Status next, TimelineItem item) {
    var items = new ArrayList<>(timeline);
    items.add(item);
    return new CaseFile(orgId, caseId, monitorId, monitorVersion, key, evidence, sourceNotes, routeTo, assistantId,
        next, List.copyOf(items), true);
  }
}
