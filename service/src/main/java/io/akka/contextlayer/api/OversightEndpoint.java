package io.akka.contextlayer.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.application.HumanEventTimedAction;
import io.akka.contextlayer.application.AssistantEntity;
import io.akka.contextlayer.application.AssistantListView;
import io.akka.contextlayer.application.EntityStore;
import io.akka.contextlayer.application.HaltEntity;
import io.akka.contextlayer.application.HumanEventEntity;
import io.akka.contextlayer.application.IdentityEntity;
import io.akka.contextlayer.application.IdentityReviews;
import io.akka.contextlayer.application.HumanEventView;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.HumanEvent;
import io.akka.contextlayer.governance.HumanEvents;
import io.akka.contextlayer.governance.HumanState;
import io.akka.contextlayer.governance.Refusal;
import io.akka.contextlayer.governance.Reviewer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the console's human events contract ({@code ai-console/docs/handoff/contracts/human.md}):
 * the situations a person must judge, and the verbs to claim, decide, escalate, send back and
 * withdraw them. The organization is the one the caller's token belongs to.
 *
 * <p>The reviewer is always the authenticated caller. A request body may say {@code reviewer.rehearsal}
 * (a console rehearsing), which can only make the act less real: the same rules are checked, the act
 * is recorded as a rehearsal, and nothing happens to the event or to what waits on it. Who the
 * reviewer is, and which roles they hold, is never taken from the body.
 *
 * <p>Routes the contract marks optional and this service does not offer answer 501.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context/oversight")
public class OversightEndpoint extends PrincipalEndpoint {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;

  public record ReviewerBody(Boolean rehearsal) {}

  public record ActBody(ReviewerBody reviewer) {}

  public record DecisionBody(
      ReviewerBody reviewer, String action, String rationale, List<String> checks, String presentedAt) {}

  public record EscalateBody(ReviewerBody reviewer, String reason) {}

  public record SendBackBody(ReviewerBody reviewer, String requested) {}

  public record WithdrawBody(ReviewerBody reviewer, String reason) {}

  public record HaltBody(ReviewerBody reviewer, String scope, String target, Boolean lift, String reason) {}

  public record ExtendBody(ReviewerBody reviewer, String extra, String reason) {}

  private final ComponentClient componentClient;
  private final EntityStore entityStore;
  private final TimerScheduler timers;

  public OversightEndpoint(ComponentClient componentClient, EntityStore entityStore, TimerScheduler timers, Authz authz, ProjectResolver projects) {
    super(authz, projects);
    this.entityStore = entityStore;
    this.timers = timers;
    this.componentClient = componentClient;
  }

  // ---- reads

  @Get("/events")
  public HttpResponse list() {
    var caller = authorize(Permission.READ);
    var params = requestContext().queryParams();
    var state = params.getString("state").orElse("");
    int limit = Math.max(1, Math.min(MAX_LIMIT, params.getInteger("limit").orElse(DEFAULT_LIMIT)));
    long now = System.currentTimeMillis();

    var rows = switch (state) {
      case "pending" -> byPending(project(), true);
      case "resolved" -> byPending(project(), false);
      default -> componentClient.forView().method(HumanEventView::byOrganization)
          .invoke(new HumanEventView.ByOrganization(project(), MAX_LIMIT)).items();
    };
    var control = params.getString("control").orElse("");
    var kind = params.getString("subject").orElse("");
    var mode = params.getString("mode").orElse("");
    var holder = params.getString("holder").orElse("");
    var routable = "me".equals(params.getString("routable").orElse(""));
    var sla = params.getString("sla").orElse("");
    var me = Reviewer.of(caller, false);

    var events = new ArrayList<HumanEvent>();
    for (var row : rows) {
      if (!control.isEmpty() && !control.equals(row.controlId())) continue;
      if (!kind.isEmpty() && !kind.equals(row.subjectKind())) continue;
      if (!mode.isEmpty() && !mode.equals(row.mode())) continue;
      if (!holder.isEmpty() && !holder.equals(row.holder())) continue;
      if (!state.isEmpty() && !state.equals("pending") && !state.equals("resolved") && !state.equals(row.state())) continue;
      var event = load(row.id());
      if (!event.exists()) continue;
      if (routable && !routableBy(event, me, now)) continue;
      if (event.pending() && coveredByConfirmedLinks(event)) continue;
      if (sla.equals("at-risk") && !event.slaState(now).equals("at-risk")) continue;
      if (sla.equals("overdue") && !event.slaState(now).equals("overdue")) continue;
      events.add(event);
    }
    events.sort(Comparator.comparing((HumanEvent e) -> e.pending() ? 0 : 1)
        .thenComparing(e -> e.pending() ? e.dueAt() : -e.closedAt())
        .thenComparing(HumanEvent::raisedAt));
    return json(StatusCodes.OK, Map.of("events", events.stream().limit(limit).map(e -> HumanEventJson.of(e, now)).toList()));
  }

  @Get("/events/{id}")
  public HttpResponse get(String id) {
    var caller = authorize(Permission.READ);
    var event = owned(project(), id);
    if (event == null) return error(StatusCodes.NOT_FOUND, "event not found");
    return json(StatusCodes.OK, HumanEventJson.of(event, System.currentTimeMillis()));
  }

  /** Every assistant and whether it is halted: the scopes this service's halt controls declare. */
  @Get("/halts")
  public HttpResponse halts() {
    var caller = authorize(Permission.READ);
    var assistants = componentClient.forView().method(AssistantListView::forOrganization).invoke(project()).items();
    var halts = new ArrayList<Map<String, Object>>();
    for (var a : assistants) {
      var state = componentClient.forKeyValueEntity(HaltEntity.id(project(), a.assistantId()))
          .method(HaltEntity::get).invoke();
      var halt = new LinkedHashMap<String, Object>();
      halt.put("scope", "assistant");
      halt.put("target", a.assistantId());
      halt.put("halted", state.halted());
      halt.put("since", state.halted() ? Instant.ofEpochMilli(state.since()).toString() : null);
      halt.put("by", state.halted() ? state.by() : null);
      halt.put("eventId", state.halted() ? state.eventId() : null);
      halt.put("safeStateChecks", List.of());
      halts.add(halt);
    }
    halts.sort(Comparator.comparing(h -> (String) h.get("target")));
    return json(StatusCodes.OK, Map.of("halts", halts));
  }

  @Get("/standing")
  public HttpResponse standing() {
    authorize(Permission.READ);
    return json(StatusCodes.OK, Map.of("standing", List.of()));
  }

  // ---- writes

  @Post("/events/{id}/claim")
  public HttpResponse claim(String id, ActBody body) {
    var caller = authorize(Permission.REVIEW);
    return act(caller, id, body == null ? null : body.reviewer(), (reviewer, now) -> componentClient
        .forEventSourcedEntity(id).method(HumanEventEntity::claim).invoke(new HumanEventEntity.Act(reviewer, now)));
  }

  @Post("/events/{id}/release")
  public HttpResponse release(String id, ActBody body) {
    var caller = authorize(Permission.REVIEW);
    return act(caller, id, body == null ? null : body.reviewer(), (reviewer, now) -> componentClient
        .forEventSourcedEntity(id).method(HumanEventEntity::release).invoke(new HumanEventEntity.Act(reviewer, now)));
  }

  @Post("/events/{id}/decision")
  public HttpResponse decision(String id, DecisionBody body) {
    var caller = authorize(Permission.REVIEW);
    if (body == null) return error(StatusCodes.UNPROCESSABLE_ENTITY, "a request body is required");
    long presentedAt;
    try {
      presentedAt = body.presentedAt() == null || body.presentedAt().isBlank() ? 0 : Instant.parse(body.presentedAt()).toEpochMilli();
    } catch (DateTimeParseException e) {
      return error(StatusCodes.UNPROCESSABLE_ENTITY, "presentedAt must be an ISO-8601 instant");
    }
    return act(caller, id, body.reviewer(), (reviewer, now) -> componentClient.forEventSourcedEntity(id)
        .method(HumanEventEntity::decide)
        .invoke(new HumanEventEntity.Decide(reviewer, body.action(), body.rationale(), body.checks(), presentedAt, now)));
  }

  @Post("/events/{id}/escalate")
  public HttpResponse escalate(String id, EscalateBody body) {
    var caller = authorize(Permission.REVIEW);
    if (body == null) return error(StatusCodes.UNPROCESSABLE_ENTITY, "a request body is required");
    return act(caller, id, body.reviewer(), (reviewer, now) -> componentClient.forEventSourcedEntity(id)
        .method(HumanEventEntity::escalate).invoke(new HumanEventEntity.Escalate(reviewer, body.reason(), now)));
  }

  @Post("/events/{id}/send-back")
  public HttpResponse sendBack(String id, SendBackBody body) {
    var caller = authorize(Permission.REVIEW);
    if (body == null) return error(StatusCodes.UNPROCESSABLE_ENTITY, "a request body is required");
    return act(caller, id, body.reviewer(), (reviewer, now) -> componentClient.forEventSourcedEntity(id)
        .method(HumanEventEntity::sendBack).invoke(new HumanEventEntity.SendBack(reviewer, body.requested(), now)));
  }

  /** The proposer answers a send-back. The proposer need not be a reviewer, so any caller may try; the event checks who they are. */
  @Post("/events/{id}/resubmit")
  public HttpResponse resubmit(String id, ActBody body) {
    var caller = authorize(Permission.READ);
    return act(caller, id, body == null ? null : body.reviewer(), (reviewer, now) -> componentClient
        .forEventSourcedEntity(id).method(HumanEventEntity::resubmit).invoke(new HumanEventEntity.Act(reviewer, now)));
  }

  @Post("/events/{id}/withdraw")
  public HttpResponse withdraw(String id, WithdrawBody body) {
    var caller = authorize(Permission.READ);
    var reason = body == null ? null : body.reason();
    return act(caller, id, body == null ? null : body.reviewer(), (reviewer, now) -> componentClient
        .forEventSourcedEntity(id).method(HumanEventEntity::withdraw).invoke(new HumanEventEntity.Withdraw(reviewer, reason, now)));
  }

  /** Gives the event more time, once, by up to seven days. */
  @Post("/events/{id}/extend")
  public HttpResponse extend(String id, ExtendBody body) {
    var caller = authorize(Permission.REVIEW);
    if (body == null) return error(StatusCodes.UNPROCESSABLE_ENTITY, "a request body is required");
    long extra;
    try {
      extra = java.time.Duration.parse(body.extra() == null ? "" : body.extra()).toMillis();
    } catch (DateTimeParseException e) {
      return error(StatusCodes.UNPROCESSABLE_ENTITY, "extra must be an ISO-8601 duration such as PT4H");
    }
    var answer = act(caller, id, body.reviewer(), (reviewer, now) -> componentClient.forEventSourcedEntity(id)
        .method(HumanEventEntity::extend).invoke(new HumanEventEntity.Extend(reviewer, extra, body.reason(), now)));
    if (answer.status().intValue() == 200 && !(body.reviewer() != null && Boolean.TRUE.equals(body.reviewer().rehearsal()))) {
      var event = load(id);
      // Events nothing else is watching are timed out by a timer; move it. An approval's workflow looks again itself.
      if (!"action".equals(event.subjectKind())) HumanEventTimedAction.schedule(timers, componentClient, id, event.dueAt());
    }
    return answer;
  }

  @Post("/events/{id}/reopen")
  public HttpResponse reopen(String id) {
    authorize(Permission.REVIEW);
    return HttpResponses.notImplemented("reopening is not offered: a closed event has already been acted on");
  }

  @Post("/flags")
  public HttpResponse flag() {
    authorize(Permission.REVIEW);
    return HttpResponses.notImplemented("flags are not offered");
  }

  /**
   * Asks for an assistant to be halted, or restarted when {@code lift}. This raises the human event;
   * the halt happens when the event is decided: one approver for a halt, two for a restart.
   */
  @Post("/halts")
  public HttpResponse halt(HaltBody body) {
    var caller = authorize(Permission.REVIEW);
    if (body == null) return error(StatusCodes.UNPROCESSABLE_ENTITY, "a request body is required");
    if (!"assistant".equals(body.scope())) return error(StatusCodes.UNPROCESSABLE_ENTITY, "scope must be assistant");
    if (body.target() == null || body.target().isBlank()) return error(StatusCodes.UNPROCESSABLE_ENTITY, "target is required");
    if (body.reason() == null || body.reason().isBlank()) return error(StatusCodes.UNPROCESSABLE_ENTITY, "a reason is required");
    boolean lift = Boolean.TRUE.equals(body.lift());
    long now = System.currentTimeMillis();
    String assistantId;
    String id;
    try {
      assistantId = ScopedId.of(project(), body.target());
      id = ScopedId.of(project(), "he", "halt", body.target(), String.valueOf(now));
    } catch (IllegalArgumentException e) {
      return error(StatusCodes.UNPROCESSABLE_ENTITY, "target is not a valid assistant id");
    }
    try {
      componentClient.forEventSourcedEntity(assistantId).method(AssistantEntity::get).invoke();
    } catch (IllegalArgumentException e) { // the assistant entity refuses a read of one that does not exist
      return error(StatusCodes.NOT_FOUND, "playbook not found");
    }
    boolean halted = HaltEntity.isHalted(componentClient, project(), body.target());
    if (lift && !halted) return error(StatusCodes.CONFLICT, "the playbook is not halted");
    if (!lift && halted) return error(StatusCodes.CONFLICT, "the playbook is already halted");

    var verb = lift ? "Restart" : "Halt";
    var raised = HumanEvents.raised(ControlCatalog.current(), lift ? Controls.RESTART : Controls.HALT, id, project(),
        "system", verb + " assistant " + body.target(), body.target(),
        Map.of("scope", "assistant", "target", body.target(), "operation", lift ? "restart" : "halt"),
        "person", verb.toLowerCase() + " requested", body.reason().trim(),
        lift ? "Should playbook " + body.target() + " be allowed to act again?" : "Should playbook " + body.target() + " be stopped?",
        List.of(lift ? "The cause of the halt is understood and removed." : "Stopping it now is better than letting it continue."),
        caller.subject(), now);
    if (body.reviewer() != null && Boolean.TRUE.equals(body.reviewer().rehearsal())) {
      // A rehearsal shows the event that would be raised and raises nothing.
      return json(StatusCodes.OK, HumanEventJson.of(HumanEvent.empty().apply(raised), now));
    }
    var answer = componentClient.forEventSourcedEntity(id).method(HumanEventEntity::raise).invoke(raised);
    HumanEventTimedAction.schedule(timers, componentClient, id, raised.dueAt());
    return json(StatusCodes.OK, HumanEventJson.of(answer.event(), now));
  }

  @Post("/standing/{id}/revoke")
  public HttpResponse revokeStanding(String id) {
    authorize(Permission.REVIEW);
    return HttpResponses.notImplemented("standing decisions are not offered");
  }

  // ---- helpers

  private interface Command {
    HumanEventEntity.Answer run(Reviewer reviewer, long now);
  }

  private HttpResponse act(io.akka.contextlayer.auth.Caller caller, String id, ReviewerBody reviewerBody, Command command) {
    if (!ScopedId.belongsTo(id, project())) return error(StatusCodes.NOT_FOUND, "event not found");
    boolean rehearsal = reviewerBody != null && Boolean.TRUE.equals(reviewerBody.rehearsal());
    long now = System.currentTimeMillis();
    var answer = command.run(Reviewer.of(caller, rehearsal), now);
    if (answer.refusal() != null) return refusal(answer.refusal(), answer.event(), now);
    if (!answer.event().orgId().equals(project())) return error(StatusCodes.NOT_FOUND, "event not found");
    return json(StatusCodes.OK, HumanEventJson.of(answer.event(), now));
  }

  private List<HumanEventView.Row> byPending(String orgId, boolean pending) {
    return componentClient.forView().method(HumanEventView::byPending)
        .invoke(new HumanEventView.ByPending(orgId, pending, MAX_LIMIT)).items();
  }

  private HumanEvent load(String id) {
    return componentClient.forEventSourcedEntity(id).method(HumanEventEntity::get).invoke();
  }

  /** The event, only if it exists and belongs to the organization; the id is caller-supplied. */
  private HumanEvent owned(String orgId, String id) {
    if (!ScopedId.belongsTo(id, orgId)) return null;
    var event = load(id);
    return event.exists() && event.orgId().equals(orgId) ? event : null;
  }

  /**
   * An identity review that confirmed links already settle leaves a person nothing to decide, the
   * same as in the review queue, so it is not listed. It is looked at when listed, so it returns if
   * that cover goes away. The event itself is unchanged and can still be fetched by id.
   */
  private boolean coveredByConfirmedLinks(HumanEvent event) {
    if (!IdentityReviews.KIND.equals(event.subjectKind())) return false;
    var cluster = componentClient.forEventSourcedEntity(event.detail().get("_clusterId"))
        .method(IdentityEntity::get).invoke();
    return entityStore.coveredByConfirmedLinks(event.orgId(), cluster.rule(), cluster.key());
  }

  /** Whether the reviewer could act on the event now: a role it needs, not held by another, not sent back, not already voted. */
  private static boolean routableBy(HumanEvent event, Reviewer reviewer, long now) {
    if (!event.pending()) return false;
    var state = event.effectiveState(now);
    if (state == HumanState.CHANGES_REQUESTED) return false;
    if (state == HumanState.CLAIMED && !event.holder().equals(reviewer.subject())) return false;
    if (event.decisions().stream().anyMatch(d -> d.reviewer().subject().equals(reviewer.subject()))) return false;
    return reviewer.holdsAnyOf(event.currentRoles());
  }

  private HttpResponse refusal(Refusal refusal, HumanEvent event, long now) {
    var status = switch (refusal.kind()) {
      case NOT_FOUND -> StatusCodes.NOT_FOUND;
      case FORBIDDEN -> StatusCodes.FORBIDDEN;
      case CONFLICT -> StatusCodes.CONFLICT;
      case INVALID -> StatusCodes.UNPROCESSABLE_ENTITY;
    };
    var body = new LinkedHashMap<String, Object>();
    body.put("error", refusal.message());
    if (refusal.kind() == Refusal.Kind.CONFLICT) body.put("state", event.exists() ? event.effectiveState(now).name() : null);
    if (refusal.kind() == Refusal.Kind.INVALID) body.put("errors", List.of(refusal.message()));
    return json(status, body);
  }

  private static HttpResponse error(akka.http.javadsl.model.StatusCode status, String message) {
    return json(status, Map.of("error", message));
  }

  private static HttpResponse json(akka.http.javadsl.model.StatusCode status, Object body) {
    try {
      return HttpResponses.of(status, ContentTypes.APPLICATION_JSON, JsonSupport.getObjectMapper().writeValueAsBytes(body));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
