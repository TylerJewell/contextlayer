package io.akka.contextlayer;

import akka.javasdk.http.HttpClient;
import akka.javasdk.http.RequestBuilder;
import akka.util.ByteString;

/**
 * Sends test requests to the service's routes. A test names a route by the project it is about,
 * {@code /orgs/{project}/...}, or by {@code /oversight/...}; the service has neither prefix, because
 * a deployment serves one project, so this sends {@code /v1/context/...}. Which project a request
 * is for travels in its token (see {@link TokenProject}).
 */
public final class Wire {

  private static final String ROOT = "/v1/context";

  private final HttpClient client;

  public Wire(HttpClient client) {
    this.client = client;
  }

  public static String route(String path) {
    if (path.startsWith("/oversight")) return ROOT + path;
    if (path.startsWith("/orgs/")) {
      int next = path.indexOf('/', "/orgs/".length());
      return next < 0 ? ROOT : ROOT + path.substring(next);
    }
    return path;
  }

  public RequestBuilder<ByteString> GET(String path) {
    return client.GET(route(path));
  }

  public RequestBuilder<ByteString> POST(String path) {
    return client.POST(route(path));
  }

  public RequestBuilder<ByteString> PUT(String path) {
    return client.PUT(route(path));
  }

  public RequestBuilder<ByteString> DELETE(String path) {
    return client.DELETE(route(path));
  }
}
