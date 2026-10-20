package io.akka.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.ConnectorPackEvent;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.PackHash;

/** Entity id: {@code ScopedId.of(orgId, packName)}. */
@Component(id = "connector-pack")
public class ConnectorPackEntity extends EventSourcedEntity<ConnectorPack, ConnectorPackEvent> {

  public record Import(String orgId, String packName, PackDefinition definition, String importedBy) {}

  public record Activate(String hash, ConnectorPack.Mode mode, String activatedBy) {}

  @Override
  public ConnectorPack emptyState() {
    return ConnectorPack.empty();
  }

  /** Stores a new version without changing what is active. Returns the version hash. */
  public Effect<String> importVersion(Import command) {
    var errors = currentState().validateImport(command.packName(), command.definition(), command.importedBy());
    if (!errors.isEmpty()) return effects().error(String.join("; ", errors));
    var hash = PackHash.of(command.definition());
    if (currentState().find(hash).isPresent()) return effects().reply(hash);
    return effects()
        .persist(new ConnectorPackEvent.Imported(
            command.orgId(), command.packName(), hash, command.definition(), command.importedBy()))
        .thenReply(state -> hash);
  }

  public Effect<Done> activate(Activate command) {
    var error = currentState().validateActivate(command.hash(), command.mode(), command.activatedBy());
    if (error.isPresent()) return effects().error(error.get());
    if (command.hash().equals(currentState().activeHash()) && command.mode() == currentState().mode()) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(new ConnectorPackEvent.Activated(command.hash(), command.mode(), command.activatedBy()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> deactivate(String by) {
    var error = currentState().validateDeactivate(by);
    if (error.isPresent()) return effects().error(error.get());
    if (currentState().mode() == ConnectorPack.Mode.INACTIVE) return effects().reply(Done.getInstance());
    return effects().persist(new ConnectorPackEvent.Deactivated(by)).thenReply(state -> Done.getInstance());
  }

  /**
   * Always replies, including for a pack that does not exist yet ({@code exists() == false}),
   * so a caller can tell "no such pack" apart from a failed call.
   */
  public ReadOnlyEffect<ConnectorPack> get() {
    return effects().reply(currentState());
  }

  @Override
  public ConnectorPack applyEvent(ConnectorPackEvent event) {
    return currentState().apply(event);
  }
}
