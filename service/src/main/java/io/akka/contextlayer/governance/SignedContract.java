package io.akka.contextlayer.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The Governance Control Contract packaged with the service: the control catalog as signed off by the risk team and
 * signed by the publisher, kept as an OCI layout in {@code governance/contract/} and made by the {@code contract} tool.
 *
 * <p>The service checks what arithmetic can: every part hashes to the digest the manifest declares, and each signed row
 * is compared with the row the service runs. It does not verify the signatures; that needs the trust a verifier is
 * given and the governance-contract library, which the {@code contract} tool's {@code verify} applies. A contract that
 * was edited after signing fails that check, not this one.
 *
 * @param problems why the layout could not be read or does not hash as declared; empty when it is intact
 * @param rows each signed row, as the catalog writes it, by row id
 */
public record SignedContract(
    boolean present, List<String> problems, String digest, long revision, String systemId, String corpusVersion,
    String createdAt, String signOffRole, String signedOffBy, Map<String, JsonNode> rows) {

  /** Where a running control stands against the signed contract. */
  public enum Standing {
    /** Signed, and the service runs it exactly as signed. */
    SIGNED,
    /** Signed, but the service runs a different definition. */
    CHANGED,
    /** The service runs it, but no signed contract holds it. */
    UNSIGNED
  }

  static final String LAYOUT = "governance/contract/";
  private static final String REF_NAME = "org.opencontainers.image.ref.name";
  private static final String TITLE = "org.opencontainers.image.title";
  private static final String TAG = "current";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final class Holder {
    static final SignedContract CURRENT = load(name -> SignedContract.class.getClassLoader().getResourceAsStream(LAYOUT + name));
  }

  /** The contract packaged with the service, read once. */
  public static SignedContract current() {
    return Holder.CURRENT;
  }

  public boolean intact() {
    return present && problems.isEmpty();
  }

  public Standing standingOf(ControlCatalog catalog, String rowId) {
    var signed = intact() ? rows.get(rowId) : null;
    if (signed == null) return Standing.UNSIGNED;
    return catalog.row(rowId).map(running -> running.equals(signed)).orElse(false) ? Standing.SIGNED : Standing.CHANGED;
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
      if (index == null) return absent("no signed contract is packaged with the service");
      String digest = null;
      for (var entry : index.path("manifests")) {
        if (TAG.equals(entry.path("annotations").path(REF_NAME).asText(null))) digest = entry.path("digest").asText();
      }
      if (digest == null) return absent("the contract layout has no manifest tagged " + TAG);

      var manifestBytes = blob(files, digest, problems);
      if (manifestBytes == null) return broken(problems);
      var manifest = JSON.readTree(manifestBytes);
      var annotations = manifest.path("annotations");
      blob(files, manifest.path("config").path("digest").asText(), problems);

      Map<String, JsonNode> rows = new TreeMap<>();
      String role = "";
      String signer = "";
      for (var layer : manifest.path("layers")) {
        var bytes = blob(files, layer.path("digest").asText(), problems);
        if (bytes == null) continue;
        var title = layer.path("annotations").path(TITLE).asText("");
        if (title.equals("contract/matrix.json") || title.equals("contract/enterprise.json")) {
          for (var row : JSON.readTree(bytes).path("rows")) {
            var asWritten = (ObjectNode) row.deepCopy();
            var id = asWritten.remove("id").asText();
            asWritten.remove("provenance");
            var ordered = JSON.createObjectNode().put("row_id", id);
            ordered.setAll(asWritten);
            rows.put(id, ordered);
          }
        } else if (title.startsWith("signoffs/")) {
          role = layer.path("annotations").path("io.akka.gcc.signoff-role").asText("");
          signer = signerOf(new String(bytes, StandardCharsets.UTF_8)).orElse("");
        }
      }
      if (!problems.isEmpty()) return broken(problems);
      return new SignedContract(true, List.of(), digest, annotations.path("io.akka.gcc.contract-revision").asLong(),
          annotations.path("io.akka.gcc.system-id").asText(""), annotations.path("io.akka.gcc.corpus-version").asText(""),
          annotations.path("org.opencontainers.image.created").asText(""), role, signer, Map.copyOf(rows));
    } catch (IOException | RuntimeException e) {
      problems.add("the contract layout cannot be read: " + e.getMessage());
      return broken(problems);
    }
  }

  /** The subject of the certificate a sign-off carries in its JWS header, which names who signed off. */
  static Optional<String> signerOf(String jws) {
    try {
      var header = JSON.readTree(Base64.getUrlDecoder().decode(jws.substring(0, jws.indexOf('.'))));
      var der = Base64.getDecoder().decode(header.path("x5c").path(0).asText());
      var certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
      return Optional.of(certificate.getSubjectX500Principal().getName());
    } catch (Exception e) {
      return Optional.empty();
    }
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

  private static String sha256(byte[] bytes) {
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
