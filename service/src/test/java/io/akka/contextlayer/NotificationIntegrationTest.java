package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.api.CaseEndpoint;
import io.akka.contextlayer.api.MonitorEndpoint;
import io.akka.contextlayer.api.NotificationEndpoint;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** A new case reaching the team it is routed to, and the record of whether anyone was told. */
public class NotificationIntegrationTest extends LayerTestBase {

  private String admin(String org) {
    return Tokens.bearer("root", org, "admin");
  }

  private int setRoute(String org, String name, String url) {
    return wire().POST("/orgs/" + org + "/routes/" + name.replace(" ", "%20"))
        .addHeader("Authorization", admin(org))
        .withRequestBody(new NotificationEndpoint.RouteRequest(url, "json", null)).invoke().status().intValue();
  }

  private void monitorTo(String org, String route) {
    post("alice", "/orgs/" + org + "/monitors/m1",
        new MonitorEndpoint.MonitorRequest("stalled-claims", stalledClaimsWithWealth(), "Claim.claimId", 60, "", route),
        MonitorEndpoint.MonitorResponse.class);
  }

  private CaseEndpoint.CaseDetail awaitCaseNoted(String org, String text) {
    var found = new CaseEndpoint.CaseDetail[1];
    Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
      var list = get("/orgs/" + org + "/cases", CaseEndpoint.CaseList.class).items();
      assertThat(list).hasSize(1);
      found[0] = get("/orgs/" + org + "/cases/" + list.get(0).caseId(), CaseEndpoint.CaseDetail.class);
      assertThat(found[0].timeline()).extracting(CaseEndpoint.TimelineEntry::text).anyMatch(t -> t.contains(text));
    });
    return found[0];
  }

  private NotificationEndpoint.DeliveryList deliveries(String org) {
    return get("/orgs/" + org + "/notifications", NotificationEndpoint.DeliveryList.class);
  }

  @Test
  public void aNewCaseIsSentToItsRouteWithoutTheEvidence() {
    var org = connectBoth(newOrg());
    assertThat(setRoute(org, "Claims Team B", "http://localhost:9/hook")).isEqualTo(200);

    monitorTo(org, "Claims Team B");

    var detail = awaitCaseNoted(org, "notified Claims Team B: delivered");
    var sent = notifier.to("Claims Team B");
    assertThat(sent).hasSize(1);
    var message = sent.get(0).message();
    assertThat(message.type()).isEqualTo("case.opened");
    assertThat(message.reference()).containsEntry("caseId", detail.caseId()).containsEntry("monitorId", "m1");
    assertThat(message.summary()).contains("m1").contains("Claims Team B");
    assertThat(message.toString()).doesNotContain("412000").doesNotContain("pat@example.com");
    assertThat(sent.get(0).idempotencyKey()).contains(org).contains("nt");

    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(deliveries(org).items()).extracting(NotificationEndpoint.DeliveryItem::status).containsExactly("DELIVERED"));
  }

  @Test
  public void aRouteNobodySetUpIsRecordedAsNobodyTold() {
    var org = connectBoth(newOrg());

    monitorTo(org, "Nowhere Team");

    awaitCaseNoted(org, "notified Nowhere Team: no route");
    assertThat(notifier.sent).isEmpty();
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      var items = deliveries(org).items();
      assertThat(items).hasSize(1);
      assertThat(items.get(0).status()).isEqualTo("NO_ROUTE");
      assertThat(items.get(0).detail()).contains("no delivery is set up");
    });
  }

  @Test
  public void aDeliveryThatFailsIsRecordedWithWhyAndDoesNotStopTheCase() {
    var org = connectBoth(newOrg());
    setRoute(org, "Claims Team B", "http://localhost:9/hook");
    notifier.failing = true;

    monitorTo(org, "Claims Team B");

    var detail = awaitCaseNoted(org, "notified Claims Team B: failed (the receiver answered HTTP 503)");
    assertThat(detail.status()).isEqualTo("OPEN");
    Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(deliveries(org).items()).extracting(NotificationEndpoint.DeliveryItem::status).containsExactly("FAILED"));
  }

  @Test
  public void aSwitchedOffRouteSendsNothing() {
    var org = connectBoth(newOrg());
    setRoute(org, "Claims Team B", "http://localhost:9/hook");
    assertThat(wire().POST("/orgs/" + org + "/routes/Claims%20Team%20B/disable").addHeader("Authorization", admin(org))
        .invoke().status().intValue()).isEqualTo(200);

    monitorTo(org, "Claims Team B");

    awaitCaseNoted(org, "the route is switched off");
    assertThat(notifier.sent).isEmpty();
  }

  @Test
  public void aRouteIsSetUpByAnAdminToAnAllowedHostAndShowsNoSecret() {
    var org = newOrg();
    var operator = Tokens.bearer("olga", org, "operator");
    var path = "/orgs/" + org + "/routes/Claims%20Team%20B";

    assertThat(wire().POST(path).addHeader("Authorization", operator)
        .withRequestBody(new NotificationEndpoint.RouteRequest("http://localhost:9/hook", "json", null)).invoke().status().intValue())
        .isEqualTo(403);
    assertThat(setRoute(org, "Claims Team B", "http://evil.example/hook")).isEqualTo(400);
    assertThat(setRoute(org, "Claims Team B", "file:///etc/passwd")).isEqualTo(400);
    assertThat(setRoute(org, "Claims Team B", "not a url")).isEqualTo(400);
    assertThat(wire().POST(path).addHeader("Authorization", admin(org))
        .withRequestBody(new NotificationEndpoint.RouteRequest("http://localhost:9/hook", "carrier-pigeon", null)).invoke()
        .status().intValue()).isEqualTo(400);

    assertThat(setRoute(org, "Claims Team B", "http://localhost:9/services/T000/B000/secret-token")).isEqualTo(200);
    var shown = wire().GET(path).addHeader("Authorization", Tokens.bearer("reader", org, "viewer"))
        .responseBodyAs(NotificationEndpoint.RouteView.class).invoke().body();
    assertThat(shown.destination()).isEqualTo("http://localhost:9/…");
    assertThat(shown.toString()).doesNotContain("secret-token");
    assertThat(shown.enabled()).isTrue();
  }

  @Test
  public void aTestMessageSaysWhetherARouteWorks() {
    var org = newOrg();
    setRoute(org, "Claims Team B", "http://localhost:9/hook");

    var ok = wire().POST("/orgs/" + org + "/routes/Claims%20Team%20B/test").addHeader("Authorization", admin(org))
        .responseBodyAs(NotificationEndpoint.DeliveryItem.class).invoke().body();
    assertThat(ok.status()).isEqualTo("DELIVERED");
    assertThat(notifier.to("Claims Team B")).hasSize(1);
    assertThat(notifier.to("Claims Team B").get(0).message().type()).isEqualTo("route.test");

    notifier.failing = true;
    var bad = wire().POST("/orgs/" + org + "/routes/Claims%20Team%20B/test").addHeader("Authorization", admin(org))
        .responseBodyAs(NotificationEndpoint.DeliveryItem.class).invoke().body();
    assertThat(bad.status()).isEqualTo("FAILED");
    assertThat(List.of(ok.subject(), bad.subject())).doesNotHaveDuplicates();
    assertThat(Map.of()).isEmpty();
  }

  private akka.javasdk.http.StrictResponse<akka.util.ByteString> setEmailRoute(String org, String name, String url) {
    return wire().POST("/orgs/" + org + "/routes/" + name.replace(" ", "%20")).addHeader("Authorization", admin(org))
        .withRequestBody(new NotificationEndpoint.RouteRequest(url, "email", null)).invoke();
  }

  @Test
  public void aRouteCanBeEmailAddressesAndOnlyWellFormedOnesAreTaken() {
    var org = newOrg();

    var ok = setEmailRoute(org, "Claims Team B", "mailto:claims@example.com, lead@example.com");

    assertThat(ok.status().intValue()).isEqualTo(200);
    var shown = get("/orgs/" + org + "/routes/Claims%20Team%20B", NotificationEndpoint.RouteView.class);
    assertThat(shown.format()).isEqualTo("email");
    assertThat(shown.destination()).isEqualTo("mailto:claims@example.com,lead@example.com");
    assertThat(setEmailRoute(org, "Bad", "mailto:not-an-address").status().intValue()).isEqualTo(400);
    assertThat(setEmailRoute(org, "Bad", "https://example.com/hook").status().intValue()).isEqualTo(400);
  }

  @Test
  public void aNewCaseGoesToAnEmailRouteLikeToAnyOther() {
    var org = connectBoth(newOrg());
    assertThat(setEmailRoute(org, "Claims Team B", "mailto:claims@example.com").status().intValue()).isEqualTo(200);

    monitorTo(org, "Claims Team B");

    awaitCaseNoted(org, "notified Claims Team B: delivered");
    assertThat(notifier.to("Claims Team B")).hasSize(1);
    assertThat(notifier.to("Claims Team B").get(0).route().format()).isEqualTo("email");
  }
}
