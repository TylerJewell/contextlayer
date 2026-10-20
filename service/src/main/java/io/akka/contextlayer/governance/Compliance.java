package io.akka.contextlayer.governance;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Where each control stands, worked out from its evidence. Only enforcing evidence counts: shadow,
 * advisory and test records say what would have happened, never that something was protected.
 *
 * <p>The statuses, in the order they are decided:
 * <ul>
 *   <li>{@code WAIVED}: a waiver is in force.
 *   <li>{@code NO_EVIDENCE}: the control has never been seen enforcing.
 *   <li>{@code FAILING}: a breach or an error within the freshness window. A breach is not erased by
 *       later passes until it ages out of the window, so a control that failed this week is not shown
 *       as healthy because it passed yesterday.
 *   <li>{@code STALE}: the latest enforcing evidence is older than the window.
 *   <li>{@code SATISFIED}: enforcing evidence upheld the control within the window, with no breach.
 *   <li>{@code UNRESOLVED}: enforcing evidence within the window, none of which upheld or breached it.
 * </ul>
 */
public final class Compliance {
  private Compliance() {}

  public enum Status {
    SATISFIED,
    FAILING,
    UNRESOLVED,
    STALE,
    NO_EVIDENCE,
    WAIVED
  }

  /** One piece of evidence reduced to what the calculation reads. */
  public record Item(EvaluationMode mode, Verdict verdict, boolean flagged, long at) {}

  /** @param waivedUntil 0 for a waiver that does not lapse */
  public record Waiver(String reason, String by, long waivedUntil) {
    public boolean inForce(long now) {
      return waivedUntil == 0 || waivedUntil > now;
    }
  }

  /**
   * @param enforcing enforcing evidence seen in the window
   * @param flagged how many of those carried a warning (such as a rushed decision)
   * @param lastEnforcingAt when the control last enforced, or 0 if it never has
   */
  public record Standing(
      Status status, long enforcing, long upheld, long breached, long flagged, long lastEnforcingAt, String why) {}

  public static Standing assess(List<Item> evidence, Waiver waiver, Duration window, long now) {
    long since = now - window.toMillis();
    var enforcingEver = evidence.stream().filter(i -> i.mode() == EvaluationMode.ENFORCING).toList();
    long last = enforcingEver.stream().map(Item::at).max(Comparator.naturalOrder()).orElse(0L);
    var inWindow = enforcingEver.stream().filter(i -> i.at() >= since).toList();
    long upheld = inWindow.stream().filter(i -> i.verdict() == Verdict.UPHELD).count();
    long breached = inWindow.stream()
        .filter(i -> i.verdict() == Verdict.BREACHED || i.verdict() == Verdict.ERROR).count();
    long flagged = inWindow.stream().filter(Item::flagged).count();
    long enforcing = inWindow.size();

    if (waiver != null && waiver.inForce(now)) {
      return new Standing(Status.WAIVED, enforcing, upheld, breached, flagged, last, waiver.reason());
    }
    if (enforcingEver.isEmpty()) {
      var onlyNonEnforcing = !evidence.isEmpty();
      return new Standing(Status.NO_EVIDENCE, 0, 0, 0, 0, 0,
          onlyNonEnforcing ? "only shadow, advisory or test evidence, which proves nothing" : "no evidence yet");
    }
    if (breached > 0) {
      return new Standing(Status.FAILING, enforcing, upheld, breached, flagged, last,
          breached + " breach or error in the last " + window.toDays() + " days");
    }
    if (inWindow.isEmpty()) {
      return new Standing(Status.STALE, 0, 0, 0, 0, last, "no enforcing evidence in the last " + window.toDays() + " days");
    }
    if (upheld == 0) {
      return new Standing(Status.UNRESOLVED, enforcing, 0, 0, flagged, last, "evidence neither upholds nor breaches the control");
    }
    return new Standing(Status.SATISFIED, enforcing, upheld, 0, flagged, last, "upheld");
  }
}
