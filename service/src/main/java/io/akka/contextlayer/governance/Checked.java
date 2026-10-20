package io.akka.contextlayer.governance;

/** A value, or the reason there is none. */
public record Checked<T>(T value, Refusal refusal) {

  public static <T> Checked<T> ok(T value) {
    return new Checked<>(value, null);
  }

  public static <T> Checked<T> refuse(Refusal refusal) {
    return new Checked<>(null, refusal);
  }

  public boolean refused() {
    return refusal != null;
  }
}
