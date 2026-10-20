package io.akka.contextlayer.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is calling, once the platform rule has admitted them. {@code subject} is the token's subject,
 * {@code issuer} its issuer, and {@code held} the permissions of this service the caller holds in
 * the project.
 */
public record Caller(String subject, String issuer, Set<Permission> held) {

  public Caller {
    held = Set.copyOf(held);
  }

  /**
   * The roles that describe what the caller holds, for routing a human event to the people who may
   * decide it: each role whose permissions are all held, leaving out a role that another role held
   * includes. Someone who may approve is an approver, not also a reviewer and a viewer.
   */
  public Set<Role> roles() {
    var satisfied = List.of(Role.values()).stream().filter(r -> held.containsAll(r.permissions())).toList();
    return satisfied.stream()
        .filter(r -> satisfied.stream().noneMatch(o -> o != r && o.permissions().containsAll(r.permissions())
            && o.permissions().size() > r.permissions().size()))
        .collect(Collectors.toSet());
  }

  /** Whether the caller holds a role a control routes to. Someone who holds every permission holds every role. */
  public boolean holdsAnyOf(List<String> routed) {
    if (held.containsAll(Role.ADMIN.permissions())) return true;
    return routed.stream().map(Role::parse).flatMap(java.util.Optional::stream).anyMatch(roles()::contains);
  }

  /**
   * The authority a decision was made under, written into every human decision: the identity
   * provider and the roles held at that moment.
   */
  public String authorityBasis() {
    var names = roles().stream().map(Role::claimName).sorted(Comparator.naturalOrder()).collect(Collectors.joining(","));
    return "idp=" + issuer + ";roles=" + names;
  }
}
