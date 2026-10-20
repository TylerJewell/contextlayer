package io.akka.contextlayer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A monitor and what it has seen. {@code known} maps each row key currently matching to the case
 * opened for it.
 *
 * <p>A run that could not read every source is incomplete. It may open cases for rows it found,
 * but it never closes any: a row missing only because a source failed has not stopped matching.
 */
public record Monitor(
    String orgId,
    String monitorId,
    MonitorDefinition definition,
    int version,
    boolean enabled,
    Map<String, String> known,
    int runCount,
    long lastRunAt,
    String lastRunNote,
    RunPlan lastPlan,
    boolean exists) {

  /** What a run changed: which rows newly match (with their case ids) and which stopped. */
  public record RunPlan(int runNumber, int monitorVersion, Map<String, String> newCases, Map<String, String> goneCases) {}

  public static Monitor empty() {
    return new Monitor("", "", null, 0, false, Map.of(), 0, 0, "", new RunPlan(0, 0, Map.of(), Map.of()), false);
  }

  public Optional<String> validateCreate(MonitorDefinition definition, String by) {
    if (exists) return Optional.of("monitor already exists");
    return validateDefinition(definition, by);
  }

  public Optional<String> validateUpdate(MonitorDefinition definition, String by) {
    if (!exists) return Optional.of("monitor not found");
    return validateDefinition(definition, by);
  }

  public Optional<String> validateChange(String by) {
    if (!exists) return Optional.of("monitor not found");
    if (by == null || by.isBlank()) return Optional.of("who is changing the monitor is required");
    return Optional.empty();
  }

  private static Optional<String> validateDefinition(MonitorDefinition definition, String by) {
    if (by == null || by.isBlank()) return Optional.of("who is changing the monitor is required");
    if (definition == null) return Optional.of("a definition is required");
    var errors = definition.validate();
    return errors.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", errors));
  }

  /** Works out what a run with these matching row keys changes. */
  public RunPlan plan(List<String> matchingKeys, boolean complete) {
    int runNumber = runCount + 1;
    var newCases = new LinkedHashMap<String, String>();
    for (var key : matchingKeys) {
      if (!known.containsKey(key) && !newCases.containsKey(key)) {
        newCases.put(key, caseIdFor(key, runNumber));
      }
    }
    var gone = new LinkedHashMap<String, String>();
    if (complete) {
      for (var entry : known.entrySet()) {
        if (!matchingKeys.contains(entry.getKey())) gone.put(entry.getKey(), entry.getValue());
      }
    }
    return new RunPlan(runNumber, version, newCases, gone);
  }

  /** The case id for a row first seen in a given run; stable, and safe to use inside an entity id. */
  public String caseIdFor(String key, int runNumber) {
    return ScopedId.of(orgId, monitorId, shortHash(key), "r" + runNumber);
  }

  private static String shortHash(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Monitor apply(MonitorEvent event) {
    return switch (event) {
      case MonitorEvent.Created e ->
          new Monitor(e.orgId(), e.monitorId(), e.definition(), 1, true, Map.of(), 0, 0, "",
              new RunPlan(0, 1, Map.of(), Map.of()), true);
      case MonitorEvent.DefinitionUpdated e ->
          new Monitor(orgId, monitorId, e.definition(), version + 1, enabled, known, runCount, lastRunAt, lastRunNote,
              lastPlan, true);
      case MonitorEvent.EnabledChanged e ->
          new Monitor(orgId, monitorId, definition, version, e.enabled(), known, runCount, lastRunAt, lastRunNote,
              lastPlan, true);
      case MonitorEvent.RunRecorded e -> {
        var next = new HashMap<>(known);
        next.putAll(e.newCases());
        e.goneCases().keySet().forEach(next::remove);
        yield new Monitor(orgId, monitorId, definition, version, enabled, Map.copyOf(next), e.runNumber(), e.at(),
            e.note(), new RunPlan(e.runNumber(), version, e.newCases(), e.goneCases()), true);
      }
    };
  }
}
