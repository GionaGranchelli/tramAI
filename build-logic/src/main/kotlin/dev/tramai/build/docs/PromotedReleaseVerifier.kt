package dev.tramai.build.docs

import java.io.File

/*
 * Promoted-release authority: the last release actually published for consumers. Distinct from
 * `tramaiVersion`, which is the development/build version (a `-SNAPSHOT` line). Anything that
 * describes published artifacts must name a promoted release rather than a development version or a
 * historical literal that silently goes stale at every release cut.
 */

/** Newest dated release heading in CHANGELOG.md: `## X.Y.Z - YYYY-MM-DD`, releases listed newest first. */
private val DATED_RELEASE_HEADING = Regex("""(?m)^## (\d+\.\d+\.\d+) - (\d{4}-\d{2}-\d{2})""")

/**
 * The last promoted release: the first dated CHANGELOG heading, which is the newest by the
 * repository's newest-first changelog convention. This is the single authority for "what is the
 * newest promoted release, and when was it cut?" — [promotedReleaseDate] reads the same heading
 * rather than re-parsing CHANGELOG.md. Fails closed when no dated heading exists.
 */
internal fun promotedReleaseVersion(rootDir: File): String = datedReleaseHeading(rootDir).groupValues[1]

/** The date carried by the [promotedReleaseVersion] heading. */
internal fun promotedReleaseDate(rootDir: File): String = datedReleaseHeading(rootDir).groupValues[2]

private fun datedReleaseHeading(rootDir: File): MatchResult =
    DATED_RELEASE_HEADING
        .find(File(rootDir, "CHANGELOG.md").readText())
        ?: error(
            "CHANGELOG.md must contain a dated release heading " +
                "(## X.Y.Z - YYYY-MM-DD)",
        )
