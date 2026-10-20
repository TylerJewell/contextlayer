package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.akka.contextlayer.domain.StructuredQuestion;
import io.akka.contextlayer.domain.StructuredQuestion.Condition;
import io.akka.contextlayer.domain.StructuredQuestion.Link;
import io.akka.contextlayer.domain.StructuredQuestion.Op;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

/**
 * How the entity store behaves with a lot of records. It runs only when asked, because it writes hundreds of thousands of
 * nodes: {@code mvn test -Dtest=ScaleStoreTest -Dscale=true} against a Neo4j server, with {@code -Dscale.claims=200000} and
 * {@code -Dscale.accounts=100000} to change the size. It writes {@code target/scale-report.json} and prints it.
 *
 * <p>The numbers are one machine's, with Neo4j in a container beside the test, so they show how cost grows and where it
 * sits, not what a deployment will measure. The assertions are generous bounds that catch a query that has stopped using
 * an index, not a promise of speed.
 */
class ScaleStoreTest {

  private static final int CLAIMS = Integer.getInteger("scale.claims", 200_000);
  private static final int ACCOUNTS = Integer.getInteger("scale.accounts", 100_000);
  private static final int CHUNK = 5_000;

  private final Map<String, Object> report = new LinkedHashMap<>();

  @Test
  void theStoreAnswersQuestionsOverHundredsOfThousandsOfRecordsWithoutScanningThem() throws Exception {
    assumeTrue("true".equals(System.getProperty("scale")), "pass -Dscale=true to run the scale test");
    var uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
    var driver = GraphDatabase.driver(uri, AuthTokens.basic(System.getenv().getOrDefault("NEO4J_USER", "neo4j"),
        System.getenv().getOrDefault("NEO4J_PASSWORD", "localdevpass123")));
    var store = new Neo4jEntityStore(driver);
    store.ensureSchema();
    var org = "scale-" + UUID.randomUUID().toString().substring(0, 8);
    report.put("claims", CLAIMS);
    report.put("accounts", ACCOUNTS);
    try {
      var regions = List.of("north", "south", "east", "west");
      var statuses = List.of("OPEN", "CLOSED", "PENDING");

      report.put("loadAccountsMillis", time(() -> {
        for (int from = 0; from < ACCOUNTS; from += CHUNK) {
          var rows = new ArrayList<Map<String, Object>>();
          for (int i = from; i < Math.min(ACCOUNTS, from + CHUNK); i++) {
            rows.add(Map.of("accountId", "W-" + i, "balance", 1_000 + (i * 7_919L) % 1_000_000, "tier", i % 3 == 0 ? "gold" : "standard"));
          }
          store.upsert(org, "wealth", "Account", "accountId", rows, 1L);
        }
      }));
      report.put("loadClaimsMillis", time(() -> {
        for (int from = 0; from < CLAIMS; from += CHUNK) {
          var rows = new ArrayList<Map<String, Object>>();
          for (int i = from; i < Math.min(CLAIMS, from + CHUNK); i++) {
            rows.add(Map.of("claimId", "CL-" + i, "status", statuses.get(i % 3), "daysOpen", (i * 13) % 90,
                "region", regions.get(i % 4), "accountId", "W-" + (i % ACCOUNTS)));
          }
          store.upsert(org, "claims", "Claim", "claimId", rows, 1L);
        }
      }));
      report.put("relateClaimsToAccountsMillis", time(() -> {
        for (int from = 0; from < CLAIMS; from += CHUNK) {
          var targets = new LinkedHashMap<String, List<String>>();
          for (int i = from; i < Math.min(CLAIMS, from + CHUNK); i++) targets.put("CL-" + i, List.of("W-" + (i % ACCOUNTS)));
          store.relate(org, "claims", "Claim", "HAS_ACCOUNT", "Account", targets);
        }
      }));

      var simple = new StructuredQuestion("Claim",
          List.of(new Condition("status", Op.EQ, "OPEN"), new Condition("daysOpen", Op.GT, 80)), List.of(), List.of("Claim.claimId"), 100);
      var oneKey = new StructuredQuestion("Claim", List.of(new Condition("claimId", Op.EQ, "CL-" + (CLAIMS / 2))), List.of(), List.of("Claim.claimId"), 10);
      var related = new StructuredQuestion("Claim", List.of(new Condition("status", Op.EQ, "OPEN")),
          List.of(new Link("Account", List.of(new Condition("balance", Op.GT, 990_000)), "HAS_ACCOUNT", StructuredQuestion.Direction.OUT, 1)),
          List.of("Claim.claimId", "Account.balance"), 100);
      report.put("questionOnFieldsMillis", median(() -> assertThat(store.run(org, simple).rows()).hasSize(100)));
      report.put("questionByKeyMillis", median(() -> assertThat(store.run(org, oneKey).rows()).hasSize(1)));
      report.put("questionAcrossARelationshipMillis", median(() -> assertThat(store.run(org, related).rows()).isNotEmpty()));
      report.put("schemaMillis", median(() -> assertThat(store.schema(org)).hasSize(2)));

      var singles = new ArrayList<Long>();
      for (int i = 0; i < 200; i++) {
        var id = "CL-" + (i * 97);
        long started = System.nanoTime();
        store.applyChange(org, "claims", "Claim", id, Map.of("claimId", id, "status", "CLOSED", "daysOpen", 1, "region", "north"), 2L + i, 5L);
        singles.add((System.nanoTime() - started) / 1_000_000);
      }
      report.put("pushedChangeMillisMedian", singles.stream().sorted().toList().get(singles.size() / 2));
      report.put("pushedChangeMillisMax", singles.stream().max(Long::compare).orElse(0L));
      report.put("pushedChangesPerSecondOnOneConnection", Math.round(1000.0 / Math.max(1, (long) report.get("pushedChangeMillisMedian"))));

      report.put("loadClaimsPerSecond", Math.round(CLAIMS * 1000.0 / (long) report.get("loadClaimsMillis")));

      assertThat((long) report.get("questionByKeyMillis")).as("a lookup by key must use the index").isLessThan(500);
      assertThat((long) report.get("questionOnFieldsMillis")).isLessThan(10_000);
      assertThat((long) report.get("questionAcrossARelationshipMillis")).isLessThan(10_000);
    } finally {
      store.deleteOrganization(org);
      driver.close();
      var json = akka.javasdk.JsonSupport.getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
      Files.writeString(Path.of("target", "scale-report.json"), json);
      System.out.println("SCALE REPORT\n" + json);
    }
  }

  private static long time(Runnable work) {
    long started = System.nanoTime();
    work.run();
    return (System.nanoTime() - started) / 1_000_000;
  }

  /** The median of five runs, in milliseconds, after one run that is not counted. */
  private static long median(Runnable work) {
    work.run();
    var runs = new ArrayList<Long>();
    for (int i = 0; i < 5; i++) runs.add(time(work));
    return runs.stream().sorted().toList().get(2);
  }

}
