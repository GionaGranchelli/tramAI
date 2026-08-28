package dev.tramai.build.docs

import java.io.File

/**
 * Pure documentation-contract verifiers for the root project (Epic 9.2d-a2).
 *
 * Each verifier is an exact, mechanical port of the historical root
 * build.gradle.kts closure it replaces: every require() message is preserved
 * byte-for-byte (they are the diagnostic contract) and failure semantics are
 * identical (fail fast on the first violated require, throwing the same
 * IllegalArgumentException the original `require` raised inside the task).
 *
 * These objects are deliberately pure Kotlin — no Gradle types beyond
 * [File] — so they can be unit-tested without TestKit and driven by the
 * thin typed task ([DocsContractVerifierTask]).
 */

/** Shared section-extraction helper used by the example-guide and comparison verifiers. */
private fun sectionBetween(text: String, start: String, end: String): String {
    val s = text.indexOf(start)
    require(s >= 0) { "Missing section start: '$start'" }
    val e = text.indexOf(end, s + start.length)
    require(e >= 0) { "Missing section end marker after '$start': '$end'" }
    return text.substring(s, e)
}

/** verifyProductPositioning (root build.gradle.kts @ 66198f33, lines 3215–3349). */
object RootDocGuardVerifiers {

    fun productPositioning(rootDir: File) {

        val positioningDoc = File(rootDir, "docs/product/positioning.md")
        require(positioningDoc.isFile) {
            "Missing product positioning document at ${positioningDoc.absolutePath}."
        }

        val text = positioningDoc.readText()

        // Required sections
        val requiredSections = listOf(
            "## Canonical Message",
            "### Tagline",
            "### One-Sentence Positioning",
            "### Thirty-Second Description",
            "## The Problem TramAI Solves",
            "## Product Category",
            "## Who TramAI Is For",
            "## Representative Use Cases",
            "## Product Pillars",
            "## What TramAI Is Not",
            "## Current Maturity",
            "## Claim Boundaries",
            "## Messaging Guide",
            "## Source-of-Truth Documents",
        )
        for (section in requiredSections) {
            require(text.contains(section)) {
                "Missing required section: '$section'"
            }
        }

        // Required tagline
        require(text.contains("Governed AI workflows for the JVM")) {
            "Missing canonical tagline: 'Governed AI workflows for the JVM'"
        }

        // Required one-sentence positioning
        require(text.contains("Kotlin-first JVM runtime for governed AI workflows")) {
            "Missing one-sentence positioning phrase"
        }

        // Required status/non-claims section
        require(text.contains("Current Maturity")) {
            "Missing current maturity section"
        }
        require(text.contains("Not implemented")) {
            "Missing deferred/not-implemented status indicators"
        }

        // Forbidden claims (case-insensitive, with punctuation variants)
        val forbiddenClaims = listOf(
            "fully compliant",
            "guarantees compliance",
            "production certified",
            "production-certified",
            "production-ready for every deployment",
            "guarantees sovereignty",
            "fully air-gapped by default",
            "amount-threshold authorization is implemented",
            "remote MCP tools are currently governed",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim found: '$claim'"
            }
        }

        // Verify old thesis links to canonical document
        val oldThesis = File(rootDir, "docs/security/PRODUCT-THESIS.md")
        require(oldThesis.isFile) {
            "Missing historical thesis at ${oldThesis.absolutePath}"
        }
        val thesisText = oldThesis.readText()
        require(thesisText.contains("product/positioning.md")) {
            "Old PRODUCT-THESIS.md must link to the canonical positioning document"
        }
        require(thesisText.contains("historical path retained")) {
            "Old PRODUCT-THESIS.md must declare itself as historical"
        }

        // Verify MCP boundary document correctly distinguishes server from client/connector
        val mcpDoc = File(rootDir, "docs/security/mcp-governance-boundary.md")
        require(mcpDoc.isFile) {
            "Missing MCP governance boundary at ${mcpDoc.absolutePath}"
        }
        val mcpText = mcpDoc.readText()

        // Must positively acknowledge the existing server
        require(mcpText.contains("currently includes an MCP server module", ignoreCase = true)) {
            "MCP governance boundary must state that the MCP server module exists"
        }

        // Must positively state the client/connector is not implemented
        require(mcpText.contains("does not currently implement the governed MCP client/connector", ignoreCase = true)) {
            "MCP governance boundary must state that the governed MCP client/connector is not implemented"
        }

        // Must not contain stale language that implies no MCP runtime exists at all
        val staleMcpPhrases = listOf(
            "does not implement an MCP server",
            "no MCP server exists",
            "does not currently implement an MCP connector, MCP client, MCP server",
            "before any MCP runtime implementation",
            "when TramAI eventually supports MCP",
        )
        for (phrase in staleMcpPhrases) {
            require(!mcpText.contains(phrase, ignoreCase = true)) {
                "Stale MCP language found: '$phrase'"
            }
        }

        // Verify roadmap contains PR #192
        val roadmap = File(rootDir, "docs/POST-SOVEREIGNTY-ROADMAP.md")
        require(roadmap.isFile) {
            "Missing post-sovereignty roadmap at ${roadmap.absolutePath}"
        }
        val roadmapText = roadmap.readText()
        require(roadmapText.contains("#192")) {
            "Post-sovereignty roadmap must reference PR #192"
        }
    }

/** verifyReadmePositioning (root build.gradle.kts @ 66198f33, lines 3360–3470). */
fun readmePositioning(rootDir: File) {

        val readme = File(rootDir, "README.md")
        require(readme.isFile) {
            "Missing README.md at ${readme.absolutePath}"
        }

        val text = readme.readText()

        // Required phrases
        val requiredPhrases = listOf(
            "Governed AI Workflows for the JVM",
            "Kotlin-first JVM runtime for governed AI workflows",
            "./gradlew :examples:governed-workflow:run",
            "examples/governed-workflow",
            "examples/approval-resume",
            "examples/sovereign-document-intelligence",
            "docs/guides/quickstart.md",
            "docs/guides/governed-workflow-quickstart.md",
            "docs/STATUS.md",
            "docs/product/positioning.md",
            "active development",
        )
        for (phrase in requiredPhrases) {
            require(text.contains(phrase, ignoreCase = true)) {
                "README must contain: '$phrase'"
            }
        }

        // The governed-workflow command must be the FIRST Gradle command in the README
        val governedCommand = "./gradlew :examples:governed-workflow:run"
        val governedRunIndex = text.indexOf(governedCommand)
        require(governedRunIndex >= 0) {
            "README must contain the governed-workflow run command"
        }

        val firstGradleIndex = Regex("""(?m)^\s*\./gradlew\b""")
            .find(text)
            ?.range
            ?.first
            ?: -1
        require(firstGradleIndex == governedRunIndex) {
            "The governed-workflow command must be the first Gradle command in README.md. " +
                "Found another Gradle command at position $firstGradleIndex before governed-workflow at $governedRunIndex."
        }

        // Verify all README navigation links resolve to actual files
        val navTargets = listOf(
            "docs/architecture/overview.md",
            "docs/modules/sovereign-runtime-module-matrix.md",
            "examples/governed-workflow",
            "examples/approval-resume",
            "examples/sovereign-document-intelligence",
        )
        for (path in navTargets) {
            val target = File(rootDir, path)
            require(target.exists()) {
                "README navigation target does not exist: $path"
            }
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "fully compliant",
            "guarantees compliance",
            "production certified",
            "production-certified",
            "production-ready for every deployment",
            "guarantees sovereignty",
            "fully air-gapped by default",
            "amount-threshold authorization is implemented",
            "remote MCP tools are currently governed",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim in README: '$claim'"
            }
        }

        // No premature competitor comparisons (reserved for PR #196)
        val forbiddenComparisons = listOf(
            "Spring AI lacks",
            "LangChain4j lacks",
            "better than Spring AI",
            "better than LangChain4j",
        )
        for (phrase in forbiddenComparisons) {
            require(!text.contains(phrase, ignoreCase = true)) {
                "Forbidden comparison in README: '$phrase'"
            }
        }

        // No stale roadmap sentence referencing completed phases
        val staleSentence = "The next phase focuses on workflow ergonomics, API stability, " +
            "structured output contracts, and runtime evidence"
        require(!text.contains(staleSentence, ignoreCase = true)) {
            "README must not contain stale roadmap sentence about completed phases"
        }
    }

/** verifyGovernedWorkflowArticle (root build.gradle.kts @ 66198f33, lines 3484–3631). */
fun governedWorkflowArticle(rootDir: File) {

        val article = File(rootDir, "docs/articles/governed-ai-workflows-for-the-jvm.md")
        val talk = File(rootDir, "docs/talks/governed-ai-workflows-for-the-jvm.md")
        require(article.isFile) {
            "Missing article at ${article.absolutePath}"
        }
        require(talk.isFile) {
            "Missing talk outline at ${talk.absolutePath}"
        }

        val articleText = article.readText()
        val talkText = talk.readText()

        // Required article headings
        val requiredHeadings = listOf(
            "## The Model Call Is the Easy Part",
            "## Governance Cannot Live Only in Prompts",
            "## What Makes a Workflow Governed",
            "## A Concrete Example: Claim Triage",
            "## Policy Before Side Effects",
            "## Human Approval Is a Lifecycle",
            "## Controlled Routing for Sensitive Workloads",
            "## Evidence and Operational Recovery",
            "## Why the JVM",
            "## Composable Adoption",
            "## What This Does Not Claim",
            "## Try It",
        )
        for (heading in requiredHeadings) {
            require(articleText.contains(heading)) {
                "Article missing required heading: '$heading'"
            }
        }

        // Required phrases
        val requiredPhrases = listOf(
            "Governed AI workflows for the JVM",
            "when governance components are configured",
            "active development",
            "does not itself",
            "make an organization compliant",
            "./gradlew :examples:governed-workflow:run",
            "policy-check",
            "approval-required",
            "replay-safe continuation",
            "classification-aware routing",
            "tamper-evident",
        )
        for (phrase in requiredPhrases) {
            require(articleText.contains(phrase, ignoreCase = true)) {
                "Article must contain: '$phrase'"
            }
        }

        // Verify the workflow snippet is actual source (not abridged placeholder)
        require(articleText.contains(".build {") || articleText.contains("abridged")) {
            "Article workflow snippet must either be the actual source with .build or labeled as abridged"
        }
        require(articleText.contains("ClaimTriageResult(")) {
            "Article workflow snippet must construct ClaimTriageResult directly, not use a made-up helper"
        }

        // Required links with target existence validation
        val requiredLinks = mapOf(
            "../../README.md" to "README.md",
            "../product/positioning.md" to "docs/product/positioning.md",
            "../STATUS.md" to "docs/STATUS.md",
            "../../examples/governed-workflow" to "examples/governed-workflow",
            "../../examples/approval-resume" to "examples/approval-resume",
            "../../examples/sovereign-document-intelligence" to "examples/sovereign-document-intelligence",
        )
        for ((link, targetPath) in requiredLinks) {
            require(articleText.contains(link)) {
                "Article must contain link: '$link'"
            }
            val target = File(rootDir, targetPath)
            require(target.exists()) {
                "Article link target does not exist: $targetPath (linked as '$link')"
            }
        }

        // Required talk-outline sections
        val requiredTalkSections = listOf(
            "## Audience",
            "## Thirty-Minute Version",
            "## Forty-Five-Minute Version",
            "## Demo Plan",
            "## Speaker Claim Boundaries",
        )
        for (section in requiredTalkSections) {
            require(talkText.contains(section)) {
                "Talk outline missing required section: '$section'"
            }
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "fully compliant",
            "guarantees compliance",
            "production certified",
            "production-certified",
            "production-ready for every deployment",
            "guarantees sovereignty",
            "fully air-gapped by default",
            "amount-threshold authorization is implemented",
            "remote MCP tools are currently governed",
            "tamper-proof",
            "every decision is always recorded",
            "every workflow resumes exactly once",
            "record every decision",
            "every governance decision",
            "evidence export worker",
            "automatically exported as",
        )
        for (claim in forbiddenClaims) {
            require(!articleText.contains(claim, ignoreCase = true)) {
                "Forbidden claim in article: '$claim'"
            }
        }

        // No premature competitor comparisons (reserved for PR #196)
        val forbiddenComparisons = listOf(
            "Spring AI lacks",
            "LangChain4j lacks",
            "better than Spring AI",
            "better than LangChain4j",
        )
        for (phrase in forbiddenComparisons) {
            require(!articleText.contains(phrase, ignoreCase = true)) {
                "Forbidden comparison in article: '$phrase'"
            }
        }
    }

/** verifyExampleSelectionGuide (root build.gradle.kts @ 66198f33, lines 3637–3923). */
fun exampleSelectionGuide(rootDir: File) {

        val guide = File(rootDir, "examples/README.md")
        require(guide.isFile) {
            "Missing example selection guide at ${guide.absolutePath}"
        }
        val text = guide.readText()

        // Required headings
        val requiredHeadings = listOf(
            "## Start Here",
            "## Choose by Goal",
            "## Example Matrix",
            "## Recommended Learning Paths",
            "## Example Profiles",
            "## What the Examples Do Not Prove",
        )
        for (heading in requiredHeadings) {
            require(text.contains(heading)) {
                "Guide missing required heading: '$heading'"
            }
        }

        // Required profiles
        val requiredProfiles = listOf(
            "### Governed Workflow",
            "### Support Agent",
            "### Kotlin Spring Boot Example",
            "### Approval Resume",
            "### Spring Sovereign Starter",
            "### Sovereign Document Intelligence",
            "### Sovereign Offline Verification",
            "### Sovereign Lab",
        )
        for (profile in requiredProfiles) {
            require(text.contains(profile)) {
                "Guide missing required profile: '$profile'"
            }
        }

        // Required commands
        val requiredCommands = listOf(
            "./gradlew :examples:governed-workflow:run",
            "./gradlew :examples:support-agent:run",
            "./gradlew :examples:approval-resume:test",
            "./gradlew :examples:spring-sovereign-starter:bootRun",
            "./gradlew :examples:sovereign-document-intelligence:run",
            "./gradlew -p examples/kotlin-springboot-example bootRun",
            "./scripts/verify-zero-egress.sh",
            "./gradlew verifySovereignLabProfile",
        )
        for (cmd in requiredCommands) {
            require(text.contains(cmd)) {
                "Guide must contain command: '$cmd'"
            }
        }

        // Required phrases
        val requiredPhrases = listOf(
            "no credentials",
            "embedded PostgreSQL",
            "no Docker",
            "separate Gradle build",
            "in-memory",
            "reference workflow",
            "verification harness",
            "physical local-model evaluation",
            "not a production deployment template",
            "active development",
        )
        for (phrase in requiredPhrases) {
            require(text.contains(phrase, ignoreCase = true)) {
                "Guide must contain: '$phrase'"
            }
        }

        // Link-to-target validation — verifies both that the link text exists
        // in the guide AND that the target file exists on disk
        val requiredLinks = mapOf(
            "spring-sovereign-starter/README.md" to
                "examples/spring-sovereign-starter/README.md",
            "kotlin-springboot-example/README.md" to
                "examples/kotlin-springboot-example/README.md",
            "sovereign-lab/README.md" to "examples/sovereign-lab/README.md",
        )
        for ((link, targetPath) in requiredLinks) {
            require(text.contains(link)) {
                "Guide must contain relative link: $link"
            }
            val target = File(rootDir, targetPath)
            require(target.isFile) {
                "Guide link target does not exist: $targetPath"
            }
        }

        // Offline harness link points to script (no README)
        require(text.contains("../scripts/verify-zero-egress.sh")) {
            "Guide must link the offline verification script as ../scripts/verify-zero-egress.sh"
        }
        require(File(rootDir, "scripts/verify-zero-egress.sh").isFile) {
            "Offline verification script does not exist: scripts/verify-zero-egress.sh"
        }

        // Prohibit duplicated prefix inside examples/README.md
        require(!text.contains("examples/sovereign-lab/README.md")) {
            "Links inside examples/README.md must not repeat the examples/ prefix"
        }

        // Root example modules in settings.gradle.kts
        val settingsText = File(rootDir, "settings.gradle.kts").readText()
        val rootModules = listOf(
            "examples:support-agent",
            "examples:sovereign-document-intelligence",
            "examples:sovereign-offline-verification",
            "examples:spring-sovereign-starter",
            "examples:governed-workflow",
            "examples:approval-resume",
        )
        for (module in rootModules) {
            require(settingsText.contains("\"$module\"")) {
                "settings.gradle.kts must still include root example module: $module"
            }
        }

        // ── Section-scoped checks ──

        // Matrix row: offline verification must document Docker + Python 3
        val matrixSection = sectionBetween(text, "## Example Matrix", "## Example Profiles")
        require(matrixSection.contains("Docker + Python 3")) {
            "Offline verification matrix row must document 'Docker + Python 3', not 'Controlled network environment'"
        }

        // Governed Workflow
        val gwSection = sectionBetween(text, "### Governed Workflow", "### Support Agent")
        require(gwSection.contains("no credentials")) {
            "Governed Workflow section must contain 'no credentials'"
        }
        require(gwSection.contains("composition")) {
            "Governed Workflow section must contain 'composition'"
        }

        // Support Agent
        val saSection = sectionBetween(text, "### Support Agent", "### Kotlin Spring Boot Example")
        require(saSection.contains("Ollama")) {
            "Support Agent section must contain 'Ollama'"
        }
        require(saSection.contains("MockAiProvider")) {
            "Support Agent section must contain 'MockAiProvider'"
        }
        require(saSection.contains("@AiDescription")) {
            "Support Agent section must contain '@AiDescription'"
        }
        require(!saSection.contains("@Structured")) {
            "Support Agent section must not reference '@Structured' — the example uses @AiDescription"
        }

        // Kotlin Spring Boot Example
        val ktSection = sectionBetween(text, "### Kotlin Spring Boot Example", "### Approval Resume")
        require(ktSection.contains("separate Gradle build")) {
            "Kotlin Spring Boot Example section must contain 'separate Gradle build'"
        }
        require(ktSection.contains("0.4.0")) {
            "Kotlin Spring Boot Example section must contain '0.4.0'"
        }
        require(ktSection.contains("gemma4:e4b")) {
            "Kotlin Spring Boot Example section must specify 'gemma4:e4b' model"
        }
        require(ktSection.contains("deepseek-r1:8b-64k")) {
            "Kotlin Spring Boot Example section must specify 'deepseek-r1:8b-64k' model"
        }

        // Approval Resume
        val arSection = sectionBetween(text, "### Approval Resume", "### Spring Sovereign Starter")
        require(arSection.contains("embedded PostgreSQL")) {
            "Approval Resume section must contain 'embedded PostgreSQL'"
        }
        require(arSection.contains("no Docker")) {
            "Approval Resume section must contain 'no Docker'"
        }
        require(arSection.contains("at-most-once", ignoreCase = true)) {
            "Approval Resume section must contain 'at-most-once'"
        }

        // Spring Sovereign Starter
        val ssSection = sectionBetween(text, "### Spring Sovereign Starter", "### Sovereign Document Intelligence")
        require(ssSection.contains("in-memory")) {
            "Spring Sovereign Starter section must contain 'in-memory'"
        }
        require(ssSection.contains("state is lost on restart")) {
            "Spring Sovereign Starter section must contain 'state is lost on restart'"
        }

        // Sovereign Document Intelligence
        val sdiSection = sectionBetween(text, "### Sovereign Document Intelligence", "### Sovereign Offline Verification")
        require(sdiSection.contains("reference workflow")) {
            "Sovereign Document Intelligence section must contain 'reference workflow'"
        }
        require(sdiSection.contains("not a production deployment template")) {
            "Sovereign Document Intelligence section must contain 'not a production deployment template'"
        }

        // Sovereign Offline Verification
        val sovSection = sectionBetween(text, "### Sovereign Offline Verification", "### Sovereign Lab")
        require(sovSection.contains("verification harness")) {
            "Sovereign Offline Verification section must contain 'verification harness'"
        }
        require(sovSection.contains("Docker")) {
            "Sovereign Offline Verification section must document Docker requirement"
        }
        require(sovSection.contains("Python 3")) {
            "Sovereign Offline Verification section must document Python 3 requirement"
        }
        require(sovSection.contains("--network=none")) {
            "Sovereign Offline Verification section must mention --network=none"
        }

        // Sovereign Lab
        val labSection = sectionBetween(text, "### Sovereign Lab", "## Recommended Learning Paths")
        require(labSection.contains("PostgreSQL")) {
            "Sovereign Lab section must contain 'PostgreSQL'"
        }
        require(labSection.contains("local model")) {
            "Sovereign Lab section must contain 'local model'"
        }
        require(labSection.contains("advanced")) {
            "Sovereign Lab section must contain 'advanced'"
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "all examples require no credentials",
            "all examples are production-ready",
            "proves compliance",
            "certifies compliance",
            "guarantees sovereignty",
            "LOCAL means air-gapped",
            "every TramAI deployment has zero egress",
            "all side effects execute exactly once",
            "every workflow resumes exactly once",
            "remote MCP tools are governed",
            "support-agent demonstrates sovereign governance",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim in guide: '$claim'"
            }
        }

        // No premature competitor comparisons (reserved for PR #196)
        val forbiddenComparisons = listOf(
            "Spring AI lacks",
            "LangChain4j lacks",
            "better than Spring AI",
            "better than LangChain4j",
        )
        for (phrase in forbiddenComparisons) {
            require(!text.contains(phrase, ignoreCase = true)) {
                "Forbidden comparison in guide: '$phrase'"
            }
        }
    }

/** verifyJvmAiFrameworkComparison (root build.gradle.kts @ 66198f33, lines 3929–4161). */
fun jvmAiFrameworkComparison(rootDir: File) {

        val doc = File(rootDir, "docs/comparison/jvm-ai-frameworks.md")
        require(doc.isFile) {
            "Missing comparison document at ${doc.absolutePath}"
        }
        val text = doc.readText()

        // Section boundaries
        val springOptimization = sectionBetween(text, "### Spring AI", "### LangChain4j")
        val langChainOptimization = sectionBetween(text, "### LangChain4j", "### TramAI")
        val capabilitySection = sectionBetween(text, "## Capability Comparison", "## Choose Spring AI When")
        val springChoiceSection = sectionBetween(text, "## Choose Spring AI When", "## Choose LangChain4j When")
        val langChainChoiceSection = sectionBetween(text, "## Choose LangChain4j When", "## Choose TramAI When")
        val tramaiChoiceSection = sectionBetween(text, "## Choose TramAI When", "## Where TramAI Is Weaker Today")
        val weaknessesSection = sectionBetween(text, "## Where TramAI Is Weaker Today", "## Coexistence and Migration")
        val coexistenceSection = sectionBetween(text, "## Coexistence and Migration", "## Limitations and Non-Claims")

        // Spring AI content: optimization + capability (table + qualification) + choice
        val springAiContent = springOptimization + "\n" +
            sectionBetween(text, "## Capability Comparison", "## Choose LangChain4j When")

        // LangChain4j content: optimization + capability (table + qualification) + choice
        val langChainContent = langChainOptimization + "\n" +
            sectionBetween(text, "## Capability Comparison", "## Choose TramAI When")

        // TramAI content: comparison table + choice + weaknesses
        val tramaiContent = sectionBetween(text, "## Capability Comparison", "## Choose Spring AI When") + "\n" +
            tramaiChoiceSection + "\n" + weaknessesSection

        // Required headings
        val requiredHeadings = listOf(
            "## Scope and Method",
            "## Version and Source Snapshot",
            "## What the Three Projects Optimize For",
            "## Shared Capabilities",
            "## Capability Comparison",
            "## Choose Spring AI When",
            "## Choose LangChain4j When",
            "## Choose TramAI When",
            "## Where TramAI Is Weaker Today",
            "## Coexistence and Migration",
            "## Limitations and Non-Claims",
            "## Source Notes",
        )
        for (heading in requiredHeadings) {
            require(text.contains(heading)) {
                "Comparison missing required heading: '$heading'"
            }
        }

        // Required snapshot phrases
        val snapshotPhrases = listOf(
            "July 12, 2026",
            "Spring AI 2.0.0",
            "LangChain4j 1.17.2",
            "0.4.0",
            "dated snapshot",
            "official documentation",
            "not an evergreen benchmark",
        )
        for (phrase in snapshotPhrases) {
            require(text.contains(phrase, ignoreCase = true)) {
                "Comparison must contain: '$phrase'"
            }
        }

        // Required Spring AI acknowledgements (scoped to Spring AI sections)
        val springAiTerms = listOf(
            "ChatClient",
            "Advisors",
            "ToolCallingManager",
            "structured output",
            "observability",
            "MCP client",
            "MCP server",
            "RAG",
        )
        for (term in springAiTerms) {
            require(springAiContent.contains(term, ignoreCase = true)) {
                "Spring AI section must acknowledge '$term'"
            }
        }

        // Required LangChain4j acknowledgements (scoped to LangChain4j sections)
        val langchainTerms = listOf(
            "AI Services",
            "structured outputs",
            "guardrails",
            "HumanInTheLoop",
            "PendingResponse",
            "persistent `AgenticScope`",
            "dynamic model selection",
            "compensation",
            "MCP client",
            "experimental",
        )
        for (term in langchainTerms) {
            require(langChainContent.contains(term, ignoreCase = true)) {
                "LangChain4j section must acknowledge '$term'"
            }
        }

        // Required TramAI boundaries (scoped to TramAI sections: comparison table, choice, weaknesses)
        val tramaiTerms = listOf(
            "policy",
            "DLP",
            "approval",
            "replay-safe",
            "trust-zone",
            "tamper-evident",
            "RC+",
            "active development",
        )
        for (term in tramaiTerms) {
            require(tramaiContent.contains(term, ignoreCase = true)) {
                "TramAI section must contain '$term'"
            }
        }

        // Required maturity acknowledgements (scoped to relevant sections)
        val langChainMaturityTerms = listOf(
            "guardrails are experimental",
            "agentic module is experimental",
        )
        for (term in langChainMaturityTerms) {
            require(langChainContent.contains(term, ignoreCase = true)) {
                "Comparison must acknowledge maturity: '$term'"
            }
        }

        val springMaturityTerms = listOf(
            "MCP security",
            "work in progress",
        )
        for (term in springMaturityTerms) {
            require(springAiContent.contains(term, ignoreCase = true)) {
                "Comparison must acknowledge maturity: '$term'"
            }
        }

        val tramaiMaturityTerms = listOf(
            "governed remote MCP client",
            "not implemented",
            "no stable sovereign 1.0 API",
        )
        for (term in tramaiMaturityTerms) {
            require(tramaiContent.contains(term, ignoreCase = true)) {
                "Comparison must acknowledge maturity: '$term'"
            }
        }

        // Required coexistence boundaries (scoped to Coexistence section)
        val coexistenceTerms = listOf(
            "not a drop-in replacement",
            "no official interoperability adapter",
            "architectural composition",
            "not a shipped adapter",
        )
        for (term in coexistenceTerms) {
            require(coexistenceSection.contains(term, ignoreCase = true)) {
                "Comparison must contain coexistence boundary: '$term'"
            }
        }

        // Official source-domain validation (link presence, not network access)
        val requiredSpringLink = "docs.spring.io/spring-ai/reference"
        val requiredLangchainLink = "docs.langchain4j.dev"
        val requiredLangchainRepoLink = "github.com/langchain4j/langchain4j"
        require(text.contains(requiredSpringLink)) {
            "Comparison must link to docs.spring.io/spring-ai/reference"
        }
        require(text.contains(requiredLangchainLink)) {
            "Comparison must link to docs.langchain4j.dev"
        }
        require(text.contains(requiredLangchainRepoLink)) {
            "Comparison must link to github.com/langchain4j/langchain4j"
        }

        // Forbidden claims (case-insensitive)
        val forbiddenClaims = listOf(
            "Spring AI lacks governance",
            "LangChain4j lacks governance",
            "Spring AI has no policy",
            "LangChain4j has no policy",
            "Spring AI cannot block requests",
            "LangChain4j cannot block requests",
            "Spring AI has no tool controls",
        )
        for (claim in forbiddenClaims) {
            require(!text.contains(claim, ignoreCase = true)) {
                "Forbidden claim in comparison: '$claim'"
            }
        }

        // Row-level comparison matrix checks (against capability section only)
        val matrixRows = listOf(
            "| **MCP client** | Implemented | Implemented | Not implemented",
            "| **MCP server** | Implemented | Community server",
            "| **Release maturity** | Stable 2.0.0",
            "Dedicated DLP/redaction",
            "Policy enforcement points with explicit ALLOW/DENY/REQUIRE_APPROVAL",
        )
        for (row in matrixRows) {
            require(capabilitySection.contains(row, ignoreCase = true)) {
                "Capability comparison table must contain row fragment: '$row'"
            }
        }
    }

/** verifyWorkflowApiStabilityBoundary (root build.gradle.kts @ 66198f33, lines 4167–4282). */
fun workflowApiStabilityBoundary(rootDir: File) {

        val boundaryDoc = File(rootDir, "docs/workflow-api-stability-boundary.md")
        require(boundaryDoc.isFile) {
            "Missing workflow API stability boundary document at ${boundaryDoc.absolutePath}."
        }

        val text = boundaryDoc.readText()

        // ── Section extraction helper ──
        fun sectionBetween(text: String, start: String, end: String): String {
            require(text.contains(start)) {
                "Workflow API stability boundary is missing section: $start"
            }
            val after = text.substringAfter(start)
            return after.substringBefore(end)
        }

        // ── Extract each stability section by its heading pair ──
        val stableSection = sectionBetween(text, "## Stable Workflow Surface", "## Preview Workflow Surface")
        val previewSection = sectionBetween(text, "## Preview Workflow Surface", "## Internal Workflow Surface")
        val internalSection = sectionBetween(text, "## Internal Workflow Surface", "## Deferred Workflow Surface")
        val deferredSection = text.substringAfter("## Deferred Workflow Surface")
            .substringBefore("## Cross-References")
        val allowedClaimsSection = sectionBetween(text, "## Allowed Claims", "## Forbidden Claims")
        val forbiddenClaimsSection = text.substringAfter("## Forbidden Claims")
            .substringBefore("## Acceptance Criteria")

        // ── Stable section: core workflow annotations ──
        listOf(
            "@AiService",
            "@Operation",
            "@SystemMessage",
            "@UserMessage",
            "@AiTool",
            "@ConversationId",
            "@AIRange",
            "@AIMinItems",
            "PolicyEngine",
            "PolicyDecision",
            "ApprovalGateway",
            "SovereignWorkflowResult",
            "TramaiException",
        ).forEach { phrase ->
            require(stableSection.contains(phrase)) {
                "Stable workflow API section must contain: $phrase"
            }
        }

        // ── Preview section: evolving capabilities ──
        listOf(
            "orchestration patterns",
            "evidence export",
            "MCP adapter",
            "tool governance",
            "REST/control-plane",
        ).forEach { phrase ->
            require(previewSection.contains(phrase, ignoreCase = true)) {
                "Preview workflow API section must contain: $phrase"
            }
        }

        // ── Internal section: implementation details ──
        listOf(
            "JDBC",
            "Worker lease internals",
            "audit outbox",
            "Gradle verification task",
        ).forEach { phrase ->
            require(internalSection.contains(phrase, ignoreCase = true)) {
                "Internal workflow API section must contain: $phrase"
            }
        }

        // ── Deferred section: out-of-scope capabilities ──
        listOf(
            "Release Console",
            "compliance",
            "attestation",
            "key rotation",
        ).forEach { phrase ->
            require(deferredSection.contains(phrase, ignoreCase = true)) {
                "Deferred workflow API section must contain: $phrase"
            }
        }

        // ── Allowed Claims section must exist and mention key allowed statements ──
        require(allowedClaimsSection.contains("stable", ignoreCase = true)) {
            "Allowed Claims section must contain stability reference."
        }

        // ── Forbidden Claims section must reject overclaims ──
        listOf(
            "all workflow APIs are stable",
            "production-certified",
            "backward compatibility for preview APIs",
            "EU AI Act conformity certification",
            "proves legal or regulatory compliance",
        ).forEach { phrase ->
            require(forbiddenClaimsSection.contains(phrase, ignoreCase = true)) {
                "Forbidden Claims section must reject: $phrase"
            }
        }
    }

/** verifyVersionAlignment (root build.gradle.kts @ 66198f33, lines 4287–4453). */
fun versionAlignment(rootDir: File, expectedVersion: String, expectedReleaseDate: String) {

        // 1. gradle.properties contains exactly tramaiVersion=<expectedVersion>
        val propsFile = File(rootDir, "gradle.properties")
        require(propsFile.isFile) { "Missing gradle.properties" }
        val propsText = propsFile.readText()
        val committedVersion = propsText
            .lineSequence()
            .single { it.startsWith("tramaiVersion=") }
            .substringAfter("=")
            .trim()
        require(committedVersion == expectedVersion) {
            "gradle.properties must set tramaiVersion exactly to $expectedVersion, got '$committedVersion'"
        }

        // 2. Build fallback is expectedVersion
        val buildFile = File(rootDir, "build.gradle.kts")
        val buildText = buildFile.readText()
        require(buildText.contains("orElse(\"$expectedVersion\")")) {
            "build.gradle.kts fallback must be $expectedVersion"
        }

        // 3. CHANGELOG.md has ## Unreleased present above a dated expectedVersion section
        val changelog = File(rootDir, "CHANGELOG.md")
        val changelogText = changelog.readText()
        require(changelogText.contains("## Unreleased")) {
            "CHANGELOG.md must retain ## Unreleased heading"
        }
        // After promotion, ## Unreleased is immediately followed by ## <expectedVersion>
        val afterUnreleased = changelogText.substringAfter("## Unreleased")
        require(afterUnreleased.contains("## $expectedVersion - $expectedReleaseDate")) {
            "CHANGELOG.md must contain a dated $expectedVersion section after ## Unreleased"
        }

        // 5. No active <expectedVersion>-SNAPSHOT references remain
        val snapshotGradleCoordinate = Regex("""dev\.tramai:[a-z0-9-]+:0\.5\.0-SNAPSHOT""")
        val snapshotMavenVersion = Regex("""<version>\s*0\.5\.0-SNAPSHOT\s*</version>""")
        val snapshotVariable = Regex("""tramaiVersion\s*=\s*"0\.5\.0-SNAPSHOT"""")

        // 6. 0.4.0 remains documented as the previous release where relevant
        val statusDoc = File(rootDir, "docs/STATUS.md")
        val statusText = statusDoc.readText()
        require(statusText.contains("0.4.0") && statusText.contains("Latest published release")) {
            "STATUS.md must identify 0.4.0 as latest published release"
        }

        // 7. The roadmap identifies the completed expectedVersion train
        val roadmap = File(rootDir, "docs/POST-SOVEREIGNTY-ROADMAP.md")
        val roadmapText = roadmap.readText()
        require(roadmapText.contains("Release train: TramAI $expectedVersion")) {
            "Roadmap must identify release train $expectedVersion"
        }
        require(roadmapText.contains("$expectedVersion release")) {
            "Roadmap must reference $expectedVersion release"
        }
        // 7b. Roadmap tables use valid Markdown (no line starting with ||)
        require(!roadmapText.lineSequence().any { it.trimStart().startsWith("||") }) {
            "Roadmap contains malformed Markdown table rows beginning with '||' — pipe prefixes must be a single |"
        }

        // 8. Release notes and readiness documents exist
        require(File(rootDir, "docs/releases/$expectedVersion-release-readiness.md").isFile) {
            "Missing $expectedVersion release-readiness document"
        }
        require(File(rootDir, "docs/releases/sovereign-runtime-release-readiness.md").isFile) {
            "Missing sovereign-runtime release-readiness document"
        }

        // 9. Consumer docs use expectedVersion for active coordinates (historical records excluded)
        val consumerDocs = listOf(
            "README.md",
            "docs/guides/getting-started.md",
            "docs/guides/quickstart.md",
            "docs/guides/spring-boot.md",
            "docs/guides/standalone-usage.md",
            "docs/guides/tutorial-invoice-analyzer.md",
            "docs/module-guide.md",
            "docs/STATUS.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
            "docs/reference/releasing.md",
            "examples/README.md",
            "examples/support-agent/build.gradle.kts",
            "examples/kotlin-springboot-example/build.gradle.kts",
            "examples/kotlin-native-smoke-example/build.gradle.kts",
            "examples/sovereign-runtime-consumer-smoke/build.gradle.kts",
            "examples/spring-sovereign-starter/build.gradle.kts",
        )
        // Also check all module docs
        val moduleDocsDir = File(rootDir, "docs/modules")
        val moduleDocs = if (moduleDocsDir.isDirectory) {
            moduleDocsDir.listFiles().orEmpty().filter { it.name.endsWith(".md") }.map { it.path }
        } else emptyList()
        val allConsumerDocs = consumerDocs + moduleDocs

        // Historical allowlist - old release records
        val historicalAllowlist = setOf(
            "docs/releases/CHANGELOG-0.3.1.md",
            "docs/releases/CHANGELOG-0.4.0.md",
            "docs/guides/secure-defaults-migration.md",
            "docs/reference/release-0.1.0.md",
        )

        for (path in allConsumerDocs) {
            val f = File(rootDir, path)
            if (!f.isFile) continue
            if (f.canonicalPath in historicalAllowlist.map { File(rootDir, it).canonicalPath }) continue
            val content = f.readText()

            // No stale SNAPSHOT references in active docs
            require(!snapshotGradleCoordinate.containsMatchIn(content)) {
                "Consumer doc $path still contains dev.tramai:*:0.5.0-SNAPSHOT dependency reference"
            }
            require(!snapshotMavenVersion.containsMatchIn(content)) {
                "Consumer doc $path still contains Maven <version>0.5.0-SNAPSHOT</version>"
            }
            require(!snapshotVariable.containsMatchIn(content)) {
                "Consumer doc $path still contains tramaiVersion = \"0.5.0-SNAPSHOT\""
            }

            // Reject any stale Gradle coordinates (dev.tramai:*:x.y.z where x.y.z != expectedVersion)
            val gradleCoordinatePattern =
                Regex("""dev\.tramai:[a-z0-9-]+:([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)""")
            val staleGradleCoords = gradleCoordinatePattern.findAll(content)
                .filter { it.groupValues[1] != expectedVersion }
                .map { it.value }
                .toList()
            require(staleGradleCoords.isEmpty()) {
                "Consumer doc $path contains stale TramAI Gradle coordinates: ${staleGradleCoords.joinToString()}"
            }

            // Reject any stale Maven versions in dev.tramai dependency blocks
            val mavenDevTramaiDependency =
                Regex("""<groupId>dev\.tramai</groupId>.*?<version>\s*([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)\s*</version>""", setOf(RegexOption.DOT_MATCHES_ALL))
            val staleMvnVersions = mavenDevTramaiDependency.findAll(content)
                .filter { it.groupValues[1] != expectedVersion }
                .map { it.value }
                .toList()
            require(staleMvnVersions.isEmpty()) {
                "Consumer doc $path contains stale TramAI Maven versions: ${staleMvnVersions.joinToString()}"
            }
        }

        // 10. No malformed Markdown tables or prohibited claims
        require(!roadmapText.lineSequence().any { it.trimStart().startsWith("||") }) {
            "Roadmap contains malformed Markdown table rows beginning with '||'"
        }
    }

/** verifyToolGovernanceExample (root build.gradle.kts @ 66198f33, lines 4459–4526). */
fun moduleDocContract(rootDir: File): List<dev.tramai.build.quality.VerificationDiagnostic> =
        dev.tramai.build.quality.ModuleDocContractVerifier.verify(rootDir)

fun toolGovernanceExample(rootDir: File) {

        val exampleDir = File(rootDir, "examples/tool-governance")
        val settingsText = File(rootDir, "settings.gradle.kts").readText()
        val examplesReadme = File(rootDir, "examples/README.md").readText()
        val guideFile = File(rootDir, "docs/guides/governed-tool-use.md")

        require(exampleDir.isDirectory) {
            "examples/tool-governance/ directory must exist"
        }
        require(settingsText.contains("\"examples:tool-governance\"")) {
            "settings.gradle.kts must include examples:tool-governance"
        }
        require(File(rootDir, "examples/tool-governance/src/main/kotlin/dev/tramai/examples/toolgovernance/ToolGovernanceMain.kt").isFile) {
            "ToolGovernanceMain.kt must exist"
        }
        require(File(rootDir, "examples/tool-governance/README.md").isFile) {
            "examples/tool-governance/README.md must exist"
        }
        require(examplesReadme.contains("./gradlew :examples:tool-governance:run")) {
            "examples/README.md must contain the exact run command"
        }
        // Verify the example matrix uses :run as primary command
        val matrixLine = examplesReadme.lines().find { it.contains("Tool Governance") && it.contains("./gradlew") }
        require(matrixLine != null && matrixLine.contains(":run")) {
            "examples/README.md example matrix must use :run as primary command for tool-governance, found: ${matrixLine?.take(80)}"
        }
        require(guideFile.isFile) {
            "docs/guides/governed-tool-use.md must exist"
        }
        val guideText = guideFile.readText()
        require(guideText.contains("./gradlew :examples:tool-governance:run")) {
            "governed-tool-use.md must contain the run command"
        }
        for (ep in listOf("BEFORE_TOOL_EXPOSURE", "BEFORE_TOOL_EXECUTION", "BEFORE_TOOL_RESULT_REINJECTION")) {
            require(guideText.contains(ep)) {
                "governed-tool-use.md must mention enforcement point '$ep'"
            }
        }
        for (decision in listOf("ALLOW", "DENY", "REQUIRE_APPROVAL")) {
            require(guideText.contains(decision)) {
                "governed-tool-use.md must mention decision '$decision'"
            }
        }
        require(guideText.contains("tool.permission")) {
            "governed-tool-use.md must reference tool.permission evidence"
        }
        require(guideText.contains("exposure permission is not execution permission")) {
            "governed-tool-use.md must state that exposure permission is not execution permission"
        }
        require(guideText.contains("never appear")) {
            "governed-tool-use.md must contain privacy boundaries"
        }
        require(guideText.contains("compliance") && guideText.contains("certification")) {
            "governed-tool-use.md must contain non-compliance and non-certification boundaries"
        }

        val roadmapText = File(rootDir, "docs/POST-SOVEREIGNTY-ROADMAP.md").readText()
        require(roadmapText.contains("PR #201")) {
            "POST-SOVEREIGNTY-ROADMAP.md must reference PR #201"
        }
    }
}
