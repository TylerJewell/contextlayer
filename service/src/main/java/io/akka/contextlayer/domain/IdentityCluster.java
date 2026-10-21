package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Records that share one key under one rule, whatever their entity type or source: a claim, a
 * CRM contact and a wealth account that carry the same email are candidates for the same real-world
 * party. A cluster with one member links nothing. A cluster of two or more is held for review, however
 * strong its rule, and is not used by questions, monitors or actions until a reviewer confirms it.
 *
 * @param contributors the people who loaded any of its records; none of them may confirm the cluster
 */
public record IdentityCluster(
    String orgId,
    String rule,
    String key,
    double confidence,
    List<Member> members,
    Review review,
    List<String> contributors,
    boolean exists) {

  public IdentityCluster {
    contributors = contributors == null ? List.of() : List.copyOf(contributors);
  }

  public record Member(String source, String entityType, String sourceRecordId) {}

  public enum Review { NONE, CONFIRMED, REJECTED }

  public enum Status {
    /** Only one record has this key, so nothing is linked. */
    SINGLE,
    CONFIRMED,
    PENDING_REVIEW,
    REJECTED
  }

  public static IdentityCluster empty() {
    return new IdentityCluster("", "", "", 0.0, List.of(), Review.NONE, List.of(), false);
  }

  /**
   * Names the set of records a review was asked about. A review decided on one set says nothing
   * about another, so the human event for a cluster is keyed by this: when a record joins or
   * leaves, the question is a new one.
   */
  public static String fingerprintOf(List<Member> members) {
    var text = members.stream().map(m -> m.source() + "/" + m.entityType() + "/" + m.sourceRecordId())
        .sorted().collect(java.util.stream.Collectors.joining("|"));
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest, 0, 6);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String fingerprint() {
    return fingerprintOf(members);
  }

  public Status status() {
    return statusOf(members.size(), confidence, review);
  }

  public static Status statusOf(int memberCount, double confidence, Review review) {
    if (review == Review.REJECTED) return Status.REJECTED;
    if (memberCount < 2) return Status.SINGLE;
    return review == Review.CONFIRMED ? Status.CONFIRMED : Status.PENDING_REVIEW;
  }

  /** Whether records in this cluster may be treated as one entity by monitors and actions. */
  public boolean usable() {
    return status() == Status.CONFIRMED;
  }

  public boolean hasMember(String source, String entityType, String sourceRecordId) {
    return members.contains(new Member(source, entityType, sourceRecordId));
  }

  public Optional<String> validateReview(String reviewer) {
    if (!exists) return Optional.of("link not found");
    if (reviewer == null || reviewer.isBlank()) return Optional.of("a reviewer is required");
    if (status() != Status.PENDING_REVIEW) {
      return Optional.of("cluster is " + status() + ", not awaiting review");
    }
    return Optional.empty();
  }

  public Optional<String> validateUnlink(String source, String entityType, String sourceRecordId, String by) {
    if (!exists) return Optional.of("link not found");
    if (by == null || by.isBlank()) return Optional.of("who is unlinking is required");
    if (!hasMember(source, entityType, sourceRecordId)) return Optional.of("record is not in this cluster");
    return Optional.empty();
  }

  public IdentityCluster apply(IdentityEvent event) {
    return switch (event) {
      case IdentityEvent.MemberAdded e -> {
        var next = new ArrayList<>(members);
        var member = new Member(e.source(), e.entityType(), e.sourceRecordId());
        if (!next.contains(member)) next.add(member);
        var people = new ArrayList<>(contributors);
        if (!e.addedBy().isBlank() && !people.contains(e.addedBy())) people.add(e.addedBy());
        // A new member invalidates an earlier review decision made on a different member set.
        yield new IdentityCluster(e.orgId(), e.rule(), e.key(), e.confidence(), List.copyOf(next),
            members.contains(member) ? review : Review.NONE, people, true);
      }
      case IdentityEvent.MemberUnlinked e -> {
        var next = new ArrayList<>(members);
        next.remove(new Member(e.source(), e.entityType(), e.sourceRecordId()));
        yield new IdentityCluster(orgId, rule, key, confidence, List.copyOf(next), Review.NONE, contributors, true);
      }
      case IdentityEvent.ReviewConfirmed e ->
          new IdentityCluster(orgId, rule, key, confidence, members, Review.CONFIRMED, contributors, true);
      case IdentityEvent.ReviewRejected e ->
          new IdentityCluster(orgId, rule, key, confidence, members, Review.REJECTED, contributors, true);
    };
  }
}
