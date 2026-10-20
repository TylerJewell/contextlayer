package io.akka.contextlayer.governance;

import java.util.List;

/**
 * What a human control asks of the person: who may decide, how long they have, and what a decision
 * must contain. Read from the control's {@code human_config}.
 *
 * @param minimumReviewSeconds a decision submitted sooner is flagged below-minimum; when
 *     {@code enforceMinimum} is set it is refused instead
 * @param independence who may not decide: {@code proposer} means the person who raised the event
 */
public record HumanPolicy(
    String flavor,
    String subjectKind,
    String mode,
    boolean blocking,
    List<String> roles,
    String reviewMode,
    int required,
    List<String> independence,
    String accountableOwner,
    String slaTarget,
    String timeoutBehaviour,
    String timeoutDefault,
    List<String> escalationChain,
    List<String> allowedActions,
    boolean rationaleRequired,
    int minimumReviewSeconds,
    boolean enforceMinimum) {

  public HumanPolicy {
    roles = roles == null ? List.of() : List.copyOf(roles);
    independence = independence == null ? List.of() : List.copyOf(independence);
    escalationChain = escalationChain == null ? List.of() : List.copyOf(escalationChain);
    allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
  }

  public boolean twoPerson() {
    return "TWO_PERSON".equals(reviewMode);
  }
}
