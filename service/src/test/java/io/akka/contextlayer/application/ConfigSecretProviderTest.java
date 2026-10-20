package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

class ConfigSecretProviderTest {

  private ConfigSecretProvider provider(String hocon) {
    return new ConfigSecretProvider(ConfigFactory.parseString(hocon));
  }

  @Test
  void aListedReferenceResolvesAndAnUnlistedOneDoesNot() {
    var p = provider("contextlayer.secrets { \"vault://a/claims\" = \"tok\" }");
    assertThat(p.resolve("vault://a/claims")).contains("tok");
    assertThat(p.resolve("vault://a/other")).isEmpty();
  }

  @Test
  void withNoSettingsNothingResolves() {
    assertThat(provider("a = 1").resolve("vault://anything")).isEmpty();
  }

  @Test
  void theDevelopmentSecretAnswersEveryReferenceButNeverOverridesAListedOne() {
    var p = provider("contextlayer { dev-secret = \"dev\", secrets { \"vault://a/claims\" = \"real\" } }");
    assertThat(p.resolve("vault://a/claims")).contains("real");
    assertThat(p.resolve("vault://a/anything")).contains("dev");
  }

  @Test
  void anEmptyDevelopmentSecretIsOff() {
    assertThat(provider("contextlayer.dev-secret = \"\"").resolve("vault://x")).isEmpty();
  }

  @Test
  void printingTheProviderRevealsNoSecret() {
    var p = provider("contextlayer { dev-secret = \"dev-value\", secrets { \"vault://a/b\" = \"s3cret\" } }");
    assertThat(p.toString()).doesNotContain("s3cret").doesNotContain("dev-value");
  }
}
