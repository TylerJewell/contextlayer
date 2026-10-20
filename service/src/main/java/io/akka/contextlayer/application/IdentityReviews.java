package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.HumanEvents;
import java.util.List;
import java.util.Map;

/**
 * The human event behind an identity cluster's review. One event asks one question: are these
 * records one entity? Its id carries a fingerprint of the records, so when a record joins or leaves
 * the cluster the old question is closed and a new one raised, and a decision made on the old set
 * is never applied to the new.
 */
public final class IdentityReviews {
  private IdentityReviews() {}

  public static final String KIND = "identity";

  /** The key is the matched text, often an email address; an id is shown and logged, so it carries a hash instead. */
  private static String digest(String text) {
    try {
      var hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash, 0, 8);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String eventId(String orgId, String rule, String key, String fingerprint) {
    return ScopedId.of(orgId, "he", KIND, digest(rule + "/" + key), fingerprint);
  }

  public static String eventId(IdentityCluster cluster) {
    return eventId(cluster.orgId(), cluster.rule(), cluster.key(), cluster.fingerprint());
  }

  /** Raises the review of a cluster waiting for a person. Raising it again leaves the first as it is. */
  public static String raise(ComponentClient client, TimerScheduler timers, IdentityCluster cluster) {
    long now = System.currentTimeMillis();
    var id = eventId(cluster);
    // No key in the summary or the detail: it is the matched text, often an email address.
    var raised = HumanEvents.raised(ControlCatalog.current(), Controls.IDENTITY_REVIEW, id, cluster.orgId(), KIND,
        "Are " + cluster.members().size() + " records matching on " + cluster.rule() + " one entity?", "",
        Map.of("rule", cluster.rule(), "_clusterId", ScopedId.of(cluster.orgId(), cluster.rule(), ScopedId.safe(cluster.key())),
            "fingerprint", cluster.fingerprint(), "memberCount", String.valueOf(cluster.members().size())),
        "identity-rule", cluster.rule(), "the match is not certain enough to join the records without a person",
        "Are the records that match on " + cluster.rule() + " one entity?",
        List.of("The records share details that identify one entity.", "No record belongs to someone else."),
        "system:identity", now);
    var answer = client.forEventSourcedEntity(id).method(HumanEventEntity::raise).invoke(raised);
    if (answer.event().raisedAt() == now) HumanEventTimedAction.schedule(timers, client, id, raised.dueAt());
    return id;
  }

  public static void supersede(ComponentClient client, String eventId, String why) {
    client.forEventSourcedEntity(eventId).method(HumanEventEntity::supersede)
        .invoke(new HumanEventEntity.Supersede(why, System.currentTimeMillis()));
  }
}
