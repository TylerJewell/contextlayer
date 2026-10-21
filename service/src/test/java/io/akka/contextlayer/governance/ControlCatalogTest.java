package io.akka.contextlayer.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class ControlCatalogTest {

  private static ControlCatalog parse(String yaml) {
    return ControlCatalog.parse(yaml.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void decidingAControlInTheConsoleDoesNotChangeTheCatalog() throws Exception {
    String yaml;
    try (var in = getClass().getClassLoader().getResourceAsStream(ControlCatalog.RESOURCE)) {
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var decided = yaml.replaceFirst("(\\R  - row_id: custom-cl0001\\R)", "$1    sign_off:\n      decision: approve\n"
        + "      reviewer: tylerjewell@gmail.com\n      at: '2026-10-20T12:00:00Z'\n      rationale: in scope\n");
    assertThat(decided).isNotEqualTo(yaml);

    assertThat(parse(decided).digest()).isEqualTo(parse(yaml).digest());
    assertThat(parse(decided).systemId()).isEqualTo("contextlayer");
  }

  @Test
  public void everyControlIdTheCodeNamesIsInTheCatalog() throws Exception {
    var catalog = ControlCatalog.load();
    var named = Arrays.stream(Controls.class.getDeclaredFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
        .map(f -> {
          try {
            return (String) f.get(null);
          } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
          }
        })
        .toList();

    assertThat(named).isNotEmpty();
    assertThat(named).allSatisfy(id -> assertThat(catalog.find(id)).as(id).isPresent());
  }

  @Test
  public void everyControlInTheCatalogIsNamedByTheCode() {
    var named = Arrays.stream(Controls.class.getDeclaredFields()).map(f -> {
      try {
        return (String) f.get(null);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }).toList();

    assertThat(ControlCatalog.load().controls()).extracting(ControlDef::rowId).containsExactlyInAnyOrderElementsOf(named);
  }

  @Test
  public void humanControlsCarryTheirPolicy() {
    var control = ControlCatalog.load().require(Controls.ACTION_APPROVAL);

    assertThat(control.mechanism()).isEqualTo(Mechanism.HUMAN);
    assertThat(control.human().rationaleRequired()).isTrue();
    assertThat(control.human().independence()).containsExactly("proposer");
    assertThat(control.human().allowedActions()).contains("APPROVE", "DENY");
  }

  @Test
  public void theDigestIsStableAndIgnoresRowOrder() {
    var a = parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call, boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded}
          - {row_id: custom-b, name: B, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call, boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded}
        """);
    var reordered = parse("""
        rows:
          - {mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call, boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded, name: B, row_id: custom-b}
          - {row_id: custom-a, name: A, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call, boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded}
        """);
    var edited = parse("""
        rows:
          - {row_id: custom-a, name: A changed, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call, boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded}
          - {row_id: custom-b, name: B, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call, boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded}
        """);

    assertThat(a.digest()).startsWith("sha256:").isEqualTo(reordered.digest()).isNotEqualTo(edited.digest());
  }

  @Test
  public void everyProblemIsReportedAtOnce() {
    assertThatThrownBy(() -> parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: human, proof_event: HumanDecisionRecorded}
          - {row_id: custom-a, name: A again, mechanism: gate, proof_event: X}
          - {row_id: custom-c, mechanism: gate}
        """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("custom-a is a human control without human_config")
        .hasMessageContaining("custom-a appears twice")
        .hasMessageContaining("custom-c has no name")
        .hasMessageContaining("custom-c names no proof event");
  }

  @Test
  public void aSlaThatIsNotADurationIsRefused() {
    assertThatThrownBy(() -> parse("""
        rows:
          - row_id: custom-a
            name: A
            mechanism: human
            proof_event: HumanDecisionRecorded
            human_config: {roles: [reviewer], allowed_actions: [APPROVE], required: 1, sla_target: soon}
        """))
        .hasMessageContaining("sla_target that is not an ISO-8601 duration");
  }

  @Test
  public void everyControlHasOneOfItsMechanismsFlavors() {
    var catalog = ControlCatalog.load();

    assertThat(catalog.controls()).hasSize(57);
    assertThat(catalog.controls()).allSatisfy(c -> {
      assertThat(c.mechanism().flavors()).as(c.rowId()).contains(c.flavor());
      assertThat(c.proofEvent()).as(c.rowId()).isEqualTo(c.mechanism().proofEvent());
    });
    assertThat(catalog.controls().stream().filter(c -> c.mechanism() == Mechanism.GATE))
        .extracting(ControlDef::rowId).containsExactlyInAnyOrder(Controls.CATALOG_VALID_AT_STARTUP, Controls.DRAFT_MATCHES_BUNDLE);
    assertThat(catalog.require(Controls.DRAFT_MATCHES_BUNDLE).gateConfig().runsAt()).containsExactly("pre-release", "at-deployment");
  }

  @Test
  public void aFlavorOfAnotherMechanismIsRefused() {
    assertThatThrownBy(() -> parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: guardrail, flavor: sanitizer-pii, hook: before-tool-call,
             boundary: BEFORE_TOOL_CALL, proof_event: GuardrailDecisionRecorded}
        """))
        .hasMessageContaining("custom-a has flavor sanitizer-pii, which is not a guardrail flavor");
  }

  @Test
  public void aGuardrailNamesOneOfTheSixHooksAndItsBoundary() {
    assertThatThrownBy(() -> parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: guardrail, flavor: guardrail-tool-permission, hook: on-data-intake,
             boundary: ON_DATA_INTAKE, proof_event: GuardrailDecisionRecorded}
          - {row_id: custom-b, name: B, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call,
             boundary: WORKFLOW_STEP, proof_event: GuardrailDecisionRecorded}
        """))
        .hasMessageContaining("custom-a is a guardrail without one of the six hooks")
        .hasMessageContaining("custom-b fires at before-tool-call but names the boundary WORKFLOW_STEP");
  }

  @Test
  public void aControlIsProvedByItsMechanismsEvent() {
    assertThatThrownBy(() -> parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: guardrail, flavor: guardrail-tool-permission, hook: before-tool-call,
             boundary: BEFORE_TOOL_CALL, proof_event: HumanDecisionRecorded}
        """))
        .hasMessageContaining("custom-a is a guardrail control, proved by GuardrailDecisionRecorded, not HumanDecisionRecorded");
  }

  @Test
  public void aGateSaysWhatItChecksAndWhenItRuns() {
    assertThatThrownBy(() -> parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: gate, flavor: gate-test, boundary: AT_DEPLOYMENT, proof_event: GateEvaluated}
          - row_id: custom-b
            name: B
            mechanism: gate
            flavor: gate-test
            proof_event: GateEvaluated
            gate_config: {document_kind: k, check_intent: c, fails_when: f, runs_at: [at-startup]}
        """))
        .hasMessageContaining("custom-a is a gate without document_kind, check_intent and fails_when")
        .hasMessageContaining("custom-b is a gate whose runs_at is not pre-release or at-deployment");
  }

  @Test
  public void aSanitizerAndAnEvalCarryTheirConfiguration() {
    assertThatThrownBy(() -> parse("""
        rows:
          - {row_id: custom-a, name: A, mechanism: sanitizer, flavor: sanitizer-pii, proof_event: SanitizerApplied}
          - {row_id: custom-b, name: B, mechanism: eval, flavor: eval-on-release, proof_event: EvaluationRecorded,
             eval_config: {check_intent: c, on_finding: f}}
        """))
        .hasMessageContaining("custom-a is a sanitizer without data classes")
        .hasMessageContaining("custom-b is an eval with neither a trigger_event nor a frequency");
  }
}
