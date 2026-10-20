package io.akka.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.NotificationPublisher;
import akka.javasdk.NotificationPublisher.NotificationStream;

/**
 * Pushes a notice to everyone watching a project whenever its data changes. There is one per
 * project, and it keeps nothing: a notice goes to the subscribers connected at that moment and is
 * not replayed. A watcher that reconnects reads the data again, so a missed notice costs one more
 * read and never a wrong answer.
 */
@Component(id = "change-feed")
public class ChangeFeedEntity extends KeyValueEntity<Done> {

  /**
   * @param op upsert, delete, or refreshed (a scheduled read of the whole type, which names no record)
   * @param entityId empty for refreshed
   */
  public record ChangeNotice(String pack, String entityType, String entityId, String op, long at) {}

  private final NotificationPublisher<ChangeNotice> publisher;

  public ChangeFeedEntity(NotificationPublisher<ChangeNotice> publisher) {
    this.publisher = publisher;
  }

  /** Publishes without writing state: the notice is for whoever is connected now. */
  public Effect<Done> publish(ChangeNotice notice) {
    publisher.publish(notice);
    return effects().reply(Done.getInstance());
  }

  public NotificationStream<ChangeNotice> updates() {
    return publisher.stream();
  }
}
