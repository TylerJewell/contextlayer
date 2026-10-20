package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Evidence;
import io.akka.contextlayer.governance.EvaluationMode;
import java.util.function.LongSupplier;

/**
 * Turns "this control ran and decided this" into an {@link Evidence} record. The control's proof
 * event and boundary come from the catalog, and the catalog's digest is stamped on the record, so
 * evidence cannot name an event the control does not declare.
 */
public final class EvidenceRecorder {

  /**
   * What happened. {@code idPart} is the same for the same fact, so a repeat records nothing new.
   * {@code eventType} is blank for the control's proof event, or another event type the control emits.
   */
  public record Fact(
      String orgId,
      String controlId,
      String idPart,
      EvaluationMode mode,
      String decision,
      String consequence,
      String reasonCode,
      String subject,
      String actor,
      String authority,
      String ref,
      boolean flagged,
      String eventType,
      long at) {}

  private final ComponentClient componentClient;
  private final ControlCatalog catalog;
  private final LongSupplier clock;

  public EvidenceRecorder(ComponentClient componentClient) {
    this(componentClient, ControlCatalog.current(), System::currentTimeMillis);
  }

  public EvidenceRecorder(ComponentClient componentClient, ControlCatalog catalog, LongSupplier clock) {
    this.componentClient = componentClient;
    this.catalog = catalog;
    this.clock = clock;
  }

  public static Fact fact(
      String orgId, String controlId, String idPart, EvaluationMode mode, String decision, String consequence,
      String reasonCode, String subject, String actor) {
    return new Fact(orgId, controlId, idPart, mode, decision, consequence, reasonCode, subject, actor, "", "", false, "", 0);
  }

  public Evidence record(Fact fact) {
    var control = catalog.require(fact.controlId());
    var id = ScopedId.of(fact.orgId(), "ev", fact.controlId(), ScopedId.safe(fact.idPart()));
    long at = fact.at() > 0 ? fact.at() : clock.getAsLong();
    var eventType = fact.eventType().isBlank() ? control.proofEvent() : fact.eventType();
    var evidence = new Evidence(id, fact.orgId(), fact.controlId(), eventType, control.boundary(),
        fact.mode(), fact.decision(), fact.consequence(), fact.reasonCode(), fact.subject(), fact.actor(),
        fact.authority(), catalog.digest(), fact.ref(), fact.flagged(), at);
    componentClient.forEventSourcedEntity(id).method(EvidenceEntity::record).invoke(evidence);
    return evidence;
  }
}
