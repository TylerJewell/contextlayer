package io.akka.contextlayer.governance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The set of controls this service enforces, read from {@code governance/controls.yaml}.
 *
 * <p>{@link #digest()} is a hash over the canonical form of the rows, ordered by row id. Every
 * evidence record carries it, so evidence says which version of the catalog was in force when it was
 * produced; editing a control changes it.
 */
public final class ControlCatalog {
  public static final String RESOURCE = "governance/controls.yaml";

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

  private final String systemId;
  private final List<ControlDef> controls;
  private final String digest;

  /**
   * The console's bookkeeping on a draft row: who decided it and when. Deciding a control does not change what it is,
   * so these are not part of a row's definition or of the catalog digest.
   */
  static final List<String> DRAFT_ONLY = List.of("sign_off", "sign_off_history", "updated_at");

  /** Each row as written in the catalog, less the console's bookkeeping, by row id: what a signed row is compared with. */
  private final Map<String, JsonNode> rows;

  private ControlCatalog(String systemId, List<ControlDef> controls, String digest, Map<String, JsonNode> rows) {
    this.rows = rows;
    this.systemId = systemId;
    this.controls = controls;
    this.digest = digest;
  }

  private static final class Holder {
    static final ControlCatalog CURRENT = load();
  }

  /** The catalog packaged with the service, read once. */
  public static ControlCatalog current() {
    return Holder.CURRENT;
  }

  public static ControlCatalog load() {
    try (InputStream in = ControlCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the classpath");
      return parse(in.readAllBytes());
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
  }

  /** Parses and validates a catalog; every problem found is reported in one exception. */
  public static ControlCatalog parse(byte[] yaml) {
    try {
      JsonNode root = YAML.readTree(yaml);
      var rows = root.path("rows");
      if (!rows.isArray()) throw new IllegalArgumentException("controls.yaml must hold a rows list");
      var controls = new ArrayList<ControlDef>();
      var problems = new ArrayList<String>();
      var seen = new HashSet<String>();
      var canonicalRows = new TreeMap<String, JsonNode>();
      for (JsonNode row : rows) {
        ControlDef def = YAML.treeToValue(row, ControlDef.class);
        problems.addAll(validate(def, seen));
        controls.add(def);
        var definition = row.deepCopy();
        if (definition instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.remove(DRAFT_ONLY);
        canonicalRows.put(row.path("row_id").asText(), definition);
      }
      if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
      controls.sort(Comparator.comparing(ControlDef::rowId));
      var sorted = YAML.createArrayNode();
      canonicalRows.values().forEach(sorted::add);
      var canonical = YAML.createObjectNode();
      canonical.set("rows", sorted);
      var systemId = root.path("matrix_id").asText(root.path("system_id").asText(""));
      return new ControlCatalog(systemId, List.copyOf(controls), CanonicalJson.sha256(canonical),
          Map.copyOf(canonicalRows));
    } catch (IOException e) {
      throw new IllegalArgumentException("controls.yaml is not valid: " + e.getMessage(), e);
    }
  }

  private static List<String> validate(ControlDef def, Set<String> seen) {
    var problems = new ArrayList<String>();
    var id = def.rowId() == null ? "(no row_id)" : def.rowId();
    if (def.rowId() == null || def.rowId().isBlank()) problems.add("a control has no row_id");
    else if (!seen.add(def.rowId())) problems.add(id + " appears twice");
    if (def.name() == null || def.name().isBlank()) problems.add(id + " has no name");
    if (def.mechanism() == null) problems.add(id + " has no mechanism");
    if (def.proofEvent() == null || def.proofEvent().isBlank()) problems.add(id + " names no proof event");
    if (def.mechanism() == Mechanism.HUMAN) {
      var h = def.human();
      if (h == null) {
        problems.add(id + " is a human control without human_config");
      } else {
        if (h.roles().isEmpty()) problems.add(id + " names no deciding role");
        if (h.allowedActions().isEmpty()) problems.add(id + " allows no action");
        if (h.required() < 1) problems.add(id + " needs at least one reviewer");
        if (h.slaTarget() == null) {
          problems.add(id + " has no sla_target");
        } else {
          try {
            java.time.Duration.parse(h.slaTarget());
          } catch (RuntimeException e) {
            problems.add(id + " has an sla_target that is not an ISO-8601 duration");
          }
        }
      }
    } else if (def.human() != null) {
      problems.add(id + " has human_config but is not a human control");
    }
    return problems;
  }

  public String systemId() {
    return systemId;
  }

  public List<ControlDef> controls() {
    return controls;
  }

  public Optional<ControlDef> find(String rowId) {
    return controls.stream().filter(c -> c.rowId().equals(rowId)).findFirst();
  }

  /** The control, or an exception: code that names a control the catalog lacks is a defect. */
  public ControlDef require(String rowId) {
    return find(rowId).orElseThrow(() -> new IllegalStateException("control " + rowId + " is not in the catalog"));
  }

  public String digest() {
    return digest;
  }

  /** The row as written in controls.yaml, or empty when the catalog has no such control. */
  public Optional<JsonNode> row(String rowId) {
    return Optional.ofNullable(rows.get(rowId));
  }
}
