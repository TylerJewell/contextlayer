package io.akka.contextlayer.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a source record into properties a graph store can hold: names reduced to letters, digits
 * and underscores (so a name can never carry a query fragment), nested objects flattened with
 * underscores, lists of simple values kept, anything else stored as JSON text.
 */
public final class RecordFlattener {

  /** Properties the store sets itself; a source field with one of these names is renamed. */
  private static final Set<String> RESERVED = Set.of("orgId", "type", "source", "id", "refreshedAt", "streamVersion");

  private static final ObjectMapper JSON = new ObjectMapper();

  private RecordFlattener() {}

  public static Map<String, Object> flatten(Map<String, Object> record) {
    var out = new LinkedHashMap<String, Object>();
    flattenInto("", record, out);
    return out;
  }

  /** The record's identifier as text, or null when the id field is missing or empty. */
  public static String idOf(Map<String, Object> record, String idField) {
    var value = record.get(idField);
    if (value == null) return null;
    var text = String.valueOf(value).strip();
    return text.isEmpty() ? null : text;
  }

  static String safeName(String name) {
    var cleaned = name.replaceAll("[^A-Za-z0-9_]", "_");
    if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) cleaned = "f_" + cleaned;
    return cleaned.length() > 63 ? cleaned.substring(0, 63) : cleaned;
  }

  private static void flattenInto(String prefix, Map<String, Object> map, Map<String, Object> out) {
    for (var entry : map.entrySet()) {
      var name = prefix.isEmpty() ? safeName(entry.getKey()) : prefix + "_" + safeName(entry.getKey());
      var value = entry.getValue();
      if (value == null) continue;
      if (value instanceof Map<?, ?> nested) {
        @SuppressWarnings("unchecked")
        var typed = (Map<String, Object>) nested;
        flattenInto(name, typed, out);
      } else if (value instanceof List<?> list) {
        out.put(rename(name, prefix.isEmpty()), listValue(list));
      } else if (value instanceof String || value instanceof Boolean || value instanceof Integer
          || value instanceof Long || value instanceof Double) {
        out.put(rename(name, prefix.isEmpty()), value);
      } else if (value instanceof Number number) {
        out.put(rename(name, prefix.isEmpty()), number.doubleValue());
      } else {
        out.put(rename(name, prefix.isEmpty()), String.valueOf(value));
      }
    }
  }

  private static String rename(String name, boolean topLevel) {
    return topLevel ? propertyName(name) : name;
  }

  /** The stored property name for a source field name that is already safe. */
  public static String propertyName(String field) {
    return RESERVED.contains(field) ? "src_" + field : field;
  }

  private static Object listValue(List<?> list) {
    if (list.isEmpty()) return List.of();
    boolean allStrings = list.stream().allMatch(v -> v instanceof String);
    boolean allBooleans = list.stream().allMatch(v -> v instanceof Boolean);
    boolean allIntegers = list.stream().allMatch(v -> v instanceof Integer || v instanceof Long);
    boolean allNumbers = list.stream().allMatch(v -> v instanceof Number);
    if (allStrings || allBooleans) return new ArrayList<>(list);
    if (allIntegers) return list.stream().map(v -> ((Number) v).longValue()).toList();
    if (allNumbers) return list.stream().map(v -> ((Number) v).doubleValue()).toList();
    try {
      return JSON.writeValueAsString(list);
    } catch (JsonProcessingException e) {
      return String.valueOf(list);
    }
  }
}
