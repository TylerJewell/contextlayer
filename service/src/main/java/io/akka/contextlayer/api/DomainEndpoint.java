package io.akka.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.application.DomainAccess;
import io.akka.contextlayer.application.DomainConfigEntity;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.DataDomain;
import io.akka.contextlayer.domain.DomainConfig;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Data domains: groups of entity types that are granted to roles and people together, and the
 * approved links between them. A project with none allows every question; with one, a question may
 * read only what the asker is granted (see {@link DomainConfig}).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
@HttpEndpoint("/v1/context")
public class DomainEndpoint extends PrincipalEndpoint {

  /** @param rationale why, recorded with the administrator's decision (control custom-cl0042) */
  public record DomainRequest(
      String description, List<String> entityTypes, List<String> readerRoles, List<String> readerPeople,
      String rationale) {

    public DomainRequest(String description, List<String> entityTypes, List<String> readerRoles, List<String> readerPeople) {
      this(description, entityTypes, readerRoles, readerPeople, null);
    }
  }

  public record ReasonRequest(String rationale) {}

  public record DomainView(
      String name, String description, List<String> entityTypes, List<String> readerRoles, List<String> readerPeople,
      String changedBy, long changedAt) {}

  public record JoinView(String a, String b, String approvedBy, String rationale, long at) {}

  /** @param governed false until the first data domain is defined; until then every question is allowed */
  public record DomainList(boolean governed, List<DomainView> domains, List<JoinView> joins) {}

  public record JoinRequest(String a, String b, String rationale) {}

  public record JoinPair(String a, String b, String rationale) {

    public JoinPair(String a, String b) {
      this(a, b, null);
    }
  }

  private final ComponentClient componentClient;

  public DomainEndpoint(ComponentClient componentClient, Authz authz, ProjectResolver projects) {
    super(authz, projects, componentClient);
    this.componentClient = componentClient;
  }

  @Get("/domains")
  public DomainList list() {
    authorize(Permission.READ);
    return toList(DomainAccess.config(componentClient, project()));
  }

  @Post("/domains/{name}")
  public DomainView set(String name, DomainRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    var domain = call(() -> new DataDomain(name, body.description(), body.entityTypes(), body.readerRoles(),
        body.readerPeople(), caller.subject(), System.currentTimeMillis()));
    var eventId = decide(caller, "set", name, "Set data domain " + name + " to " + String.join(", ", domain.entityTypes()),
        Map.of("domain", name), body.rationale());
    var config = GateDecisions.applying(componentClient, eventId, "domain set", () -> call(() ->
        componentClient.forEventSourcedEntity(project()).method(DomainConfigEntity::set).invoke(domain)));
    return toView(config.domains().get(name));
  }

  @Post("/domains/{name}/remove")
  public DomainList remove(String name, ReasonRequest body) {
    var caller = authorize(Permission.ADMINISTER);
    var eventId = decide(caller, "remove", name, "Remove data domain " + name, Map.of("domain", name),
        body == null ? null : body.rationale());
    var config = GateDecisions.applying(componentClient, eventId, "domain removed", () -> call(() ->
        componentClient.forEventSourcedEntity(project()).method(DomainConfigEntity::remove)
            .invoke(new DomainConfigEntity.RemoveDomain(name, caller.subject(), System.currentTimeMillis()))));
    return toList(config);
  }

  /**
   * Approves linking two domains in one question. It is a decision under {@link Controls#DOMAIN_JOIN}:
   * the approver has to be someone other than whoever last changed either domain, because changing a
   * domain is how its contents and readers are decided.
   */
  @Post("/join-rules")
  public DomainList approveJoin(JoinRequest body) {
    var caller = authorize(Permission.APPROVE);
    required(body);
    if (body.a() == null || body.b() == null) throw HttpException.badRequest("name the two data domains");
    var config = DomainAccess.config(componentClient, project());
    var a = config.domains().get(body.a());
    var b = config.domains().get(body.b());
    if (a == null || b == null) throw HttpException.badRequest("both data domains have to exist");
    for (var domain : List.of(a, b)) {
      if (domain.changedBy().equals(caller.subject())) {
        var reason = "you last changed the " + domain.name() + " data domain, so someone else has to approve linking it";
        verdicts().humanRefusal(project(), Controls.DOMAIN_JOIN, body.a() + " " + body.b(), caller.subject(), reason);
        throw HttpException.forbidden(reason);
      }
    }
    long now = System.currentTimeMillis();
    var eventId = GateDecisions.decide(componentClient, caller, project(), Controls.DOMAIN_JOIN,
        ScopedId.of(project(), "he", "join", body.a(), body.b(), String.valueOf(now)), "change",
        "Allow questions to link the " + body.a() + " and " + body.b() + " data domains", "",
        Map.of("a", body.a(), "b", body.b()),
        "Should one question be able to link the " + body.a() + " and " + body.b() + " data domains?",
        a.changedBy(), "APPROVE", body.rationale());
    var approved = GateDecisions.applying(componentClient, eventId, "join rule approved", () -> call(() ->
        componentClient.forEventSourcedEntity(project()).method(DomainConfigEntity::approveJoin)
            .invoke(new DomainConfigEntity.ApproveJoin(body.a(), body.b(), caller.subject(), body.rationale(), now))));
    return toList(approved);
  }

  @Post("/join-rules/revoke")
  public DomainList revokeJoin(JoinPair body) {
    var caller = authorize(Permission.ADMINISTER);
    required(body);
    if (body.a() == null || body.b() == null) throw HttpException.badRequest("name the two data domains");
    var eventId = decide(caller, "revoke", body.a() + "-" + body.b(),
        "Stop questions linking the " + body.a() + " and " + body.b() + " data domains", Map.of("a", body.a(), "b", body.b()),
        body.rationale());
    var config = GateDecisions.applying(componentClient, eventId, "join rule revoked", () -> call(() ->
        componentClient.forEventSourcedEntity(project()).method(DomainConfigEntity::revokeJoin)
            .invoke(new DomainConfigEntity.RevokeJoin(body.a(), body.b(), caller.subject(), System.currentTimeMillis()))));
    return toList(config);
  }

  /** An administrator's change to the data domains, recorded as a decision under custom-cl0042 before it is made. */
  private String decide(
      io.akka.contextlayer.auth.Caller caller, String verb, String target, String summary, Map<String, String> detail,
      String rationale) {
    long now = System.currentTimeMillis();
    return GateDecisions.decide(componentClient, caller, project(), Controls.DOMAIN_ADMINISTRATION,
        call(() -> ScopedId.of(project(), "he", "domain", verb, ScopedId.safe(target), String.valueOf(now))), "change",
        summary, "", detail, summary + "?", "system:domains", "APPROVE", rationale);
  }

  private static DomainList toList(DomainConfig config) {
    var domains = config.domains().values().stream().sorted(Comparator.comparing(DataDomain::name))
        .map(DomainEndpoint::toView).toList();
    var joins = config.joins().stream().sorted(Comparator.comparing(DomainConfig.JoinRule::a).thenComparing(DomainConfig.JoinRule::b))
        .map(j -> new JoinView(j.a(), j.b(), j.approvedBy(), j.rationale(), j.at())).toList();
    return new DomainList(config.governed(), domains, joins);
  }

  private static DomainView toView(DataDomain d) {
    return new DomainView(d.name(), d.description(), d.entityTypes(), d.readerRoles(), d.readerPeople(), d.changedBy(), d.changedAt());
  }
}
