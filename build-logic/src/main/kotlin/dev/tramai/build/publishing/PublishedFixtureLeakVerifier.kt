package dev.tramai.build.publishing

import groovy.json.JsonSlurper
import java.io.File

/**
 * A single unexpected fixture publication found in a generated publication artifact.
 *
 * [module] and [artifact] are what the diagnostic names: a leak is actionable only if the report says
 * which module published it and which variant or file carried it.
 */
data class PublishedFixtureLeak(
    val module: String,
    val variant: String,
    val artifact: String?,
    val detail: String,
) {
    fun describe(): String =
        "module '$module' publishes $detail" +
            (artifact?.let { " (artifact '$it')" } ?: "") +
            " in variant '$variant'"
}

/**
 * Verifies that a publishable module's **generated** publication metadata carries no test-fixtures
 * variant or artifact.
 *
 * The authority is the artifact Gradle writes, not the build-script configuration: `module.json` is
 * what a consumer's dependency resolution actually reads, and it lists every published variant with the
 * file names it serves. A module can therefore configure an exclusion and still leak (a different
 * variant re-attached, a stale artifact, a new fixtures configuration), and only the generated metadata
 * shows it. That is why this verifier is given files, never a flag.
 *
 * Fixture *usage* is unaffected: internal consumers depend on the project's `testFixtures*Elements`
 * **configuration**, which exists whether or not the variant is published.
 */
object PublishedFixtureLeakVerifier {
    private const val FIXTURE_VARIANT_MARKER = "testfixtures"
    private const val FIXTURE_ARTIFACT_MARKER = "test-fixtures"

    /**
     * @param modules publishable module paths, with or without a leading `:`
     * @param moduleJsonFor resolves a module path to its generated `module.json`, or null when the
     *   module generated none — a missing metadata file is itself reported, never treated as clean.
     */
    fun verify(
        modules: List<String>,
        moduleJsonFor: (String) -> File?,
    ): List<PublishedFixtureLeak> {
        val leaks = mutableListOf<PublishedFixtureLeak>()
        for (module in modules.distinct().sorted()) {
            val metadata = moduleJsonFor(module)
            if (metadata == null || !metadata.isFile) {
                leaks +=
                    PublishedFixtureLeak(
                        module = module,
                        variant = "<none>",
                        artifact = null,
                        detail =
                            "no generated publication metadata was found, so its published variants " +
                                "cannot be audited",
                    )
                continue
            }
            leaks += leaksIn(module, metadata)
        }
        return leaks
    }

    private fun leaksIn(
        module: String,
        metadata: File,
    ): List<PublishedFixtureLeak> {
        val variants = readVariants(metadata)
        val leaks = mutableListOf<PublishedFixtureLeak>()
        for (variant in variants) {
            val name = variant["name"] as? String ?: continue
            val files =
                (variant["files"] as? List<*>)
                    ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
                    ?: emptyList()
            val variantIsFixture = name.lowercase().contains(FIXTURE_VARIANT_MARKER)
            val fixtureFile = files.firstOrNull { it.lowercase().contains(FIXTURE_ARTIFACT_MARKER) }
            when {
                variantIsFixture -> {
                    leaks +=
                        PublishedFixtureLeak(
                            module = module,
                            variant = name,
                            artifact = fixtureFile ?: files.firstOrNull(),
                            detail = "a test-fixtures variant",
                        )
                }

                // A non-fixture variant that still serves the fixture jar is the harder leak: the
                // variant name looks legitimate, so only the artifact identifies it.
                fixtureFile != null -> {
                    leaks +=
                        PublishedFixtureLeak(
                            module = module,
                            variant = name,
                            artifact = fixtureFile,
                            detail = "a test-fixtures artifact under a non-fixture variant",
                        )
                }
            }
        }
        return leaks
    }

    private fun readVariants(metadata: File): List<Map<*, *>> {
        val parsed =
            runCatching { JsonSlurper().parseText(metadata.readText()) }.getOrElse { cause ->
                throw IllegalStateException(
                    "publication metadata '${metadata.path}' is not readable as JSON: ${cause.message}",
                    cause,
                )
            }
        val variants = (parsed as? Map<*, *>)?.get("variants") as? List<*>
        return variants?.mapNotNull { it as? Map<*, *> } ?: emptyList()
    }
}
