package io.akka.contextlayer.application;

/** Sends a message by the way its route's format asks for: email through the mail server, anything else as a web request. */
public class RoutingNotifier implements Notifier {

  private final Notifier web;
  private final Notifier email;

  public RoutingNotifier(Notifier web, Notifier email) {
    this.web = web;
    this.email = email;
  }

  @Override
  public Result send(Route route, Message message, String idempotencyKey) {
    return ("email".equals(route.format()) ? email : web).send(route, message, idempotencyKey);
  }
}
