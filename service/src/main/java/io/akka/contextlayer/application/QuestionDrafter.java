package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.application.EntityStore.FieldSchema;
import io.akka.contextlayer.application.EntityStore.TypeSchema;
import io.akka.contextlayer.auth.Caller;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.PersonalDataMasker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns a request in words into a structured question, without a model ever seeing a record.
 *
 * <p>The model is shown the shape of the data: entity types, field names and kinds, and, for a field
 * that holds a handful of categories across many records (a status, say), those categories. A
 * category is shown only when the sample is large enough that it describes a category and not a few
 * individuals, and only if masking would not change it. What comes back is checked against that
 * shape and against the question rules; a draft that fails is sent back once with the reasons.
 * The result is a draft: a person reads it and runs it.
 */
public class QuestionDrafter {

  /** @param question null when the model asked for clarification or no valid draft was reached */
  /** @param draftId the draft's id, set when a question was drafted, to be passed back when a person runs it */
  public record Drafted(
      StructuredQuestion question, String explanation, String clarification, List<String> problems, String draftId) {

    public Drafted(StructuredQuestion question, String explanation, String clarification, List<String> problems) {
      this(question, explanation, clarification, problems, "");
    }
  }

  public static final int MAX_REQUEST = 1000;
  private static final int MAX_CATEGORIES = 8;
  private static final int MAX_CATEGORY_LENGTH = 30;

  private final ComponentClient componentClient;
  private final EntityStore store;

  public QuestionDrafter(ComponentClient componentClient, EntityStore store) {
    this.componentClient = componentClient;
    this.store = store;
  }

  public Drafted draft(String orgId, String request, Caller caller) {
    var domains = DomainAccess.config(componentClient, orgId);
    var readable = domains.readableTypes(caller.subject(), DomainAccess.rolesOf(caller));
    var schema = store.schema(orgId).stream()
        .filter(type -> readable.isEmpty() || readable.get().contains(type.entityType())).toList();
    if (schema.isEmpty()) {
      return new Drafted(null, "", "", List.of(readable.isPresent()
          ? "no data you are granted has been read yet"
          : "no data has been read yet; connect a source and read it first"));
    }
    var shown = describe(schema);
    var id = UUID.randomUUID().toString();
    var recorder = new EvidenceRecorder(componentClient);
    // The runtime's question-shape guardrail refuses a call that shows more than the shape; checking it here too
    // records the verdict beside the draft, and nothing is sent when it would be refused.
    var shape = shapeProblems(shown);
    record(recorder, orgId, Controls.SHAPE_ONLY_DRAFTING, id, shape.isEmpty() ? "PASS" : "BLOCK",
        shape.isEmpty() ? "CONTINUED" : "BLOCKED", shape.isEmpty() ? "shape-only" : String.join("; ", shape), "");
    if (!shape.isEmpty()) return new Drafted(null, "", "", List.of("the data's shape could not be shown safely"));

    String feedback = "";
    Drafted last = null;
    String interaction = "";
    for (int attempt = 0; attempt < 2; attempt++) {
      var reply = componentClient.forAgent().inSession(ScopedId.of(orgId, "qd", id + "-" + attempt))
          .method(QuestionAgent::draft).withDetailedReply().invoke(new QuestionAgent.Request(request, shown, feedback));
      interaction = reply.interactionId().map(i -> "ledger:" + i).orElse("");
      var draft = reply.value();
      if (draft == null) {
        last = new Drafted(null, "", "", List.of("the model gave no answer"));
      } else if (draft.clarification() != null && !draft.clarification().isBlank()) {
        return new Drafted(null, "", draft.clarification().trim(), List.of());
      } else {
        last = check(draft, schema);
        if (last.problems().isEmpty()) {
          record(recorder, orgId, Controls.DRAFT_SCHEMA_CHECK, id, "PASS", "CONTINUED", "drafted", interaction);
          // The model was shown only what the asker may read, but a draft can still link two domains.
          var access = DomainAccess.check(componentClient, orgId, domains, caller, DomainAccess.typesOf(last.question()));
          return access.allowed() ? new Drafted(last.question(), last.explanation(), "", List.of(), id)
              : new Drafted(null, "", "", access.problems());
        }
      }
      feedback = String.join("\n", last.problems());
    }
    record(recorder, orgId, Controls.DRAFT_SCHEMA_CHECK, id, "BLOCK", "BLOCKED", "invalid-draft", interaction);
    return new Drafted(null, "", "", last.problems());
  }

  /** What the model is shown: types, fields, kinds, and categories only where they are safe to show. */
  static String describe(List<TypeSchema> schema) {
    var out = new StringBuilder();
    for (var type : schema) {
      out.append(type.entityType()).append(" (").append(type.count()).append(" records)\n");
      for (var field : type.fields()) {
        out.append("  ").append(field.name()).append(": ").append(field.kind());
        var categories = categories(field);
        if (!categories.isEmpty()) out.append(", one of ").append(String.join(", ", categories));
        out.append('\n');
      }
      for (var relation : type.relations()) {
        out.append("  relationship ").append(relation.name()).append(" -> ").append(relation.targetType()).append('\n');
      }
    }
    return out.toString();
  }

  static List<String> categories(FieldSchema field) {
    if (!"string".equals(field.kind()) || field.distinct() > MAX_CATEGORIES || field.sampled() < 2 * field.distinct()) {
      return List.of();
    }
    var values = new ArrayList<String>();
    for (var value : field.values()) {
      if (value.length() > MAX_CATEGORY_LENGTH || PersonalDataMasker.mask(value).total() > 0) return List.of();
      values.add(value);
    }
    return values;
  }

  private static final Pattern TYPE_LINE = Pattern.compile("\\S+ \\(\\d+ records\\)");
  private static final Pattern FIELD_LINE = Pattern.compile("  [^\\s:][^:]*: (?:string|number|boolean)(?:, one of (.+))?");
  private static final Pattern RELATION_LINE = Pattern.compile("  relationship \\S+ -> \\S+");

  /**
   * What in {@code shown} is not the shape of the data as {@link #describe} writes it: a line of another form, or a
   * category list that a record's values could have leaked into. Empty when it is shape only.
   */
  static List<String> shapeProblems(String shown) {
    var problems = new ArrayList<String>();
    var lines = shown.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      if (TYPE_LINE.matcher(line).matches() || RELATION_LINE.matcher(line).matches()) continue;
      var field = FIELD_LINE.matcher(line);
      if (!field.matches()) {
        problems.add("line " + (i + 1) + " is not a type, a field or a relationship");
        continue;
      }
      if (field.group(1) == null) continue;
      var values = field.group(1).split(", ");
      if (values.length > MAX_CATEGORIES) problems.add("line " + (i + 1) + " lists more than " + MAX_CATEGORIES + " categories");
      for (var value : values) {
        if (value.length() > MAX_CATEGORY_LENGTH || PersonalDataMasker.mask(value).total() > 0) {
          problems.add("line " + (i + 1) + " lists a value that is not a category");
          break;
        }
      }
    }
    return problems;
  }

  private static Drafted check(QuestionAgent.Draft draft, List<TypeSchema> schema) {
    var problems = new ArrayList<String>();
    var byType = new java.util.HashMap<String, Map<String, String>>();
    var relationsOf = new java.util.HashMap<String, List<EntityStore.Relation>>();
    for (var type : schema) {
      var kinds = new java.util.HashMap<String, String>();
      type.fields().forEach(f -> kinds.put(f.name(), f.kind()));
      byType.put(type.entityType(), kinds);
      relationsOf.put(type.entityType(), type.relations());
    }

    var base = draft.entityType() == null ? "" : draft.entityType().trim();
    if (!byType.containsKey(base)) problems.add("entityType '" + base + "' is not one of " + byType.keySet().stream().sorted().toList());
    var where = conditions("where", base, draft.where(), byType, problems);
    var links = new ArrayList<StructuredQuestion.Link>();
    for (var link : draft.links() == null ? List.<QuestionAgent.Link>of() : draft.links()) {
      var type = link.entityType() == null ? "" : link.entityType().trim();
      if (!byType.containsKey(type)) {
        problems.add("link type '" + type + "' is not one of " + byType.keySet().stream().sorted().toList());
        continue;
      }
      var via = link.via() == null ? "" : link.via().trim();
      var direction = StructuredQuestion.Direction.OUT;
      if (link.direction() != null && link.direction().trim().equalsIgnoreCase("IN")) direction = StructuredQuestion.Direction.IN;
      int depth = link.depth() == null || link.depth() < 1 ? 1 : Math.min(link.depth(), StructuredQuestion.MAX_DEPTH);
      if (!via.isEmpty()) {
        var from = direction == StructuredQuestion.Direction.OUT ? base : type;
        var to = direction == StructuredQuestion.Direction.OUT ? type : base;
        final var name = via;
        if (relationsOf.getOrDefault(from, List.of()).stream().noneMatch(r -> r.name().equals(name) && r.targetType().equals(to))) {
          problems.add("link " + type + ": there is no relationship " + via + " from " + from + " to " + to);
        }
      }
      links.add(new StructuredQuestion.Link(type, conditions("link " + type, type, link.where(), byType, problems), via, direction,
          via.isEmpty() ? 1 : depth));
    }
    var returns = draft.returnFields() == null ? List.<String>of() : draft.returnFields().stream().map(String::trim).toList();
    for (var reference : returns) {
      var parts = reference.split("\\.", -1);
      if (parts.length == 2 && byType.containsKey(parts[0]) && !byType.get(parts[0]).containsKey(parts[1])) {
        problems.add("return field '" + reference + "': " + parts[0] + " has no field " + parts[1]);
      }
    }
    int limit = draft.limit() == null ? 100 : draft.limit();
    var question = new StructuredQuestion(base, where, links, returns, limit);
    if (problems.isEmpty()) problems.addAll(question.validate());
    return problems.isEmpty()
        ? new Drafted(question, draft.explanation() == null ? "" : draft.explanation().trim(), "", List.of())
        : new Drafted(null, "", "", List.copyOf(problems));
  }

  private static List<StructuredQuestion.Condition> conditions(
      String where, String type, List<QuestionAgent.Condition> drafted, Map<String, Map<String, String>> byType,
      List<String> problems) {
    var out = new ArrayList<StructuredQuestion.Condition>();
    for (var c : drafted == null ? List.<QuestionAgent.Condition>of() : drafted) {
      var kinds = byType.getOrDefault(type, Map.of());
      var kind = c.field() == null ? null : kinds.get(c.field().trim());
      if (kind == null) {
        problems.add(where + ": " + type + " has no field '" + c.field() + "'");
        continue;
      }
      var op = operator(c.op());
      if (op.isEmpty()) {
        problems.add(where + ": '" + c.op() + "' is not an operator; use EQ, NEQ, GT, GTE, LT or LTE");
        continue;
      }
      var value = coerce(kind, c.value());
      if (value.isEmpty()) {
        problems.add(where + ": " + c.field() + " is a " + kind + " field and '" + c.value() + "' is not one");
        continue;
      }
      if (!"string".equals(kind) && !"number".equals(kind) && op.get() != StructuredQuestion.Op.EQ && op.get() != StructuredQuestion.Op.NEQ) {
        problems.add(where + ": a " + kind + " field can only be compared with EQ or NEQ");
        continue;
      }
      out.add(new StructuredQuestion.Condition(c.field().trim(), op.get(), value.get()));
    }
    return out;
  }

  static Optional<StructuredQuestion.Op> operator(String text) {
    if (text == null) return Optional.empty();
    return Optional.ofNullable(switch (text.trim().toUpperCase()) {
      case "EQ", "=", "==" -> StructuredQuestion.Op.EQ;
      case "NEQ", "!=" -> StructuredQuestion.Op.NEQ;
      case "GT", ">" -> StructuredQuestion.Op.GT;
      case "GTE", ">=" -> StructuredQuestion.Op.GTE;
      case "LT", "<" -> StructuredQuestion.Op.LT;
      case "LTE", "<=" -> StructuredQuestion.Op.LTE;
      default -> null;
    });
  }

  static Optional<Object> coerce(String kind, String text) {
    if (text == null) return Optional.empty();
    var t = text.trim();
    switch (kind) {
      case "number":
        try {
          return Optional.of(t.contains(".") || t.contains("e") || t.contains("E") ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t));
        } catch (NumberFormatException e) {
          return Optional.empty();
        }
      case "boolean":
        return t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")
            ? Optional.of(Boolean.parseBoolean(t)) : Optional.empty();
      default:
        return Optional.of(text);
    }
  }

  private static void record(EvidenceRecorder recorder, String orgId, String control, String id, String decision,
      String consequence, String reason, String interaction) {
    recorder.record(new EvidenceRecorder.Fact(orgId, control, id, EvaluationMode.ENFORCING, decision,
        consequence, reason, "draft " + id, "system:drafter", "system", interaction, false, "", 0));
  }
}
