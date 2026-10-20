package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.application.EntityStore;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

/**
 * A fault on our side (here, an entity store that fails with a message naming a host and a password)
 * must not be presented as the caller's mistake. A 400 tells a caller to change what they sent, and
 * would carry the message to them.
 */
public class InternalFailureIntegrationTest extends TestKitSupport {
private Wire wire() {    return new Wire(httpClient);  }

  private static final String SECRET_MESSAGE = "cannot reach bolt://db.internal:7687 with password=hunter2";

  @Override
  protected TestKit.Settings testKitSettings() {
    EntityStore exploding = (EntityStore) Proxy.newProxyInstance(
        EntityStore.class.getClassLoader(), new Class<?>[] {EntityStore.class},
        (proxy, method, args) -> {
          throw new IllegalStateException(SECRET_MESSAGE);
        });
    return TestDependencies.settings().withDependencyProvider(TestDependencies.provider(
        (org, type, payload) -> ActionExecutor.Result.ok("unused"), TestDependencies.NO_SOURCES, exploding));
  }

  @Test
  public void aStoreFailureIsAServerErrorNotABadRequest() {
    var question = "{\"entityType\":\"Claim\",\"where\":[],\"links\":[],\"returnFields\":[\"Claim.claimId\"],\"limit\":5}";

    var response = wire().POST("/orgs/acme/questions").addHeader("Authorization", Tokens.admin("tester", "/orgs/acme/x"))
        .withRequestBody(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, question.getBytes()).invoke();

    assertThat(response.status().intValue()).isEqualTo(500);
  }

  @Test
  public void aRuleSayingNoIsStillABadRequestWithItsMessage() {
    var response = wire().POST("/orgs/acme/assistants/a1").addHeader("Authorization", Tokens.admin("tester", "/orgs/acme/x"))
        .withRequestBody(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, "{\"purpose\":\"\"}".getBytes()).invoke();

    assertThat(response.status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String()).contains("purpose is required");
  }
}
