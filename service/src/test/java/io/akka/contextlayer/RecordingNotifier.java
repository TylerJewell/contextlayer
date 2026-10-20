package io.akka.contextlayer;

import io.akka.contextlayer.application.Notifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps what it is asked to send, and can be made to fail. */
public class RecordingNotifier implements Notifier {

  public record Sent(Route route, Message message, String idempotencyKey) {}

  public final List<Sent> sent = new CopyOnWriteArrayList<>();
  public volatile boolean failing;

  @Override
  public Result send(Route route, Message message, String idempotencyKey) {
    sent.add(new Sent(route, message, idempotencyKey));
    return failing ? Result.failed("the receiver answered HTTP 503") : Result.ok("delivered (HTTP 200)");
  }

  public void reset() {
    sent.clear();
    failing = false;
  }

  public List<Sent> to(String route) {
    return sent.stream().filter(s -> s.message().route().equals(route)).toList();
  }
}
