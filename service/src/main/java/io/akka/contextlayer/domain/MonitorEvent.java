package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;
import java.util.Map;

public sealed interface MonitorEvent {

  @TypeName("monitor-created")
  record Created(String orgId, String monitorId, MonitorDefinition definition, String createdBy)
      implements MonitorEvent {}

  @TypeName("monitor-definition-updated")
  record DefinitionUpdated(MonitorDefinition definition, String updatedBy) implements MonitorEvent {}

  @TypeName("monitor-enabled-changed")
  record EnabledChanged(boolean enabled, String changedBy) implements MonitorEvent {}

  /**
   * @param newCases key to case id, for rows that newly match
   * @param goneCases key to case id, for rows that no longer match (empty when the run was incomplete)
   */
  @TypeName("monitor-run-recorded")
  record RunRecorded(
      int runNumber,
      long at,
      boolean complete,
      Map<String, String> newCases,
      Map<String, String> goneCases,
      String note)
      implements MonitorEvent {}
}
