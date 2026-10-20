package io.akka.contextlayer.governance;

import io.akka.contextlayer.auth.Caller;
import io.akka.contextlayer.auth.Role;
import java.util.List;

/**
 * The person acting on a human event, taken from their verified token and never from a request
 * body. {@code authority} is the identity provider and roles in force at that moment.
 *
 * @param rehearsal set by a console that is rehearsing: the act is recorded as a rehearsal and
 *     changes nothing real
 */
public record Reviewer(String subject, List<String> roles, String identityProvider, boolean rehearsal, String authority) {

  public Reviewer {
    roles = roles == null ? List.of() : List.copyOf(roles);
  }

  public static Reviewer of(Caller caller, boolean rehearsal) {
    return new Reviewer(caller.subject(), caller.roles().stream().map(Role::claimName).sorted().toList(),
        caller.issuer(), rehearsal, caller.authorityBasis());
  }

  /** The workflow, timer or other part of the system acting without a person. */
  public static Reviewer system(String name) {
    return new Reviewer("system:" + name, List.of(), "contextlayer", false, "system");
  }

  public boolean holdsAnyOf(List<String> routed) {
    if (roles.contains(Role.ADMIN.claimName())) return true;
    return routed.stream().anyMatch(roles::contains);
  }
}
