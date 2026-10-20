package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a pack before it is stored. Returns every problem found, so an author fixes them in one
 * pass; an empty list means the pack is acceptable. Never throws on missing fields.
 */
public final class PackValidator {

  static final int MAX_ITEMS = 100;
  static final int MAX_PAGE_SIZE = 10_000;

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}");
  private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
  private static final Pattern TYPE = Pattern.compile("[A-Z][A-Za-z0-9]{0,62}");
  private static final Pattern ACTION = Pattern.compile("[a-z][A-Za-z0-9]{0,62}");
  /** A reference names a store and a path ("vault://team/claims-api"). A bare secret has no scheme. */
  private static final Pattern REFERENCE = Pattern.compile("[a-z][a-z0-9+.-]*://\\S+");
  private static final Set<String> SOURCE_KINDS = Set.of("openapi", "mcp", "database", "file", "stream");
  private static final Pattern RELATION = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
  private static final int MAX_RELATIONS = 10;
  private static final Set<String> FRESHNESS = Set.of("MAINTAINED", "CACHED", "STREAMED", "ON_DEMAND");
  private static final Set<String> PAGING = Set.of("none", "page", "cursor");

  private PackValidator() {}

  public static List<String> validate(PackDefinition pack) {
    var errors = new ArrayList<String>();
    if (pack == null) return List.of("pack is required");

    if (pack.name() == null || !NAME.matcher(pack.name()).matches()) {
      errors.add("name must be lowercase letters, digits and hyphens, starting with a letter");
    }
    if (pack.version() == null || !VERSION.matcher(pack.version()).matches()) {
      errors.add("version must look like 1.2.3");
    }
    if (pack.source() == null || !SOURCE_KINDS.contains(pack.source().kind())) {
      errors.add("source.kind must be one of " + SOURCE_KINDS.stream().sorted().toList());
    } else if (blank(pack.source().ref())) {
      errors.add("source.ref is required");
    }
    boolean streamSource = pack.source() != null && "stream".equals(pack.source().kind());
    // A stream is pushed to the service, which holds no credential for it.
    if (!streamSource && (pack.credentialRef() == null || !REFERENCE.matcher(pack.credentialRef()).matches())) {
      errors.add("credentialRef must be a reference such as vault://path, never the credential itself");
    }

    var entities = pack.entities() == null ? List.<PackDefinition.EntityMapping>of() : pack.entities();
    var actions = pack.actions() == null ? List.<PackDefinition.ActionDef>of() : pack.actions();
    if (entities.isEmpty() && actions.isEmpty()) errors.add("a pack needs at least one entity or action");
    if (entities.size() > MAX_ITEMS) errors.add("at most " + MAX_ITEMS + " entities per pack");
    if (actions.size() > MAX_ITEMS) errors.add("at most " + MAX_ITEMS + " actions per pack");

    var types = new HashSet<String>();
    for (var entity : entities) {
      validateEntity(entity, types, errors);
    }
    var names = new HashSet<String>();
    for (var action : actions) {
      validateAction(action, names, errors);
    }
    return List.copyOf(errors);
  }

  private static void validateEntity(PackDefinition.EntityMapping e, Set<String> seen, List<String> errors) {
    if (e == null) {
      errors.add("entities must not contain null");
      return;
    }
    var label = "entity " + e.type();
    if (e.type() == null || !TYPE.matcher(e.type()).matches()) {
      errors.add("entity type must start with a capital letter and contain only letters and digits");
    } else if (!seen.add(e.type())) {
      errors.add(label + " is declared twice");
    }
    boolean streamed = e.freshness() != null && "STREAMED".equals(e.freshness().policy());
    // A streamed type with no operation is fed only by what the source pushes, so nothing is read.
    boolean pushOnly = streamed && blank(e.operation());
    if (!pushOnly && blank(e.operation())) errors.add(label + ": operation is required");
    if (blank(e.idField())) errors.add(label + ": idField is required");
    if (e.freshness() == null || !FRESHNESS.contains(e.freshness().policy())) {
      errors.add(label + ": freshness.policy must be one of " + FRESHNESS.stream().sorted().toList());
    } else if (!"ON_DEMAND".equals(e.freshness().policy()) && e.freshness().ttlMinutes() <= 0) {
      errors.add(label + ": a " + e.freshness().policy() + " entity needs freshness.ttlMinutes above zero");
    }
    var relationNames = new HashSet<String>();
    if (e.relations().size() > MAX_RELATIONS) errors.add(label + ": at most " + MAX_RELATIONS + " relationships");
    for (var relation : e.relations()) {
      if (relation == null || relation.name() == null || !RELATION.matcher(relation.name()).matches()) {
        errors.add(label + ": a relationship name must be letters, digits and underscores");
        continue;
      }
      if (!relationNames.add(relation.name())) errors.add(label + ": relationship " + relation.name() + " is declared twice");
      if (relation.field() == null || !FIELD.matcher(relation.field()).matches()) {
        errors.add(label + ": relationship " + relation.name() + " needs the field that holds the id it points at");
      }
      if (relation.targetType() == null || !TYPE.matcher(relation.targetType()).matches()) {
        errors.add(label + ": relationship " + relation.name() + " needs a target type that starts with a capital letter");
      }
    }
    if (pushOnly) return;
    if (e.paging() == null || !PAGING.contains(e.paging().style())) {
      errors.add(label + ": paging.style must be one of " + PAGING.stream().sorted().toList());
    } else {
      var paging = e.paging();
      if (paging.maxPageSize() < 1 || paging.maxPageSize() > MAX_PAGE_SIZE) {
        errors.add(label + ": paging.maxPageSize must be between 1 and " + MAX_PAGE_SIZE);
      }
      if ("cursor".equals(paging.style()) && (blank(paging.cursorParam()) || !pointer(paging.nextCursorPath()))) {
        errors.add(label + ": cursor paging needs paging.cursorParam and paging.nextCursorPath (a JSON pointer)");
      }
      if ("page".equals(paging.style()) && blank(paging.pageParam())) {
        errors.add(label + ": page paging needs paging.pageParam");
      }
    }
    if (!pointer(e.recordsPath())) {
      errors.add(label + ": recordsPath must be a JSON pointer such as /items");
    }
    // Every call is bounded: a source with no declared rate could be called without limit (QRY-05).
    if (e.rate() == null || e.rate().callsPerMinute() < 1) {
      errors.add(label + ": rate.callsPerMinute is required and must be at least 1");
    }
  }

  private static void validateAction(PackDefinition.ActionDef a, Set<String> seen, List<String> errors) {
    if (a == null) {
      errors.add("actions must not contain null");
      return;
    }
    var label = "action " + a.name();
    if (a.name() == null || !ACTION.matcher(a.name()).matches()) {
      errors.add("action name must start with a lowercase letter and contain only letters and digits");
    } else if (!seen.add(a.name())) {
      errors.add(label + " is declared twice");
    }
    if (blank(a.operation())) errors.add(label + ": operation is required");
    // The author must choose a risk class; there is no default that could be too permissive.
    if (a.risk() == null) errors.add(label + ": risk is required");
  }

  private static boolean pointer(String s) {
    return s != null && s.startsWith("/") && s.length() > 1;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
