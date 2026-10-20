package claims.api;

import akka.javasdk.annotations.TypeName;
import java.util.Map;

/**
 * What this system publishes on its {@code entity_changes} stream. The type names are part of the
 * contract with the context service, which reads the stream; they stay as they are when a class is
 * renamed.
 */
public sealed interface EntityChange {

  String entityType();

  @TypeName("entity-upserted")
  record Upserted(String entityType, String entityId, long version, Map<String, Object> attributes)
      implements EntityChange {}

  @TypeName("entity-deleted")
  record Deleted(String entityType, String entityId, long version) implements EntityChange {}

  @TypeName("entity-heartbeat")
  record Heartbeat(String entityType) implements EntityChange {}
}
