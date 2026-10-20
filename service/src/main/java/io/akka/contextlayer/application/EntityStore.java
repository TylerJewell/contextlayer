package io.akka.contextlayer.application;

import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Holds the entities read from sources and the identity links between them, and answers
 * structured questions. Every method takes the organization id and can only see that
 * organization's data (REQUIREMENTS ISO-01).
 */
public interface EntityStore {

  record UpsertResult(int stored, int skippedWithoutId) {}

  /** An identity cluster: the rule that matched and the normalized key. */
  record IdentityKey(String rule, String key) {}

  /** What the store holds for one entity type: how many, and when it was last refreshed (epoch millis). */
  record TypeSummary(String entityType, long count, long lastRefreshedAt) {}

  /**
   * One field of an entity type as the store holds it.
   *
   * @param kind string, number or boolean; fields holding lists or other structures are not queryable and are omitted
   * @param distinct how many different values the sampled entities held
   * @param sampled how many entities were looked at
   * @param values up to eight of the values seen, for the caller to use only if it is safe to
   */
  record FieldSchema(String name, String kind, long distinct, long sampled, List<String> values) {}

  /** A relationship that records of the owning type have to records of {@code targetType}. */
  record Relation(String name, String targetType) {}

  record TypeSchema(String entityType, long count, List<FieldSchema> fields, List<Relation> relations) {
    public TypeSchema(String entityType, long count, List<FieldSchema> fields) {
      this(entityType, count, fields, List.of());
    }
  }

  /**
   * @param rows one map per result row, keyed by the question's {@code Type.field} names
   * @param unconfirmedLeftOut how many additional base entities would have matched through
   *     identity links still waiting for review, and so were left out
   */
  record QueryResult(List<Map<String, Object>> rows, long unconfirmedLeftOut) {}

  UpsertResult upsert(
      String orgId, String source, String entityType, String idField, List<Map<String, Object>> records, long refreshedAt);

  /** Connects one stored entity to an identity cluster. Does nothing if the entity is not stored. */
  void linkIdentity(String orgId, String source, String entityType, String entityId, String rule, String key);

  /** Removes one entity's membership of one identity cluster. Does nothing if there is none. */
  void unlinkIdentity(String orgId, String source, String entityType, String entityId, String rule, String key);

  /** The ids of every entity held for one source and type. */
  Set<String> entityIds(String orgId, String source, String entityType);

  /** For each entity held for one source and type, the identity clusters it belongs to (entities with none are omitted). */
  Map<String, Set<IdentityKey>> identityMemberships(String orgId, String source, String entityType);

  /** The identity keys one entity belongs to. */
  Set<IdentityKey> identityKeysOf(String orgId, String source, String entityType, String entityId);

  /**
   * Makes the named relationship of each given entity point at the entities of {@code targetType} with the given ids,
   * replacing what it pointed at. A target that is not held yet is kept as a placeholder and joined to the real
   * record when it arrives, so records can be read in any order.
   */
  void relate(
      String orgId, String source, String entityType, String relation, String targetType,
      Map<String, List<String>> targetsByEntityId);

  /** Whether a pushed change was applied, or was not because the store already holds a newer one. */
  enum Applied { APPLIED, OLDER_THAN_HELD }

  /**
   * Stores one pushed record unless the store holds a later version of it. A record read by a pull
   * has no version and is replaced by any pushed one.
   */
  Applied applyChange(
      String orgId, String source, String entityType, String entityId, Map<String, Object> record, long version, long at);

  /** Removes one pushed-away record unless the store holds a later version of it. */
  Applied removeChange(String orgId, String source, String entityType, String entityId, long version);

  /** Removes entities and everything attached to them. Returns how many were removed. */
  int deleteEntities(String orgId, String source, String entityType, Collection<String> entityIds);

  /**
   * Whether every record in the cluster is already connected to the others through clusters that
   * are confirmed, so a person has nothing left to decide about it. A cluster of fewer than two
   * records is trivially covered.
   */
  boolean coveredByConfirmedLinks(String orgId, String rule, String key);

  /** Records the cluster's current status; only CONFIRMED clusters join entities in questions. */
  void setIdentityStatus(String orgId, String rule, String key, IdentityCluster.Status status);

  QueryResult run(String orgId, StructuredQuestion question);

  /** The entity types the organization holds and the fields they have, from a sample of each. */
  List<TypeSchema> schema(String orgId);

  /** What one source holds of one entity type, or empty if it holds none. */
  Optional<TypeSummary> summary(String orgId, String source, String entityType);

  /** Removes everything held for the organization. */
  void deleteOrganization(String orgId);
}
