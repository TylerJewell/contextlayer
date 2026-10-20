package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ControlClaims;
import io.akka.contextlayer.governance.TrustDocument;
import java.util.Optional;
import java.util.function.Function;

/** Reading a project's trust documents. */
public final class Documents {
  private Documents() {}

  public static String entityId(String project, String name) {
    return ScopedId.of(project, "doc", ScopedId.safe(name));
  }

  public static TrustDocument.Document get(ComponentClient componentClient, String project, String name) {
    return componentClient.forEventSourcedEntity(entityId(project, name)).method(DocumentEntity::get).invoke();
  }

  /** The date the named document was last current, which is what an assertion's coverage is judged by. */
  public static Optional<String> newestAsOf(ComponentClient componentClient, String project, String name) {
    var newest = get(componentClient, project, name).newest();
    return newest == null ? Optional.empty() : Optional.of(newest.asOf());
  }

  public static ControlClaims.CoverageResult coverage(ComponentClient componentClient, String project, ControlClaims claims, long now) {
    Function<String, Optional<String>> lookup = name -> newestAsOf(componentClient, project, name);
    return claims.coverage(now, lookup);
  }
}
