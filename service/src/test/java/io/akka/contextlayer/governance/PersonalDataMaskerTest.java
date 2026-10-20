package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class PersonalDataMaskerTest {

  @Test
  public void anEmailIsMaskedCharacterForCharacter() {
    var masked = PersonalDataMasker.mask("write to sam.lee+claims@example.co.uk today");

    assertThat(masked.text()).isEqualTo("write to " + "*".repeat("sam.lee+claims@example.co.uk".length()) + " today");
    assertThat(masked.found()).containsEntry("email", 1);
  }

  @Test
  public void aCardNumberThatPassesTheLuhnCheckIsMaskedAndOtherLongNumbersAreNot() {
    var masked = PersonalDataMasker.mask("card 4111 1111 1111 1111 order 1234567890123456 ref 20260915");

    assertThat(masked.text()).startsWith("card " + "*".repeat(19) + " order 1234567890123456");
    assertThat(masked.found()).containsOnlyKeys("card");
  }

  @Test
  public void anIbanAnIpAddressAndAPhoneNumberAreMasked() {
    var masked = PersonalDataMasker.mask(
        "pay GB82 WEST 1234 5698 7654 32 from 192.168.10.7, call +1 (555) 123-4567 or 555-123-4567");

    assertThat(masked.text()).doesNotContain("GB82").doesNotContain("192.168").doesNotContain("555");
    assertThat(masked.found()).containsEntry("iban", 1).containsEntry("ip", 1).containsEntry("phone", 2);
    assertThat(masked.total()).isEqualTo(4);
  }

  @Test
  public void textWithoutPersonalDataComesBackUnchanged() {
    var text = "Claim 1 has been open for 14 days and the adjuster has not replied.";
    var masked = PersonalDataMasker.mask(text);

    assertThat(masked.text()).isEqualTo(text);
    assertThat(masked.found()).isEmpty();
    assertThat(masked.summary()).isEqualTo("nothing to mask");
  }

  @Test
  public void aSummaryNamesKindsAndCountsNeverValues() {
    var masked = PersonalDataMasker.mask("a@b.co c@d.co 555-123-4567");

    assertThat(masked.summary()).contains("email 2").contains("phone 1").doesNotContain("@");
  }

  @Test
  public void nullAndEmptyAreSafe() {
    assertThat(PersonalDataMasker.mask(null).text()).isEmpty();
    assertThat(PersonalDataMasker.mask("").total()).isZero();
  }
}
