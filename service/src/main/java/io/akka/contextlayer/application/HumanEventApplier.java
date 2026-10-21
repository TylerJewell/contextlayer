package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.HumanEvent;
import io.akka.contextlayer.governance.HumanEventEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries a decided human event into the thing that waited on it. The decision is the only thing
 * that can release an action, so there is no other path from a person's answer to the workflow, and
 * a failed delivery is retried by the runtime until it lands. The workflow takes a repeated
 * delivery without complaint.
 */
@Component(id = "human-event-applier")
@Consume.FromEventSourcedEntity(HumanEventEntity.class)
public class HumanEventApplier extends Consumer {

  private static final Logger log = LoggerFactory.getLogger(HumanEventApplier.class);

  private final ComponentClient componentClient;

  public HumanEventApplier(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(HumanEventEvent event) {
    var id = messageContext().eventSubject().orElseThrow();
    try {
      switch (event) {
        case HumanEventEvent.Decided e when e.state() == io.akka.contextlayer.governance.HumanState.DECIDED ->
            apply(current(id), e.outcomeAction(), e.decision().reviewer().subject(), e.decision().rationale());
        case HumanEventEvent.Withdrawn e -> {
          // Only an action approval has a requester to withdraw; a closed identity question leaves the cluster as it is.
          var held = current(id);
          if ("action".equals(held.subjectKind()) && !UnknownOutcomes.isOne(held)) {
            apply(held, "DENY", e.reviewer().subject(), "withdrawn by the requester");
          }
        }
        default -> { }
      }
      return effects().done();
    } catch (RuntimeException e) {
      log.warn("Could not apply {} for human event {}: {}", event.getClass().getSimpleName(), id, e.toString());
      throw e;
    }
  }

  private HumanEvent current(String id) {
    return componentClient.forEventSourcedEntity(id).method(HumanEventEntity::get).invoke();
  }

  private void apply(HumanEvent event, String action, String by, String why) {
    if (UnknownOutcomes.isOne(event)) {
      applyUnknownOutcome(event, action, by, why);
    } else if ("action".equals(event.subjectKind())) {
      var workflow = componentClient.forWorkflow(
          ScopedId.of(event.orgId(), event.assistantId(), event.detail().get("actionKey")));
      if ("APPROVE".equals(action)) {
        workflow.method(ActionWorkflow::approve).invoke(by);
      } else {
        workflow.method(ActionWorkflow::reject).invoke(new ActionWorkflow.Rejection(by, why));
      }
    } else if (IdentityReviews.KIND.equals(event.subjectKind())) {
      applyIdentity(event, action, by);
    } else if ("system".equals(event.subjectKind()) && "assistant".equals(event.detail().get("scope"))) {
      applyHalt(event, action, by);
    } else if ("failed-step".equals(event.detail().get("operation"))) {
      // A hand-off holds nothing back, so its decision changes nothing; it says the operator has taken it on.
      settle(event, "APPROVE".equals(action) ? "acknowledged by " + by : "escalated by " + by, null);
    }
  }

  /**
   * Confirms or rejects the cluster, but only if it still holds the records the person was shown.
   * A decision about a different set of records is recorded as not applied rather than applied.
   */
  private void applyIdentity(HumanEvent event, String action, String by) {
    var cluster = componentClient.forEventSourcedEntity(event.detail().get("_clusterId"))
        .method(IdentityEntity::get).invoke();
    String note;
    if (!cluster.fingerprint().equals(event.detail().get("fingerprint"))) {
      note = "the records changed after this was decided; it was not applied";
      settle(event, null, note);
      return;
    }
    try {
      var review = new IdentityEntity.Review(by);
      var entity = componentClient.forEventSourcedEntity(event.detail().get("_clusterId"));
      if ("APPROVE".equals(action)) entity.method(IdentityEntity::confirm).invoke(review);
      else entity.method(IdentityEntity::reject).invoke(review);
    } catch (IllegalArgumentException e) {
      settle(event, null, e.getMessage());
      return;
    }
    settle(event, "link " + ("APPROVE".equals(action) ? "confirmed" : "rejected"), null);
  }

  /** Records in the ledger what the reviewer found: APPROVE is that the action took effect, DENY that it did not. */
  private void applyUnknownOutcome(HumanEvent event, String action, String by, String why) {
    boolean happened = "APPROVE".equals(action);
    try {
      componentClient.forEventSourcedEntity(ScopedId.of(event.orgId(), event.assistantId(), event.detail().get("actionKey")))
          .method(ActionLedgerEntity::resolveUnknown)
          .invoke(new ActionLedgerEntity.ResolveUnknown(by, happened, why));
    } catch (IllegalArgumentException e) {
      settle(event, null, e.getMessage());
      return;
    }
    settle(event, happened ? "recorded as having taken effect" : "recorded as not having taken effect", null);
  }

  /** A halt or restart takes effect only when it is approved; a denial leaves the assistant as it was. */
  private void applyHalt(HumanEvent event, String action, String by) {
    boolean halt = "halt".equals(event.detail().get("operation"));
    if (!"APPROVE".equals(action)) {
      settle(event, "denied; the playbook is unchanged", null);
      return;
    }
    componentClient.forKeyValueEntity(HaltEntity.id(event.orgId(), event.assistantId())).method(HaltEntity::set)
        .invoke(new HaltEntity.Set(halt, by, event.reason(), event.id(), System.currentTimeMillis()));
    settle(event, halt ? "the playbook is halted" : "the playbook is restarted", null);
  }

  private void settle(HumanEvent event, String applied, String failure) {
    var entity = componentClient.forEventSourcedEntity(event.id());
    long now = System.currentTimeMillis();
    if (applied != null) entity.method(HumanEventEntity::markApplied).invoke(new HumanEventEntity.Applied(applied, now));
    else entity.method(HumanEventEntity::markApplyFailed).invoke(new HumanEventEntity.Applied(failure, now));
  }
}
