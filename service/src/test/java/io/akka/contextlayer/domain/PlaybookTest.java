package io.akka.contextlayer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaybookTest {

  /** Judge how badly the client is affected, then note it and, if blocked, request an action. */
  static final String VALID = """
      {"steps":[
        {"id":"judge","type":"classify","instruction":"How badly is the client affected?",
         "fields":["Claim.daysOpen"],"labels":["BLOCKED","DEGRADED","MINOR"],"store":"impact","next":"route"},
        {"id":"route","type":"branch","var":"impact","cases":{"BLOCKED":"attach","DEGRADED":"note"},"otherwise":"end"},
        {"id":"attach","type":"act","actionType":"record.update","payload":"claim {Claim.claimId} is {impact}","next":"note"},
        {"id":"note","type":"annotate","text":"impact judged {impact}","next":"end"}]}""";

  @Test
  void aWellFormedPlaybookParsesAndKnowsItsFirstStep() {
    var playbook = Playbook.parse(VALID);
    assertThat(playbook.steps()).hasSize(4);
    assertThat(playbook.first()).isEqualTo("judge");
    assertThat(playbook.step("route").cases()).containsEntry("BLOCKED", "attach");
  }

  @Test
  void anUnknownFieldIsRefusedSoATypoCannotSilentlyDisableAControl() {
    var typo = VALID.replace("\"fields\"", "\"feilds\"");
    assertThatThrownBy(() -> Playbook.parse(typo)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void textThatIsNotAPlaybookIsRefused() {
    assertThatThrownBy(() -> Playbook.parse("playbook v1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Playbook.parse("{\"steps\":[]}")).hasMessageContaining("at least one step");
  }

  @Test
  void aStepsMustNameWhatFollowsAndOnlyForward() {
    var dangling = VALID.replace("\"next\":\"note\"", "\"next\":\"nowhere\"");
    assertThatThrownBy(() -> Playbook.parse(dangling)).hasMessageContaining("must name a step");

    var loop = VALID.replace("\"text\":\"impact judged {impact}\",\"next\":\"end\"",
        "\"text\":\"impact judged {impact}\",\"next\":\"judge\"");
    assertThatThrownBy(() -> Playbook.parse(loop)).hasMessageContaining("loop");
  }

  @Test
  void aClassifyStepMustLimitWhatTheModelSeesAndWhatItMayAnswer() {
    var noFields = VALID.replace("\"fields\":[\"Claim.daysOpen\"],", "");
    assertThatThrownBy(() -> Playbook.parse(noFields)).hasMessageContaining("fields");

    var oneLabel = VALID.replace("[\"BLOCKED\",\"DEGRADED\",\"MINOR\"]", "[\"BLOCKED\"]");
    assertThatThrownBy(() -> Playbook.parse(oneLabel)).hasMessageContaining("labels");

    var lowercase = VALID.replace("\"MINOR\"", "\"minor\"");
    assertThatThrownBy(() -> Playbook.parse(lowercase)).hasMessageContaining("labels");
  }

  @Test
  void duplicateIdsAndUnknownTypesAreReported() {
    var dup = VALID.replace("\"id\":\"note\"", "\"id\":\"judge\"");
    assertThatThrownBy(() -> Playbook.parse(dup)).hasMessageContaining("declared twice");

    var type = VALID.replace("\"type\":\"annotate\"", "\"type\":\"runScript\"");
    assertThatThrownBy(() -> Playbook.parse(type)).hasMessageContaining("type must be one of");
  }

  @Test
  void aPlaybookIsBounded() {
    var steps = new StringBuilder("{\"steps\":[");
    for (int i = 0; i <= Playbook.MAX_STEPS; i++) {
      if (i > 0) steps.append(',');
      steps.append("{\"id\":\"s").append(i).append("\",\"type\":\"annotate\",\"text\":\"x\",\"next\":\"end\"}");
    }
    steps.append("]}");
    assertThatThrownBy(() -> Playbook.parse(steps.toString())).hasMessageContaining("at most");
  }

  @Test
  void placeholdersAreFilledFromLabelsThenEvidence() {
    var text = Playbook.fill("claim {Claim.claimId} is {impact}", Map.of("Claim.claimId", "CL-889"),
        Map.of("impact", "BLOCKED"));
    assertThat(text).isEqualTo("claim CL-889 is BLOCKED");
  }

  @Test
  void aPlaceholderWithNoValueFailsInsteadOfSendingGuessedText() {
    assertThatThrownBy(() -> Playbook.fill("hello {Client.name}", Map.of(), Map.of()))
        .hasMessageContaining("Client.name");
  }

  @Test
  void regexCharactersInValuesAreNotInterpreted() {
    assertThat(Playbook.fill("{x}", Map.of("x", "$1 \\ cost"), Map.of())).isEqualTo("$1 \\ cost");
  }
}
