package io.akka.contextlayer.application;

/**
 * The outbound gateway's executing half: the one place an action leaves the platform. An
 * implementation may throw, which means the outcome is unknown. Returning
 * {@link Result#failed} means the target system definitively did not apply the action.
 */
public interface ActionExecutor {

  record Result(boolean succeeded, String detail) {
    public static Result ok(String detail) {
      return new Result(true, detail);
    }

    public static Result failed(String detail) {
      return new Result(false, detail);
    }
  }

  Result perform(String orgId, String actionType, String payload);

  /** An action a connector pack offers: the pack, and the action's entry in it. */
  record PackCall(io.akka.contextlayer.domain.PackDefinition pack, io.akka.contextlayer.domain.PackDefinition.ActionDef action) {
    public String actionType() {
      return pack.name() + "." + action.name();
    }
  }

  /**
   * Sends an action to the source its pack names. {@code idempotencyKey} is the same on every
   * attempt at this action, so a source that honours it applies the action once. An executor with no
   * way to reach a pack's system treats it as any other action.
   */
  default Result performPack(String orgId, PackCall call, String payload, String idempotencyKey) {
    return perform(orgId, call.actionType(), payload);
  }
}
