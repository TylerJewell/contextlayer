package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.IdentityEvent;
import java.util.ArrayList;

/**
 * Keeps the queue of identity reviews in step with the clusters: a cluster that needs a person gets
 * a human event, and the event of a question that a changed cluster has made obsolete is closed.
 * Raising is idempotent, so a retried event raises nothing twice.
 */
@Component(id = "identity-review-raiser")
@Consume.FromEventSourcedEntity(IdentityEntity.class)
public class IdentityReviewRaiser extends Consumer {

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public IdentityReviewRaiser(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  public Effect onEvent(IdentityEvent event) {
    var clusterId = messageContext().eventSubject().orElseThrow();
    var cluster = componentClient.forEventSourcedEntity(clusterId).method(IdentityEntity::get).invoke();

    var before = new ArrayList<>(cluster.members());
    switch (event) {
      case IdentityEvent.MemberAdded added ->
          before.remove(new IdentityCluster.Member(added.source(), added.entityType(), added.sourceRecordId()));
      case IdentityEvent.MemberUnlinked gone ->
          before.add(new IdentityCluster.Member(gone.source(), gone.entityType(), gone.sourceRecordId()));
      default -> { }
    }
    if (!IdentityCluster.fingerprintOf(before).equals(cluster.fingerprint())) {
      IdentityReviews.supersede(componentClient,
          IdentityReviews.eventId(cluster.orgId(), cluster.rule(), cluster.key(), IdentityCluster.fingerprintOf(before)),
          "the records in the cluster changed");
    }
    if (cluster.status() == IdentityCluster.Status.PENDING_REVIEW) {
      IdentityReviews.raise(componentClient, timers, cluster);
    }
    return effects().done();
  }
}
