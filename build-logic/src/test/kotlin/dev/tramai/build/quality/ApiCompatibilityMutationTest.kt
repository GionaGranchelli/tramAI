package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED discriminator suite (B-series) for Epic 10.2 API compatibility closure.
 *
 * Every test must FAIL before the ApiCompatibilityVerifier / taxonomy changes
 * exist and PASS after. Tests reference the intended API only; the missing
 * types are the RED state.
 *
 * Contract 1 = source ↔ committed dump. Contract 2 = base-branch dump ↔
 * current dump (drives stability policy).
 */
class ApiCompatibilityMutationTest {

    @TempDir
    lateinit var tempDir: File

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun entry(
        path: String,
        apiStability: String,
        maturity: String = if (apiStability == "internal") "internal" else "preview",
        publishability: String = "published",
    ): ModuleCatalog.ModuleEntry = ModuleCatalog.ModuleEntry(
        path = path,
        layer = ModuleLayer.fromYaml("core-contracts") ?: error("bad layer"),
        maturity = ModuleMaturity.fromYaml(maturity) ?: error("bad maturity $maturity"),
        publishability = ModulePublishability.fromYaml(publishability) ?: error("bad pub $publishability"),
        apiStability = ModuleApiStability.fromYaml(apiStability) ?: error("bad api $apiStability"),
        visibility = ModuleVisibility.fromYaml("public") ?: error("bad vis"),
        owner = "test",
        dependencyPolicy = "core",
        releaseInclusion = ReleaseInclusion.fromYaml("included") ?: error("bad rel"),
        rationale = "Test fixture entry."
    )

    private val catalog = mapOf(
        ":tramai-core" to entry(":tramai-core", "stable", maturity = "stable"),
        ":tramai-engine" to entry(":tramai-engine", "preview"),
        ":tramai-experimental" to entry(":tramai-experimental", "experimental", maturity = "experimental"),
        ":tramai-internal" to entry(":tramai-internal", "internal", publishability = "internal"),
        ":tramai-preview-leak" to entry(":tramai-preview-leak", "preview"),
    )

    private fun sha256(content: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun dump(module: String, vararg lines: String): String =
        (listOf("public final class dev/tramai/${module.removePrefix(":tramai-")}/Main {") + lines + listOf("}"))
            .joinToString("\n") + "\n"

    private fun evidence(
        committed: Map<String, String>,
        generated: Map<String, String> = committed,
        base: Map<String, String> = committed,
    ) = ApiDumpEvidence(
        generated = generated,
        committed = committed,
        base = base,
    )

    private fun verifier() = ApiCompatibilityVerifier(
        catalogModules = catalog,
        projectVersion = "0.6.0",
    )

    private fun compatCodes(diagnostics: List<VerificationDiagnostic>): Set<DiagnosticCode> =
        diagnostics.filter { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED }.map { it.code }.toSet()

    // ── B0: Contract 1 — source/current-dump mismatch ────────────────────

    @Test
    fun `B0 source current dump mismatch fails regardless of stability`() {
        val committed = mapOf(":tramai-core" to dump(":tramai-core", "\tpublic fun a ()V"))
        val generated = mapOf(":tramai-core" to dump(":tramai-core", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val diagnostics = verifier().verify(evidence(committed = committed, generated = generated), migrations = emptyList())
        assertTrue(
            diagnostics.any {
                it.code == DiagnosticCode.API_COMPATIBILITY_FAILED &&
                    it.message.contains("does not represent current source")
            },
            "expected Contract-1 source/committed mismatch to fail, got: $diagnostics"
        )
    }

    // ── B0b: Contract 1 — missing generated evidence cannot PASS ───────────

    @Test
    fun `B0b missing generated evidence fails fail-closed`() {
        val committed = mapOf(":tramai-core" to dump(":tramai-core", "\tpublic fun a ()V"))
        // generated evidence entirely absent (clean workspace before apiBuild)
        val diagnostics = verifier().verify(
            evidence(committed = committed, generated = emptyMap()),
            migrations = emptyList()
        )
        assertTrue(
            diagnostics.any {
                it.code == DiagnosticCode.API_COMPATIBILITY_FAILED &&
                    it.message.contains("generated (apiBuild) API dump") && it.message.contains(":tramai-core")
            },
            "missing generated evidence must fail, not silently pass, got: $diagnostics"
        )
    }

    // ── B1: Contract 2 — stable base→current change fails, additive too ──

    @Test
    fun `B1 stable additive API change fails without migration rescue`() {
        val base = mapOf(":tramai-core" to dump(":tramai-core", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-core" to dump(":tramai-core", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val migration = ApiMigrationEntry(
            module = ":tramai-core",
            fromSha256 = sha256(base.getValue(":tramai-core")),
            toSha256 = sha256(current.getValue(":tramai-core")),
            targetVersion = "0.6.0",
            rationale = "Added b",
            migration = "No action needed."
        )
        val diagnostics = verifier().verify(evidence(committed = current, base = base), migrations = listOf(migration))
        assertTrue(
            diagnostics.any {
                it.code == DiagnosticCode.API_COMPATIBILITY_FAILED &&
                    it.message.contains("stable") && it.message.contains(":tramai-core")
            },
            "stable additive change must fail even with a migration entry, got: $diagnostics"
        )
    }

    // ── B2/B3: preview — migration is exact-transition evidence ──────────

    @Test
    fun `B2 preview change without exact migration fails`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val diagnostics = verifier().verify(evidence(committed = current, base = base), migrations = emptyList())
        assertTrue(compatCodes(diagnostics).isNotEmpty(), "preview drift without migration must fail, got: $diagnostics")
    }

    @Test
    fun `B3 preview change with exact hash-bound migration passes`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val migration = ApiMigrationEntry(
            module = ":tramai-engine",
            fromSha256 = sha256(base.getValue(":tramai-engine")),
            toSha256 = sha256(current.getValue(":tramai-engine")),
            targetVersion = "0.6.0",
            rationale = "Added b",
            migration = "No action needed."
        )
        val diagnostics = verifier().verify(evidence(committed = current, base = base), migrations = listOf(migration))
        assertTrue(
            diagnostics.none { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "exact transition entry must authorize preview change, got: $diagnostics"
        )
    }

    // ── B4: internal/excluded drift is not a compat failure ──────────────

    @Test
    fun `B4 internal drift does not fail compatibility`() {
        val base = mapOf(":tramai-internal" to dump(":tramai-internal", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-internal" to dump(":tramai-internal", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val diagnostics = verifier().verify(evidence(committed = current, base = base), migrations = emptyList())
        assertTrue(
            diagnostics.none { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "internal drift must not be a compat failure, got: $diagnostics"
        )
    }

    // ── B5: stability inversion — stronger API leaking weaker type ───────

    @Test
    fun `B5 stable dump referencing preview owned type fails`() {
        // preview module owns dev/tramai/preview-leak/Internal
        val previewDump =
            "public final class dev/tramai/preview-leak/Internal {\n\tpublic fun a ()V\n}\n"
        val baseStable = dump(":tramai-core", "\tpublic fun a ()V")
        // current stable dump now references a class owned by the preview module
        val currentStable = dump(":tramai-core", "\tpublic fun a ()V", "\tpublic fun f (Ldev/tramai/preview-leak/Internal;)V")
        val committed = mapOf(
            ":tramai-core" to currentStable,
            ":tramai-preview-leak" to previewDump,
        )
        val base = mapOf(
            ":tramai-core" to baseStable,
            ":tramai-preview-leak" to previewDump,
        )
        val diagnostics = verifier().verify(evidence(committed = committed, base = base), migrations = emptyList())
        assertTrue(
            diagnostics.any {
                it.code == DiagnosticCode.API_COMPATIBILITY_FAILED &&
                    it.message.contains("inversion") && it.message.contains(":tramai-core")
            },
            "stable→preview type leak must fail, got: $diagnostics"
        )
    }

    // ── B6: experimental classification works end-to-end ─────────────────

    @Test
    fun `B6 experimental classification is accepted and requires migration`() {
        assertEquals("experimental", ModuleApiStability.fromYaml("experimental")?.yaml)
        val base = mapOf(":tramai-experimental" to dump(":tramai-experimental", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-experimental" to dump(":tramai-experimental", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val withoutEntry = verifier().verify(evidence(committed = current, base = base), migrations = emptyList())
        assertTrue(
            compatCodes(withoutEntry).isNotEmpty(),
            "experimental drift without entry must fail, got: $withoutEntry"
        )
        val withEntry = verifier().verify(
            evidence(committed = current, base = base),
            migrations = listOf(
                ApiMigrationEntry(
                    module = ":tramai-experimental",
                    fromSha256 = sha256(base.getValue(":tramai-experimental")),
                    toSha256 = sha256(current.getValue(":tramai-experimental")),
                    targetVersion = "0.6.0",
                    rationale = "Experiments.",
                    migration = "Experimental."
                )
            )
        )
        assertTrue(
            withEntry.none { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "experimental drift with exact entry must pass, got: $withEntry"
        )
    }

    // ── B7: invalid maturity/API-strength combination fails ──────────────

    @Test
    fun `B7 stable API with preview or experimental maturity fails catalog validation`() {
        val catalogFile = File(tempDir, "config/quality/module-catalog.yml").apply {
            parentFile.mkdirs()
            writeText(
                """
                schemaVersion: "2"
                description: "test"
                dependencyPolicies:
                  core: { allowedLayers: [core-contracts] }
                entryDefaults: {}
                modules:
                  - path: ":bad-stable-preview"
                    layer: core-contracts
                    maturity: preview
                    visibility: public
                    owner: test
                    dependencyPolicy: core
                    releaseInclusion: included
                    publishability: published
                    apiStability: stable
                """.trimIndent()
            )
        }
        val result = ModuleCatalog(tempDir).parse()
        assertTrue(
            result.errors.any { it.code == DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION },
            "stable API + preview maturity must fail catalog validation, got: ${result.errors}"
        )
    }

    // ── B8: malformed/duplicate/orphan/hash-mismatched migrations fail ───

    @Test
    fun `B8 wrong-hash migration entry fails`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val migration = ApiMigrationEntry(
            module = ":tramai-engine",
            fromSha256 = "deadbeef",
            toSha256 = "cafebabe",
            targetVersion = "0.6.0",
            rationale = "wrong hashes",
            migration = "x"
        )
        val diagnostics = verifier().verify(evidence(committed = current, base = base), migrations = listOf(migration))
        assertTrue(compatCodes(diagnostics).isNotEmpty(), "wrong-hash entry must fail, got: $diagnostics")
    }

    @Test
    fun `B8 duplicate migration entries fail`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val from = sha256(base.getValue(":tramai-engine"))
        val to = sha256(current.getValue(":tramai-engine"))
        val diagnostics = verifier().verify(
            evidence(committed = current, base = base),
            migrations = listOf(
                ApiMigrationEntry(":tramai-engine", from, to, "0.6.0", "r1", "m1"),
                ApiMigrationEntry(":tramai-engine", from, to, "0.6.0", "r2", "m2"),
            )
        )
        assertTrue(compatCodes(diagnostics).isNotEmpty(), "duplicate entries must fail, got: $diagnostics")
    }

    @Test
    fun `B8 orphan migration entry for unchanged module fails`() {
        val committed = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V"))
        val diagnostics = verifier().verify(
            evidence(committed = committed),
            migrations = listOf(
                ApiMigrationEntry(
                    ":tramai-engine",
                    sha256(dump(":tramai-engine", "\tpublic fun a ()V")),
                    "deadbeef",
                    "0.6.0",
                    "orphan",
                    "m"
                )
            )
        )
        assertTrue(compatCodes(diagnostics).isNotEmpty(), "orphan entry must fail, got: $diagnostics")
    }

    @Test
    fun `B8 targetVersion mismatch fails`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "\tpublic fun a ()V", "\tpublic fun b ()V"))
        val migration = ApiMigrationEntry(
            module = ":tramai-engine",
            fromSha256 = sha256(base.getValue(":tramai-engine")),
            toSha256 = sha256(current.getValue(":tramai-engine")),
            targetVersion = "0.5.0",
            rationale = "wrong version",
            migration = "x"
        )
        val diagnostics = verifier().verify(evidence(committed = current, base = base), migrations = listOf(migration))
        assertTrue(compatCodes(diagnostics).isNotEmpty(), "targetVersion mismatch must fail, got: $diagnostics")
    }

    // ── B9/B10: consumer proofs require real sources and real classes ────

    @Test
    fun `B9 java consumer fixture has real sources`() {
        val sourceDir = File(repoRoot(), "examples/java-consumer-smoke/src/main/java")
        val diagnostics = ConsumerCompatibilityGuard.validateSources(sourceDir, "java")
        assertTrue(
            diagnostics.none { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "java consumer fixture must have real non-empty sources, got: $diagnostics"
        )
    }

    @Test
    fun `B10 kotlin consumer fixture has real sources`() {
        val sourceDir = File(repoRoot(), "examples/kotlin-consumer-smoke/src/main/kotlin")
        val diagnostics = ConsumerCompatibilityGuard.validateSources(sourceDir, "kotlin")
        assertTrue(
            diagnostics.none { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "kotlin consumer fixture must have real non-empty sources, got: $diagnostics"
        )
    }

    @Test
    fun `B9 java consumer guard fails on empty source dir`() {
        val empty = File(tempDir, "empty").apply { mkdirs() }
        val diagnostics = ConsumerCompatibilityGuard.validate(empty, File(tempDir, "classes"), "java")
        assertTrue(
            diagnostics.any { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "empty consumer source set must fail, got: $diagnostics"
        )
    }

    @Test
    fun `B9 java consumer guard fails when classes were not produced`() {
        val sources = File(tempDir, "src").apply { mkdirs() }
        File(sources, "Consumer.java").writeText("public final class Consumer {}\n")
        val diagnostics = ConsumerCompatibilityGuard.validate(sources, File(tempDir, "missing-classes"), "java")
        assertTrue(
            diagnostics.any { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "consumer guard must fail when compilation produced no classes, got: $diagnostics"
        )
    }

    @Test
    fun `B9 java consumer guard passes with real sources and produced classes`() {
        val sources = File(tempDir, "src").apply { mkdirs() }
        File(sources, "Consumer.java").writeText("public final class Consumer {}\n")
        val classes = File(tempDir, "classes").apply { mkdirs() }
        File(classes, "Consumer.class").writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        val diagnostics = ConsumerCompatibilityGuard.validate(sources, classes, "java")
        assertTrue(
            diagnostics.none { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "consumer guard must pass with sources and classes present, got: $diagnostics"
        )
    }

    // ── C6: malformed migration registry produces typed FAIL diagnostics ───

    @Test
    fun `C6 non-map migration entry fails parse`() {
        val f = File(tempDir, "migrations.yml")
        f.writeText("migrations:\n  - definitely-not-a-valid-entry\n")
        val result = ApiCompatibilityEvidenceReader.parseMigrations(f)
        assertTrue(
            result.diagnostics.any { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "non-map entry must produce a parse diagnostic, got: ${result.diagnostics}"
        )
    }

    @Test
    fun `C6 missing required fields fails parse`() {
        val f = File(tempDir, "migrations.yml")
        f.writeText(
            """
            migrations:
              - module: ":tramai-engine"
                fromSha256: "${"a".repeat(64)}"
                toSha256: "${"b".repeat(64)}"
            """.trimIndent()
        )
        val result = ApiCompatibilityEvidenceReader.parseMigrations(f)
        assertTrue(
            result.diagnostics.any { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "blank required fields must produce a parse diagnostic, got: ${result.diagnostics}"
        )
    }

    @Test
    fun `C6 non-hex sha256 fails parse`() {
        val f = File(tempDir, "migrations.yml")
        f.writeText(
            """
            migrations:
              - module: ":tramai-engine"
                fromSha256: "not-a-hash"
                toSha256: "${"b".repeat(64)}"
                targetVersion: "0.6.0"
                rationale: "r"
                migration: "m"
            """.trimIndent()
        )
        val result = ApiCompatibilityEvidenceReader.parseMigrations(f)
        assertTrue(
            result.diagnostics.any {
                it.code == DiagnosticCode.API_COMPATIBILITY_FAILED && it.message.contains("invalid sha256")
            },
            "non-hex hash must produce a parse diagnostic, got: ${result.diagnostics}"
        )
    }

    @Test
    fun `C6 migrations not a list fails parse`() {
        val f = File(tempDir, "migrations.yml")
        f.writeText("migrations: not-a-list\n")
        val result = ApiCompatibilityEvidenceReader.parseMigrations(f)
        assertTrue(
            result.diagnostics.any { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED },
            "non-list migrations must produce a parse diagnostic, got: ${result.diagnostics}"
        )
    }

    @Test
    fun `C6 valid entry parses without diagnostics`() {
        val f = File(tempDir, "migrations.yml")
        f.writeText(
            """
            migrations:
              - module: ":tramai-engine"
                fromSha256: "${"a".repeat(64)}"
                toSha256: "${"b".repeat(64)}"
                targetVersion: "0.6.0"
                rationale: "r"
                migration: "m"
            """.trimIndent()
        )
        val result = ApiCompatibilityEvidenceReader.parseMigrations(f)
        assertTrue(result.diagnostics.isEmpty(), "valid entry must parse clean, got: ${result.diagnostics}")
        assertEquals(1, result.entries.size)
    }

    private fun repoRoot(): File =
        System.getProperty("tramai.repositoryRoot")
            ?.let { File(it) }
            ?: error("tramai.repositoryRoot system property not set (wired by build-logic/build.gradle.kts)")
}
