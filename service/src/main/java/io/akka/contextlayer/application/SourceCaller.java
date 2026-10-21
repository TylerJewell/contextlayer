package io.akka.contextlayer.application;

import io.akka.contextlayer.domain.PackDefinition;
import java.util.List;
import java.util.Map;

/**
 * Reads the records of one entity type from a source system, within the limits its connector
 * pack declares. A call never silently returns nothing: the result states whether it is
 * complete, partial or failed (REQUIREMENTS QRY-03).
 */
public interface SourceCaller {

  enum Status { OK, PARTIAL, FAILED }

  /**
   * @param records what was read; present for OK and PARTIAL
   * @param warnings why the result is partial, or why it failed
   */
  record Result(Status status, List<Map<String, Object>> records, List<String> warnings) {
    public static Result failed(String reason) {
      return new Result(Status.FAILED, List.of(), List.of(reason));
    }

    /** A call refused before it was sent because its host is not on the egress allow list. */
    public static Result refusedHost(String what) {
      return failed(what + " is not on the " + EGRESS_REFUSAL);
    }

    public boolean refusedByEgress() {
      return status == Status.FAILED && warnings.stream().anyMatch(w -> w.endsWith(EGRESS_REFUSAL));
    }

    /** The guardrail that refused the call before it was sent, or empty when it was sent. */
    public java.util.Optional<Guard> refusedBy() {
      if (status != Status.FAILED || warnings.isEmpty()) return java.util.Optional.empty();
      var reason = warnings.get(0);
      if (refusedByEgress()) return java.util.Optional.of(Guard.EGRESS);
      if (reason.equals(CREDENTIAL_REFUSAL)) return java.util.Optional.of(Guard.CREDENTIAL);
      if (BOUNDS_REFUSALS.stream().anyMatch(reason::startsWith)) return java.util.Optional.of(Guard.BOUNDS);
      return java.util.Optional.empty();
    }
  }

  /** The checks every source call passes before it is sent, each its own control. */
  enum Guard {
    /** The hosts are on the egress allow list. */
    EGRESS,
    /** The credential is a reference that resolves. */
    CREDENTIAL,
    /** The call is within what the connector declares: its filters, a GET, no path parameters, a supported source. */
    BOUNDS
  }

  String CREDENTIAL_REFUSAL = "the credential for this pack is not available";

  /** How each refusal outside the connector's declared bounds begins. */
  List<String> BOUNDS_REFUSALS = List.of("filters not declared by the pack", "source kind ",
      "only read (GET) operations", "operations with path parameters");

  /** What every egress refusal ends with, so a refusal can be told from any other failure. */
  String EGRESS_REFUSAL = "allowed list of hosts";

  Result fetchEntities(
      String orgId, PackDefinition pack, PackDefinition.EntityMapping entity, Map<String, String> filters);
}
