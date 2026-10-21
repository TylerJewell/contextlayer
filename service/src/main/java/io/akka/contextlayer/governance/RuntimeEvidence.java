package io.akka.contextlayer.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes what this service knows about a project as RDF Turtle in the Runtime Evidence vocabulary
 * (prefix {@code rte}), so it can be loaded into a graph that joins evidence from many systems to the
 * obligations it supports.
 *
 * <p>The export keeps the claims apart as the vocabulary does. A control becomes an obligation; the
 * project's catalog is the policy that binds it, and an acceptance is the attribution of that binding.
 * An assertion about what counts as evidence is its own node with its own author, derived from the
 * binding it rests on, and each revision points at the one before. A record is evidence only through
 * an assertion; the export states the records and the assertions and does not join them.
 *
 * <p>Each evidence record becomes an activity of the matching class, a record in the ledger with its
 * position in the chain and its digest, and the person or software that acted. Records of someone
 * accepting, asserting or attaching are left out: they are claims, not runtime activity.
 */
public final class RuntimeEvidence {
  private RuntimeEvidence() {}

  /** One chained evidence record: where it sits in the chain, and what it says. */
  public record Item(Integrity.Link link, Evidence evidence) {}

  public record Input(
      String project, ControlCatalog catalog, Map<String, ControlClaims> claims, List<Item> records, long chainCount,
      String chainHead, Map<String, String> documentAsOf) {}

  /** The activity class of each mechanism's proof event. */
  private static final Map<String, String> KIND_CLASS = Map.of(
      "HumanDecisionRecorded", "rte:HumanReview",
      "GuardrailDecisionRecorded", "rte:GuardrailIntervention",
      "SanitizerApplied", "rte:Sanitization",
      "EvaluationRecorded", "rte:ControlDecision",
      "GateEvaluated", "rte:ControlDecision");

  /** Guardrails that decide who may reach what are access decisions, the rest guardrail interventions. */
  private static final Set<String> ACCESS_CONTROLS = Set.of(Controls.DOMAIN_ACCESS, Controls.APPROVED_JOINS,
      Controls.CONFIRMED_IDENTITIES, Controls.PROJECT_ISOLATION, Controls.CALLER_AUTHORIZATION);

  public static String turtle(Input in) {
    var out = new StringBuilder(PREFIXES.replace("{project}", id(in.project())));
    var catalog = in.catalog();
    var proofEvents = new LinkedHashSet<String>();
    catalog.controls().forEach(c -> proofEvents.add(c.proofEvent()));
    var people = new LinkedHashMap<String, Boolean>();

    out.append("\n### The system and the policy it runs under\n\n");
    out.append("ex:system a ai:AISystem ;\n    skos:prefLabel ").append(lit("Project " + in.project())).append("@en ;\n")
        .append("    dpv:hasPolicy ex:policy .\n\n");
    out.append("ex:policy a dpv:Policy, prov:Plan ;\n    skos:prefLabel ").append(lit("Controls of " + catalog.systemId()))
        .append("@en ;\n    dct:identifier ").append(lit(catalog.digest())).append(" ;\n    rte:hasBinding ");
    out.append(String.join(", ", catalog.controls().stream().map(c -> "ex:binding-" + id(c.rowId())).toList())).append(" .\n");

    out.append("\n### Record stores\n\n");
    store(out, "evidence-ledger", Integrity.HASH_CHAINED, "Evidence ledger", in.chainCount(), in.chainHead());
    store(out, "human-events", Integrity.NO_INTEGRITY, "Human review events", -1, null);
    store(out, "control-claims", Integrity.NO_INTEGRITY, "Acceptances and assertions", -1, null);
    store(out, "trust-documents", Integrity.NO_INTEGRITY, "Trust documents", -1, null);
    store(out, "entity-graph", Integrity.NO_INTEGRITY, "Entity graph", -1, null);

    out.append("\n### Obligations, their sources and their bindings\n\n");
    var provisions = new LinkedHashSet<String>();
    for (var c : catalog.controls()) {
      var claims = in.claims().getOrDefault(c.rowId(), ControlClaims.empty());
      var coverage = claims.coverage(System.currentTimeMillis(), name -> java.util.Optional.ofNullable(in.documentAsOf().get(name)));
      if (c.corpusRef() != null && !c.corpusRef().isBlank() && provisions.add(c.corpusRef())) {
        out.append("ex:prov-").append(id(c.corpusRef())).append(" a rte:Provision ;\n    skos:prefLabel ").append(lit(c.corpusRef())).append("@en .\n");
      }
      out.append("ex:obl-").append(id(c.rowId())).append(" a dpv:Obligation ;\n    skos:prefLabel ").append(lit(c.name())).append("@en ;\n    skos:definition ")
          .append(lit(c.summary() == null ? "" : c.summary().strip())).append("@en");
      if (c.corpusRef() != null && !c.corpusRef().isBlank()) out.append(" ;\n    prov:wasDerivedFrom ex:prov-").append(id(c.corpusRef()));
      out.append(" .\n");
      out.append("ex:binding-").append(id(c.rowId())).append(" a rte:ObligationBinding ;\n    rte:boundObligation ex:obl-").append(id(c.rowId()));
      var acceptance = claims.acceptance();
      if (acceptance != null) {
        people.putIfAbsent(acceptance.by(), true);
        out.append(" ;\n    prov:wasAttributedTo ").append(person(acceptance.by())).append(" ;\n    dct:issued ")
            .append(date(acceptance.at())).append(" ;\n    dct:identifier ").append(lit(acceptance.catalogDigest()));
        if (!acceptance.catalogDigest().equals(catalog.digest())) {
          out.append(" ;\n    skos:scopeNote ").append(lit("Accepted at an earlier version of the catalog.")).append("@en");
        }
      }
      out.append(" .\n");
      assertions(out, c, claims, coverage, people);
    }

    out.append("\n### Runtime records\n\n");
    for (var item : in.records()) {
      var e = item.evidence();
      if (!proofEvents.contains(e.eventType())) continue;
      people.putIfAbsent(e.actor(), !e.actor().startsWith("system:"));
      activity(out, in, item);
    }

    out.append("\n### People and software that acted or said\n\n");
    for (var entry : people.entrySet()) {
      out.append(person(entry.getKey())).append(entry.getValue() ? " a prov:Person" : " a prov:SoftwareAgent").append(" ;\n    skos:prefLabel ")
          .append(lit(entry.getKey())).append(" .\n");
    }
    return out.toString();
  }

  private static void store(StringBuilder out, String id, String scheme, String label, long records, String head) {
    out.append("ex:store-").append(id).append(" a rte:RecordStore ;\n    skos:prefLabel ").append(lit(label)).append("@en ;\n    rte:hasIntegrityScheme rte:")
        .append(scheme);
    if (head != null && !head.isEmpty()) out.append(" ;\n    dct:identifier ").append(lit(head));
    out.append(" .\n");
  }

  private static void assertions(
      StringBuilder out, ControlDef c, ControlClaims claims, ControlClaims.CoverageResult coverage, Map<String, Boolean> people) {
    var all = new ArrayList<>(claims.earlier());
    if (claims.assertion() != null) all.add(claims.assertion());
    for (int i = 0; i < all.size(); i++) {
      var a = all.get(i);
      people.putIfAbsent(a.by(), true);
      var node = "ex:evid-" + id(c.rowId()) + "-r" + a.revision();
      out.append(node).append(" a rte:EvidenceAssertion ;\n    rte:evidences ex:obl-").append(id(c.rowId())).append(" ;\n    rte:hasEvidenceKind ")
          .append(KIND_CLASS.getOrDefault(a.evidenceKind(), "rte:RuntimeActivity")).append(" ;\n    rte:appliesToSystem ex:system ;\n    prov:wasAttributedTo ")
          .append(person(a.by()));
      if (!a.basisDigest().isEmpty()) out.append(" ;\n    prov:wasDerivedFrom ex:binding-").append(id(c.rowId()));
      if (i > 0) out.append(" ;\n    prov:wasRevisionOf ex:evid-").append(id(c.rowId())).append("-r").append(all.get(i - 1).revision());
      if (i == all.size() - 1) {
        out.append(" ;\n    rte:hasCoverage rte:").append(coverage.coverage() == ControlClaims.Coverage.FULL ? "FullCoverage" : "PartialCoverage");
        for (var unmet : coverage.unmet()) {
          out.append(" ;\n    skos:scopeNote ").append(lit("Still needed: " + unmet)).append("@en");
        }
      }
      out.append(" .\n");
    }
  }

  private static void activity(StringBuilder out, Input in, Item item) {
    var e = item.evidence();
    var activity = "ex:act-" + id(e.evidenceId());
    out.append(activity).append(" a ").append(activityClass(e)).append(" ;\n    rte:withinSystem ex:system ;\n    rte:ruleId ").append(lit(e.controlId()))
        .append(" ;\n    prov:qualifiedAssociation [ a prov:Association ; prov:agent ").append(person(e.actor())).append(" ; prov:hadPlan ex:policy ]")
        .append(" ;\n    prov:wasAssociatedWith ").append(person(e.actor()));
    var outcome = outcome(e);
    if (outcome != null) out.append(" ;\n    rte:hasOutcome rte:").append(outcome);
    if (e.reasonCode() != null && !e.reasonCode().isBlank()) out.append(" ;\n    rte:outcomeReason ").append(lit(e.reasonCode()));
    var point = interventionPoint(e);
    if (point != null) out.append(" ;\n    rte:atInterventionPoint rte:").append(point);
    out.append(" ;\n    prov:endedAtTime \"").append(Instant.ofEpochMilli(e.at())).append("\"^^xsd:dateTime .\n");
    out.append("ex:rec-").append(id(e.evidenceId())).append(" a rte:RuntimeRecord ;\n    prov:wasGeneratedBy ").append(activity)
        .append(" ;\n    rte:recordedIn ex:store-evidence-ledger ;\n    rte:streamId ").append(lit(in.project())).append(" ;\n    rte:streamPosition ")
        .append(item.link().seq()).append(" ;\n    rte:recordDigest ").append(lit(item.link().recordDigest())).append(" .\n");
  }

  static String activityClass(Evidence e) {
    if (e.controlId().equals(Controls.HALT)) return "rte:SystemHalt";
    if (ACCESS_CONTROLS.contains(e.controlId())) return "rte:AccessDecision";
    return KIND_CLASS.getOrDefault(e.eventType(), "rte:RuntimeActivity");
  }

  static String outcome(Evidence e) {
    var decision = e.decision() == null ? "" : e.decision();
    if (e.controlId().equals(Controls.HALT)) return decision.equals("APPROVE") ? "Halted" : null;
    if (e.eventType().equals("SanitizerApplied")) {
      return Set.of("MASKED", "REDACTED").contains(e.consequence()) ? "Transformed" : "Permit";
    }
    if (e.eventType().equals("HumanDecisionRecorded")) {
      return switch (decision) {
        case "APPROVE" -> "Approved";
        case "DENY", "REJECT" -> "Rejected";
        default -> null;
      };
    }
    return switch (decision) {
      case "PASS", "ALLOW" -> "Permit";
      case "BLOCK", "DENY" -> "Deny";
      case "ERROR", "FAIL" -> "Indeterminate";
      default -> null;
    };
  }

  static String interventionPoint(Evidence e) {
    if (e.eventType().equals("HumanDecisionRecorded")) return null;
    return switch (e.boundary()) {
      case "BEFORE_LLM_CALL" -> "BeforeModelCall";
      case "AFTER_LLM_RESPONSE" -> "AfterModelResponse";
      case "BEFORE_TOOL_CALL" -> "BeforeToolCall";
      default -> null;
    };
  }

  // ---- Turtle

  /** A name that is safe as the local part of a prefixed name. */
  static String id(String text) {
    var out = new StringBuilder();
    for (char c : text.toCharArray()) out.append(Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_' ? c : '_');
    return out.toString();
  }

  private static String person(String subject) {
    return "ex:agent-" + id(subject);
  }

  private static String lit(String text) {
    var out = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }
    return out.append('"').toString();
  }

  private static String date(long epochMillis) {
    return "\"" + Instant.ofEpochMilli(epochMillis).toString().substring(0, 10) + "\"^^xsd:date";
  }

  private static final String PREFIXES = """
      @prefix rte:  <https://w3id.org/rte#> .
      @prefix ex:   <urn:contextlayer:{project}:> .
      @prefix prov: <http://www.w3.org/ns/prov#> .
      @prefix dpv:  <https://w3id.org/dpv#> .
      @prefix ai:   <https://w3id.org/dpv/ai#> .
      @prefix dct:  <http://purl.org/dc/terms/> .
      @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
      @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
      """;
}
