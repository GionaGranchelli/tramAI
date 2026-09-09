# TramAI 0.10.0 — Governed Learning & Optimization

> **Status:** Authoritative release roadmap for TramAI 0.10.0  
> **Depends on:** stable governed runtime/control-plane contracts from 0.7, governance DX/intelligence from 0.8, and enterprise deployment/security foundations from 0.9 where applicable  
> **Purpose:** Learn from governed outcomes and optimize execution without weakening governance, privacy, or authority boundaries.  
> **Long-term sequence:** [`LONG-TERM-ROADMAP-0.7-0.10.md`](LONG-TERM-ROADMAP-0.7-0.10.md)

---

## 1. Release thesis

> **Learn and optimize from governed outcomes without turning governance into surveillance or allowing optimization to widen authority.**

0.10 introduces learning and optimization only after the underlying execution, evidence, policy, identity, deployment, and privacy boundaries are explicit enough to support them safely.

Two invariants are non-negotiable:

```text
authorized = governance constraints
viable     = authorized ∩ operational constraints
selected   = optimization(viable)
```

and:

```text
audit / telemetry != learning-data authorization
```

Optimization may select or further constrain. It may never create authority.

---

## 2. Governed learning

Candidate scope:

- canonical semantic learning traces;
- explicit learning eligibility separate from audit retention;
- privacy-safe evaluation capture;
- minimization and redaction;
- retention, deletion, lineage, and provenance;
- residency/sovereignty-aware storage and export;
- controlled dataset export;
- evaluation/SFT/preference/distillation adapters where justified;
- accepted/rejected/corrected trajectory semantics derived from authoritative runtime outcomes.

Mandatory privacy gate:

> **No production raw-content learning capture before capture, purpose, retention, access, deletion, residency, provenance, and export boundaries are explicit and proven.**

Detailed design: [`design/LEARNING-TRACES-AND-GOVERNED-DATASET-CAPTURE.md`](design/LEARNING-TRACES-AND-GOVERNED-DATASET-CAPTURE.md).

---

## 3. Policy-constrained adaptive routing and optimization

0.7 establishes the authority boundary and the separation between authorized, viable, and selected candidates.

0.10 may add richer optimization among already-authorized candidates using signals such as:

- availability and health;
- quota and capacity;
- cost and budget;
- latency;
- quality;
- locality/provider preference;
- energy/carbon signals where trustworthy and explicitly configured.

The optimizer must expose safe structured reasons and explicit unknown/missing-signal behavior.

Detailed design: [`design/ADAPTIVE-ROUTING-AND-SELECTION.md`](design/ADAPTIVE-ROUTING-AND-SELECTION.md).

---

## 4. FinOps

Candidate scope:

- model/provider usage evidence;
- cost attribution by workload/provider/model/deployment;
- budget state and policy-aware spend controls;
- budget-aware selection within the authorized set;
- explainable optimization decisions;
- explicit unknown/missing-price semantics;
- historical comparison without rewriting governance evidence.

FinOps data must not silently become a policy override or a reason to route protected data to an unauthorized deployment.

---

## 5. Evaluation and training integrations

TramAI should integrate with training/evaluation systems rather than becoming a general-purpose trainer.

Potential sequence:

```text
governed executions
      ↓
eligible semantic traces
      ↓
privacy + quality curation
      ↓
evaluation dataset
      ↓
candidate model evaluation
      ↓
controlled promotion
```

Training adapters may be added only where the learning-data governance contract is sufficiently explicit.

Hidden model chain-of-thought is not a required learning source.

---

## 6. Dependencies on earlier releases

0.10 relies on earlier releases preserving:

- stable workload/configuration identity;
- classification and trust-zone semantics;
- restrictive policy composition;
- authorization / viability / selection separation;
- provider/model/tool evidence and reason paths;
- control-plane query/reconstruction boundaries;
- governance facts/semantic contracts where adopted;
- enterprise identity, key, storage, and deployment boundaries where production learning data requires them.

If an earlier release lacks a boundary required to keep learning or optimization safe, 0.10 must refine that prerequisite rather than bypass it locally.

---

## 7. Explicit non-goals

0.10 does not imply:

- automatic capture of every prompt/completion/tool payload;
- indefinite raw-data retention;
- cross-tenant learning by default;
- unrestricted export to external training services;
- optimization that widens governance authority;
- hidden or provider-internal chain-of-thought capture;
- a general GPU training scheduler;
- a general model-training platform;
- machine-learned routing before deterministic authority and evidence contracts are proven.

---

## 8. Release proof

A representative proof should demonstrate:

```text
governed execution produces authoritative outcome/evidence
        ↓
learning eligibility evaluated separately from audit retention
        ↓
only permitted/minimized data enters a learning trace
        ↓
trace is curated into an evaluation artifact with provenance
        ↓
candidate model is evaluated
        ↓
optimizer compares authorized viable candidates using cost/latency/quality signals
        ↓
selection remains inside governance authority
        ↓
control plane can explain both governance and optimization reasons
```

---

## 9. Design-note rule

Detailed documents under [`design/`](design/) preserve architecture explorations created during earlier roadmap work. Their internal historical version labels do not determine implementation ownership.

This file and `LONG-TERM-ROADMAP-0.7-0.10.md` determine 0.10 scope.
