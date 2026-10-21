package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.SignedContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The checks a release makes of itself when it first starts: the at-deployment gates and the conformance eval.
 *
 * <ul>
 *   <li>{@link Controls#CATALOG_VALID_AT_STARTUP}: the catalog the release carries parses and every row is complete.
 *   <li>{@link Controls#DRAFT_MATCHES_BUNDLE}: once a bundle is signed, every control the release runs is in the
 *       bundle in force exactly as written. With no bundle signed yet there is nothing to match and the gate does not
 *       apply.
 *   <li>{@link Controls#SIGNED_CONTRACT_CONFORMANCE}: which controls run as signed, changed or unsigned.
 * </ul>
 *
 * <p>A failing gate stops the release from starting ({@link #enforce()}), so a failing gate verdict is never recorded
 * by the release it held back: the failure is in the release's start-up log. Evidence belongs to a project, and a
 * release learns a project when it first serves it, so each verdict is recorded then, once per project and release
 * (keyed by the catalog's and the bundle's digests).
 */
public final class ReleaseGates {
  private ReleaseGates() {}

  private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();

  /** What the bundle gate found: whether it applies, and every control that is not as signed. */
  public record BundleCheck(boolean applies, List<String> problems) {
    public boolean passed() {
      return problems.isEmpty();
    }
  }

  /** Fails the start of a release whose catalog is invalid or whose controls are not the ones signed. */
  public static void enforce() {
    var catalog = ControlCatalog.current(); // a catalog that does not validate throws here
    var bundle = checkBundle(catalog, SignedContract.current());
    if (!bundle.passed()) {
      throw new IllegalStateException("gate " + Controls.DRAFT_MATCHES_BUNDLE
          + " failed: governance/controls.yaml is not the bundle in force; sign again in the console: "
          + String.join("; ", bundle.problems()));
    }
  }

  public static BundleCheck checkBundle(ControlCatalog catalog, SignedContract signed) {
    if (!signed.present()) return new BundleCheck(false, List.of());
    var problems = new ArrayList<String>(signed.problems());
    if (signed.intact()) {
      for (var control : catalog.controls()) {
        var standing = signed.standingOf(catalog, control.rowId());
        if (standing != SignedContract.Standing.SIGNED) problems.add(control.rowId() + " is " + standing.name().toLowerCase());
      }
    }
    return new BundleCheck(true, List.copyOf(problems));
  }

  /** Records the release's gate and conformance verdicts for a project, the first time the release serves it. */
  public static void recordFor(ComponentClient componentClient, String project) {
    var catalog = ControlCatalog.current();
    var signed = SignedContract.current();
    var release = catalog.digest() + "~" + (signed.present() ? signed.digest() : "unsigned");
    if (!RECORDED.add(project + "|" + release)) return;
    var verdicts = new Verdicts(componentClient);
    var part = "release~" + release;
    verdicts.gate(project, Controls.CATALOG_VALID_AT_STARTUP, part, true,
        catalog.controls().size() + " controls, every one complete for its mechanism", "catalog " + catalog.digest());
    var bundle = checkBundle(catalog, signed);
    if (bundle.applies()) {
      verdicts.gate(project, Controls.DRAFT_MATCHES_BUNDLE, part, bundle.passed(),
          bundle.passed() ? "every control is signed as written" : String.join("; ", bundle.problems()),
          "bundle " + signed.digest());
    } else {
      verdicts.notApplicable(project, Controls.DRAFT_MATCHES_BUNDLE, part, "no bundle has been signed yet",
          "catalog " + catalog.digest());
    }
    var unsigned = new ArrayList<String>();
    for (var control : catalog.controls()) {
      var standing = signed.standingOf(catalog, control.rowId());
      if (standing != SignedContract.Standing.SIGNED) unsigned.add(control.rowId() + " " + standing.name().toLowerCase());
    }
    verdicts.eval(project, Controls.SIGNED_CONTRACT_CONFORMANCE, part, unsigned.isEmpty(),
        unsigned.isEmpty() ? "every control runs as signed"
            : unsigned.size() + " of " + catalog.controls().size() + " not as signed: " + String.join(", ", unsigned),
        signed.present() ? "bundle " + signed.digest() : "no bundle");
  }
}
