package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The contract packaged with the service, read and compared with the catalog the service runs. */
public class SignedContractTest {

  private static InputStream packaged(String name) {
    return SignedContractTest.class.getClassLoader().getResourceAsStream(SignedContract.LAYOUT + name);
  }

  @Test
  public void thePackagedContractIsIntactAndHoldsEveryControlAsTheServiceRunsIt() {
    var signed = SignedContract.current();
    var catalog = ControlCatalog.current();

    assertThat(signed.intact()).as("%s", signed.problems()).isTrue();
    assertThat(signed.systemId()).isEqualTo(catalog.systemId());
    assertThat(signed.corpusVersion()).isEqualTo("2026-09");
    assertThat(signed.signOffRole()).isEqualTo("risk-team");
    assertThat(signed.signedOffBy()).contains("OU=risk-team");
    assertThat(catalog.controls()).allSatisfy(c ->
        assertThat(signed.standingOf(catalog, c.rowId())).as(c.rowId()).isEqualTo(SignedContract.Standing.SIGNED));
    assertThat(signed.notRunning(catalog)).isEmpty();
  }

  @Test
  public void aControlRunDifferentlyFromHowItWasSignedIsChanged() throws IOException {
    String yaml;
    try (var in = getClass().getClassLoader().getResourceAsStream("governance/controls.yaml")) {
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var edited = ControlCatalog.parse(yaml.replace("Rushed human decisions are counted", "Rushed human decisions are ignored")
        .getBytes(StandardCharsets.UTF_8));

    assertThat(SignedContract.current().standingOf(edited, "custom-cl0008")).isEqualTo(SignedContract.Standing.CHANGED);
    assertThat(SignedContract.current().standingOf(edited, "custom-cl0001")).isEqualTo(SignedContract.Standing.SIGNED);
  }

  @Test
  public void aPartThatNoLongerHashesToItsDigestBreaksTheContract() {
    var tampered = SignedContract.load(name -> {
      var in = packaged(name);
      if (in == null || !name.startsWith("blobs/")) return in;
      var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return new ByteArrayInputStream(text.replace("custom-cl0003", "custom-cl0099").getBytes(StandardCharsets.UTF_8));
    });

    assertThat(tampered.intact()).isFalse();
    assertThat(tampered.problems()).anyMatch(p -> p.contains("does not hash to its digest"));
    assertThat(tampered.standingOf(ControlCatalog.current(), "custom-cl0003")).isEqualTo(SignedContract.Standing.UNSIGNED);
  }

  @Test
  public void withNoContractEveryControlIsUnsigned() {
    var none = SignedContract.load(name -> null);

    assertThat(none.present()).isFalse();
    assertThat(none.standingOf(ControlCatalog.current(), "custom-cl0001")).isEqualTo(SignedContract.Standing.UNSIGNED);
  }
}
