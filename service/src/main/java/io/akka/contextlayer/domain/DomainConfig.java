package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The data domains of a project and which pairs of them may be linked in one question.
 *
 * <p>A project with no domains is ungoverned and every question is allowed, so a project adopts
 * domains when it is ready. Once it has one, a question may read only entity types that are in a
 * domain, only domains its asker is granted, and may join two domains only if a join rule for that
 * pair has been approved.
 */
public record DomainConfig(Map<String, DataDomain> domains, List<JoinRule> joins) {

  /** An approved link between two domains. {@code a} sorts before {@code b}. */
  public record JoinRule(String a, String b, String approvedBy, String rationale, long at) {}

  /** What a question may do. {@code problems} says why not, in words for the asker. */
  public record Access(boolean allowed, List<String> problems, Set<String> domains) {}

  public DomainConfig {
    domains = Map.copyOf(domains);
    joins = List.copyOf(joins);
  }

  public static DomainConfig empty() {
    return new DomainConfig(Map.of(), List.of());
  }

  public boolean governed() {
    return !domains.isEmpty();
  }

  public Optional<DataDomain> domainOf(String entityType) {
    return domains.values().stream().filter(d -> d.entityTypes().contains(entityType)).findFirst();
  }

  public boolean joinApproved(String a, String b) {
    var first = a.compareTo(b) <= 0 ? a : b;
    var second = a.compareTo(b) <= 0 ? b : a;
    return joins.stream().anyMatch(j -> j.a().equals(first) && j.b().equals(second));
  }

  /** Checks a question that reads these entity types, asked by someone with these roles. */
  public Access check(String subject, Set<String> roles, Collection<String> entityTypes) {
    if (!governed()) return new Access(true, List.of(), Set.of());
    var problems = new ArrayList<String>();
    var touched = new LinkedHashSet<String>();
    for (var type : entityTypes) {
      var domain = domainOf(type);
      if (domain.isEmpty()) {
        problems.add(type + " is in no data domain; an administrator has to assign it to one before it can be read");
        continue;
      }
      touched.add(domain.get().name());
      if (!domain.get().admits(subject, roles)) {
        problems.add("you are not granted the " + domain.get().name() + " data domain, which " + type + " belongs to");
      }
    }
    var names = List.copyOf(touched);
    for (int i = 0; i < names.size(); i++) {
      for (int j = i + 1; j < names.size(); j++) {
        if (!joinApproved(names.get(i), names.get(j))) {
          problems.add("linking the " + names.get(i) + " and " + names.get(j) + " data domains needs an approved join rule");
        }
      }
    }
    return new Access(problems.isEmpty(), List.copyOf(problems), Set.copyOf(touched));
  }

  /** The entity types a caller may read, or empty when the project is ungoverned and every type may be. */
  public Optional<Set<String>> readableTypes(String subject, Set<String> roles) {
    if (!governed()) return Optional.empty();
    var types = new LinkedHashSet<String>();
    for (var domain : domains.values()) {
      if (domain.admits(subject, roles)) types.addAll(domain.entityTypes());
    }
    return Optional.of(types);
  }

  // ---- changes, as the entity applies them

  public DomainConfig with(DataDomain domain) {
    var next = new LinkedHashMap<>(domains);
    next.put(domain.name(), domain);
    return new DomainConfig(next, joins);
  }

  /** Removes a domain and the join rules that named it. */
  public DomainConfig without(String name) {
    var next = new TreeMap<>(domains);
    next.remove(name);
    var kept = joins.stream().filter(j -> !j.a().equals(name) && !j.b().equals(name)).toList();
    return new DomainConfig(next, kept);
  }

  public DomainConfig withJoin(JoinRule rule) {
    var kept = new ArrayList<>(joins.stream().filter(j -> !(j.a().equals(rule.a()) && j.b().equals(rule.b()))).toList());
    kept.add(rule);
    return new DomainConfig(domains, kept);
  }

  public DomainConfig withoutJoin(String a, String b) {
    var first = a.compareTo(b) <= 0 ? a : b;
    var second = a.compareTo(b) <= 0 ? b : a;
    return new DomainConfig(domains, joins.stream().filter(j -> !(j.a().equals(first) && j.b().equals(second))).toList());
  }

  /** The domain that already holds one of these types, other than {@code except}, or empty. */
  public Optional<String> conflictFor(DataDomain candidate) {
    for (var type : candidate.entityTypes()) {
      var holder = domainOf(type);
      if (holder.isPresent() && !holder.get().name().equals(candidate.name())) {
        return Optional.of(type + " is already in the " + holder.get().name() + " data domain");
      }
    }
    return Optional.empty();
  }
}
