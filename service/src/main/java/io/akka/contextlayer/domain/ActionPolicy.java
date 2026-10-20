package io.akka.contextlayer.domain;

/** Decides what happens to a requested action, from the assistant's stage and the action's risk. */
public final class ActionPolicy {

  public record Decision(ActionStatus status, String reason) {}

  private ActionPolicy() {}

  /** A person halted the assistant; nothing it asks for is done until the halt is lifted. */
  public static Decision halted() {
    return new Decision(ActionStatus.DENIED, "playbook is halted");
  }

  /** The action type is not in the catalog, or its connector pack is not live. */
  public static Decision notAvailable() {
    return new Decision(ActionStatus.DENIED, "action type is not available: unknown, or its connector is not live");
  }

  public static Decision decide(AssistantStage stage, boolean hasRunnableRelease, RiskClass risk) {
    if (risk == RiskClass.NEVER_BY_ASSISTANT) {
      return new Decision(ActionStatus.DENIED, "this action is never available to assistants");
    }
    return switch (stage) {
      case DRAFT -> new Decision(ActionStatus.DENIED, "playbook is in draft");
      case SHADOW -> new Decision(ActionStatus.WOULD_DO, "shadow mode: recorded, not sent");
      case LIVE -> {
        if (!hasRunnableRelease) {
          yield new Decision(ActionStatus.DENIED, "no approved release is running");
        }
        yield risk == RiskClass.UNATTENDED
            ? new Decision(ActionStatus.APPROVED, "unattended action within permissions")
            : new Decision(ActionStatus.AWAITING_APPROVAL, "a person must approve this action");
      }
    };
  }
}
