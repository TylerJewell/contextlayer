package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.contextlayer.governance.ControlClaims;
import java.util.HashSet;
import java.util.List;

/**
 * The acceptance of a control and the assertion about its evidence, with their history. Id:
 * {@code ScopedId.of(orgId, "claims", controlId)}. Every change is an event, so the journal is the
 * record of who said what and when; nothing is edited or removed.
 */
@Component(id = "control-claims")
public class ControlClaimsEntity extends EventSourcedEntity<ControlClaims, ControlClaimsEntity.Event> {

  public sealed interface Event {
    @TypeName("control-accepted")
    record Accepted(ControlClaims.Acceptance acceptance) implements Event {}

    @TypeName("control-acceptance-withdrawn")
    record AcceptanceWithdrawn(String by, long at) implements Event {}

    @TypeName("evidence-asserted")
    record Asserted(ControlClaims.Assertion assertion) implements Event {}

  }

  public record Accept(String by, String authority, String rationale, String catalogDigest, long at) {}

  public record Assert(
      String evidenceKind, List<ControlClaims.Expectation> expects, String by, String authority, String rationale,
      long at) {}

  private static final int MAX_EXPECTATIONS = 20;

  @Override
  public ControlClaims emptyState() {
    return ControlClaims.empty();
  }

  public ReadOnlyEffect<ControlClaims> get() {
    return effects().reply(currentState());
  }

  public Effect<ControlClaims> accept(Accept command) {
    if (blank(command.rationale())) return effects().error("a reason is required");
    var acceptance = new ControlClaims.Acceptance(command.by(), command.authority(), command.rationale().trim(),
        command.catalogDigest(), command.at());
    return effects().persist(new Event.Accepted(acceptance)).thenReply(s -> s);
  }

  public Effect<ControlClaims> withdrawAcceptance(Accept command) {
    if (!currentState().accepted()) return effects().error("this control has not been accepted");
    return effects().persist(new Event.AcceptanceWithdrawn(command.by(), command.at())).thenReply(s -> s);
  }

  public Effect<ControlClaims> assertEvidence(Assert command) {
    if (blank(command.evidenceKind())) return effects().error("say which kind of record counts as evidence");
    if (blank(command.rationale())) return effects().error("a reason is required");
    var expects = command.expects() == null ? List.<ControlClaims.Expectation>of() : command.expects();
    if (expects.size() > MAX_EXPECTATIONS) return effects().error("at most " + MAX_EXPECTATIONS + " expected documents");
    var names = new HashSet<String>();
    for (var expected : expects) {
      if (expected == null || blank(expected.name())) return effects().error("an expected document needs a name");
      if (expected.maxAgeDays() < 1 || expected.maxAgeDays() > 3650) {
        return effects().error(expected.name() + ": maxAgeDays must be between 1 and 3650");
      }
      if (!names.add(expected.name())) return effects().error(expected.name() + " is expected twice");
    }
    var current = currentState();
    int revision = current.asserted() ? current.assertion().revision() + 1 : 1;
    var basis = current.accepted() ? current.acceptance().catalogDigest() : "";
    var assertion = new ControlClaims.Assertion(revision, command.evidenceKind().trim(), List.copyOf(expects), command.by(),
        command.authority(), command.rationale().trim(), basis, command.at());
    return effects().persist(new Event.Asserted(assertion)).thenReply(s -> s);
  }

  @Override
  public ControlClaims applyEvent(Event event) {
    return switch (event) {
      case Event.Accepted e -> currentState().withAcceptance(e.acceptance());
      case Event.AcceptanceWithdrawn e -> currentState().withoutAcceptance();
      case Event.Asserted e -> currentState().withAssertion(e.assertion());
    };
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
