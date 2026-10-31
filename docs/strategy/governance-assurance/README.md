# Governance Assurance Program (GAP)

Status: **Strategic direction approved for roadmap proposal; technical and legal coverage not certified.**
Date: 2026-10-11

The objective is to make AI governance assurance a cross-release priority for TramAI: define controls, enforce policy in the existing JVM runtime, test guarantees, preserve evidence, and trace evidence to *applicable* EU AI Act and ISO/IEC 42001 requirements.

This is not a claim of conformity or ISO certification. The AI Act depends on system/operator applicability; ISO/IEC 42001 covers organizational management systems.

Architecture guardrails:
- One canonical policy/enforcement authority; dashboard, mapping and simulation are clients, not competing engines.
- Keep embedded/headless operation and existing 0.7 release boundary intact.
- Config changes must be typed, versioned, validated, authorized, and activated at safe boundaries. Not all properties are hot reloadable.
- Evidence must cite actual controls and effective revision identities; unknown coverage is not compliance.
- Continue in-flight 0.7 security, authority and correctness gates without interruption.

Proposed workstreams:
- GAP-100: Regulatory applicability and control-to-evidence traceability.
- CFG-0001/GAP-200: Governed runtime configuration authority.
- GAP-300: Contract tests, assurance and evidence integrity.
- GAP-400: Lifecycle risk and evaluations.
- GAP-500: Enterprise operations and external integrations.

This document records strategic direction. A complete clause/control inventory requires normative source review, accountable applicability owners and test-based evidence; implement new work only through explicit release-scope decisions.
