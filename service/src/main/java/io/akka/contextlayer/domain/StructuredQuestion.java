package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A read-only question over entities: one base entity type, conditions on it, and links to other
 * entity types, either through confirmed identity or by following a named relationship a source
 * declared (REQUIREMENTS QRY-01, QRY-02, QRY-05). It cannot express a write, and every part is
 * bounded, so a question can be checked before anything runs.
 *
 * <p>Fields in {@code returnFields} are written {@code Type.field}, where Type is the base or a
 * linked entity type.
 */
public record StructuredQuestion(
    String entityType,
    List<Condition> where,
    List<Link> links,
    List<String> returnFields,
    int limit) {

  public static final int MAX_CONDITIONS = 10;
  public static final int MAX_LINKS = 3;
  public static final int MAX_RETURN_FIELDS = 20;
  public static final int MAX_LIMIT = 1_000;
  public static final int MAX_DEPTH = 3;

  private static final Pattern TYPE = Pattern.compile("[A-Z][A-Za-z0-9]{0,62}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
  private static final Pattern RELATION = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");

  public enum Op { EQ, NEQ, GT, GTE, LT, LTE }

  /** The value is a string, a number or a boolean. */
  public record Condition(String field, Op op, Object value) {}

  public enum Direction { OUT, IN }

  /**
   * Entities of {@code entityType} that are related to the base entity. With no {@code via} they are the
   * ones that share a confirmed identity with it. With {@code via} they are the ones reached by following
   * the named relationship, in the given direction, one to {@code depth} steps.
   */
  public record Link(String entityType, List<Condition> where, String via, Direction direction, int depth) {

    public Link {
      via = via == null ? "" : via;
      direction = direction == null ? Direction.OUT : direction;
      depth = depth == 0 ? 1 : depth;
    }

    public Link(String entityType, List<Condition> where) {
      this(entityType, where, "", Direction.OUT, 1);
    }

    public boolean byIdentity() {
      return via.isEmpty();
    }
  }

  /** Every problem found, or an empty list when the question is acceptable. */
  public List<String> validate() {
    var errors = new ArrayList<String>();
    var types = new HashSet<String>();
    if (!validType(entityType)) {
      errors.add("entityType must start with a capital letter and contain only letters and digits");
    } else {
      types.add(entityType);
    }
    validateConditions("where", where, errors);

    var linkList = links == null ? List.<Link>of() : links;
    if (linkList.size() > MAX_LINKS) errors.add("at most " + MAX_LINKS + " links");
    for (var link : linkList) {
      if (link == null || !validType(link.entityType())) {
        errors.add("a link needs a valid entityType");
        continue;
      }
      types.add(link.entityType());
      validateConditions("link " + link.entityType(), link.where(), errors);
      if (link.byIdentity() && link.depth() != 1) errors.add("link " + link.entityType() + ": depth applies only to a relationship");
      if (!link.byIdentity() && !RELATION.matcher(link.via()).matches()) {
        errors.add("link " + link.entityType() + ": via must be a relationship name, letters, digits and underscores");
      }
      if (link.depth() < 1 || link.depth() > MAX_DEPTH) {
        errors.add("link " + link.entityType() + ": depth must be between 1 and " + MAX_DEPTH);
      }
    }

    if (returnFields == null || returnFields.isEmpty()) {
      errors.add("returnFields must name at least one field");
    } else {
      if (returnFields.size() > MAX_RETURN_FIELDS) errors.add("at most " + MAX_RETURN_FIELDS + " return fields");
      for (var reference : returnFields) {
        var parts = reference == null ? new String[0] : reference.split("\\.", -1);
        if (parts.length != 2 || !types.contains(parts[0]) || !FIELD.matcher(parts[1]).matches()) {
          errors.add("return field '" + reference + "' must be Type.field for a type in this question");
        }
      }
    }
    if (limit < 1 || limit > MAX_LIMIT) errors.add("limit must be between 1 and " + MAX_LIMIT);
    return List.copyOf(errors);
  }

  private static void validateConditions(String where, List<Condition> conditions, List<String> errors) {
    if (conditions == null) return;
    if (conditions.size() > MAX_CONDITIONS) errors.add(where + ": at most " + MAX_CONDITIONS + " conditions");
    for (var c : conditions) {
      if (c == null || c.field() == null || !FIELD.matcher(c.field()).matches()) {
        errors.add(where + ": condition field must be letters, digits and underscores");
      } else if (c.op() == null) {
        errors.add(where + ": condition on " + c.field() + " needs an operator");
      } else if (!(c.value() instanceof String || c.value() instanceof Number || c.value() instanceof Boolean)) {
        errors.add(where + ": condition on " + c.field() + " needs a string, number or boolean value");
      }
    }
  }

  private static boolean validType(String type) {
    return type != null && TYPE.matcher(type).matches();
  }
}
