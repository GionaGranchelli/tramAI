# GAP — Governance-First Engineering Protocol (proposed)

This protocol supplements AGENTS.md, CI, API stability rules and module change guides; it does not replace their existing verification and stop-and-report rules.

## When to apply

For changes affecting authorization, classification, sensitive-data handling, routing, tools, approvals, runtime control, evidence, mutable configuration, model or data lifecycle, and regulator-facing evidence exports.

Unrelated build fixes and ordinary internal refactoring may use `governance-impact: not-directly-applicable` with a short reason. Regulatory control IDs are **never** assigned merely to make a feature appear compliant.

## Required change/PR record

- **Impact:** affected actors, data and execution/trust boundaries.
- **Applicability:** law/standard identifier, edition, role/use-case assumptions, control reference; mark conditional/unknown/not applicable accurately.
- **Authority:** canonical enforcement point, effective policy/config revision and ordering of restrictive scopes.
- **Config:** mutability class, authorized editor, activation boundary and rollback/revocation behavior.
- **Proof:** positive, negative and discriminating adversarial tests; concurrency/restart tests for persisted or distributed controls.
- **Evidence:** safe schema, exact SHA/revision IDs, provenance, retention/access and what a missing record means.
- **Outside ownership:** processes, risk decisions, model quality, domain validity and legal/ISO review that TramAI cannot complete.
- **Verification:** precise local/CI commands, unrun checks as NOT RUN, no historical proof transferred to a new head.
- **Claims:** separate VERIFIED implementation/test facts, documented-but-unverified capabilities, inferences and unresolved risks.

## Definition of done

A governance-affecting feature is not complete on a passing happy-path test alone. It needs an explicit failure/abuse path, source-of-authority boundary, evidence attribution, and compatibility/persistence impact assessment. Only claim coverage of the specifically tested technical property, not blanket regulatory conformity.

## Pilot rollout

Apply this template first to GAP-100, CFG-0001 and one existing 0.7 approval/routing/evidence PR. Audit the overhead and only then consider making it a repository-wide PR template/CI gate. Don't inject bureaucracy into every code change.
