package io.akka.contextlayer.api;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.optimize.auth.AuthorizedEndpoint;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Caller;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.application.ReleaseGates;
import io.akka.contextlayer.application.Sampled;
import io.akka.contextlayer.application.Verdicts;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import java.util.EnumSet;
import java.util.function.Supplier;

/**
 * Shared by the endpoints: who is calling, what they may do, a required request body, and how a
 * failure becomes an answer.
 *
 * <p>Every endpoint class carries {@code @JWT(validate = BEARER_TOKEN)}, so the runtime has already
 * verified the token's signature and expiry before a method runs, and a request without a valid
 * token never reaches it. {@link #authorize} then asks the platform rule ({@link Authz}) whether the
 * caller may do what the route needs, and answers 403 when not. The data a request reads and writes
 * is the project's: {@link #project()}.
 */
abstract class PrincipalEndpoint extends AuthorizedEndpoint {

  private final Authz authz;
  private final ProjectResolver projects;
  private final Verdicts verdicts;
  private final ComponentClient componentClient;

  protected PrincipalEndpoint(Authz authz, ProjectResolver projects, ComponentClient componentClient) {
    super(authz);
    this.authz = authz;
    this.projects = projects;
    this.componentClient = componentClient;
    this.verdicts = new Verdicts(componentClient);
  }

  /**
   * The project this request acts for, which keys everything the service stores. An id that cannot be
   * part of an entity id is refused here, so nothing is stored under it.
   */
  protected String project() {
    var project = projects.project(requestContext());
    try {
      return ScopedId.of(project);
    } catch (IllegalArgumentException e) {
      throw HttpException.badRequest("the project id cannot be used: " + e.getMessage());
    }
  }

  /**
   * The caller, once the platform rule has admitted them for the permission the route needs. A
   * subject starting {@code system:} is refused: that prefix names the service's own parts as the
   * proposer of events, and a person must not be able to act under it.
   */
  protected Caller authorize(Permission permission) {
    akka.optimize.auth.Caller admitted;
    try {
      admitted = require(permission);
    } catch (RuntimeException refused) { // the rule refuses by throwing
      refuseCaller(permission, "permission-not-held");
      throw refused;
    }
    var subject = switch (admitted) {
      case akka.optimize.auth.Caller.Operator operator -> operator.name();
    };
    if (subject == null || subject.isBlank() || subject.startsWith("system:")) {
      refuseCaller(permission, "unusable-subject");
      throw HttpException.unauthorized("the token has no usable subject");
    }
    ReleaseGates.recordFor(componentClient, project());
    if (Sampled.firstThisHour(project() + "|" + Controls.CALLER_AUTHORIZATION + "|" + subject + "|" + permission)) {
      verdicts.guardrail(project(), Controls.CALLER_AUTHORIZATION, Sampled.hourKey(subject + "~" + permission), true,
          "permission-held", permission.name(), subject);
    }
    var held = EnumSet.of(permission);
    for (var other : Permission.values()) {
      if (other != permission && holds(other)) held.add(other);
    }
    var issuer = requestContext().getJwtClaims().issuer().orElse("unknown");
    return new Caller(subject, issuer, held);
  }

  /**
   * Records a refused caller under {@link Controls#CALLER_AUTHORIZATION}. A request whose project cannot be told has
   * nowhere to be recorded and is refused all the same.
   */
  private void refuseCaller(Permission permission, String reason) {
    String org;
    try {
      org = project();
    } catch (RuntimeException e) {
      return;
    }
    var subject = requestContext().getJwtClaims().subject().orElse("unknown");
    verdicts.guardrail(org, Controls.CALLER_AUTHORIZATION, Verdicts.once(subject), false, reason, permission.name(),
        subject);
  }

  /**
   * Refuses a request that names something of another project, and records the refusal under
   * {@link Controls#PROJECT_ISOLATION}; a request that stays in its project is recorded once an hour per caller.
   *
   * @param belongs whether the id the request named is one of this project's
   */
  protected void requireOwnProject(boolean belongs, String kind, Caller caller) {
    if (!ownProject(belongs, kind, caller)) throw HttpException.notFound();
  }

  /** As {@link #requireOwnProject}, for a route that answers the refusal itself. */
  protected boolean ownProject(boolean belongs, String kind, Caller caller) {
    if (!belongs) {
      verdicts.guardrail(project(), Controls.PROJECT_ISOLATION, Verdicts.once(kind), false, "other-project", kind,
          caller.subject());
      return false;
    }
    if (Sampled.firstThisHour(project() + "|" + Controls.PROJECT_ISOLATION + "|" + caller.subject() + "|" + kind)) {
      verdicts.guardrail(project(), Controls.PROJECT_ISOLATION, Sampled.hourKey(caller.subject() + "~" + kind), true,
          "own-project", kind, caller.subject());
    }
    return true;
  }

  /** Records verdicts of the controls a route enforces. */
  protected Verdicts verdicts() {
    return verdicts;
  }

  private boolean holds(Permission permission) {
    try {
      authz.authorize(requestContext(), permission);
      return true;
    } catch (RuntimeException refused) { // the rule refuses by throwing
      return false;
    }
  }

  /** A body that was not sent arrives as null; say so plainly instead of failing on the first field. */
  protected static <B> B required(B body) {
    if (body == null) throw HttpException.badRequest("a request body is required");
    return body;
  }

  /**
   * Runs a call to a component. A rule saying no (the component refused the command, or an id or
   * value failed validation, both IllegalArgumentExceptions) is the caller's mistake and becomes a 400 with that message. Anything
   * else is a fault on our side and is left to the runtime to report, which logs the detail and
   * tells the caller only that an error occurred.
   */
  protected static <T> T call(Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (IllegalArgumentException e) { // a refused command is an IllegalArgumentException too
      throw HttpException.badRequest(e.getMessage());
    }
  }
}
