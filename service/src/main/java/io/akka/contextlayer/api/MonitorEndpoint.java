package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.application.DomainAccess;
import io.akka.contextlayer.application.MonitorEntity;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.application.MonitorListView;
import io.akka.contextlayer.application.MonitorRunWorkflow;
import io.akka.contextlayer.application.MonitorTimedAction;
import io.akka.contextlayer.domain.Monitor;
import io.akka.contextlayer.domain.MonitorDefinition;
import io.akka.contextlayer.domain.ScopedId;
import java.time.Duration;
import java.util.List;

/** Saved questions that run on a schedule and open cases for rows that newly match. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context/monitors")
public class MonitorEndpoint extends PrincipalEndpoint {

  public record MonitorRequest(
      String name,
      ContextLayerEndpoint.QuestionRequest question,
      String keyField,
      int intervalMinutes,
      String assistantId,
      String routeTo,
      Boolean onChange) {

    public MonitorRequest(
        String name, ContextLayerEndpoint.QuestionRequest question, String keyField, int intervalMinutes,
        String assistantId, String routeTo) {
      this(name, question, keyField, intervalMinutes, assistantId, routeTo, null);
    }
  }

  public record MonitorResponse(
      String monitorId,
      String name,
      int version,
      boolean enabled,
      int runCount,
      long lastRunAt,
      String lastRunNote,
      List<String> openRowKeys,
      ContextLayerEndpoint.QuestionRequest question,
      String keyField,
      int intervalMinutes,
      String assistantId,
      String routeTo,
      boolean onChange) {}

  public record RunStarted(String runId, String status) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public MonitorEndpoint(ComponentClient componentClient, TimerScheduler timerScheduler, Authz authz, ProjectResolver projects) {
    super(authz, projects, componentClient);
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  @Post("/{monitorId}")
  public MonitorResponse create(String monitorId, MonitorRequest body) {
    var caller = authorize(Permission.OPERATE);
    var principal = caller.subject();
    var definition = toDomain(body).ownedBy(caller.subject(), roleNames(caller));
    requireReadable(caller, definition);
    call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), monitorId))
        .method(MonitorEntity::create)
        .invoke(new MonitorEntity.Create(project(), monitorId, definition, principal)));
    scheduleNow(project(), monitorId);
    return get(monitorId);
  }

  @Post("/{monitorId}/definition")
  public MonitorResponse update(String monitorId, MonitorRequest body) {
    var caller = authorize(Permission.OPERATE);
    var principal = caller.subject();
    var definition = toDomain(body).ownedBy(caller.subject(), roleNames(caller));
    requireReadable(caller, definition);
    call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), monitorId))
        .method(MonitorEntity::update)
        .invoke(new MonitorEntity.Update(definition, principal)));
    return get(monitorId);
  }

  @Post("/{monitorId}/enable")
  public MonitorResponse enable(String monitorId) {
    setEnabled(project(), monitorId, true);
    scheduleNow(project(), monitorId);
    return get(monitorId);
  }

  @Post("/{monitorId}/disable")
  public MonitorResponse disable(String monitorId) {
    setEnabled(project(), monitorId, false);
    timerScheduler.delete(MonitorTimedAction.timerName(project(), monitorId));
    return get(monitorId);
  }

  /** Runs the monitor now, in addition to its schedule. */
  @Post("/{monitorId}/run")
  public RunStarted runNow(String monitorId) {
    authorize(Permission.OPERATE);
    long now = System.currentTimeMillis();
    var runId = call(() -> ScopedId.of(project(), monitorId, "t" + now));
    var status = call(() -> componentClient.forWorkflow(runId)
        .method(MonitorRunWorkflow::start)
        .invoke(new MonitorRunWorkflow.Start(project(), monitorId, now)));
    return new RunStarted(runId, status);
  }

  public record MonitorSummary(
      String monitorId,
      String name,
      boolean enabled,
      int intervalMinutes,
      String assistantId,
      int runCount,
      long lastRunAt,
      String lastRunNote) {}

  public record MonitorList(List<MonitorSummary> items) {}

  @Get
  public MonitorList list() {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(MonitorListView::forOrganization).invoke(project());
    return new MonitorList(rows.items().stream()
        .map(r -> new MonitorSummary(r.monitorId(), r.name(), r.enabled(), r.intervalMinutes(), r.assistantId(),
            r.runCount(), r.lastRunAt(), r.lastRunNote()))
        .sorted((a, b) -> a.monitorId().compareTo(b.monitorId()))
        .toList());
  }

  @Get("/{monitorId}")
  public MonitorResponse get(String monitorId) {
    authorize(Permission.READ);
    var monitor = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), monitorId))
        .method(MonitorEntity::get)
        .invoke());
    if (!monitor.exists()) throw HttpException.notFound();
    var d = monitor.definition();
    return new MonitorResponse(monitorId, d.name(), monitor.version(), monitor.enabled(), monitor.runCount(),
        monitor.lastRunAt(), monitor.lastRunNote(), monitor.known().keySet().stream().sorted().toList(),
        QuestionMapping.toApi(d.question()), d.keyField(), d.intervalMinutes(), d.assistantId(), d.routeTo(),
        d.onChange());
  }

  private void setEnabled(String orgId, String monitorId, boolean enabled) {
    var principal = authorize(Permission.OPERATE).subject();
    call(() -> componentClient.forEventSourcedEntity(ScopedId.of(orgId, monitorId))
        .method(MonitorEntity::setEnabled)
        .invoke(new MonitorEntity.SetEnabled(enabled, principal)));
  }

  private void scheduleNow(String orgId, String monitorId) {
    timerScheduler.createSingleTimer(
        MonitorTimedAction.timerName(orgId, monitorId),
        Duration.ofMillis(1),
        componentClient.forTimedAction().method(MonitorTimedAction::tick)
            .deferred(new MonitorTimedAction.Tick(orgId, monitorId, System.currentTimeMillis())));
  }

  private static List<String> roleNames(io.akka.contextlayer.auth.Caller caller) {
    return DomainAccess.rolesOf(caller).stream().sorted().toList();
  }

  /** A monitor reads on behalf of whoever sets it up, so it may be set up only over data they may read. */
  private void requireReadable(io.akka.contextlayer.auth.Caller caller, MonitorDefinition definition) {
    if (definition.question() != null && definition.question().validate().isEmpty()) {
      DomainGate.require(componentClient, project(), caller, definition.entityTypes());
    }
  }

  private static MonitorDefinition toDomain(MonitorRequest body) {
    if (body == null) throw HttpException.badRequest("a watcher definition is required");
    return new MonitorDefinition(
        body.name(),
        QuestionMapping.toDomain(body.question()),
        body.keyField(),
        body.intervalMinutes(),
        body.assistantId() == null ? "" : body.assistantId(),
        body.routeTo() == null ? "" : body.routeTo(),
        Boolean.TRUE.equals(body.onChange()));
  }
}
