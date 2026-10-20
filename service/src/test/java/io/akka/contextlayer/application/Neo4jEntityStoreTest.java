package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.domain.StructuredQuestion.Condition;
import io.akka.contextlayer.domain.StructuredQuestion.Link;
import io.akka.contextlayer.domain.StructuredQuestion.Op;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * Runs against a real Neo4j: NEO4J_URI (default bolt://localhost:7687), NEO4J_USER (default neo4j),
 * NEO4J_PASSWORD (default localdevpass123). Skipped, with a message, when none is reachable.
 */
class Neo4jEntityStoreTest {

  private static Driver driver;
  private static Neo4jEntityStore store;
  private String orgA;
  private String orgB;

  @BeforeAll
  static void connect() {
    var uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
    var user = System.getenv().getOrDefault("NEO4J_USER", "neo4j");
    var password = System.getenv().getOrDefault("NEO4J_PASSWORD", "localdevpass123");
    try {
      driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
      driver.verifyConnectivity();
      store = new Neo4jEntityStore(driver);
      store.ensureSchema();
    } catch (RuntimeException e) {
      assumeTrue(false, "Neo4j not reachable at " + uri + ": " + e.getMessage());
    }
  }

  @AfterAll
  static void close() {
    if (driver != null) driver.close();
  }

  @BeforeEach
  void orgs() {
    orgA = "org-a-" + UUID.randomUUID().toString().substring(0, 8);
    orgB = "org-b-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @AfterEach
  void clean() {
    store.deleteOrganization(orgA);
    store.deleteOrganization(orgB);
  }

  private static Map<String, Object> claim(String id, String status, int daysOpen) {
    return Map.of("claimId", id, "status", status, "daysOpen", daysOpen);
  }

  private static Map<String, Object> account(String id, int balance) {
    return Map.of("accountId", id, "balance", balance);
  }

  private void linkConfirmed(String org, String srcA, String typeA, String idA, String srcB, String typeB, String idB, String key) {
    store.linkIdentity(org, srcA, typeA, idA, "email", key);
    store.linkIdentity(org, srcB, typeB, idB, "email", key);
    store.setIdentityStatus(org, "email", key, IdentityCluster.Status.CONFIRMED);
  }

  private StructuredQuestion stalledClaimsWithWealth() {
    return new StructuredQuestion("Claim",
        List.of(new Condition("status", Op.EQ, "OPEN"), new Condition("daysOpen", Op.GT, 30)),
        List.of(new Link("Account", List.of(new Condition("balance", Op.GT, 250000)))),
        List.of("Claim.claimId", "Account.balance"), 100);
  }

  @Test
  void storesRecordsAndAnswersASingleTypeQuestion() {
    var result = store.upsert(orgA, "claims", "Claim", "claimId",
        List.of(claim("1", "OPEN", 41), claim("2", "CLOSED", 5), Map.of("status", "OPEN")), 1000L);
    assertThat(result.stored()).isEqualTo(2);
    assertThat(result.skippedWithoutId()).isEqualTo(1);

    var q = new StructuredQuestion("Claim", List.of(new Condition("status", Op.EQ, "OPEN")), List.of(),
        List.of("Claim.claimId", "Claim.daysOpen"), 10);
    var rows = store.run(orgA, q).rows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("Claim.claimId", "1").containsEntry("Claim.daysOpen", 41L);
  }

  @Test
  void aRepeatedUpsertUpdatesInPlaceAndDropsFieldsThatDisappeared() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(Map.of("claimId", "1", "status", "OPEN", "note", "x")), 1000L);
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(Map.of("claimId", "1", "status", "CLOSED")), 2000L);

    var q = new StructuredQuestion("Claim", List.of(), List.of(), List.of("Claim.status", "Claim.note"), 10);
    var rows = store.run(orgA, q).rows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("Claim.status", "CLOSED").containsEntry("Claim.note", null);
    assertThat(store.summary(orgA, "claims", "Claim")).get().satisfies(s -> {
      assertThat(s.count()).isEqualTo(1);
      assertThat(s.lastRefreshedAt()).isEqualTo(2000L);
    });
  }

  @Test
  void joinsEntitiesOfDifferentSourcesThroughConfirmedIdentityOnly() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41), claim("2", "OPEN", 36)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000), account("w2", 288500)), 1L);
    linkConfirmed(orgA, "claims", "Claim", "1", "wealth", "Account", "w1", "pat@example.com");
    // Claim 2 and account w2 share only a cluster still waiting for review.
    store.linkIdentity(orgA, "claims", "Claim", "2", "name-dob", "sam~1990");
    store.linkIdentity(orgA, "wealth", "Account", "w2", "name-dob", "sam~1990");
    store.setIdentityStatus(orgA, "name-dob", "sam~1990", IdentityCluster.Status.PENDING_REVIEW);

    var result = store.run(orgA, stalledClaimsWithWealth());

    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0)).containsEntry("Claim.claimId", "1").containsEntry("Account.balance", 412000L);
    // Claim 2 would match through its pending link, so it is reported as left out, not returned.
    assertThat(result.unconfirmedLeftOut()).isEqualTo(1);
  }

  @Test
  void reportsMatchesLeftOutBecauseTheirIdentityIsUnconfirmed() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("2", "OPEN", 36)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w2", 300000)), 1L);
    store.linkIdentity(orgA, "claims", "Claim", "2", "name-dob", "sam~1990");
    store.linkIdentity(orgA, "wealth", "Account", "w2", "name-dob", "sam~1990");
    store.setIdentityStatus(orgA, "name-dob", "sam~1990", IdentityCluster.Status.PENDING_REVIEW);

    var result = store.run(orgA, stalledClaimsWithWealth());

    assertThat(result.rows()).isEmpty();
    assertThat(result.unconfirmedLeftOut()).isEqualTo(1);

    store.setIdentityStatus(orgA, "name-dob", "sam~1990", IdentityCluster.Status.CONFIRMED);
    assertThat(store.run(orgA, stalledClaimsWithWealth()).rows()).hasSize(1);

    store.setIdentityStatus(orgA, "name-dob", "sam~1990", IdentityCluster.Status.REJECTED);
    var rejected = store.run(orgA, stalledClaimsWithWealth());
    assertThat(rejected.rows()).isEmpty();
    assertThat(rejected.unconfirmedLeftOut()).isEqualTo(0);
  }

  @Test
  void anOrganizationNeverSeesAnotherOrganizationsEntitiesOrLinks() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000)), 1L);
    linkConfirmed(orgA, "claims", "Claim", "1", "wealth", "Account", "w1", "pat@example.com");
    // Organization B holds the same ids and the same identity key, but only its own claim.
    store.upsert(orgB, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 99)), 1L);
    store.linkIdentity(orgB, "claims", "Claim", "1", "email", "pat@example.com");
    store.setIdentityStatus(orgB, "email", "pat@example.com", IdentityCluster.Status.CONFIRMED);

    assertThat(store.run(orgB, stalledClaimsWithWealth()).rows()).isEmpty();
    assertThat(store.run(orgA, stalledClaimsWithWealth()).rows()).hasSize(1);

    var all = new StructuredQuestion("Claim", List.of(), List.of(), List.of("Claim.daysOpen"), 10);
    assertThat(store.run(orgB, all).rows()).extracting(r -> r.get("Claim.daysOpen")).containsExactly(99L);
    assertThat(store.summary(orgB, "wealth", "Account")).isEmpty();
  }

  @Test
  void deletingAnOrganizationRemovesOnlyItsData() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 1)), 1L);
    store.upsert(orgB, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 1)), 1L);

    store.deleteOrganization(orgA);

    assertThat(store.summary(orgA, "claims", "Claim")).isEmpty();
    assertThat(store.summary(orgB, "claims", "Claim")).isPresent();
  }

  @Test
  void valuesThatLookLikeQueryTextAreStoredAndMatchedAsPlainText() {
    var nasty = "x' }) DETACH DELETE n //";
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(Map.of("claimId", "1", "status", nasty)), 1L);
    store.upsert(orgB, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 1)), 1L);

    var q = new StructuredQuestion("Claim", List.of(new Condition("status", Op.EQ, nasty)), List.of(),
        List.of("Claim.status"), 10);
    assertThat(store.run(orgA, q).rows()).hasSize(1);
    assertThat(store.summary(orgB, "claims", "Claim")).isPresent();
  }

  @Test
  void aFieldNamedLikeAStoreProperty_cannotBeUsedToSpoofTheOrganization() {
    // A source record claiming orgId = orgB, stored under orgA, must stay in orgA.
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(Map.of("claimId", "1", "orgId", orgB)), 1L);

    assertThat(store.summary(orgA, "claims", "Claim")).isPresent();
    assertThat(store.summary(orgB, "claims", "Claim")).isEmpty();
  }

  // ---- keeping the graph in step with a source

  private void put(String org, String source, String type, String idField, String... ids) {
    var records = new java.util.ArrayList<Map<String, Object>>();
    for (var id : ids) records.add(Map.of(idField, id));
    store.upsert(org, source, type, idField, records, 1L);
  }

  @Test
  void membershipsAreReportedPerEntityAndOnlyForThatSourceAndType() {
    put(orgA, "claims", "Claim", "claimId", "1", "2");
    put(orgA, "wealth", "Account", "accountId", "w1");
    store.linkIdentity(orgA, "claims", "Claim", "1", "email", "pat@example.com");
    store.linkIdentity(orgA, "claims", "Claim", "1", "name-dob", "pat~1980");
    store.linkIdentity(orgA, "wealth", "Account", "w1", "email", "pat@example.com");

    var memberships = store.identityMemberships(orgA, "claims", "Claim");

    assertThat(memberships).containsOnlyKeys("1");
    assertThat(memberships.get("1")).containsExactlyInAnyOrder(
        new EntityStore.IdentityKey("email", "pat@example.com"), new EntityStore.IdentityKey("name-dob", "pat~1980"));
    assertThat(store.entityIds(orgA, "claims", "Claim")).containsExactlyInAnyOrder("1", "2");
    assertThat(store.entityIds(orgB, "claims", "Claim")).isEmpty();
  }

  @Test
  void unlinkingOneMembershipLeavesTheOthers() {
    put(orgA, "claims", "Claim", "claimId", "1");
    store.linkIdentity(orgA, "claims", "Claim", "1", "email", "a@x");
    store.linkIdentity(orgA, "claims", "Claim", "1", "name-dob", "a~1");

    store.unlinkIdentity(orgA, "claims", "Claim", "1", "email", "a@x");
    store.unlinkIdentity(orgA, "claims", "Claim", "1", "email", "a@x"); // again: nothing to do

    assertThat(store.identityMemberships(orgA, "claims", "Claim").get("1"))
        .containsExactly(new EntityStore.IdentityKey("name-dob", "a~1"));
  }

  @Test
  void deletingEntitiesRemovesThemTheirLinksAndIdentitiesNobodyBelongsToAnyMore() {
    put(orgA, "claims", "Claim", "claimId", "1", "2");
    store.linkIdentity(orgA, "claims", "Claim", "1", "email", "gone@x");
    store.linkIdentity(orgA, "claims", "Claim", "2", "email", "kept@x");
    put(orgB, "claims", "Claim", "claimId", "1");

    int removed = store.deleteEntities(orgA, "claims", "Claim", List.of("1", "missing"));

    assertThat(removed).isEqualTo(1);
    assertThat(store.entityIds(orgA, "claims", "Claim")).containsExactly("2");
    assertThat(store.entityIds(orgB, "claims", "Claim")).containsExactly("1");
    try (var session = driver.session()) {
      var identities = session.run("MATCH (i:Identity {orgId: $org}) RETURN i.key AS key", Map.of("org", orgA))
          .list(r -> r.get("key").asString());
      assertThat(identities).containsExactly("kept@x");
    }
  }

  private void cluster(String org, String key, IdentityCluster.Status status, String... members) {
    for (var member : members) {
      var parts = member.split("/");
      store.linkIdentity(org, parts[0], parts[1], parts[2], "r", key);
    }
    store.setIdentityStatus(org, "r", key, status);
  }

  @Test
  void aClusterIsCoveredWhenItsMembersAreAlreadyConnectedByConfirmedLinks() {
    put(orgA, "claims", "Claim", "claimId", "1");
    put(orgA, "wealth", "Account", "accountId", "w1");
    cluster(orgA, "strong", IdentityCluster.Status.CONFIRMED, "claims/Claim/1", "wealth/Account/w1");
    cluster(orgA, "weak", IdentityCluster.Status.PENDING_REVIEW, "claims/Claim/1", "wealth/Account/w1");

    assertThat(store.coveredByConfirmedLinks(orgA, "r", "weak")).isTrue();
  }

  @Test
  void aClusterIsCoveredThroughSeveralConfirmedHops() {
    put(orgA, "claims", "Claim", "claimId", "1");
    put(orgA, "crm", "Client", "clientId", "c1");
    put(orgA, "wealth", "Account", "accountId", "w1");
    cluster(orgA, "one", IdentityCluster.Status.CONFIRMED, "claims/Claim/1", "crm/Client/c1");
    cluster(orgA, "two", IdentityCluster.Status.CONFIRMED, "crm/Client/c1", "wealth/Account/w1");
    cluster(orgA, "weak", IdentityCluster.Status.PENDING_REVIEW, "claims/Claim/1", "wealth/Account/w1");

    assertThat(store.coveredByConfirmedLinks(orgA, "r", "weak")).isTrue();
  }

  @Test
  void aClusterIsNotCoveredByPendingOrRejectedLinksOrByItself() {
    put(orgA, "claims", "Claim", "claimId", "1");
    put(orgA, "wealth", "Account", "accountId", "w1");
    cluster(orgA, "other-pending", IdentityCluster.Status.PENDING_REVIEW, "claims/Claim/1", "wealth/Account/w1");
    cluster(orgA, "other-rejected", IdentityCluster.Status.REJECTED, "claims/Claim/1", "wealth/Account/w1");
    cluster(orgA, "weak", IdentityCluster.Status.PENDING_REVIEW, "claims/Claim/1", "wealth/Account/w1");

    assertThat(store.coveredByConfirmedLinks(orgA, "r", "weak")).isFalse();
  }

  @Test
  void aClusterWithOneMemberOrNoneHasNothingToDecide() {
    put(orgA, "claims", "Claim", "claimId", "1");
    cluster(orgA, "alone", IdentityCluster.Status.SINGLE, "claims/Claim/1");

    assertThat(store.coveredByConfirmedLinks(orgA, "r", "alone")).isTrue();
    assertThat(store.coveredByConfirmedLinks(orgA, "r", "never-existed")).isTrue();
  }

  @Test
  void coverageIsNeverProvenWithAnotherOrganizationsLinks() {
    put(orgA, "claims", "Claim", "claimId", "1");
    put(orgA, "wealth", "Account", "accountId", "w1");
    cluster(orgA, "weak", IdentityCluster.Status.PENDING_REVIEW, "claims/Claim/1", "wealth/Account/w1");
    // Organization B has a confirmed cluster with the same key and same ids.
    put(orgB, "claims", "Claim", "claimId", "1");
    put(orgB, "wealth", "Account", "accountId", "w1");
    cluster(orgB, "strong", IdentityCluster.Status.CONFIRMED, "claims/Claim/1", "wealth/Account/w1");

    assertThat(store.coveredByConfirmedLinks(orgA, "r", "weak")).isFalse();
  }

  @Test
  void manyThreadsLinkingIntoTheSameIdentityAtOnceAllSucceed() throws Exception {
    int threads = 12;
    var records = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 0; i < threads; i++) records.add(Map.of("claimId", "c" + i));
    store.upsert(orgA, "claims", "Claim", "claimId", records, 1L);

    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var start = new java.util.concurrent.CountDownLatch(1);
    var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    for (int i = 0; i < threads; i++) {
      final String id = "c" + i;
      results.add(pool.submit(() -> {
        start.await();
        store.linkIdentity(orgA, "claims", "Claim", id, "email", "shared@example.com");
        store.setIdentityStatus(orgA, "email", "shared@example.com", IdentityCluster.Status.CONFIRMED);
        return null;
      }));
    }
    start.countDown();
    for (var result : results) result.get(30, java.util.concurrent.TimeUnit.SECONDS);
    pool.shutdown();

    try (var session = driver.session()) {
      var identities = session.run("MATCH (i:Identity {orgId: $org}) RETURN count(i) AS n", Map.of("org", orgA))
          .single().get("n").asLong();
      var links = session.run("MATCH (:Entity {orgId: $org})-[r:MEMBER_OF]->(:Identity) RETURN count(r) AS n",
          Map.of("org", orgA)).single().get("n").asLong();
      assertThat(identities).isEqualTo(1);
      assertThat(links).isEqualTo(threads);
    }
  }

  @Test
  void manyThreadsUpsertingTheSameEntityAtOnceAllSucceed() throws Exception {
    int threads = 12;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var start = new java.util.concurrent.CountDownLatch(1);
    var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    for (int i = 0; i < threads; i++) {
      final int n = i;
      results.add(pool.submit(() -> {
        start.await();
        store.upsert(orgA, "claims", "Claim", "claimId", List.of(Map.of("claimId", "same", "n", n)), 1L);
        return null;
      }));
    }
    start.countDown();
    for (var result : results) result.get(30, java.util.concurrent.TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(store.summary(orgA, "claims", "Claim")).get().satisfies(s -> assertThat(s.count()).isEqualTo(1));
  }

  @Test
  void theSchemaListsTypesFieldsAndKindsAndOnlyThatOrganizationsOwn() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(
        Map.of("claimId", "1", "status", "OPEN", "daysOpen", 41, "urgent", true, "notes", List.of("a", "b")),
        Map.of("claimId", "2", "status", "CLOSED", "daysOpen", 12, "urgent", false, "notes", List.of("c")),
        Map.of("claimId", "3", "status", "OPEN", "daysOpen", 7, "urgent", false, "notes", List.of())), 1000);
    store.upsert(orgB, "claims", "Policy", "policyId", List.of(Map.of("policyId", "p1", "tier", "gold")), 1000);

    var schema = store.schema(orgA);

    assertThat(schema).extracting(EntityStore.TypeSchema::entityType).containsExactly("Claim");
    var claim = schema.get(0);
    assertThat(claim.count()).isEqualTo(3);
    assertThat(claim.fields()).extracting(EntityStore.FieldSchema::name)
        .containsExactly("claimId", "daysOpen", "status", "urgent");
    assertThat(claim.fields()).filteredOn(f -> f.name().equals("daysOpen")).extracting(EntityStore.FieldSchema::kind)
        .containsExactly("number");
    assertThat(claim.fields()).filteredOn(f -> f.name().equals("urgent")).extracting(EntityStore.FieldSchema::kind)
        .containsExactly("boolean");
    var status = claim.fields().stream().filter(f -> f.name().equals("status")).findFirst().orElseThrow();
    assertThat(status.kind()).isEqualTo("string");
    assertThat(status.distinct()).isEqualTo(2);
    assertThat(status.sampled()).isEqualTo(3);
    assertThat(status.values()).containsExactlyInAnyOrder("OPEN", "CLOSED");
    assertThat(store.schema(orgB)).extracting(EntityStore.TypeSchema::entityType).containsExactly("Policy");
  }

  // ---- named relationships

  private StructuredQuestion claimsWithAccount(StructuredQuestion.Direction direction, int depth, String via, Condition... on) {
    return new StructuredQuestion("Claim", List.of(),
        List.of(new Link("Account", List.of(on), via, direction, depth)), List.of("Claim.claimId", "Account.balance"), 100);
  }

  private void relateClaimsToAccounts(Map<String, List<String>> targets) {
    store.relate(orgA, "claims", "Claim", "HAS_ACCOUNT", "Account", targets);
  }

  @Test
  void aQuestionFollowsANamedRelationshipToTheRecordsItPointsAt() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41), claim("2", "OPEN", 36)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000), account("w2", 9000)), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1"), "2", List.of("w2")));

    var rich = store.run(orgA, claimsWithAccount(StructuredQuestion.Direction.OUT, 1, "HAS_ACCOUNT", new Condition("balance", Op.GT, 250000)));

    assertThat(rich.rows()).singleElement().satisfies(r -> {
      assertThat(r.get("Claim.claimId")).isEqualTo("1");
      assertThat(r.get("Account.balance")).isEqualTo(412000L);
    });
    assertThat(rich.unconfirmedLeftOut()).as("no identity link was used, so none is waiting").isZero();
  }

  @Test
  void aRelationshipToARecordThatArrivesLaterIsJoinedWhenItDoes() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1")));
    var question = claimsWithAccount(StructuredQuestion.Direction.OUT, 1, "HAS_ACCOUNT");
    assertThat(store.run(orgA, question).rows()).as("nothing to follow to yet").isEmpty();
    assertThat(store.schema(orgA)).extracting(EntityStore.TypeSchema::entityType).containsExactly("Claim");

    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000)), 2L);

    assertThat(store.run(orgA, question).rows()).singleElement().satisfies(r -> assertThat(r.get("Account.balance")).isEqualTo(412000L));
  }

  @Test
  void relatingAgainReplacesWhatTheRelationshipPointedAt() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000), account("w2", 9000)), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1")));

    relateClaimsToAccounts(Map.of("1", List.of("w2")));

    var rows = store.run(orgA, claimsWithAccount(StructuredQuestion.Direction.OUT, 1, "HAS_ACCOUNT")).rows();
    assertThat(rows).singleElement().satisfies(r -> assertThat(r.get("Account.balance")).isEqualTo(9000L));
  }

  @Test
  void aRecordThatIsDeletedLeavesItsReferrersPointingAtItSoItCanComeBack() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000)), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1")));
    var question = claimsWithAccount(StructuredQuestion.Direction.OUT, 1, "HAS_ACCOUNT");

    store.deleteEntities(orgA, "wealth", "Account", List.of("w1"));
    assertThat(store.run(orgA, question).rows()).isEmpty();
    assertThat(store.summary(orgA, "wealth", "Account")).isEmpty();

    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 500000)), 3L);

    assertThat(store.run(orgA, question).rows()).singleElement().satisfies(r -> assertThat(r.get("Account.balance")).isEqualTo(500000L));
  }

  @Test
  void aRelationshipCanBeFollowedInReverseAndForSeveralSteps() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    store.upsert(orgA, "wealth", "Account", "accountId", List.of(account("w1", 412000), account("w2", 9000)), 1L);
    store.upsert(orgA, "wealth", "Branch", "branchId", List.of(Map.of("branchId", "b1", "city", "Leeds")), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1")));
    store.relate(orgA, "wealth", "Account", "HELD_AT", "Branch", Map.of("w1", List.of("b1")));

    var reverse = new StructuredQuestion("Account", List.of(),
        List.of(new Link("Claim", List.of(), "HAS_ACCOUNT", StructuredQuestion.Direction.IN, 1)),
        List.of("Account.accountId", "Claim.claimId"), 100);
    assertThat(store.run(orgA, reverse).rows()).singleElement().satisfies(r -> assertThat(r.get("Account.accountId")).isEqualTo("w1"));

    var twoSteps = new StructuredQuestion("Claim", List.of(),
        List.of(new Link("Branch", List.of(new Condition("city", Op.EQ, "Leeds")), "HELD_AT", StructuredQuestion.Direction.OUT, 1)),
        List.of("Claim.claimId", "Branch.city"), 100);
    assertThat(store.run(orgA, twoSteps).rows()).as("HELD_AT is not a step from a claim").isEmpty();
  }

  @Test
  void relationshipsDoNotCrossOrganizations() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    store.upsert(orgB, "wealth", "Account", "accountId", List.of(account("w1", 412000)), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1")));

    assertThat(store.run(orgA, claimsWithAccount(StructuredQuestion.Direction.OUT, 1, "HAS_ACCOUNT")).rows()).isEmpty();
  }

  @Test
  void theSchemaNamesTheRelationshipsARecordTypeHas() {
    store.upsert(orgA, "claims", "Claim", "claimId", List.of(claim("1", "OPEN", 41)), 1L);
    relateClaimsToAccounts(Map.of("1", List.of("w1")));

    var claim = store.schema(orgA).get(0);

    assertThat(claim.relations()).containsExactly(new EntityStore.Relation("HAS_ACCOUNT", "Account"));
  }
}
