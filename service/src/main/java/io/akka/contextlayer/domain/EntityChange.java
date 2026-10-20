package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;
import java.util.Map;

/**
 * A change to one record in a source system, as the source pushes it. This is the stream a source
 * publishes for the service to consume: an Akka service publishes it as a service stream, and any
 * other system can post it to {@code /v1/context/streams/changes}.
 *
 * <p>{@code version} orders the changes to one record. A change is applied only if its version is
 * later than the one held, so redelivery and reordering do not undo newer data. Use the source's own
 * version number or a timestamp.
 */
public sealed interface EntityChange {

  String entityType();

  /** The record now looks like {@code attributes}. */
  @TypeName("entity-upserted")
  record Upserted(String entityType, String entityId, long version, Map<String, Object> attributes)
      implements EntityChange {}

  /** The record no longer exists. */
  @TypeName("entity-deleted")
  record Deleted(String entityType, String entityId, long version) implements EntityChange {}

  /** The source is alive and has nothing new for this type. It keeps a quiet type from reading as stale. */
  @TypeName("entity-heartbeat")
  record Heartbeat(String entityType) implements EntityChange {}
}
