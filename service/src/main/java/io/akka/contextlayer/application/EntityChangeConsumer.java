package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import akka.javasdk.timer.TimerScheduler;
import com.typesafe.config.Config;
import io.akka.contextlayer.domain.EntityChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes in the records another Akka service in this project pushes on its {@code entity_changes}
 * stream. The stream starts from the publisher's snapshot, so a new deployment gets current data
 * and then every change after it.
 *
 * <p>Until a connector that declares a STREAMED type is active, the consumer holds at the first change
 * it cannot place and retries, so nothing published before the connector goes live is lost. Once one
 * is active, a change to a type none of them declares is logged and dropped, because holding there
 * would stop every other type behind it.
 */
@Component(id = "entity-change-consumer")
@Consume.FromServiceStream(service = "claims-system", id = "entity_changes")
public class EntityChangeConsumer extends Consumer {

  private static final Logger log = LoggerFactory.getLogger(EntityChangeConsumer.class);

  private final ChangeApplier applier;
  private final String project;

  public EntityChangeConsumer(
      ComponentClient componentClient, TimerScheduler timers, SourceCaller sourceCaller, EntityStore store, Config config) {
    this.applier = new ChangeApplier(componentClient, timers,
        new EntityRefresher(componentClient, sourceCaller, store, System::currentTimeMillis), System::currentTimeMillis);
    this.project = config.getString("contextlayer.project-id");
  }

  public Effect onUpserted(EntityChange.Upserted change) {
    return take(change);
  }

  public Effect onDeleted(EntityChange.Deleted change) {
    return take(change);
  }

  public Effect onHeartbeat(EntityChange.Heartbeat change) {
    return take(change);
  }

  private Effect take(EntityChange change) {
    switch (applier.apply(project, change)) {
      case NOT_READY -> throw new IllegalStateException("no connector is receiving a stream yet; holding " + change.entityType());
      case NO_CONNECTOR -> log.warn("Dropped a change to {}: no active connector declares it as STREAMED", change.entityType());
      default -> { }
    }
    return effects().done();
  }
}
