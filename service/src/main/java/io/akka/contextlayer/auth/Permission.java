package io.akka.contextlayer.auth;

/**
 * What a route needs the caller to be allowed to do. Each value maps to one platform permission, so
 * the platform's policy names five permissions for this service rather than one per route.
 */
public enum Permission implements akka.optimize.auth.Permission {
  /** Look at anything in the project. */
  READ("read"),
  /** Feed records in, ask questions, request actions, run monitors, build assistants and packs. */
  OPERATE("operate"),
  /** Judge matters that need a person: identity links, cases, human events, unknown outcomes. */
  REVIEW("review"),
  /** Authorize something to go live or happen: action approvals, releases, stages, packs. */
  APPROVE("approve"),
  /** Waive controls and set where notifications go. */
  ADMINISTER("administer");

  private final String verb;

  Permission(String verb) {
    this.verb = verb;
  }

  @Override
  public String permissionName() {
    return "project.governedContext." + verb;
  }

  @Override
  public String action() {
    return "context." + verb;
  }
}
