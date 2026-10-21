# Control pack: contextlayer-governed-context

The controls of the Akka system contextlayer in Verify's control-pack form
(`explain/framework/schema/control-pack.schema.yaml`), a customer pack.

| File | What it holds |
|---|---|
| `pack.yaml` | The manifest, and the corpus controls (corpus 2026-09) the system meets as `selections`; each remark names the system's controls that meet it |
| `controls-original.yaml` | Each of the system's 57 controls as a pack-original control in Verify's control schema: one mechanism, one flavor, the configuration block of its mechanism, and the evidence it records |
| `generate.py` | Writes both from `../controls.yaml`, the controls draft the console signs |

The draft is the source. After changing it, run `python governance/pack/generate.py` from `service/`;
`ControlPackTest` fails when the pack and the draft disagree. Both files validate against Verify's schemas.

Two things in the draft are proposals Verify does not have yet, so the pack states them in each control's
`commentary` instead of its fields: `gate_config.runs_at` (`at-deployment`, a gate the release runs once when it
first starts) and guardrail hooks for points that are not tool calls.
