package claims.domain;

/**
 * A claim as the claims system holds it.
 *
 * @param version when this state was written, in epoch millis, and always later than the state
 *     before it. The context service uses it to tell a newer state of a claim from an older one.
 */
public record Claim(
    String claimId, String status, int daysOpen, String region, String clientEmail, long amount, long version) {

  public Claim next(String status, int daysOpen, String region, String clientEmail, long amount, long now) {
    return new Claim(claimId, status, daysOpen, region, clientEmail, amount, Math.max(version + 1, now));
  }
}
