package dev.tramai.build.quality

// The applicability parser behind the merge gate's path-conditional requirements: reading a workflow's own
// `pull_request.paths:` filters instead of transcribing them into the gate.
//
// Separate from MergeGateVerifier because that file sits at its function-count limit, and because this is a
// distinct concern — turning workflow text into the filters a requirement is decided by — with its own
// fail-closed rule. Both the parser and the fallback are unit-tested in MergeGateVerifierTest.

/** The filter returned when a workflow declares no parsable `pull_request.paths:`. */
internal const val MATCHES_EVERY_PATH = "**"

/** The keys and sequence prefix the `pull_request:` path parser reads. */
private const val PR_TRIGGER = "pull_request:"

private const val PATHS_KEY = "paths:"

private const val ITEM_PREFIX = "- "

private const val COMMENT_PREFIX = "#"

/**
 * The `pull_request.paths:` filters a workflow declares, read from that workflow's own YAML.
 *
 * Read rather than transcribed, because a transcribed copy drifts the moment the workflow's `paths:` change,
 * and a drifted copy fails in both directions: it demands a workflow that legitimately did not run, or stops
 * demanding one that should have. The gate keeps the authority where the workflow declares it.
 *
 * Fail closed: text that yields no usable filter returns a filter matching every path, so an unreadable or
 * reshaped workflow file makes the conditional requirement unconditional — a refusal — rather than
 * inapplicable, which would be a silent pass.
 */
internal fun pullRequestPathFilters(text: String): List<String> {
    val lines = text.lineSequence().toList()
    return filtersInPullRequestBlock(lines).ifEmpty { listOf(MATCHES_EVERY_PATH) }
}

/**
 * The `paths:` items the file declares for `pull_request:`, or nothing when it declares neither.
 *
 * `pull_request:` sits under `on:`, so its indentation is read rather than assumed: matching on column 0
 * would miss it and fall back to demanding the workflow on every pull request, which is the defect this
 * function exists to remove. The block ends where the indentation returns to that level.
 */
private fun filtersInPullRequestBlock(lines: List<String>): List<String> {
    val blockStart = lines.indexOfFirst { it.trim() == PR_TRIGGER }
    val block =
        if (blockStart < 0) {
            emptyList()
        } else {
            lines.drop(blockStart + 1).takeWhile { indentOf(it) > indentOf(lines[blockStart]) }
        }
    val pathsAt = block.indexOfFirst { it.trim() == PATHS_KEY }
    return if (pathsAt < 0) {
        emptyList()
    } else {
        val pathsIndent = indentOf(block[pathsAt])
        block
            .drop(pathsAt + 1)
            .takeWhile { keepsPathsSequenceOpen(it, pathsIndent) }
            .filter { it.trim().startsWith(ITEM_PREFIX) }
            .map {
                it
                    .trim()
                    .removePrefix(ITEM_PREFIX)
                    .trim()
                    .trim('"', '\'')
            }
    }
}

/**
 * Whether [line] leaves the `paths:` sequence that starts at [pathsIndent] open.
 *
 * Open means one of its items, or an intervening blank or comment line. The sequence CLOSES at the first key
 * line, so collecting by indentation alone is wrong in a way that reintroduces the defect this parser removes:
 * a sibling key such as `branches:` sits at exactly `paths:`'s indentation, and its own items are indented just
 * as deep as the paths, so a purely positional scan accepts the sibling's entries as path filters and the gate
 * then demands an RC run for pull requests GitHub never triggered it for.
 */
private fun keepsPathsSequenceOpen(
    line: String,
    pathsIndent: Int,
): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
        return true
    }
    return trimmed.startsWith(ITEM_PREFIX) && indentOf(line) >= pathsIndent
}

/** A line's indentation. A blank line counts as deeper than anything, so it never ends a block. */
private fun indentOf(line: String): Int {
    val indent = line.indexOfFirst { !it.isWhitespace() }
    return if (indent < 0) Int.MAX_VALUE else indent
}
