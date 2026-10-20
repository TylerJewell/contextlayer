package io.akka.contextlayer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A pretend system an action is sent to: an OpenAPI document with one write operation
 * (POST /claims/{claimId}/notes) and one read, and an answer the test chooses. It records every
 * request it receives. Requires the bearer token "tok".
 */
public final class MockTarget implements AutoCloseable {

  /** How the system answers the write. */
  public enum Answer { OK, REFUSE, ERROR, HANG, DROP }

  public record Received(String method, String path, String authorization, String idempotencyKey, String body) {}

  private final HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile Answer answer = Answer.OK;

  public MockTarget() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    var origin = "http://localhost:" + server.getAddress().getPort();
    server.createContext("/openapi.json", ex -> respond(ex, 200, """
        {"openapi":"3.0.0","servers":[{"url":"%s"}],"paths":{
          "/claims/{claimId}/notes":{"post":{"operationId":"addNote"}},
          "/claims":{"get":{"operationId":"listClaims"}}}}""".formatted(origin)));
    server.createContext("/claims", this::handle);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // a hanging answer must not block the next request
    server.start();
  }

  public String specUrl() {
    return "http://localhost:" + server.getAddress().getPort() + "/openapi.json";
  }

  public void answer(Answer next) {
    this.answer = next;
  }

  public List<Received> received() {
    return received;
  }

  private void handle(HttpExchange ex) throws IOException {
    var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(),
        ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Idempotency-Key"), body));
    switch (answer) {
      case OK -> respond(ex, 200, "{\"ok\":true}");
      case REFUSE -> respond(ex, 422, "{\"error\":\"no such claim\"}");
      case ERROR -> respond(ex, 500, "{}");
      case HANG -> {
        try {
          Thread.sleep(4000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        respond(ex, 200, "{}");
      }
      case DROP -> { } // no answer at all: the connection is cut below
      default -> { }
    }
    if (answer == Answer.DROP) ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static void respond(HttpExchange ex, int status, String text) throws IOException {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    try {
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
    } catch (IOException e) {
      // the caller gave up waiting; nothing to tell it
    } finally {
      ex.close();
    }
  }
}
