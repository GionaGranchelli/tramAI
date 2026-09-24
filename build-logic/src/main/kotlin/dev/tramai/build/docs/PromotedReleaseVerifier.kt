package dev.tramai.build.docs

import java.io.File

/*
 * Promoted-release authority: the last release actually published for consumers. Distinct from
 * `tramaiVersion`, which is the development/build version (a `-SNAPSHOT` line). Anything that
 * describes published artifacts must name a promoted release rather than a development version or a
 * historical literal that silently goes stale at every release cut.
 */

/** A dated release as recorded in CHANGELOG.md. */
internal data class PromotedRelease(
    val version: String,
    val date: String,
)

/** Newest dated release heading in CHANGELOG.md: `## X.Y.Z - YYYY-MM-DD`, releases listed newest first. */
private val DATED_RELEASE_HEADING = Regex("""(?m)^## (\d+\.\d+\.\d+) - (\d{4}-\d{2}-\d{2})""")

/**
 * The last promoted release: the first dated CHANGELOG heading, which is the newest by the
 * repository's newest-first changelog convention, together with the date it carries. Fails closed
 * when no dated heading exists.
 *
 * This is the single authority for "what is the newest promoted release and when was it cut?".
 * Callers that need only the version use [promotedReleaseVersion].
 */
internal fun promotedRelease(rootDir: File): PromotedRelease =
    DATED_RELEASE_HEADING
        .find(File(rootDir, "CHANGELOG.md").readText())
        ?.let { match -> PromotedRelease(match.groupValues[1], match.groupValues[2]) }
        ?: error(
            "CHANGELOG.md must contain a dated release heading " +
                "(## X.Y.Z - YYYY-MM-DD)",
        )

/** [promotedRelease] version only. */
internal fun promotedReleaseVersion(rootDir: File): String = promotedRelease(rootDir).version
