package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.ContextLayerEndpoint;
import io.akka.contextlayer.application.SourceCaller;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the platform holds must follow what the sources say: a record whose identifying details change
 * stops being linked under the old ones, a record deleted at the source stops appearing in answers,
 * a read that was not complete or failed never removes anything, and the review queue does not ask a
 * person about matches that stronger links already cover. Reads are scripted so each case is exact.
 */
public class RefreshIntegrationTest extends LayerTestBase {

  /** What each source answers next, by pack name. */
  private final Map<String, SourceCaller.Result> script = new ConcurrentHashMap<>();

  @Override
  protected SourceCaller sourceCaller() {
    return (orgId, pack, entity, filters) -> script.getOrDefault(pack.name(), SourceCaller.Result.failed("nothing scripted"));
  }

  @BeforeEach
  void clearScript() {
    script.clear();
  }

  private static Map<String, Object> claim(String id, String email, String name, String dob, int days) {
    var record = new LinkedHashMap<String, Object>();
    record.put("claimId", id);
    record.put("email", email);
    if (name != null) record.put("name", name);
    if (dob != null) record.put("dob", dob);
    record.put("status", "OPEN");
    record.put("daysOpen", days);
    return record;
  }

  private static Map<String, Object> account(String id, String email, String name, String dob, int balance) {
    var record = new LinkedHashMap<String, Object>();
    record.put("accountId", id);
    record.put("email", email);
    if (name != null) record.put("name", name);
    if (dob != null) record.put("dob", dob);
    record.put("balance", balance);
    return record;
  }

  private String connected() {
    var org = newOrg();
    connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId"));
    connect(org, "wealth", pack("wealth", wealth, "Account", "listAccounts", "accountId"));
    // Wait for the connector listing; reads are scripted, so a "not scripted" failure is expected here.
    awaitConnectorsVisible(org);
    return org;
  }

  private void serve(String pack, SourceCaller.Status status, List<Map<String, Object>> records) {
    script.put(pack, new SourceCaller.Result(status, records, status == SourceCaller.Status.OK ? List.of() : List.of("scripted")));
  }

  private ContextLayerEndpoint.RefreshResponse refresh(String org, String pack, String type) {
    return post("alice", "/orgs/" + org + "/connectors/" + pack + "/entities/" + type + "/refresh", Map.of(),
        ContextLayerEndpoint.RefreshResponse.class);
  }

  private List<String> answer(String org) {
    // Skip reading again: both packs are ON_DEMAND in the test base, so the question itself would
    // re-read the scripted sources. The scripted state is what the next read sees.
    var result = post("alice", "/orgs/" + org + "/questions", stalledClaimsWithWealth(), ContextLayerEndpoint.AnswerResponse.class);
    return result.rows().stream().map(r -> String.valueOf(r.get("Claim.claimId"))).sorted().toList();
  }

  private void awaitIdentityStatus(String org, String rule, String status) {
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      try (var session = driver.session()) {
        var found = session.run("MATCH (i:Identity {orgId: $org, rule: $rule}) RETURN i.status AS status",
            Map.of("org", org, "rule", rule)).list(r -> r.get("status").asString());
        assertThat(found).contains(status);
      }
    });
  }

  private List<ContextLayerEndpoint.ClusterResponse> reviewQueue(String org) {
    return get("/orgs/" + org + "/identity/review", ContextLayerEndpoint.ClusterList.class).items();
  }

  private ContextLayerEndpoint.PackRequest cached(ContextLayerEndpoint.PackRequest p, int ttlMinutes) {
    var e = p.entities().get(0);
    var entity = new ContextLayerEndpoint.EntityRequest(e.type(), e.operation(), e.recordsPath(), e.idField(),
        e.identityAttributes(), e.filters(), new ContextLayerEndpoint.FreshnessRequest("CACHED", ttlMinutes), e.paging(),
        e.rate());
    return new ContextLayerEndpoint.PackRequest(p.name(), p.version(), p.source(), p.credentialRef(), List.of(entity), p.actions());
  }

  private ContextLayerEndpoint.AnswerResponse ask(String org, ContextLayerEndpoint.QuestionRequest question) {
    return post("alice", "/orgs/" + org + "/questions", question, ContextLayerEndpoint.AnswerResponse.class);
  }

  private ContextLayerEndpoint.QuestionRequest allClaims() {
    return new ContextLayerEndpoint.QuestionRequest("Claim", List.of(), List.of(), List.of("Claim.claimId"), 100);
  }

  private static ContextLayerEndpoint.SourceReportView report(ContextLayerEndpoint.AnswerResponse a, String pack) {
    return a.sources().stream().filter(s -> s.pack().equals(pack)).findFirst().orElseThrow();
  }

  @Test
  public void aPartialReadIsNotServedFromTheCacheAsIfItWereWhole() {
    var org = newOrg();
    connect(org, "claims", cached(pack("claims", claims, "Claim", "listClaims", "claimId"), 15));
    serve("claims", SourceCaller.Status.OK, List.of());
    Awaitility.await().ignoreExceptions().atMost(15, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(report(ask(org, allClaims()), "claims")).isNotNull());

    serve("claims", SourceCaller.Status.PARTIAL, List.of(claim("CL-1", "a@x", null, null, 41)));
    // The earlier empty read is still fresh under the cache policy, so force a read of the partial script.
    post("alice", "/orgs/" + org + "/connectors/claims/entities/Claim/refresh", Map.of(),
        ContextLayerEndpoint.RefreshResponse.class);
    var first = ask(org, allClaims());
    assertThat(report(first, "claims").lastOutcome()).isEqualTo("PARTIAL");

    // The source recovers. A cache must not hold on to a read that was known to be incomplete.
    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "a@x", null, null, 41), claim("CL-2", "b@x", null, null, 36)));
    var second = ask(org, allClaims());

    assertThat(report(second, "claims").freshness()).isEqualTo("REFRESHED_NOW");
    assertThat(second.rows()).extracting(r -> r.get("Claim.claimId")).containsExactlyInAnyOrder("CL-1", "CL-2");
  }

  @Test
  public void oddDataInASourceNeverBreaksARefresh() {
    var org = connected();
    var nested = new LinkedHashMap<String, Object>();
    nested.put("claimId", "CL-NESTED");
    nested.put("email", "nested@example.com");
    nested.put("detail", Map.of("opened", Map.of("by", "x"), "tags", List.of("a", 2, Map.of("k", "v"))));
    var noId = new LinkedHashMap<String, Object>();
    noId.put("email", "no-id@example.com");
    var nullId = new LinkedHashMap<String, Object>();
    nullId.put("claimId", null);
    serve("claims", SourceCaller.Status.OK, List.of(
        claim("CL-PIPE", "a|b@example.com", "Pipe|Name", "1980-01-02", 41),
        claim("CL-COLON", "c:d@example.com", "Colon: Name", "1980-01-03", 41),
        claim("CL-PERCENT", "100%25@example.com", null, null, 41),
        claim("CL-LONG", "x".repeat(300) + "@example.com", null, null, 41),
        claim("CL-CONTROL", "ctl\u0001\n@example.com", "New\nLine", "1980-01-04", 41),
        claim("CL-QUOTE", "o'brien\"`}) DETACH DELETE (n //@example.com", null, null, 41),
        claim("42", "numeric@example.com", null, null, 41),
        nested, noId, nullId));
    serve("wealth", SourceCaller.Status.OK, List.of(
        account("W-1", "a|b@example.com", "Pipe|Name", "1980-01-02", 400000),
        account("W-2", "c:d@example.com", "Colon: Name", "1980-01-03", 400000),
        account("W-3", "x".repeat(300) + "@example.com", null, null, 400000)));

    var claimsRefresh = refresh(org, "claims", "Claim");
    var wealthRefresh = refresh(org, "wealth", "Account");

    assertThat(claimsRefresh.outcome()).isEqualTo("OK");
    assertThat(claimsRefresh.stored()).isEqualTo(8);
    assertThat(claimsRefresh.skippedWithoutId()).isEqualTo(2);
    assertThat(wealthRefresh.outcome()).isEqualTo("OK");
    // Records that share an odd email are still linked to each other, so the question joins them.
    assertThat(answer(org)).containsExactly("CL-COLON", "CL-LONG", "CL-PIPE");
  }

  @Test
  public void aFailingSourceIsReportedWithItsOwnLastReadNotAnotherSourcesOfTheSameType() throws Exception {
    var org = newOrg();
    var archive = new MockSource("/claims", "listClaims", CLAIMS);
    try {
      connect(org, "claims", pack("claims", claims, "Claim", "listClaims", "claimId"));
      connect(org, "claims-archive", pack("claims-archive", archive, "Claim", "listClaims", "claimId"));
      serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "a@x", null, null, 41)));
      serve("claims-archive", SourceCaller.Status.OK, List.of(claim("OLD-1", "z@x", null, null, 400)));
      Awaitility.await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
          assertThat(ask(org, allClaims()).sources()).extracting(ContextLayerEndpoint.SourceReportView::pack)
              .contains("claims", "claims-archive"));
      long claimsLastRead = report(ask(org, allClaims()), "claims").dataAsOf();
      Thread.sleep(50);

      script.put("claims", SourceCaller.Result.failed("down"));
      var answer = ask(org, allClaims());

      assertThat(report(answer, "claims").freshness()).isEqualTo("STALE");
      assertThat(report(answer, "claims").dataAsOf()).isLessThan(report(answer, "claims-archive").dataAsOf());
      assertThat(report(answer, "claims").dataAsOf()).isLessThanOrEqualTo(claimsLastRead + 5_000);
    } finally {
      archive.close();
    }
  }

  @Test
  public void aRecordWhoseEmailChangesIsNoLongerLinkedUnderTheOldOne() {
    var org = connected();
    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "pat@example.com", null, null, 41)));
    serve("wealth", SourceCaller.Status.OK, List.of(account("W-1", "pat@example.com", null, null, 412000)));
    assertThat(answer(org)).containsExactly("CL-1");

    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "pat.new@example.com", null, null, 41)));

    assertThat(answer(org)).as("the claim no longer shares an email with the account").isEmpty();
  }

  @Test
  public void aRecordDeletedAtTheSourceStopsAppearingInAnswers() {
    var org = connected();
    serve("claims", SourceCaller.Status.OK, List.of(
        claim("CL-1", "pat@example.com", null, null, 41), claim("CL-2", "sam@example.com", null, null, 36)));
    serve("wealth", SourceCaller.Status.OK, List.of(
        account("W-1", "pat@example.com", null, null, 412000), account("W-2", "sam@example.com", null, null, 300000)));
    assertThat(answer(org)).containsExactly("CL-1", "CL-2");

    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "pat@example.com", null, null, 41)));

    assertThat(answer(org)).containsExactly("CL-1");
  }

  @Test
  public void aReadThatWasNotCompleteNeverRemovesAnything() {
    var org = connected();
    serve("claims", SourceCaller.Status.OK, List.of(
        claim("CL-1", "pat@example.com", null, null, 41), claim("CL-2", "sam@example.com", null, null, 36)));
    serve("wealth", SourceCaller.Status.OK, List.of(
        account("W-1", "pat@example.com", null, null, 412000), account("W-2", "sam@example.com", null, null, 300000)));
    assertThat(answer(org)).containsExactly("CL-1", "CL-2");

    // Only the first record came back, because the read stopped early.
    serve("claims", SourceCaller.Status.PARTIAL, List.of(claim("CL-1", "pat@example.com", null, null, 41)));

    assertThat(answer(org)).containsExactly("CL-1", "CL-2");
  }

  @Test
  public void aFailedReadChangesNothingThatIsHeld() {
    var org = connected();
    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "pat@example.com", null, null, 41)));
    serve("wealth", SourceCaller.Status.OK, List.of(account("W-1", "pat@example.com", null, null, 412000)));
    assertThat(answer(org)).containsExactly("CL-1");

    script.put("claims", SourceCaller.Result.failed("down"));

    assertThat(answer(org)).containsExactly("CL-1");
  }

  @Test
  public void aMatchOnNameAndBirthDateIsNotAskedAboutWhenAnEmailMatchAlreadyLinksThem() {
    var org = connected();
    // Both records carry the same email AND the same name and birth date.
    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-1", "pat@example.com", "Pat Lee", "1980-01-02", 41)));
    serve("wealth", SourceCaller.Status.OK, List.of(account("W-1", "pat@example.com", "Pat Lee", "1980-01-02", 412000)));
    assertThat(answer(org)).containsExactly("CL-1");

    // The weaker cluster really exists and is waiting, so an empty queue is not just a view that lags.
    awaitIdentityStatus(org, "name-dob", "PENDING_REVIEW");
    Awaitility.await().during(3, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(reviewQueue(org)).isEmpty());
  }

  @Test
  public void aMatchOnNameAndBirthDateAloneStillWaitsForAPerson() {
    var org = connected();
    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-2", "sam.o@claims.example", "Sam Ortiz", "1990-05-05", 36)));
    serve("wealth", SourceCaller.Status.OK, List.of(account("W-2", "sam@wealth.example", "Sam Ortiz", "1990-05-05", 300000)));
    answer(org);

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      assertThat(reviewQueue(org)).hasSize(1);
      assertThat(reviewQueue(org).get(0).rule()).isEqualTo("name-dob");
    });
  }

  @Test
  public void aDeletedRecordLeavesTheReviewQueueWhenItWasTheOtherHalfOfTheMatch() {
    var org = connected();
    serve("claims", SourceCaller.Status.OK, List.of(claim("CL-2", "sam.o@claims.example", "Sam Ortiz", "1990-05-05", 36)));
    serve("wealth", SourceCaller.Status.OK, List.of(account("W-2", "sam@wealth.example", "Sam Ortiz", "1990-05-05", 300000)));
    answer(org);
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(reviewQueue(org)).hasSize(1));

    serve("wealth", SourceCaller.Status.OK, List.of());
    answer(org);

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(reviewQueue(org)).isEmpty());
  }
}
