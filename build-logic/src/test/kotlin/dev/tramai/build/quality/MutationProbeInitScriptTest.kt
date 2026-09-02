package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 10.3c2 mutation-infrastructure contract tests (M01–M06).
 *
 * These lock the repaired canonical PIT init-script semantics so the two
 * mutation consumers (MaintainabilityBaselinePlugin.generateCriticalMutationBaseline
 * and CanonicalGradleProbe.probeTestQualityBaseline) share one renderer and the
 * c1 root causes cannot regress:
 *
 *  M01 Java-plugin timing   — PIT is applied ONLY inside plugins.withId('java');
 *                             a direct beforeProject apply is not rendered.
 *  M02 targetTests authority — the configured targetTests are emitted explicitly
 *                             (never left to the plugin's targetClasses mirror).
 *  M03 unknown family        — rendered script fails closed on missing family.
 *  M04 zero population       — failWhenNoMutations is always set.
 *  M05 output ownership      — deterministic family/module report path.
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
    fun `M01 pitest application is inside java plugin lifecycle`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)

        // The working lifecycle: apply only after the java plugin is present.
        assertContains(script, "measuredProject.plugins.withId('java') {")
        // The PIT plugin class is loaded and applied INSIDE that block.
        val withIdBlock =
            script.substringAfter("withId('java') {").substringBefore("mutationTasks <<")
        assertContains(withIdBlock, "pluginManager.apply(pluginClass)")
        // No direct apply before the withId guard.
        val beforeWithId = script.substringBefore("withId('java') {")
        assertFalse(beforeWithId.contains("pluginManager.apply"), "PIT must not be applied before the java plugin")
    }

    @Test
    fun `M02 explicit targetTests are rendered and reach pitest config`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)

        // The family table carries the configured tests.
        assertContains(script, "targetTests: ['dev.example.contract.*']")
        // The pitest extension receives them explicitly.
        assertContains(script, "pitestExt.targetTests.set(familyTargetTests)")
        // The pitest junit5 companion is wired so tests are actually found.
        assertContains(script, "dependencies.add('pitest', 'org.pitest:pitest-junit5-plugin:1.2.1')")
        assertContains(script, "pitestExt.testPlugin.set('junit5')")
    }

    @Test
    fun `M03 unknown family fails closed`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(
            script,
            "throw new GradleException(\"Unknown or missing tramaiMutationFamily: \" + selectedFamily)",
        )
    }

    @Test
    fun `M04 zero population cannot silently succeed`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(script, "pitestExt.failWhenNoMutations.set(true)")
    }

    @Test
    fun `M05 deterministic family module report path`() {
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        // Non-timestamped XML/HTML under outputRoot/<family>/<moduleSlug>.
        assertContains(script, "pitestExt.timestampedReports.set(false)")
        assertContains(script, "outputFormats.set(['XML', 'HTML'] as Set)")
        assertContains(script, "new File(outputRoot, selectedFamily + '/' + moduleSlug)")
        assertContains(script, reportRoot.absolutePath)
    }

    @Test
    fun `M06 consumers share one renderer and one PIT version`() {
        // Versions are owned in one place.
        assertTrue(MutationProbeInitScript.PIT_PLUGIN_VERSION.isNotBlank())
        assertTrue(MutationProbeInitScript.JUNIT5_PLUGIN_VERSION.isNotBlank())
        val script = MutationProbeInitScript.render(configuration, reportRoot)
        assertContains(
            script,
            "classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:" +
                "${MutationProbeInitScript.PIT_PLUGIN_VERSION}'",
        )
        assertContains(
            script,
            "classpath 'org.pitest:pitest-junit5-plugin:" +
                "${MutationProbeInitScript.JUNIT5_PLUGIN_VERSION}'",
        )
    }
}
