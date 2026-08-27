package dev.tramai.build.quality

import java.io.File
import java.io.FileFilter

/**
 * Mechanical documentation-contract verifier for module cards (Epic 11.2b3).
 *
 * Enforces only what can be checked mechanically:
 *  - one card per manifest module, no orphans
 *  - required C2b headings present
 *  - local Markdown links + recognizable inline repo paths resolve
 *  - no legacy hand-maintained classification metadata (Module type: /
 *    duplicated maturity / publishability / release fields)
 *  - no versionless dev.tramai:* dependency snippets without a BOM import
 *    or explicit version in the same self-contained example
 *  - internal/unpublished modules must not advertise dev.tramai:<module>
 *    Maven consumption
 *  - README coverage counts match manifest/cards reality
 *
 * It deliberately does NOT assert semantics (spec-link relevance, lifecycle/
 * thread-safety truth, public-JVM vs supported-API intent).
 */
object ModuleDocContractVerifier {

    val REQUIRED_HEADINGS = listOf(
        "Responsibility",
        "Public entry points",
        "Internal extension points",
        "Significant dependencies",
        "Lifecycle ownership",
        "Thread-safety and concurrency",
        "Failure semantics",
        "Contract tests / TCKs",
        "Do not",
        "Related architecture",
    )

    /**
     * Legacy manual architecture-classification metadata — rejected ONLY in the
     * top-level metadata area (header block before the first `##` section) and
     * ONLY in specific manifest-duplicating shapes. Prose/table uses of
     * "Status:"/"Role:" elsewhere (runtime/workflow state, descriptions) are
     * legitimate documentation vocabulary and must NOT be rejected.
     */
    private val LEGACY_CLASSIFICATION_PATTERNS = listOf(
        Regex("""\*\*Module type:\*\*"""),
        Regex("""\*\*Source files:\*\*"""),
        Regex("""\*\*Test files:\*\*"""),
        Regex("""\*\*Build:\*\*\s*`?dev\.tramai"""),
        Regex("""\*\*Version:\*\*\s*`?\d+\.\d+"""),
        Regex("""\*\*Status:\*\*\s*(Stable|Preview|Internal|Experimental|GA|Beta|Alpha)"""),
        Regex("""\*\*Publishability:\*\*"""),
        Regex("""\*\*Maturity:\*\*"""),
        Regex("""\*\*Release:\*\*"""),
    )

    private val MANIFEST_LINK_MARKER = "module-catalog.yml"
    private val README = "README.md"

    /** Verifies all module cards under docsDir against the manifest. */
    fun verify(rootDir: File): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val catalog = ModuleCatalog(rootDir).parse()
        val modules = catalog.modules
        val docsDir = File(rootDir, "docs/modules")
        if (!docsDir.isDirectory) {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.MODULE_CARD_MISSING,
                "docs/modules directory does not exist",
            )
            return diagnostics
        }

        val cardFiles = docsDir.listFiles(FileFilter { f ->
            f.name.endsWith(".md") &&
                f.name != README &&
                // Reference document, not a module card (README documents this).
                f.name != "sovereign-runtime-module-matrix.md"
        })
            ?.sortedBy { it.name }
            ?: emptyList<File>()
        val cardNames = cardFiles.map { it.name.removeSuffix(".md") }.toSet()

        // 1. One card per manifest module (missing / orphan).
        // Manifest keys are full Gradle paths (":tramai-server"); cards use the
        // bare module name. Normalize to the suffix after the last ':'.
        val manifestNames = modules.keys.map { it.substringAfterLast(':') }.toSet()
        for (name in manifestNames) {
            if (name !in cardNames) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_MISSING,
                    "Manifest module '$name' has no card in docs/modules",
                    modulePath = name,
                )
            }
        }
        for (card in cardNames) {
            if (card !in manifestNames) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_ORPHAN,
                    "Card '$card' has no manifest module",
                    modulePath = card,
                )
            }
        }

        // 2-6. Per-card checks
        for (file in cardFiles) {
            val name = file.name.removeSuffix(".md")
            val text = file.readText()
            val entry = modules[name] ?: modules[":$name"]

            // Headings
            for (heading in REQUIRED_HEADINGS) {
                if (!text.contains("### $heading")) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_HEADING_MISSING,
                        "Card '$name' is missing heading '### $heading'",
                        modulePath = name,
                    )
                }
            }

            // Legacy classification metadata — ONLY in the top-level metadata
            // block (before the first '##' section), and ONLY for the specific
            // manifest-duplicating shapes above. Prose/table "Status:"/"Role:"
            // elsewhere is legitimate vocabulary, not legacy classification.
            val header = text.substringBefore("## ")
            for (pattern in LEGACY_CLASSIFICATION_PATTERNS) {
                if (pattern.containsMatchIn(header)) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION,
                        "Card '$name' header contains legacy classification metadata matching ${pattern.pattern}",
                        modulePath = name,
                    )
                }
            }

            // Manifest classification link present in the header block (before first '##')
            if (entry != null && MANIFEST_LINK_MARKER !in header) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION,
                    "Card '$name' header must link classification to module-catalog.yml",
                    modulePath = name,
                )
            }

            // Local markdown links resolve
            val linkRegex = Regex("""\[[^\]]*\]\(([^)#]+)""")
            for (match in linkRegex.findAll(text)) {
                val target = match.groupValues[1]
                if (target.startsWith("http") || target.startsWith("#")) continue
                val resolved = File(docsDir, target).normalize()
                if (!resolved.isFile) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_LINK_BROKEN,
                        "Card '$name' link '$target' does not resolve (${resolved.path})",
                        modulePath = name,
                    )
                }
            }

            // Inline repo paths resolve (docs/... or config/... or examples/...)
            val inlineRegex = Regex("""`((?:docs|config|examples)/[A-Za-z0-9_./-]+\.(?:md|yml|yaml|json|kt|kts))`""")
            for (match in inlineRegex.findAll(text)) {
                val path = match.groupValues[1]
                if (!File(rootDir, path).isFile) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_INLINE_PATH_BROKEN,
                        "Card '$name' inline repo path '$path' does not resolve",
                        modulePath = name,
                    )
                }
            }

            // Versionless dev.tramai deps: every standalone kotlin snippet with
            // dev.tramai deps must import the BOM or use an explicit version.
            val kotlinBlocks = Regex("""```kotlin\n(.*?)```""", setOf(RegexOption.DOT_MATCHES_ALL))
            for (block in kotlinBlocks.findAll(text)) {
                val code = block.groupValues[1]
                val deps = Regex("""implementation\("dev\.tramai:[a-z0-9-]+"\)""").findAll(code).map { it.value }.toList()
                if (deps.isEmpty()) continue
                val hasBom = code.contains("tramai-bom:") || code.contains("tramai-bom\"")
                val hasExplicitVersion = Regex("""dev\.tramai:[a-z0-9-]+:\$""").containsMatchIn(code)
                val hasProjectDep = code.contains("project(\":")
                if (!hasBom && !hasExplicitVersion && !hasProjectDep) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_VERSIONLESS_DEPENDENCY,
                        "Card '$name' has versionless dependency snippet without BOM import or explicit version: ${deps.first()}",
                        modulePath = name,
                    )
                }
            }

            // Internal/unpublished modules must not advertise external Maven consumption.
            // Only the module's OWN artifact coordinate is rejected; mentioning
            // other (published) TramAI dependencies is legitimate.
            if (entry != null && entry.publishability != ModulePublishability.PUBLISHED) {
                val mavenAd = Regex("""implementation\("dev\.tramai:${Regex.escape(name)}"\)""").containsMatchIn(text)
                if (mavenAd) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT,
                        "Internal/unpublished module '$name' advertises external Maven consumption (dev.tramai:$name)",
                        modulePath = name,
                    )
                }
            }
        }

        // 7. README coverage counts match reality
        val readme = File(docsDir, README)
        if (readme.isFile) {
            val readmeText = readme.readText()
            val manifestCount = modules.size
            val expected = "| Manifest modules | $manifestCount |"
            if (expected !in readmeText) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH,
                    "README coverage table must state Manifest modules = $manifestCount",
                )
            }
            val expectedCards = "| Module cards | ${cardNames.size} |"
            if (expectedCards !in readmeText) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH,
                    "README coverage table must state Module cards = ${cardNames.size}",
                )
            }
            // All cards conform => non-conforming 0, missing 0, orphans 0
            if (diagnostics.none { it.code == DiagnosticCode.MODULE_CARD_HEADING_MISSING ||
                    it.code == DiagnosticCode.MODULE_CARD_MISSING ||
                    it.code == DiagnosticCode.MODULE_CARD_ORPHAN }) {
                if ("| Existing non-conforming | 0 |" !in readmeText ||
                    "| Missing cards | 0 |" !in readmeText ||
                    "| Orphans | 0 |" !in readmeText) {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH,
                        "README coverage table must state non-conforming 0, missing 0, orphans 0 at 60/60 closure",
                    )
                }
            }
        }

        return diagnostics
    }
}
