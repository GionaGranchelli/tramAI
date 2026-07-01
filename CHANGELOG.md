# Changelog

## Unreleased

### Added
- Promoted the approval workflow golden-path APIs to **RC+ Stable** for the Sovereign Runtime RC+ milestone (PR #133). This includes `ApprovalGateway`, `ApprovalRequestResult`, `SovereignWorkflowResult`, `ApprovalRequestResult.toWorkflowResult`, `ApprovalWorkflowResults`, `ApprovalWorkflowResults.fromApprovalRequestResult`, `ApprovalRequestResults`, and `HumanApprovalDecisions` — the core developer-facing golden path covered by Kotlin tests, Java interop tests, source-shape guards, manifest checks, and executable Spring/JDBC smoke proofs. Control-plane (`ApprovalDecisionControlPlane`, `ApprovalResumeControlPlane`, `ApprovalInboxQueryService`), REST/UI, auto-configuration, fallback gateway, and JDBC implementation surfaces remain Preview/Internal.
- Updated the Approval Gateway golden path guide after PR #133 to describe the core approval workflow APIs as RC+ Stable while keeping REST/control-plane, reviewer UI, Spring auto-configuration, fallback gateway, and implementation details marked Preview/Internal. Added build guards preventing stale Preview language from reappearing in the guide.
- Added approval workflow API stabilization candidate boundary (PR #132). Documents the golden-path approval workflow APIs as candidates for future RC+ Stable promotion.
