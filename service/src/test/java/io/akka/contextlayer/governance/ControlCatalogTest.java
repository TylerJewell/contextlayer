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
          - {row_id: custom-a, name: A, mechanism: gate, proof_event: ControlEvaluationRecorded}
          - {row_id: custom-b, name: B, mechanism: gate, proof_event: ControlEvaluationRecorded}
        """);
    var reordered = parse("""
        rows:
          - {proof_event: ControlEvaluationRecorded, mechanism: gate, name: B, row_id: custom-b}
          - {row_id: custom-a, name: A, mechanism: gate, proof_event: ControlEvaluationRecorded}
        """);
    var edited = parse("""
        rows:
          - {row_id: custom-a, name: A changed, mechanism: gate, proof_event: ControlEvaluationRecorded}
          - {row_id: custom-b, name: B, mechanism: gate, proof_event: ControlEvaluationRecorded}
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
}
