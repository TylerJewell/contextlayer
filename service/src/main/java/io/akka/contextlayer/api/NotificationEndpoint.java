package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import io.akka.contextlayer.application.Sampled;
import io.akka.contextlayer.application.Verdicts;
import io.akka.contextlayer.governance.Controls;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.contextlayer.application.DeliveryView;
import io.akka.contextlayer.application.EgressPolicy;
import io.akka.contextlayer.application.NotificationRouteEntity;
import io.akka.contextlayer.application.SmtpNotifier;
import io.akka.contextlayer.application.Notifications;
import io.akka.contextlayer.application.Notifier;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where the people a case or an approval is routed to are told. A route is a name (the "route to" of
 * a monitor, or {@code role:approver}) and a web address that receives a JSON post, or a Slack
 * incoming webhook. Setting one up sends records' existence, never their contents, to an outside
 * address, so it needs the admin role and the address must be on the egress list.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context")
public class NotificationEndpoint extends PrincipalEndpoint {

  private static final int MAX_NAME = 100;

  public record RouteRequest(String url, String format, String credentialRef) {}

  /** The address is shown without its path or query, which can carry a secret (a Slack webhook does). */
  public record RouteView(String name, boolean enabled, String destination, String format, boolean hasCredential) {}

  public record DeliveryItem(String route, String type, String subject, String status, String detail, String at) {}

  public record DeliveryList(List<DeliveryItem> items) {}

  private final ComponentClient componentClient;
  private final EgressPolicy egress;
  private final Notifier notifier;

  public NotificationEndpoint(ComponentClient componentClient, EgressPolicy egress, Notifier notifier, Authz authz, ProjectResolver projects) {
    super(authz, projects, componentClient);
    this.componentClient = componentClient;
    this.egress = egress;
    this.notifier = notifier;
  }

  @Post("/routes/{name}")
  public RouteView setRoute(String name, RouteRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    checkName(name);
    var format = body.format() == null || body.format().isBlank() ? "json" : body.format();
    if (!List.of("json", "slack", "email").contains(format)) throw HttpException.badRequest("format must be json, slack or email");
    if (format.equals("email")) {
      var addresses = SmtpNotifier.recipients(body.url());
      if (addresses.isEmpty()) {
        throw HttpException.badRequest("an email route's url is mailto: followed by one to " + SmtpNotifier.MAX_RECIPIENTS + " addresses, separated by commas");
      }
      var state = call(() -> componentClient.forKeyValueEntity(NotificationRouteEntity.id(project(), name))
          .method(NotificationRouteEntity::set)
          .invoke(new NotificationRouteEntity.Set("mailto:" + String.join(",", addresses), format, "", caller.subject(),
              System.currentTimeMillis())));
      return view(name, state);
    }
    URI uri;
    try {
      uri = URI.create(body.url() == null ? "" : body.url().trim());
    } catch (IllegalArgumentException e) {
      throw HttpException.badRequest("url is not a valid address");
    }
    if (uri.getHost() == null || (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))) {
      throw HttpException.badRequest("url must be an http or https address");
    }
    if (!egress.allows(uri.getHost())) {
      verdicts().guardrail(project(), Controls.EGRESS, Verdicts.once(name), false, "host-not-allowed",
          "notification route " + name, caller.subject());
      throw HttpException.badRequest("that host is not on the allowed list of hosts");
    }
    var state = call(() -> componentClient.forKeyValueEntity(NotificationRouteEntity.id(project(), name))
        .method(NotificationRouteEntity::set)
        .invoke(new NotificationRouteEntity.Set(uri.toString(), format, body.credentialRef(), caller.subject(),
            System.currentTimeMillis())));
    return view(name, state);
  }

  @Get("/routes/{name}")
  public RouteView getRoute(String name) {
    authorize(Permission.READ);
    checkName(name);
    var state = call(() -> componentClient.forKeyValueEntity(NotificationRouteEntity.id(project(), name))
        .method(NotificationRouteEntity::get).invoke());
    if (!state.exists()) throw HttpException.notFound();
    return view(name, state);
  }

  @Post("/routes/{name}/disable")
  public RouteView disableRoute(String name) {
    var caller = authorize(Permission.ADMINISTER);
    checkName(name);
    var state = call(() -> componentClient.forKeyValueEntity(NotificationRouteEntity.id(project(), name))
        .method(NotificationRouteEntity::disable)
        .invoke(new NotificationRouteEntity.Set("", "json", "", caller.subject(), System.currentTimeMillis())));
    return view(name, state);
  }

  /** Sends a test message and says how it went, so a route is known to work before a case depends on it. */
  @Post("/routes/{name}/test")
  public DeliveryItem testRoute(String name) {
    authorize(Permission.ADMINISTER);
    checkName(name);
    long now = System.currentTimeMillis();
    var message = new Notifier.Message("route.test", project(), name, "Test message",
        "This checks that " + name + " receives messages from the context layer.", Map.of(), null,
        Instant.ofEpochMilli(now).toString());
    var subject = "test:" + now + ":" + java.util.UUID.randomUUID().toString().substring(0, 8);
    var delivery = call(() -> Notifications.send(componentClient, notifier, project(), name, subject, message));
    return item(delivery);
  }

  @Get("/notifications")
  public DeliveryList notifications() {
    authorize(Permission.READ);
    var rows = componentClient.forView().method(DeliveryView::forOrganization)
        .invoke(new DeliveryView.ForOrganization(project(), 200)).items();
    return new DeliveryList(rows.stream().map(r -> new DeliveryItem(r.route(), r.type(), r.subject(), r.status(),
        r.detail(), Instant.ofEpochMilli(r.at()).toString())).toList());
  }

  private static void checkName(String name) {
    if (name == null || name.isBlank() || name.length() > MAX_NAME || name.chars().anyMatch(Character::isISOControl)) {
      throw HttpException.badRequest("a route name is 1 to " + MAX_NAME + " characters with no control characters");
    }
  }

  /**
   * A web address can carry a secret in its path, so only where it goes is shown (control custom-cl0020, recorded once
   * an hour for each route). Email addresses carry none.
   */
  private RouteView view(String name, NotificationRouteEntity.State s) {
    var uri = URI.create(s.url().isBlank() ? "http://unset" : s.url());
    boolean web = !s.url().isBlank() && !"email".equals(s.format());
    var destination = s.url().isBlank() ? "" : web ? uri.getScheme() + "://" + uri.getAuthority() + "/…" : s.url();
    if (web && Sampled.firstThisHour(project() + "|" + Controls.ROUTE_SECRET_REDACTION + "|" + name)) {
      var hidden = uri.getRawPath() != null && uri.getRawPath().length() > 1 || uri.getRawQuery() != null;
      verdicts().sanitizer(project(), Controls.ROUTE_SECRET_REDACTION, Sampled.hourKey(name), hidden ? 1 : 0,
          "notification route " + name, "system:routes");
    }
    return new RouteView(name, s.enabled(), destination, s.format(), !s.credentialRef().isBlank());
  }

  private static DeliveryItem item(io.akka.contextlayer.application.DeliveryEntity.Delivery d) {
    return new DeliveryItem(d.route(), d.type(), d.subject(), d.status(), d.detail(), Instant.ofEpochMilli(d.at()).toString());
  }
}
