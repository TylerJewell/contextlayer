package io.akka.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.contextlayer.domain.ScopedId;

/**
 * Where the messages for one route go. A route is a name a case is sent to ("Claims Team B") or
 * {@code role:approver} for the people an approval waits on. Id: {@code ScopedId.of(orgId, "route",
 * safe(name))}.
 */
@Component(id = "notification-route")
public class NotificationRouteEntity extends KeyValueEntity<NotificationRouteEntity.State> {

  public record State(boolean exists, boolean enabled, String url, String format, String credentialRef, String by, long at) {
    public Notifier.Route route() {
      return new Notifier.Route(url, format, credentialRef);
    }
  }

  public record Set(String url, String format, String credentialRef, String by, long now) {}

  public static String id(String orgId, String routeName) {
    return ScopedId.of(orgId, "route", ScopedId.safe(routeName));
  }

  @Override
  public State emptyState() {
    return new State(false, false, "", "json", "", "", 0);
  }

  public Effect<State> set(Set command) {
    var state = new State(true, true, command.url(), command.format(),
        command.credentialRef() == null ? "" : command.credentialRef(), command.by(), command.now());
    return effects().updateState(state).thenReply(state);
  }

  public Effect<State> disable(Set command) {
    if (!currentState().exists()) return effects().error("there is no such route");
    var s = currentState();
    var state = new State(true, false, s.url(), s.format(), s.credentialRef(), command.by(), command.now());
    return effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}
