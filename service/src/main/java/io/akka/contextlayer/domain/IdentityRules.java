package io.akka.contextlayer.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a source record into the keys under which it may be the same entity as other records
 * (REQUIREMENTS ENT-02). Each rule has a fixed confidence; matches below
 * {@link #AUTO_CONFIRM_THRESHOLD} wait for a person (ENT-03).
 */
public final class IdentityRules {

  public static final double AUTO_CONFIRM_THRESHOLD = 0.9;

  public record Candidate(String rule, String key, double confidence) {}

  private IdentityRules() {}

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

  static String normalizeName(String name) {
    var folded = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim().replaceAll("\\s+", " ");
  }
}
