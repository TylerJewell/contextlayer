package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.EntityChange;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.RefreshStatus;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Applies a change a source pushed to the entity store. The same code serves the service stream
 * consumer and the HTTP route, so a change means the same thing however it arrives.
 *
 * <p>A change is accepted only for an entity type that an active connector declares as STREAMED.
 * Anything else is reported and left out, because a pushed change is not a request and nobody is
 * waiting to be told it was refused.
 */
public class ChangeApplier {

  public enum Outcome {
    APPLIED,
    /** The store already held this version or a later one. */
    ALREADY_HELD,
    /** Connectors are receiving, but none declares the type as STREAMED. */
    NO_CONNECTOR,
    /** No connector is receiving a stream yet, so a stream cannot be taken in without losing its changes. */
    NOT_READY
  }

  private final ComponentClient componentClient;
  private final EntityRefresher refresher;
  private final LongSupplier nowMillis;
  private final TimerScheduler timers;

  public ChangeApplier(
      ComponentClient componentClient, TimerScheduler timers, EntityRefresher refresher, LongSupplier nowMillis) {
    this.componentClient = componentClient;
    this.timers = timers;
    this.refresher = refresher;
    this.nowMillis = nowMillis;
  }

  public Outcome apply(String orgId, EntityChange change) {
    var found = lookup(orgId, change.entityType());
    if (found.provider() == null) {
      recordIntake(orgId, change.entityType(), false, "no active connector streams " + change.entityType());
      return found.anyStreamed() ? Outcome.NO_CONNECTOR : Outcome.NOT_READY;
    }
    var provider = found.provider();
    long now = nowMillis.getAsLong();
    var applied = switch (change) {
      case EntityChange.Upserted e ->
          refresher.applyPushed(orgId, provider.pack().name(), provider.entity(), e.entityId(), e.attributes(), e.version(), now);
      case EntityChange.Deleted e ->
          refresher.applyRemoved(orgId, provider.pack().name(), e.entityType(), e.entityId(), e.version());
      case EntityChange.Heartbeat e -> EntityStore.Applied.OLDER_THAN_HELD;
    };
    // Any word from the source, a repeat or a heartbeat included, shows it is alive.
    componentClient.forKeyValueEntity(ScopedId.of(orgId, provider.pack().name(), change.entityType()))
        .method(RefreshStatusEntity::record)
        .invoke(RefreshStatus.of(orgId, provider.pack().name(), change.entityType(), RefreshStatus.Outcome.OK, now, 0, 0,
            provider.pack().activeHash(), List.of()));
    if (applied != EntityStore.Applied.APPLIED) {
      if (!(change instanceof EntityChange.Heartbeat)) {
        recordIntake(orgId, change.entityType(), false, "no newer than the version held");
      }
      return Outcome.ALREADY_HELD;
    }
    recordIntake(orgId, change.entityType(), true, "from an active connector that streams it");
    ChangeFeed.changed(componentClient, timers, orgId, new ChangeFeedEntity.ChangeNotice(provider.pack().name(),
        change.entityType(), change instanceof EntityChange.Upserted u ? u.entityId() : ((EntityChange.Deleted) change).entityId(),
        change instanceof EntityChange.Upserted ? "upsert" : "delete", now));
    return Outcome.APPLIED;
  }

  /**
   * Records the intake guardrail (custom-cl0031) once an hour for each type and outcome. A stream is replayed from
   * its start whenever the consumer starts again, so a record per change would repeat every held change each time.
   */
  private void recordIntake(String orgId, String entityType, boolean accepted, String reason) {
    var key = entityType + "~" + (accepted ? "accepted" : reason);
    if (!Sampled.firstThisHour(orgId + "|" + Controls.GOVERNED_INTAKE + "|" + key)) return;
    new Verdicts(componentClient).guardrail(orgId, Controls.GOVERNED_INTAKE, Sampled.hourKey(key), accepted, reason,
        "pushed " + entityType, "system:intake");
  }

  private record Provider(ConnectorPack pack, PackDefinition.EntityMapping entity) {}

  private record Lookup(Provider provider, boolean anyStreamed) {}

  private Lookup lookup(String orgId, String entityType) {
    boolean anyStreamed = false;
    var rows = componentClient.forView().method(ConnectorPackView::forOrganization).invoke(orgId);
    for (var row : rows.items()) {
      var pack = componentClient.forEventSourcedEntity(ScopedId.of(orgId, row.name()))
          .method(ConnectorPackEntity::get).invoke();
      if (!pack.exists() || pack.active().isEmpty() || pack.active().get().definition().entities() == null) continue;
      for (var entity : pack.active().get().definition().entities()) {
        if (entity.freshness() == null || !"STREAMED".equals(entity.freshness().policy())) continue;
        anyStreamed = true;
        if (entity.type().equals(entityType)) return new Lookup(new Provider(pack, entity), true);
      }
    }
    return new Lookup(null, anyStreamed);
  }
}
