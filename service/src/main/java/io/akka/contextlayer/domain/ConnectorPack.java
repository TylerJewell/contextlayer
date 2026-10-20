package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All imported versions of one connector pack in one organization, and which version is active
 * and in what mode (REQUIREMENTS CON-04, CON-05).
 *
 * <p>Importing never changes what the platform does. A version takes effect only when someone
 * other than its importer activates it, so no pack goes live on its author's say-so alone. A
 * pack is activated read-only first; only LIVE exposes its actions to assistants.
 */
public record ConnectorPack(
    String orgId,
    String name,
    List<Version> versions,
    String activeHash,
    Mode mode,
    boolean exists) {

  public record Version(String hash, PackDefinition definition, String importedBy) {}

  public enum Mode { INACTIVE, READ_ONLY, LIVE }

  public static ConnectorPack empty() {
    return new ConnectorPack("", "", List.of(), "", Mode.INACTIVE, false);
  }

  public Optional<Version> find(String hash) {
    return versions.stream().filter(v -> v.hash().equals(hash)).findFirst();
  }

  public Optional<Version> active() {
    return mode == Mode.INACTIVE ? Optional.empty() : find(activeHash);
  }

  /** The risk class of an action this pack offers to assistants, only while the pack is LIVE. */
  public Optional<RiskClass> actionRisk(String actionName) {
    if (mode != Mode.LIVE) return Optional.empty();
    return active().flatMap(v -> v.definition().actions() == null ? Optional.empty()
        : v.definition().actions().stream()
            .filter(a -> a.name().equals(actionName))
            .map(PackDefinition.ActionDef::risk)
            .findFirst());
  }

  public List<String> validateImport(String packName, PackDefinition definition, String by) {
    var errors = new ArrayList<>(PackValidator.validate(definition));
    if (definition != null && definition.name() != null && !definition.name().equals(packName)) {
      errors.add("the pack's name must match the name in the path (" + packName + ")");
    }
    if (by == null || by.isBlank()) errors.add("who is importing is required");
    return List.copyOf(errors);
  }

  public Optional<String> validateActivate(String hash, Mode target, String by) {
    if (!exists) return Optional.of("connector pack not found");
    if (target == null || target == Mode.INACTIVE) return Optional.of("choose READ_ONLY or LIVE; use deactivate to switch off");
    if (by == null || by.isBlank()) return Optional.of("who is activating is required");
    var version = find(hash);
    if (version.isEmpty()) return Optional.of("version " + hash + " not found");
    if (version.get().importedBy().equals(by)) {
      return Optional.of("a version must be activated by someone other than its importer");
    }
    return Optional.empty();
  }

  public Optional<String> validateDeactivate(String by) {
    if (!exists) return Optional.of("connector pack not found");
    if (by == null || by.isBlank()) return Optional.of("who is deactivating is required");
    return Optional.empty();
  }

  public ConnectorPack apply(ConnectorPackEvent event) {
    return switch (event) {
      case ConnectorPackEvent.Imported e -> {
        var next = new ArrayList<>(versions);
        if (find(e.hash()).isEmpty()) next.add(new Version(e.hash(), e.definition(), e.importedBy()));
        yield new ConnectorPack(e.orgId(), e.name(), List.copyOf(next), activeHash, mode, true);
      }
      case ConnectorPackEvent.Activated e ->
          new ConnectorPack(orgId, name, versions, e.hash(), e.mode(), true);
      case ConnectorPackEvent.Deactivated e ->
          new ConnectorPack(orgId, name, versions, activeHash, Mode.INACTIVE, true);
    };
  }
}
