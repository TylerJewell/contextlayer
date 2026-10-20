package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.governance.ControlClaims.Acceptance;
import io.akka.contextlayer.governance.ControlClaims.Assertion;
import io.akka.contextlayer.governance.ControlClaims.Expectation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeEvidenceTest {

  private static final ControlCatalog CATALOG = ControlCatalog.current();
  private static final long AT = 1_792_000_000_000L;

  private static Evidence evidence(String control, String eventType, String boundary, String decision, String consequence, String actor) {
    return new Evidence("p:ev:" + control + ":" + decision + actor, "p", control, eventType, boundary, EvaluationMode.ENFORCING, decision,
        consequence, "reason-" + decision, "subject", actor, "idp=x", CATALOG.digest(), "", false, AT);
  }

  private static String export(List<Evidence> records, Map<String, ControlClaims> claims) {
    var items = new ArrayList<RuntimeEvidence.Item>();
    String head = "";
    for (int i = 0; i < records.size(); i++) {
      var link = Integrity.next(head, i, records.get(i).evidenceId(), Integrity.recordDigest(records.get(i)), AT);
      items.add(new RuntimeEvidence.Item(link, records.get(i)));
      head = link.chainDigest();
    }
    return RuntimeEvidence.turtle(new RuntimeEvidence.Input("p", CATALOG, claims, items, records.size(), head, Map.of()));
  }

  @Test
  void everyControlBecomesAnObligationWithItsOwnBindingUnderOnePolicy() {
    var ttl = export(List.of(), Map.of());

    assertThat(ttl).contains("ex:system a ai:AISystem").contains("ex:policy a dpv:Policy, prov:Plan");
    for (var control : CATALOG.controls()) {
      var id = RuntimeEvidence.id(control.rowId());
      assertThat(ttl).contains("ex:obl-" + id + " a dpv:Obligation").contains("ex:binding-" + id + " a rte:ObligationBinding")
          .contains("rte:boundObligation ex:obl-" + id);
    }
    assertThat(ttl).contains("dct:identifier \"" + CATALOG.digest() + "\"");
  }

  @Test
  void theRecordStoresSayWhichOnesAreChained() {
    var ttl = export(List.of(evidence(Controls.ACTION_APPROVAL, "HumanDecisionRecorded", "WORKFLOW_STEP", "APPROVE", "CONTINUED", "bob")), Map.of());

    assertThat(ttl).contains("ex:store-evidence-ledger a rte:RecordStore").contains("rte:hasIntegrityScheme rte:HashChained")
        .contains("ex:store-human-events a rte:RecordStore");
    assertThat(ttl.split("rte:hasIntegrityScheme rte:NoIntegrity", -1).length - 1).isEqualTo(4);
  }

  @Test
  void aHumanDecisionIsAReviewWithAnOutcomeAPersonAndAPositionInTheLedger() {
    var approve = evidence(Controls.ACTION_APPROVAL, "HumanDecisionRecorded", "WORKFLOW_STEP", "APPROVE", "CONTINUED", "bob");
    var deny = evidence(Controls.ACTION_APPROVAL, "HumanDecisionRecorded", "WORKFLOW_STEP", "DENY", "BLOCKED", "carol");

    var ttl = export(List.of(approve, deny), Map.of());

    assertThat(ttl).contains(" a rte:HumanReview").contains("rte:hasOutcome rte:Approved").contains("rte:hasOutcome rte:Rejected");
    assertThat(ttl).contains("ex:agent-bob a prov:Person").contains("ex:agent-carol a prov:Person");
    assertThat(ttl).contains("a rte:RuntimeRecord").contains("rte:streamPosition 1").contains("rte:streamPosition 2")
        .contains("rte:recordedIn ex:store-evidence-ledger").contains("rte:recordDigest \"sha256:");
  }

  @Test
  void aHaltASanitizationAndAnAccessDecisionAreOfTheirOwnClasses() {
    var halt = evidence(Controls.HALT, "HumanDecisionRecorded", "STATE_TRANSITION", "APPROVE", "CONTINUED", "dan");
    var mask = evidence(Controls.MASK_PERSONAL_DATA, "GuardrailDecisionRecorded", "BEFORE_LLM_CALL", "PASS", "MASKED", "system:masker");
    var access = evidence(Controls.DOMAIN_ACCESS, "ControlEvaluationRecorded", "WORKFLOW_STEP", "BLOCK", "BLOCKED", "alice");

    var ttl = export(List.of(halt, mask, access), Map.of());

    assertThat(ttl).contains("a rte:SystemHalt").contains("rte:hasOutcome rte:Halted");
    assertThat(ttl).contains("a rte:Sanitization").contains("rte:hasOutcome rte:Transformed").contains("rte:atInterventionPoint rte:BeforeModelCall");
    assertThat(ttl).contains("a rte:AccessDecision").contains("rte:hasOutcome rte:Deny");
    assertThat(ttl).contains("ex:agent-system_masker a prov:SoftwareAgent");
  }

  @Test
  void aRecordOfSomeoneAcceptingOrAssertingIsAClaimNotRuntimeActivity() {
    var claim = evidence(Controls.ACTION_APPROVAL, "ControlAccepted", "WORKFLOW_STEP", "ACCEPT", "OBSERVED_ONLY", "dana");

    var ttl = export(List.of(claim), Map.of());

    assertThat(ttl).doesNotContain("rte:RuntimeRecord").doesNotContain("ex:agent-dana");
  }

  @Test
  void anAcceptanceAttributesTheBindingAndAnAssertionIsItsOwnNodeDerivedFromIt() {
    var digest = CATALOG.digest();
    var claims = ControlClaims.empty()
        .withAcceptance(new Acceptance("dana", "idp=x", "taken on", digest, AT))
        .withAssertion(new Assertion(1, "HumanDecisionRecorded", List.of(new Expectation("override procedure", 365)), "erin", "idp=x", "why", digest, AT))
        .withAssertion(new Assertion(2, "HumanDecisionRecorded", List.of(new Expectation("override procedure", 365)), "frank", "idp=x", "again", digest, AT));
    var id = RuntimeEvidence.id(Controls.ACTION_APPROVAL);

    var ttl = export(List.of(), Map.of(Controls.ACTION_APPROVAL, claims));

    assertThat(ttl).contains("ex:binding-" + id + " a rte:ObligationBinding ;\n    rte:boundObligation ex:obl-" + id + " ;\n    prov:wasAttributedTo ex:agent-dana");
    assertThat(ttl).contains("ex:evid-" + id + "-r1 a rte:EvidenceAssertion").contains("ex:evid-" + id + "-r2 a rte:EvidenceAssertion");
    assertThat(ttl).contains("rte:hasEvidenceKind rte:HumanReview").contains("prov:wasAttributedTo ex:agent-erin").contains("prov:wasAttributedTo ex:agent-frank");
    assertThat(ttl).contains("prov:wasDerivedFrom ex:binding-" + id).contains("prov:wasRevisionOf ex:evid-" + id + "-r1");
    assertThat(ttl).contains("rte:hasCoverage rte:PartialCoverage").contains("Still needed: override procedure: none attached");
    assertThat(ttl.split("rte:hasCoverage", -1).length - 1).as("only the current assertion states coverage").isEqualTo(1);
  }

  @Test
  void textIsEscapedAndNamesAreSafe() {
    assertThat(RuntimeEvidence.id("a b:c|d/é")).isEqualTo("a_b_c_d__");
    var claims = ControlClaims.empty().withAcceptance(new Acceptance("o\"wner", "idp", "x", "d", AT));

    var ttl = export(List.of(), Map.of(Controls.ACTION_APPROVAL, claims));

    assertThat(ttl).contains("ex:agent-o_wner").contains("skos:prefLabel \"o\\\"wner\"");
  }
}
