package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.contextlayer.domain.IdentityEvent;
import io.akka.contextlayer.domain.ScopedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the entity graph in step with identity decisions: a new member is linked to its cluster, a
 * removed member is unlinked, and every change re-states the cluster's status, so a confirmation
 * joins entities in questions and a rejection or unlinking removes the join. Every operation can be
 * repeated safely, because a failed event is retried.
 */
@Component(id = "identity-graph-consumer")
@Consume.FromEventSourcedEntity(IdentityEntity.class)
public class IdentityGraphConsumer extends Consumer {

  private static final Logger log = LoggerFactory.getLogger(IdentityGraphConsumer.class);

  private final ComponentClient componentClient;
  private final EntityStore store;

  public IdentityGraphConsumer(ComponentClient componentClient, EntityStore store) {
    this.componentClient = componentClient;
    this.store = store;
  }

  public Effect onEvent(IdentityEvent event) {
    var clusterId = messageContext().eventSubject().orElseThrow();
    try {
      var cluster = componentClient.forEventSourcedEntity(clusterId).method(IdentityEntity::get).invoke();
      var orgId = ScopedId.orgOf(clusterId);

      switch (event) {
        case IdentityEvent.MemberAdded added ->
            store.linkIdentity(orgId, added.source(), added.entityType(), added.sourceRecordId(), added.rule(), added.key());
        case IdentityEvent.MemberUnlinked gone ->
            store.unlinkIdentity(orgId, gone.source(), gone.entityType(), gone.sourceRecordId(), cluster.rule(), cluster.key());
        default -> { }
      }
      store.setIdentityStatus(orgId, cluster.rule(), cluster.key(), cluster.status());
      return effects().done();
    } catch (RuntimeException e) {
      // The runtime retries a failing event and reports only that it failed; say why.
      log.warn("Could not apply {} for identity cluster {}: {}", event.getClass().getSimpleName(), clusterId, e.toString());
      throw e;
    }
  }
}
