package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ConnectorPack;
import io.akka.contextlayer.domain.IdentityRules;
import io.akka.contextlayer.domain.PackDefinition;
import io.akka.contextlayer.domain.RecordFlattener;
import io.akka.contextlayer.domain.RefreshStatus;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.Controls;
import io.akka.contextlayer.governance.EvaluationMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Reads one entity type from an active connector pack into the entity store, offers each record's
 * identity keys to the identity clusters, and records what happened. Used by the background
 * refresh workflow and by questions that need data within a freshness policy.
 *
 * <p>A failed read changes nothing in the store: the data already held stays, and the recorded
 * status says the refresh failed.
 */
public class EntityRefresher {

  private final ComponentClient componentClient;
  private final SourceCaller sourceCaller;
  private final EntityStore store;
  private final LongSupplier nowMillis;

  public EntityRefresher(
      ComponentClient componentClient, SourceCaller sourceCaller, EntityStore store, LongSupplier nowMillis) {
    this.componentClient = componentClient;
    this.sourceCaller = sourceCaller;
    this.store = store;
    this.nowMillis = nowMillis;
  }

  public RefreshStatus refresh(String orgId, String packName, String entityType, Map<String, String> filters) {
    var pack = componentClient.forEventSourcedEntity(ScopedId.of(orgId, packName))
        .method(ConnectorPackEntity::get)
        .invoke();
    var status = refresh(orgId, packName, pack, entityType, filters);
    componentClient.forKeyValueEntity(ScopedId.of(orgId, packName, entityType))
        .method(RefreshStatusEntity::record)
        .invoke(status);
    return status;
  }

  /** Applies one record a source pushed, and tells the identity clusters what it now looks like. */
  public EntityStore.Applied applyPushed(
      String orgId, String packName, PackDefinition.EntityMapping entity, String entityId, Map<String, Object> attributes,
      long version, long at) {
    var record = new java.util.LinkedHashMap<>(attributes);
    record.put(entity.idField(), entityId);
    var before = store.identityKeysOf(orgId, packName, entity.type(), entityId);
    var applied = store.applyChange(orgId, packName, entity.type(), entityId, record, version, at);
    if (applied == EntityStore.Applied.OLDER_THAN_HELD) return applied;
    applyRelations(orgId, packName, entity, List.of(record));
    var now = offerIdentityKeys(orgId, packName, entity, List.of(record)).getOrDefault(entityId, Set.of());
    for (var key : before) {
      if (!now.contains(key)) leave(orgId, packName, entity.type(), entityId, key, "its details no longer match");
    }
    return applied;
  }

  /** Removes one record a source says is gone, and takes it out of its identity clusters. */
  public EntityStore.Applied applyRemoved(
      String orgId, String packName, String entityType, String entityId, long version) {
    var before = store.identityKeysOf(orgId, packName, entityType, entityId);
    var applied = store.removeChange(orgId, packName, entityType, entityId, version);
    if (applied == EntityStore.Applied.APPLIED) {
      for (var key : before) leave(orgId, packName, entityType, entityId, key, "the record was deleted at the source");
    }
    return applied;
  }

  /** The egress allow list is a gate on every source call: a refusal and a call let through are both evidence. */
  private void recordEgress(String orgId, String packName, String entityType, SourceCaller.Result result, long at) {
    boolean refused = result.refusedByEgress();
    new EvidenceRecorder(componentClient).record(new EvidenceRecorder.Fact(orgId, Controls.EGRESS,
        packName + "~" + entityType + "~" + at, EvaluationMode.ENFORCING, refused ? "BLOCK" : "PASS",
        refused ? "BLOCKED" : "CONTINUED", refused ? "host-not-allowed" : "host-allowed", packName + "/" + entityType,
        "system:egress", "system", "", false, "", at));
  }

  private RefreshStatus refresh(
      String orgId, String packName, ConnectorPack pack, String entityType, Map<String, String> filters) {
    long now = nowMillis.getAsLong();
    if (!pack.exists() || pack.mode() == ConnectorPack.Mode.INACTIVE || pack.active().isEmpty()) {
      return failed(orgId, packName, entityType, now, "", "the connector " + packName + " is not active");
    }
    var version = pack.active().get();
    var definition = version.definition();
    var entity = definition.entities() == null ? null : definition.entities().stream()
        .filter(e -> e.type().equals(entityType)).findFirst().orElse(null);
    if (entity == null) {
      return failed(orgId, packName, entityType, now, version.hash(),
          "the connector " + packName + " does not declare " + entityType);
    }

    if (entity.operation() == null || entity.operation().isBlank()) {
      return failed(orgId, packName, entityType, now, version.hash(),
          entityType + " is fed by what its source pushes and has no operation to read");
    }
    var result = sourceCaller.fetchEntities(orgId, definition, entity, filters);
    recordEgress(orgId, packName, entityType, result, now);
    if (result.status() == SourceCaller.Status.FAILED) {
      return RefreshStatus.of(orgId, packName, entityType, RefreshStatus.Outcome.FAILED, now, 0, 0,
          version.hash(), result.warnings());
    }

    var heldBefore = store.entityIds(orgId, packName, entityType);
    var membershipsBefore = store.identityMemberships(orgId, packName, entityType);
    var stored = store.upsert(orgId, packName, entityType, entity.idField(), result.records(), now);

    var seen = new HashSet<String>();
    for (var record : result.records()) {
      var id = RecordFlattener.idOf(record, entity.idField());
      if (id != null) seen.add(id);
    }
    applyRelations(orgId, packName, entity, result.records());
    var membershipsNow = offerIdentityKeys(orgId, packName, entity, result.records());
    dropOutdatedMemberships(orgId, packName, entityType, membershipsBefore, membershipsNow, seen);

    // Only a complete read proves a record is gone. A partial read simply did not reach it.
    var complete = result.status() == SourceCaller.Status.OK;
    if (complete) removeRecordsGone(orgId, packName, entityType, heldBefore, seen, membershipsBefore);

    var outcome = complete ? RefreshStatus.Outcome.OK : RefreshStatus.Outcome.PARTIAL;
    return RefreshStatus.of(orgId, packName, entityType, outcome, now, stored.stored(), stored.skippedWithoutId(),
        version.hash(), result.warnings());
  }

  /** Points each record's declared relationships at the records its fields name, replacing what they pointed at. */
  private void applyRelations(String orgId, String packName, PackDefinition.EntityMapping entity, List<Map<String, Object>> records) {
    for (var relation : entity.relations()) {
      var targets = new java.util.LinkedHashMap<String, List<String>>();
      for (var record : records) {
        var id = RecordFlattener.idOf(record, entity.idField());
        if (id != null) targets.put(id, idsOf(record.get(relation.field())));
      }
      store.relate(orgId, packName, entity.type(), relation.name(), relation.targetType(), targets);
    }
  }

  /** The ids a relationship field holds: one value, or a list of them. */
  static List<String> idsOf(Object value) {
    if (value == null) return List.of();
    var out = new java.util.ArrayList<String>();
    for (var item : value instanceof java.util.Collection<?> collection ? collection : List.of(value)) {
      if (item == null) continue;
      var text = String.valueOf(item).strip();
      if (!text.isEmpty()) out.add(text);
    }
    return List.copyOf(out);
  }

  /** Offers each record's identity keys to their clusters and returns the keys each record now has. */
  private Map<String, Set<EntityStore.IdentityKey>> offerIdentityKeys(
      String orgId, String packName, PackDefinition.EntityMapping entity, List<Map<String, Object>> records) {
    var now = new HashMap<String, Set<EntityStore.IdentityKey>>();
    if (entity.identityAttributes() == null || entity.identityAttributes().isEmpty()) return now;
    for (var record : records) {
      var recordId = RecordFlattener.idOf(record, entity.idField());
      if (recordId == null) continue;
      for (var candidate : IdentityRules.candidates(identityAttributes(entity, record))) {
        var cluster = componentClient.forEventSourcedEntity(ScopedId.of(orgId, candidate.rule(), ScopedId.safe(candidate.key())))
            .method(IdentityEntity::addMember)
            .invoke(new IdentityEntity.AddMember(orgId, candidate.rule(), candidate.key(), candidate.confidence(),
                packName, entity.type(), recordId));
        // Linked here as well as by IdentityGraphConsumer, so a question asked right after a refresh
        // already sees the links; both steps can be repeated safely.
        store.linkIdentity(orgId, packName, entity.type(), recordId, candidate.rule(), candidate.key());
        store.setIdentityStatus(orgId, candidate.rule(), candidate.key(), cluster.status());
        now.computeIfAbsent(recordId, k -> new HashSet<>()).add(new EntityStore.IdentityKey(candidate.rule(), candidate.key()));
      }
    }
    return now;
  }

  /** A record whose identifying details changed is no longer a member of the clusters they used to give it. */
  private void dropOutdatedMemberships(
      String orgId, String packName, String entityType, Map<String, Set<EntityStore.IdentityKey>> before,
      Map<String, Set<EntityStore.IdentityKey>> now, Set<String> seen) {
    for (var entry : before.entrySet()) {
      if (!seen.contains(entry.getKey())) continue;
      for (var key : entry.getValue()) {
        if (!now.getOrDefault(entry.getKey(), Set.of()).contains(key)) {
          leave(orgId, packName, entityType, entry.getKey(), key, "its details no longer match");
        }
      }
    }
  }

  private void removeRecordsGone(
      String orgId, String packName, String entityType, Set<String> heldBefore, Set<String> seen,
      Map<String, Set<EntityStore.IdentityKey>> membershipsBefore) {
    var gone = new HashSet<>(heldBefore);
    gone.removeAll(seen);
    if (gone.isEmpty()) return;
    for (var id : gone) {
      for (var key : membershipsBefore.getOrDefault(id, Set.of())) {
        leave(orgId, packName, entityType, id, key, "the record is no longer in the source");
      }
    }
    store.deleteEntities(orgId, packName, entityType, gone);
  }

  /** Takes one record out of one identity cluster, in the cluster's history and in the graph. */
  private void leave(String orgId, String packName, String entityType, String recordId, EntityStore.IdentityKey key,
      String reason) {
    var cluster = componentClient.forEventSourcedEntity(ScopedId.of(orgId, key.rule(), ScopedId.safe(key.key())))
        .method(IdentityEntity::unlink)
        .invoke(new IdentityEntity.Unlink(packName, entityType, recordId, "system", reason));
    store.unlinkIdentity(orgId, packName, entityType, recordId, key.rule(), key.key());
    if (cluster.exists()) store.setIdentityStatus(orgId, key.rule(), key.key(), cluster.status());
  }

  /** An entry "email" reads the field named email; "email=contactEmail" reads contactEmail as email. */
  static Map<String, String> identityAttributes(PackDefinition.EntityMapping entity, Map<String, Object> record) {
    var attributes = new HashMap<String, String>();
    for (var entry : entity.identityAttributes()) {
      var parts = entry.split("=", 2);
      var attribute = parts[0].strip();
      var field = parts.length == 2 ? parts[1].strip() : attribute;
      var value = record.get(field);
      if (value != null) attributes.put(attribute, String.valueOf(value));
    }
    return attributes;
  }

  private static RefreshStatus failed(
      String orgId, String packName, String entityType, long now, String hash, String reason) {
    return RefreshStatus.of(orgId, packName, entityType, RefreshStatus.Outcome.FAILED, now, 0, 0, hash, List.of(reason));
  }
}
