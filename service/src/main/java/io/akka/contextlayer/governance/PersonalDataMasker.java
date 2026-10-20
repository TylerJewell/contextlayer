package io.akka.contextlayer.governance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data in text before it reaches a model, replacing each character of a match with
 * a {@code *} as the runtime's sanitizers do. It covers the same kinds as the runtime's predefined
 * sanitizers (email, IBAN, credit card, IP address, phone) and runs in the service so that what was
 * masked is known here and can be recorded as evidence; the runtime's own sanitizer, configured for
 * the same control, masks again at the model boundary.
 *
 * <p>Card numbers are masked only when they pass the Luhn check, so order numbers and other long
 * digit strings that are not cards are left alone. Matching is by pattern and so is best-effort: it
 * reduces what a model sees, it does not guarantee that none of it is personal.
 */
public final class PersonalDataMasker {
  private PersonalDataMasker() {}

  /** @param found how many values of each kind were masked, in the order the kinds are applied */
  public record Masked(String text, Map<String, Integer> found) {
    public int total() {
      return found.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** "email 2, phone 1": the kinds and counts, never the values. */
    public String summary() {
      if (found.isEmpty()) return "nothing to mask";
      var parts = new StringBuilder();
      found.forEach((kind, count) -> parts.append(parts.length() == 0 ? "" : ", ").append(kind).append(' ').append(count));
      return parts.toString();
    }
  }

  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b");
  private static final Pattern CARD = Pattern.compile("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");
  private static final Pattern IPV4 = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
  private static final Pattern PHONE =
      Pattern.compile("(?<![\\w])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]?\\d{3}[ .-]?\\d{4}(?![\\w])");

  public static Masked mask(String text) {
    if (text == null || text.isEmpty()) return new Masked(text == null ? "" : text, Map.of());
    var found = new LinkedHashMap<String, Integer>();
    var out = apply(text, EMAIL, "email", found, false);
    out = apply(out, IBAN, "iban", found, false);
    out = apply(out, CARD, "card", found, true);
    out = apply(out, IPV4, "ip", found, false);
    out = apply(out, PHONE, "phone", found, false);
    return new Masked(out, Map.copyOf(found));
  }

  private static String apply(String text, Pattern pattern, String kind, Map<String, Integer> found, boolean luhn) {
    Matcher m = pattern.matcher(text);
    var result = new StringBuilder();
    int last = 0;
    int count = 0;
    while (m.find()) {
      if (luhn && !passesLuhn(m.group())) continue;
      result.append(text, last, m.start()).append("*".repeat(m.end() - m.start()));
      last = m.end();
      count++;
    }
    if (count == 0) return text;
    result.append(text, last, text.length());
    found.merge(kind, count, Integer::sum);
    return result.toString();
  }

  private static boolean passesLuhn(String candidate) {
    int sum = 0;
    boolean doubleIt = false;
    int digits = 0;
    for (int i = candidate.length() - 1; i >= 0; i--) {
      char c = candidate.charAt(i);
      if (!Character.isDigit(c)) continue;
      int d = c - '0';
      if (doubleIt) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      doubleIt = !doubleIt;
      digits++;
    }
    return digits >= 13 && digits <= 19 && sum % 10 == 0;
  }
}
