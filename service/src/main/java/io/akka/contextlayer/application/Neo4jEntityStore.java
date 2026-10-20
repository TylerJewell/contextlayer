package io.akka.contextlayer.application;

import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.RecordFlattener;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;

/**
 * {@link EntityStore} on Neo4j. Entities are {@code (:Entity)} nodes keyed by organization, type,
 * source and id; identity clusters are {@code (:Identity)} nodes that entities join with
 * {@code MEMBER_OF}. Every statement carries the organization id, and questions are compiled by
 * {@link Neo4jQueryCompiler}, so no caller-supplied text reaches a statement.
 */
public class Neo4jEntityStore implements EntityStore {

  private static final int BATCH = 500;
  /** The most entities a coverage walk follows before it gives up and says "not covered". */
  private static final int MAX_WALK = 1_000;

  private final Driver driver;

  public Neo4jEntityStore(Driver driver) {
    this.driver = driver;
  }

  public void ensureSchema() {
    try (var session = driver.session()) {
      session.run("CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (e:Entity) "
          + "REQUIRE (e.orgId, e.type, e.source, e.id) IS UNIQUE").consume();
      // Relationships and identity find a record by its type and id from any source, which the key above, led by the source, cannot serve.
      session.run("CREATE INDEX entity_type_id IF NOT EXISTS FOR (e:Entity) ON (e.orgId, e.type, e.id)").consume();
      session.run("CREATE CONSTRAINT identity_key IF NOT EXISTS FOR (i:Identity) "
          + "REQUIRE (i.orgId, i.rule, i.key) IS UNIQUE").consume();
      session.run("CREATE CONSTRAINT placeholder_key IF NOT EXISTS FOR (p:Placeholder) "
          + "REQUIRE (p.orgId, p.type, p.id) IS UNIQUE").consume();
    }
  }

  @Override
  public UpsertResult upsert(
      String orgId, String source, String entityType, String idField, List<Map<String, Object>> records, long refreshedAt) {
    var rows = new ArrayList<Map<String, Object>>();
    int skipped = 0;
    for (var record : records) {
      var id = RecordFlattener.idOf(record, idField);
      if (id == null) {
        skipped++;
        continue;
      }
      rows.add(Map.of("id", id, "props", RecordFlattener.flatten(record)));
    }
    for (int from = 0; from < rows.size(); from += BATCH) {
      var batch = rows.subList(from, Math.min(rows.size(), from + BATCH));
      write(new Query(
          "UNWIND $rows AS row "
              + "MERGE (e:Entity {orgId: $orgId, type: $type, source: $source, id: row.id}) "
              + "SET e = row.props "
              + "SET e.orgId = $orgId, e.type = $type, e.source = $source, e.id = row.id, e.refreshedAt = $refreshedAt",
          Map.of("rows", new ArrayList<>(batch), "orgId", orgId, "type", entityType, "source", source,
              "refreshedAt", refreshedAt)));
      resolvePlaceholders(orgId, entityType, batch.stream().map(r -> (String) r.get("id")).toList());
    }
    return new UpsertResult(rows.size(), skipped);
  }

  @Override
  public Applied applyChange(
      String orgId, String source, String entityType, String entityId, Map<String, Object> record, long version, long at) {
    var rows = writeRows(new Query(
        "MERGE (e:Entity {orgId: $orgId, type: $type, source: $source, id: $id}) "
            + "WITH e WHERE e.streamVersion IS NULL OR e.streamVersion < $version "
            + "SET e = $props "
            + "SET e.orgId = $orgId, e.type = $type, e.source = $source, e.id = $id, e.refreshedAt = $at, "
            + "e.streamVersion = $version "
            + "RETURN count(e) AS applied",
        Map.of("orgId", orgId, "type", entityType, "source", source, "id", entityId,
            "props", RecordFlattener.flatten(record), "version", version, "at", at)));
    boolean applied = ((Number) rows.get(0).get("applied")).intValue() > 0;
    if (applied) resolvePlaceholders(orgId, entityType, List.of(entityId));
    return applied ? Applied.APPLIED : Applied.OLDER_THAN_HELD;
  }

  @Override
  public void relate(
      String orgId, String source, String entityType, String relation, String targetType,
      Map<String, List<String>> targetsByEntityId) {
    var ids = new ArrayList<>(targetsByEntityId.keySet());
    for (int from = 0; from < ids.size(); from += BATCH) {
      var batch = new ArrayList<>(ids.subList(from, Math.min(ids.size(), from + BATCH)));
      var base = Map.<String, Object>of("orgId", orgId, "type", entityType, "source", source, "name", relation,
          "targetType", targetType);
      var withIds = new java.util.HashMap<>(base);
      withIds.put("ids", batch);
      write(new Query(
          "UNWIND $ids AS id MATCH (a:Entity {orgId: $orgId, type: $type, source: $source, id: id})"
              + "-[r:REL {orgId: $orgId, name: $name}]->() DELETE r", withIds));
      var pairs = new ArrayList<Map<String, Object>>();
      for (var id : batch) {
        var to = targetsByEntityId.get(id);
        if (to != null && !to.isEmpty()) pairs.add(Map.of("from", id, "to", new ArrayList<>(to)));
      }
      if (pairs.isEmpty()) continue;
      var withPairs = new java.util.HashMap<>(base);
      withPairs.put("pairs", pairs);
      write(new Query(
          "UNWIND $pairs AS pair "
              + "MATCH (a:Entity {orgId: $orgId, type: $type, source: $source, id: pair.from}) "
              + "UNWIND pair.to AS tid "
              + "OPTIONAL MATCH (b:Entity {orgId: $orgId, type: $targetType, id: tid}) "
              + "WITH a, tid, collect(b) AS bs "
              + "FOREACH (b IN bs | MERGE (a)-[:REL {orgId: $orgId, name: $name}]->(b)) "
              + "FOREACH (_ IN CASE WHEN size(bs) = 0 THEN [1] ELSE [] END | "
              + "MERGE (p:Placeholder {orgId: $orgId, type: $targetType, id: tid}) "
              + "MERGE (a)-[:REL {orgId: $orgId, name: $name}]->(p))", withPairs));
    }
  }

  /** Points whatever referred to these ids before they were held at the real records, and drops the placeholders. */
  private void resolvePlaceholders(String orgId, String entityType, List<String> ids) {
    write(new Query(
        "UNWIND $ids AS id "
            + "MATCH (p:Placeholder {orgId: $orgId, type: $type, id: id}) "
            + "MATCH (e:Entity {orgId: $orgId, type: $type, id: id}) "
            + "CALL { WITH p, e MATCH (x)-[r:REL]->(p) MERGE (x)-[:REL {orgId: r.orgId, name: r.name}]->(e) } "
            + "DETACH DELETE p",
        Map.of("orgId", orgId, "type", entityType, "ids", new ArrayList<>(ids))));
  }

  @Override
  public Applied removeChange(String orgId, String source, String entityType, String entityId, long version) {
    var params = Map.<String, Object>of("orgId", orgId, "type", entityType, "source", source, "id", entityId,
        "version", version);
    var held = read(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source, id: $id}) "
            + "WHERE e.streamVersion IS NOT NULL AND e.streamVersion > $version RETURN count(e) AS newer", params));
    if (((Number) held.get(0).get("newer")).intValue() > 0) return Applied.OLDER_THAN_HELD;
    deleteEntities(orgId, source, entityType, List.of(entityId));
    return Applied.APPLIED;
  }

  @Override
  public void linkIdentity(String orgId, String source, String entityType, String entityId, String rule, String key) {
    write(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source, id: $id}) "
            + "MERGE (i:Identity {orgId: $orgId, rule: $rule, key: $key}) "
            + "ON CREATE SET i.status = 'SINGLE' "
            + "MERGE (e)-[:MEMBER_OF]->(i)",
        Map.of("orgId", orgId, "type", entityType, "source", source, "id", entityId, "rule", rule, "key", key)));
  }

  @Override
  public void setIdentityStatus(String orgId, String rule, String key, IdentityCluster.Status status) {
    write(new Query(
        "MERGE (i:Identity {orgId: $orgId, rule: $rule, key: $key}) SET i.status = $status",
        Map.of("orgId", orgId, "rule", rule, "key", key, "status", status.name())));
  }

  @Override
  public void unlinkIdentity(String orgId, String source, String entityType, String entityId, String rule, String key) {
    write(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source, id: $id})"
            + "-[r:MEMBER_OF]->(:Identity {orgId: $orgId, rule: $rule, key: $key}) DELETE r",
        Map.of("orgId", orgId, "type", entityType, "source", source, "id", entityId, "rule", rule, "key", key)));
  }

  @Override
  public Set<String> entityIds(String orgId, String source, String entityType) {
    var ids = new HashSet<String>();
    for (var row : read(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source}) RETURN e.id AS id",
        Map.of("orgId", orgId, "type", entityType, "source", source)))) {
      ids.add((String) row.get("id"));
    }
    return ids;
  }

  @Override
  public Map<String, Set<IdentityKey>> identityMemberships(String orgId, String source, String entityType) {
    var memberships = new HashMap<String, Set<IdentityKey>>();
    for (var row : read(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source})-[:MEMBER_OF]->(i:Identity {orgId: $orgId}) "
            + "RETURN e.id AS id, i.rule AS rule, i.key AS key",
        Map.of("orgId", orgId, "type", entityType, "source", source)))) {
      memberships.computeIfAbsent((String) row.get("id"), k -> new HashSet<>())
          .add(new IdentityKey((String) row.get("rule"), (String) row.get("key")));
    }
    return memberships;
  }

  @Override
  public Set<IdentityKey> identityKeysOf(String orgId, String source, String entityType, String entityId) {
    var keys = new HashSet<IdentityKey>();
    for (var row : read(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source, id: $id})-[:MEMBER_OF]->(i:Identity {orgId: $orgId}) "
            + "RETURN i.rule AS rule, i.key AS key",
        Map.of("orgId", orgId, "type", entityType, "source", source, "id", entityId)))) {
      keys.add(new IdentityKey((String) row.get("rule"), (String) row.get("key")));
    }
    return keys;
  }

  @Override
  public int deleteEntities(String orgId, String source, String entityType, Collection<String> entityIds) {
    var ids = new ArrayList<>(entityIds);
    int removed = 0;
    for (int from = 0; from < ids.size(); from += BATCH) {
      var batch = new ArrayList<>(ids.subList(from, Math.min(ids.size(), from + BATCH)));
      var params = Map.<String, Object>of("orgId", orgId, "type", entityType, "source", source, "ids", batch);
      removed += ((Number) read(new Query(
          "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source}) WHERE e.id IN $ids RETURN count(e) AS n",
          params)).get(0).get("n")).intValue();
      // Whatever referred to a record that goes keeps its reference, as a placeholder the record can rejoin.
      write(new Query(
          "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source}) WHERE e.id IN $ids "
              + "MATCH (x)-[r:REL]->(e) "
              + "MERGE (p:Placeholder {orgId: $orgId, type: e.type, id: e.id}) "
              + "MERGE (x)-[:REL {orgId: r.orgId, name: r.name}]->(p)", params));
      write(new Query(
          "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source}) WHERE e.id IN $ids DETACH DELETE e", params));
    }
    // An identity that no entity belongs to any more carries no information.
    write(new Query(
        "MATCH (i:Identity {orgId: $orgId}) WHERE NOT (i)<-[:MEMBER_OF]-() DELETE i", Map.of("orgId", orgId)));
    return removed;
  }

  @Override
  public boolean coveredByConfirmedLinks(String orgId, String rule, String key) {
    var members = new HashSet<String>();
    for (var row : read(new Query(
        "MATCH (m:Entity {orgId: $orgId})-[:MEMBER_OF]->(:Identity {orgId: $orgId, rule: $rule, key: $key}) "
            + "RETURN elementId(m) AS id",
        Map.of("orgId", orgId, "rule", rule, "key", key)))) {
      members.add((String) row.get("id"));
    }
    if (members.size() < 2) return true;

    // Walk outward from one member through confirmed identities only. The cluster being asked about
    // is not confirmed, so it can never be used to prove itself.
    var visited = new HashSet<String>();
    visited.add(members.iterator().next());
    var frontier = new HashSet<>(visited);
    while (!frontier.isEmpty() && visited.size() < MAX_WALK && !visited.containsAll(members)) {
      var next = new HashSet<String>();
      for (var row : read(new Query(
          "MATCH (a:Entity {orgId: $orgId})-[:MEMBER_OF]->(:Identity {orgId: $orgId, status: 'CONFIRMED'})"
              + "<-[:MEMBER_OF]-(b:Entity {orgId: $orgId}) WHERE elementId(a) IN $ids AND a <> b "
              + "RETURN DISTINCT elementId(b) AS id",
          Map.of("orgId", orgId, "ids", new ArrayList<>(frontier))))) {
        next.add((String) row.get("id"));
      }
      next.removeAll(visited);
      visited.addAll(next);
      frontier = next;
    }
    return visited.containsAll(members);
  }

  @Override
  public QueryResult run(String orgId, StructuredQuestion question) {
    var confirmed = Neo4jQueryCompiler.compile(
        orgId, question, IdentityCluster.Status.CONFIRMED, Neo4jQueryCompiler.Output.ROWS);
    List<Map<String, Object>> rows = read(new Query(confirmed.cypher(), confirmed.parameters()));

    long leftOut = 0;
    if (question.links() != null && question.links().stream().anyMatch(StructuredQuestion.Link::byIdentity)) {
      var viaConfirmed = baseIds(orgId, question, IdentityCluster.Status.CONFIRMED);
      var viaPending = baseIds(orgId, question, IdentityCluster.Status.PENDING_REVIEW);
      viaPending.removeAll(viaConfirmed);
      leftOut = viaPending.size();
    }
    return new QueryResult(rows, leftOut);
  }

  @Override
  public Optional<TypeSummary> summary(String orgId, String source, String entityType) {
    var rows = read(new Query(
        "MATCH (e:Entity {orgId: $orgId, type: $type, source: $source}) RETURN count(e) AS c, max(e.refreshedAt) AS m",
        Map.of("orgId", orgId, "type", entityType, "source", source)));
    var row = rows.get(0);
    long count = ((Number) row.get("c")).longValue();
    if (count == 0) return Optional.empty();
    return Optional.of(new TypeSummary(entityType, count, ((Number) row.get("m")).longValue()));
  }

  private List<Relation> relationsOf(String orgId, String entityType) {
    var relations = new ArrayList<Relation>();
    for (var row : read(new Query(
        // Sampled like the fields: a type declares its relationships for every record, and walking every edge costs as much as the data.
        "MATCH (a:Entity {orgId: $orgId, type: $type}) WITH a LIMIT " + SCHEMA_SAMPLE + " "
            + "MATCH (a)-[r:REL {orgId: $orgId}]->(b) "
            + "RETURN DISTINCT r.name AS name, b.type AS target ORDER BY name, target",
        Map.of("orgId", orgId, "type", entityType)))) {
      relations.add(new Relation((String) row.get("name"), (String) row.get("target")));
    }
    return relations;
  }

  private static final int SCHEMA_SAMPLE = 200;
  private static final List<String> STORE_PROPERTIES = List.of("orgId", "type", "source", "id", "refreshedAt", "streamVersion");

  @Override
  public List<TypeSchema> schema(String orgId) {
    var out = new ArrayList<TypeSchema>();
    for (var typeRow : read(new Query(
        "MATCH (e:Entity {orgId: $orgId}) RETURN e.type AS type, count(e) AS n ORDER BY type", Map.of("orgId", orgId)))) {
      var type = (String) typeRow.get("type");
      long count = ((Number) typeRow.get("n")).longValue();
      long sampled = Math.min(count, SCHEMA_SAMPLE);
      var fields = new ArrayList<FieldSchema>();
      for (var row : read(new Query(
          "MATCH (e:Entity {orgId: $orgId, type: $type}) WITH e LIMIT " + SCHEMA_SAMPLE + " "
              + "UNWIND keys(e) AS k WITH k, e[k] AS v WHERE NOT k IN $skip "
              + "RETURN k AS name, collect(DISTINCT valueType(v)) AS kinds, count(DISTINCT v) AS distinct, "
              + "collect(DISTINCT v)[0..8] AS vals ORDER BY name",
          Map.of("orgId", orgId, "type", type, "skip", STORE_PROPERTIES)))) {
        var kind = fieldKind((List<?>) row.get("kinds"));
        if (kind == null) continue;
        var values = ((List<?>) row.get("vals")).stream().map(String::valueOf).toList();
        fields.add(new FieldSchema((String) row.get("name"), kind, ((Number) row.get("distinct")).longValue(), sampled, values));
      }
      out.add(new TypeSchema(type, count, fields, relationsOf(orgId, type)));
    }
    return out;
  }

  /** One kind for a field, or null when it holds lists, mixed kinds or anything a question cannot compare. */
  private static String fieldKind(List<?> valueTypes) {
    String kind = null;
    for (var t : valueTypes) {
      var text = String.valueOf(t);
      var next = text.startsWith("STRING") ? "string"
          : text.startsWith("INTEGER") || text.startsWith("FLOAT") ? "number"
          : text.startsWith("BOOLEAN") ? "boolean" : null;
      if (next == null || (kind != null && !kind.equals(next))) return null;
      kind = next;
    }
    return kind;
  }

  @Override
  public void deleteOrganization(String orgId) {
    // A project can hold hundreds of thousands of nodes, which one transaction would have to hold in memory to delete.
    // Each label is deleted on its own so the project id is looked up in that label's index and nothing else is scanned.
    for (var label : List.of("Entity", "Placeholder", "Identity")) {
      int removed;
      do {
        removed = ((Number) writeRows(new Query(
            "MATCH (n:" + label + " {orgId: $orgId}) WITH n LIMIT 10000 DETACH DELETE n RETURN count(*) AS removed",
            Map.of("orgId", orgId))).get(0).get("removed")).intValue();
      } while (removed > 0);
    }
  }

  private HashSet<String> baseIds(String orgId, StructuredQuestion question, IdentityCluster.Status status) {
    var compiled = Neo4jQueryCompiler.compile(orgId, question, status, Neo4jQueryCompiler.Output.BASE_IDS);
    var ids = new HashSet<String>();
    for (var row : read(new Query(compiled.cypher(), compiled.parameters()))) ids.add((String) row.get("baseId"));
    return ids;
  }

  private void write(Query query) {
    try (var session = driver.session()) {
      session.executeWriteWithoutResult(tx -> tx.run(query).consume());
    }
  }

  private List<Map<String, Object>> writeRows(Query query) {
    try (var session = driver.session()) {
      return session.executeWrite(tx -> {
        var out = new ArrayList<Map<String, Object>>();
        for (var record : tx.run(query).list()) out.add(new LinkedHashMap<>(record.asMap()));
        return out;
      });
    }
  }

  private List<Map<String, Object>> read(Query query) {
    try (var session = driver.session()) {
      return session.executeRead(tx -> {
        var out = new ArrayList<Map<String, Object>>();
        for (var record : tx.run(query).list()) out.add(new LinkedHashMap<>(record.asMap()));
        return out;
      });
    }
  }
}
