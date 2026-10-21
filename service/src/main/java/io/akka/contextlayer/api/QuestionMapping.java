package io.akka.contextlayer.api;

import akka.javasdk.http.HttpException;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.util.List;

/** Turns the API's question shape into the domain's, refusing operators it does not know. */
final class QuestionMapping {

  private static final int DEFAULT_LIMIT = 100;

  private QuestionMapping() {}

  static StructuredQuestion toDomain(ContextLayerEndpoint.QuestionRequest r) {
    if (r == null) throw HttpException.badRequest("a question is required");
    return new StructuredQuestion(
        r.entityType(),
        conditions(r.where()),
        r.links() == null ? List.of() : r.links().stream()
            .map(l -> new StructuredQuestion.Link(l.entityType(), conditions(l.where()), l.via(), direction(l.direction()),
                l.depth() == null ? 1 : l.depth())).toList(),
        r.returnFields(),
        r.limit() == null ? DEFAULT_LIMIT : r.limit());
  }

  private static StructuredQuestion.Direction direction(String text) {
    if (text == null || text.isBlank()) return StructuredQuestion.Direction.OUT;
    try {
      return StructuredQuestion.Direction.valueOf(text.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw HttpException.badRequest("unknown direction '" + text + "'; use OUT or IN");
    }
  }

  private static List<StructuredQuestion.Condition> conditions(List<ContextLayerEndpoint.ConditionRequest> list) {
    if (list == null) return List.of();
    return list.stream().map(c -> {
      try {
        return new StructuredQuestion.Condition(c.field(), StructuredQuestion.Op.valueOf(c.op().toUpperCase()), c.value());
      } catch (IllegalArgumentException | NullPointerException e) {
        throw HttpException.badRequest("unknown operator '" + c.op() + "'; use EQ, NEQ, GT, GTE, LT or LTE");
      }
    }).toList();
  }

  /** The API shape of a stored question, for reading a monitor back. */
  static ContextLayerEndpoint.QuestionRequest toApi(StructuredQuestion q) {
    return toApi(q, null);
  }

  /** The API shape of a question; a drafted one carries its draft's id. */
  static ContextLayerEndpoint.QuestionRequest toApi(StructuredQuestion q, String draftId) {
    return new ContextLayerEndpoint.QuestionRequest(
        q.entityType(),
        api(q.where()),
        q.links().stream().map(l -> new ContextLayerEndpoint.LinkRequest(l.entityType(), api(l.where()),
            l.byIdentity() ? null : l.via(), l.byIdentity() ? null : l.direction().name(), l.byIdentity() ? null : l.depth())).toList(),
        q.returnFields(),
        q.limit(),
        draftId);
  }

  private static List<ContextLayerEndpoint.ConditionRequest> api(List<StructuredQuestion.Condition> list) {
    return list.stream()
        .map(c -> new ContextLayerEndpoint.ConditionRequest(c.field(), c.op().name(), c.value()))
        .toList();
  }
}
