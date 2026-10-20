package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;
import java.util.List;
import java.util.Map;

public sealed interface CaseEvent {

  @TypeName("case-opened")
  record Opened(
      String orgId,
      String caseId,
      String monitorId,
      int monitorVersion,
      String key,
      Map<String, Object> evidence,
      List<String> sourceNotes,
      String routeTo,
      String assistantId,
      long at)
      implements CaseEvent {}

  @TypeName("case-no-longer-matching")
  record NoLongerMatching(long at) implements CaseEvent {}

  @TypeName("case-resolved")
  record Resolved(String by, String note, long at) implements CaseEvent {}

  @TypeName("case-dismissed")
  record Dismissed(String by, String note, long at) implements CaseEvent {}

  @TypeName("case-annotated")
  record Annotated(String actor, String text, long at) implements CaseEvent {}
}
