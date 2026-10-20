package io.akka.contextlayer;

import akka.javasdk.http.HttpException;
import akka.javasdk.http.RequestContext;
import io.akka.contextlayer.auth.ProjectResolver;
import java.util.List;

/**
 * The project a test request is for: the first entry of the token's {@code orgs} claim. A deployment
 * serves one project; a test suite serves a new one for each test, so that tests do not see each
 * other's data.
 */
public final class TokenProject implements ProjectResolver {

  @Override
  public String project(RequestContext context) {
    var claims = context.getJwtClaims();
    var orgs = claims.getStringList("orgs").orElseGet(() -> claims.getString("orgs").map(List::of).orElse(List.of()));
    if (orgs.isEmpty()) throw HttpException.forbidden("the test token names no project");
    return orgs.get(0);
  }
}
