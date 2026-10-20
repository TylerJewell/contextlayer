package io.akka.contextlayer.governance;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What people have said about one control of one project, kept apart because they are said by
 * different people about different things.
 *
 * <p>Acceptance is the binding: someone with the authority to take the obligation on says this
 * system accepts this control, at this version of the catalog. An assertion is a separate claim by
 * whoever answers for the evidence: which kind of record counts as evidence for the control, and
 * which other documents it is expected to be backed by. Neither says the control is working; only
 * enforcing records do that.
 */
public record ControlClaims(
    Acceptance acceptance, Assertion assertion, List<Assertion> earlier) {

  /** @param catalogDigest the version of the catalog that was accepted */
  public record Acceptance(String by, String authority, String rationale, String catalogDigest, long at) {}

  /** A document the evidence is expected to be backed by, and how old it may be. */
  public record Expectation(String name, int maxAgeDays) {}

  /**
   * @param revision 1 for the first assertion; each new one replaces the last and keeps it in {@code earlier}
   * @param evidenceKind the proof event of the records that count as evidence
   * @param basisDigest the catalog version of the acceptance this assertion rests on, or empty if there was none
   */
  public record Assertion(
      int revision, String evidenceKind, List<Expectation> expects, String by, String authority, String rationale,
      String basisDigest, long at) {}

  public enum Coverage { NONE, PARTIAL, FULL }

  /** @param unmet what is still missing, in words, when coverage is not full */
  public record CoverageResult(Coverage coverage, List<String> unmet) {}

  public ControlClaims {
    earlier = earlier == null ? List.of() : List.copyOf(earlier);
  }

  public static ControlClaims empty() {
    return new ControlClaims(null, null, List.of());
  }

  public boolean accepted() {
    return acceptance != null;
  }

  public boolean asserted() {
    return assertion != null;
  }

  /** Whether the acceptance is of the catalog as it stands, not of an earlier version of it. */
  public boolean acceptanceCurrent(String catalogDigest) {
    return acceptance != null && acceptance.catalogDigest().equals(catalogDigest);
  }

  /**
   * How fully the project's documents back the assertion. With no assertion nothing is claimed, so coverage is none. An
   * assertion that expects nothing more than the records is fully covered.
   *
   * @param asOfOf the date the named document was last current, as yyyy-mm-dd, or empty if the project holds none
   */
  public CoverageResult coverage(long now, Function<String, Optional<String>> asOfOf) {
    if (assertion == null) return new CoverageResult(Coverage.NONE, List.of("no assertion has been made about what counts as evidence"));
    var unmet = new ArrayList<String>();
    for (var expected : assertion.expects()) {
      var newest = asOfOf.apply(expected.name());
      if (newest.isEmpty()) {
        unmet.add(expected.name() + ": none attached");
        continue;
      }
      var asOf = LocalDate.parse(newest.get());
      var age = Duration.between(asOf.atStartOfDay().toInstant(ZoneOffset.UTC), java.time.Instant.ofEpochMilli(now));
      if (age.toDays() > expected.maxAgeDays()) {
        unmet.add(expected.name() + ": last current " + newest.get() + ", more than " + expected.maxAgeDays() + " days ago");
      }
    }
    return new CoverageResult(unmet.isEmpty() ? Coverage.FULL : Coverage.PARTIAL, List.copyOf(unmet));
  }

  // ---- changes, as the entity applies them

  public ControlClaims withAcceptance(Acceptance next) {
    return new ControlClaims(next, assertion, earlier);
  }

  public ControlClaims withoutAcceptance() {
    return new ControlClaims(null, assertion, earlier);
  }

  public ControlClaims withAssertion(Assertion next) {
    var history = new ArrayList<>(earlier);
    if (assertion != null) history.add(assertion);
    return new ControlClaims(acceptance, next, history);
  }
}
