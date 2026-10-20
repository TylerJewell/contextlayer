package io.akka.contextlayer.application;

import java.util.Optional;

/**
 * Turns a credential reference (the {@code credentialRef} of a connector pack) into the
 * credential itself. Callers must never log, store or return what this gives back
 * (REQUIREMENTS CON-02).
 */
public interface SecretProvider {

  Optional<String> resolve(String reference);
}
