package io.akka.contextlayer.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An assistant's work, written as data: an ordered list of typed steps, each naming the step that
 * follows it. A playbook contains no code, and it can only move forward, so every run ends
 * (REQUIREMENTS AST-03, DESIGN D-05). The definition of an assistant release is a playbook.
 *
 * <p>Step types:
 * <ul>
 *   <li>{@code classify}: a model labels the case, choosing only from {@code labels}, seeing only
 *       the evidence {@code fields}; the label is kept under the name {@code store}</li>
 *   <li>{@code branch}: continues at the step named for the value of {@code var}, or {@code otherwise}</li>
 *   <li>{@code act}: requests an action, with {@code payload} filled in from evidence and labels</li>
 *   <li>{@code annotate}: adds {@code text} to the case timeline</li>
 * </ul>
 * Text may contain {@code {Type.field}} (evidence) and {@code {name}} (a stored label).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Playbook(List<Step> steps) {

  public static final String END = "end";
  public static final int MAX_STEPS = 20;
  public static final int MAX_LABELS = 12;

  /** One flat shape for every step type; which fields are required depends on {@code type}. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Step(
      String id,
      String type,
      String next,
      String instruction,
      List<String> fields,
      List<String> labels,
      String store,
      String var,
      Map<String, String> cases,
      String otherwise,
      String actionType,
      String payload,
      String text) {}

  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
      .build();
  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,30}");
  private static final Pattern LABEL = Pattern.compile("[A-Z][A-Z0-9_]{0,30}");
  private static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9_]{0,30}");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
  private static final Set<String> TYPES = Set.of("classify", "branch", "act", "annotate");

  /** Parses and validates; the message of the exception lists every problem found. */
  public static Playbook parse(String json) {
    Playbook playbook;
    try {
      playbook = MAPPER.readValue(json, Playbook.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("the definition is not a valid playbook: " + e.getOriginalMessage());
    }
    var errors = playbook.validate();
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    return playbook;
  }

  public Step step(String id) {
    return steps.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  public String first() {
    return steps.get(0).id();
  }

  public List<String> validate() {
    var errors = new ArrayList<String>();
    if (steps == null || steps.isEmpty()) return List.of("a playbook needs at least one step");
    if (steps.size() > MAX_STEPS) errors.add("at most " + MAX_STEPS + " steps");

    var ids = new HashSet<String>();
    for (var step : steps) {
      if (step == null || step.id() == null || !ID.matcher(step.id()).matches()) {
        errors.add("a step id must be lowercase letters, digits and hyphens");
      } else if (!ids.add(step.id())) {
        errors.add("step " + step.id() + " is declared twice");
      }
    }
    for (var step : steps) {
      if (step == null || step.id() == null) continue;
      validateStep(step, ids, errors);
    }
    if (errors.isEmpty() && hasCycle()) errors.add("steps must only move forward; the steps form a loop");
    return List.copyOf(errors);
  }

  private void validateStep(Step s, Set<String> ids, List<String> errors) {
    var at = "step " + s.id();
    if (s.type() == null || !TYPES.contains(s.type())) {
      errors.add(at + ": type must be one of " + TYPES.stream().sorted().toList());
      return;
    }
    switch (s.type()) {
      case "classify" -> {
        if (blank(s.instruction())) errors.add(at + ": instruction is required");
        if (s.fields() == null || s.fields().isEmpty()) errors.add(at + ": fields (what the model may see) is required");
        if (s.labels() == null || s.labels().size() < 2 || s.labels().size() > MAX_LABELS
            || !s.labels().stream().allMatch(l -> l != null && LABEL.matcher(l).matches())) {
          errors.add(at + ": labels must be 2 to " + MAX_LABELS + " names in capitals");
        }
        if (s.store() == null || !NAME.matcher(s.store()).matches()) errors.add(at + ": store must be a name");
        checkTarget(at, "next", s.next(), ids, errors);
      }
      case "branch" -> {
        if (s.var() == null || !NAME.matcher(s.var()).matches()) errors.add(at + ": var must be a name");
        if (s.cases() == null || s.cases().isEmpty()) errors.add(at + ": cases is required");
        else s.cases().values().forEach(t -> checkTarget(at, "a case target", t, ids, errors));
        checkTarget(at, "otherwise", s.otherwise(), ids, errors);
      }
      case "act" -> {
        if (blank(s.actionType())) errors.add(at + ": actionType is required");
        if (s.payload() == null) errors.add(at + ": payload is required (use an empty string for none)");
        checkTarget(at, "next", s.next(), ids, errors);
      }
      case "annotate" -> {
        if (blank(s.text())) errors.add(at + ": text is required");
        checkTarget(at, "next", s.next(), ids, errors);
      }
      default -> { }
    }
  }

  private static void checkTarget(String at, String what, String target, Set<String> ids, List<String> errors) {
    if (target == null || !(END.equals(target) || ids.contains(target))) {
      errors.add(at + ": " + what + " must name a step or '" + END + "'");
    }
  }

  private boolean hasCycle() {
    var graph = new HashMap<String, List<String>>();
    for (var s : steps) graph.put(s.id(), targets(s));
    var done = new HashSet<String>();
    var onPath = new HashSet<String>();
    for (var id : graph.keySet()) {
      if (visit(id, graph, done, onPath)) return true;
    }
    return false;
  }

  private static boolean visit(String id, Map<String, List<String>> graph, Set<String> done, Set<String> onPath) {
    if (END.equals(id) || done.contains(id)) return false;
    if (!onPath.add(id)) return true;
    for (var next : graph.getOrDefault(id, List.of())) {
      if (visit(next, graph, done, onPath)) return true;
    }
    onPath.remove(id);
    done.add(id);
    return false;
  }

  /** The step ids a step may continue at. */
  public static List<String> targets(Step s) {
    var out = new ArrayList<String>();
    if ("branch".equals(s.type())) {
      out.addAll(s.cases().values());
      out.add(s.otherwise());
    } else {
      out.add(s.next());
    }
    return out;
  }

  /**
   * Fills {@code {Type.field}} from evidence and {@code {name}} from stored labels. Fails on a
   * placeholder that has no value, because guessing would send wrong text to a person or a system.
   */
  public static String fill(String template, Map<String, Object> evidence, Map<String, String> labels) {
    var out = new StringBuilder();
    Matcher m = PLACEHOLDER.matcher(template);
    while (m.find()) {
      var name = m.group(1);
      Object value = labels.containsKey(name) ? labels.get(name) : evidence.get(name);
      if (value == null) throw new IllegalArgumentException("no value for {" + name + "}");
      m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
