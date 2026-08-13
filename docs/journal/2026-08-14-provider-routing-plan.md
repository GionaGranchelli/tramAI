# Implementation Status — 2026-08-14

## What's Been Implemented

PR #229: **refactor(routing): introduce authoritative provider routing plan** (Epic 2.2 complete).

- `ProviderRoutingPlan` in tramai-core — single immutable snapshot of providers/routes/defaultProvider, typed `ProviderId`/`ModelId` value classes, fail-fast build-time validation (duplicates, blank IDs, unknown providers, duplicate fallbacks, unknown defaults).
- `ProviderRegistry` reduced to a compatibility façade over the plan (public API + `ProviderRoute`/`ResolvedProviderRoute` JVM shapes unchanged, additive API dump only).
- Engine freezes the plan into `EngineComponents.ProviderComponents`; `TramaiInvocationHandler` resolves candidates from the plan.
- Standalone `Tramai.Builder` mutates the canonical plan builder; validates+freezes once at build.
- Sovereign shadow state deleted (`registeredProviders`, `primaryModelRoutes`, `fallbackRoutes`, `defaultProviderName`, `FallbackRoute`); `SovereignRoutingValidationPolicy` validates the shared plan (incl. offline LOCAL constraints); artifact-verification targets derive from the plan.
- Spring merges property providers + `ModelProvider` beans into one unique set (bean overrides same-id property provider) before the plan builder; genuine duplicate user beans fail deterministically. No Spring-side route validator.
- Docs: ROADMAP-0.6.0.md (Epic 2.2 ✅ Complete), CHANGELOG.md, tramai-spring.md, tramai-sovereign.md.

## What's Missing / Blocked

- Nothing for Epic 2.2. P3 note from agy review (close abandoned `Tramai` if sovereign validation throws) deferred — engine is lazily created, no active leak.
- Pre-existing `examples:governed-workflow` apiCheck drift on master (unrelated to this PR; `buildGovernedNetworkPolicyWorkflow` never dumped).

## Current State

- Branch `refactor/0.6.0-provider-routing-plan`, commit 2407b4d1, pushed.
- Local gates: 757 module tests / 0 failures, apiCheck (tramai modules) PASS, verifyPr PASS (268 tasks), verifyCancellationSafety PASS (292=292).
- agy review: merge-ready, 0 P1/P2.
- PR #229 open: https://github.com/GionaGranchelli/tramAI/pull/229 — CI running, awaiting review.
