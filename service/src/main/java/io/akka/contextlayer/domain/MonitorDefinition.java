package io.akka.contextlayer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A saved question that is run on a schedule. Each run compares the rows now matching with the
 * rows that matched before; a row's identity is the value of {@code keyField}, one of the
 * question's return fields (REQUIREMENTS MON-01).
 *
 * @param assistantId the assistant whose playbook handles each new case, or empty for a person to handle it
 * @param routeTo who a new case is routed to when no assistant handles it
 * @param onChange also run when data the question reads changes, shortly after the change; the interval
 *     then only makes sure a run happens even if no change is seen
 * @param owner who set the monitor up. A monitor reads on their behalf, so each run checks that they may
 *     still read what it asks about
 * @param ownerRoles the roles the owner had when they set it up, which is what the run checks against
 */
public record MonitorDefinition(
    String name,
    StructuredQuestion question,
    String keyField,
    int intervalMinutes,
    String assistantId,
    String routeTo,
    boolean onChange,
    String owner,
    List<String> ownerRoles) {

  public MonitorDefinition {
    owner = owner == null ? "" : owner;
    ownerRoles = ownerRoles == null ? List.of() : List.copyOf(ownerRoles);
  }

  public MonitorDefinition(
      String name, StructuredQuestion question, String keyField, int intervalMinutes, String assistantId, String routeTo,
      boolean onChange) {
    this(name, question, keyField, intervalMinutes, assistantId, routeTo, onChange, "", List.of());
  }

  public MonitorDefinition(
      String name, StructuredQuestion question, String keyField, int intervalMinutes, String assistantId, String routeTo) {
    this(name, question, keyField, intervalMinutes, assistantId, routeTo, false, "", List.of());
  }

  public MonitorDefinition ownedBy(String subject, List<String> roles) {
    return new MonitorDefinition(name, question, keyField, intervalMinutes, assistantId, routeTo, onChange, subject, roles);
  }

  /** The entity types the question reads: the base type and each linked type. */
  public List<String> entityTypes() {
    var types = new ArrayList<String>();
    if (question == null) return types;
    types.add(question.entityType());
    if (question.links() != null) question.links().forEach(l -> types.add(l.entityType()));
    return types;
  }

  public static final int MIN_INTERVAL_MINUTES = 1;
  public static final int MAX_INTERVAL_MINUTES = 7 * 24 * 60;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,80}");

  public List<String> validate() {
    var errors = new ArrayList<String>();
    if (name == null || !NAME.matcher(name).matches()) {
      errors.add("name must be letters, digits, spaces, hyphens or underscores");
    }
    if (question == null) {
      errors.add("a question is required");
    } else {
      errors.addAll(question.validate());
      if (keyField == null || question.returnFields() == null || !question.returnFields().contains(keyField)) {
        errors.add("keyField must be one of the question's return fields");
      }
    }
    if (intervalMinutes < MIN_INTERVAL_MINUTES || intervalMinutes > MAX_INTERVAL_MINUTES) {
      errors.add("intervalMinutes must be between " + MIN_INTERVAL_MINUTES + " and " + MAX_INTERVAL_MINUTES);
    }
    return List.copyOf(errors);
  }
}
