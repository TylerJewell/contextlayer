package io.akka.contextlayer.application;

import com.typesafe.config.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development secret provider: reads {@code contextlayer.secrets} from configuration, where each
 * key is a credential reference and each value is the credential. Production deployments replace
 * this with a provider backed by a key management service.
 *
 * <p>If {@code contextlayer.dev-secret} is set, every reference not listed resolves to it. That is
 * for running against mock sources on a developer's machine and is off unless the setting is
 * non-empty; a warning is logged when it is on.
 */
public class ConfigSecretProvider implements SecretProvider {

  private static final Logger log = LoggerFactory.getLogger(ConfigSecretProvider.class);

  private final Map<String, String> secrets = new HashMap<>();
  private final String devSecret;

  public ConfigSecretProvider(Config config) {
    if (config.hasPath("contextlayer.secrets")) {
      for (var entry : config.getObject("contextlayer.secrets").unwrapped().entrySet()) {
        secrets.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    this.devSecret = config.hasPath("contextlayer.dev-secret") ? config.getString("contextlayer.dev-secret") : "";
    if (!devSecret.isEmpty()) {
      log.warn("contextlayer.dev-secret is set: every credential reference resolves to it. Use this only on a developer machine.");
    }
  }

  @Override
  public Optional<String> resolve(String reference) {
    var configured = secrets.get(reference);
    if (configured != null) return Optional.of(configured);
    return devSecret.isEmpty() ? Optional.empty() : Optional.of(devSecret);
  }

  /** Deliberately does not print the secrets. */
  @Override
  public String toString() {
    return "ConfigSecretProvider[" + secrets.size() + " references]";
  }
}
