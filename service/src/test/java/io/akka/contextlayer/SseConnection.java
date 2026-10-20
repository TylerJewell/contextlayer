package io.akka.contextlayer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A server-sent event stream held open by a test, whose events can be waited for. */
public final class SseConnection implements AutoCloseable {

  private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
  private final CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response;
  private volatile Thread reader;

  private SseConnection(HttpRequest request) {
    this.response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().sendAsync(request, HttpResponse.BodyHandlers.ofLines());
    response.thenAcceptAsync(r -> {
      reader = Thread.currentThread();
      try {
        r.body().forEach(line -> {
          if (line.startsWith("data:")) events.add(line.substring("data:".length()).strip());
        });
      } catch (RuntimeException closed) {
        // The test ended the stream.
      }
    });
  }

  /** Opens the stream and returns once the service has answered, so events published after this are seen. */
  public static SseConnection open(int port, String method, String path, String authorization, String jsonBody)
      throws IOException {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", authorization).header("Accept", "text/event-stream");
    if (jsonBody == null) {
      builder.GET();
    } else {
      builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
    }
    var connection = new SseConnection(builder.build());
    try {
      var answered = connection.response.get(10, TimeUnit.SECONDS);
      if (answered.statusCode() != 200) throw new IOException("the stream answered " + answered.statusCode());
    } catch (Exception e) {
      throw new IOException("the stream did not open: " + e, e);
    }
    return connection;
  }

  /** The next event that satisfies the test, or null if none arrives in time. */
  public String next(Predicate<String> wanted, Duration within) throws InterruptedException {
    long deadline = System.nanoTime() + within.toNanos();
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) return null;
      var event = events.poll(left, TimeUnit.NANOSECONDS);
      if (event == null) return null;
      if (wanted.test(event)) return event;
    }
  }

  @Override
  public void close() {
    response.cancel(true);
    var thread = reader;
    if (thread != null) thread.interrupt();
  }
}
