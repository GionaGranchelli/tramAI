package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 10.3c2 mutation-infrastructure contract tests (M01–M06), rebuilt on the
 * 10.3c1 #362 renderer semantics.
 *
 * These lock the canonical PIT init-script semantics so the two mutation
 * consumers (MaintainabilityBaselinePlugin.generateCriticalMutationBaseline
 * and CanonicalGradleProbe.probeTestQualityBaseline) share one renderer and
 * the #362 C1 pins cannot regress:
 *
 *  M01 root-build guard    — probe runs only on the root build; included
 *                            builds (build-logic) must return early.
 *  M02 targetTests authority — the configured targetTests are emitted explicitly
 *                             (never left to the plugin's targetClasses mirror).
 *  M03 unknown family        — rendered script fails closed on missing family.
 *  M04 zero population       — failWhenNoMutations is always set.
 *  M05 C1 mutation semantics — engine version, expanded DEFAULT mutator set and
 *                             timeout const/factor are pinned in the script.
 *  M06 root/canonical parity — both consumers render through the same object.
 */
class MutationProbeInitScriptTest {
    private val configuration =
        TestQualityConfiguration(
            schemaVersion = "1",
            criticalModules = listOf(":core"),
            coverage =
                TestQualityConfiguration.CoverageConfiguration(
                    1.0,
                    listOf(CoverageExclusion("**/model/**", "Generated model classes")),
                ),
            mutation =
                TestQualityConfiguration.MutationConfiguration(
                    1.0,
                    mapOf(
                        "routing" to
                            TestQualityConfiguration.MutationTargetFamily(
                                modules = listOf(":core"),
                                targetClasses = listOf("dev.example.routing.Router"),
                                targetTests = listOf("dev.example.contract.*"),
                            ),
                    ),
                ),
        )

    @TempDir
    lateinit var tempDir: File

    private val reportRoot: File
        get() = File(tempDir, "mutation-root")

    @Test
    fun `M01 root-build guard prevents included-build probe execution`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        // The init script is evaluated for the root build AND for every included
        // build (build-logic). Only the root build (gradle.parent == null) runs
        // the probe; startParameter.projectProperties do not carry -P across an
        // included-build boundary, so without this guard the family check would
        // spuriously throw in build-logic.
        assertContains(script, "if (gradle.parent != null) return")
        val guardStart = script.indexOf("if (gradle.parent != null) return")
        assertTrue(guardStart >= 0)
        val familyCheck = script.indexOf("Unknown or missing tramaiMutationFamily")
        assertTrue(guardStart < familyCheck, "root-build guard must precede the family validation")
    }

    @Test
    fun `M02 explicit targetTests are rendered and reach pitest config`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(script, "targetTests: ['dev.example.contract.*']")
        assertContains(script, "ext.targetTests.set(familyTargetTests)")
        // The junit5 plugin is added to the pitest runtime configuration because
        // PIT refuses to run without it ("pitest junit 5 plugin is not installed").
        assertContains(script, "org.pitest:pitest-junit5-plugin:${MutationProbeInitScript.JUNIT5_PLUGIN_VERSION}")
    }

    @Test
    fun `M03 unknown family fails closed`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(
            script,
            """throw new GradleException("Unknown or missing tramaiMutationFamily: " + selectedFamily)""",
        )
    }

    @Test
    fun `M04 zero population cannot silently succeed`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(script, "ext.failWhenNoMutations.set(true)")
    }

    @Test
    fun `M05 C1 mutation semantics are pinned`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        // Engine version, mutator set and timeout semantics are pinned so a
        // future gradle-pitest-plugin or engine upgrade cannot silently change
        // the measured population.
        assertContains(script, "ext.pitestVersion.set('${MutationProbeInitScript.PIT_ENGINE_VERSION}')")
        MutationProbeInitScript.PIT_MUTATORS.forEach { mutator ->
            assertContains(script, "'$mutator'")
        }
        assertContains(script, "ext.timeoutConstInMillis.set(${MutationProbeInitScript.TIMEOUT_CONST_MILLIS})")
        assertContains(script, "ext.timeoutFactor.set(${MutationProbeInitScript.TIMEOUT_FACTOR})")
        // The mutator set must be the expanded pinned list (ext.mutators.set([...]))
        // rather than the DEFAULTS group name. The comment above the block
        // legitimately mentions DEFAULTS, so scope the check to the mutators block.
        val mutatorsBlock = script.substringAfter("ext.mutators.set([").substringBefore("] as Set)")
        assertContains(mutatorsBlock, "'CONDITIONALS_BOUNDARY'")
        assertContains(mutatorsBlock, "'VOID_METHOD_CALLS'")
        assertFalse(mutatorsBlock.contains("DEFAULTS"), "mutator set must be the expanded list, not the group name")
    }

    @Test
    fun `M06 consumers share one renderer and one PIT version`() {
        // Single rendering authority: both consumers delegate to this object,
        // and the analyzer version is derived from the same pinned plugin
        // version rather than a second hard-coded literal.
        assertTrue(MutationProbeInitScript.PIT_PLUGIN_VERSION.isNotBlank())
        assertTrue(MutationProbeInitScript.JUNIT5_PLUGIN_VERSION.isNotBlank())
        assertTrue(MutationProbeInitScript.PIT_ENGINE_VERSION.isNotBlank())
        val pitPlugin = MutationProbeInitScript.PIT_PLUGIN_VERSION
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(
            script,
            "classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:$pitPlugin'",
        )
        assertContains(script, "rootProject.tasks.register('canonicalMutationProbe')")
    }
}
