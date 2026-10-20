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
import io.akka.contextlayer.domain.CaseFile;
import io.akka.contextlayer.domain.ScopedId;
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
    super(authz, projects);
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
    authorize(Permission.READ);
    // The id carries the organization it belongs to, so an id from another organization, or one the
    // runtime could not use as an entity id, is simply not found.
    if (!ScopedId.belongsTo(caseId, project())) throw HttpException.notFound();
    var file = call(() -> componentClient.forEventSourcedEntity(caseId).method(CaseEntity::get).invoke());
    if (!file.exists() || !file.orgId().equals(project())) throw HttpException.notFound();
    return toDetail(file);
  }

  @Post("/{caseId}/resolve")
  public CaseDetail resolve(String caseId, CloseRequest body) {
    var principal = authorize(Permission.REVIEW).subject();
    get(caseId);
    var file = call(() -> componentClient.forEventSourcedEntity(caseId).method(CaseEntity::resolve)
        .invoke(new CaseEntity.Close(principal, note(body), System.currentTimeMillis())));
    return toDetail(file);
  }

  @Post("/{caseId}/dismiss")
  public CaseDetail dismiss(String caseId, CloseRequest body) {
    var principal = authorize(Permission.REVIEW).subject();
    get(caseId);
    var file = call(() -> componentClient.forEventSourcedEntity(caseId).method(CaseEntity::dismiss)
        .invoke(new CaseEntity.Close(principal, note(body), System.currentTimeMillis())));
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
