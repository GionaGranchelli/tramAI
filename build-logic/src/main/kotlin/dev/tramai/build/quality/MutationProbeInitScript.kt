package dev.tramai.build.quality

import java.io.File

/**
 * Single authority for the canonical PIT init-script rendering (10.3c2 Stage-A
 * reconstruction on top of 10.3c1 #362).
 *
 * 10.3c1 (#362) owns the production renderer inside MaintainabilityBaselinePlugin
 * with pinned mutation semantics; CanonicalGradleProbe carried a second,
 * older renderer that had drifted (no engine/mutator/timeout pins, different
 * extension lifecycle). Both consumers now delegate here so the semantics
 * cannot diverge again.
 *
 * The rendered script body is #362's renderer, extracted verbatim — NOT the
 * pre-#362 renderer this branch originally shipped.
 */
object MutationProbeInitScript {
    /** gradle-pitest-plugin version (matches #362's initscript classpath). */
    const val PIT_PLUGIN_VERSION = "1.19.0"

    /** pitest-junit5-plugin required for JUnit 5 projects (PIT refuses without it). */
    const val JUNIT5_PLUGIN_VERSION = "1.2.1"

    /** Pinned PIT engine version (gradle-pitest-plugin 1.19.0's DEFAULT_PITEST_VERSION). */
    const val PIT_ENGINE_VERSION = "1.22.1"

    /** 10.3c1-C1: pinned PIT DEFAULTS mutator set (engine 1.22.1), expanded. */
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

    /** 10.3c1-C1: timeout pinned as mutation semantics (PIT documented defaults). */
    const val TIMEOUT_CONST_MILLIS: Long = 4_000L

    const val TIMEOUT_FACTOR: Double = 1.25

    /**
     * Renders the family-driven canonical PIT probe init script for the given
     * test-quality mutation configuration. The script validates the selected
     * `tramaiMutationFamily`, applies PIT to the family's modules with its
     * targetClasses/targetTests, pins engine/mutator/timeout semantics and
     * registers `canonicalMutationProbe` aggregating the per-module pitest
     * tasks. XML reports are written to `<reportRoot>/<family>/<moduleSlug>/`.
     */
    fun render(
        configuration: TestQualityConfiguration,
        reportRoot: File,
    ): String {
        val familyModules =
            configuration.mutation.targetFamilies.entries
                .sortedBy { it.key }
                .joinToString(",\n") { (family, target) ->
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
                    "    '${groovyString(family)}': [modules: [$modules], targetClasses: [$classes], targetTests: [$tests]]"
                }
        return """
            initscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
                dependencies {
                    classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:${PIT_PLUGIN_VERSION}'
                }
            }

            def targetFamilities = [
            $familyModules
            ]
            // The init script is evaluated for the root build AND for every
            // included build (build-logic). startParameter.projectProperties on an
            // included build's Gradle instance does not carry the outer -P
            // properties, so the family check would spuriously throw there.
            // Only the root build (gradle.parent == null) runs the probe.
            if (gradle.parent != null) return
            def selectedFamily = gradle.startParameter.projectProperties['tramaiMutationFamily']
            if (selectedFamily == null || !targetFamilities.containsKey(selectedFamily)) {
                throw new GradleException("Unknown or missing tramaiMutationFamily: " + selectedFamily)
            }
            def familyConfig = targetFamilities[selectedFamily]
            def selectedModules = familyConfig.modules as Set
            def familyTargetClasses = familyConfig.targetClasses as Set
            def familyTargetTests = familyConfig.targetTests as Set
            def mutationTasks = []
            def outputRoot = new File('${groovyString(reportRoot.absolutePath)}')

            gradle.beforeProject { measuredProject ->
                if (!(measuredProject.path in selectedModules)) return
                def pluginClass = initscript.classLoader.loadClass(
                    'info.solidsoft.gradle.pitest.PitestPlugin'
                )
                measuredProject.pluginManager.apply(pluginClass)
                // JUnit 5 projects need the pitest-junit5-plugin on the pitest
                // runtime classpath or PIT fails with "pitest junit 5 plugin is
                // not installed". The plugin itself (gradle-pitest-plugin) does
                // not add it automatically.
                measuredProject.dependencies.add('pitest', 'org.pitest:pitest-junit5-plugin:${JUNIT5_PLUGIN_VERSION}')
            }

            gradle.projectsEvaluated {
                selectedModules.each { modulePath ->
                    def measuredProject = gradle.rootProject.findProject(modulePath)
                    if (measuredProject == null) return
                    def ext = measuredProject.extensions.findByName('pitest')
                    // PitestPlugin defers extension creation until the Java
                    // plugin is present (plugins.withType(JavaPlugin)), so by
                    // projectsEvaluated the extension exists. Use direct
                    // property access: the closure form (extensions.configure)
                    // resolves its delegate to the build, not the extension.
                    ext.targetClasses.set(familyTargetClasses)
                    ext.targetTests.set(familyTargetTests)
                    ext.outputFormats.set(['XML', 'HTML'] as Set)
                    ext.timestampedReports.set(false)
                    ext.failWhenNoMutations.set(true)
                    ext.threads.set(2)
                    // 10.3c1-C1: pin the engine version and mutator set. The
                    // DEFAULTS group expands to the 11 individual mutators
                    // (verified against org.pitest.pitest ${PIT_ENGINE_VERSION}'s Mutator
                    // class) — pinning the expanded list, not the group name,
                    // so a future engine's group redefinition cannot silently
                    // change the population.
                    ext.pitestVersion.set('${PIT_ENGINE_VERSION}')
                    // 10.3c1-C1: pin timeout as mutation semantics. PIT's
                    // documented defaults (timeoutConstInMillis=${TIMEOUT_CONST_MILLIS},
                    // timeoutFactor=${TIMEOUT_FACTOR}) are pinned explicitly so a future
                    // plugin upgrade cannot silently change timeout semantics.
                    // The 20s experiment cost 23m48s on approval alone for no
                    // authority gain: SURVIVED<->TIMED_OUT is the same
                    // NON_KILLED state, so raw status races don't matter.
                    ext.timeoutConstInMillis.set(${TIMEOUT_CONST_MILLIS})
                    ext.timeoutFactor.set(${TIMEOUT_FACTOR})
                    ext.mutators.set([
                        ${PIT_MUTATORS.joinToString(",\n                        ") { "'$it'" }}
                    ] as Set)
                    def moduleSlug = measuredProject.path.substring(1).replace(':', '_')
                    ext.reportDir.set(new File(outputRoot, selectedFamily + '/' + moduleSlug))
                    mutationTasks << measuredProject.tasks.named('pitest')
                }
                rootProject.tasks.register('canonicalMutationProbe') {
                    dependsOn mutationTasks.collect { it.get() }
                }
            }
            """.trimIndent() + "\n"
    }

    private fun groovyString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
