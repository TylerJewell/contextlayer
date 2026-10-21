package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.contextlayer.application.CaseEntity;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.application.CaseView;
import io.akka.contextlayer.application.MonitorEntity;
import io.akka.contextlayer.domain.CaseFile;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import java.util.List;
import java.util.Map;

/** Cases opened by monitors: the list, one case with its timeline, and closing or annotating it. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context/cases")
public class CaseEndpoint extends PrincipalEndpoint {

  public record CaseSummary(String caseId, String monitorId, String key, String status, String routeTo, long openedAt) {}

  public record CaseList(List<CaseSummary> items) {}

  public record TimelineEntry(long at, String actor, String text) {}

  public record CaseDetail(
      String caseId,
      String monitorId,
      int monitorVersion,
      String key,
      String status,
      String routeTo,
      String assistantId,
      Map<String, Object> evidence,
      List<String> sourceNotes,
      List<TimelineEntry> timeline) {}

  public record CloseRequest(String note) {}

  public record AnnotateRequest(String text) {}

  private final ComponentClient componentClient;

  public CaseEndpoint(ComponentClient componentClient, Authz authz, ProjectResolver projects) {
    super(authz, projects, componentClient);
    this.componentClient = componentClient;
  }

  @Get
  public CaseList list() {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(CaseView::forOrganization).invoke(project());
    return toList(rows);
  }

  @Get("/status/{status}")
  public CaseList listByStatus(String status) {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(CaseView::byStatus).invoke(new CaseView.ByStatus(project(), status));
    return toList(rows);
  }

  @Get("/{caseId}")
  public CaseDetail get(String caseId) {
    var caller = authorize(Permission.READ);
    // The id carries the organization it belongs to, so an id from another organization, or one the
    // runtime could not use as an entity id, is simply not found.
    requireOwnProject(ScopedId.belongsTo(caseId, project()), "case", caller);
    var file = call(() -> componentClient.forEventSourcedEntity(caseId).method(CaseEntity::get).invoke());
    if (!file.exists()) throw HttpException.notFound();
    requireOwnProject(file.orgId().equals(project()), "case", caller);
    // A case shows the records its monitor matched, so reading it is reading what the monitor's question reads.
    if (!file.monitorId().isBlank()) {
      var monitor = componentClient.forEventSourcedEntity(ScopedId.of(project(), file.monitorId()))
          .method(MonitorEntity::get).invoke();
      if (monitor.definition() != null) DomainGate.require(componentClient, project(), caller, monitor.definition().entityTypes());
    }
    return toDetail(file);
  }

  /** A reviewer resolves the case: a decision under control custom-cl0046, with the note as its reason. */
  @Post("/{caseId}/resolve")
  public CaseDetail resolve(String caseId, CloseRequest body) {
    return close(caseId, body, "APPROVE", "resolved");
  }

  /** A reviewer dismisses the case: the same decision, denied. */
  @Post("/{caseId}/dismiss")
  public CaseDetail dismiss(String caseId, CloseRequest body) {
    return close(caseId, body, "DENY", "dismissed");
  }

  private CaseDetail close(String caseId, CloseRequest body, String action, String verb) {
    var caller = authorize(Permission.REVIEW);
    get(caseId);
    var eventId = call(() -> ScopedId.of(project(), "he", "case", caseId.substring(caseId.indexOf(':') + 1).replace(':', '_'),
        String.valueOf(System.currentTimeMillis())));
    GateDecisions.decide(componentClient, caller, project(), Controls.CASE_RESOLUTION, eventId, "change",
        "Close case " + caseId + " as " + verb, "", Map.of("caseId", caseId, "outcome", verb),
        "Is case " + caseId + " " + verb + "?", "system:cases", action, note(body));
    var file = GateDecisions.applying(componentClient, eventId, "case " + verb, () -> call(() -> {
      var entity = componentClient.forEventSourcedEntity(caseId);
      var close = new CaseEntity.Close(caller.subject(), note(body), System.currentTimeMillis());
      return "APPROVE".equals(action) ? entity.method(CaseEntity::resolve).invoke(close)
          : entity.method(CaseEntity::dismiss).invoke(close);
    }));
    return toDetail(file);
  }

  @Post("/{caseId}/annotate")
  public CaseDetail annotate(String caseId, AnnotateRequest body) {
    var principal = authorize(Permission.OPERATE).subject();
    get(caseId);
    var file = call(() -> componentClient.forEventSourcedEntity(caseId).method(CaseEntity::annotate)
        .invoke(new CaseEntity.Annotate(principal, body == null ? null : body.text(), System.currentTimeMillis())));
    return toDetail(file);
  }

  private static String note(CloseRequest body) {
    return body == null || body.note() == null ? "" : body.note();
  }

  private static CaseList toList(CaseView.CaseRows rows) {
    return new CaseList(rows.items().stream()
        .map(r -> new CaseSummary(r.caseId(), r.monitorId(), r.key(), r.status(), r.routeTo(), r.openedAt()))
        .toList());
  }

  private static CaseDetail toDetail(CaseFile f) {
    return new CaseDetail(f.caseId(), f.monitorId(), f.monitorVersion(), f.key(), f.status().name(), f.routeTo(),
        f.assistantId(), f.evidence(), f.sourceNotes(),
        f.timeline().stream().map(t -> new TimelineEntry(t.at(), t.actor(), t.text())).toList());
  }
}
