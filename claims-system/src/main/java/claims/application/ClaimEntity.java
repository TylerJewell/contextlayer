package claims.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import claims.domain.Claim;

/** One claim. Writing it, or deleting it, is what the claims system publishes. */
@Component(id = "claim")
public class ClaimEntity extends KeyValueEntity<Claim> {

  public record Write(String status, int daysOpen, String region, String clientEmail, long amount) {}

  public Effect<Claim> write(Write command) {
    long now = System.currentTimeMillis();
    var current = currentState();
    var id = commandContext().entityId();
    var next = current == null
        ? new Claim(id, command.status(), command.daysOpen(), command.region(), command.clientEmail(), command.amount(), now)
        : current.next(command.status(), command.daysOpen(), command.region(), command.clientEmail(), command.amount(), now);
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Claim> get() {
    if (currentState() == null) return effects().error("claim not found");
    return effects().reply(currentState());
  }

  public Effect<Done> remove() {
    return effects().deleteEntity().thenReply(Done.getInstance());
  }
}
