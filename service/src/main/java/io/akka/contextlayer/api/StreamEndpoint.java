package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.javasdk.timer.TimerScheduler;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.application.ChangeApplier;
import io.akka.contextlayer.application.EntityRefresher;
import io.akka.contextlayer.application.EntityStore;
import io.akka.contextlayer.application.SourceCaller;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.EntityChange;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.contextlayer.application.ChangeFeedEntity;
import io.akka.contextlayer.application.DomainAccess;
import io.akka.contextlayer.application.QuestionService;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Where a source that is not an Akka service pushes changes to its records, such as a webhook from
 * a third party. The changes mean the same as the ones an Akka service publishes as a stream.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context/streams")
public class StreamEndpoint extends PrincipalEndpoint {

  static final int MAX_BATCH = 500;

  /** @param op upsert, delete or heartbeat */
  public record ChangeRequest(String op, String entityType, String entityId, Long version, Map<String, Object> attributes) {}

  public record ChangeBatch(List<ChangeRequest> changes) {}

  /** @param problems one line for each change that was not understood, by its position in the batch */
  public record ChangeResult(int applied, int alreadyHeld, int noConnector, List<String> problems) {}

  /** One change a watcher is told about. {@code entityId} is empty when a type was read whole. */
  public record ChangeEvent(String pack, String entityType, String entityId, String op, long at) {}

  /**
   * What a standing question tells its watcher: the whole answer first, then what was added to it and
   * removed from it each time a change alters the answer.
   */
  public record WatchEvent(
      String event,
      List<Map<String, Object>> rows,
      List<Map<String, Object>> added,
      List<Map<String, Object>> removed,
      int total,
      List<ContextLayerEndpoint.SourceReportView> sources) {}

  private static final long QUESTION_TIME_BUDGET_MILLIS = 20_000;

  private final ComponentClient componentClient;
  private final SourceCaller sourceCaller;
  private final EntityStore store;
  private final ChangeApplier applier;

  public StreamEndpoint(
      ComponentClient componentClient, TimerScheduler timers, SourceCaller sourceCaller, EntityStore store, Authz authz,
      ProjectResolver projects) {
    super(authz, projects);
    this.componentClient = componentClient;
    this.sourceCaller = sourceCaller;
    this.store = store;
    this.applier = new ChangeApplier(componentClient, timers,
        new EntityRefresher(componentClient, sourceCaller, store, System::currentTimeMillis), System::currentTimeMillis);
  }

  @Post("/changes")
  public ChangeResult changes(ChangeBatch body) {
    authorize(Permission.OPERATE);
    required(body);
    if (body.changes() == null || body.changes().isEmpty() || body.changes().size() > MAX_BATCH) {
      throw HttpException.badRequest("send between 1 and " + MAX_BATCH + " changes");
    }
    int applied = 0, held = 0, none = 0;
    var problems = new ArrayList<String>();
    for (int i = 0; i < body.changes().size(); i++) {
      var change = toChange(body.changes().get(i), problems, i);
      if (change == null) continue;
      switch (applier.apply(project(), change)) {
        case APPLIED -> applied++;
        case ALREADY_HELD -> held++;
        case NO_CONNECTOR, NOT_READY -> none++;
      }
    }
    return new ChangeResult(applied, held, none, List.copyOf(problems));
  }

  /** Tells the caller, as it happens, when records of the given types change ({@code ?types=Claim,Account}). */
  @Get("/changes")
  public HttpResponse watchChanges() {
    var caller = authorize(Permission.READ);
    var wanted = types(requestContext().queryParams().getString("types"));
    var readable = DomainAccess.config(componentClient, project())
        .readableTypes(caller.subject(), DomainAccess.rolesOf(caller));
    var source = changes(project())
        .filter(n -> wanted.isEmpty() || wanted.contains(n.entityType()))
        .filter(n -> readable.isEmpty() || readable.get().contains(n.entityType()))
        .map(n -> new ChangeEvent(n.pack(), n.entityType(), n.entityId(), n.op(), n.at()));
    return HttpResponses.serverSentEvents(source);
  }

  /**
   * A standing question: the answer now, then what changes in it as the data it reads changes. A
   * monitor is the same thing with a case as its output. The stream starts again from the whole
   * answer when a watcher reconnects.
   */
  @Post("/watch")
  public HttpResponse watch(ContextLayerEndpoint.QuestionRequest body) {
    var caller = authorize(Permission.OPERATE);
    required(body);
    var question = QuestionMapping.toDomain(body);
    var problems = question.validate();
    if (!problems.isEmpty()) throw HttpException.badRequest(String.join("; ", problems));
    var project = project();
    var read = DomainAccess.typesOf(question);
    DomainGate.require(componentClient, project, caller, read);
    var service = QuestionService.standard(componentClient, store, sourceCaller, QUESTION_TIME_BUDGET_MILLIS);

    var held = new AtomicReference<Map<String, Map<String, Object>>>(null);
    var triggers = Source.single("start").concat(changes(project)
        .filter(n -> read.contains(n.entityType()))
        .groupedWithin(1000, Duration.ofSeconds(1))
        .map(group -> "changed"));
    Source<WatchEvent, ?> events = triggers
        .mapAsync(1, trigger -> CompletableFuture.supplyAsync(() -> evaluate(service, project, question, held)))
        .mapConcat(event -> event.map(List::of).orElse(List.of()));
    return HttpResponses.serverSentEvents(events);
  }

  private Source<ChangeFeedEntity.ChangeNotice, ?> changes(String project) {
    return componentClient.forKeyValueEntity(project).notificationStream(ChangeFeedEntity::updates).source();
  }

  private static Optional<WatchEvent> evaluate(
      QuestionService service, String project, StructuredQuestion question,
      AtomicReference<Map<String, Map<String, Object>>> held) {
    var answer = service.ask(project, question);
    var now = new LinkedHashMap<String, Map<String, Object>>();
    for (var row : answer.rows()) now.put(JsonSupport.encodeToString(row), row);
    var before = held.getAndSet(now);
    if (before == null) {
      var sources = answer.sources().stream()
          .map(s -> new ContextLayerEndpoint.SourceReportView(s.pack(), s.entityType(), s.freshness().name(),
              s.lastOutcome().name(), s.dataAsOf(), s.warnings()))
          .toList();
      return Optional.of(new WatchEvent("snapshot", List.copyOf(now.values()), List.of(), List.of(), now.size(), sources));
    }
    var added = now.entrySet().stream().filter(e -> !before.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
    var removed = before.entrySet().stream().filter(e -> !now.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
    if (added.isEmpty() && removed.isEmpty()) return Optional.empty();
    return Optional.of(new WatchEvent("changed", List.of(), added, removed, now.size(), List.of()));
  }

  private static Set<String> types(Optional<String> text) {
    if (text.isEmpty() || text.get().isBlank()) return Set.of();
    return Arrays.stream(text.get().split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
  }

  private static EntityChange toChange(ChangeRequest r, List<String> problems, int position) {
    var where = "change " + position + ": ";
    if (r == null || r.op() == null || r.entityType() == null || r.entityType().isBlank()) {
      problems.add(where + "op and entityType are required");
      return null;
    }
    if (r.op().equals("heartbeat")) return new EntityChange.Heartbeat(r.entityType());
    if (r.entityId() == null || r.entityId().isBlank() || r.version() == null) {
      problems.add(where + "entityId and version are required");
      return null;
    }
    return switch (r.op()) {
      case "upsert" -> r.attributes() == null
          ? problem(problems, where + "an upsert needs attributes")
          : new EntityChange.Upserted(r.entityType(), r.entityId(), r.version(), r.attributes());
      case "delete" -> new EntityChange.Deleted(r.entityType(), r.entityId(), r.version());
      default -> problem(problems, where + "op must be upsert, delete or heartbeat");
    };
  }

  private static EntityChange problem(List<String> problems, String text) {
    problems.add(text);
    return null;
  }
}
