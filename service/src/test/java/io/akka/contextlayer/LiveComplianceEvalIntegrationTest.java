package io.akka.contextlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import akka.javasdk.testkit.TestKit;
import io.akka.contextlayer.api.GovernanceEndpoint;
import io.akka.contextlayer.application.ComplianceAnswerer;
import io.akka.contextlayer.governance.Controls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How well a real model chooses which fixed question an auditor asked, and whether what the second model writes survives the check
 * against the facts. It calls the model, so it runs only when asked: set {@code GOOGLE_AI_GEMINI_API_KEY} and pass
 * {@code -Dlive.model=true}.
 */
public class LiveComplianceEvalIntegrationTest extends LayerTestBase {

  private static final String MODEL = System.getProperty("live.model.name", "gemini-2.5-flash");

  private record Case(String id, String question, Set<String> intents, String control, boolean unplaced) {}

  private static Case ask(String id, String question, String control, String... intents) {
    return new Case(id, question, Set.of(intents), control, false);
  }

  private static final List<Case> CASES = List.of(
      ask("c01", "Where do we stand on approving playbook actions?", Controls.ACTION_APPROVAL, "CONTROL_STATUS"),
      ask("c02", "Can a person override what the playbook does, and who signed that off?", Controls.ACTION_APPROVAL, "CONTROL_STATUS", "HUMAN_DECISIONS"),
      ask("c03", "What did people decide about identity links in the last 30 days?", Controls.IDENTITY_REVIEW, "HUMAN_DECISIONS"),
      ask("c04", "What evidence do we have that personal data is masked before a model is called?", Controls.MASK_PERSONAL_DATA, "EVIDENCE_FOR_CONTROL", "CONTROL_STATUS"),
      ask("c05", "Which of our controls have nobody signed off or are missing documents?", "", "GAPS"),
      ask("c06", "Could someone alter our evidence without us noticing?", "", "INTEGRITY"),
      ask("c07", "Is the halt control actually working?", Controls.HALT, "CONTROL_STATUS", "EVIDENCE_FOR_CONTROL"),
      ask("c08", "Show me who approved connector go-lives this quarter.", Controls.PACK_ACTIVATION, "HUMAN_DECISIONS", "EVIDENCE_FOR_CONTROL"),
      ask("c09", "Where are the gaps in our compliance picture?", "", "GAPS"),
      ask("c10", "Is our evidence tamper-evident?", "", "INTEGRITY"),
      new Case("n01", "What is the weather in Boston?", Set.of(), "", true),
      new Case("n02", "Write me a poem about governance.", Set.of(), "", true));

  @Override
  protected TestKit.Settings testKitSettings() {
    return baseSettings().withAdditionalConfig("""
        akka.javasdk.agent.model-provider = googleai-gemini
        akka.javasdk.agent.googleai-gemini {
          model-name = "%s"
        }
        """.formatted(MODEL));
  }

  private void say(String org, String who, String role, String path, Object body) {
    wire().POST("/orgs/" + org + "/governance/controls/" + Controls.ACTION_APPROVAL + path)
        .addHeader("Authorization", Tokens.bearer(who, org, role)).withRequestBody(body).invoke();
  }

  @Test
  public void aRealModelChoosesTheRightQuestionAndItsWordsSurviveTheCheck() throws Exception {
    assumeTrue(System.getenv("GOOGLE_AI_GEMINI_API_KEY") != null && "true".equals(System.getProperty("live.model")),
        "set GOOGLE_AI_GEMINI_API_KEY and -Dlive.model=true to call a real model");
    var org = newOrg();
    say(org, "dana", "approver", "/acceptance", new GovernanceEndpoint.AcceptRequest("the owner takes this on"));
    say(org, "erin", "admin", "/assertion", new GovernanceEndpoint.AssertRequest("HumanDecisionRecorded",
        List.of(new GovernanceEndpoint.ExpectationView("override procedure", 365)), "human reviews are the evidence"));

    var rows = new ArrayList<Map<String, Object>>();
    int placedRight = 0, placed = 0, declined = 0, negatives = 0, checked = 0, answered = 0, overclaims = 0;
    for (var c : CASES) {
      var answer = post("auditor", "/orgs/" + org + "/governance/ask", new GovernanceEndpoint.AskRequest(c.question()), ComplianceAnswerer.Response.class);
      boolean ok;
      if (c.unplaced()) {
        negatives++;
        ok = !answer.clarification().isBlank();
        if (ok) declined++;
      } else {
        placed++;
        ok = c.intents().contains(answer.intent()) && (c.control().isEmpty() || c.control().equals(answer.controlId()));
        if (ok) placedRight++;
        if (!answer.intent().isEmpty()) {
          answered++;
          if (answer.summaryChecked()) checked++;
          if (answer.summary().toLowerCase().contains("fully compliant")) overclaims++;
        }
      }
      var row = new LinkedHashMap<String, Object>();
      row.put("id", c.id());
      row.put("question", c.question());
      row.put("ok", ok);
      row.put("intent", answer.intent());
      row.put("controlId", answer.controlId());
      row.put("summaryChecked", answer.summaryChecked());
      row.put("unsupported", answer.unsupported());
      row.put("clarification", answer.clarification());
      rows.add(row);
    }
    var summary = new LinkedHashMap<String, Object>();
    summary.put("model", MODEL);
    summary.put("questions", placed);
    summary.put("placedRight", placedRight);
    summary.put("accuracy", (double) placedRight / placed);
    summary.put("negatives", negatives);
    summary.put("negativesDeclined", declined);
    summary.put("answered", answered);
    summary.put("wordingSurvivedTheCheck", checked);
    summary.put("fullyCompliantInASummary", overclaims);
    summary.put("results", rows);
    Files.writeString(Path.of("target", "live-compliance-eval.json"),
        akka.javasdk.JsonSupport.getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    System.out.println("LIVE COMPLIANCE EVAL: model=" + MODEL + " right=" + placedRight + "/" + placed + " declined=" + declined + "/" + negatives
        + " wordingSurvived=" + checked + "/" + answered + " overclaims=" + overclaims);

    assertThat(overclaims).as("no summary may call the system fully compliant").isZero();
    assertThat((double) placedRight / placed).as("questions placed correctly").isGreaterThanOrEqualTo(0.8);
    assertThat(declined).as("questions that are not about the controls are handed back").isEqualTo(negatives);
  }
}
