package io.akka.contextlayer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Builds entity ids that always begin with the organization id, so no two organizations can
 * ever address the same entity (REQUIREMENTS ISO-01).
 *
 * <p>':' separates the parts. The runtime reserves '|' in entity ids, and control characters make
 * an id unusable, so none of these may appear in a part. A part chosen by a caller is refused if it
 * has any; a part derived from data (such as an identity key taken from an email address) is passed
 * through {@link #safe} first, so odd data can never break the entity it names.
 */
public final class ScopedId {
  private static final int MAX_PART_LENGTH = 200;

  private ScopedId() {}

  public static String of(String orgId, String... parts) {
    var id = new StringBuilder(require("organization id", orgId));
    for (var part : parts) {
      id.append(':').append(require("id part", part));
    }
    return id.toString();
  }

  /** The organization an id was scoped to. */
  public static String orgOf(String scopedId) {
    int separator = scopedId.indexOf(':');
    return separator < 0 ? scopedId : scopedId.substring(0, separator);
  }

  /** Whether an id a caller sent can be used as an entity id of this organization at all. */
  public static boolean belongsTo(String entityId, String orgId) {
    return entityId != null && entityId.startsWith(orgId + ":") && entityId.length() <= 4 * MAX_PART_LENGTH
        && entityId.chars().noneMatch(c -> c == '|' || Character.isISOControl(c));
  }

  /**
   * Makes any text usable as one part of an id: the characters that are not allowed are written as
   * %XX, and text too long to fit is cut and given a short hash of the whole so that two long
   * texts never share an id. Text that needs no change comes back as it was.
   */
  public static String safe(String text) {
    var out = new StringBuilder();
    for (char c : text.toCharArray()) {
      if (c == ':' || c == '|' || c == '%' || Character.isISOControl(c)) {
        // Every escaped character is below U+0100, so two hex digits always suffice.
        out.append('%').append(String.format("%02X", (int) c));
      } else {
        out.append(c);
      }
    }
    if (out.length() <= MAX_PART_LENGTH) return out.toString();
    return out.substring(0, MAX_PART_LENGTH - 17) + "~" + hash(text);
  }

  private static String require(String what, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    if (value.indexOf(':') >= 0 || value.indexOf('|') >= 0) {
      throw new IllegalArgumentException(what + " must not contain ':' or '|'");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(what + " must not contain control characters");
    }
    if (value.length() > MAX_PART_LENGTH) {
      throw new IllegalArgumentException(what + " is too long");
    }
    return value;
  }

  private static String hash(String text) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
