# TASK-012: Execute 0.1.0 Release Operations and Credibility Closure

- Status: done
- Priority: high
- Primary spec: [SPEC-008](../../specs/spec-008-documentation-publishing.md)
- Related ADRs: [ADR-005](../../adr/adr-005.md), [ADR-008](../../adr/adr-008.md)
- Last updated: 2026-05-04
- Note: 0.1.0 released. This task tracked the operational release closure now complete.

## Rationale

Tramai's documentation and testing baseline now reflect implementation reality, but the project still needs the final operational and credibility work required for a first public MVP release.

## Scope

- complete release metadata and changelog closure for `0.1.0`
- validate the tag-to-publish path with real credentials and signing
- tighten public-facing release narrative and credibility anchors
- confirm runnable example and provider confidence expectations for release

## Definition Of Done

- the remaining `0.1.0` checklist items that require operational release execution are either complete or explicitly deferred
- release documentation and artifacts match the public release plan
- the repository has a credible release narrative for first-time evaluators

## Notes

This task is intentionally separate from the earlier documentation-reconciliation work.
It tracks the remaining operational work needed to move from strong alpha to the first public MVP release.
Repo-local release validation is now in place through root tests, `publishToMavenLocal`, and the example smoke test.
The release-facing docs, quickstarts, and `0.1.0` changelog entry are now updated to reflect the current repository state.
Repo-local API stability, publication metadata checks, local artifact verification, local signed publish verification, and a public release-validation note are now in place.
Remaining work is mostly external or release-operator driven: remote publishing with real credentials and guarded real-provider execution.
The GitHub publish workflow is now wired for Sonatype Central Portal compatibility by publishing to the OSSRH Staging API service and issuing the required manual upload handoff for tagged releases.
