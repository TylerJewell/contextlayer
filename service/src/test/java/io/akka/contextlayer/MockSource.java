package io.akka.contextlayer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend source for tests: an OpenAPI document and one list operation that answers with a
 * body the test can change, or fails on demand. Requires the bearer token "tok".
 */
public final class MockSource implements AutoCloseable {

  private final HttpServer server;
  private volatile String body;
  private volatile boolean down;
  private final AtomicInteger hits = new AtomicInteger();

  public MockSource(String path, String operationId, String body) throws IOException {
    this.body = body;
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    var origin = "http://localhost:" + server.getAddress().getPort();
    server.createContext("/openapi.json", ex -> respond(ex, 200, """
        {"openapi":"3.0.0","servers":[{"url":"%s"}],"paths":{"%s":{"get":{"operationId":"%s"}}}}"""
        .formatted(origin, path, operationId)));
    server.createContext(path, ex -> {
      hits.incrementAndGet();
      if (down) respond(ex, 500, "{}");
      else if (!"Bearer tok".equals(ex.getRequestHeaders().getFirst("Authorization"))) respond(ex, 401, "{}");
      else respond(ex, 200, this.body);
    });
    server.start();
  }

  public String specUrl() {
    return "http://localhost:" + server.getAddress().getPort() + "/openapi.json";
  }

  public void setBody(String newBody) {
    this.body = newBody;
  }

  public void setDown(boolean isDown) {
    this.down = isDown;
  }

  public int hits() {
    return hits.get();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static void respond(HttpExchange ex, int status, String text) throws IOException {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
