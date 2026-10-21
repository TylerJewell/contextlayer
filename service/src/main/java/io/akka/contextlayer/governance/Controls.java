package io.akka.contextlayer.governance;

/** Row ids of the catalog's controls, for the code that enforces them, grouped by mechanism. */
public final class Controls {
  private Controls() {}

  // Sanitizers
  public static final String MASK_PERSONAL_DATA = "custom-cl0001";
  public static final String APPROVER_VIEW_MASK = "custom-cl0017";
  public static final String NOTIFICATION_MASK = "custom-cl0018";
  public static final String IDENTITY_KEY_HASHING = "custom-cl0019";
  public static final String ROUTE_SECRET_REDACTION = "custom-cl0020";

  // Guardrails at the model
  public static final String JAILBREAK_DETECTION = "custom-cl0010";
  public static final String SHAPE_ONLY_DRAFTING = "custom-cl0013";
  public static final String CLASSIFY_FIELD_ALLOWLIST = "custom-cl0021";
  public static final String COMPLIANCE_QUESTION_CATALOG = "custom-cl0022";
  public static final String LABEL_ALLOWLIST = "custom-cl0002";
  public static final String FACTS_ONLY_WORDING = "custom-cl0016";
  public static final String DRAFT_SCHEMA_CHECK = "custom-cl0023";

  // Guardrails on actions
  public static final String NEVER_BY_PLAYBOOK = "custom-cl0007";
  public static final String STAGE_GATED_ACTIONS = "custom-cl0024";
  public static final String LIVE_CONNECTOR_ACTIONS = "custom-cl0025";
  public static final String HALT_ENFORCEMENT = "custom-cl0026";
  public static final String APPROVED_RELEASE_RUNS = "custom-cl0027";
  public static final String EXACTLY_ONCE_ACTIONS = "custom-cl0028";

  // Guardrails on calls out and data in
  public static final String EGRESS = "custom-cl0009";
  public static final String CREDENTIALS_BY_REFERENCE = "custom-cl0029";
  public static final String BOUNDED_SOURCE_READS = "custom-cl0030";
  public static final String GOVERNED_INTAKE = "custom-cl0031";

  // Guardrails on reading data
  public static final String DOMAIN_ACCESS = "custom-cl0015";
  public static final String APPROVED_JOINS = "custom-cl0032";
  public static final String CONFIRMED_IDENTITIES = "custom-cl0033";
  public static final String READ_ONLY_QUESTIONS = "custom-cl0034";
  public static final String PROJECT_ISOLATION = "custom-cl0035";
  public static final String CALLER_AUTHORIZATION = "custom-cl0036";

  // Guardrails on definitions and cases
  public static final String PLAYBOOK_DEFINITION_BOUNDS = "custom-cl0037";
  public static final String VALID_CONNECTOR_PACKS = "custom-cl0038";
  public static final String COMPLETE_RUNS_CLOSE_CASES = "custom-cl0039";

  // Humans
  public static final String ACTION_APPROVAL = "custom-cl0003";
  public static final String IDENTITY_REVIEW = "custom-cl0004";
  public static final String RELEASE_APPROVAL = "custom-cl0005";
  public static final String STAGE_CHANGE_APPROVAL = "custom-cl0040";
  public static final String PACK_ACTIVATION = "custom-cl0006";
  public static final String PACK_DEACTIVATION = "custom-cl0041";
  public static final String HALT = "custom-cl0011";
  public static final String RESTART = "custom-cl0012";
  public static final String DOMAIN_JOIN = "custom-cl0014";
  public static final String DOMAIN_ADMINISTRATION = "custom-cl0042";
  public static final String PERSON_RUNS_QUESTION = "custom-cl0043";
  public static final String UNKNOWN_OUTCOME_RESOLUTION = "custom-cl0044";
  public static final String FAILED_STEP_HANDOFF = "custom-cl0045";
  public static final String CASE_RESOLUTION = "custom-cl0046";
  public static final String CONTROL_WAIVER = "custom-cl0047";
  public static final String CONTROL_ACCEPTANCE = "custom-cl0048";
  public static final String EVIDENCE_ASSERTION = "custom-cl0049";

  // Evals
  public static final String RUSHED_DECISIONS = "custom-cl0008";
  public static final String LABEL_AUDIT = "custom-cl0050";
  public static final String WORDING_AUDIT = "custom-cl0051";
  public static final String EVIDENCE_CHAIN_INTEGRITY = "custom-cl0052";
  public static final String SIGNED_CONTRACT_CONFORMANCE = "custom-cl0053";
  public static final String SOURCE_FRESHNESS = "custom-cl0054";
  public static final String DOCUMENT_FRESHNESS = "custom-cl0055";

  // Gates
  public static final String CATALOG_VALID_AT_STARTUP = "custom-cl0056";
  public static final String DRAFT_MATCHES_BUNDLE = "custom-cl0057";
}
