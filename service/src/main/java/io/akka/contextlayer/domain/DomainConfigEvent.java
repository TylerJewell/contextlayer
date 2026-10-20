package io.akka.contextlayer.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface DomainConfigEvent {

  @TypeName("domain-set")
  record DomainSet(DataDomain domain) implements DomainConfigEvent {}

  @TypeName("domain-removed")
  record DomainRemoved(String name, String by, long at) implements DomainConfigEvent {}

  @TypeName("join-approved")
  record JoinApproved(DomainConfig.JoinRule rule) implements DomainConfigEvent {}

  @TypeName("join-revoked")
  record JoinRevoked(String a, String b, String by, long at) implements DomainConfigEvent {}
}
