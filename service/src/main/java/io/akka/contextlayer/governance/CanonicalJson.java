package io.akka.contextlayer.governance;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;

/**
 * Canonical JSON in the manner of RFC 8785 (JCS): object keys sorted by UTF-16 code unit, no
 * insignificant whitespace, strings escaped minimally. Numbers are written as Jackson prints them,
 * which matches JCS for the integers and short decimals the catalog holds. Equal content gives equal
 * bytes, so a digest over it identifies the content.
 */
public final class CanonicalJson {
  private CanonicalJson() {}

  public static String of(JsonNode node) {
    var out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  public static String sha256(JsonNode node) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(of(node).getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      var names = new ArrayList<String>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      out.append('{');
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) out.append(',');
        string(names.get(i), out);
        out.append(':');
        write(node.get(names.get(i)), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(',');
        write(node.get(i), out);
      }
      out.append(']');
    } else if (node.isTextual()) {
      string(node.textValue(), out);
    } else {
      out.append(node.toString());
    }
  }

  private static void string(String text, StringBuilder out) {
    out.append('"');
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }
}
