package io.akka.contextlayer.governance;

/** Row ids of the catalog's controls, for the code that enforces them. */
public final class Controls {
  private Controls() {}

  public static final String MASK_PERSONAL_DATA = "custom-cl0001";
  public static final String LABEL_ALLOWLIST = "custom-cl0002";
  public static final String ACTION_APPROVAL = "custom-cl0003";
  public static final String IDENTITY_REVIEW = "custom-cl0004";
  public static final String RELEASE_APPROVAL = "custom-cl0005";
  public static final String PACK_ACTIVATION = "custom-cl0006";
  public static final String ACTION_POLICY = "custom-cl0007";
  public static final String RUSHED_DECISIONS = "custom-cl0008";
  public static final String EGRESS = "custom-cl0009";
  public static final String JAILBREAK_DETECTION = "custom-cl0010";
  public static final String HALT = "custom-cl0011";
  public static final String RESTART = "custom-cl0012";
  public static final String QUESTION_DRAFT = "custom-cl0013";
  public static final String DOMAIN_JOIN = "custom-cl0014";
  public static final String DOMAIN_ACCESS = "custom-cl0015";
  public static final String COMPLIANCE_ANSWER = "custom-cl0016";
}
