# GAP-100 — Regulatory Applicability and Evidence Baseline

> Status: **Preliminary mapping scope, not a legal audit or ISO conformity assessment.**
> Baseline: Regulation (EU) 2024/1689 consolidated 2026-07-27; ISO/IEC 42001:2023.
> Initial repository reference: master 611845cc4bb54ad26507cae5cc69257f7e4c767b.

## Regulatory sources and applicability

- EU AI Act: https://eur-lex.europa.eu/eli/reg/2024/1689/2026-07-27/eng
- ISO/IEC 42001:2023: https://www.iso.org/standard/81230.html
- The EU AI Act imposes different obligations on model providers, AI-system providers, deployers, importers and others. Assess specific intended use and Annex III/Annex I classification, prohibited practices, exceptions, territorial scope and temporal applicability before deriving applicable controls.
- ISO 42001 is a management-system standard. Mandatory management requirements and risk-selected Annex A controls must not be treated as if a JVM library could certify an organization. Licensed text and competent human review are required for exhaustive coverage.

## Seed crosswalk (selected requirements, not exhaustive)

| Source area | Technical contribution | Responsibility / known gap |
|---|---|---|
| AI Act Art. 5 prohibited uses | Explicit deny rules, pre-execution control | Organizational intended-use classification and legal applicability |
| Art. 9 risk management | Mitigation and risk-acceptance references | Risk management lifecycle, risk owners, assessment |
| Art. 10 data governance | DLP and data-routing control; provenance interfaces | Data quality, representativeness, dataset governance |
| Arts. 11–12 technical docs/records | Versioned evidence and execution records | Annex IV dossier assembly and documentation ownership |
| Arts. 13–14 transparency/oversight | Explainable decisions, guarded approvals | Instructions, meaningful human oversight, competence |
| Art. 15 robustness/security | Adversarial tests, safe invocation boundaries | Task-specific performance, bias and robustness evaluation |
| Art. 17 provider QMS | Change evidence and release traceability | Organizational quality-management system |
| Arts. 26–27 deployer obligations/FRIA | System metadata and approval/impact evidence integration | Use-case-specific duties and fundamental-rights assessment |
| Art. 50 transparency | Disclosure and content-origin hooks | End-user disclosures/marking and exceptions |
| Art. 53 GPAI obligations | Model metadata and lineage references | Applies to specific GPAI model providers; not blanket adopter duty |
| Arts. 72–73 monitoring/incidents | Event telemetry, incident references | Post-market plan and legally scoped reporting |
| ISO 42001 clauses 4–10 | Versioned supporting artifacts and operational controls | AIMS scope, leadership, audits, management review and continual improvement |
| ISO 42001 Annex A | Mapped technical control references | Control selection, Statement of Applicability, owned evidence |

## Present TramAI evidence candidates (not independently requalified in GAP-000)

Repo documents describe classification-aware sovereign routing, DLP, policy evaluation, tool governance, approval/resume, audit/outbox persistence, typed runtime evidence and initial control-plane work. Source docs: docs/STATUS.md, docs/product/positioning.md, docs/ROADMAP-0.7.0-RELEASE-CUT.md and docs/reference/release-validation.md.

These document-level claims are **not** an exhaustive code audit, current exact-head verified test report, source-of-truth regulatory coverage claim, or legal certification. The existing 0.6.0 release validation explicitly excludes EU AI Act conformity.

## Next work: evidence-backed registry

For each obligation/control: source version/edition; exact reference; jurisdiction; role/intended use/system applicability; organizational/technical ownership; implementation capability; test/evidence pointer with SHA; status VERIFIED/PARTIAL/EXTERNAL/GAP/UNKNOWN/NOT_APPLICABLE; reviewer and review date.

Do not generate an overall compliance percentage. Missing or outdated evidence must be visible. Review legal interpretation with qualified counsel and ISO control coverage with access to the normative text.
