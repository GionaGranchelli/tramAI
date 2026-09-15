package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Discriminators for the release-line API policy settled in 0.7.0 development:
 *
 * - **Stable = backward compatible**, not byte-identical. Additions pass; removals, signature
 *   changes and visibility reductions fail; migration entries can never authorize stable breakage.
 * - **Migration targets are release-bound**: an ACTIVE entry declares the release its project
 *   version belongs to (`0.7.0-SNAPSHOT` -> `0.7.0`), so the same entry is truthful during
 *   development and after the release cut.
 *
 * Each case below is a row of the agreed policy table.
 */
class ApiStabilityPolicyTest {
    // ── fixtures ──────────────────────────────────────────────────────────

    private fun entry(
        path: String,
        apiStability: String,
    ): ModuleCatalog.ModuleEntry =
        ModuleCatalog.ModuleEntry(
            path = path,
            layer = ModuleLayer.fromYaml("core-contracts") ?: error("bad layer"),
            maturity =
                ModuleMaturity.fromYaml(if (apiStability == "stable") "stable" else "preview")
                    ?: error("bad maturity"),
            publishability = ModulePublishability.fromYaml("published") ?: error("bad publishability"),
            apiStability = ModuleApiStability.fromYaml(apiStability) ?: error("bad apiStability"),
            visibility = ModuleVisibility.fromYaml("public") ?: error("bad visibility"),
            owner = "test",
            dependencyPolicy = "core",
            releaseInclusion = ReleaseInclusion.fromYaml("included") ?: error("bad release inclusion"),
            rationale = "Policy fixture.",
            description = "Policy fixture.",
        )

    private val catalog =
        mapOf(
            ":tramai-core" to entry(":tramai-core", "stable"),
            ":tramai-engine" to entry(":tramai-engine", "preview"),
        )

    private fun verifier(projectVersion: String) =
        ApiCompatibilityVerifier(
            catalogModules = catalog,
            projectVersion = projectVersion,
        )

    private fun dump(
        module: String,
        vararg body: String,
    ): String =
        (
            listOf(
                "public final class dev/tramai/${module.removePrefix(":tramai-").replace('-', '/')}/Main {",
            ) +
                body.map { "\t$it" } +
                listOf("}")
        ).joinToString("\n") + "\n"

    private fun sha256(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun migration(
        module: String,
        base: String,
        current: String,
        targetVersion: String,
    ) = ApiMigrationEntry(
        module = module,
        fromSha256 = sha256(base),
        toSha256 = sha256(current),
        targetVersion = targetVersion,
        rationale = "Fixture transition.",
        migration = "No consumer action.",
    )

    private fun failures(
        base: Map<String, String>,
        current: Map<String, String>,
        migrations: List<ApiMigrationEntry> = emptyList(),
        projectVersion: String = "0.6.0",
    ): List<VerificationDiagnostic> =
        verifier(projectVersion)
            .verify(
                ApiDumpEvidence(generated = current, committed = current, base = base),
                migrations,
            ).filter { it.code == DiagnosticCode.API_COMPATIBILITY_FAILED && it.severity == DiagnosticSeverity.FAILURE }

    private fun assertCompatible(
        base: String,
        current: String,
    ) {
        val breakages = ApiDumpCompatibility.incompatibleDeclarations(base, current)
        assertTrue(breakages.isEmpty(), "expected backward compatible, got: $breakages")
    }

    private fun assertIncompatible(
        base: String,
        current: String,
    ) {
        val breakages = ApiDumpCompatibility.incompatibleDeclarations(base, current)
        assertFalse(breakages.isEmpty(), "expected an incompatibility, got none")
    }

    // ── stable: compatibility promise ─────────────────────────────────────

    @Test
    fun `stable unchanged API passes`() {
        val a = dump(":tramai-core", "public fun a ()V")
        assertCompatible(a, a)
    }

    @Test
    fun `stable additive class and member pass`() {
        val base = dump(":tramai-core", "public fun a ()V")
        val current =
            dump(":tramai-core", "public fun a ()V", "public fun b ()V") +
                "public final class dev/tramai/core/Added {" +
                "\n\tpublic fun x ()V\n}\n"
        assertCompatible(base, current)
    }

    @Test
    fun `stable additive change passes through the verifier without any migration entry`() {
        val base = mapOf(":tramai-core" to dump(":tramai-core", "public fun a ()V"))
        val current = mapOf(":tramai-core" to dump(":tramai-core", "public fun a ()V", "public fun b ()V"))
        assertTrue(
            failures(base, current).isEmpty(),
            "additive stable change must not need a migration entry",
        )
    }

    @Test
    fun `stable member removal fails`() {
        val base = dump(":tramai-core", "public fun a ()V", "public fun b ()V")
        val current = dump(":tramai-core", "public fun a ()V")
        assertIncompatible(base, current)
        assertTrue(
            failures(mapOf(":tramai-core" to base), mapOf(":tramai-core" to current)).isNotEmpty(),
            "removal must fail the gate",
        )
    }

    @Test
    fun `stable method signature change fails`() {
        val base = dump(":tramai-core", "public fun a ()V")
        val current = dump(":tramai-core", "public fun a (Ljava/lang/String;)V")
        assertIncompatible(base, current)
    }

    @Test
    fun `stable class removal fails`() {
        val base = dump(":tramai-core", "public fun a ()V")
        val current = "public final class dev/tramai/core/Other {\n}\n"
        assertIncompatible(base, current)
    }

    @Test
    fun `stable supertype removal fails`() {
        val base = "public final class dev/tramai/core/Main : dev/tramai/core/Base, dev/tramai/core/Marker {\n}\n"
        val current = "public final class dev/tramai/core/Main : dev/tramai/core/Base {\n}\n"
        assertIncompatible(base, current)
    }

    @Test
    fun `stable supertype addition passes`() {
        val base = "public final class dev/tramai/core/Main : dev/tramai/core/Base {\n}\n"
        val current = "public final class dev/tramai/core/Main : dev/tramai/core/Base, dev/tramai/core/Marker {\n}\n"
        assertCompatible(base, current)
    }

    @Test
    fun `stable visibility reduction fails`() {
        val base = "public final class dev/tramai/core/Main {\n}\n"
        val current = "protected final class dev/tramai/core/Main {\n}\n"
        assertIncompatible(base, current)
    }

    @Test
    fun `stable breaking change fails even with an exact migration entry`() {
        val base = mapOf(":tramai-core" to dump(":tramai-core", "public fun a ()V", "public fun b ()V"))
        val current = mapOf(":tramai-core" to dump(":tramai-core", "public fun a ()V"))
        val entry = migration(":tramai-core", base.getValue(":tramai-core"), current.getValue(":tramai-core"), "0.6.0")
        assertTrue(
            failures(base, current, listOf(entry)).isNotEmpty(),
            "migration entries must never authorize stable breakage",
        )
    }

    // ── preview/experimental: exact, release-bound transitions ────────────

    @Test
    fun `preview change without an ACTIVE entry fails`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V", "public fun b ()V"))
        assertTrue(failures(base, current).isNotEmpty(), "preview drift needs an exact entry")
    }

    @Test
    fun `preview change passes with an ACTIVE entry targeting the release of a snapshot project version`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V", "public fun b ()V"))
        val baseDump = base.getValue(":tramai-engine")
        val currentDump = current.getValue(":tramai-engine")
        val entry = migration(":tramai-engine", baseDump, currentDump, "0.7.0")
        assertTrue(
            failures(base, current, listOf(entry), projectVersion = "0.7.0-SNAPSHOT").isEmpty(),
            "a 0.7.0 entry must authorize a transition on the 0.7.0-SNAPSHOT development line",
        )
    }

    @Test
    fun `preview change fails when the entry targets the wrong release`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V", "public fun b ()V"))
        val baseDump = base.getValue(":tramai-engine")
        val currentDump = current.getValue(":tramai-engine")
        val entry = migration(":tramai-engine", baseDump, currentDump, "0.6.0")
        val diagnostics = failures(base, current, listOf(entry), projectVersion = "0.7.0-SNAPSHOT")
        assertTrue(diagnostics.isNotEmpty(), "an entry for another release must not authorize the transition")
        assertTrue(
            diagnostics.first().message.contains("targetVersion=0.7.0"),
            "the diagnostic must name the release the project version belongs to, got: ${diagnostics.first().message}",
        )
    }

    @Test
    fun `preview change fails when the entry hash-bound to another transition`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V", "public fun b ()V"))
        val stale =
            ApiMigrationEntry(
                module = ":tramai-engine",
                fromSha256 = sha256("some other base"),
                toSha256 = sha256("some other current"),
                targetVersion = "0.7.0",
                rationale = "Fixture transition.",
                migration = "No consumer action.",
            )
        assertTrue(
            failures(base, current, listOf(stale), projectVersion = "0.7.0-SNAPSHOT").isNotEmpty(),
            "a hash-mismatched entry must not authorize the transition",
        )
    }

    @Test
    fun `landed historical entry under a later development line is retained history and authorizes nothing`() {
        val base = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V"))
        val current = mapOf(":tramai-engine" to dump(":tramai-engine", "public fun a ()V", "public fun b ()V"))
        // LANDED: the entry's target hash has landed, i.e. it is now the base of a further
        // in-flight change — retained history that authorizes nothing.
        val historical =
            ApiMigrationEntry(
                module = ":tramai-engine",
                fromSha256 = sha256("0.5.0 dump"),
                toSha256 = sha256(base.getValue(":tramai-engine")),
                targetVersion = "0.5.0",
                rationale = "Landed history.",
                migration = "No consumer action.",
            )
        val diagnostics = failures(base, current, listOf(historical), projectVersion = "0.7.0-SNAPSHOT")
        assertTrue(
            diagnostics.any { it.message.contains("changed without an exact hash-bound migration entry") },
            "a landed 0.5.0 entry must not authorize a live 0.7.0 transition, got: $diagnostics",
        )
        assertTrue(
            diagnostics.none { it.message.contains("is stale, orphaned") },
            "a landed entry must not itself be reported as stale, got: $diagnostics",
        )
    }

    @Test
    fun `landed entry for an unchanged module is accepted as history`() {
        val dump = dump(":tramai-engine", "public fun a ()V")
        val evidence = mapOf(":tramai-engine" to dump)
        val historical =
            ApiMigrationEntry(
                module = ":tramai-engine",
                fromSha256 = sha256(dump),
                toSha256 = sha256(dump),
                targetVersion = "0.5.0",
                rationale = "Landed history.",
                migration = "No consumer action.",
            )
        assertTrue(
            failures(evidence, evidence, listOf(historical), projectVersion = "0.7.0-SNAPSHOT").isEmpty(),
            "a landed entry whose hash is the committed dump is retained history, not a failure",
        )
    }

    // ── version vocabulary ────────────────────────────────────────────────

    @Test
    fun `release version strips only the exact snapshot suffix`() {
        assertTrue(TramaiVersions.releaseVersionOf("0.7.0-SNAPSHOT") == "0.7.0")
        assertTrue(TramaiVersions.releaseVersionOf("0.7.0") == "0.7.0")
        assertTrue(TramaiVersions.releaseVersionOf("0.7.0-RC1") == "0.7.0-RC1")
        assertTrue(TramaiVersions.isSnapshot("0.7.0-SNAPSHOT"))
        assertFalse(TramaiVersions.isSnapshot("0.7.0"))
    }
}
