package io.akka.contextlayer.auth;

import akka.javasdk.http.RequestContext;

/**
 * The project a request acts for. A deployment of this service belongs to one project, so the
 * answer does not depend on the caller; it is a dependency so that a test can run several projects
 * against one service.
 */
public interface ProjectResolver {

  String project(RequestContext context);

  /** The project this deployment runs in. */
  static ProjectResolver fixed(String projectId) {
    return context -> projectId;
  }
}
