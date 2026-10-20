package io.akka.contextlayer.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Answering an auditor's question from recorded facts. A model only decides which question was asked;
 * the facts are computed by fixed templates from what the service recorded, and a second model
 * only words them. What it words is checked against the facts, so the answer cannot say more than
 * they do.
 */
public final class ComplianceAnswer {
  private ComplianceAnswer() {}

  /** The questions the service can answer. Anything else is handed back with this list. */
  public enum Intent {
    CONTROL_STATUS("Where one control stands: whether it is working, who accepted it, who said what counts as its evidence, and what is missing.", true),
    EVIDENCE_FOR_CONTROL("What evidence exists for one control over a period, what it shows, and how it is held.", true),
    HUMAN_DECISIONS("The decisions people made under one control over a period: approvals, refusals, and any made too quickly.", true),
    GAPS("Which controls are not accepted, have nothing asserted about their evidence, have no enforcing records, or lack backing documents.", false),
    INTEGRITY("Whether the evidence can be altered unnoticed: which stores are tamper-evident and whether the evidence chain checks out.", false);

    private final String description;
    private final boolean needsControl;

    Intent(String description, boolean needsControl) {
      this.description = description;
      this.needsControl = needsControl;
    }

    public String description() {
      return description;
    }

    public boolean needsControl() {
      return needsControl;
    }

    public static Optional<Intent> parse(String text) {
      if (text == null) return Optional.empty();
      var wanted = text.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
      for (var intent : values()) if (intent.name().equals(wanted)) return Optional.of(intent);
      return Optional.empty();
    }
  }

  /** What the classifying model said. It is checked before it is used. */
  public record Classification(String intent, String controlId, Integer periodDays, String clarification) {}

  /** One thing the records say, and the records it comes from, such as {@code control:custom-cl0003}. */
  public record Fact(String text, List<String> refs) {
    public Fact {
      refs = refs == null ? List.of() : List.copyOf(refs);
    }
  }

  /** @param limits what the records do not show, stated as plainly as the facts are */
  public record Facts(Intent intent, String controlId, int periodDays, List<Fact> facts, List<String> limits) {
    public Facts {
      facts = List.copyOf(facts);
      limits = List.copyOf(limits);
    }

    public String allText() {
      var parts = new ArrayList<String>();
      facts.forEach(f -> parts.add(f.text()));
      parts.addAll(limits);
      return String.join("\n", parts);
    }
  }

  public static final int DEFAULT_PERIOD_DAYS = 90;
  public static final int MAX_PERIOD_DAYS = 365;

  public static int period(Integer days) {
    return days == null || days < 1 ? DEFAULT_PERIOD_DAYS : Math.min(days, MAX_PERIOD_DAYS);
  }

  /** The answer written without a model: the facts, then what is not shown. */
  public static String plain(Facts facts) {
    var out = new StringBuilder();
    for (var fact : facts.facts()) out.append(fact.text()).append('\n');
    if (!facts.limits().isEmpty()) {
      out.append("\nWhat the records do not show:\n");
      for (var limit : facts.limits()) out.append("- ").append(limit).append('\n');
    }
    return out.toString().strip();
  }

  // ---- checking what a model wrote against the facts

  private static final Pattern CONTROL_ID = Pattern.compile("custom-cl\\d{4}");
  private static final Pattern NUMBER = Pattern.compile("(?<![\\w.-])\\d[\\d,]*(?:\\.\\d+)?(?![\\w-])");
  /** Words that claim more than a record can show, allowed only if the facts themselves use them. */
  private static final Set<String> ABSOLUTES = Set.of("compliant", "fully compliant", "no gaps", "tamper-proof", "tamper proof",
      "immutable", "guaranteed", "certified");

  /** @param unsupported what the text says that the facts do not */
  public record Check(boolean ok, List<String> unsupported) {}

  /**
   * A text is supported when every control id and every number in it appears in the facts, and it makes no
   * absolute claim the facts do not make. Words are not checked: a sentence can still be a poor summary. This
   * stops the failures that matter, an invented number, an invented control, and a verdict nobody recorded.
   */
  public static Check check(String text, Facts facts) {
    return check(text, facts.allText());
  }

  /** As {@link #check(String, Facts)}, against the text of the facts and limits as the model was given them. */
  public static Check check(String text, String factsText) {
    var known = factsText.toLowerCase(Locale.ROOT);
    var unsupported = new ArrayList<String>();
    var lower = text.toLowerCase(Locale.ROOT);
    var ids = CONTROL_ID.matcher(text);
    while (ids.find()) if (!known.contains(ids.group())) unsupported.add("the control " + ids.group());
    var numbers = NUMBER.matcher(text);
    while (numbers.find()) {
      var n = numbers.group().replace(",", "");
      if (!known.replace(",", "").contains(n)) unsupported.add("the number " + n);
    }
    for (var word : ABSOLUTES) if (lower.contains(word) && !known.contains(word)) unsupported.add("the claim \"" + word + "\"");
    return new Check(unsupported.isEmpty(), unsupported.stream().distinct().collect(Collectors.toList()));
  }
}
