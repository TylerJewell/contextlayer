package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.domain.DataDomain;
import io.akka.contextlayer.domain.DomainConfig;
import io.akka.contextlayer.domain.DomainConfigEvent;

/** The data domains of one project and the join rules between them. The entity id is the project. */
@Component(id = "domain-config")
public class DomainConfigEntity extends EventSourcedEntity<DomainConfig, DomainConfigEvent> {

  public record RemoveDomain(String name, String by, long at) {}

  public record ApproveJoin(String a, String b, String by, String rationale, long at) {}

  public record RevokeJoin(String a, String b, String by, long at) {}

  @Override
  public DomainConfig emptyState() {
    return DomainConfig.empty();
  }

  public ReadOnlyEffect<DomainConfig> get() {
    return effects().reply(currentState());
  }

  public Effect<DomainConfig> set(DataDomain domain) {
    var problems = domain.validate();
    if (!problems.isEmpty()) return effects().error(String.join("; ", problems));
    var conflict = currentState().conflictFor(domain);
    if (conflict.isPresent()) return effects().error(conflict.get());
    return effects().persist(new DomainConfigEvent.DomainSet(domain)).thenReply(s -> s);
  }

  public Effect<DomainConfig> remove(RemoveDomain command) {
    if (!currentState().domains().containsKey(command.name())) return effects().error("no data domain " + command.name());
    return effects().persist(new DomainConfigEvent.DomainRemoved(command.name(), command.by(), command.at())).thenReply(s -> s);
  }

  public Effect<DomainConfig> approveJoin(ApproveJoin command) {
    if (command.a().equals(command.b())) return effects().error("a join rule links two different data domains");
    for (var name : new String[] {command.a(), command.b()}) {
      if (!currentState().domains().containsKey(name)) return effects().error("no data domain " + name);
    }
    var first = command.a().compareTo(command.b()) <= 0 ? command.a() : command.b();
    var second = command.a().compareTo(command.b()) <= 0 ? command.b() : command.a();
    return effects()
        .persist(new DomainConfigEvent.JoinApproved(
            new DomainConfig.JoinRule(first, second, command.by(), command.rationale(), command.at())))
        .thenReply(s -> s);
  }

  public Effect<DomainConfig> revokeJoin(RevokeJoin command) {
    if (!currentState().joinApproved(command.a(), command.b())) return effects().error("no join rule for that pair");
    return effects().persist(new DomainConfigEvent.JoinRevoked(command.a(), command.b(), command.by(), command.at()))
        .thenReply(s -> s);
  }

  @Override
  public DomainConfig applyEvent(DomainConfigEvent event) {
    return switch (event) {
      case DomainConfigEvent.DomainSet e -> currentState().with(e.domain());
      case DomainConfigEvent.DomainRemoved e -> currentState().without(e.name());
      case DomainConfigEvent.JoinApproved e -> currentState().withJoin(e.rule());
      case DomainConfigEvent.JoinRevoked e -> currentState().withoutJoin(e.a(), e.b());
    };
  }
}
