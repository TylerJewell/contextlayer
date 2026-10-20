package io.akka.contextlayer.application;

import java.util.Map;

/**
 * Tells a team that something needs them. The one place a message leaves the service for a person's
 * inbox or channel. An implementation never throws for a delivery that did not work: it says so in
 * the result, because the caller records that and moves on.
 */
public interface Notifier {

  /**
   * Where a route's messages go.
   *
   * @param format "json" posts the notification as a JSON object; "slack" posts {@code {"text": ...}}
   * @param credentialRef a reference resolved to a bearer token, or blank for none (Slack webhooks carry theirs in the URL)
   */
  record Route(String url, String format, String credentialRef) {}

  /**
   * @param type what happened, such as {@code case.opened} or {@code approval.requested}
   * @param reference ids that let the receiver find it in the console; never record contents
   */
  record Message(String type, String organization, String route, String title, String summary,
      Map<String, String> reference, String dueAt, String at) {}

  record Result(boolean delivered, String detail) {
    public static Result ok(String detail) {
      return new Result(true, detail);
    }

    public static Result failed(String detail) {
      return new Result(false, detail);
    }
  }

  /** {@code idempotencyKey} is the same for every attempt at one message, so a receiver can drop repeats. */
  Result send(Route route, Message message, String idempotencyKey);
}
