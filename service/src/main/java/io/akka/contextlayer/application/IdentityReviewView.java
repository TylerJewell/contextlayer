package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.IdentityEvent;
import java.util.List;

/** One row per identity cluster, so the review queue is a query on status. */
@Component(id = "identity-review-view")
public class IdentityReviewView extends View {

  public record ClusterRow(
      String orgId,
      String rule,
      String key,
      double confidence,
      int memberCount,
      String review,
      String status) {

    ClusterRow withMembers(int count) {
      return recompute(count, review);
    }

    ClusterRow withReview(IdentityCluster.Review next) {
      return recompute(memberCount, next.name());
    }

    private ClusterRow recompute(int count, String nextReview) {
      var status = IdentityCluster.statusOf(count, confidence, IdentityCluster.Review.valueOf(nextReview));
      return new ClusterRow(orgId, rule, key, confidence, count, nextReview, status.name());
    }
  }

  public record ClusterRows(List<ClusterRow> items) {}

  public record ByStatus(String orgId, String status) {}

  @Consume.FromEventSourcedEntity(IdentityEntity.class)
  public static class Updater extends TableUpdater<ClusterRow> {
    public Effect<ClusterRow> onEvent(IdentityEvent event) {
      return switch (event) {
        case IdentityEvent.MemberAdded e -> {
          var current = rowState();
          int count = current == null ? 1 : current.memberCount() + 1;
          var base = new ClusterRow(e.orgId(), e.rule(), e.key(), e.confidence(), 0,
              IdentityCluster.Review.NONE.name(), "");
          // A new member invalidates an earlier review decision, as in the entity.
          yield effects().updateRow(base.withMembers(count));
        }
        case IdentityEvent.MemberUnlinked e ->
            effects().updateRow(rowState().withReview(IdentityCluster.Review.NONE)
                .withMembers(Math.max(0, rowState().memberCount() - 1)));
        case IdentityEvent.ReviewConfirmed e ->
            effects().updateRow(rowState().withReview(IdentityCluster.Review.CONFIRMED));
        case IdentityEvent.ReviewRejected e ->
            effects().updateRow(rowState().withReview(IdentityCluster.Review.REJECTED));
      };
    }
  }

  @Query("""
      SELECT * AS items FROM identity_clusters
      WHERE orgId = :orgId AND status = :status
      """)
  public QueryEffect<ClusterRows> byStatus(ByStatus query) {
    return queryResult();
  }
}
