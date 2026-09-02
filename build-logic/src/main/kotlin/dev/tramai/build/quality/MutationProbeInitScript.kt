package dev.tramai.build.quality

import java.io.File

/**
 * Single renderer for the family-driven PIT init script.
 *
 * Both mutation consumers (MaintainabilityBaselinePlugin.generateCriticalMutationBaseline
 * and CanonicalGradleProbe.probeTestQualityBaseline) render their init script
 * from this object so the two paths cannot drift. The rendered script follows
 * the lifecycle proven by the 10.3c1 routing-core pilot and kept working in
 * CanonicalGradleProbe:
 *
 *  1. PIT is applied ONLY inside `plugins.withId('java')` — gradle-pitest-plugin
 *     1.19.0 registers its extension from a JavaPlugin callback, so a bare
 *     `beforeProject` apply fails with "Extension with name 'pitest' does not
 *     exist" (10.3c1 C8.1).
 *  2. `pitest-junit5-plugin` is added to the `pitest` configuration and
 *     `testPlugin` is pinned to `junit5` — without it PIT finds no tests
 *     (10.3c1 C8.3).
 *  3. `targetTests` is pinned explicitly — when unset the solidsoft plugin
 *     mirrors `targetClasses` into the test filter and finds 0 tests (10.3c1
 *     C8.2).
 *
 * All PIT/plugin versions, formats, non-vacuity, thread count, and report
 * paths are owned here.
 */
object MutationProbeInitScript {
    const val PIT_PLUGIN_VERSION = "1.19.0"
    const val JUNIT5_PLUGIN_VERSION = "1.2.1"

    fun render(
        configuration: TestQualityConfiguration,
        reportRoot: File,
    ): String {
        val familyModules =
            configuration.mutation.targetFamilies.entries
                .sortedBy { it.key }
                .joinToString(",\n", transform = ::familyDeclaration)
        val body = probeBody(reportRoot)
        return """
            initscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
                dependencies {
                    classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:${PIT_PLUGIN_VERSION}'
                    classpath 'org.pitest:pitest-junit5-plugin:${JUNIT5_PLUGIN_VERSION}'
                }
            }

            def targetFamilies = [
            $familyModules
            ]
            $body
            """.trimIndent() + "\n"
    }

    private fun familyDeclaration(entry: Map.Entry<String, TestQualityConfiguration.MutationTargetFamily>): String {
        val (family, target) = entry
        val modules =
            target.modules.sorted().joinToString(", ") {
                "'${groovyString(it)}'"
            }
        val classes =
            target.targetClasses.sorted().joinToString(", ") {
                "'${groovyString(it)}'"
            }
        val tests =
            target.targetTests.sorted().joinToString(", ") {
                "'${groovyString(it)}'"
            }
        return "    '${groovyString(family)}': [modules: [$modules], " +
            "targetClasses: [$classes], targetTests: [$tests]]"
    }

    private fun probeBody(reportRoot: File): String =
        """
        // This init script is evaluated once per build in the build tree:
        // the root build carries -PtramaiMutationFamily, while included
        // builds (build-logic) re-evaluate it with no project properties.
        // Skip non-root evaluations so the family lookup below cannot fail
        // on an included build that never receives the property.
        if (gradle.parent != null) { return }
        def selectedFamily = gradle.startParameter.projectProperties['tramaiMutationFamily']
        if (selectedFamily == null || !targetFamilies.containsKey(selectedFamily)) {
            throw new GradleException("Unknown or missing tramaiMutationFamily: " + selectedFamily)
        }
        def familyConfig = targetFamilies[selectedFamily]
        def selectedModules = familyConfig.modules as Set
        def familyTargetClasses = familyConfig.targetClasses as Set
        def familyTargetTests = familyConfig.targetTests as Set
        def mutationTasks = []
        def outputRoot = new File('${groovyString(reportRoot.absolutePath)}')

        gradle.beforeProject { measuredProject ->
            if (!(measuredProject.path in selectedModules)) return
            measuredProject.plugins.withId('java') {
                def pluginClass = initscript.classLoader.loadClass(
                    'info.solidsoft.gradle.pitest.PitestPlugin'
                )
                measuredProject.pluginManager.apply(pluginClass)
                // Add pitest-junit5-plugin to the pitest configuration so PIT can run JUnit 5 tests
                measuredProject.dependencies.add('pitest', 'org.pitest:pitest-junit5-plugin:${JUNIT5_PLUGIN_VERSION}')
                measuredProject.extensions.configure('pitest') { pitestExt ->
                    pitestExt.testPlugin.set('junit5')
                    pitestExt.targetClasses.set(familyTargetClasses)
                    pitestExt.targetTests.set(familyTargetTests)
                    pitestExt.outputFormats.set(['XML', 'HTML'] as Set)
                    pitestExt.timestampedReports.set(false)
                    pitestExt.failWhenNoMutations.set(true)
                    pitestExt.threads.set(2)
                    def moduleSlug = measuredProject.path.substring(1).replace(':', '_')
                    pitestExt.reportDir.set(new File(outputRoot, selectedFamily + '/' + moduleSlug))
                }
                mutationTasks << measuredProject.tasks.named('pitest')
            }
        }

        gradle.projectsEvaluated {
            rootProject.tasks.register('canonicalMutationProbe') {
                dependsOn mutationTasks.collect { it.get() }
            }
        }
        """.trimIndent()

    private fun groovyString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
