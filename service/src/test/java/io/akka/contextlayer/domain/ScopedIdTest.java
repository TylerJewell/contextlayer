package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScopedIdTest {

  @Test
  void partsAreJoinedWithTheOrganizationFirst() {
    assertThat(ScopedId.of("org", "a", "b")).isEqualTo("org:a:b");
    assertThat(ScopedId.orgOf("org:a:b")).isEqualTo("org");
  }

  @Test
  void aCallerChosenPartWithAnythingTheRuntimeCannotUseIsRefused() {
    for (var bad : new String[] {"a:b", "a|b", "a\u0000b", "a\nb", "a\u007fb", "", "   ", "x".repeat(201)}) {
      assertThatThrownBy(() -> ScopedId.of("org", bad)).as("part '%s'", bad.replace("\n", "\\n")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ScopedId.of(bad, "ok")).as("organization '%s'", bad.replace("\n", "\\n")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void ordinaryTextPunctuationAndUnicodeAreAccepted() {
    assertThat(ScopedId.of("org-1", "sam ortiz~1990-05-05", "é日本", "pat+tag@example.com")).startsWith("org-1:");
  }

  @Test
  void safeLeavesOrdinaryTextAloneAndEscapesTheRest() {
    assertThat(ScopedId.safe("pat@example.com")).isEqualTo("pat@example.com");
    assertThat(ScopedId.safe("a:b|c%d")).isEqualTo("a%3Ab%7Cc%25d");
    assertThat(ScopedId.safe("a\nb")).isEqualTo("a%0Ab");
  }

  @Test
  void safeOutputIsAlwaysAcceptedAsAPart() {
    for (var text : new String[] {"a:b", "x|y", "\u0000\u0001", "%", "e@x.com:|%", "é:日本", "x".repeat(5000)}) {
      var safe = ScopedId.safe(text);
      assertThat(ScopedId.of("org", safe)).startsWith("org:");
    }
  }

  @Test
  void differentTextsNeverShareASafeForm() {
    assertThat(ScopedId.safe("a:b")).isNotEqualTo(ScopedId.safe("a%3Ab"));
    assertThat(ScopedId.safe("a:b")).isNotEqualTo(ScopedId.safe("a|b"));
    assertThat(ScopedId.safe("x".repeat(500) + "1")).isNotEqualTo(ScopedId.safe("x".repeat(500) + "2"));
  }

  @Test
  void aCallerSuppliedEntityIdMustBelongToTheOrganizationAndBeUsable() {
    assertThat(ScopedId.belongsTo("org:m:h:r1", "org")).isTrue();
    assertThat(ScopedId.belongsTo("other:m:h:r1", "org")).isFalse();
    assertThat(ScopedId.belongsTo("org2:m", "org")).isFalse();
    assertThat(ScopedId.belongsTo("org:a|b", "org")).isFalse();
    assertThat(ScopedId.belongsTo("org:a\u0000b", "org")).isFalse();
    assertThat(ScopedId.belongsTo("org:" + "x".repeat(2000), "org")).isFalse();
    assertThat(ScopedId.belongsTo(null, "org")).isFalse();
  }
}
