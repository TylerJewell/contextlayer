package io.akka.contextlayer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * An assistant: a purpose, an accountable owner, and immutable releases (REQUIREMENTS
 * AST-01..03). A release is identified by a hash of its definition, so changing the
 * definition always produces a different release that must be approved on its own.
 */
public record Assistant(
    String orgId,
    String assistantId,
    String purpose,
    String owner,
    AssistantStage stage,
    List<Release> releases,
    boolean exists) {

  public record Release(String releaseId, String definition, boolean approved, String approvedBy) {}

  public static Assistant empty() {
    return new Assistant("", "", "", "", AssistantStage.DRAFT, List.of(), false);
  }

  public static String releaseIdFor(String definition) {
    try {
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(definition.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- command validation: each returns an error message, or empty when the command is allowed

  public Optional<String> validateCreate(String purpose, String owner) {
    if (exists) return Optional.of("playbook already exists");
    if (purpose == null || purpose.isBlank()) return Optional.of("purpose is required");
    if (owner == null || owner.isBlank()) return Optional.of("an accountable owner is required");
    return Optional.empty();
  }

  public Optional<String> validateAddRelease(String definition) {
    if (!exists) return Optional.of("playbook not found");
    if (definition == null || definition.isBlank()) return Optional.of("definition is required");
    return Optional.empty();
  }

  public Optional<String> validateApprove(String releaseId, String approver) {
    if (!exists) return Optional.of("playbook not found");
    if (find(releaseId).isEmpty()) return Optional.of("release " + releaseId + " not found");
    if (!owner.equals(approver)) {
      return Optional.of("only the accountable owner can approve a release");
    }
    return Optional.empty();
  }

  public Optional<String> validateStage(AssistantStage target, String actor) {
    if (!exists) return Optional.of("playbook not found");
    if (!owner.equals(actor)) {
      return Optional.of("only the accountable owner can change the stage");
    }
    if (target == AssistantStage.SHADOW && releases.isEmpty()) {
      return Optional.of("shadow mode needs at least one release");
    }
    if (target == AssistantStage.LIVE && latestApproved().isEmpty()) {
      return Optional.of("live mode needs an approved release");
    }
    return Optional.empty();
  }

  // ---- queries

  public Optional<Release> find(String releaseId) {
    return releases.stream().filter(r -> r.releaseId().equals(releaseId)).findFirst();
  }

  public Optional<Release> latestApproved() {
    for (int i = releases.size() - 1; i >= 0; i--) {
      if (releases.get(i).approved()) return Optional.of(releases.get(i));
    }
    return Optional.empty();
  }

  /**
   * The release that runs now. Live runs only an approved release, so a newer release that is
   * not yet approved takes no effect. Shadow may try the newest release, whatever its state.
   */
  public Optional<Release> runnableRelease() {
    return switch (stage) {
      case DRAFT -> Optional.empty();
      case SHADOW -> releases.isEmpty() ? Optional.empty() : Optional.of(releases.get(releases.size() - 1));
      case LIVE -> latestApproved();
    };
  }

  // ---- event application

  public Assistant apply(AssistantEvent event) {
    return switch (event) {
      case AssistantEvent.Created e ->
          new Assistant(e.orgId(), e.assistantId(), e.purpose(), e.owner(), AssistantStage.DRAFT,
              List.of(), true);
      case AssistantEvent.ReleaseAdded e -> {
        if (find(e.releaseId()).isPresent()) yield this;
        var next = new ArrayList<>(releases);
        next.add(new Release(e.releaseId(), e.definition(), false, ""));
        yield new Assistant(orgId, assistantId, purpose, owner, stage, List.copyOf(next), true);
      }
      case AssistantEvent.ReleaseApproved e -> {
        var next = new ArrayList<Release>();
        for (var r : releases) {
          next.add(r.releaseId().equals(e.releaseId())
              ? new Release(r.releaseId(), r.definition(), true, e.approvedBy())
              : r);
        }
        yield new Assistant(orgId, assistantId, purpose, owner, stage, List.copyOf(next), true);
      }
      case AssistantEvent.StageChanged e ->
          new Assistant(orgId, assistantId, purpose, owner, e.stage(), releases, true);
    };
  }
}
