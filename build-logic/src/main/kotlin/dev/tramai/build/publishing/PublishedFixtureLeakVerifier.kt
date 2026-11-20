package dev.tramai.build.publishing

import groovy.json.JsonSlurper
import java.io.File

/**
 * A single unexpected fixture publication, or an unauditable publication artifact.
 *
 * [module] and [artifact] are what the diagnostic names: a leak is actionable only if the report says
 * which module published it and which variant or file carried it. [variant] is null when the finding
 * is that a variant could not be named at all, so a missing or malformed artifact never renders as a
 * variant name that does not exist.
 */
data class PublishedFixtureLeak(
    val module: String,
    val variant: String?,
    val artifact: String?,
    val detail: String,
) {
    fun describe(): String {
        val artifactPart = artifact?.let { " (artifact '$it')" } ?: ""
        val variantPart = variant?.let { " in variant '$it'" } ?: ""
        return "module '$module' publishes $detail$artifactPart$variantPart"
    }
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
 * Fail-closed applies to structure as well as absence: metadata that cannot be parsed, carries no
 * `variants` array, carries an empty one, or carries a variant with no name is a finding. Reading any of
 * those as "zero variants, therefore no leaks" is exactly the pass this gate exists to refuse — a file
 * containing only `{"formatVersion": "1.1"}` would otherwise satisfy the boundary.
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
                        variant = null,
                        artifact = null,
                        detail =
                            "no generated publication metadata, so none of its published variants " +
                                "could be audited",
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
        val variants =
            runCatching { variantMaps(readJson(metadata)) }.getOrElse { cause ->
                return listOf(unauditable(module, metadata, cause.message ?: "that could not be read"))
            }
        return variants.flatMap { variantLeaks(module, it) }
    }

    private fun readJson(metadata: File): Any? {
        val text =
            runCatching { metadata.readText() }.getOrElse { cause ->
                throw IllegalStateException("that is not readable (${cause.message})", cause)
            }
        return runCatching { JsonSlurper().parseText(text) }.getOrElse { cause ->
            throw IllegalStateException("that is not valid JSON (${cause.message})", cause)
        }
    }

    /**
     * The variant structures, or a thrown reason why the file cannot be audited at all. An absent or
     * empty `variants` array and a non-object variant are refusals here, not empty results: reading them
     * as "nothing to inspect" is the pass this gate exists to deny.
     */
    private fun variantMaps(parsed: Any?): List<Map<*, *>> {
        val variants = (parsed as? Map<*, *>)?.get("variants") as? List<*>
        check(!variants.isNullOrEmpty()) {
            "with no variants array, so its published variants could not be audited"
        }
        val malformed = variants.count { it !is Map<*, *> }
        check(malformed == 0) {
            "whose variants include $malformed non-object entries, so they could not be audited"
        }
        return variants.filterIsInstance<Map<*, *>>()
    }

    private fun variantLeaks(
        module: String,
        variant: Map<*, *>,
    ): List<PublishedFixtureLeak> {
        val name = (variant["name"] as? String)?.takeIf { it.isNotBlank() }
        if (name == null) {
            return listOf(
                PublishedFixtureLeak(
                    module = module,
                    variant = null,
                    artifact = null,
                    detail =
                        "a variant without a name in its publication metadata, so what that " +
                            "variant carries could not be audited",
                ),
            )
        }
        val files =
            (variant["files"] as? List<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
                ?: emptyList()
        val variantIsFixture = name.lowercase().contains(FIXTURE_VARIANT_MARKER)
        val fixtureFile = files.firstOrNull { it.lowercase().contains(FIXTURE_ARTIFACT_MARKER) }
        return when {
            variantIsFixture -> {
                listOf(
                    PublishedFixtureLeak(
                        module = module,
                        variant = name,
                        artifact = fixtureFile ?: files.firstOrNull(),
                        detail = "a test-fixtures variant",
                    ),
                )
            }

            // A non-fixture variant that still serves the fixture jar is the harder leak: the variant
            // name looks legitimate, so only the artifact identifies it.
            fixtureFile != null -> {
                listOf(
                    PublishedFixtureLeak(
                        module = module,
                        variant = name,
                        artifact = fixtureFile,
                        detail = "a test-fixtures artifact under a non-fixture variant",
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun unauditable(
        module: String,
        metadata: File,
        reason: String,
    ): PublishedFixtureLeak =
        PublishedFixtureLeak(
            module = module,
            variant = null,
            artifact = metadata.name,
            detail = "publication metadata $reason",
        )

    /**
     * The POM half of the same boundary: a fixture dependency exported through published metadata reaches
     * consumers as a normal dependency whether or not the fixture jar is published.
     *
     * @param pomFor resolves a module path to its generated `pom-default.xml`, or null when none exists.
     */
    fun verifyPoms(
        modules: List<String>,
        pomFor: (String) -> File?,
    ): List<PublishedFixtureLeak> {
        val leaks = mutableListOf<PublishedFixtureLeak>()
        for (module in modules.distinct().sorted()) {
            val pom = pomFor(module)
            if (pom == null || !pom.isFile) {
                leaks +=
                    PublishedFixtureLeak(
                        module = module,
                        variant = null,
                        artifact = null,
                        detail = "no generated POM, so its published dependencies could not be audited",
                    )
                continue
            }
            val text =
                runCatching { pom.readText() }.getOrElse { cause ->
                    return leaks + unauditable(module, pom, "that is not readable (${cause.message})")
                }
            if (text.contains(FIXTURE_ARTIFACT_MARKER, ignoreCase = true) ||
                text.contains(FIXTURE_VARIANT_MARKER, ignoreCase = true)
            ) {
                leaks +=
                    PublishedFixtureLeak(
                        module = module,
                        variant = null,
                        artifact = pom.name,
                        detail = "a test-fixtures dependency in its published POM",
                    )
            }
        }
        return leaks
    }
}
