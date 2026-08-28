package dev.tramai.build.docs

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * tramai.docs-guards (Epic 9.2d-a2): registers the root documentation-contract
 * verification tasks as typed tasks with declared, configuration-cache
 * serializable inputs. Applied to the root project; task names, groups,
 * descriptions, and failure diagnostics are identical to the historical
 * root build.gradle.kts closures they replace.
 *
 * Wiring preserved: the root build's tasks.named("check") blocks and the
 * verify050ReleaseReadiness aggregation reference these task names by string
 * and resolve to the typed registrations below.
 *
 * Ownership: tramai.docs-guards owns repository documentation/reference
 * contract verification (the 9 ordinary root doc/positioning guards plus
 * verifyModuleDocContract). Sovereign-lab verification and structural
 * maintainability analysis stay in their own plugins.
 */
class TramaiDocsGuardsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (project != project.rootProject) return
        val projectRoot = project.rootDir

        project.tasks.register<DocsContractVerifierTask>("verifyPostSovereigntyRoadmap") {
            group = "verification"
            description = "Verifies the post-sovereignty roadmap exists and contains required declaration statements."
            contractId.set("verifyPostSovereigntyRoadmap")
            verifierKind.set(DocGuardKind.GENERIC_CLAIMS)
            documents.from(projectRoot.resolve("docs/POST-SOVEREIGNTY-ROADMAP.md"))
            requiredClaims.set(
                listOf(
                    "Sovereign Lab Evidence Handoff v1 is complete",
                    "Workflow Ergonomics",
                    "API Stability",
                    "Structured Output Contracts",
                    "Runtime Evidence",
                    "Phase 0",
                    "Non-Goals",
                    "Claim Boundaries",
                    "Global Acceptance Criteria",
                )
            )
            forbiddenClaims.set(listOf("production certified", "is GA-certified"))
            requiredClaimMessagePrefix.set("Post-sovereignty roadmap is missing required phrase: ")
            forbiddenClaimMessagePrefix.set("Post-sovereignty roadmap must not claim: ")
        }

        project.tasks.register<DocsContractVerifierTask>("verifyProductPositioning") {
            group = "verification"
            description = "Verifies the canonical product positioning document exists, contains required sections, and avoids forbidden claims."
            contractId.set("verifyProductPositioning")
            verifierKind.set(DocGuardKind.PRODUCT_POSITIONING)
            this.rootDir.set(project.rootDir)
            documents.from(
                projectRoot.resolve("docs/product/positioning.md"),
                projectRoot.resolve("docs/security/PRODUCT-THESIS.md"),
                projectRoot.resolve("docs/security/mcp-governance-boundary.md"),
                projectRoot.resolve("docs/POST-SOVEREIGNTY-ROADMAP.md"),
            )
        }

        project.tasks.register<DocsContractVerifierTask>("verifyReadmePositioning") {
            group = "verification"
            description = "Verifies the README leads with governed workflows and avoids forbidden claims."
            contractId.set("verifyReadmePositioning")
            verifierKind.set(DocGuardKind.README_POSITIONING)
            this.rootDir.set(project.rootDir)
            documents.from(
                projectRoot.resolve("README.md"),
                projectRoot.resolve("docs/architecture/overview.md"),
                projectRoot.resolve("docs/modules/sovereign-runtime-module-matrix.md"),
                projectRoot.resolve("examples/governed-workflow"),
                projectRoot.resolve("examples/approval-resume"),
                projectRoot.resolve("examples/sovereign-document-intelligence"),
            )
        }

        project.tasks.register<DocsContractVerifierTask>("verifyGovernedWorkflowArticle") {
            group = "verification"
            description = "Verifies the governed AI workflow article and companion talk outline are correct."
            contractId.set("verifyGovernedWorkflowArticle")
            verifierKind.set(DocGuardKind.GOVERNED_WORKFLOW_ARTICLE)
            this.rootDir.set(project.rootDir)
            documents.from(
                projectRoot.resolve("docs/articles/governed-ai-workflows-for-the-jvm.md"),
                projectRoot.resolve("docs/talks/governed-ai-workflows-for-the-jvm.md"),
                projectRoot.resolve("README.md"),
                projectRoot.resolve("docs/product/positioning.md"),
                projectRoot.resolve("docs/STATUS.md"),
                projectRoot.resolve("examples/governed-workflow"),
                projectRoot.resolve("examples/approval-resume"),
                projectRoot.resolve("examples/sovereign-document-intelligence"),
            )
        }

        project.tasks.register<DocsContractVerifierTask>("verifyExampleSelectionGuide") {
            group = "verification"
            description = "Verifies the example selection guide covers eight examples with correct classifications, commands, and non-claims."
            contractId.set("verifyExampleSelectionGuide")
            verifierKind.set(DocGuardKind.EXAMPLE_SELECTION_GUIDE)
            this.rootDir.set(project.rootDir)
            documents.from(
                projectRoot.resolve("examples/README.md"),
                projectRoot.resolve("scripts/verify-zero-egress.sh"),
                projectRoot.resolve("settings.gradle.kts"),
                projectRoot.resolve("examples/spring-sovereign-starter/README.md"),
                projectRoot.resolve("examples/kotlin-springboot-example/README.md"),
                projectRoot.resolve("examples/sovereign-lab/README.md"),
            )
        }

        project.tasks.register<DocsContractVerifierTask>("verifyJvmAiFrameworkComparison") {
            group = "verification"
            description = "Verifies the JVM AI framework comparison document."
            contractId.set("verifyJvmAiFrameworkComparison")
            verifierKind.set(DocGuardKind.JVM_AI_FRAMEWORK_COMPARISON)
            this.rootDir.set(project.rootDir)
            documents.from(projectRoot.resolve("docs/comparison/jvm-ai-frameworks.md"))
        }

        project.tasks.register<DocsContractVerifierTask>("verifyWorkflowApiStabilityBoundary") {
            group = "verification"
            description = "Verifies the workflow API stability boundary document exists, contains required classifications, and avoids forbidden overclaims."
            contractId.set("verifyWorkflowApiStabilityBoundary")
            verifierKind.set(DocGuardKind.WORKFLOW_API_STABILITY_BOUNDARY)
            this.rootDir.set(project.rootDir)
            documents.from(projectRoot.resolve("docs/workflow-api-stability-boundary.md"))
        }

        project.tasks.register<DocsContractVerifierTask>("verifyVersionAlignment") {
            group = "verification"
            description = "Verifies the repository version surfaces are aligned: 0.5.0 as release version."
            contractId.set("verifyVersionAlignment")
            verifierKind.set(DocGuardKind.VERSION_ALIGNMENT)
            this.rootDir.set(project.rootDir)
            // Canonical values come from gradle.properties (same sources of truth
            // the historical closure used); no self-introspection of build files.
            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedReleaseDate.set(project.providers.gradleProperty("tramaiReleaseDate"))
            documents.from(
                projectRoot.resolve("gradle.properties"),
                projectRoot.resolve("build.gradle.kts"),
                projectRoot.resolve("CHANGELOG.md"),
                projectRoot.resolve("docs/STATUS.md"),
                projectRoot.resolve("docs/POST-SOVEREIGNTY-ROADMAP.md"),
                projectRoot.resolve("docs/releases/0.5.0-release-readiness.md"),
                projectRoot.resolve("docs/releases/sovereign-runtime-release-readiness.md"),
                projectRoot.resolve("README.md"),
                projectRoot.resolve("docs/guides/getting-started.md"),
                projectRoot.resolve("docs/guides/quickstart.md"),
                projectRoot.resolve("docs/guides/spring-boot.md"),
                projectRoot.resolve("docs/guides/standalone-usage.md"),
                projectRoot.resolve("docs/guides/tutorial-invoice-analyzer.md"),
                projectRoot.resolve("docs/module-guide.md"),
                projectRoot.resolve("docs/reference/releasing.md"),
                projectRoot.resolve("examples/README.md"),
                projectRoot.resolve("examples/support-agent/build.gradle.kts"),
                projectRoot.resolve("examples/kotlin-springboot-example/build.gradle.kts"),
                projectRoot.resolve("examples/kotlin-native-smoke-example/build.gradle.kts"),
                projectRoot.resolve("examples/sovereign-runtime-consumer-smoke/build.gradle.kts"),
                projectRoot.resolve("examples/spring-sovereign-starter/build.gradle.kts"),
                projectRoot.resolve("docs/modules"),
            )
        }

        project.tasks.register<DocsContractVerifierTask>("verifyToolGovernanceExample") {
            group = "verification"
            description = "Verifies the tool-governance example module and guide are correctly wired."
            contractId.set("verifyToolGovernanceExample")
            verifierKind.set(DocGuardKind.TOOL_GOVERNANCE_EXAMPLE)
            this.rootDir.set(project.rootDir)
            documents.from(
                projectRoot.resolve("examples/tool-governance"),
                projectRoot.resolve("settings.gradle.kts"),
                projectRoot.resolve("examples/README.md"),
                projectRoot.resolve("docs/guides/governed-tool-use.md"),
                projectRoot.resolve("docs/POST-SOVEREIGNTY-ROADMAP.md"),
            )
        }

        project.tasks.register<DocsContractVerifierTask>("verifyModuleDocContract") {
            group = "verification"
            description = "Verifies the module-card documentation contract (Epic 11.2b3): manifest/card coverage, required headings, link/path resolution, no legacy classification, resolvable dependency snippets, no internal Maven advertisement, README counts"
            contractId.set("verifyModuleDocContract")
            verifierKind.set(DocGuardKind.MODULE_DOC_CONTRACT)
            this.rootDir.set(project.rootDir)
            documents.from(
                projectRoot.resolve("config/quality"),
                projectRoot.resolve("docs/modules"),
                projectRoot.resolve("docs/reference"),
                projectRoot.resolve("docs/architecture"),
                projectRoot.resolve("ARCHITECTURE.md"),
            )
        }
    }
}
