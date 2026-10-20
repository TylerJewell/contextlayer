package io.akka.contextlayer.domain;

import java.util.List;

/**
 * A connector pack: what one source system exposes and how the platform may call it. The
 * description of the source's API stays in its own standard document (referenced by
 * {@code source.ref}); the pack adds only what that document lacks (REQUIREMENTS CON-01, CON-03).
 * Credentials are never part of a pack, only a reference to where they are kept (CON-02).
 */
public record PackDefinition(
    String name,
    String version,
    Source source,
    String credentialRef,
    List<EntityMapping> entities,
    List<ActionDef> actions) {

  /** Kind is one of openapi, mcp, database, file, stream. A stream pushes records to the service. */
  public record Source(String kind, String ref) {}

  public record EntityMapping(
      String type,
      String operation,
      String recordsPath,
      String idField,
      List<String> identityAttributes,
      List<String> filters,
      Freshness freshness,
      Paging paging,
      Rate rate,
      List<Relation> relations) {

    public EntityMapping {
      relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public EntityMapping(
        String type, String operation, String recordsPath, String idField, List<String> identityAttributes,
        List<String> filters, Freshness freshness, Paging paging, Rate rate) {
      this(type, operation, recordsPath, idField, identityAttributes, filters, freshness, paging, rate, List.of());
    }}

  /**
   * A relationship a record of this type has to records of another type: the field holds the id, or a list of
   * ids, of the records it points at. A question can follow it by name, in either direction.
   */
  public record Relation(String name, String field, String targetType) {}

  /**
   * Policy is one of MAINTAINED, CACHED, STREAMED, ON_DEMAND. The others need ttlMinutes above zero, and it
   * means: for CACHED, how old a read may be before a question reads again; for MAINTAINED, how often the
   * service reads in the background; for STREAMED, the longest silence (no event or heartbeat) before the
   * data is reported stale.
   */
  public record Freshness(String policy, int ttlMinutes) {}

  /**
   * Style is one of none, page, cursor. A cursor source needs cursorParam and nextCursorPath (a JSON
   * pointer to the next cursor in each response); a page source needs pageParam. pageSizeParam is
   * optional.
   */
  public record Paging(
      String style,
      int maxPageSize,
      String pageSizeParam,
      String cursorParam,
      String nextCursorPath,
      String pageParam) {}

  /** The most calls per minute the platform may make to the source for this entity type. */
  public record Rate(int callsPerMinute) {}

  public record ActionDef(String name, String operation, RiskClass risk) {}
}
