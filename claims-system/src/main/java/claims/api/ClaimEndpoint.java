package claims.api;

import akka.Done;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import claims.application.ClaimEntity;
import claims.domain.Claim;
import java.util.List;

/** The claims system's own interface, used to open, change and remove claims. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/claims")
public class ClaimEndpoint {

  public record ClaimBody(String status, int daysOpen, String region, String clientEmail, long amount) {}

  private final ComponentClient componentClient;

  public ClaimEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Put("/{claimId}")
  public Claim write(String claimId, ClaimBody body) {
    return componentClient.forKeyValueEntity(claimId).method(ClaimEntity::write)
        .invoke(new ClaimEntity.Write(body.status(), body.daysOpen(), body.region(), body.clientEmail(), body.amount()));
  }

  @Get("/{claimId}")
  public Claim get(String claimId) {
    return componentClient.forKeyValueEntity(claimId).method(ClaimEntity::get).invoke();
  }

  @Delete("/{claimId}")
  public Done remove(String claimId) {
    return componentClient.forKeyValueEntity(claimId).method(ClaimEntity::remove).invoke();
  }

  /** Opens a spread of claims, for a demonstration. */
  @Post("/seed")
  public List<Claim> seed() {
    var seeded = new java.util.ArrayList<Claim>();
    var statuses = List.of("OPEN", "OPEN", "PENDING", "CLOSED");
    var regions = List.of("north", "south", "east", "west");
    for (int i = 1; i <= 12; i++) {
      seeded.add(write("CL-" + (100 + i), new ClaimBody(statuses.get(i % 4), (i * 9) % 60, regions.get(i % 4),
          "client" + i + "@example.com", 1_000L + i * 1_750L)));
    }
    return seeded;
  }
}
