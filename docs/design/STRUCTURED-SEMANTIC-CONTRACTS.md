# TramAI 0.7.0 — Structured Semantic Contracts Addendum

> **Status:** Draft roadmap addendum  
> **Target release:** TramAI 0.7.0  
> **Relationship:** Complements `ROADMAP-0.7.0.md` and the ecosystem-governance addendum  
> **Primary objective:** Extend structured output from shape validation into deterministic semantic contracts that can reject invalid model decisions, produce safe repair feedback, and retry without moving domain invariants into prompts.  
> **Theme:** **Prompts express intent. Contracts enforce invariants.**

---

## 1. Executive Decision

TramAI 0.7.0 should add a stable semantic-validation direction for structured model output.

Today TramAI already has the important execution shape:

```text
schema → model response → extract → deserialize → validate → success/failure
                                                     │
                                                     └─ failure feedback → model repair retry
```

The current stable annotation-driven validation surface is intentionally small:

- `@AiRange` for inclusive numeric ranges;
- `@AiMinItems` for minimum collection size;
- Kotlin nullability and type shape as structural constraints.

The missing capability is not merely “more annotations”. The important gap is the ability to express and enforce **semantic invariants** such as:

```text
amount > 5000 => risk == HIGH

startDate <= endDate

subtotal + tax == total

country == "US" => state != null

returnedAmount == requestedAmount
```

Those rules must not depend on the model obeying natural-language instructions.

The architectural thesis is:

> **The model may propose a structured decision. TramAI accepts it only if the executable contract holds.**

This directly extends the 0.7.0 control-plane thesis:

> **The model proposes. TramAI decides. The control plane shows why.**

---

## 2. Why This Belongs in TramAI

### 2.1 Prompt instructions are not enforcement

A prompt such as:

```text
If amount is greater than 5000, risk MUST be HIGH.
```

is useful guidance, but it is not a deterministic control.

The model can still return:

```json
{
  "amount": 8400,
  "risk": "MEDIUM"
}
```

If the application treats that output as valid merely because the prompt contained the rule, the rule is advisory rather than enforced.

TramAI should instead be able to reject the candidate output, emit a stable violation reason, and use the existing structured repair loop to request a corrected response.

### 2.2 Governance is stronger when semantic decisions are executable

Semantic contracts strengthen several TramAI capabilities at once:

- typed structured output;
- deterministic governance boundaries;
- explainable rejection reasons;
- repair/retry semantics;
- runtime evidence;
- governance contract testing;
- control-plane traces;
- incident reconstruction.

A semantic validation failure is not “the LLM made a formatting mistake”. It can be evidence that a candidate AI decision violated an application or governance invariant.

### 2.3 Do not turn TramAI into a generic business-rules engine

TramAI should own the **AI output acceptance boundary**, not every business rule in the application.

The capability should therefore stay focused on rules that determine whether a model-produced structured result may cross the runtime boundary as a valid typed result.

---

## 3. Three Validation Layers

The roadmap must distinguish three kinds of constraint because they have different schema, runtime, security, and API implications.

### 3.1 Layer A — property constraints

Examples:

```kotlin
data class CustomerDecision(
    @property:AiNotBlank
    val customerId: String,

    @property:AiLength(min = 3, max = 120)
    val explanation: String,

    @property:AiPattern("[A-Z]{3}")
    val currency: String,

    @property:AiRange(min = 0.0, max = 1.0)
    val confidence: Double,
)
```

Candidate native constraints for 0.7.x:

- `@AiNotBlank`;
- `@AiLength(min, max)`;
- `@AiPattern(...)`;
- `@AiMaxItems(...)`;
- existing `@AiMinItems(...)`;
- existing `@AiRange(...)`.

These should map into both:

1. the generated model-facing schema where JSON Schema can represent the constraint; and
2. deterministic post-deserialization validation.

The compiled structured descriptor remains authoritative. Schema generation and runtime validation must never evolve independently.

### 3.2 Layer B — cross-field semantic constraints

Examples:

```text
amount > 5000 => risk == HIGH
startDate <= endDate
subtotal + tax == total
```

These cannot be correctly modeled as an annotation on one field because validity depends on the object as a whole.

0.7.0 should therefore introduce a stable class/object-level validator SPI.

Conceptual direction:

```kotlin
@AiValidatedBy(PaymentDecisionValidator::class)
data class PaymentDecision(
    val amount: BigDecimal,
    val risk: RiskLevel,
    val explanation: String,
)
```

with a validator similar to:

```kotlin
class PaymentDecisionValidator : AiStructuredValidator<PaymentDecision> {
    override fun validate(
        value: PaymentDecision,
        context: AiValidationContext,
    ): List<AiViolation> {
        if (value.amount > BigDecimal("5000") && value.risk != RiskLevel.HIGH) {
            return listOf(
                AiViolation(
                    path = "risk",
                    code = "RISK_THRESHOLD",
                    repairMessage = "risk must be HIGH when amount exceeds 5000",
                ),
            )
        }
        return emptyList()
    }
}
```

The API names above are directional, not yet frozen.

### 3.3 Layer C — invocation-aware domain invariants

Some rules cannot be validated from output alone.

Example:

```kotlin
suspend fun authorizePayment(requestedAmount: BigDecimal): PaymentDecision
```

The returned amount may be required to equal the requested amount:

```text
output.amount == invocation.requestedAmount
```

The validator therefore needs a safe, explicit invocation context.

Conceptual direction:

```kotlin
interface AiStructuredValidator<T : Any> {
    fun validate(
        value: T,
        context: AiValidationContext,
    ): List<AiViolation>
}
```

`AiValidationContext` may expose only the minimum information required for deterministic validation, for example:

```text
operation identity
structured argument view / explicitly addressable inputs
workload identity where available
classification/security context where appropriate
```

The exact context surface must be deliberately designed; it must not accidentally become a generic raw-payload escape hatch.

---

## 4. Core Runtime Contract

### 4.1 Authoritative pipeline

The structured-output acceptance path should become conceptually:

```text
Generate contract/schema
        ↓
Provider/model candidate response
        ↓
Extract JSON
        ↓
Shape validation
        ↓
Deserialize typed value
        ↓
Property constraints
        ↓
Semantic validators
        ↓
Invocation/domain invariants
        ↓
ACCEPT
```

Any failure before acceptance becomes a structured rejection with explicit retryability and diagnostics.

### 4.2 No invalid value crosses the response boundary

The central invariant is:

```text
returnedTypedValue => all configured authoritative structured validators passed
```

A failed semantic validator must behave like a failed structural contract: the invalid candidate value is not cached, persisted as a successful conversation result, or returned to application code.

### 4.3 Existing retry separation must remain explicit

TramAI already separates structured-output retries (`@Operation.maxRetries`) from provider transport/resilience retries (`@Operation.providerRetries`). Semantic validation should reuse the structured repair path rather than being treated as a provider failure.

The roadmap should evaluate whether the public name `maxRetries` remains sufficiently clear once semantic repair becomes first-class. A future clearer name such as `repairRetries` may be considered only with explicit compatibility/migration treatment; 0.7.0 must not silently change the meaning of an existing retry property.

---

## 5. Violation Model

Semantic validation needs a typed violation representation rather than arbitrary exception strings.

Conceptual shape:

```kotlin
data class AiViolation(
    val path: String?,
    val code: String,
    val repairMessage: String,
)
```

The final API should distinguish at least:

- stable machine-readable code;
- optional field/object path;
- safe model-facing repair message;
- privileged/internal diagnostic detail where needed.

### 5.1 Stable codes before free-form text

Examples:

```text
AI_NOT_BLANK
AI_LENGTH
AI_PATTERN
AI_RANGE
AI_MIN_ITEMS
AI_MAX_ITEMS
RISK_THRESHOLD
AMOUNT_MISMATCH
CROSS_FIELD_INVARIANT
```

Applications may define domain-specific codes for custom validators.

### 5.2 Diagnostics and repair feedback are different products

Do not require the same string to serve both observability and model repair.

Example:

```text
Privileged diagnostic:
  expected requestedAmount=7421.37; model returned amount=7400.00

Model-facing repair feedback:
  returned amount must equal the requested amount
```

This separation is a security requirement, not only a UX improvement.

---

## 6. Safe Repair Feedback

### 6.1 Repair must not become a data-exfiltration path

A validator can compare output with sensitive invocation arguments without automatically echoing those arguments back to a remote model.

Default rule:

> **Validation may inspect more data than repair feedback is allowed to reveal.**

### 6.2 Required properties

Repair feedback must be:

- bounded in size;
- explicitly model-facing;
- free of credentials/secrets;
- conservative around classified or sensitive invocation data;
- attributable to stable violation codes;
- auditable without storing raw sensitive values by default.

### 6.3 Suggested repair interaction

Candidate output:

```json
{
  "amount": 8400,
  "risk": "MEDIUM"
}
```

Violation:

```text
code: RISK_THRESHOLD
path: risk
repair: risk must be HIGH when amount exceeds 5000
```

Repair request:

```text
Your previous response failed structured validation.

- risk must be HIGH when amount exceeds 5000

Return corrected JSON only.
```

Corrected candidate:

```json
{
  "amount": 8400,
  "risk": "HIGH"
}
```

Only the corrected, validated object may cross the typed response boundary.

---

## 7. Validator Binding and Dependency Injection

### 7.1 Do not make annotation reflection the lifecycle authority

A class-level annotation may identify a validator type, but TramAI should not require reflective no-argument construction as the only execution model.

Validators may need application dependencies such as:

- policy/configuration snapshots;
- domain reference data;
- deterministic lookup services;
- tenant configuration.

The design should therefore support validator resolution through a registry/composition boundary.

Possible direction:

```text
@AiValidatedBy(FooValidator::class)
        ↓
StructuredValidatorRegistry / resolver
        ↓
registered FooValidator instance
```

Spring integration may resolve validator beans from the application context. Standalone composition should allow explicit registration.

### 7.2 Determinism requirement

A validator used as an authoritative contract should be deterministic for deterministic inputs unless its dependency contract explicitly says otherwise.

Hidden network calls inside validation are strongly discouraged and should not be the default extension model.

---

## 8. Schema Parity Rules

Property constraints that JSON Schema can express should be emitted into the model-facing schema and independently checked after deserialization.

Examples:

| TramAI constraint | Schema direction | Runtime validation |
|---|---|---|
| `@AiRange` | `minimum` / `maximum` | required |
| `@AiMinItems` | `minItems` | required |
| `@AiMaxItems` | `maxItems` | required |
| `@AiLength` | `minLength` / `maxLength` | required |
| `@AiPattern` | `pattern` | required |
| `@AiNotBlank` | schema approximation where safe | authoritative runtime check required |

Cross-field and invocation-aware rules may not have a portable JSON Schema representation. Their absence from the generated schema must never mean they are optional at runtime.

### Invariant

```text
schema guidance <= authoritative runtime contract
```

The model-facing schema may guide the model, but the post-generation validator is authoritative.

---

## 9. Deterministic Derived Values vs Model Authority

TramAI should not encourage an LLM to generate values that are fully determined by code.

If the complete rule is:

```text
amount > 5000 => risk = HIGH
amount <= 5000 => risk = LOW
```

then `risk` should probably be computed deterministically rather than generated and repaired.

Semantic validation is most valuable when the model retains legitimate judgment but deterministic policy places hard bounds around that judgment.

Example:

```text
Model considers:
  amount
  recipient
  geography
  purpose
  historical context

Policy invariant:
  amount > 5000 can never result in risk below HIGH
```

In this case the model proposes the risk assessment, while TramAI enforces the policy floor.

---

## 10. Optional Jakarta Bean Validation Bridge

TramAI should not reimplement the entire JVM validation ecosystem.

A later/optional adapter may integrate Jakarta Bean Validation for common application constraints and custom validators.

Possible module direction:

```text
tramai-structured
  native AI/schema-aware constraints
  semantic validator SPI

tramai-validation-jakarta
  optional Jakarta Bean Validation bridge
```

The core module should not require Jakarta Validation.

Native TramAI constraints remain useful because they participate in both model-facing contract generation and runtime validation.

---

## 11. Control-Plane and Evidence Integration

Semantic validation should produce governance-safe runtime signals.

Candidate event/evidence concepts:

```text
StructuredValidationFailed
StructuredRepairRequested
StructuredRepairSucceeded
StructuredRepairExhausted
SemanticInvariantRejected
```

The exact event catalogue must follow the existing typed runtime-event discipline rather than inventing ad-hoc strings.

Safe projected fields may include:

```text
workload/run/operation identity
validator/constraint identifier
violation code
property path
attempt number
willRetry
provider/model identity
policy/configuration version where relevant
```

Sensitive candidate values and invocation arguments remain excluded by default.

The semantic execution timeline should be able to render a sequence such as:

```text
Model response received
  ↓
Structured contract validation
  ↓
Semantic invariant rejected: RISK_THRESHOLD
  ↓
Repair requested
  ↓
Corrected response received
  ↓
Structured contract accepted
```

---

## 12. Governance Contract Testing

The ecosystem-governance roadmap introduces governance contract testing. Structured semantic contracts should integrate with the same philosophy.

Tests should be able to prove invariants such as:

```text
amount > 5000 => risk == HIGH
returnedAmount == requestedAmount
HIGH-risk decision => explanation is nonblank
subtotal + tax == total
```

The test path should:

- run offline;
- execute production validator implementations;
- require no real provider by default;
- expose stable violation codes and paths;
- prove rejected values never become accepted typed outputs;
- prove repair feedback is sanitized;
- prove retry exhaustion is deterministic.

A shared TCK should pin descriptor/schema/runtime-validation parity for every native constraint.

---

## 13. Delivery Slices

### S1 — Validation contract and violation model

**Priority:** P0 candidate

- define typed `AiViolation`/equivalent;
- define validator result semantics;
- define safe vs privileged diagnostics;
- define retryability and failure taxonomy;
- pin no-invalid-value-crosses-boundary behavior.

### S2 — Class-level semantic validator SPI

**Priority:** P0 candidate

- add `AiStructuredValidator<T>`/equivalent;
- support object-level/cross-field validation;
- add stable validator binding/resolution;
- avoid reflective lifecycle authority;
- add standalone registration and Spring bean integration direction.

### S3 — Invocation-aware validation context

**Priority:** P0 candidate

- expose minimal safe operation argument context;
- support output-vs-input invariants;
- define security/classification exposure rules;
- prohibit repair feedback from implicitly echoing sensitive arguments.

### S4 — Repair integration and retry semantics

**Priority:** P0 candidate

- feed semantic violations through the existing structured repair loop;
- preserve separation from `providerRetries`;
- keep retry accounting deterministic;
- ensure invalid candidates are not cached/persisted/returned;
- add repair exhausted diagnostics.

### S5 — Native property-constraint vocabulary

**Priority:** P1

- `@AiNotBlank`;
- `@AiLength`;
- `@AiPattern`;
- `@AiMaxItems`;
- descriptor/schema/runtime parity;
- explicit applicability/type validation.

### S6 — Semantic validation telemetry

**Priority:** P1

- typed runtime events;
- safe control-plane projection;
- semantic timeline entries;
- evidence provenance;
- repair-attempt observability.

### S7 — Validation TCK and mutation evidence

**Priority:** P0/P1

- native constraint TCK;
- semantic validator contract tests;
- schema/runtime parity tests;
- repair redaction tests;
- mutation campaign for skipped validators, inverted conditions, leaked diagnostics, and accepted-invalid outputs.

### S8 — Optional Jakarta Validation adapter

**Priority:** P2 / post-core

- evaluate Bean Validation bridge;
- keep core dependency-free;
- document overlap and precedence with native TramAI constraints;
- do not block 0.7.0 core semantic contracts on this adapter.

---

## 14. Recommended 0.7.0 Scope Boundary

The valuable 0.7.0 minimum is **not** “ship every validation annotation”.

The differentiating minimum is:

1. a stable typed violation model;
2. class-level semantic validation;
3. invocation-aware domain validation;
4. safe repair feedback through the existing structured retry loop;
5. deterministic tests proving invalid semantic decisions cannot cross the boundary.

The additional property annotations improve usability but are secondary to the semantic contract architecture.

If 0.7.0 scope becomes constrained, keep S1–S4 and S7 ahead of a broad annotation catalogue or Jakarta adapter.

---

## 15. Explicit Non-Goals

0.7.0 should not introduce:

- a string expression DSL such as `@AiRule("amount > 5000 => risk == HIGH")` as the primary contract model;
- SpEL, JavaScript, Kotlin scripting, or arbitrary code expressions embedded in annotations;
- a generic enterprise business-rules engine;
- remote/network-dependent validation as the default pattern;
- silent reflection-based construction of validator dependencies;
- automatic echoing of invocation arguments into model repair prompts;
- a claim that schema guidance alone enforces semantic rules;
- a requirement that every deterministic derived field be generated by an LLM;
- a mandatory Jakarta Validation dependency in `tramai-core` or `tramai-structured`.

A string rule language can be reconsidered only if there is a concrete administrative-policy use case that cannot be served safely by typed validators and configuration.

---

## 16. Acceptance Criteria

The semantic-contract core is credible when all of the following are true:

1. A structured output can be rejected because of an object-level cross-field invariant.
2. A structured output can be rejected because it conflicts with an invocation input.
3. The rejection has a stable machine-readable code and optional path.
4. The model can receive bounded, explicitly safe repair feedback.
5. Sensitive invocation values are not automatically copied into repair messages, telemetry, or generic exceptions.
6. A repaired candidate is validated again through the complete authoritative contract.
7. Exhausted repair attempts fail closed with deterministic public failure semantics.
8. Invalid candidates are not returned, cached as success, or persisted as successful structured conversation turns.
9. Native property constraints generate schema and runtime validation from one authoritative descriptor model.
10. Custom validators can be supplied through explicit application composition/DI.
11. Semantic validation failures can be represented in typed runtime telemetry without exposing raw payloads by default.
12. Governance contract tests can exercise the same production semantic validators offline.

---

## 17. Canonical Demonstration

The KTConf-style payment/risk example should become a canonical reference because it makes the product boundary obvious.

```kotlin
@AiValidatedBy(PaymentDecisionValidator::class)
data class PaymentDecision(
    val amount: BigDecimal,
    val risk: RiskLevel,
    val explanation: String,
)
```

Candidate model output:

```json
{
  "amount": 8400,
  "risk": "MEDIUM",
  "explanation": "Established supplier"
}
```

Application contract:

```text
amount > 5000 => risk must be HIGH
```

TramAI behavior:

```text
model proposes MEDIUM
        ↓
semantic contract rejects RISK_THRESHOLD
        ↓
safe repair feedback
        ↓
model proposes HIGH
        ↓
contract accepts
        ↓
typed PaymentDecision crosses application boundary
```

The message to developers is concise:

> **Prompts express intent. Contracts enforce invariants.**

And the governance interpretation is stronger:

> **The model can propose a decision. It cannot violate the contract.**
