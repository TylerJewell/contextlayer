package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.RefreshStatus;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.governance.Controls;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Answers a structured question. Freshness is settled before the question runs: for every entity
 * type it touches, each active connector that provides the type is refreshed according to the
 * type's policy, and the answer reports what each source did. Nothing is fetched while the
 * question itself executes (DESIGN D-04).
 */
public class QuestionService {

  /** How a source was treated for this question. */
  public enum Freshness {
    /** Read from the source for this question. */
    REFRESHED_NOW,
    /** Stored data was recent enough under the cache policy. */
    WITHIN_POLICY,
    /** Read in the background on a schedule; stored data was used as is. */
    MAINTAINED,
    /** Kept current by what the source pushes; stored data was used as is. */
    STREAMED,
    /** A refresh was needed but failed or timed out, or a pushed or scheduled source went quiet; older stored data was used. */
    STALE,
    /** A refresh was needed, failed, and nothing is stored. */
    UNAVAILABLE
  }

  public record SourceReport(
      String pack,
      String entityType,
      Freshness freshness,
      RefreshStatus.Outcome lastOutcome,
      long dataAsOf,
      List<String> warnings) {}

  public record Answer(List<Map<String, Object>> rows, long unconfirmedLeftOut, List<SourceReport> sources) {}

  private final ComponentClient componentClient;
  private final EntityStore store;
  private final EntityRefresher refresher;
  private final LongSupplier nowMillis;
  private final long timeBudgetMillis;

  public QuestionService(
      ComponentClient componentClient, EntityStore store, EntityRefresher refresher, LongSupplier nowMillis,
      long timeBudgetMillis) {
    this.componentClient = componentClient;
    this.store = store;
    this.refresher = refresher;
    this.nowMillis = nowMillis;
    this.timeBudgetMillis = timeBudgetMillis;
  }

  /** The service wired to the real clock, refresher and store. */
  public static QuestionService standard(
      ComponentClient componentClient, EntityStore store, SourceCaller sourceCaller, long timeBudgetMillis) {
    return new QuestionService(componentClient, store,
        new EntityRefresher(componentClient, sourceCaller, store, System::currentTimeMillis),
        System::currentTimeMillis, timeBudgetMillis);
  }

  /**
   * Answers the question and records the verdicts of the controls it passes: it is read-only and within bounds
   * (custom-cl0034), it follows only confirmed identity links (custom-cl0033), and every source it read was current
   * (custom-cl0054).
   *
   * @throws IllegalArgumentException when the question is not acceptable
   */
  public Answer ask(String orgId, StructuredQuestion question) {
    var problems = question.validate();
    var verdicts = new Verdicts(componentClient);
    var subject = String.join(", ", DomainAccess.typesOf(question));
    var part = Verdicts.once("question");
    verdicts.guardrail(orgId, Controls.READ_ONLY_QUESTIONS, part, problems.isEmpty(),
        problems.isEmpty() ? "read-only, parameterised, within bounds" : String.join("; ", problems), subject,
        "system:questions");
    if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));

    long deadline = nowMillis.getAsLong() + timeBudgetMillis;
    var reports = new ArrayList<SourceReport>();
    var types = new LinkedHashSet<String>();
    types.add(question.entityType());
    if (question.links() != null) question.links().forEach(l -> types.add(l.entityType()));

    var packs = activePacks(orgId);
    for (var type : types) {
      var providers = providers(packs, type);
      if (providers.isEmpty()) {
        reports.add(new SourceReport("", type, Freshness.UNAVAILABLE, RefreshStatus.Outcome.NEVER, 0,
            List.of("no active connector provides " + type)));
      }
      for (var provider : providers) {
        reports.add(settle(orgId, provider, type, deadline));
      }
    }

    var result = store.run(orgId, question);
    boolean byIdentity = question.links() != null
        && question.links().stream().anyMatch(l -> l.via() == null || l.via().isBlank());
    if (byIdentity) {
      verdicts.guardrail(orgId, Controls.CONFIRMED_IDENTITIES, part, true,
          "followed confirmed links only; " + result.unconfirmedLeftOut() + " unconfirmed left out", subject,
          "system:questions");
    }
    var behind = reports.stream()
        .filter(r -> r.freshness() == Freshness.STALE || r.freshness() == Freshness.UNAVAILABLE)
        .map(r -> (r.pack().isEmpty() ? "" : r.pack() + "/") + r.entityType() + " " + r.freshness())
        .toList();
    verdicts.eval(orgId, Controls.SOURCE_FRESHNESS, part, behind.isEmpty(),
        behind.isEmpty() ? "every source within its freshness policy" : String.join("; ", behind), subject);
    return new Answer(result.rows(), result.unconfirmedLeftOut(), List.copyOf(reports));
  }

  record Provider(String pack, PackDefinition.EntityMapping entity) {}

  private List<ConnectorPack> activePacks(String orgId) {
    var rows = componentClient.forView().method(ConnectorPackView::forOrganization).invoke(orgId);
    var packs = new ArrayList<ConnectorPack>();
    for (var row : rows.items()) {
      var pack = componentClient.forEventSourcedEntity(ScopedId.of(orgId, row.name()))
          .method(ConnectorPackEntity::get).invoke();
      if (pack.exists() && pack.active().isPresent()) packs.add(pack);
    }
    return packs;
  }

  private static List<Provider> providers(List<ConnectorPack> packs, String type) {
    var found = new ArrayList<Provider>();
    for (var pack : packs) {
      var entities = pack.active().get().definition().entities();
      if (entities == null) continue;
      entities.stream().filter(e -> e.type().equals(type))
          .forEach(e -> found.add(new Provider(pack.name(), e)));
    }
    return found;
  }

  private SourceReport settle(String orgId, Provider provider, String type, long deadline) {
    var id = ScopedId.of(orgId, provider.pack(), type);
    var last = componentClient.forKeyValueEntity(id).method(RefreshStatusEntity::get).invoke();
    long now = nowMillis.getAsLong();
    var policy = provider.entity().freshness();

    if ("STREAMED".equals(policy.policy())) {
      return streamed(provider, type, last, now, policy.ttlMinutes());
    }
    // Before the first scheduled read has happened there is nothing to answer from, so read now.
    if ("MAINTAINED".equals(policy.policy()) && last.exists()) {
      return maintained(provider, type, last, now, policy.ttlMinutes());
    }
    if ("CACHED".equals(policy.policy()) && last.freshWithin(policy.ttlMinutes(), now)) {
      return report(provider, type, Freshness.WITHIN_POLICY, last, List.of());
    }
    if (now >= deadline) {
      return staleOrUnavailable(orgId, provider, type, List.of("the time budget ran out before this refresh"));
    }

    var refreshed = refresher.refresh(orgId, provider.pack(), type, Map.of());
    if (refreshed.outcome() == RefreshStatus.Outcome.FAILED) {
      return staleOrUnavailable(orgId, provider, type, refreshed.warnings());
    }
    return report(provider, type, Freshness.REFRESHED_NOW, refreshed, refreshed.warnings());
  }

  /** A scheduled source that has missed two reads in a row, or whose last read failed, is not being kept current. */
  static SourceReport maintained(Provider provider, String type, RefreshStatus last, long now, int everyMinutes) {
    long age = now - last.refreshedAt();
    if (last.outcome() == RefreshStatus.Outcome.FAILED || age > 2 * everyMinutes * 60_000L) {
      var warnings = new ArrayList<>(last.warnings());
      warnings.add("last read " + Math.max(1, age / 60_000) + " minutes ago; it should be read every " + everyMinutes);
      return report(provider, type, Freshness.STALE, last, List.copyOf(warnings));
    }
    return report(provider, type, Freshness.MAINTAINED, last, last.warnings());
  }

  /** A pushed source is current while it keeps sending something: a change, or a heartbeat. */
  static SourceReport streamed(Provider provider, String type, RefreshStatus last, long now, int silenceMinutes) {
    if (!last.exists()) {
      return new SourceReport(provider.pack(), type, Freshness.UNAVAILABLE, RefreshStatus.Outcome.NEVER, 0,
          List.of("nothing has been received from the source yet"));
    }
    long age = now - last.refreshedAt();
    if (age > silenceMinutes * 60_000L) {
      return report(provider, type, Freshness.STALE, last,
          List.of("nothing received for " + Math.max(1, age / 60_000) + " minutes; expected something every " + silenceMinutes));
    }
    return report(provider, type, Freshness.STREAMED, last, List.of());
  }

  private SourceReport staleOrUnavailable(
      String orgId, Provider provider, String type, List<String> why) {
    var held = store.summary(orgId, provider.pack(), type);
    var warnings = new ArrayList<>(why);
    if (held.isPresent()) {
      warnings.add("using data from the last successful read");
      return new SourceReport(provider.pack(), type, Freshness.STALE, RefreshStatus.Outcome.FAILED,
          held.get().lastRefreshedAt(), List.copyOf(warnings));
    }
    return new SourceReport(provider.pack(), type, Freshness.UNAVAILABLE, RefreshStatus.Outcome.FAILED, 0,
        List.copyOf(warnings));
  }

  static SourceReport report(
      Provider provider, String type, Freshness freshness, RefreshStatus status, List<String> warnings) {
    return new SourceReport(provider.pack(), type, freshness, status.outcome(), status.refreshedAt(), warnings);
  }
}
