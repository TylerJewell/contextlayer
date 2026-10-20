package claims.application;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.DeleteHandler;
import akka.javasdk.annotations.Produce;
import akka.javasdk.consumer.Consumer;
import claims.api.EntityChange;
import claims.domain.Claim;
import java.util.LinkedHashMap;

/**
 * Publishes every change to a claim as a stream other services in the project can read. A service
 * that subscribes starts from the current state of every claim and then follows each change, so it
 * needs no separate load.
 */
@Component(id = "claim-changes")
@Consume.FromKeyValueEntity(ClaimEntity.class)
@Produce.ServiceStream(id = "entity_changes")
@Acl(allow = @Acl.Matcher(service = "*"))
public class ClaimChanges extends Consumer {

  public Effect onChange(Claim claim) {
    var attributes = new LinkedHashMap<String, Object>();
    attributes.put("claimId", claim.claimId());
    attributes.put("status", claim.status());
    attributes.put("daysOpen", claim.daysOpen());
    attributes.put("region", claim.region());
    attributes.put("email", claim.clientEmail());
    attributes.put("amount", claim.amount());
    return effects().produce(new EntityChange.Upserted("Claim", claim.claimId(), claim.version(), attributes));
  }

  @DeleteHandler
  public Effect onDelete() {
    var id = messageContext().eventSubject().orElseThrow();
    return effects().produce(new EntityChange.Deleted("Claim", id, System.currentTimeMillis()));
  }
}
