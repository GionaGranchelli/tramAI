package dev.tramai.build.quality

/**
 * Version vocabulary for the quality gates, kept dependency-free so build-logic tasks and the
 * docs guards share one interpretation.
 *
 * The repository has a single version authority: the `tramaiVersion` Gradle property, which is both
 * the artifact version and the value the gates read. On a development line it carries a
 * `-SNAPSHOT` suffix (release publishing refuses such a value, which is what marks it as
 * pre-release), while every release-scoped contract — migration target versions, CHANGELOG
 * sections, release-readiness documents, roadmap release trains — names the release the line will
 * become.
 */
object TramaiVersions {
    /**
     * The release a project version belongs to: `0.7.0-SNAPSHOT` -> `0.7.0`, `0.7.0` -> `0.7.0`.
     *
     * Deliberately narrow: only the exact `-SNAPSHOT` suffix is stripped, so a migration entry
     * remains truthful both during development and after the release cut. A general SemVer
     * pre-release parser is not needed by any current consumer.
     */
    fun releaseVersionOf(projectVersion: String): String = projectVersion.removeSuffix(SNAPSHOT_SUFFIX)

    /** True when the project version is a development snapshot rather than a promoted release. */
    fun isSnapshot(projectVersion: String): Boolean = projectVersion.endsWith(SNAPSHOT_SUFFIX)

    private const val SNAPSHOT_SUFFIX = "-SNAPSHOT"
}
