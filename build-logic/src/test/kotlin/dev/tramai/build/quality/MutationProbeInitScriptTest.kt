package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 10.3c2 contract for the shared canonical PIT renderer reconstructed on top
 * of the 10.3c1 (#362) semantics.
 *
 * Kept as one contract test intentionally: the maintainability workflow has a
 * fail-closed exact population for Mutation*Test. These assertions belong to
 * one authority boundary and must not change that lane's cardinality merely
 * because the inline renderer was extracted.
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

    @Test
    fun `shared renderer preserves lifecycle targeting and C1 mutation semantics`() {
        val script = MutationProbeInitScript.render(configuration, File(tempDir, "mutation-root"))

        val guard = script.indexOf("if (gradle.parent != null) return")
        val familyValidation = script.indexOf("Unknown or missing tramaiMutationFamily")
        assertTrue(guard >= 0)
        assertTrue(guard < familyValidation, "root-build guard must precede family validation")

        assertContains(script, "targetTests: ['dev.example.contract.*']")
        assertContains(script, "ext.targetTests.set(familyTargetTests)")
        assertContains(
            script,
            "org.pitest:pitest-junit5-plugin:${MutationProbeInitScript.JUNIT5_PLUGIN_VERSION}",
        )
        assertContains(
            script,
            """throw new GradleException("Unknown or missing tramaiMutationFamily: " + selectedFamily)""",
        )
        assertContains(script, "ext.failWhenNoMutations.set(true)")

        assertContains(script, "ext.pitestVersion.set('${MutationProbeInitScript.PIT_ENGINE_VERSION}')")
        MutationProbeInitScript.PIT_MUTATORS.forEach { mutator -> assertContains(script, "'$mutator'") }
        assertContains(script, "ext.timeoutConstInMillis.set(${MutationProbeInitScript.TIMEOUT_CONST_MILLIS})")
        assertContains(script, "ext.timeoutFactor.set(${MutationProbeInitScript.TIMEOUT_FACTOR})")

        val mutatorsBlock = script.substringAfter("ext.mutators.set([").substringBefore("] as Set)")
        assertContains(mutatorsBlock, "'CONDITIONALS_BOUNDARY'")
        assertContains(mutatorsBlock, "'VOID_METHOD_CALLS'")
        assertFalse(mutatorsBlock.contains("DEFAULTS"), "mutator set must stay expanded and pinned")

        val pluginVersion = MutationProbeInitScript.PIT_PLUGIN_VERSION
        assertContains(script, "classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:$pluginVersion'")
        assertContains(script, "rootProject.tasks.register('canonicalMutationProbe')")
    }
}
