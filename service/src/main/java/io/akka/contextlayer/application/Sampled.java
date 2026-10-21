package io.akka.contextlayer.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * For guardrails that pass on every request or every pushed change (caller authorization, project isolation, data
 * intake), one pass record per key an hour shows the guardrail running without a ledger write on every call; every
 * refusal is still recorded. The
 * record's id is keyed by the hour, so instances that each record it write it once between them. This set only saves
 * an instance repeating the write, and forgets everything when it grows large.
 */
public final class Sampled {
  private Sampled() {}

  private static final int LIMIT = 10_000;
  private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

  public static boolean firstThisHour(String key) {
    if (SEEN.size() > LIMIT) SEEN.clear();
    return SEEN.add(hour() + "|" + key);
  }

  /** An evidence id part that is the same for the same fact within the hour. */
  public static String hourKey(String fact) {
    return fact + "~h" + hour();
  }

  private static long hour() {
    return System.currentTimeMillis() / 3_600_000L;
  }
}
