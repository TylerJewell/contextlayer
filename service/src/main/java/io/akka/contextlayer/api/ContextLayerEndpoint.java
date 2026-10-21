package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.javasdk.timer.TimerScheduler;
import io.akka.contextlayer.application.IdentityReviews;
import io.akka.contextlayer.application.ActionLedgerEntity;
import io.akka.contextlayer.application.ActionLedgerView;
import io.akka.contextlayer.application.ActionWorkflow;
import io.akka.contextlayer.application.AssistantEntity;
import io.akka.contextlayer.application.IdentityEntity;
import io.akka.contextlayer.application.IdentityReviewView;
import io.akka.contextlayer.application.ConnectorPackEntity;
import io.akka.contextlayer.application.MaintainedRefreshAction;
import io.akka.contextlayer.application.DomainAccess;
import io.akka.contextlayer.application.EntityRefresher;
import io.akka.contextlayer.application.EntityStore;
import io.akka.contextlayer.application.HumanEventEntity;
import io.akka.contextlayer.application.QuestionDrafter;
import io.akka.contextlayer.application.QuestionService;
import io.akka.contextlayer.application.RefreshStatusEntity;
import io.akka.contextlayer.application.SourceCaller;
import io.akka.contextlayer.application.UnknownOutcomes;
import io.akka.contextlayer.application.Verdicts;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.Assistant;
import io.akka.contextlayer.domain.AssistantStage;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.IdentityRules;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.Playbook;
import io.akka.contextlayer.domain.RefreshStatus;
import io.akka.contextlayer.domain.RiskClass;
import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.Reviewer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The first HTTP surface: assistants and releases, actions with approvals, and identity linking.
 *
 * <p>Every route needs a bearer token; see {@link PrincipalEndpoint#authorize} for what is checked.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context")
public class ContextLayerEndpoint extends PrincipalEndpoint {

  private static final long QUESTION_TIME_BUDGET_MILLIS = 20_000;

  private final ComponentClient componentClient;
  private final EntityStore entityStore;
  private final SourceCaller sourceCaller;
  private final TimerScheduler timerScheduler;

  public ContextLayerEndpoint(
      ComponentClient componentClient, EntityStore entityStore, SourceCaller sourceCaller, TimerScheduler timerScheduler, Authz authz, ProjectResolver projects) {
    super(authz, projects, componentClient);
    this.timerScheduler = timerScheduler;
    this.componentClient = componentClient;
    this.entityStore = entityStore;
    this.sourceCaller = sourceCaller;
  }

  // ---- API types (separate from domain types by design)

  public record CreateAssistantRequest(String purpose) {}

  public record AddReleaseRequest(String definition) {}

  public record ReleaseResponse(String releaseId) {}

  public record StageRequest(AssistantStage stage, String rationale) {}

  /** What a person says when deciding something that needs their approval. */
  public record DecisionRequest(String rationale) {}

  /** @param definition the release's playbook, as it was added */
  public record ReleaseView(String releaseId, boolean approved, String approvedBy, String definition) {}

  /** What is wrong with a playbook; empty when it would be accepted as a release. */
  public record CheckResponse(List<String> problems) {}

  public record SchemaField(String name, String kind) {}

  public record SchemaType(String entityType, List<SchemaField> fields) {}

  /** The record types and fields a playbook step may name, without any of their values. */
  public record SchemaResponse(List<SchemaType> types) {}

  public record AssistantResponse(
      String assistantId,
      String purpose,
      String owner,
      String stage,
      String runningReleaseId,
      List<ReleaseView> releases) {}

  public record ActionRequest(String actionType, String payload) {}

  public record ActionAccepted(String actionKey, String status) {}

  public record ActionResponse(
      String assistantId,
      String actionKey,
      String actionType,
      String status,
      String reason,
      String decidedBy,
      String releaseId) {}

  public record ActionList(List<ActionResponse> items) {}

  /** The reason is the rationale of the decision. */
  public record RejectRequest(String reason) {}

  public record ResolveRequest(boolean succeeded, String note) {}

  public record IngestRequest(
      String entityType, String source, String recordId, Map<String, String> attributes) {}

  public record IdentityMatch(String rule, String status, int memberCount) {}

  public record IngestResponse(List<IdentityMatch> matches) {}

  public record ClusterRef(String rule, String key, String rationale) {}

  /** @param members the records, as "source / type id", so a reviewer can see what is being joined */
  public record ClusterResponse(
      String rule, String key, double confidence, int memberCount, String status, List<String> members) {}

  public record ClusterList(List<ClusterResponse> items) {}

  // ---- assistants

  @Post("/assistants/{assistantId}")
  public AssistantResponse createAssistant(String assistantId, CreateAssistantRequest body) {
    var principal = authorize(Permission.OPERATE).subject();
    required(body);
    call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
        .method(AssistantEntity::create)
        .invoke(new AssistantEntity.Create(project(), assistantId, body.purpose(), principal)));
    return getAssistant(assistantId);
  }

  @Get("/assistants/{assistantId}")
  public AssistantResponse getAssistant(String assistantId) {
    authorize(Permission.READ);
    var assistant = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
        .method(AssistantEntity::get)
        .invoke());
    return toResponse(assistant);
  }

  @Post("/assistants/{assistantId}/releases")
  public ReleaseResponse addRelease(String assistantId, AddReleaseRequest body) {
    var principal = authorize(Permission.OPERATE).subject();
    required(body);
    var entityId = call(() -> ScopedId.of(project(), assistantId));
    String releaseId;
    try {
      releaseId = componentClient.forEventSourcedEntity(entityId)
          .method(AssistantEntity::addRelease)
          .invoke(new AssistantEntity.AddRelease(body.definition(), principal));
    } catch (IllegalArgumentException refused) { // the entity refuses a release that is not a valid playbook
      verdicts().guardrail(project(), Controls.PLAYBOOK_DEFINITION_BOUNDS, Verdicts.once(assistantId), false,
          refused.getMessage(), "release of " + assistantId, principal);
      throw HttpException.badRequest(refused.getMessage());
    }
    verdicts().guardrail(project(), Controls.PLAYBOOK_DEFINITION_BOUNDS, "release~" + assistantId + "~" + releaseId, true,
        "a valid playbook", "release " + releaseId + " of " + assistantId, principal);
    return new ReleaseResponse(releaseId);
  }

  /** Checks a playbook as adding it as a release would, and keeps nothing. */
  @Post("/assistants/{assistantId}/releases/check")
  public CheckResponse checkRelease(String assistantId, AddReleaseRequest body) {
    authorize(Permission.OPERATE);
    required(body);
    if (blank(body.definition())) return new CheckResponse(List.of("the playbook is empty"));
    try {
      Playbook.parse(body.definition());
      return new CheckResponse(List.of());
    } catch (IllegalArgumentException e) {
      return new CheckResponse(List.of(e.getMessage().split("; ")));
    }
  }

  @Post("/assistants/{assistantId}/releases/{releaseId}/approve")
  public AssistantResponse approveRelease(String assistantId, String releaseId, DecisionRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    var assistant = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
        .method(AssistantEntity::get)
        .invoke());
    assistant.validateApprove(releaseId, caller.subject()).ifPresent(error -> {
      throw GateDecisions.refuse(componentClient, caller, project(), Controls.RELEASE_APPROVAL,
          assistantId + " " + releaseId, error);
    });
    var eventId = GateDecisions.decide(componentClient, caller, project(), Controls.RELEASE_APPROVAL,
        eventId("release", project(), assistantId, releaseId + "-" + System.currentTimeMillis()), "change",
        "Make release " + releaseId + " of assistant " + assistantId + " runnable", assistantId,
        Map.of("assistantId", assistantId, "releaseId", releaseId),
        "Should release " + releaseId + " of assistant " + assistantId + " be allowed to run?",
        "system:releases", "APPROVE", body.rationale());
    GateDecisions.applying(componentClient, eventId, "release approved", () -> call(() ->
        componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
            .method(AssistantEntity::approveRelease)
            .invoke(new AssistantEntity.ApproveRelease(releaseId, caller.subject()))));
    return getAssistant(assistantId);
  }

  @Post("/assistants/{assistantId}/stage")
  public AssistantResponse changeStage(String assistantId, StageRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    if (body.stage() == null) throw HttpException.badRequest("stage is required");
    var assistant = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
        .method(AssistantEntity::get)
        .invoke());
    assistant.validateStage(body.stage(), caller.subject()).ifPresent(error -> {
      throw GateDecisions.refuse(componentClient, caller, project(), Controls.STAGE_CHANGE_APPROVAL,
          assistantId + " " + body.stage(), error);
    });
    var eventId = GateDecisions.decide(componentClient, caller, project(), Controls.STAGE_CHANGE_APPROVAL,
        eventId("stage", project(), assistantId, body.stage() + "-" + System.currentTimeMillis()), "change",
        "Move assistant " + assistantId + " to " + body.stage(), assistantId,
        Map.of("assistantId", assistantId, "stage", body.stage().name()),
        "Should playbook " + assistantId + " move to " + body.stage() + "?",
        "system:stages", "APPROVE", body.rationale());
    GateDecisions.applying(componentClient, eventId, "stage changed to " + body.stage(), () -> call(() ->
        componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
            .method(AssistantEntity::changeStage)
            .invoke(new AssistantEntity.ChangeStage(body.stage(), caller.subject()))));
    return getAssistant(assistantId);
  }

  private static String eventId(String kind, String orgId, String first, String second) {
    return call(() -> ScopedId.of(orgId, "he", kind, first, second));
  }

  // ---- actions

  /** Starts the action asynchronously. Read it back to see the policy decision and outcome. */
  @Post("/assistants/{assistantId}/actions/{actionKey}")
  public ActionAccepted requestAction(String assistantId, String actionKey, ActionRequest body) {
    var caller = authorize(Permission.OPERATE);
    required(body);
    if (body.actionType() == null || body.actionType().isBlank()) {
      throw HttpException.badRequest("actionType is required");
    }
    call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId))
        .method(AssistantEntity::get)
        .invoke());
    var status = call(() -> componentClient.forWorkflow(ScopedId.of(project(), assistantId, actionKey))
        .method(ActionWorkflow::start)
        .invoke(new ActionWorkflow.Start(project(), assistantId, actionKey, body.actionType(),
            body.payload() == null ? "" : body.payload(), "", caller.subject())));
    if (!"STARTED".equals(status)) {
      verdicts().guardrail(project(), Controls.EXACTLY_ONCE_ACTIONS, Verdicts.once(actionKey), true,
          "repeat request: the action was not sent again", body.actionType() + " " + actionKey, caller.subject());
    }
    return new ActionAccepted(actionKey, status);
  }

  @Get("/assistants/{assistantId}/actions/{actionKey}")
  public ActionResponse getAction(String assistantId, String actionKey) {
    authorize(Permission.READ);
    var record = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), assistantId, actionKey))
        .method(ActionLedgerEntity::get)
        .invoke());
    return new ActionResponse(record.assistantId(), record.actionKey(), record.actionType(), record.status().name(),
        record.reason(), record.decidedBy(), record.releaseId());
  }

  @Get("/assistants/{assistantId}/actions")
  public ActionList listActions(String assistantId) {
    authorize(Permission.READ);
    var rows = componentClient.forView()
        .method(ActionLedgerView::byAssistant)
        .invoke(new ActionLedgerView.ByAssistant(project(), assistantId));
    return toList(rows);
  }

  @Get("/approvals")
  public ActionList pendingApprovals() {
    authorize(Permission.READ);
    var rows = componentClient.forView()
        .method(ActionLedgerView::byStatus)
        .invoke(new ActionLedgerView.ByStatus(project(), "AWAITING_APPROVAL"));
    return toList(rows);
  }

  @Post("/assistants/{assistantId}/actions/{actionKey}/approve")
  public ActionAccepted approveAction(String assistantId, String actionKey, DecisionRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    decideAction(project(), assistantId, actionKey, caller, "APPROVE", body.rationale());
    return new ActionAccepted(actionKey, "DECIDED");
  }

  @Post("/assistants/{assistantId}/actions/{actionKey}/reject")
  public ActionAccepted rejectAction(String assistantId, String actionKey, RejectRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    decideAction(project(), assistantId, actionKey, caller, "DENY", body.reason());
    return new ActionAccepted(actionKey, "DECIDED");
  }

  /**
   * Decides the action's approval event as the caller: claims it first, because a claim is when the
   * person is shown the event and the minimum review time counts from it, then decides. The
   * workflow acts on the decision when the event reports it; nothing here reaches the workflow.
   */
  private void decideAction(
      String orgId, String assistantId, String actionKey, io.akka.contextlayer.auth.Caller caller, String action,
      String rationale) {
    var eventId = call(() -> ActionWorkflow.humanEventId(orgId, assistantId, actionKey));
    decideEvent(eventId, caller, action, rationale, "action is not awaiting approval");
  }

  /** Claims the event if its control measures review time and the caller does not hold it, then decides it. */
  private void decideEvent(
      String eventId, io.akka.contextlayer.auth.Caller caller, String action, String rationale, String ifMissing) {
    var event = componentClient.forEventSourcedEntity(eventId).method(HumanEventEntity::get).invoke();
    if (!event.exists()) throw HttpException.badRequest(ifMissing);
    var reviewer = Reviewer.of(caller, false);
    if (event.policy().minimumReviewSeconds() > 0 && !(event.holder().equals(caller.subject()) && event.pending())) {
      var claimed = componentClient.forEventSourcedEntity(eventId).method(HumanEventEntity::claim)
          .invoke(new HumanEventEntity.Act(reviewer, System.currentTimeMillis()));
      if (claimed.refusal() != null) {
        verdicts().humanRefusal(claimed.event(), caller.subject(), "claim", claimed.refusal(), false);
        throw GateDecisions.toException(claimed.refusal());
      }
    }
    var decided = componentClient.forEventSourcedEntity(eventId).method(HumanEventEntity::decide)
        .invoke(new HumanEventEntity.Decide(reviewer, action, rationale, List.of(), 0, System.currentTimeMillis()));
    if (decided.refusal() != null) {
      verdicts().humanRefusal(decided.event(), caller.subject(), "decide", decided.refusal(), false);
      throw GateDecisions.toException(decided.refusal());
    }
  }

  /**
   * A person records what really happened after an action whose outcome was unknown, by deciding its human event
   * (control custom-cl0044): approve if it took effect, deny if not. The ledger changes when the decision is applied.
   */
  @Post("/assistants/{assistantId}/actions/{actionKey}/resolve")
  public ActionResponse resolveUnknown(String assistantId, String actionKey, ResolveRequest body) {
    var caller = authorize(Permission.REVIEW);
    required(body);
    var eventId = call(() -> UnknownOutcomes.eventId(project(), assistantId, actionKey));
    decideEvent(eventId, caller, body.succeeded() ? "APPROVE" : "DENY", body.note(), "the action's outcome is not unknown");
    return getAction(assistantId, actionKey);
  }

  // ---- connector packs

  public record PackRequest(
      String name,
      String version,
      SourceRequest source,
      String credentialRef,
      List<EntityRequest> entities,
      List<PackActionRequest> actions) {}

  public record SourceRequest(String kind, String ref) {}

  public record EntityRequest(
      String type,
      String operation,
      String recordsPath,
      String idField,
      List<String> identityAttributes,
      List<String> filters,
      FreshnessRequest freshness,
      PagingRequest paging,
      RateRequest rate,
      List<RelationRequest> relations) {

    public EntityRequest(
        String type, String operation, String recordsPath, String idField, List<String> identityAttributes,
        List<String> filters, FreshnessRequest freshness, PagingRequest paging, RateRequest rate) {
      this(type, operation, recordsPath, idField, identityAttributes, filters, freshness, paging, rate, null);
    }
  }

  public record RelationRequest(String name, String field, String targetType) {}

  public record FreshnessRequest(String policy, int ttlMinutes) {}

  public record PagingRequest(
      String style,
      int maxPageSize,
      String pageSizeParam,
      String cursorParam,
      String nextCursorPath,
      String pageParam) {}

  public record RateRequest(int callsPerMinute) {}

  public record PackActionRequest(String name, String operation, RiskClass risk) {}

  public record PackVersionResponse(String hash) {}

  public record ActivateRequest(ConnectorPack.Mode mode, String rationale) {}

  public record PackVersionView(String hash, String version, String importedBy) {}

  public record PackResponse(String name, String mode, String activeHash, List<PackVersionView> versions) {}

  @Post("/connectors/{packName}/versions")
  public PackVersionResponse importPack(String packName, PackRequest body) {
    var principal = authorize(Permission.OPERATE).subject();
    var entityId = call(() -> ScopedId.of(project(), packName));
    String hash;
    try {
      hash = componentClient.forEventSourcedEntity(entityId)
          .method(ConnectorPackEntity::importVersion)
          .invoke(new ConnectorPackEntity.Import(project(), packName, toDomain(body), principal));
    } catch (IllegalArgumentException refused) { // the entity refuses a pack that does not validate
      verdicts().guardrail(project(), Controls.VALID_CONNECTOR_PACKS, Verdicts.once(packName), false,
          refused.getMessage(), "connector " + packName, principal);
      throw HttpException.badRequest(refused.getMessage());
    }
    verdicts().guardrail(project(), Controls.VALID_CONNECTOR_PACKS, "pack~" + packName + "~" + hash, true,
        "a valid connector pack", "version " + hash + " of connector " + packName, principal);
    return new PackVersionResponse(hash);
  }

  @Post("/connectors/{packName}/versions/{hash}/activate")
  public PackResponse activatePack(String packName, String hash, ActivateRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    var pack = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), packName))
        .method(ConnectorPackEntity::get)
        .invoke());
    pack.validateActivate(hash, body.mode(), caller.subject()).ifPresent(error -> {
      throw GateDecisions.refuse(componentClient, caller, project(), Controls.PACK_ACTIVATION, packName + " " + hash,
          error);
    });
    var importer = pack.find(hash).map(v -> v.importedBy()).orElse("");
    var eventId = GateDecisions.decide(componentClient, caller, project(), Controls.PACK_ACTIVATION,
        eventId("pack", project(), packName, hash + "-" + System.currentTimeMillis()), "change",
        "Put version " + hash + " of connector " + packName + " in mode " + body.mode(), "",
        Map.of("pack", packName, "hash", hash, "mode", String.valueOf(body.mode())),
        "Should version " + hash + " of connector " + packName + " go " + body.mode() + "?",
        importer, "APPROVE", body.rationale());
    GateDecisions.applying(componentClient, eventId, "connector set to " + body.mode(), () -> call(() ->
        componentClient.forEventSourcedEntity(ScopedId.of(project(), packName))
            .method(ConnectorPackEntity::activate)
            .invoke(new ConnectorPackEntity.Activate(hash, body.mode(), caller.subject()))));
    var activated = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), packName))
        .method(ConnectorPackEntity::get).invoke());
    MaintainedRefreshAction.start(timerScheduler, componentClient, project(), activated);
    return getPack(packName);
  }

  /** Takes a connector out of service, as an approver's decision with a reason (control custom-cl0041). */
  @Post("/connectors/{packName}/deactivate")
  public PackResponse deactivatePack(String packName, DecisionRequest body) {
    var caller = authorize(Permission.APPROVE);
    var before = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), packName))
        .method(ConnectorPackEntity::get).invoke());
    if (!before.exists()) throw HttpException.notFound();
    var eventId = GateDecisions.decide(componentClient, caller, project(), Controls.PACK_DEACTIVATION,
        eventId("pack-off", project(), packName, String.valueOf(System.currentTimeMillis())), "change",
        "Take connector " + packName + " out of service", "", Map.of("pack", packName),
        "Should connector " + packName + " stop being used?", "system:connectors", "APPROVE",
        body == null ? null : body.rationale());
    MaintainedRefreshAction.stop(timerScheduler, project(), before);
    GateDecisions.applying(componentClient, eventId, "connector deactivated", () -> call(() ->
        componentClient.forEventSourcedEntity(ScopedId.of(project(), packName))
            .method(ConnectorPackEntity::deactivate)
            .invoke(caller.subject())));
    return getPack(packName);
  }

  @Get("/connectors/{packName}")
  public PackResponse getPack(String packName) {
    authorize(Permission.READ);
    var pack = call(() -> componentClient.forEventSourcedEntity(ScopedId.of(project(), packName))
        .method(ConnectorPackEntity::get)
        .invoke());
    if (!pack.exists()) throw HttpException.notFound();
    var versions = pack.versions().stream()
        .map(v -> new PackVersionView(v.hash(), v.definition().version(), v.importedBy()))
        .toList();
    return new PackResponse(pack.name(), pack.mode().name(), pack.activeHash(), versions);
  }

  private static PackDefinition toDomain(PackRequest r) {
    if (r == null) return null;
    return new PackDefinition(
        r.name(), r.version(),
        r.source() == null ? null : new PackDefinition.Source(r.source().kind(), r.source().ref()),
        r.credentialRef(),
        r.entities() == null ? null : r.entities().stream().map(e -> e == null ? null
            : new PackDefinition.EntityMapping(e.type(), e.operation(), e.recordsPath(), e.idField(), e.identityAttributes(),
                e.filters(),
                e.freshness() == null ? null : new PackDefinition.Freshness(e.freshness().policy(), e.freshness().ttlMinutes()),
                e.paging() == null ? null : new PackDefinition.Paging(e.paging().style(), e.paging().maxPageSize(), e.paging().pageSizeParam(),
                    e.paging().cursorParam(), e.paging().nextCursorPath(), e.paging().pageParam()),
                e.rate() == null ? null : new PackDefinition.Rate(e.rate().callsPerMinute()),
                e.relations() == null ? List.of() : e.relations().stream()
                    .map(rel -> new PackDefinition.Relation(rel.name(), rel.field(), rel.targetType())).toList())).toList(),
        r.actions() == null ? null : r.actions().stream().map(a -> a == null ? null
            : new PackDefinition.ActionDef(a.name(), a.operation(), a.risk())).toList());
  }

  // ---- identity

  @Post("/records")
  public IngestResponse ingest(IngestRequest body) {
    var caller = authorize(Permission.OPERATE);
    required(body);
    if (blank(body.entityType()) || blank(body.source()) || blank(body.recordId())) {
      throw HttpException.badRequest("entityType, source and recordId are required");
    }
    if (body.attributes() == null) throw HttpException.badRequest("attributes are required");
    DomainGate.require(componentClient, project(), caller, List.of(body.entityType()));
    var matches = new ArrayList<IdentityMatch>();
    for (var candidate : IdentityRules.candidates(body.attributes())) {
      var cluster = call(() -> componentClient
          .forEventSourcedEntity(clusterId(project(), candidate.rule(), candidate.key()))
          .method(IdentityEntity::addMember)
          .invoke(new IdentityEntity.AddMember(project(), candidate.rule(), candidate.key(), candidate.confidence(),
              body.source(), body.entityType(), body.recordId(), caller.subject())));
      matches.add(new IdentityMatch(candidate.rule(), cluster.status().name(), cluster.members().size()));
    }
    return new IngestResponse(matches);
  }

  @Get("/identity/review")
  public ClusterList reviewQueue() {
    authorize(Permission.READ);
    var rows = componentClient.forView()
        .method(IdentityReviewView::byStatus)
        .invoke(new IdentityReviewView.ByStatus(project(), "PENDING_REVIEW"));
    // A match that stronger, confirmed links already cover leaves a person nothing to decide, so it
    // is not asked about. Looked at when listed, so it returns to the queue if that cover goes away.
    var items = rows.items().stream()
        .filter(r -> !entityStore.coveredByConfirmedLinks(project(), r.rule(), r.key()))
        .map(r -> {
          var cluster = componentClient.forEventSourcedEntity(clusterId(project(), r.rule(), r.key()))
              .method(IdentityEntity::get).invoke();
          return new ClusterResponse(r.rule(), r.key(), r.confidence(), r.memberCount(), r.status(), memberNames(cluster));
        })
        .toList();
    return new ClusterList(items);
  }

  public record OpenedReview(String eventId, int minimumReviewSeconds, long openedAt) {}

  /**
   * Shows a cluster to the caller for review: raises its human event if needed and claims it for
   * them. The claim is when review time starts, so a console calls this when it puts the cluster in
   * front of a person and confirms or rejects only after they have looked.
   */
  @Post("/identity/open")
  public OpenedReview openReview(ClusterRef ref) {
    var caller = authorize(Permission.REVIEW);
    requireCluster(ref);
    var clusterId = call(() -> clusterId(project(), ref.rule(), ref.key()));
    var cluster = call(() -> componentClient.forEventSourcedEntity(clusterId).method(IdentityEntity::get).invoke());
    cluster.validateReview(caller.subject()).ifPresent(error -> {
      throw HttpException.badRequest(error);
    });
    var eventId = IdentityReviews.raise(componentClient, timerScheduler, cluster);
    var answer = componentClient.forEventSourcedEntity(eventId).method(HumanEventEntity::claim)
        .invoke(new HumanEventEntity.Act(Reviewer.of(caller, false), System.currentTimeMillis()));
    if (answer.refusal() != null) {
      verdicts().humanRefusal(answer.event(), caller.subject(), "claim", answer.refusal(), false);
      throw GateDecisions.toException(answer.refusal());
    }
    return new OpenedReview(eventId, answer.event().policy().minimumReviewSeconds(), answer.event().claimedAt());
  }

  @Post("/identity/confirm")
  public ClusterResponse confirmCluster(ClusterRef ref) {
    var caller = authorize(Permission.REVIEW);
    requireCluster(ref);
    return reviewCluster(project(), ref, caller, "APPROVE", "confirmed");
  }

  @Post("/identity/reject")
  public ClusterResponse rejectCluster(ClusterRef ref) {
    var caller = authorize(Permission.REVIEW);
    requireCluster(ref);
    return reviewCluster(project(), ref, caller, "DENY", "rejected");
  }

  /**
   * A reviewer's decision on whether the records of a cluster are one entity, recorded as a human
   * event under their authority before the link is confirmed or rejected.
   */
  private ClusterResponse reviewCluster(
      String orgId, ClusterRef ref, io.akka.contextlayer.auth.Caller caller, String action, String verb) {
    var clusterId = call(() -> clusterId(orgId, ref.rule(), ref.key()));
    var cluster = call(() -> componentClient.forEventSourcedEntity(clusterId).method(IdentityEntity::get).invoke());
    cluster.validateReview(caller.subject()).ifPresent(error -> {
      throw HttpException.badRequest(error);
    });
    // The review is a human event like an action approval: claimed, then decided. Raising it here
    // too means a review is never missing because the queue has not caught up.
    var eventId = IdentityReviews.raise(componentClient, timerScheduler, cluster);
    decideEvent(eventId, caller, action, ref.rationale(), "the records are not awaiting review");
    // The link changes when the decision is applied, so this is the cluster as it stands now.
    return toResponse(cluster);
  }

  // ---- questions and refresh

  public record ConditionRequest(String field, String op, Object value) {}

  /** With no {@code via}, entities that share a confirmed identity. With one, entities reached by the named relationship. */
  public record LinkRequest(
      String entityType, List<ConditionRequest> where, String via, String direction, Integer depth) {

    public LinkRequest(String entityType, List<ConditionRequest> where) {
      this(entityType, where, null, null, null);
    }
  }

  /**
   * @param draftId set when the question was drafted by the model (it comes back in the draft): running it then
   *     records that a person ran the draft (control custom-cl0043)
   */
  public record QuestionRequest(
      String entityType,
      List<ConditionRequest> where,
      List<LinkRequest> links,
      List<String> returnFields,
      Integer limit,
      String draftId) {

    public QuestionRequest(
        String entityType, List<ConditionRequest> where, List<LinkRequest> links, List<String> returnFields, Integer limit) {
      this(entityType, where, links, returnFields, limit, null);
    }
  }

  public record SourceReportView(
      String pack, String entityType, String freshness, String lastOutcome, long dataAsOf, List<String> warnings) {}

  public record AnswerResponse(List<Map<String, Object>> rows, long unconfirmedLeftOut, List<SourceReportView> sources) {}

  public record RefreshResponse(
      String pack, String entityType, String outcome, long refreshedAt, int stored, int skippedWithoutId,
      List<String> warnings) {}

  @Post("/questions")
  public AnswerResponse ask(QuestionRequest body) {
    var caller = authorize(Permission.OPERATE);
    var question = QuestionMapping.toDomain(body);
    if (question.validate().isEmpty()) {
      DomainGate.require(componentClient, project(), caller, DomainAccess.typesOf(question));
    }
    if (body.draftId() != null && !body.draftId().isBlank()) recordPersonRuns(caller, body.draftId());
    var service = QuestionService.standard(componentClient, entityStore, sourceCaller, QUESTION_TIME_BUDGET_MILLIS);
    var answer = call(() -> service.ask(project(), question));
    var sources = answer.sources().stream()
        .map(s -> new SourceReportView(s.pack(), s.entityType(), s.freshness().name(), s.lastOutcome().name(),
            s.dataAsOf(), s.warnings()))
        .toList();
    return new AnswerResponse(answer.rows(), answer.unconfirmedLeftOut(), sources);
  }

  /**
   * A person runs a question the model drafted: a decision under {@link Controls#PERSON_RUNS_QUESTION}. The draft must
   * be one this service made and checked; the person may have changed it before running it.
   */
  private void recordPersonRuns(io.akka.contextlayer.auth.Caller caller, String draftId) {
    var checked = call(() -> componentClient
        .forEventSourcedEntity(ScopedId.of(project(), "ev", Controls.DRAFT_SCHEMA_CHECK, ScopedId.safe(draftId)))
        .method(io.akka.contextlayer.application.EvidenceEntity::get).invoke().evidence());
    if (checked == null || !"PASS".equals(checked.decision())) {
      throw HttpException.badRequest("draftId names no draft this service made and checked");
    }
    long now = System.currentTimeMillis();
    GateDecisions.decide(componentClient, caller, project(), Controls.PERSON_RUNS_QUESTION,
        eventId("run", project(), ScopedId.safe(draftId), String.valueOf(now)), "change",
        "Run the question drafted as " + draftId, "", Map.of("draftId", draftId),
        "Run the drafted question?", "system:drafter", "APPROVE", null);
  }

  public record DraftRequest(String text) {}

  /**
   * @param question the question the model drafted, to be read and run by a person; null when it asked
   *     for clarification or could not draft one that passes the checks
   */
  public record DraftResponse(QuestionRequest question, String explanation, String clarification, List<String> problems) {}

  /**
   * Turns a request in words into a question. The model sees the shape of the data and never a
   * record, and this does not run the question: the person reads it and runs it.
   */
  @Post("/questions/draft")
  public DraftResponse draftQuestion(DraftRequest body) {
    var caller = authorize(Permission.OPERATE);
    required(body);
    if (blank(body.text())) throw HttpException.badRequest("text is required");
    if (body.text().length() > QuestionDrafter.MAX_REQUEST) {
      throw HttpException.badRequest("text is at most " + QuestionDrafter.MAX_REQUEST + " characters");
    }
    var drafted = call(() -> new QuestionDrafter(componentClient, entityStore).draft(project(), body.text().trim(), caller));
    return new DraftResponse(drafted.question() == null ? null : QuestionMapping.toApi(drafted.question(), drafted.draftId()),
        drafted.explanation(), drafted.clarification(), drafted.problems());
  }

  /**
   * The types and fields a caller may name in a playbook step: the ones read so far, in the data domains the caller
   * is granted. Names and kinds only; a value is never returned.
   */
  @Get("/schema")
  public SchemaResponse schema() {
    var caller = authorize(Permission.READ);
    var readable = DomainAccess.config(componentClient, project()).readableTypes(caller.subject(), DomainAccess.rolesOf(caller));
    return new SchemaResponse(entityStore.schema(project()).stream()
        .filter(t -> readable.isEmpty() || readable.get().contains(t.entityType()))
        .map(t -> new SchemaType(t.entityType(), t.fields().stream().map(f -> new SchemaField(f.name(), f.kind())).toList()))
        .toList());
  }

  /** Reads the entity type from its source now, whatever its freshness policy. */
  @Post("/connectors/{packName}/entities/{entityType}/refresh")
  public RefreshResponse refresh(String packName, String entityType) {
    var caller = authorize(Permission.OPERATE);
    DomainGate.require(componentClient, project(), caller, List.of(entityType));
    var refresher = new EntityRefresher(componentClient, sourceCaller, entityStore, System::currentTimeMillis);
    return toResponse(call(() -> refresher.refresh(project(), packName, entityType, Map.of())));
  }

  @Get("/connectors/{packName}/entities/{entityType}/refresh-status")
  public RefreshResponse refreshStatus(String packName, String entityType) {
    authorize(Permission.READ);
    var status = call(() -> componentClient.forKeyValueEntity(ScopedId.of(project(), packName, entityType))
        .method(RefreshStatusEntity::get)
        .invoke());
    if (!status.exists()) throw HttpException.notFound();
    return toResponse(status);
  }

  private static RefreshResponse toResponse(RefreshStatus s) {
    return new RefreshResponse(s.packName(), s.entityType(), s.outcome().name(), s.refreshedAt(), s.stored(),
        s.skippedWithoutId(), s.warnings());
  }

  // ---- helpers

  /** The key is the matched text, which may hold anything, so it is made safe to use inside an id. */
  private static String clusterId(String orgId, String rule, String key) {
    return ScopedId.of(orgId, rule, ScopedId.safe(key));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static void requireCluster(ClusterRef ref) {
    required(ref);
    if (blank(ref.rule()) || blank(ref.key())) throw HttpException.badRequest("rule and key are required");
  }

  private static AssistantResponse toResponse(Assistant a) {
    var running = a.runnableRelease().map(Assistant.Release::releaseId).orElse("");
    var releases = a.releases().stream()
        .map(r -> new ReleaseView(r.releaseId(), r.approved(), r.approvedBy(), r.definition()))
        .toList();
    return new AssistantResponse(a.assistantId(), a.purpose(), a.owner(), a.stage().name(), running, releases);
  }

  private static List<String> memberNames(io.akka.contextlayer.domain.IdentityCluster c) {
    return c.members().stream().map(m -> m.source() + " / " + m.entityType() + " " + m.sourceRecordId()).toList();
  }

  private static ClusterResponse toResponse(io.akka.contextlayer.domain.IdentityCluster c) {
    return new ClusterResponse(c.rule(), c.key(), c.confidence(), c.members().size(), c.status().name(), memberNames(c));
  }

  private static ActionList toList(ActionLedgerView.ActionRows rows) {
    return new ActionList(rows.items().stream()
        .map(r -> new ActionResponse(r.assistantId(), r.actionKey(), r.actionType(), r.status(), r.reason(), r.decidedBy(), ""))
        .toList());
  }
}
