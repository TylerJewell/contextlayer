package io.akka.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.contextlayer.domain.RefreshStatus;

/**
 * The latest refresh result for one entity type from one pack. Only the latest matters, and the
 * action ledger and identity events already carry the audit history, so this is a plain value.
 * Entity id: {@code ScopedId.of(orgId, packName, entityType)}.
 */
@Component(id = "refresh-status")
public class RefreshStatusEntity extends KeyValueEntity<RefreshStatus> {

  @Override
  public RefreshStatus emptyState() {
    return RefreshStatus.empty();
  }

  public Effect<Done> record(RefreshStatus status) {
    return effects().updateState(status).thenReply(Done.getInstance());
  }

  /** Always replies; {@code exists() == false} means nothing has been refreshed yet. */
  public ReadOnlyEffect<RefreshStatus> get() {
    return effects().reply(currentState());
  }
}
