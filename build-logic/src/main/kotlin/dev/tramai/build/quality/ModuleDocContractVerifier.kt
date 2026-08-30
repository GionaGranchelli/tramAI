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
    val REQUIRED_HEADINGS =
        listOf(
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
    private val LEGACY_CLASSIFICATION_PATTERNS =
        listOf(
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
        val catalog = ModuleCatalog.fromRootDir(rootDir).parse()
        val modules = catalog.modules
        val docsDir = File(rootDir, "docs/modules")
        if (!docsDir.isDirectory) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_MISSING,
                    "docs/modules directory does not exist",
                )
            return diagnostics
        }

        val cardFiles =
            docsDir
                .listFiles(
                    FileFilter { f ->
                        f.name.endsWith(".md") &&
                            f.name != README &&
                            // Reference document, not a module card (README documents this).
                            f.name != "sovereign-runtime-module-matrix.md"
                    },
                )?.sortedBy { it.name }
                ?: emptyList<File>()
        val cardNames = cardFiles.map { it.name.removeSuffix(".md") }.toSet()

        // 1. One card per manifest module (missing / orphan).
        // Manifest keys are full Gradle paths (":tramai-server"); cards use the
        // bare module name. Normalize to the suffix after the last ':'.
        val manifestNames = modules.keys.map { it.substringAfterLast(':') }.toSet()
        for (name in manifestNames) {
            if (name !in cardNames) {
                diagnostics +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CARD_MISSING,
                        "Manifest module '$name' has no card in docs/modules",
                        modulePath = name,
                    )
            }
        }
        for (card in cardNames) {
            if (card !in manifestNames) {
                diagnostics +=
                    VerificationDiagnostic.failure(
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

            // Headings — exact Markdown heading lines ("### Responsibility" must
            // be its own line; "### ResponsibilityXYZ" does not satisfy it).
            for (heading in REQUIRED_HEADINGS) {
                val headingLine = Regex("""^### ${Regex.escape(heading)}\s*$""", RegexOption.MULTILINE)
                if (!headingLine.containsMatchIn(text)) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
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
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION,
                            "Card '$name' header contains legacy classification metadata matching ${pattern.pattern}",
                            modulePath = name,
                        )
                }
            }

            // Manifest classification link present in the header block (before first '##')
            if (entry != null && MANIFEST_LINK_MARKER !in header) {
                diagnostics +=
                    VerificationDiagnostic.failure(
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
                    diagnostics +=
                        VerificationDiagnostic.failure(
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
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MODULE_CARD_INLINE_PATH_BROKEN,
                            "Card '$name' inline repo path '$path' does not resolve",
                            modulePath = name,
                        )
                }
            }

            // Versionless dev.tramai deps: every external dev.tramai:<artifact>
            // declaration in a kotlin snippet must be either explicitly
            // versioned, or covered by a VERSIONED TramAI BOM import in the
            // same block. No block-wide exemptions: an explicit version on an
            // unrelated artifact, or a project(":...") dependency, does not
            // exempt a versionless Maven coordinate. An unversioned
            // platform("dev.tramai:tramai-bom") is invalid coverage.
            val kotlinBlocks = Regex("""```kotlin\n(.*?)```""", setOf(RegexOption.DOT_MATCHES_ALL))
            for (block in kotlinBlocks.findAll(text)) {
                val code = block.groupValues[1]
                val hasVersionedBom = Regex("""tramai-bom:[^\s"]+""").containsMatchIn(code)
                val declarationRegex = Regex("""(?:implementation|api)\(\s*"dev\.tramai:([a-z0-9-]+)(?::([^"]*))?"\s*\)""")
                for (decl in declarationRegex.findAll(code)) {
                    val artifact = decl.groupValues[1]
                    val version = decl.groupValues[2]
                    if (artifact == "tramai-bom") continue // BOM import handled via hasVersionedBom
                    if (version.isBlank() && !hasVersionedBom) {
                        diagnostics +=
                            VerificationDiagnostic.failure(
                                DiagnosticCode.MODULE_CARD_VERSIONLESS_DEPENDENCY,
                                "Card '$name' has versionless dependency declaration without a versioned TramAI BOM import in the same snippet: dev.tramai:$artifact",
                                modulePath = name,
                            )
                    }
                }
            }

            // Internal/unpublished modules must not advertise external Maven consumption.
            // Only the module's OWN artifact coordinate is rejected — in Gradle
            // (implementation/api, with or without a version) or Maven XML —
            // regardless of version. Mentioning other (published) TramAI
            // dependencies is legitimate.
            if (entry != null && entry.publishability != ModulePublishability.PUBLISHED) {
                val ownGradle =
                    Regex(
                        """(?:implementation|api)\(\s*"dev\.tramai:${Regex.escape(name)}(?::[^"]*)?"\s*\)""",
                    ).containsMatchIn(text)
                val ownMaven = Regex("""<artifactId>\s*${Regex.escape(name)}\s*</artifactId>""").containsMatchIn(text)
                if (ownGradle || ownMaven) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT,
                            "Internal/unpublished module '$name' advertises external Maven consumption (dev.tramai:$name)",
                            modulePath = name,
                        )
                }
            }
        }

        // 7. README coverage counts match reality — README absent is a failure.
        val readme = File(docsDir, README)
        if (!readme.isFile) {
            diagnostics +=
                VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH,
                    "docs/modules/README.md is missing — coverage counts cannot be verified",
                )
        } else {
            val readmeText = readme.readText()
            val manifestCount = modules.size
            // A card is conforming when it carries all required headings and no
            // legacy header classification (the two per-card contract checks
            // that define the conforming/non-conforming split in the README).
            var conforming = 0
            for (file in cardFiles) {
                val cardText = file.readText()
                val cardHeader = cardText.substringBefore("## ")
                val allHeadings =
                    REQUIRED_HEADINGS.all {
                        Regex(
                            """^### ${Regex.escape(it)}\s*$""",
                            RegexOption.MULTILINE,
                        ).containsMatchIn(cardText)
                    }
                val noLegacy = LEGACY_CLASSIFICATION_PATTERNS.none { it.containsMatchIn(cardHeader) }
                if (allHeadings && noLegacy) conforming++
            }
            val missing = (manifestNames - cardNames).size
            val orphans = (cardNames - manifestNames).size
            val expected =
                mapOf(
                    "Manifest modules" to manifestCount,
                    "Module cards" to cardNames.size,
                    "Conforming cards" to conforming,
                    "Existing non-conforming" to (cardNames.size - conforming),
                    "Missing cards" to missing,
                    "Orphans" to orphans,
                )
            for ((label, count) in expected) {
                val expectedLine = "| $label | $count |"
                if (expectedLine !in readmeText) {
                    diagnostics +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH,
                            "README coverage table must state '$label = $count' (found: $expectedLine not present)",
                        )
                }
            }
        }

        return diagnostics
    }
}
