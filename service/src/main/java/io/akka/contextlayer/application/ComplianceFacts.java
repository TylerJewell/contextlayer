package io.akka.contextlayer.application;

import akka.javasdk.client.ComponentClient;
import io.akka.contextlayer.domain.ScopedId;
import io.akka.contextlayer.governance.ComplianceAnswer.Fact;
import io.akka.contextlayer.governance.ComplianceAnswer.Facts;
import io.akka.contextlayer.governance.ComplianceAnswer.Intent;
import io.akka.contextlayer.governance.Compliance;
import io.akka.contextlayer.governance.ControlCatalog;
import io.akka.contextlayer.governance.ControlClaims;
import io.akka.contextlayer.governance.ControlDef;
import io.akka.contextlayer.governance.EvaluationMode;
import io.akka.contextlayer.governance.Integrity;
import io.akka.contextlayer.governance.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Computes, from what the service recorded, the facts that answer one kind of question. Everything here is a
 * fixed template over recorded data: a sentence is written only for something that exists, and what is
 * absent is said as a limit, so an empty result is stated and never left to be inferred.
 */
public class ComplianceFacts {

  private static final int MAX_ROWS = 1000;
  private static final int MAX_LISTED = 5;
  private static final int MAX_LINKS_CHECKED = 1000;
  /** Records of who said what about a control are claims, not evidence that it ran. */
  private static final Set<String> CLAIM_EVENTS =
      Set.of("ControlAccepted", "ControlAcceptanceWithdrawn", "EvidenceAsserted", "EvidenceAttached");

  private final ComponentClient componentClient;
  private final ControlCatalog catalog;

  public ComplianceFacts(ComponentClient componentClient, ControlCatalog catalog) {
    this.componentClient = componentClient;
    this.catalog = catalog;
  }

  public Facts of(String project, Intent intent, String controlId, int periodDays, long now) {
    return switch (intent) {
      case CONTROL_STATUS -> controlStatus(project, catalog.require(controlId), periodDays, now);
      case EVIDENCE_FOR_CONTROL -> evidenceFor(project, catalog.require(controlId), periodDays, now);
      case HUMAN_DECISIONS -> humanDecisions(project, catalog.require(controlId), periodDays, now);
      case GAPS -> gaps(project, periodDays, now);
      case INTEGRITY -> integrity(project);
    };
  }

  // ---- control status

  private Facts controlStatus(String project, ControlDef control, int days, long now) {
    var facts = new ArrayList<Fact>();
    var limits = new ArrayList<String>();
    var ref = "control:" + control.rowId();
    facts.add(new Fact("Control " + control.rowId() + " (" + control.name() + ") is a " + control.mechanism().name().toLowerCase()
        + " control that acts at " + control.boundary() + " and is proved by " + control.proofEvent() + " records.", List.of(ref)));

    var rows = evidenceRows(project, control.rowId(), now - Duration.ofDays(days).toMillis());
    var items = rows.stream().map(r -> new Compliance.Item(EvaluationMode.valueOf(r.mode()), Verdict.valueOf(r.verdict()), r.flagged(), r.at())).toList();
    var waiver = WaiverEntity.asWaiver(componentClient.forKeyValueEntity(ScopedId.of(project, "waiver", control.rowId()))
        .method(WaiverEntity::get).invoke());
    var standing = Compliance.assess(items, waiver, Duration.ofDays(7), now);
    facts.add(new Fact("Its standing is " + standing.status() + ": " + standing.why() + ". In the last " + days + " days it ran "
        + standing.enforcing() + " time(s) as an enforcing control: " + standing.upheld() + " upheld, " + standing.breached()
        + " breached, " + standing.flagged() + " flagged.", List.of(ref)));
    if (standing.enforcing() == 0) limits.add("No enforcing record of this control exists in the last " + days + " days, so nothing shows it working.");

    var claims = claims(project, control.rowId());
    claimFacts(project, claims, now, ref, facts, limits);
    return new Facts(Intent.CONTROL_STATUS, control.rowId(), days, facts, limits);
  }

  private void claimFacts(String project, ControlClaims claims, long now, String ref, List<Fact> facts, List<String> limits) {
    if (claims.accepted()) {
      var a = claims.acceptance();
      facts.add(new Fact("It was accepted by " + a.by() + " on " + Instant.ofEpochMilli(a.at()).toString().substring(0, 10)
          + (claims.acceptanceCurrent(catalog.digest()) ? "" : ", for an earlier version of the catalog") + ". The reason given: " + a.rationale(),
          List.of(ref, "person:" + a.by())));
    } else {
      limits.add("Nobody has accepted this control for the project.");
    }
    if (claims.asserted()) {
      var a = claims.assertion();
      facts.add(new Fact("Revision " + a.revision() + " of the assertion about its evidence was made by " + a.by() + ": " + a.evidenceKind()
          + " records count as evidence. The reason given: " + a.rationale(), List.of(ref, "person:" + a.by())));
      var coverage = Documents.coverage(componentClient, project, claims, now);
      facts.add(new Fact("Coverage of that assertion is " + coverage.coverage() + ".", List.of(ref)));
      coverage.unmet().forEach(u -> limits.add("Still needed: " + u + "."));
    } else {
      limits.add("Nobody has said which kind of record counts as evidence for this control.");
    }
  }

  // ---- evidence

  private Facts evidenceFor(String project, ControlDef control, int days, long now) {
    var facts = new ArrayList<Fact>();
    var limits = new ArrayList<String>();
    var ref = "control:" + control.rowId();
    var rows = evidenceRows(project, control.rowId(), now - Duration.ofDays(days).toMillis());
    if (rows.isEmpty()) {
      limits.add("No evidence was recorded for control " + control.rowId() + " in the last " + days + " days.");
    } else {
      var byDecision = new TreeMap<String, Long>();
      rows.forEach(r -> byDecision.merge(r.decision(), 1L, Long::sum));
      facts.add(new Fact("There are " + rows.size() + " evidence records for control " + control.rowId() + " (" + control.name()
          + ") in the last " + days + " days: " + byDecision.entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
              .collect(Collectors.joining(", ")) + ".", List.of(ref)));
      for (var r : rows.stream().limit(MAX_LISTED).toList()) {
        facts.add(new Fact("Record " + r.evidenceId() + " on " + Instant.ofEpochMilli(r.at()) + ": decision " + r.decision() + ", consequence "
            + r.consequence() + ", by " + r.actor() + ", " + r.mode().toLowerCase() + " mode.", List.of(ref, "evidence:" + r.evidenceId())));
      }
      if (rows.size() > MAX_LISTED) limits.add("Only the " + MAX_LISTED + " most recent of the " + rows.size() + " records are listed.");
    }
    facts.addAll(ledgerFacts(project, limits));
    return new Facts(Intent.EVIDENCE_FOR_CONTROL, control.rowId(), days, facts, limits);
  }

  private Facts humanDecisions(String project, ControlDef control, int days, long now) {
    var facts = new ArrayList<Fact>();
    var limits = new ArrayList<String>();
    var ref = "control:" + control.rowId();
    var rows = evidenceRows(project, control.rowId(), now - Duration.ofDays(days).toMillis()).stream()
        .filter(r -> r.eventType().equals("HumanDecisionRecorded")).toList();
    if (rows.isEmpty()) {
      limits.add("No decision by a person was recorded under control " + control.rowId() + " in the last " + days + " days.");
    } else {
      var byDecision = new TreeMap<String, Long>();
      var byPerson = new TreeMap<String, Long>();
      rows.forEach(r -> {
        byDecision.merge(r.decision(), 1L, Long::sum);
        byPerson.merge(r.actor(), 1L, Long::sum);
      });
      long rushed = rows.stream().filter(r -> r.flagged()).count();
      facts.add(new Fact("People made " + rows.size() + " decision(s) under control " + control.rowId() + " in the last " + days + " days: "
          + byDecision.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", ")) + ".", List.of(ref)));
      facts.add(new Fact("They were made by " + byPerson.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
          .collect(Collectors.joining(", ")) + ".", byPerson.keySet().stream().map(p -> "person:" + p).toList()));
      facts.add(new Fact(rushed + " of them were flagged as made faster than the control expects a review to take.", List.of(ref)));
    }
    limits.add("The reasons people gave are held with the human events and are not repeated here.");
    facts.addAll(ledgerFacts(project, limits));
    return new Facts(Intent.HUMAN_DECISIONS, control.rowId(), days, facts, limits);
  }

  // ---- gaps and integrity

  private Facts gaps(String project, int days, long now) {
    var unaccepted = new ArrayList<String>();
    var notAsserted = new ArrayList<String>();
    var noEvidence = new ArrayList<String>();
    var partial = new ArrayList<String>();
    for (var control : catalog.controls()) {
      var claims = claims(project, control.rowId());
      var rows = evidenceRows(project, control.rowId(), now - Duration.ofDays(days).toMillis());
      boolean enforcing = rows.stream().anyMatch(r -> r.mode().equals("ENFORCING"));
      if (!claims.accepted() || !claims.acceptanceCurrent(catalog.digest())) unaccepted.add(control.rowId());
      if (claims.accepted() && !claims.asserted()) notAsserted.add(control.rowId());
      if (claims.asserted() && !enforcing) noEvidence.add(control.rowId());
      if (claims.asserted() && Documents.coverage(componentClient, project, claims, now).coverage() != ControlClaims.Coverage.FULL) partial.add(control.rowId());
    }
    var facts = new ArrayList<Fact>();
    var limits = new ArrayList<String>();
    facts.add(new Fact("The catalog has " + catalog.controls().size() + " controls.", List.of()));
    list("not accepted for the project, or accepted for an earlier version of the catalog", unaccepted, facts);
    list("accepted but with nothing asserted about what counts as evidence", notAsserted, facts);
    list("asserted but with no enforcing record in the last " + days + " days", noEvidence, facts);
    list("asserted but with expected documents missing or too old", partial, facts);
    if (facts.size() == 1) limits.add("No control falls between the claims in the way the checks above look for.");
    limits.add("These checks look at the claims and at enforcing records. They do not judge whether a control is the right one.");
    return new Facts(Intent.GAPS, "", days, facts, limits);
  }

  private void list(String what, List<String> ids, List<Fact> facts) {
    if (ids.isEmpty()) return;
    facts.add(new Fact(ids.size() + " control(s) are " + what + ": " + String.join(", ", ids) + ".",
        ids.stream().map(i -> "control:" + i).toList()));
  }

  private Facts integrity(String project) {
    var facts = new ArrayList<Fact>();
    var limits = new ArrayList<String>();
    facts.add(new Fact("The evidence ledger is HashChained: each record carries a digest chained to the one before it.", List.of("store:evidence-ledger")));
    facts.add(new Fact("The human events, the control claims and the entity graph are NoIntegrity stores: they have no digest. Each decision and "
        + "each claim is copied into the evidence ledger.", List.of("store:human-events", "store:control-claims", "store:entity-graph")));
    facts.addAll(ledgerFacts(project, limits));
    limits.add("Someone who can rewrite the whole ledger and its head together would not be detected, unless the head was kept somewhere they cannot write.");
    return new Facts(Intent.INTEGRITY, "", 0, facts, limits);
  }

  private List<Fact> ledgerFacts(String project, List<String> limits) {
    var chain = componentClient.forEventSourcedEntity(project).method(IntegrityChainEntity::get).invoke();
    if (chain.count() == 0) {
      limits.add("The evidence ledger holds no records yet, so there is nothing to check.");
      return List.of();
    }
    long from = Math.max(1, chain.count() - MAX_LINKS_CHECKED + 1);
    var result = Integrity.check(from, MAX_LINKS_CHECKED, chain.count(),
        seq -> Optional.ofNullable(componentClient.forEventSourcedEntity(IntegrityConsumer.linkId(project, seq)).method(ChainLinkEntity::get).invoke().link()),
        id -> Optional.ofNullable(componentClient.forEventSourcedEntity(id).method(EvidenceEntity::get).invoke().evidence()));
    var text = "The evidence ledger holds " + chain.count() + " records. A check of " + result.checked() + " of them, the most recent, found "
        + (result.intact() ? "none altered." : "it NOT intact: " + String.join("; ", result.problems()));
    if (from > 1) limits.add("Records before position " + from + " were not checked in this answer.");
    return List.of(new Fact(text, List.of("store:evidence-ledger")));
  }

  // ---- reading

  private List<EvidenceView.Row> evidenceRows(String project, String controlId, long since) {
    return componentClient.forView().method(EvidenceView::byControl)
        .invoke(new EvidenceView.ByControl(project, controlId, since, MAX_ROWS)).items().stream()
        .filter(r -> !r.eventType().startsWith("ControlWaiver") && !r.eventType().equals("ControlWaived") && !CLAIM_EVENTS.contains(r.eventType()))
        .toList();
  }

  private ControlClaims claims(String project, String controlId) {
    return componentClient.forEventSourcedEntity(ScopedId.of(project, "claims", controlId)).method(ControlClaimsEntity::get).invoke();
  }
}
