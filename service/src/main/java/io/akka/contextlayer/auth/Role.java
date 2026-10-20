package io.akka.contextlayer.auth;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The roles a token can carry. Operators and approvers are kept apart on purpose: an approver can
 * review and authorize but not request actions or feed records, so a person who asked for something
 * is not also the person who lets it happen unless they hold both roles (and the four-eyes rule
 * still bars them from approving their own request).
 */
public enum Role {
  VIEWER(EnumSet.of(Permission.READ)),
  OPERATOR(EnumSet.of(Permission.READ, Permission.OPERATE)),
  REVIEWER(EnumSet.of(Permission.READ, Permission.REVIEW)),
  APPROVER(EnumSet.of(Permission.READ, Permission.REVIEW, Permission.APPROVE)),
  ADMIN(EnumSet.allOf(Permission.class));

  private final Set<Permission> permissions;

  Role(Set<Permission> permissions) {
    this.permissions = permissions;
  }

  public Set<Permission> permissions() {
    return permissions;
  }

  public String claimName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Optional<Role> parse(String claim) {
    if (claim == null) return Optional.empty();
    for (Role role : values()) {
      if (role.claimName().equals(claim.trim().toLowerCase(Locale.ROOT))) return Optional.of(role);
    }
    return Optional.empty();
  }
}
