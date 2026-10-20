package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The bundle in force, read as the Akka local console writes it: bundles built here follow its local profile of the
 * Governance Control Contract, signed with an Ed25519 key the way the console signs.
 */
public class SignedContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  /** A layout of one bundle, v1, holding every control of the catalog as an accepted officer row. */
  static final class Bundle {
    final Map<String, byte[]> files = new HashMap<>();
    final KeyPair key;
    String manifestDigest;

    Bundle(ControlCatalog catalog, UnaryOperator<ObjectNode> editRow, boolean forgeEnterpriseSignOff) throws Exception {
      var generator = KeyPairGenerator.getInstance("Ed25519");
      key = generator.generateKeyPair();
      var rows = JSON.createArrayNode();
      for (var control : catalog.controls()) rows.add(editRow.apply(contractRow(catalog.row(control.rowId()).orElseThrow())));
      var matrix = bytes(JSON.createObjectNode().set("rows", JSON.createArrayNode()));
      var enterprise = bytes(JSON.createObjectNode().set("rows", rows));
      var derivation = bytes(JSON.createObjectNode());
      var matrixJws = signOff("matrix", "risk-team", matrix, matrix);
      var enterpriseJws = signOff("enterprise", "control-owner", enterprise, forgeEnterpriseSignOff ? matrix : enterprise);
      var config = bytes(JSON.createObjectNode().put("gcc_version", 1).put("system_id", "contextlayer").put("gov_version", 1)
          .putNull("corpus_version").put("created_at", "2026-10-20T12:00:00Z"));
      var manifest = JSON.createObjectNode().put("schemaVersion", 2).put("mediaType", "application/vnd.oci.image.manifest.v1+json")
          .put("artifactType", "application/vnd.akka.gcc.contract.v1+json");
      manifest.set("config", descriptor("application/vnd.akka.gcc.manifest.v1+json", config, null));
      var layers = manifest.putArray("layers");
      layers.add(descriptor("application/vnd.akka.gcc.matrix.v1+json", matrix, "contract/matrix.json"));
      layers.add(descriptor("application/vnd.akka.gcc.enterprise.v1+json", enterprise, "contract/enterprise.json"));
      layers.add(descriptor("application/vnd.akka.gcc.derivation.v1+json", derivation, "contract/derivation.json"));
      layers.add(descriptor("application/jose+json", matrixJws, "signoffs/matrix.jws"));
      layers.add(descriptor("application/jose+json", enterpriseJws, "signoffs/enterprise.jws"));
      var manifestBytes = bytes(manifest);
      manifestDigest = put(manifestBytes);
      var index = JSON.createObjectNode().put("schemaVersion", 2);
      var entry = index.putArray("manifests").addObject().put("mediaType", "application/vnd.oci.image.manifest.v1+json")
          .put("digest", manifestDigest).put("size", manifestBytes.length);
      entry.putObject("annotations").put("org.opencontainers.image.ref.name", "v1").put("io.akka.gcc.gov-version", "1")
          .put("io.akka.gcc.system-id", "contextlayer");
      files.put("index.json", bytes(index));
    }

    /** A draft row as the console signs it: the contract fields, then everything else as its definition. */
    private static ObjectNode contractRow(JsonNode draft) {
      var row = JSON.createObjectNode();
      var definition = JSON.createObjectNode();
      draft.fields().forEachRemaining(e -> {
        if (List.of("row_id", "name", "mechanism", "lifecycle", "provenance", "corpus_ref", "rationale", "summary", "applies_to")
            .contains(e.getKey())) {
          row.set(e.getKey(), e.getValue());
        } else {
          definition.set(e.getKey(), e.getValue());
        }
      });
      for (var field : List.of("lifecycle", "corpus_ref", "rationale", "summary", "applies_to")) if (!row.has(field)) row.putNull(field);
      row.putObject("decision").put("kind", "accepted").put("by", "tylerjewell@gmail.com").put("at", "2026-10-20T12:00:00Z").putNull("note");
      row.set("definition", definition);
      return row;
    }

    private byte[] signOff(String section, String role, byte[] digested, byte[] signed) throws Exception {
      var encoded = key.getPublic().getEncoded();
      var x = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
      var header = JSON.createObjectNode().put("alg", "EdDSA").put("typ", "application/vnd.akka.gcc.signoff+jws")
          .put("io.akka.gcc.section", section).put("io.akka.gcc.section-digest", "sha256:" + SignedContract.sha256(digested));
      header.putObject("jwk").put("kty", "OKP").put("crv", "Ed25519").put("x", B64.encodeToString(x));
      header.putObject("io.akka.gcc.signer").put("subject", "tylerjewell@gmail.com").put("role", role).put("trust", "local");
      var protectedPart = B64.encodeToString(bytes(header));
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(key.getPrivate());
      signer.update((protectedPart + "." + B64.encodeToString(signed)).getBytes(StandardCharsets.US_ASCII));
      return bytes(JSON.createObjectNode().put("protected", protectedPart).put("signature", B64.encodeToString(signer.sign())));
    }

    private ObjectNode descriptor(String mediaType, byte[] content, String title) {
      var node = JSON.createObjectNode().put("mediaType", mediaType).put("digest", put(content)).put("size", content.length);
      if (title != null) node.putObject("annotations").put("org.opencontainers.image.title", title);
      return node;
    }

    private String put(byte[] content) {
      var hex = SignedContract.sha256(content);
      files.put("blobs/sha256/" + hex, content);
      return "sha256:" + hex;
    }

    SignedContract read() {
      return SignedContract.load(name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name)) : null);
    }

    private static byte[] bytes(JsonNode node) {
      try {
        return JSON.writeValueAsBytes(node);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static final ControlCatalog CATALOG = ControlCatalog.current();

  @Test
  public void aBundleSignedFromTheCatalogHoldsEveryControlAsTheServiceRunsIt() throws Exception {
    var signed = new Bundle(CATALOG, UnaryOperator.identity(), false).read();

    assertThat(signed.intact()).as("%s", signed.problems()).isTrue();
    assertThat(signed.revision()).isEqualTo(1);
    assertThat(signed.systemId()).isEqualTo(CATALOG.systemId()).isEqualTo("contextlayer");
    assertThat(signed.signedBy()).isEqualTo("tylerjewell@gmail.com");
    assertThat(signed.signOffRole()).isEqualTo("risk-team, control-owner");
    assertThat(CATALOG.controls()).allSatisfy(c ->
        assertThat(signed.standingOf(CATALOG, c.rowId())).as(c.rowId()).isEqualTo(SignedContract.Standing.SIGNED));
    assertThat(signed.notRunning(CATALOG)).isEmpty();
  }

  @Test
  public void aControlRunDifferentlyFromHowItWasSignedIsChanged() throws Exception {
    var signed = new Bundle(CATALOG, row -> "custom-cl0008".equals(row.path("row_id").asText())
        ? row.put("name", "Rushed human decisions are ignored") : row, false).read();

    assertThat(signed.standingOf(CATALOG, "custom-cl0008")).isEqualTo(SignedContract.Standing.CHANGED);
    assertThat(signed.standingOf(CATALOG, "custom-cl0001")).isEqualTo(SignedContract.Standing.SIGNED);
  }

  @Test
  public void aPartThatNoLongerHashesToItsDigestBreaksTheBundle() throws Exception {
    var bundle = new Bundle(CATALOG, UnaryOperator.identity(), false);
    bundle.files.replaceAll((name, content) -> name.startsWith("blobs/")
        ? new String(content, StandardCharsets.UTF_8).replace("custom-cl0003", "custom-cl0099").getBytes(StandardCharsets.UTF_8)
        : content);

    var signed = bundle.read();

    assertThat(signed.intact()).isFalse();
    assertThat(signed.problems()).anyMatch(p -> p.contains("does not hash to its digest"));
    assertThat(signed.standingOf(CATALOG, "custom-cl0003")).isEqualTo(SignedContract.Standing.UNSIGNED);
  }

  @Test
  public void aSignOffOverOtherContentDoesNotVerify() throws Exception {
    var signed = new Bundle(CATALOG, UnaryOperator.identity(), true).read();

    assertThat(signed.intact()).isFalse();
    assertThat(signed.problems()).contains("the enterprise sign-off does not verify");
  }

  @Test
  public void withNoBundleEveryControlIsUnsigned() {
    var none = SignedContract.load(name -> null);

    assertThat(none.present()).isFalse();
    assertThat(none.standingOf(CATALOG, "custom-cl0001")).isEqualTo(SignedContract.Standing.UNSIGNED);
  }

  @Test
  public void aPackagedBundleHoldsEveryControlTheServiceRunsExactlyAsSigned() {
    var packaged = SignedContract.current();
    if (!packaged.present()) return;  // nothing signed yet: every control reports UNSIGNED, which the report shows

    assertThat(packaged.intact()).as("%s", packaged.problems()).isTrue();
    assertThat(CATALOG.controls()).as("re-sign in the console after changing governance/controls.yaml").allSatisfy(c ->
        assertThat(packaged.standingOf(CATALOG, c.rowId())).as(c.rowId()).isEqualTo(SignedContract.Standing.SIGNED));
  }
}
