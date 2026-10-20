package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.governance.Integrity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Puts evidence records in a single order and chains their digests. The entity id is the project.
 *
 * <p>Taking an evidence record in is repeated if its delivery is repeated, so the last
 * {@value #RECENT} links are kept to recognise a record that is already in the chain and answer with
 * the link it has, instead of chaining it twice.
 */
@Component(id = "integrity-chain")
public class IntegrityChainEntity extends EventSourcedEntity<IntegrityChainEntity.Chain, IntegrityChainEntity.Event> {

  static final int RECENT = 500;

  /** @param head the chain digest of the last link, empty before the first */
  public record Chain(long count, String head, List<Integrity.Link> recent) {
    public Optional<Integrity.Link> alreadyChained(String recordId) {
      return recent.stream().filter(l -> l.recordId().equals(recordId)).findFirst();
    }
  }

  public sealed interface Event {
    @TypeName("evidence-chained")
    record Chained(Integrity.Link link) implements Event {}
  }

  public record Append(String recordId, String recordDigest, long at) {}

  @Override
  public Chain emptyState() {
    return new Chain(0, "", List.of());
  }

  public ReadOnlyEffect<Chain> get() {
    return effects().reply(currentState());
  }

  public Effect<Integrity.Link> append(Append command) {
    var existing = currentState().alreadyChained(command.recordId());
    if (existing.isPresent()) return effects().reply(existing.get());
    var link = Integrity.next(currentState().head(), currentState().count(), command.recordId(), command.recordDigest(), command.at());
    return effects().persist(new Event.Chained(link)).thenReply(s -> link);
  }

  @Override
  public Chain applyEvent(Event event) {
    return switch (event) {
      case Event.Chained e -> {
        var recent = new ArrayList<>(currentState().recent());
        recent.add(e.link());
        if (recent.size() > RECENT) recent.remove(0);
        yield new Chain(e.link().seq(), e.link().chainDigest(), recent);
      }
    };
  }
}
