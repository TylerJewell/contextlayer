package io.akka.contextlayer.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The system's bundle in force: its controls as decided and signed in the Akka local console, kept as an OCI layout in
 * {@code governance/signed/} (the console's local profile of the Governance Control Contract, ADR-015). The newest
 * bundle is in force.
 *
 * <p>The service checks the bundle in force as the console does: every part hashes to the digest its manifest
 * declares, it has the five parts in their order, and each sign-off is a valid Ed25519 signature over its section. The
 * key is the one the sign-off carries; nothing here says whose it is ({@code trust: "local"}), which the console's
 * profile leaves to product work. It then compares each signed control with the one the service runs.
 *
 * @param problems why there is no usable bundle in force, or what is wrong with it; empty when it checks out
 * @param signedBy who signed it off, as each sign-off names them
 * @param rows each signed control by row id, as the controls draft writes it: what the service's row is compared with
 */
public record SignedContract(
    boolean present, List<String> problems, String digest, long revision, String systemId, String corpusVersion,
    String createdAt, String signOffRole, String signedBy, Map<String, JsonNode> rows) {

  /** Where a running control stands against the bundle in force. */
  public enum Standing {
    /** In the bundle in force, and the service runs it exactly as signed. */
    SIGNED,
    /** In the bundle in force, but the service runs a different definition. */
    CHANGED,
    /** The service runs it, but the bundle in force does not hold it. */
    UNSIGNED
  }

  static final String LAYOUT = "governance/signed/";
  private static final String GOV_VERSION = "io.akka.gcc.gov-version";
  private static final String TITLE = "org.opencontainers.image.title";
  private static final String CONTRACT = "application/vnd.akka.gcc.contract.v1+json";
  private static final List<String> PARTS = List.of(
      "contract/matrix.json", "contract/enterprise.json", "contract/derivation.json", "signoffs/matrix.jws", "signoffs/enterprise.jws");
  /** The fields a signed row carries beside its definition; the rest of a draft row is its definition. */
  private static final List<String> ROW_FIELDS = List.of(
      "row_id", "name", "mechanism", "lifecycle", "provenance", "corpus_ref", "rationale", "summary", "applies_to");
  /** An Ed25519 public key as X.509 SubjectPublicKeyInfo is this prefix and then the 32 key bytes. */
  private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final class Holder {
    static final SignedContract CURRENT = load(name -> SignedContract.class.getClassLoader().getResourceAsStream(LAYOUT + name));
  }

  /** The bundle in force packaged with the service, read once. */
  public static SignedContract current() {
    return Holder.CURRENT;
  }

  public boolean intact() {
    return present && problems.isEmpty();
  }

  public Standing standingOf(ControlCatalog catalog, String rowId) {
    var signed = intact() ? rows.get(rowId) : null;
    if (signed == null) return Standing.UNSIGNED;
    return catalog.row(rowId).map(running -> withoutNulls(running).equals(signed)).orElse(false) ? Standing.SIGNED : Standing.CHANGED;
  }

  /** Signed controls the service does not run at all. */
  public List<String> notRunning(ControlCatalog catalog) {
    return rows.keySet().stream().filter(id -> catalog.find(id).isEmpty()).sorted().toList();
  }

  /** Opens a file of the layout by its path in it, or returns null when there is none. */
  public interface Files {
    InputStream open(String name) throws IOException;
  }

  public static SignedContract load(Files files) {
    var problems = new ArrayList<String>();
    try {
      var index = read(files, "index.json");
      if (index == null) return absent("no bundle has been signed for this system yet");
      JsonNode inForce = null;
      for (var entry : index.path("manifests")) {
        var version = entry.path("annotations").path(GOV_VERSION).asLong(0);
        if (version > 0 && (inForce == null || version > inForce.path("annotations").path(GOV_VERSION).asLong())) inForce = entry;
      }
      if (inForce == null) return absent("governance/signed/ holds no bundle");
      long version = inForce.path("annotations").path(GOV_VERSION).asLong();
      var digest = inForce.path("digest").asText();

      var manifestBytes = blob(files, digest, problems);
      if (manifestBytes == null) return broken(problems);
      var manifest = JSON.readTree(manifestBytes);
      if (!CONTRACT.equals(manifest.path("artifactType").asText())) problems.add("bundle v" + version + " is not a controls contract");
      var configBytes = blob(files, manifest.path("config").path("digest").asText(), problems);
      var config = configBytes == null ? JSON.createObjectNode() : JSON.readTree(configBytes);
      if (config.path("gov_version").asLong(-1) != version) problems.add("bundle v" + version + " is numbered differently inside");

      var layers = manifest.path("layers");
      var parts = new TreeMap<String, byte[]>();
      var digests = new TreeMap<String, String>();
      for (int i = 0; i < PARTS.size(); i++) {
        var layer = layers.path(i);
        var title = layer.path("annotations").path(TITLE).asText("");
        if (!PARTS.get(i).equals(title)) {
          problems.add("part " + (i + 1) + " of bundle v" + version + " should be " + PARTS.get(i));
          continue;
        }
        var bytes = blob(files, layer.path("digest").asText(), problems);
        if (bytes != null) {
          parts.put(title, bytes);
          digests.put(title, layer.path("digest").asText());
        }
      }
      if (layers.size() != PARTS.size()) problems.add("bundle v" + version + " has " + layers.size() + " parts, not " + PARTS.size());
      if (!problems.isEmpty()) return broken(problems);

      var roles = new LinkedHashSet<String>();
      var signers = new LinkedHashSet<String>();
      for (var section : List.of("matrix", "enterprise")) {
        var jws = JSON.readTree(parts.get("signoffs/" + section + ".jws"));
        var header = verify(jws, section, parts.get("contract/" + section + ".json"), digests.get("contract/" + section + ".json"), problems);
        if (header != null) {
          roles.add(header.path("io.akka.gcc.signer").path("role").asText(""));
          signers.add(header.path("io.akka.gcc.signer").path("subject").asText(""));
        }
      }
      if (!problems.isEmpty()) return broken(problems);

      Map<String, JsonNode> rows = new TreeMap<>();
      for (var section : List.of("contract/matrix.json", "contract/enterprise.json")) {
        for (var row : JSON.readTree(parts.get(section)).path("rows")) rows.put(row.path("row_id").asText(), asWritten(row));
      }
      return new SignedContract(true, List.of(), digest, version, config.path("system_id").asText(""),
          config.path("corpus_version").asText(""), config.path("created_at").asText(""),
          String.join(", ", roles), String.join(", ", signers), Map.copyOf(rows));
    } catch (IOException | RuntimeException e) {
      problems.add("governance/signed/ cannot be read: " + e.getMessage());
      return broken(problems);
    }
  }

  /** The sign-off's protected header when its signature verifies over the section, or null with the problem recorded. */
  private static JsonNode verify(JsonNode jws, String section, byte[] payload, String payloadDigest, List<String> problems) {
    try {
      var protectedPart = jws.path("protected").asText();
      var header = JSON.readTree(Base64.getUrlDecoder().decode(protectedPart));
      var jwk = header.path("jwk");
      if (!"EdDSA".equals(header.path("alg").asText()) || !"OKP".equals(jwk.path("kty").asText())
          || !"Ed25519".equals(jwk.path("crv").asText())) {
        problems.add("the " + section + " sign-off is not an Ed25519 signature");
        return null;
      }
      if (!section.equals(header.path("io.akka.gcc.section").asText())
          || !payloadDigest.equals(header.path("io.akka.gcc.section-digest").asText())) {
        problems.add("the " + section + " sign-off is not over this bundle's " + section);
        return null;
      }
      var raw = Base64.getUrlDecoder().decode(jwk.path("x").asText());
      var spki = new byte[ED25519_SPKI.length + raw.length];
      System.arraycopy(ED25519_SPKI, 0, spki, 0, ED25519_SPKI.length);
      System.arraycopy(raw, 0, spki, ED25519_SPKI.length, raw.length);
      var verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
      verifier.update((protectedPart + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
          .getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(Base64.getUrlDecoder().decode(jws.path("signature").asText()))) {
        problems.add("the " + section + " sign-off does not verify");
        return null;
      }
      return header;
    } catch (Exception e) {
      problems.add("the " + section + " sign-off cannot be read: " + e.getMessage());
      return null;
    }
  }

  /** A signed row as the controls draft writes it: its fields, then its definition's fields beside them. */
  static JsonNode asWritten(JsonNode signed) {
    var out = JSON.createObjectNode();
    for (var field : ROW_FIELDS) if (signed.has(field)) out.set(field, signed.get(field));
    if (signed.path("definition").isObject()) out.setAll((ObjectNode) signed.get("definition").deepCopy());
    return withoutNulls(out);
  }

  /** A signed row names every field, null when the draft lacks it; an absent field and a null one are the same. */
  private static JsonNode withoutNulls(JsonNode row) {
    var out = JSON.createObjectNode();
    row.fields().forEachRemaining(e -> {
      if (!e.getValue().isNull()) out.set(e.getKey(), e.getValue());
    });
    return out;
  }

  private static JsonNode read(Files files, String name) throws IOException {
    try (var in = files.open(name)) {
      return in == null ? null : JSON.readTree(in);
    }
  }

  /** The blob, or null with the problem recorded when it is missing or does not hash to its name. */
  private static byte[] blob(Files files, String digest, List<String> problems) throws IOException {
    if (!digest.matches("sha256:[0-9a-f]{64}")) {
      problems.add("a part is named by something other than a sha256 digest: " + digest);
      return null;
    }
    try (var in = files.open("blobs/sha256/" + digest.substring(7))) {
      if (in == null) {
        problems.add("part " + digest + " is missing");
        return null;
      }
      var bytes = in.readAllBytes();
      if (!digest.equals("sha256:" + sha256(bytes))) {
        problems.add("part " + digest + " does not hash to its digest");
        return null;
      }
      return bytes;
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SignedContract absent(String why) {
    return new SignedContract(false, List.of(why), "", 0, "", "", "", "", "", Map.of());
  }

  private static SignedContract broken(List<String> problems) {
    return new SignedContract(true, problems.stream().distinct().toList(), "", 0, "", "", "", "", "", Map.of());
  }
}
