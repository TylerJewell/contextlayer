package io.akka.contextlayer.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A document that backs a control's evidence without being a runtime record: an override procedure, a policy, a test
 * report, a mapping of data flows. The service keeps what is needed to say whether it is there and current, where it is,
 * and which content it was: not the content itself.
 */
public final class TrustDocument {
  private TrustDocument() {}

  public static final Set<String> KINDS = Set.of("procedure", "policy", "test-report", "mapping", "other");
  public static final int MAX_CONTENT_BYTES = 1_000_000;

  /**
   * One version of a document.
   *
   * @param asOf the date this version was last current, as yyyy-mm-dd
   * @param digest the SHA-256 of the content the person gave when registering it, or empty if they gave none. Anyone holding the
   *     document can compute the same digest to show it is the one that was registered.
   */
  public record Version(int version, String name, String kind, String ref, String asOf, String digest, String by, long at) {}

  /** A document and every version of it, oldest first. */
  public record Document(String name, List<Version> versions) {
    public Document {
      versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public boolean exists() {
      return !versions.isEmpty();
    }

    /** The version that was last current most recently; a later registration of an older date does not displace it. */
    public Version newest() {
      return versions.stream().max(java.util.Comparator.comparing(Version::asOf).thenComparing(Version::version)).orElse(null);
    }
  }

  public static String digestOf(String content) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
