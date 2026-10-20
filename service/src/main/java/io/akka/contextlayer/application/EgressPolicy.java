package io.akka.contextlayer.application;

import java.util.List;
import java.util.Locale;

/**
 * Which hosts the platform may call (REQUIREMENTS OPS-04). An empty list allows nothing. An entry
 * is an exact host name, or "*.example.com" for any subdomain of example.com.
 */
public class EgressPolicy {

  private final List<String> allowed;

  public EgressPolicy(List<String> allowedHosts) {
    this.allowed = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
  }

  public boolean allows(String host) {
    if (host == null || host.isBlank()) return false;
    var h = host.toLowerCase(Locale.ROOT);
    for (var rule : allowed) {
      if (rule.equals(h)) return true;
      if (rule.startsWith("*.") && h.endsWith(rule.substring(1)) && h.length() > rule.length() - 1) {
        return true;
      }
    }
    return false;
  }
}
