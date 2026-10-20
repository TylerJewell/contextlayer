package io.akka.contextlayer;

import akka.javasdk.JsonSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Bearer tokens for tests. The runtime does not check signatures when it runs integration tests, so
 * a token is a header and a claims object with the algorithm {@code none}; the claims are still read
 * and enforced as they would be in production.
 */
public final class Tokens {
  public static final String ISSUER = "test-idp";

  private Tokens() {}

  /** A header value for a person with the given roles in one organization. */
  public static String bearer(String subject, String orgId, String... roles) {
    return bearer(subject, List.of(orgId), roles);
  }

  public static String bearer(String subject, List<String> orgs, String... roles) {
    var claims = Map.of("iss", ISSUER, "sub", subject, "roles", List.of(roles), "orgs", orgs);
    try {
      var encoder = Base64.getUrlEncoder().withoutPadding();
      var header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes());
      var payload = encoder.encodeToString(JsonSupport.getObjectMapper().writeValueAsBytes(claims));
      return "Bearer " + header + "." + payload;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** An admin of the organization a request path names ("/orgs/{org}/..."). */
  public static String admin(String subject, String path) {
    return bearer(subject, orgOf(path), "admin");
  }

  public static String orgOf(String path) {
    var parts = path.split("/");
    if (parts.length < 3 || !parts[1].equals("orgs")) throw new IllegalArgumentException("not an org path: " + path);
    return parts[2];
  }
}
