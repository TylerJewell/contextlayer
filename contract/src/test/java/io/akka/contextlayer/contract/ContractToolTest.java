package io.akka.contextlayer.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.nexus.governance.contract.verify.VerificationStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Signs the service's real catalog into a layout and verifies it, as an auditor would, with no registry. */
class ContractToolTest {

  private static final Path CONTROLS = Path.of("../service/src/main/resources/governance/controls.yaml");

  @TempDir Path dir;
  Path keys, trust, layout;

  @BeforeEach
  void keys() throws IOException {
    keys = dir.resolve("keys");
    trust = dir.resolve("trust");
    layout = dir.resolve("layout");
    Keys.generate(keys, trust, "test risk team");
  }

  @Test
  void theCatalogSignsAndVerifiesInEveryStage() throws IOException {
    var signed = ContractTool.sign(CONTROLS, layout, keys, trust, "abc123", Optional.empty());
    assertThat(signed.revision()).isEqualTo(1);
    assertThat(signed.systemId()).isEqualTo("contextlayer-akka");

    var result = ContractTool.verify(layout, trust);

    assertThat(result.verified()).as("%s", result.describe()).isTrue();
    assertThat(result.stage(VerificationStage.SIGN_OFFS).passed()).isTrue();
    assertThat(result.stage(VerificationStage.TIMESTAMP).passed()).as("the local authority dated it").isTrue();
    assertThat(Files.exists(layout.resolve("index.json"))).isTrue();
  }

  @Test
  void signingAgainMakesTheNextRevisionAndMovesCurrent() throws IOException {
    ContractTool.sign(CONTROLS, layout, keys, trust, "abc123", Optional.empty());
    var second = ContractTool.sign(CONTROLS, layout, keys, trust, "def456", Optional.empty());

    assertThat(second.revision()).isEqualTo(2);
    assertThat(new LayoutRegistry(layout).resolveTag("contextlayer", "current")).contains(second.digest());
    assertThat(ContractTool.verify(layout, trust).verified()).isTrue();
  }

  @Test
  void anEditedControlInTheLayoutFailsIntegrity() throws IOException {
    ContractTool.sign(CONTROLS, layout, keys, trust, "abc123", Optional.empty());
    try (var blobs = Files.list(layout.resolve("blobs/sha256"))) {
      for (var blob : blobs.toList()) {
        var text = Files.readString(blob);
        if (text.contains("custom-cl0003") && text.contains("\"rows\"")) {
          Files.writeString(blob, text.replace("custom-cl0003", "custom-cl0099"));
        }
      }
    }

    var result = ContractTool.verify(layout, trust);

    assertThat(result.stage(VerificationStage.INTEGRITY).passed()).isFalse();
    assertThat(result.verified()).isFalse();
  }

  @Test
  void aContractSignedWithAnotherRiskTeamKeyIsNotTrusted() throws IOException {
    ContractTool.sign(CONTROLS, layout, keys, trust, "abc123", Optional.empty());
    var otherTrust = dir.resolve("other-trust");
    Keys.generate(dir.resolve("other-keys"), otherTrust, "someone else");

    assertThat(ContractTool.verify(layout, otherTrust).verified()).isFalse();
  }
}
