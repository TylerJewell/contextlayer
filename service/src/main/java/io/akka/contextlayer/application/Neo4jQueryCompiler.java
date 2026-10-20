package io.akka.contextlayer.application;

import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.RecordFlattener;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a validated {@link StructuredQuestion} into one read-only parameterized Cypher statement.
 *
 * <p>Nothing from a question is concatenated into the statement except names that were checked
 * against a strict pattern and are quoted; every value travels as a parameter. Every node pattern
 * carries the organization id, so a statement cannot reach another organization's data. The
 * statement contains no write clause.
 */
public final class Neo4jQueryCompiler {

  public record Compiled(String cypher, Map<String, Object> parameters) {}

  public enum Output {
    /** The requested columns. */
    ROWS,
    /** Only the identity of each matching base entity, to count matches. */
    BASE_IDS
  }

  private Neo4jQueryCompiler() {}

  public static Compiled compile(
      String orgId, StructuredQuestion question, IdentityCluster.Status identityStatus, Output output) {
    var errors = question.validate();
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));

    var params = new LinkedHashMap<String, Object>();
    params.put("orgId", orgId);
    params.put("identityStatus", identityStatus.name());
    params.put("limit", question.limit());

    var cypher = new StringBuilder();
    var aliases = new LinkedHashMap<String, String>(); // entity type -> alias used in the statement
    aliases.put(question.entityType(), "a");
    params.put("t0", question.entityType());
    cypher.append("MATCH (a:Entity {orgId: $orgId, type: $t0})");
    appendWhere(cypher, params, "a", "p", question.where());

    int index = 1;
    for (var link : question.links() == null ? List.<StructuredQuestion.Link>of() : question.links()) {
      var alias = "b" + index;
      aliases.putIfAbsent(link.entityType(), alias);
      params.put("t" + index, link.entityType());
      if (link.byIdentity()) {
        cypher.append("\nMATCH (a)-[:MEMBER_OF]->(:Identity {orgId: $orgId, status: $identityStatus})")
            .append("<-[:MEMBER_OF]-(").append(alias).append(":Entity {orgId: $orgId, type: $t").append(index).append("})");
      } else {
        params.put("r" + index, link.via());
        var steps = "[:REL*1.." + link.depth() + " {orgId: $orgId, name: $r" + index + "}]";
        var target = "(" + alias + ":Entity {orgId: $orgId, type: $t" + index + "})";
        cypher.append("\nMATCH (a)").append(link.direction() == StructuredQuestion.Direction.OUT ? "-" + steps + "->" : "<-" + steps + "-")
            .append(target);
      }
      var conditions = new ArrayList<String>();
      conditions.add("a <> " + alias);
      appendConditions(conditions, params, alias, "q" + index + "_", link.where());
      cypher.append("\nWHERE ").append(String.join(" AND ", conditions));
      index++;
    }

    if (output == Output.BASE_IDS) {
      cypher.append("\nRETURN DISTINCT elementId(a) AS baseId LIMIT $limit");
    } else {
      var columns = new ArrayList<String>();
      for (var reference : question.returnFields()) {
        var parts = reference.split("\\.", 2);
        var alias = aliases.get(parts[0]);
        columns.add(alias + ".`" + RecordFlattener.propertyName(parts[1]) + "` AS `" + reference + "`");
      }
      cypher.append("\nRETURN DISTINCT ").append(String.join(", ", columns)).append(" LIMIT $limit");
    }
    return new Compiled(cypher.toString(), params);
  }

  private static void appendWhere(
      StringBuilder cypher, Map<String, Object> params, String alias, String prefix, List<StructuredQuestion.Condition> where) {
    var conditions = new ArrayList<String>();
    appendConditions(conditions, params, alias, prefix, where);
    if (!conditions.isEmpty()) cypher.append("\nWHERE ").append(String.join(" AND ", conditions));
  }

  private static void appendConditions(
      List<String> out, Map<String, Object> params, String alias, String prefix, List<StructuredQuestion.Condition> where) {
    if (where == null) return;
    int n = 0;
    for (var c : where) {
      var name = prefix + n++;
      params.put(name, c.value());
      out.add(alias + ".`" + RecordFlattener.propertyName(c.field()) + "` " + operator(c.op()) + " $" + name);
    }
  }

  private static String operator(StructuredQuestion.Op op) {
    return switch (op) {
      case EQ -> "=";
      case NEQ -> "<>";
      case GT -> ">";
      case GTE -> ">=";
      case LT -> "<";
      case LTE -> "<=";
    };
  }
}
