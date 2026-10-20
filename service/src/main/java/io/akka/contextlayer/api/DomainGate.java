package io.akka.contextlayer.api;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.contextlayer.application.DomainAccess;
import io.akka.contextlayer.auth.Caller;
import java.util.Collection;

/** Refuses, with the reasons, a request that reads data its caller is not granted. */
final class DomainGate {
  private DomainGate() {}

  static void require(ComponentClient componentClient, String project, Caller caller, Collection<String> entityTypes) {
    var access = DomainAccess.check(componentClient, project, caller, entityTypes);
    if (!access.allowed()) throw HttpException.forbidden(String.join("; ", access.problems()));
  }
}
