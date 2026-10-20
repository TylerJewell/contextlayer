package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.IdentityEvent;

/** Entity id: {@code ScopedId.of(orgId, rule, normalizedKey)}. */
@Component(id = "identity-cluster")
public class IdentityEntity extends EventSourcedEntity<IdentityCluster, IdentityEvent> {

  public record AddMember(
      String orgId,
      String rule,
      String key,
      double confidence,
      String source,
      String entityType,
      String sourceRecordId) {}

  public record Review(String reviewer) {}

  public record Unlink(String source, String entityType, String sourceRecordId, String by, String reason) {}

  @Override
  public IdentityCluster emptyState() {
    return IdentityCluster.empty();
  }

  public Effect<IdentityCluster> addMember(AddMember command) {
    if (currentState().hasMember(command.source(), command.entityType(), command.sourceRecordId())) {
      return effects().reply(currentState());
    }
    return effects()
        .persist(new IdentityEvent.MemberAdded(
            command.orgId(), command.rule(), command.key(), command.confidence(), command.source(),
            command.entityType(), command.sourceRecordId()))
        .thenReply(state -> state);
  }

  public Effect<IdentityCluster> confirm(Review command) {
    var error = currentState().validateReview(command.reviewer());
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new IdentityEvent.ReviewConfirmed(command.reviewer())).thenReply(state -> state);
  }

  public Effect<IdentityCluster> reject(Review command) {
    var error = currentState().validateReview(command.reviewer());
    if (error.isPresent()) return effects().error(error.get());
    return effects().persist(new IdentityEvent.ReviewRejected(command.reviewer())).thenReply(state -> state);
  }

  /**
   * Reverses a link: the cluster returns to the review state of its remaining members. A record that
   * is not a member is already unlinked, so asking again changes nothing.
   */
  public Effect<IdentityCluster> unlink(Unlink command) {
    if (!currentState().exists() || !currentState().hasMember(command.source(), command.entityType(), command.sourceRecordId())) {
      return effects().reply(currentState());
    }
    var error = currentState().validateUnlink(
        command.source(), command.entityType(), command.sourceRecordId(), command.by());
    if (error.isPresent()) return effects().error(error.get());
    return effects()
        .persist(new IdentityEvent.MemberUnlinked(
            command.source(), command.entityType(), command.sourceRecordId(), command.by(), command.reason()))
        .thenReply(state -> state);
  }

  public ReadOnlyEffect<IdentityCluster> get() {
    if (!currentState().exists()) return effects().error("identity cluster not found");
    return effects().reply(currentState());
  }

  @Override
  public IdentityCluster applyEvent(IdentityEvent event) {
    return currentState().apply(event);
  }
}
