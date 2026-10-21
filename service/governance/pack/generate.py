"""Writes the control pack of the system contextlayer from its controls draft.

    python governance/pack/generate.py            (from service/)

The pack is Verify's form of the same controls: pack.yaml selects the corpus controls the system meets, and
controls-original.yaml holds each of the system's controls as a pack-original control with one mechanism and
flavor. The draft (governance/controls.yaml) is the source; run this after changing it. ControlPackTest fails when
the pack and the draft disagree.
"""
import pathlib
import yaml

HERE = pathlib.Path(__file__).resolve().parent
DRAFT = HERE.parent / "controls.yaml"
PACK_ID = "contextlayer-governed-context"
EFFECTIVE = "2026-10-21"

OBLIGATION = {"guardrail": "restrict", "sanitizer": "restrict", "human": "operate", "eval": "operate", "gate": "gate"}
CHAPTER = {"sanitizer": "Sanitizers", "guardrail": "Guardrails", "human": "Human oversight", "eval": "Evals",
           "gate": "Gates"}


def pack_control_id(row):
    return f"{PACK_ID}.{row['mechanism']}.{row['row_id'].removeprefix('custom-')}"


def trigger(row):
    if row["mechanism"] == "gate":
        return "before-deployment"
    if row["mechanism"] == "human" and row["human_config"]["blocking"]:
        return "before-automated-decision"
    return "continuous"


def runtime(row):
    mechanism = {"kind": row["mechanism"], "flavor": row["flavor"]}
    if row["mechanism"] == "guardrail":
        mechanism["hook"] = row["hook"]
    out = {
        "enforcement_locus": "partially-akka",
        "mechanisms": [mechanism],
        "evidence_required": [{"type": "aud-event", "name": row["proof_event"],
                               "content": f"a verdict recorded under control id {row['row_id']}"}],
    }
    if row["mechanism"] == "guardrail":
        hook = row["hook"]
        out["guardrail_config"] = {
            "boundary_type": "model_call" if "llm" in hook else "tool_call",
            "direction": "input" if hook.startswith("before") else "output",
            "target": row["summary"].split(".")[0],
            "checks_for": row["name"],
            "block_when": row["summary"],
            "fail_mode": "fail-open" if row["row_id"] == "custom-cl0010" else "fail-closed",
            "emits_on_block": f"{row['proof_event']} with decision BLOCK under {row['row_id']}",
            "values_tested": "what the call or request carries, as named in the verdict's subject",
            "test_methods": "service unit and TestKit integration tests that assert the PASS and BLOCK verdicts",
            "possible_decisions": "PASS (continues) | BLOCK (refused and recorded) | ERROR -> fail-closed",
        }
    if row["mechanism"] == "sanitizer":
        out["sanitizer_config"] = dict(row["sanitizer_config"])
    if row["mechanism"] == "eval":
        out["eval_config"] = {k: v for k, v in row["eval_config"].items() if v is not None}
    if row["mechanism"] == "gate":
        # runs_at is the proposed extension of gate_config; the pack keeps to Verify's schema and says it in commentary.
        out["gate_config"] = {k: v for k, v in row["gate_config"].items() if k != "runs_at" and v is not None}
    if row["mechanism"] == "human":
        h = row["human_config"]
        out["required_authority"] = {"actor_roles": list(h["roles"]), "approval_required": bool(h["blocking"])}
        if row["flavor"] == "human-halt":
            out["halt_config"] = {
                "scope": "capability",
                "exerciser_personae": list(h["roles"]),
                "exercise_endpoint": "POST /v1/context/oversight/halts",
                "affected_calls": "tool-calls-only",
                "emits_on_exercise": f"HumanDecisionRecorded under {row['row_id']}",
                "safe_state_definition": "the playbook's actions are refused and its runs stop before their next step",
            }
    return out


def notes(row):
    lines = [f"Pack-original control of the system contextlayer, row {row['row_id']} of its controls draft."]
    if row["mechanism"] == "guardrail" and row["hook"] == "before-tool-call" and row["row_id"] in (
            "custom-cl0031", "custom-cl0036", "custom-cl0037", "custom-cl0038", "custom-cl0039"):
        lines.append("Declared before-tool-call because no guardrail hook names this point; a hook for it is "
                     "proposed to Verify.")
    if row["mechanism"] == "gate":
        lines.append("Runs " + " and ".join(row["gate_config"]["runs_at"]) + ". at-deployment (the release checks "
                     "itself once when it first starts) is a proposed extension of gate_config.runs_at.")
    if row["row_id"] == "custom-cl0052":
        lines.append("eval-performance-monitor stands in until Verify has a flavor for integrity checks.")
    if row["mechanism"] == "human":
        h = row["human_config"]
        lines.append(f"Decided by {', '.join(h['roles'])}; {h['review_mode'].lower().replace('_', ' ')} review; "
                     f"due within {h['sla_target']}; independence: {', '.join(h['independence']) or 'none'}.")
    return " ".join(lines)


def original(row):
    return {
        "schema_version": "2.7",
        "id": pack_control_id(row),
        "framework": f"pack:{PACK_ID}",
        "article": row["mechanism"],
        "paragraph": row["row_id"].removeprefix("custom-cl"),
        "chapter": CHAPTER[row["mechanism"]],
        "title": row["name"],
        "summary": row["summary"],
        "full_text_verbatim": row["summary"],
        "applies_to": list(row["applies_to"]),
        "risk_tier": ["high"],
        "obligation_type": OBLIGATION[row["mechanism"]],
        "trigger": trigger(row),
        "lifecycle_status": "published",
        "effective_from": EFFECTIVE,
        "sources": [f"contextlayer controls draft (governance/controls.yaml), row {row['row_id']}"],
        "akka_runtime": runtime(row),
        "commentary": {"interpretation_notes": notes(row), "related_controls": list(row["implements"])},
        "tracking": {"last_updated": EFFECTIVE, "publication_date": EFFECTIVE},
    }


def main():
    draft = yaml.safe_load(DRAFT.read_text(encoding="utf-8"))
    rows = draft["rows"]
    met_by = {}
    for row in rows:
        for ref in row["implements"]:
            met_by.setdefault(ref, []).append(row["row_id"])
    manifest = {
        "schema_version": "1.0",
        "id": PACK_ID,
        "publisher_type": "customer",
        "publisher_id": "akka",
        "title": "contextlayer: a governed context layer on Akka",
        "description": (
            "The controls of the Akka system contextlayer, a governed context layer: staged playbooks with approved "
            "releases, actions with human approval, connector packs for sources, an entity graph, identity linking "
            "with review, structured questions, monitors and cases. Every control has exactly one of Verify's five "
            "mechanisms and one of its flavors.\n\nSelections are the corpus controls the system meets; each "
            "remark names the system's controls that meet it. The system's own controls are pack-original "
            "(controls-original.yaml), one per row of its controls draft, which the system signs as its control "
            "contract in the Akka console."),
        "internal_version": f"v{draft['revision']}",
        "selections": [{"control_id": ref, "remarks": "met by " + ", ".join(ids)} for ref, ids in sorted(met_by.items())],
        "controls_original": [],
    }
    header = "# Generated by generate.py from governance/controls.yaml. Edit the draft and run it again.\n"
    (HERE / "pack.yaml").write_text(header + yaml.safe_dump(manifest, sort_keys=False, allow_unicode=True, width=110),
                                    encoding="utf-8", newline="\n")
    originals = {"controls": [original(row) for row in rows]}
    (HERE / "controls-original.yaml").write_text(
        header + yaml.safe_dump(originals, sort_keys=False, allow_unicode=True, width=110), encoding="utf-8",
        newline="\n")
    print(f"wrote {len(manifest['selections'])} selections and {len(rows)} pack-original controls")


if __name__ == "__main__":
    main()
