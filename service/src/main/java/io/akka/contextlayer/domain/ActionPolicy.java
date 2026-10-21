package io.akka.contextlayer.domain;

/** Decides what happens to a requested action, from the assistant's stage and the action's risk. */
public final class ActionPolicy {

  /**
   * The rule that decided, in the order the rules are checked: a halt, then whether the action is available, then
   * whether it is ever allowed, then the playbook's stage. {@code ALLOWED} means no rule stopped it.
   */
  public enum Rule {
    HALT,
    AVAILABILITY,
    NEVER,
    STAGE,
    ALLOWED
  }

  public record Decision(ActionStatus status, String reason, Rule rule) {}

  private ActionPolicy() {}

  /** A person halted the assistant; nothing it asks for is done until the halt is lifted. */
  public static Decision halted() {
    return new Decision(ActionStatus.DENIED, "playbook is halted", Rule.HALT);
  }

  /** The action type is not in the catalog, or its connector pack is not live. */
  public static Decision notAvailable() {
    return new Decision(ActionStatus.DENIED, "action type is not available: unknown, or its connector is not live",
        Rule.AVAILABILITY);
  }

  public static Decision decide(AssistantStage stage, boolean hasRunnableRelease, RiskClass risk) {
    if (risk == RiskClass.NEVER_BY_ASSISTANT) {
      return new Decision(ActionStatus.DENIED, "this action is never available to assistants", Rule.NEVER);
    }
    return switch (stage) {
      case DRAFT -> new Decision(ActionStatus.DENIED, "playbook is in draft", Rule.STAGE);
      case SHADOW -> new Decision(ActionStatus.WOULD_DO, "shadow mode: recorded, not sent", Rule.STAGE);
      case LIVE -> {
        if (!hasRunnableRelease) {
          yield new Decision(ActionStatus.DENIED, "no approved release is running", Rule.STAGE);
        }
        yield risk == RiskClass.UNATTENDED
            ? new Decision(ActionStatus.APPROVED, "unattended action within permissions", Rule.ALLOWED)
            : new Decision(ActionStatus.AWAITING_APPROVAL, "a person must approve this action", Rule.ALLOWED);
      }
    };
  }
}
