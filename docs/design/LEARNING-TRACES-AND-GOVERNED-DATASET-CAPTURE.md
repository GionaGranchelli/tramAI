# TramAI 0.7.0 — Learning Traces and Governed Dataset Capture

> **Status:** P1 roadmap companion with mandatory privacy-design gate  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Complements `ROADMAP-0.7.0-RELEASE-CUT.md`, provider execution, tool invocation contracts, structured semantic contracts, approvals, audit/evidence, governance reconstruction/replay, classification, trust zones, and model evaluation  
> **Scope:** Capture semantically rich, governed AI execution traces that can later produce evaluation and fine-tuning datasets without turning provider logging into an uncontrolled copy of production data.

---

## 1. Decision

TramAI should support **governed learning traces**: an opt-in recording path that can observe model exchanges together with the higher-level execution facts TramAI uniquely knows, then curate those traces into datasets for evaluation, distillation, supervised fine-tuning, tool-use training, preference data, and failure analysis.

This is deliberately broader than a provider HTTP interceptor.

A provider interceptor can observe:

```text
messages
provider/model request
model response
provider-native tool calls
usage / latency
```

but it cannot, by itself, authoritatively determine whether the interaction was good training data.

TramAI can also know:

```text
workload / operation identity
classification
policy decision
provider/model selection
structured-output validation
repair attempts
tool invocation contract
proposed tool calls
tool governance / authorization
approval requirement and outcome
tool execution result
terminal operation outcome
```

The target architecture is therefore:

```text
provider exchange
      +
authoritative TramAI execution semantics
      ↓
Governed LearningTrace
      ↓
privacy + quality eligibility
      ↓
curated dataset example
      ↓
evaluation / SFT / tool-use / preference / distillation export
```

> **TramAI should record learning evidence, not indiscriminately copy conversations.**

---

## 2. Product value

This capability can create a useful learning flywheel:

```text
production execution
      ↓
governed semantic traces
      ↓
privacy/quality curation
      ↓
evaluation + training datasets
      ↓
candidate/local model
      ↓
TramAI contract evaluation
      ↓
controlled promotion
      ↓
better production execution
```

Potential uses include:

- supervised fine-tuning of smaller/local models;
- teaching correct tool selection and argument construction;
- preference pairs from rejected versus accepted trajectories;
- distillation from a stronger model into a smaller model;
- deterministic regression/evaluation corpora for candidate models;
- model-compliance benchmarking for required tools and structured contracts;
- failure analysis by model/provider/workload;
- measuring repair success and contract-compliance rates.

A future control-plane experience could answer questions such as:

```text
Qwen3-4B
required-tool compliance     82.1%
structured-contract pass     94.7%
repair success               87.0%
approval-safe tool requests  99.1%

Mistral-7B
required-tool compliance     96.4%
structured-contract pass     98.2%
repair success               94.3%
```

The first implementation does not need that full UI, but the trace model should preserve the semantics needed to build it later.

---

## 3. Not a provider-only interceptor

Provider interception is one useful observation point, but it must not become the canonical product boundary.

Preferred layering:

```text
                 ProviderTraceAdapter
                         │
             normalized provider exchange
                         │
                         ▼
┌──────────────────────────────────────────────┐
│                TramAI runtime                │
│                                              │
│ classification                              │
│ provider/model eligibility                  │
│ operation contracts                         │
│ semantic validation / repair                │
│ tool governance                             │
│ approvals                                   │
│ tool results                                │
│ terminal outcome                            │
└─────────────────────┬────────────────────────┘
                      │
                      ▼
              LearningTraceRecorder
                      │
                      ▼
                LearningTrace
                      │
            privacy + quality gates
                      │
             ┌────────┼────────┐
             ▼        ▼        ▼
           eval      SFT    preference
```

Provider-specific raw payloads should not define the canonical learning schema.

---

## 4. Canonical trace layers

The design should distinguish three layers.

### 4.1 Provider exchange

A normalized description of what crossed the provider boundary, where capture policy permits it:

```text
provider deployment identity
model identity
operation/request identity
normalized messages or protected references
provider parameters relevant to behavior
normalized response
provider-native tool calls
usage / latency
provider error category
```

Raw vendor payload retention is not required by default.

### 4.2 `LearningTrace`

A semantic TramAI trajectory, conceptually containing:

```text
trace identity
workload identity/version
operation identity/fingerprint
workflowRunId / correlationId
runtime/evaluator version

classification + source
policy/configuration identity
provider/model/deployment identity
trust-zone identity/category

observable model request/output where capture permits
structured validation results
repair attempts and result
tool invocation contract
proposed tool calls
tool validation/governance decision
approval requirement/outcome
tool execution outcome
final accepted response / typed result where capture permits
terminal operation outcome

quality labels / eligibility reasons
privacy eligibility / retention metadata
```

The exact API is not frozen by this roadmap.

### 4.3 Dataset example

A deliberately derived artifact suitable for a target use:

```text
SFT example
Tool-use example
Preference pair
Evaluation fixture
Distillation example
Failure-analysis sample
```

A `LearningTrace` is **not automatically a training example**.

---

## 5. Quality curation is mandatory

TramAI must not blindly train on every production interaction.

Naive behavior would create a self-reinforcing failure loop:

```text
model makes mistake
      ↓
record mistake
      ↓
train on mistake
      ↓
model learns mistake
```

Instead, quality eligibility should use authoritative runtime outcomes where possible.

Illustrative positive eligibility:

```text
terminal operation success
AND required tool contract satisfied where configured
AND semantic validators passed
AND approval satisfied where required
AND tool execution accepted/succeeded where relevant
AND no disqualifying governance failure
```

Rejected or failed traces remain useful, but for different purposes:

```text
accepted trajectory  → positive/SFT/eval candidate
rejected trajectory  → negative/preference/failure-analysis candidate
corrected trajectory → preference/repair candidate
```

A failure must never be silently relabeled as positive training data merely because the model returned syntactically valid content.

---

# 6. Mandatory privacy-design sub-epic

Privacy is a **first-class prerequisite** for this capability, not a later hardening task.

This epic is not considered implementation-ready for production raw-content capture until the privacy model is refined and explicitly reviewed.

> **No production raw-content learning capture before the privacy sub-epic defines and proves the capture, retention, access, deletion, and export boundaries.**

The privacy design must cover at least the following.

## 6.1 Explicit opt-in and deny-by-default capture

Learning capture must be disabled by default.

Enabling ordinary runtime telemetry, audit evidence, or control-plane projection must **not** implicitly enable learning-data capture.

Conceptually:

```text
runtime execution                 enabled
safe audit/evidence               enabled as configured
learning trace metadata           separately configured
raw learning content capture      explicit opt-in only
```

There must be no hidden switch where adding a trace sink silently begins retaining prompts, completions, documents, or tool payloads.

## 6.2 Purpose limitation

Captured content needs an explicit purpose such as:

```text
EVALUATION
MODEL_TRAINING
FAILURE_ANALYSIS
DISTILLATION
```

A dataset collected for one purpose must not silently become authorized for every later purpose.

The implementation must preserve enough metadata to enforce or audit that distinction.

## 6.3 Classification-aware eligibility

Existing TramAI classification should influence whether content is eligible for capture and where it may be stored.

Illustrative policy:

```text
PUBLIC        → capture may be allowed
INTERNAL      → capture may be allowed under organization policy
CONFIDENTIAL  → restricted/local protected dataset store only, if explicitly authorized
RESTRICTED    → denied by default
```

These are examples, not universal hardcoded mappings.

Organization/environment/workload policy remains authoritative.

Missing classification must not silently become permission to capture raw data.

## 6.4 Data minimization

The recorder should capture only what is required for the configured learning purpose.

Examples:

- prefer stable IDs/digests over duplicated configuration documents;
- omit credentials and authorization headers unconditionally;
- avoid full tool results when a bounded semantic outcome is sufficient;
- allow prompt/output capture independently from metadata capture;
- avoid retaining entire source documents when a protected reference or redacted excerpt is sufficient.

> **Learning utility must not become an excuse for unrestricted production-data replication.**

## 6.5 Redaction and secret handling

Before durable learning storage, TramAI should support a dedicated sanitization/redaction stage appropriate to the capture policy.

At minimum the privacy refinement must define handling for:

- credentials/tokens/API keys;
- authorization headers;
- obvious secrets;
- personal identifiers where configured;
- sensitive tool arguments/results;
- internal paths/diagnostics that should not become training material.

Redaction failure semantics must be explicit. For protected classifications, fail closed rather than storing raw data because sanitization failed.

## 6.6 Separate audit/evidence and learning stores

The audit/evidence subsystem and the learning-data subsystem have different privacy goals.

Audit should generally prefer:

```text
IDs
digests
classification
reason codes
policy/configuration versions
safe decision evidence
```

Learning may deliberately retain richer content.

Therefore:

> **The learning store must not silently reuse the audit/evidence store as a raw-content archive.**

Separate retention, access control, encryption, deletion, export, and residency policies must be possible.

## 6.7 Retention and deletion

The privacy design must define:

- default retention policy;
- per-dataset/per-purpose retention overrides;
- deletion semantics;
- trace-to-derived-dataset lineage so deletions can be propagated or reported;
- treatment of immutable evaluation fixtures versus deletable production-derived content;
- expired data behavior;
- deletion evidence without retaining the deleted payload itself.

A future export/fine-tune pipeline must not make source-data deletion impossible to reason about.

## 6.8 Access control and privileged reveal

Raw learning data is potentially more sensitive than ordinary telemetry.

The design must define distinct permissions for actions such as:

```text
view trace metadata
view redacted content
view raw content
curate dataset
export dataset
approve training use
delete dataset/trace
```

Control-plane read access must not automatically imply raw training-data access.

## 6.9 Tenant isolation

Multi-tenant deployments must not mix learning material across tenants unless an explicit higher-level contract authorizes that use.

Tenant identity must be preserved through trace capture, curation, export, deletion, and dataset lineage.

Cross-tenant training aggregation must be explicit rather than an incidental consequence of one shared trace sink.

## 6.10 Residency / sovereignty

Learning data may be materially more sensitive than the evidence required to explain a run.

The privacy refinement must define how dataset storage/export interacts with named trust zones and sovereignty policy.

For example, an interaction allowed to execute in an EU provider zone does not automatically authorize exporting the resulting raw conversation to a global training service.

Required principle:

```text
execution eligibility
      ≠
training-data export eligibility
```

Dataset export must pass its own governed boundary.

## 6.11 Rights, provenance, and training eligibility

Before fine-tuning/export is treated as a product capability, the design must preserve provenance sufficient to answer questions such as:

- where did this training example originate?;
- which workload/tenant produced it?;
- which capture policy allowed it?;
- was it generated by a model, user, tool, or imported document?;
- is the content still eligible for the intended use?;
- has the source been deleted or invalidated?;
- is the example derived from third-party content subject to additional restrictions?

The exact legal policy is organization-specific and is not hardcoded by TramAI, but the product must not erase the provenance needed to enforce it.

## 6.12 Observable reasoning only

The feature must not depend on hidden model chain-of-thought.

TramAI may record only reasoning-like material actually exposed through supported provider APIs or application-visible outputs, such as:

- explicit reasoning summaries;
- tool calls;
- structured decisions;
- repair attempts;
- model-visible intermediate messages.

Hidden/internal chain-of-thought is not a required or assumed data source.

---

## 7. Privacy policy concept

The exact API is not frozen, but the implementation likely needs an explicit policy object rather than scattered booleans.

Illustrative only:

```kotlin
LearningCapturePolicy(
    enabled = true,
    purposes = setOf(EVALUATION),
    captureInputs = true,
    captureOutputs = true,
    captureToolArguments = false,
    captureToolResults = false,
    redactBeforePersist = true,
    allowedClassifications = setOf(PUBLIC, INTERNAL),
    retentionDays = 30,
)
```

The final design should integrate with TramAI policy composition rather than allowing workload code to widen organization privacy restrictions.

---

## 8. Training/evaluation export boundary

Export is a separate governed action.

```text
LearningTrace
      ↓
privacy eligibility
      ↓
quality eligibility
      ↓
dataset transformation
      ↓
export authorization
      ↓
target sink / trainer / evaluation harness
```

Creating an export must not imply that the original source data may remain forever.

Potential formats later include:

- generic JSONL;
- chat/SFT conversation format;
- tool-calling examples;
- preference pairs;
- TramAI-native deterministic evaluation fixtures.

Provider-specific export formats must be adapters over the canonical dataset model.

---

## 9. Evaluation before fine-tuning orchestration

0.7.0 should prioritize reusable trace capture and evaluation datasets before building a training platform.

A strong first product loop is:

```text
capture governed traces
      ↓
curate privacy-safe evaluation set
      ↓
register candidate model
      ↓
run deterministic/contract evaluations
      ↓
compare candidate with current model
```

Actual fine-tuning orchestration, GPU scheduling, checkpoint management, and training infrastructure are later concerns.

TramAI should integrate with training systems rather than becoming a general-purpose trainer.

---

## 10. Relationship to tool invocation contracts

The typed-tool contract creates especially useful learning labels.

Example:

```text
operation requires schedule-payment

attempt A:
  model returns ordinary text
  → REQUIRED_TOOL_NOT_CALLED

attempt B:
  model emits schedule-payment(...)
  → governance/approval/tool execution succeed
  → terminal success
```

That pair can become:

```text
rejected trajectory  → negative/preference example
accepted trajectory  → positive/tool-use example
```

The trace must preserve the distinction between:

```text
model proposed tool
TramAI accepted tool proposal
governance authorized tool
approval satisfied
tool executed successfully
```

A model proposal alone is not proof of a positive example.

---

## 11. Relationship to structured semantic contracts

Structured validation also provides high-quality labels.

```text
model output
      ↓
semantic validator
      ├─ PASS → possible positive example
      └─ FAIL
           ↓
         repair
           ├─ PASS → corrected/preference trajectory
           └─ FAIL → negative/failure sample
```

This allows dataset construction to use authoritative application semantics rather than superficial string heuristics.

---

## 12. Relationship to audit/reconstruction/replay

Learning traces and governance evidence may correlate through stable run/decision identifiers, but they are not interchangeable.

Reconstruction must remain possible without requiring retention of raw learning content.

Deleting learning content must not corrupt safe governance evidence that is independently required for audit/reconstruction.

Conversely, retaining governance evidence must not be used as a loophole to retain raw prompts/completions indefinitely.

Core principle:

> **Auditability and learnability have different retention authorities.**

---

## 13. P1 implementation scope

### P1.1 Canonical semantic trace model

- Define a provider-neutral `LearningTrace`-equivalent model.
- Correlate provider exchange with operation/workload/run identity.
- Capture typed validation/tool/approval/outcome semantics.
- Preserve accepted/rejected/corrected trajectory distinctions.

### P1.2 Recorder/sink boundary

- Define an opt-in recorder interface.
- Avoid coupling capture to one provider implementation.
- Allow local/custom sinks.
- Define failure behavior so learning capture cannot silently break authoritative execution.

### P1.3 Privacy-design sub-epic — mandatory prerequisite

Before production raw capture is enabled:

- finalize deny-by-default enablement;
- purpose limitation;
- classification-aware eligibility;
- minimization/redaction rules;
- retention/deletion/lineage semantics;
- access-control model;
- tenant isolation;
- residency/export governance;
- provenance/training-eligibility metadata;
- safe treatment of reasoning-like content;
- negative/privacy tests and mutation coverage.

This sub-epic is **not optional** even if the initial recorder implementation is technically complete.

### P1.4 Quality curation

- Define deterministic eligibility labels from runtime outcomes.
- Separate positive, negative, corrected, and unknown-quality traces.
- Never default unknown-quality material to positive training data.

### P1.5 Evaluation dataset export

- Produce a deterministic TramAI-native evaluation representation or bounded JSONL export.
- Preserve model/provider/configuration identity needed for comparison.
- Ensure export authorization is separate from capture authorization.

---

## 14. Architecture commitment for 0.7.0

Even if all dataset exporters slip to 0.7.x, 0.7.0 architecture should preserve:

```text
provider exchange observation
      +
authoritative execution semantics
      ↓
canonical learning trace
      ↓
separate privacy/quality gates
      ↓
separate dataset/export boundary
```

Do not hardwire raw provider logging directly to a fine-tuning file format.

---

## 15. P2 / later

Explicit non-blockers for 0.7.0:

- full dataset curation UI;
- automatic preference-pair generation for every failure type;
- managed fine-tuning jobs;
- GPU/training infrastructure orchestration;
- checkpoint/model-artifact lifecycle management;
- automatic model promotion;
- generic RL/RLHF/RLAIF platform;
- cross-organization dataset marketplace;
- automatic legal/compliance determination of whether content may be trained on;
- retention of hidden chain-of-thought;
- automatic raw capture for every provider/workload.

---

## 16. Safety and privacy invariants

Required invariants:

1. learning capture is disabled by default;
2. enabling audit/telemetry does not enable raw learning capture;
3. capture authorization and dataset-export authorization are distinct;
4. learning data has a separate retention/access authority from audit evidence;
5. missing/unknown classification never silently authorizes raw capture;
6. workload-local settings cannot widen organization privacy restrictions;
7. credentials/secrets are never intentionally persisted as training material;
8. redaction failure follows explicit fail-closed rules for protected data;
9. tenant identity is preserved and cross-tenant mixing is explicit;
10. execution-zone eligibility does not imply training-export eligibility;
11. rejected/failed traces are never silently marked as positive examples;
12. model-generated tool calls are not considered successful merely because they were emitted;
13. deletion/retention semantics include derived dataset lineage where feasible;
14. raw training content is not required for governance reconstruction;
15. hidden chain-of-thought is not assumed or required.

---

## 17. Acceptance criteria

The P1 slice is successful when:

- TramAI can create a provider-neutral semantic learning trace from an execution without introducing a second runtime authority;
- a trace can correlate provider/model output with validation, tool, approval, and terminal outcomes;
- positive versus rejected/corrected trajectories are represented explicitly;
- ordinary production execution does not retain raw learning content unless explicitly enabled;
- privacy policy can deny capture based on classification/purpose/workload/tenant constraints;
- audit/evidence retention is independent from learning-data retention;
- raw-content access/export can be authorized separately from metadata access;
- export can fail closed when privacy eligibility is not established;
- privacy tests demonstrate no credentials/secrets leak through the normal capture path;
- a deleted/expired trace is not silently reintroduced through a derived export without lineage semantics;
- evaluation datasets can be built without requiring TramAI to operate a fine-tuning platform;
- provider-specific formats remain adapters rather than the canonical trace model.

---

## 18. Mandatory design review before production enablement

Before production raw-content capture is declared supported, perform a dedicated privacy/security architecture review covering at minimum:

```text
WHAT is captured?
WHY is it captured?
WHO may access it?
WHERE may it be stored/exported?
HOW LONG is it retained?
HOW is it redacted/encrypted?
HOW is deletion propagated?
HOW is tenant separation proven?
HOW is training eligibility/provenance preserved?
WHAT happens when privacy metadata is missing or ambiguous?
```

The outcome of that review must be reflected in stable policy/configuration contracts and tests before the feature is presented as production-ready.

---

## Final principle

> **Learn from governed outcomes without turning governance into surveillance.**

And operationally:

> **Capture only what is explicitly authorized, retain only what is necessary, and train only on traces whose privacy and quality eligibility are known.**
