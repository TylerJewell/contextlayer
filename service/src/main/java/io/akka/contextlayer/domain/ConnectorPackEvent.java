package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface ConnectorPackEvent {

  @TypeName("connector-pack-imported")
  record Imported(String orgId, String name, String hash, PackDefinition definition, String importedBy)
      implements ConnectorPackEvent {}

  @TypeName("connector-pack-activated")
  record Activated(String hash, ConnectorPack.Mode mode, String activatedBy)
      implements ConnectorPackEvent {}

  @TypeName("connector-pack-deactivated")
  record Deactivated(String deactivatedBy) implements ConnectorPackEvent {}
}
