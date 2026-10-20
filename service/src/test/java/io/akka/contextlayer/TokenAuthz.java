package io.akka.contextlayer;

import akka.javasdk.http.HttpException;
import akka.javasdk.http.RequestContext;
import akka.optimize.auth.Authz;
import akka.optimize.auth.Caller;
import akka.optimize.auth.Permission;
import io.akka.contextlayer.auth.Role;
import java.util.HashSet;
import java.util.List;

/**
 * The platform rule as a test sees it: a caller holds the permissions of the roles named in the
 * token's {@code roles} claim. The platform decides this from a project's roles; a test token
 * carries them instead.
 */
public final class TokenAuthz implements Authz {

  @Override
  public Caller authorize(RequestContext context, Permission required) {
    var claims = context.getJwtClaims();
    var held = new HashSet<akka.optimize.auth.Permission>();
    var names = claims.getStringList("roles").orElseGet(() -> claims.getString("roles").map(List::of).orElse(List.of()));
    names.forEach(name -> Role.parse(name).ifPresent(role -> held.addAll(role.permissions())));
    if (!held.contains(required)) throw HttpException.forbidden("requires permission " + required.action());
    return new Caller.Operator(claims.subject().orElse(""));
  }
}
