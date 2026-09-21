package dev.tramai.build.docs

import dev.tramai.build.quality.TramaiVersions
import java.io.File

/*
 * Version-surface contract (verifyVersionAlignment), extracted from RootDocGuardVerifiers so both
 * files stay inside their function budgets and the release-line scoping stays readable.
 */

/** Active-doc guards for legacy snapshot coordinates. */
private val SNAPSHOT_GRADLE_COORDINATE = Regex("""dev\.tramai:[a-z0-9-]+:0\.5\.0-SNAPSHOT""")
private val SNAPSHOT_MAVEN_VERSION = Regex("""<version>\s*0\.5\.0-SNAPSHOT\s*</version>""")
private val SNAPSHOT_VARIABLE = Regex("""tramaiVersion\s*=\s*"0\.5\.0-SNAPSHOT"""")

/** `x.y.z` capture used by both active-coordinate patterns. */
private const val DOTTED_VERSION_CAPTURE = """([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)"""

/** Newest dated release heading in CHANGELOG.md: `## <x.y.z> - <date>`. */
private val DATED_RELEASE_HEADING = Regex("""(?m)^## (\d+\.\d+\.\d+) - \d{4}-\d{2}-\d{2}""")

/** Numeric `x.y.z` ordering of two release versions; unparseable input sorts below everything. */
private const val UNPARSEABLE = -1

/** `major.minor.patch`. */
private const val RELEASE_VERSION_PARTS = 3

/** The three `x.y.z` components of a release version, or null when it is not one. */
private fun parseReleaseVersion(version: String): List<Int>? =
    version.split(".").map { it.toIntOrNull() ?: return null }.takeIf { it.size == RELEASE_VERSION_PARTS }

/** Numeric `x.y.z` ordering of two release versions; unparseable input sorts below everything. */
private fun compareReleaseVersions(
    left: String,
    right: String,
): Int {
    val leftParts = parseReleaseVersion(left)
    val rightParts = parseReleaseVersion(right)
    if (leftParts == null || rightParts == null) return UNPARSEABLE
    return leftParts.zip(rightParts).firstOrNull { (l, r) -> l != r }?.let { (l, r) -> l.compareTo(r) } ?: 0
}

/** Active consumer docs plus every module card; historical release records are excluded later. */
private fun activeConsumerDocs(rootDir: File): List<String> {
    val consumerDocs =
        listOf(
            "README.md",
            "docs/guides/getting-started.md",
            "docs/guides/quickstart.md",
            "docs/guides/spring-boot.md",
            "docs/guides/standalone-usage.md",
            "docs/guides/tutorial-invoice-analyzer.md",
            "docs/module-guide.md",
            "docs/STATUS.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
            "docs/reference/releasing.md",
            "examples/README.md",
            "examples/support-agent/build.gradle.kts",
            "examples/kotlin-springboot-example/build.gradle.kts",
            "examples/kotlin-native-smoke-example/build.gradle.kts",
            "examples/sovereign-runtime-consumer-smoke/build.gradle.kts",
            "examples/spring-sovereign-starter/build.gradle.kts",
        )
    val moduleDocsDir = File(rootDir, "docs/modules")
    val moduleDocs =
        if (moduleDocsDir.isDirectory) {
            moduleDocsDir
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".md") }
                .map { it.path }
        } else {
            emptyList()
        }
    return consumerDocs + moduleDocs
}

fun verifyVersionAlignmentSurfaces(
    rootDir: File,
    expectedVersion: String,
    expectedReleaseDate: String,
) {
    requireVersionProperties(rootDir, expectedVersion)
    val releaseScopedVersion = requireChangelogReleaseSection(rootDir, expectedVersion, expectedReleaseDate)
    requireReleaseSurfaces(rootDir, releaseScopedVersion)
    requireConsumerDocCoordinates(rootDir, releaseScopedVersion)
}

/** 1-2: the project version is the single authority, and the build fallback agrees with it. */
private fun requireVersionProperties(
    rootDir: File,
    expectedVersion: String,
) {
    // 1. gradle.properties contains exactly tramaiVersion=<expectedVersion>
    val propsFile = File(rootDir, "gradle.properties")
    require(propsFile.isFile) { "Missing gradle.properties" }
    val propsText = propsFile.readText()
    val committedVersion =
        propsText
            .lineSequence()
            .single { it.startsWith("tramaiVersion=") }
            .substringAfter("=")
            .trim()
    require(committedVersion == expectedVersion) {
        "gradle.properties must set tramaiVersion exactly to $expectedVersion, got '$committedVersion'"
    }

    // 2. Build fallback is expectedVersion
    val buildFile = File(rootDir, "build.gradle.kts")
    val buildText = buildFile.readText()
    require(buildText.contains("orElse(\"$expectedVersion\")")) {
        "build.gradle.kts fallback must be $expectedVersion"
    }
}

/**
 * 3: `## Unreleased` is present above a dated release section, and returns the version the
 * release-scoped surfaces of this tree describe (see [versionAlignment]).
 */
private fun requireChangelogReleaseSection(
    rootDir: File,
    expectedVersion: String,
    expectedReleaseDate: String,
): String {
    // 3. CHANGELOG.md has ## Unreleased present above a dated release section.
    //
    // Release-scoped surfaces — the dated CHANGELOG section, the release-readiness document,
    // the roadmap release train and consumer dependency coordinates — describe a *promoted
    // release*. On a development line the project version carries a -SNAPSHOT suffix, so those
    // surfaces still describe the last promoted release: keep verifying the same promises
    // against the release they describe, instead of demanding release-promotion artifacts for
    // an unreleased snapshot.
    val changelog = File(rootDir, "CHANGELOG.md")
    val changelogText = changelog.readText()
    require(changelogText.contains("## Unreleased")) {
        "CHANGELOG.md must retain ## Unreleased heading"
    }
    val afterUnreleased = changelogText.substringAfter("## Unreleased")
    val developmentLine = TramaiVersions.isSnapshot(expectedVersion)
    val releaseScopedVersion =
        if (developmentLine) {
            DATED_RELEASE_HEADING.find(changelogText)?.groupValues?.get(1) ?: expectedVersion
        } else {
            expectedVersion
        }
    if (developmentLine) {
        require(afterUnreleased.contains("## $releaseScopedVersion - ")) {
            "CHANGELOG.md must contain a dated $releaseScopedVersion section after ## Unreleased " +
                "(release-scoped surface of the $expectedVersion development line)"
        }
        // The one thing a development line must not do is target a release that is not ahead
        // of the last promoted one: that would mislabel the line rather than merely postpone
        // its promotion.
        require(compareReleaseVersions(TramaiVersions.releaseVersionOf(expectedVersion), releaseScopedVersion) > 0) {
            "Development line $expectedVersion must target a release after the last promoted " +
                "release $releaseScopedVersion"
        }
    } else {
        // After promotion, ## Unreleased is immediately followed by ## <expectedVersion>
        require(afterUnreleased.contains("## $expectedVersion - $expectedReleaseDate")) {
            "CHANGELOG.md must contain a dated $expectedVersion section after ## Unreleased"
        }
    }

    return releaseScopedVersion
}

/** 5-8, 10: release surfaces, roadmap train and release documents for [releaseScopedVersion]. */
private fun requireReleaseSurfaces(
    rootDir: File,
    releaseScopedVersion: String,
) {
    // 5. No active <expectedVersion>-SNAPSHOT references remain
    // 6. 0.4.0 remains documented as the previous release where relevant
    val statusDoc = File(rootDir, "docs/STATUS.md")
    val statusText = statusDoc.readText()
    require(statusText.contains("0.4.0") && statusText.contains("Latest published release")) {
        "STATUS.md must identify 0.4.0 as latest published release"
    }

    // 7. The roadmap identifies the completed expectedVersion train
    val roadmap = File(rootDir, "docs/POST-SOVEREIGNTY-ROADMAP.md")
    val roadmapText = roadmap.readText()
    require(roadmapText.contains("Release train: TramAI $releaseScopedVersion")) {
        "Roadmap must identify release train $releaseScopedVersion"
    }
    require(roadmapText.contains("$releaseScopedVersion release")) {
        "Roadmap must reference $releaseScopedVersion release"
    }
    // 7b. Roadmap tables use valid Markdown (no line starting with ||)
    require(!roadmapText.lineSequence().any { it.trimStart().startsWith("||") }) {
        "Roadmap contains malformed Markdown table rows beginning with '||' — pipe prefixes must be a single |"
    }

    // 8. Release notes and readiness documents exist
    require(File(rootDir, "docs/releases/$releaseScopedVersion-release-readiness.md").isFile) {
        "Missing $releaseScopedVersion release-readiness document"
    }
    require(File(rootDir, "docs/releases/sovereign-runtime-release-readiness.md").isFile) {
        "Missing sovereign-runtime release-readiness document"
    }

    // 10. No malformed Markdown tables or prohibited claims
    require(!roadmapText.lineSequence().any { it.trimStart().startsWith("||") }) {
        "Roadmap contains malformed Markdown table rows beginning with '||'"
    }
}

/** 9: no consumer doc advertises coordinates other than [releaseScopedVersion]. */
private fun requireConsumerDocCoordinates(
    rootDir: File,
    releaseScopedVersion: String,
) {
    // 9. Consumer docs use expectedVersion for active coordinates (historical records excluded)
    val allConsumerDocs = activeConsumerDocs(rootDir)

    // Historical allowlist - old release records
    val historicalAllowlist =
        setOf(
            "docs/releases/CHANGELOG-0.3.1.md",
            "docs/releases/CHANGELOG-0.4.0.md",
            "docs/guides/secure-defaults-migration.md",
            "docs/reference/release-0.1.0.md",
        )

    val historical = historicalAllowlist.map { File(rootDir, it).canonicalPath }.toSet()

    for (path in allConsumerDocs) {
        val f = File(rootDir, path)
        if (!f.isFile || f.canonicalPath in historical) continue
        val content = f.readText()

        // No stale SNAPSHOT references in active docs
        require(!SNAPSHOT_GRADLE_COORDINATE.containsMatchIn(content)) {
            "Consumer doc $path still contains dev.tramai:*:0.5.0-SNAPSHOT dependency reference"
        }
        require(!SNAPSHOT_MAVEN_VERSION.containsMatchIn(content)) {
            "Consumer doc $path still contains Maven <version>0.5.0-SNAPSHOT</version>"
        }
        require(!SNAPSHOT_VARIABLE.containsMatchIn(content)) {
            "Consumer doc $path still contains tramaiVersion = \"0.5.0-SNAPSHOT\""
        }

        // Reject any stale Gradle coordinates (dev.tramai:*:x.y.z where x.y.z != expectedVersion)
        val gradleCoordinatePattern =
            Regex("""dev\.tramai:[a-z0-9-]+:$DOTTED_VERSION_CAPTURE""")
        val staleGradleCoords =
            gradleCoordinatePattern
                .findAll(content)
                .filter { it.groupValues[1] != releaseScopedVersion }
                .map { it.value }
                .toList()
        require(staleGradleCoords.isEmpty()) {
            "Consumer doc $path contains stale TramAI Gradle coordinates: ${staleGradleCoords.joinToString()}"
        }

        // Reject any stale Maven versions in dev.tramai dependency blocks
        val mavenDevTramaiDependency =
            Regex(
                """<groupId>dev\.tramai</groupId>.*?<version>\s*$DOTTED_VERSION_CAPTURE\s*</version>""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )
        val staleMvnVersions =
            mavenDevTramaiDependency
                .findAll(content)
                .filter { it.groupValues[1] != releaseScopedVersion }
                .map { it.value }
                .toList()
        require(staleMvnVersions.isEmpty()) {
            "Consumer doc $path contains stale TramAI Maven versions: ${staleMvnVersions.joinToString()}"
        }
    }
}
