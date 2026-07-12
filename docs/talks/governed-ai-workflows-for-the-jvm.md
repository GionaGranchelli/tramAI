# Beyond the Model Call: Governed AI Workflows for the JVM

> **Talk outline** — companion to the [governed JVM AI workflow article](../articles/governed-ai-workflows-for-the-jvm.md).

---

## Audience

JVM developers, application architects, platform engineers, and security/governance practitioners. No prior TramAI knowledge assumed.

---

## Formats

- **30-minute version** — narrative arc with code snippets and architectural diagrams
- **45-minute version** — adds live demo, deeper architecture, and Q&A

---

## Thirty-Minute Version

| Time | Section | Content |
|---|---|---|
| 0–3 min | The model call is not the hard part | Opening problem: calling an LLM is easy — governing what it sees, does, and leaves behind is the real challenge. |
| 3–7 min | Why prompt-only governance fails | Prompt instructions vs. runtime enforcement. Probabilistic compliance vs. deterministic decisions. |
| 7–13 min | Claim-triage workflow | Walk through the Kotlin workflow: classification, policy gate, approval gate, finalization. Four scenarios in one definition. |
| 13–18 min | Policy before side effects | BEFORE_TOOL_EXPOSURE, BEFORE_TOOL_EXECUTION. ALLOW/DENY/REQUIRE_APPROVAL. Tool never executes on denial. |
| 18–23 min | Approval and safe continuation | Approval as a lifecycle: suspend, decide, resume. At-most-once side effects. Replay-safe continuation. |
| 23–27 min | Routing and evidence | Classification-aware routing. Evidence vs. logs. Tamper-evident audit records. |
| 27–30 min | Maturity, non-claims, and where to start | What TramAI is, what it is not, and the `./gradlew :examples:governed-workflow:run` zero-credential demo command. |

**Key slides:**

1. "The model call is 1% of the problem" — problem framing
2. "Prompts are not enforcement" — side-by-side comparison table
3. Claim-triage workflow definition — actual Kotlin code
4. Gate decisions table — claim risk × approval state → outcome matrix
5. Approval lifecycle diagram — suspend → decide → resume
6. Routing zones — classification → provider zone mapping
7. Evidence structure — policy, approval, routing decisions
8. Maturity boundary — what is and is not claimed

---

## Forty-Five-Minute Version

Complete talk schedule:

| Time | Section | Content |
|---|---|---|
| 0–4 min | Opening and problem framing | The model call is not the hard part. Real governance questions start after the first request. |
| 4–9 min | Prompts versus runtime enforcement | Prompt instructions vs. deterministic runtime policy. Why "do not send confidential data" in a prompt is not enforcement. |
| 9–15 min | Claim-triage workflow | Walk through the complete Kotlin workflow. Classification, policy gate, approval gate, finalization. Four scenarios. |
| 15–21 min | Live governed-workflow demo | Run `./gradlew :examples:governed-workflow:run` live. Walk through each output line. Modify a classification and rerun. |
| 21–27 min | Policy before side effects | BEFORE_TOOL_EXPOSURE, BEFORE_TOOL_EXECUTION. ALLOW/DENY/REQUIRE_APPROVAL. Tool never executes on denial. |
| 27–33 min | Approval suspension and resume | Approval as a lifecycle. Live demo: low-value bypass, high-value suspend, approve → resume, deny → no reimbursement. |
| 33–37 min | Controlled routing | Classification-aware routing. Routing zones and sovereign defaults. |
| 37–40 min | Evidence and recovery | Evidence vs. logs. Exporters, bundle placement, tamper-evident digests. |
| 40–43 min | Maturity and non-claims | What TramAI is, what it is not. Configuration-aware language. |
| 43–45 min | Questions and closing | Open Q&A. `./gradlew :examples:governed-workflow:run` one more time. |

---

## Demo Plan

### Demo 1: Governed Workflow (both formats)

```bash
git clone https://github.com/GionaGranchelli/tramAI.git
cd tramAI
./gradlew :examples:governed-workflow:run
```

Expected output (deterministic, no network):

```
✓ Low-risk claim: ready-for-review
✓ Restricted claim: rejected
✓ High-risk unapproved claim: rejected
✓ High-risk approved claim: ready-for-review
```

**Live modification (45-minute):** Change one claim's risk classification and show the output changes.

### Demo 2: Approval Resume (45-minute only)

```bash
./gradlew :examples:approval-resume:test
```

Show test output proving:
- Low-value expense bypasses approval
- High-value expense suspends
- Approved expense resumes and reimburses exactly once
- Denied expense does not reimburse

### Demo 3: Sovereign Evidence (45-minute only)

Show a runtime-evidence JSONL fragment with policy, approval, and routing records. Point out digest-only provider identifiers, safe metadata fields, and the absence of raw prompts or secrets.

---

## Speaker Claim Boundaries

When presenting this talk, do not:

- claim that TramAI guarantees compliance;
- claim that every TramAI deployment is production-ready;
- call TramAI a certification product;
- claim that all deployments are sovereign or air-gapped by default;
- compare TramAI to named frameworks (Spring AI, LangChain4j, etc.) — comparison is a separate, careful document;
- claim universal production readiness for every deployment;
- present the deterministic example as proof of the full durable governance stack;
- use absolute enforcement language ("always", "guarantees", "fully") without qualification.

Do:

- explain the architectural problem and why it matters;
- show real, runnable Kotlin code;
- distinguish between implemented, evolving, and not-yet-implemented capabilities;
- use configuration-aware language ("when configured", "can", "supports");
- direct attendees to the project status, positioning, and examples for depth.
