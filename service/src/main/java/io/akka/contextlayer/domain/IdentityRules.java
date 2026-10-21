package io.akka.contextlayer.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a record into the keys under which it may be the same thing as other records (REQUIREMENTS ENT-02): the same
 * person in a CRM and a claims system, or the same product under one SKU in two ERPs. A connector declares which of a
 * record's fields are its match keys. Each rule has a fixed confidence, shown to the reviewer; every match waits for a
 * person whatever its confidence (ENT-03, control custom-cl0004).
 */
public final class IdentityRules {

  public record Candidate(String rule, String key, double confidence) {}

  private IdentityRules() {}

  /** The built-in keys of a person: an email address, and a name with a date of birth. */
  public static List<Candidate> candidates(Map<String, String> attributes) {
    var found = new ArrayList<Candidate>();
    var email = attributes.get("email");
    if (email != null && email.contains("@")) {
      found.add(new Candidate("email", email.strip().toLowerCase(Locale.ROOT), 1.0));
    }
    var name = attributes.get("name");
    var dob = attributes.get("dob");
    if (name != null && !name.isBlank() && dob != null && !dob.isBlank()) {
      found.add(new Candidate("name-dob", normalizeName(name) + "~" + dob.strip(), 0.7));
    }
    return found;
  }

  /**
   * Every key a connector declares for a record: {@code email}, and {@code name} with {@code dob}, follow the
   * built-in rules for a person; any other key, such as {@code sku} or {@code gtin}, links records whose values are
   * equal once trimmed and case-folded, under a rule named after the key.
   */
  public static List<Candidate> declared(Map<String, String> attributes) {
    var found = new ArrayList<>(candidates(attributes));
    attributes.entrySet().stream()
        .filter(e -> !PERSON_KEYS.contains(e.getKey()))
        .filter(e -> e.getValue() != null && !e.getValue().isBlank())
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> found.add(new Candidate(e.getKey(), e.getValue().strip().toLowerCase(Locale.ROOT), 1.0)));
    return found;
  }

  private static final List<String> PERSON_KEYS = List.of("email", "name", "dob");

  static String normalizeName(String name) {
    var folded = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim().replaceAll("\\s+", " ");
  }
}
