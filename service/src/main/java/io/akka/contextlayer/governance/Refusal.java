package io.akka.contextlayer.governance;

/** Why a request on a human event was not accepted; each kind has its own HTTP status. */
public record Refusal(Kind kind, String message) {

  public enum Kind {
    NOT_FOUND(404),
    FORBIDDEN(403),
    CONFLICT(409),
    INVALID(422);

    private final int status;

    Kind(int status) {
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  public static Refusal forbidden(String message) {
    return new Refusal(Kind.FORBIDDEN, message);
  }

  public static Refusal conflict(String message) {
    return new Refusal(Kind.CONFLICT, message);
  }

  public static Refusal invalid(String message) {
    return new Refusal(Kind.INVALID, message);
  }
}
