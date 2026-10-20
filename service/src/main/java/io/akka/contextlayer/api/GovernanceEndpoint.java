package io.akka.contextlayer.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.contextlayer.application.ChainLinkEntity;
import io.akka.contextlayer.application.ComplianceAnswerer;
import io.akka.contextlayer.application.ControlClaimsEntity;
import io.akka.contextlayer.application.DocumentEntity;
import io.akka.contextlayer.application.DocumentView;
import io.akka.contextlayer.application.Documents;
import io.akka.contextlayer.application.EvidenceEntity;
import io.akka.contextlayer.application.IntegrityChainEntity;
import io.akka.contextlayer.application.IntegrityConsumer;
import io.akka.contextlayer.application.EvidenceRecorder;
import io.akka.contextlayer.application.EvidenceView;
import io.akka.contextlayer.application.WaiverEntity;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Compliance;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.ControlClaims;
import io.akka.contextlayer.governance.ControlDef;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.Integrity;
import io.akka.contextlayer.governance.RuntimeEvidence;
import io.akka.contextlayer.governance.TrustDocument;
import io.akka.contextlayer.governance.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * What the service enforces and what shows it did: the control catalog with its digest, the
 * evidence each control produced, and where each control stands. Evidence is exported as it was
 * recorded; nothing here can change it.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context/governance")
public class GovernanceEndpoint extends PrincipalEndpoint {

  /** How recent evidence must be to count. */
  static final Duration FRESHNESS = Duration.ofDays(7);
  private static final Duration LOOKBACK = Duration.ofDays(90);
  private static final int MAX_EVIDENCE = 1000;
  private static final int MAX_CHECK = 5000;
  /** Records of who said what about a control; they are evidence of the saying, not of the control running. */
  private static final java.util.Set<String> CLAIM_EVENTS =
      java.util.Set.of("ControlAccepted", "ControlAcceptanceWithdrawn", "EvidenceAsserted", "EvidenceAttached");
  private static final Duration MAX_WAIVER = Duration.ofDays(365);

  public record HumanSummary(String flavor, String mode, List<String> roles, String reviewMode, int required,
      String slaTarget, boolean rationaleRequired, int minimumReviewSeconds, boolean enforceMinimum,
      List<String> allowedActions) {}

  public record ControlView(String rowId, String name, String mechanism, String lifecycle, String corpusRef,
      String summary, String proofEvent, String boundary, HumanSummary human) {}

  public record ControlList(String systemId, String digest, List<ControlView> controls) {}

  public record EvidenceItem(String evidenceId, String controlId, String eventType, String boundary, String evaluationMode,
      String decision, String runtimeConsequence, String reasonCode, String subject, String actor, String authority,
      String bundleDigest, String ref, boolean flagged, String verdict, String recordedAt) {}

  public record EvidenceExport(String catalogDigest, int count, boolean truncated, List<EvidenceItem> evidence) {}

  /**
   * The binding: who accepted the control for this project, and whether that was of the catalog as it
   * stands. It says nothing about whether the control works.
   */
  public record AcceptanceView(
      boolean accepted, boolean current, String by, String authority, String at, String catalogDigest, String rationale) {}

  public record ExpectationView(String name, int maxAgeDays) {}

  /** The assertion: which kind of record counts as evidence for the control, by whom, and what else it is expected to be backed by. */
  public record AssertionView(
      boolean asserted, int revision, String evidenceKind, String by, String authority, String at, String basisDigest,
      String rationale, List<ExpectationView> expects) {}

  public record CoverageView(String coverage, List<String> unmet) {}

  public record ControlStanding(String controlId, String name, String mechanism, String status, String why,
      long enforcing, long upheld, long breached, long flagged, String lastEnforcingAt, AcceptanceView acceptance,
      AssertionView assertion, CoverageView coverage) {}

  /** Controls that fall between the claims, by id: the sets that only exist as differences. */
  public record Gaps(
      List<String> unaccepted, List<String> acceptedWithoutAssertion, List<String> assertedWithoutEvidence,
      List<String> coveragePartial) {}

  public record ComplianceReport(
      String catalogDigest, String asOf, int freshnessDays, List<ControlStanding> controls, Gaps gaps) {}

  /**
   * What checking the evidence chain found, for the stretch that was asked for. {@code head} and {@code count}
   * are the whole chain, and are what an outside party should keep to be able to show later that nothing before
   * them was changed.
   */
  public record IntegrityReport(
      String scheme, long count, String head, long from, long checked, boolean intact, List<String> problems, Long next) {}

  /** @param scheme HashChained or NoIntegrity; the names are those of the runtime evidence vocabulary */
  public record RecordStoreView(String id, String holds, String scheme, String note, Long records, String head) {}

  public record RecordStoreList(List<RecordStoreView> stores) {}

  public record AskRequest(String text) {}

  public record AcceptRequest(String rationale) {}

  public record AssertRequest(String evidenceKind, List<ExpectationView> expects, String rationale) {}

  /** @param kind procedure, policy, test-report, mapping or other; other when not given */
  public record AttachRequest(String name, String ref, String asOf, String kind, String content) {
    public AttachRequest(String name, String ref, String asOf) {
      this(name, ref, asOf, null, null);
    }
  }

  /** @param content optional; only its SHA-256 is kept, so the document can later be shown to be the one registered */
  public record DocumentRequest(String kind, String ref, String asOf, String content) {}

  public record DocumentVersionView(int version, String name, String kind, String ref, String asOf, String digest, String by, String at) {}

  public record DocumentSummary(String name, String kind, String ref, String asOf, String digest, String by, int versions) {}

  public record DocumentList(List<DocumentSummary> documents) {}

  public record DocumentDetail(String name, DocumentVersionView newest, List<DocumentVersionView> versions) {}

  public record ClaimsDetail(
      AcceptanceView acceptance, AssertionView assertion, List<AssertionView> earlier, List<DocumentVersionView> documents,
      CoverageView coverage) {}

  public record WaiveRequest(String reason, String until) {}

  public record WaiverView(String controlId, boolean waived, String reason, String by, String until) {}

  private final ComponentClient componentClient;

  public GovernanceEndpoint(ComponentClient componentClient, Authz authz, ProjectResolver projects) {
    super(authz, projects);
    this.componentClient = componentClient;
  }

  @Get("/controls")
  public ControlList controls() {
    authorize(Permission.READ);
    var catalog = ControlCatalog.current();
    return new ControlList(catalog.systemId(), catalog.digest(), catalog.controls().stream().map(GovernanceEndpoint::view).toList());
  }

  /** The evidence of an organization, newest first, optionally for one control and since a time. */
  @Get("/evidence")
  public EvidenceExport evidence() {
    authorize(Permission.READ);
    var params = requestContext().queryParams();
    var control = params.getString("control").orElse("");
    long since;
    try {
      since = params.getString("since").map(s -> Instant.parse(s).toEpochMilli()).orElse(0L);
    } catch (DateTimeParseException e) {
      throw HttpException.badRequest("since must be an ISO-8601 instant");
    }
    var rows = control.isEmpty()
        ? componentClient.forView().method(EvidenceView::byOrganization)
            .invoke(new EvidenceView.ByOrganization(project(), since, MAX_EVIDENCE + 1)).items()
        : componentClient.forView().method(EvidenceView::byControl)
            .invoke(new EvidenceView.ByControl(project(), control, since, MAX_EVIDENCE + 1)).items();
    boolean truncated = rows.size() > MAX_EVIDENCE;
    var shown = rows.stream().limit(MAX_EVIDENCE).map(GovernanceEndpoint::view).toList();
    return new EvidenceExport(ControlCatalog.current().digest(), shown.size(), truncated, shown);
  }

  @Get("/compliance")
  public ComplianceReport compliance() {
    authorize(Permission.READ);
    long now = System.currentTimeMillis();
    var catalog = ControlCatalog.current();
    var standings = catalog.controls().stream().map(control -> standing(project(), control, now)).toList();
    return new ComplianceReport(catalog.digest(), Instant.ofEpochMilli(now).toString(), (int) FRESHNESS.toDays(), standings,
        gaps(standings));
  }

  /**
   * Answers a question about the controls, such as "what evidence do we have that a person can override the assistant, and is it
   * tamper-evident?". A model only chooses which of a fixed list of questions was asked; the facts come from recorded data by
   * fixed templates; a second model words them, and its words are checked against them. The facts and the records they come
   * from are always returned beside the words.
   */
  @Post("/ask")
  public ComplianceAnswerer.Response ask(AskRequest body) {
    var caller = authorize(Permission.READ);
    required(body);
    if (body.text() == null || body.text().isBlank()) throw HttpException.badRequest("text is required");
    if (body.text().length() > ComplianceAnswerer.MAX_QUESTION) {
      throw HttpException.badRequest("text is at most " + ComplianceAnswerer.MAX_QUESTION + " characters");
    }
    return call(() -> new ComplianceAnswerer(componentClient, ControlCatalog.current()).ask(project(), body.text().trim(), caller.subject()));
  }

  /**
   * Checks the evidence chain, a stretch at a time ({@code ?from=1&limit=1000}; at most 5000). A stretch that
   * does not start at 1 is checked against the stored digest of the link before it.
   */
  @Get("/integrity")
  public IntegrityReport integrity() {
    authorize(Permission.READ);
    var params = requestContext().queryParams();
    long from = Math.max(1, params.getLong("from").orElse(1L));
    long limit = Math.min(MAX_CHECK, Math.max(1, params.getLong("limit").orElse(1000L)));
    var chain = componentClient.forEventSourcedEntity(project()).method(IntegrityChainEntity::get).invoke();
    var result = Integrity.check(from, limit, chain.count(),
        seq -> Optional.ofNullable(componentClient.forEventSourcedEntity(IntegrityConsumer.linkId(project(), seq))
            .method(ChainLinkEntity::get).invoke().link()),
        id -> Optional.ofNullable(componentClient.forEventSourcedEntity(id).method(EvidenceEntity::get).invoke().evidence()));
    Long next = result.lastSeq() < chain.count() && result.checked() > 0 ? result.lastSeq() + 1 : null;
    return new IntegrityReport(Integrity.HASH_CHAINED, chain.count(), chain.head().isEmpty() ? null : chain.head(), from,
        result.checked(), result.intact(), result.problems(), next);
  }

  /**
   * What this project knows, as RDF Turtle in the Runtime Evidence vocabulary: the controls as obligations, the
   * acceptances and assertions as separate attributed claims, and the most recent chained evidence records
   * ({@code ?limit=500}, at most 5000) as activities with their position in the ledger.
   */
  @Get("/rte")
  public HttpResponse rte() {
    authorize(Permission.READ);
    long limit = Math.min(MAX_CHECK, Math.max(1, requestContext().queryParams().getLong("limit").orElse(500L)));
    var chain = componentClient.forEventSourcedEntity(project()).method(IntegrityChainEntity::get).invoke();
    var items = new java.util.ArrayList<RuntimeEvidence.Item>();
    for (long seq = Math.max(1, chain.count() - limit + 1); seq <= chain.count(); seq++) {
      var link = componentClient.forEventSourcedEntity(IntegrityConsumer.linkId(project(), seq)).method(ChainLinkEntity::get).invoke().link();
      if (link == null) continue;
      var evidence = componentClient.forEventSourcedEntity(link.recordId()).method(EvidenceEntity::get).invoke().evidence();
      if (evidence != null) items.add(new RuntimeEvidence.Item(link, evidence));
    }
    var catalog = ControlCatalog.current();
    var claims = new java.util.LinkedHashMap<String, ControlClaims>();
    catalog.controls().forEach(c -> claims.put(c.rowId(), readClaims(project(), c.rowId())));
    var documentAsOf = new java.util.LinkedHashMap<String, String>();
    claims.values().stream().filter(ControlClaims::asserted).flatMap(c -> c.assertion().expects().stream()).map(ControlClaims.Expectation::name)
        .distinct().forEach(name -> Documents.newestAsOf(componentClient, project(), name).ifPresent(d -> documentAsOf.put(name, d)));
    var turtle = RuntimeEvidence.turtle(new RuntimeEvidence.Input(project(), catalog, claims, items, chain.count(), chain.head(), documentAsOf));
    return HttpResponse.create().withEntity(akka.http.javadsl.model.HttpEntities.create(TURTLE, turtle));
  }

  private static final akka.http.javadsl.model.ContentType.NonBinary TURTLE = akka.http.javadsl.model.ContentTypes.create(
      akka.http.javadsl.model.MediaTypes.customWithOpenCharset("text", "turtle", java.util.Map.of(), true, "ttl"),
      akka.http.javadsl.model.HttpCharsets.UTF_8);

  /** What holds the records this service keeps, and what each does to make them tamper-evident. */
  @Get("/record-stores")
  public RecordStoreList recordStores() {
    authorize(Permission.READ);
    var chain = componentClient.forEventSourcedEntity(project()).method(IntegrityChainEntity::get).invoke();
    return new RecordStoreList(List.of(
        new RecordStoreView("evidence-ledger", "Evidence that controls ran, including who accepted and asserted what",
            Integrity.HASH_CHAINED, "Each record is chained to the one before it; check it at /governance/integrity.",
            chain.count(), chain.head().isEmpty() ? null : chain.head()),
        new RecordStoreView("human-events", "Human reviews and their decisions, with rationale and authority",
            Integrity.NO_INTEGRITY, "An event journal with no digest. Each decision is copied to the evidence ledger, which is chained.",
            null, null),
        new RecordStoreView("control-claims", "Acceptances of controls and assertions about their evidence",
            Integrity.NO_INTEGRITY, "An event journal with no digest. Each change is copied to the evidence ledger, which is chained.",
            null, null),
        new RecordStoreView("trust-documents", "Which documents back the evidence, where they are, and the digest of the content registered",
            Integrity.NO_INTEGRITY, "An event journal with no digest. The digest of each registered content lets a document be shown to be the one registered.",
            null, null),
        new RecordStoreView("entity-graph", "Records read from source systems, and the links between them",
            Integrity.NO_INTEGRITY, "Rebuilt from the sources; it is not a record of what happened.", null, null)));
  }

  /** What has been said about a control: its acceptance, its assertion and their history, and what backs the assertion. */
  @Get("/controls/{controlId}/claims")
  public ClaimsDetail claims(String controlId) {
    authorize(Permission.READ);
    ControlCatalog.current().find(controlId).orElseThrow(HttpException::notFound);
    var claims = readClaims(project(), controlId);
    long now = System.currentTimeMillis();
    return new ClaimsDetail(acceptanceView(claims), assertionView(claims.assertion()),
        claims.earlier().stream().map(GovernanceEndpoint::assertionView).toList(),
        claims.asserted() ? claims.assertion().expects().stream()
            .map(e -> Documents.get(componentClient, project(), e.name()).newest()).filter(java.util.Objects::nonNull)
            .map(GovernanceEndpoint::versionView).toList() : List.of(),
        coverageView(project(), claims, now));
  }

  /**
   * Accepts a control for this project, at the version of the catalog in force. This is the binding:
   * it says the project took the obligation on, and by whom. It says nothing about what counts as evidence.
   */
  @Post("/controls/{controlId}/acceptance")
  public AcceptanceView accept(String controlId, AcceptRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    ControlCatalog.current().find(controlId).orElseThrow(HttpException::notFound);
    long now = System.currentTimeMillis();
    var claims = call(() -> componentClient.forEventSourcedEntity(claimsId(project(), controlId))
        .method(ControlClaimsEntity::accept).invoke(new ControlClaimsEntity.Accept(caller.subject(), caller.authorityBasis(),
            body.rationale(), ControlCatalog.current().digest(), now)));
    record(project(), controlId, "ControlAccepted", "accepted-" + now, "ACCEPT", body.rationale(), caller.subject(),
        caller.authorityBasis(), now);
    return acceptanceView(claims);
  }

  @Post("/controls/{controlId}/acceptance/withdraw")
  public AcceptanceView withdrawAcceptance(String controlId) {
    var caller = authorize(Permission.APPROVE);
    ControlCatalog.current().find(controlId).orElseThrow(HttpException::notFound);
    long now = System.currentTimeMillis();
    var claims = call(() -> componentClient.forEventSourcedEntity(claimsId(project(), controlId))
        .method(ControlClaimsEntity::withdrawAcceptance).invoke(new ControlClaimsEntity.Accept(caller.subject(),
            caller.authorityBasis(), "", ControlCatalog.current().digest(), now)));
    record(project(), controlId, "ControlAcceptanceWithdrawn", "withdrawn-" + now, "WITHDRAW", "acceptance withdrawn",
        caller.subject(), caller.authorityBasis(), now);
    return acceptanceView(claims);
  }

  /**
   * Asserts which kind of record counts as evidence for a control, and which documents it is expected to
   * be backed by. It is a separate claim from the acceptance and has its own author. Each assertion is a
   * new revision; the earlier ones stay.
   */
  @Post("/controls/{controlId}/assertion")
  public AssertionView assertEvidence(String controlId, AssertRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    var catalog = ControlCatalog.current();
    catalog.find(controlId).orElseThrow(HttpException::notFound);
    var kinds = catalog.controls().stream().map(ControlDef::proofEvent).collect(java.util.stream.Collectors.toSet());
    if (body.evidenceKind() == null || !kinds.contains(body.evidenceKind())) {
      throw HttpException.badRequest("evidenceKind must be one of " + kinds.stream().sorted().toList());
    }
    long now = System.currentTimeMillis();
    var expects = body.expects() == null ? List.<ControlClaims.Expectation>of()
        : body.expects().stream().map(e -> new ControlClaims.Expectation(e.name(), e.maxAgeDays())).toList();
    var claims = call(() -> componentClient.forEventSourcedEntity(claimsId(project(), controlId))
        .method(ControlClaimsEntity::assertEvidence).invoke(new ControlClaimsEntity.Assert(body.evidenceKind(), expects,
            caller.subject(), caller.authorityBasis(), body.rationale(), now)));
    record(project(), controlId, "EvidenceAsserted", "asserted-" + now, "ASSERT", body.rationale(), caller.subject(),
        caller.authorityBasis(), now);
    return assertionView(claims.assertion());
  }

  /**
   * Registers a document an assertion expects, such as the override procedure, with the date it was last current. It goes into the
   * project's document store, so one document can back several controls.
   */
  @Post("/controls/{controlId}/documents")
  public CoverageView attach(String controlId, AttachRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    ControlCatalog.current().find(controlId).orElseThrow(HttpException::notFound);
    var claims = readClaims(project(), controlId);
    if (!claims.asserted()) throw HttpException.badRequest("make an assertion before attaching documents to it");
    if (body.name() == null || claims.assertion().expects().stream().noneMatch(e -> e.name().equals(body.name()))) {
      throw HttpException.badRequest("the assertion does not expect a document named " + body.name());
    }
    long now = System.currentTimeMillis();
    register(caller, body.name(), new DocumentRequest(body.kind() == null ? "other" : body.kind(), body.ref(), body.asOf(), body.content()), now);
    record(project(), controlId, "EvidenceAttached", "attached-" + now + "-" + body.name(), "ATTACH", body.name(),
        caller.subject(), caller.authorityBasis(), now);
    return coverageView(project(), claims, now);
  }

  /** Registers a version of a trust document of the project. Only the digest of its content, if given, is kept. */
  @Post("/documents/{name}")
  public DocumentVersionView addDocument(String name, DocumentRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    long now = System.currentTimeMillis();
    var added = register(caller, name, body, now);
    return versionView(added.versions().get(added.versions().size() - 1));
  }

  @Get("/documents")
  public DocumentList documents() {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(DocumentView::forOrganization).invoke(project()).items();
    return new DocumentList(rows.stream().sorted(java.util.Comparator.comparing(DocumentView.Row::name))
        .map(r -> new DocumentSummary(r.name(), r.kind(), r.ref(), r.asOf(), r.digest(), r.by(), r.versions())).toList());
  }

  @Get("/documents/{name}")
  public DocumentDetail document(String name) {
    authorize(Permission.READ);
    var document = Documents.get(componentClient, project(), name);
    if (!document.exists()) throw HttpException.notFound();
    return new DocumentDetail(document.name(), versionView(document.newest()), document.versions().stream().map(GovernanceEndpoint::versionView).toList());
  }

  private TrustDocument.Document register(io.akka.contextlayer.auth.Caller caller, String name, DocumentRequest body, long now) {
    return call(() -> componentClient.forEventSourcedEntity(Documents.entityId(project(), name)).method(DocumentEntity::add)
        .invoke(new DocumentEntity.Add(name, body.kind(), body.ref(), body.asOf(), body.content(), caller.subject(), now)));
  }

  private static DocumentVersionView versionView(TrustDocument.Version v) {
    return new DocumentVersionView(v.version(), v.name(), v.kind(), v.ref(), v.asOf(), v.digest().isEmpty() ? null : v.digest(), v.by(),
        Instant.ofEpochMilli(v.at()).toString());
  }

  /** Waives a control, optionally until a time. The waiver and who granted it are also evidence. */
  @Post("/controls/{controlId}/waiver")
  public WaiverView waive(String controlId, WaiveRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    ControlCatalog.current().find(controlId).orElseThrow(HttpException::notFound);
    if (body.reason() == null || body.reason().isBlank()) throw HttpException.badRequest("a reason is required");
    long now = System.currentTimeMillis();
    long until;
    try {
      until = body.until() == null || body.until().isBlank() ? 0 : Instant.parse(body.until()).toEpochMilli();
    } catch (DateTimeParseException e) {
      throw HttpException.badRequest("until must be an ISO-8601 instant");
    }
    if (until != 0 && until > now + MAX_WAIVER.toMillis()) {
      throw HttpException.badRequest("a waiver can last at most " + MAX_WAIVER.toDays() + " days");
    }
    var state = call(() -> componentClient.forKeyValueEntity(waiverId(project(), controlId)).method(WaiverEntity::grant)
        .invoke(new WaiverEntity.Grant(body.reason(), caller.subject(), caller.authorityBasis(), until, now)));
    record(project(), controlId, "ControlWaived", "waived-" + now, "WAIVE", body.reason(), caller.subject(),
        caller.authorityBasis(), now);
    return waiverView(controlId, state);
  }

  @Post("/controls/{controlId}/waiver/lift")
  public WaiverView lift(String controlId) {
    var caller = authorize(Permission.ADMINISTER);
    ControlCatalog.current().find(controlId).orElseThrow(HttpException::notFound);
    long now = System.currentTimeMillis();
    var state = call(() -> componentClient.forKeyValueEntity(waiverId(project(), controlId)).method(WaiverEntity::lift)
        .invoke(new WaiverEntity.Lift(caller.subject(), caller.authorityBasis(), now)));
    record(project(), controlId, "ControlWaiverLifted", "lifted-" + now, "LIFT", "waiver lifted", caller.subject(),
        caller.authorityBasis(), now);
    return waiverView(controlId, state);
  }

  // ---- helpers

  private ControlStanding standing(String orgId, ControlDef control, long now) {
    long since = now - LOOKBACK.toMillis();
    var rows = componentClient.forView().method(EvidenceView::byControl)
        .invoke(new EvidenceView.ByControl(orgId, control.rowId(), since, MAX_EVIDENCE)).items();
    var items = rows.stream()
        .filter(r -> !r.eventType().startsWith("ControlWaiver") && !r.eventType().equals("ControlWaived")
            && !CLAIM_EVENTS.contains(r.eventType()))
        .map(r -> new Compliance.Item(EvaluationMode.valueOf(r.mode()), Verdict.valueOf(r.verdict()), r.flagged(), r.at()))
        .toList();
    var waiver = WaiverEntity.asWaiver(componentClient.forKeyValueEntity(waiverId(orgId, control.rowId()))
        .method(WaiverEntity::get).invoke());
    var s = Compliance.assess(items, waiver, FRESHNESS, now);
    var claims = readClaims(orgId, control.rowId());
    return new ControlStanding(control.rowId(), control.name(), control.mechanism().name().toLowerCase(),
        s.status().name(), s.why(), s.enforcing(), s.upheld(), s.breached(), s.flagged(),
        s.lastEnforcingAt() == 0 ? null : Instant.ofEpochMilli(s.lastEnforcingAt()).toString(),
        acceptanceView(claims), assertionView(claims.assertion()), coverageView(orgId, claims, now));
  }

  private ControlClaims readClaims(String orgId, String controlId) {
    return componentClient.forEventSourcedEntity(claimsId(orgId, controlId)).method(ControlClaimsEntity::get).invoke();
  }

  private static String claimsId(String orgId, String controlId) {
    return ScopedId.of(orgId, "claims", controlId);
  }

  private static AcceptanceView acceptanceView(ControlClaims claims) {
    var a = claims.acceptance();
    if (a == null) return new AcceptanceView(false, false, null, null, null, null, null);
    return new AcceptanceView(true, claims.acceptanceCurrent(ControlCatalog.current().digest()), a.by(), a.authority(),
        Instant.ofEpochMilli(a.at()).toString(), a.catalogDigest(), a.rationale());
  }

  private static AssertionView assertionView(ControlClaims.Assertion a) {
    if (a == null) return new AssertionView(false, 0, null, null, null, null, null, null, List.of());
    return new AssertionView(true, a.revision(), a.evidenceKind(), a.by(), a.authority(), Instant.ofEpochMilli(a.at()).toString(),
        a.basisDigest().isEmpty() ? null : a.basisDigest(), a.rationale(),
        a.expects().stream().map(e -> new ExpectationView(e.name(), e.maxAgeDays())).toList());
  }

  private CoverageView coverageView(String project, ControlClaims claims, long now) {
    var c = Documents.coverage(componentClient, project, claims, now);
    return new CoverageView(c.coverage().name(), c.unmet());
  }

  private static Gaps gaps(List<ControlStanding> standings) {
    var unaccepted = new java.util.ArrayList<String>();
    var acceptedNoAssertion = new java.util.ArrayList<String>();
    var assertedNoEvidence = new java.util.ArrayList<String>();
    var partial = new java.util.ArrayList<String>();
    for (var s : standings) {
      if (!s.acceptance().accepted() || !s.acceptance().current()) unaccepted.add(s.controlId());
      if (s.acceptance().accepted() && !s.assertion().asserted()) acceptedNoAssertion.add(s.controlId());
      if (s.assertion().asserted() && s.enforcing() == 0) assertedNoEvidence.add(s.controlId());
      if (s.assertion().asserted() && !"FULL".equals(s.coverage().coverage())) partial.add(s.controlId());
    }
    return new Gaps(List.copyOf(unaccepted), List.copyOf(acceptedNoAssertion), List.copyOf(assertedNoEvidence), List.copyOf(partial));
  }

  /** Waiver bookkeeping is advisory evidence: it records who decided, and never counts as the control enforcing. */
  private void record(String orgId, String controlId, String eventType, String part, String decision, String reason,
      String actor, String authority, long now) {
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(orgId, controlId, part,
        EvaluationMode.ADVISORY, decision, "OBSERVED_ONLY", reason, controlId, actor, authority, "", false, eventType, now));
  }

  private static String waiverId(String orgId, String controlId) {
    return ScopedId.of(orgId, "waiver", controlId);
  }

  private static WaiverView waiverView(String controlId, WaiverEntity.State s) {
    boolean waived = WaiverEntity.asWaiver(s) != null;
    return new WaiverView(controlId, waived, s.reason(), s.by(),
        s.waivedUntil() == 0 ? null : Instant.ofEpochMilli(s.waivedUntil()).toString());
  }

  private static ControlView view(ControlDef c) {
    var h = c.human();
    return new ControlView(c.rowId(), c.name(), c.mechanism().name().toLowerCase(), c.lifecycle(), c.corpusRef(),
        c.summary(), c.proofEvent(), c.boundary(),
        h == null ? null : new HumanSummary(h.flavor(), h.mode(), h.roles(), h.reviewMode(), h.required(),
            h.slaTarget(), h.rationaleRequired(), h.minimumReviewSeconds(), h.enforceMinimum(), h.allowedActions()));
  }

  private static EvidenceItem view(EvidenceView.Row r) {
    return new EvidenceItem(r.evidenceId(), r.controlId(), r.eventType(), r.boundary(), r.mode(), r.decision(),
        r.consequence(), r.reasonCode(), r.subject(), r.actor(), r.authority(), r.bundleDigest(), r.ref(), r.flagged(),
        r.verdict().toLowerCase(), Instant.ofEpochMilli(r.at()).toString());
  }
}
