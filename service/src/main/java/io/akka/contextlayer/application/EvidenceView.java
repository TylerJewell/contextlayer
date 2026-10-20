package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.governance.Evidence;
import java.util.List;

/** Evidence by organization and control, newest first, for compliance and export. */
@Component(id = "evidence-view")
public class EvidenceView extends View {

  public record Row(
      String evidenceId, String orgId, String controlId, String eventType, String boundary, String mode,
      String decision, String consequence, String reasonCode, String subject, String actor, String authority,
      String bundleDigest, String ref, boolean flagged, long at, String verdict) {}

  public record Rows(List<Row> items) {}

  public record ByControl(String orgId, String controlId, long since, int max) {}

  public record ByOrganization(String orgId, long since, int max) {}

  @Consume.FromEventSourcedEntity(EvidenceEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onEvent(EvidenceEntity.Event event) {
      if (!(event instanceof EvidenceEntity.Event.Recorded recorded)) return effects().ignore();
      Evidence e = recorded.evidence();
      return effects().updateRow(new Row(e.evidenceId(), e.orgId(), e.controlId(), e.eventType(), e.boundary(),
          e.mode().name(), e.decision(), e.consequence(), e.reasonCode(), e.subject(), e.actor(), e.authority(),
          e.bundleDigest(), e.ref(), e.flagged(), e.at(), e.verdict().name()));
    }
  }

  @Query("""
      SELECT * AS items FROM evidence
      WHERE orgId = :orgId AND controlId = :controlId AND at >= :since
      ORDER BY at DESC
      LIMIT :max
      """)
  public QueryEffect<Rows> byControl(ByControl query) {
    return queryResult();
  }

  @Query("""
      SELECT * AS items FROM evidence
      WHERE orgId = :orgId AND at >= :since
      ORDER BY at DESC
      LIMIT :max
      """)
  public QueryEffect<Rows> byOrganization(ByOrganization query) {
    return queryResult();
  }
}
