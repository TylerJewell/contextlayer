package io.akka.contextlayer.domain;

import java.util.List;

/**
 * What the last refresh of one entity type from one connector pack did. Questions report it so a
 * reader can see how current each source was and whether it failed (REQUIREMENTS QRY-03).
 */
public record RefreshStatus(
    String orgId,
    String packName,
    String entityType,
    Outcome outcome,
    long refreshedAt,
    int stored,
    int skippedWithoutId,
    String packHash,
    List<String> warnings,
    boolean exists) {

  /** NEVER means no refresh has been recorded. */
  public enum Outcome { NEVER, OK, PARTIAL, FAILED }

  public static RefreshStatus empty() {
    return new RefreshStatus("", "", "", Outcome.NEVER, 0, 0, 0, "", List.of(), false);
  }

  public static RefreshStatus of(
      String orgId, String packName, String entityType, Outcome outcome, long at, int stored, int skipped,
      String packHash, List<String> warnings) {
    return new RefreshStatus(orgId, packName, entityType, outcome, at, stored, skipped, packHash, warnings, true);
  }

  /**
   * Whether the stored data can be used as is under a CACHED policy of {@code ttlMinutes}. Only a
   * complete read counts: a read that stopped early is not held on to, so the next question tries again.
   */
  public boolean freshWithin(int ttlMinutes, long now) {
    return exists && outcome == Outcome.OK && now - refreshedAt < ttlMinutes * 60_000L;
  }
}
