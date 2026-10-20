package io.akka.contextlayer.api;

import akka.javasdk.http.HttpException;
import akka.optimize.auth.AuthorizedEndpoint;
import akka.optimize.auth.Authz;
import io.akka.contextlayer.auth.Caller;
import io.akka.contextlayer.auth.Permission;
import io.akka.contextlayer.auth.ProjectResolver;
import io.akka.contextlayer.domain.ScopedId;
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

  protected PrincipalEndpoint(Authz authz, ProjectResolver projects) {
    super(authz);
    this.authz = authz;
    this.projects = projects;
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
    var admitted = require(permission);
    var subject = switch (admitted) {
      case akka.optimize.auth.Caller.Operator operator -> operator.name();
    };
    if (subject == null || subject.isBlank() || subject.startsWith("system:")) {
      throw HttpException.unauthorized("the token has no usable subject");
    }
    var held = EnumSet.of(permission);
    for (var other : Permission.values()) {
      if (other != permission && holds(other)) held.add(other);
    }
    var issuer = requestContext().getJwtClaims().issuer().orElse("unknown");
    return new Caller(subject, issuer, held);
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
