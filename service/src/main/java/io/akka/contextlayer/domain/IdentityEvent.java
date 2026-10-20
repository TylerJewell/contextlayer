package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface IdentityEvent {

  @TypeName("identity-member-added")
  record MemberAdded(
      String orgId,
      String rule,
      String key,
      double confidence,
      String source,
      String entityType,
      String sourceRecordId)
      implements IdentityEvent {}

  @TypeName("identity-member-unlinked")
  record MemberUnlinked(String source, String entityType, String sourceRecordId, String unlinkedBy, String reason)
      implements IdentityEvent {}

  @TypeName("identity-review-confirmed")
  record ReviewConfirmed(String confirmedBy) implements IdentityEvent {}

  @TypeName("identity-review-rejected")
  record ReviewRejected(String rejectedBy) implements IdentityEvent {}
}
