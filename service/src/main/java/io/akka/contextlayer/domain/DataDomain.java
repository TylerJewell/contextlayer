package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A named group of entity types that are governed together: who may ask about them is decided for
 * the group, not type by type.
 *
 * @param readerRoles roles whose holders may read the domain (viewer, operator, reviewer, approver,
 *     admin). A caller has the roles that describe what they hold, so a role listed here admits
 *     callers whose most specific role it is.
 * @param readerPeople subjects who may read the domain whatever their roles
 */
public record DataDomain(
    String name,
    String description,
    List<String> entityTypes,
    List<String> readerRoles,
    List<String> readerPeople,
    String changedBy,
    long changedAt) {

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,40}");
  private static final Pattern TYPE = Pattern.compile("[A-Z][A-Za-z0-9]{0,62}");
  private static final int MAX_ITEMS = 100;

  public DataDomain {
    entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
    readerRoles = readerRoles == null ? List.of()
        : readerRoles.stream().map(r -> r == null ? "" : r.trim().toLowerCase(java.util.Locale.ROOT)).toList();
    readerPeople = readerPeople == null ? List.of() : List.copyOf(readerPeople);
    description = description == null ? "" : description;
  }

  public List<String> validate() {
    var errors = new ArrayList<String>();
    if (name == null || !NAME.matcher(name).matches()) {
      errors.add("name must be lowercase letters, digits and hyphens, starting with a letter");
    }
    if (entityTypes.isEmpty()) errors.add("a data domain needs at least one entity type");
    if (entityTypes.size() > MAX_ITEMS || readerRoles.size() > MAX_ITEMS || readerPeople.size() > MAX_ITEMS) {
      errors.add("at most " + MAX_ITEMS + " entity types, roles and people");
    }
    var seen = new HashSet<String>();
    for (var type : entityTypes) {
      if (type == null || !TYPE.matcher(type).matches()) {
        errors.add("entity type '" + type + "' must start with a capital letter and contain only letters and digits");
      } else if (!seen.add(type)) {
        errors.add("entity type " + type + " is listed twice");
      }
    }
    for (var role : readerRoles) {
      if (io.akka.contextlayer.auth.Role.parse(role).isEmpty()) errors.add("'" + role + "' is not a role; use viewer, operator, reviewer, approver or admin");
    }
    for (var person : readerPeople) {
      if (person == null || person.isBlank() || person.startsWith("system:")) errors.add("a reader must be a person's subject");
    }
    if (readerRoles.isEmpty() && readerPeople.isEmpty()) errors.add("a data domain needs at least one role or person who may read it");
    return List.copyOf(errors);
  }

  /** Whether a caller with these roles may read the domain. */
  public boolean admits(String subject, java.util.Set<String> roles) {
    return readerPeople.contains(subject) || readerRoles.stream().anyMatch(roles::contains);
  }
}
