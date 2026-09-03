package dev.tramai.build.quality

import java.io.File

/**
 * Single authority for canonical PIT init-script rendering.
 *
 * 10.3c1 (#362) established the production semantics. 10.3c2 extracts those
 * semantics without changing them so the root baseline generator and the
 * isolated canonical probe cannot drift independently.
 */
object MutationProbeInitScript {
    const val PIT_PLUGIN_VERSION = "1.19.0"
    const val JUNIT5_PLUGIN_VERSION = "1.2.1"
    const val PIT_ENGINE_VERSION = "1.22.1"

    val PIT_MUTATORS: List<String> =
        listOf(
            "CONDITIONALS_BOUNDARY",
            "INCREMENTS",
            "INVERT_NEGS",
            "MATH",
            "NEGATE_CONDITIONALS",
            "TRUE_RETURNS",
            "FALSE_RETURNS",
            "PRIMITIVE_RETURNS",
            "EMPTY_RETURNS",
            "NULL_RETURNS",
            "VOID_METHOD_CALLS",
        )

    const val TIMEOUT_CONST_MILLIS: Long = 4_000L
    const val TIMEOUT_FACTOR: Double = 1.25

    fun render(
        configuration: TestQualityConfiguration,
        reportRoot: File,
    ): String =
        listOf(
            renderSelection(configuration, reportRoot),
            renderPluginApplication(),
            renderPitConfiguration(),
        ).joinToString("\n\n", postfix = "\n")

    private fun renderSelection(
        configuration: TestQualityConfiguration,
        reportRoot: File,
    ): String {
        val familyTable = renderFamilyTable(configuration)
        return """
            initscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
                dependencies {
                    classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:$PIT_PLUGIN_VERSION'
                }
            }

            def targetFamilies = [
            $familyTable
            ]
            // Included builds do not inherit the outer -P family property.
            // Only the root build owns the canonical mutation probe.
            if (gradle.parent != null) return
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
            """.trimIndent()
    }

    private fun renderPluginApplication(): String =
        """
        gradle.beforeProject { measuredProject ->
            if (!(measuredProject.path in selectedModules)) return
            def pluginClass = initscript.classLoader.loadClass(
                'info.solidsoft.gradle.pitest.PitestPlugin'
            )
            measuredProject.pluginManager.apply(pluginClass)
            // JUnit 5 projects need the companion on PIT's runtime classpath.
            measuredProject.dependencies.add(
                'pitest',
                'org.pitest:pitest-junit5-plugin:$JUNIT5_PLUGIN_VERSION'
            )
        }
        """.trimIndent()

    private fun renderPitConfiguration(): String {
        val mutators = PIT_MUTATORS.joinToString(",\n                        ") { "'$it'" }
        return """
            gradle.projectsEvaluated {
                selectedModules.each { modulePath ->
                    def measuredProject = gradle.rootProject.findProject(modulePath)
                    if (measuredProject == null) return
                    def ext = measuredProject.extensions.findByName('pitest')
                    // #362 C1: target selection and analyzer semantics are authority.
                    ext.targetClasses.set(familyTargetClasses)
                    ext.targetTests.set(familyTargetTests)
                    ext.outputFormats.set(['XML', 'HTML'] as Set)
                    ext.timestampedReports.set(false)
                    ext.failWhenNoMutations.set(true)
                    ext.threads.set(2)
                    ext.pitestVersion.set('$PIT_ENGINE_VERSION')
                    ext.timeoutConstInMillis.set($TIMEOUT_CONST_MILLIS)
                    ext.timeoutFactor.set($TIMEOUT_FACTOR)
                    ext.mutators.set([
                        $mutators
                    ] as Set)
                    def moduleSlug = measuredProject.path.substring(1).replace(':', '_')
                    ext.reportDir.set(new File(outputRoot, selectedFamily + '/' + moduleSlug))
                    mutationTasks << measuredProject.tasks.named('pitest')
                }
                rootProject.tasks.register('canonicalMutationProbe') {
                    dependsOn mutationTasks.collect { it.get() }
                }
            }
            """.trimIndent()
    }

    private fun renderFamilyTable(configuration: TestQualityConfiguration): String =
        configuration.mutation.targetFamilies.entries
            .sortedBy { it.key }
            .joinToString(",\n") { (family, target) ->
                val modules = target.modules.sorted().joinToString(", ") { "'${groovyString(it)}'" }
                val classes = target.targetClasses.sorted().joinToString(", ") { "'${groovyString(it)}'" }
                val tests = target.targetTests.sorted().joinToString(", ") { "'${groovyString(it)}'" }
                "    '${groovyString(family)}': [modules: [$modules], " +
                    "targetClasses: [$classes], targetTests: [$tests]]"
            }

    private fun groovyString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
