package dev.tramai.build.quality

/**
 * Source-level guard for direct, authority-free approval mutations.
 *
 * ## What this proves
 *
 * Every production call to `ApprovalStore.transition(...)` must appear in the reviewed inventory in
 * `config/quality/direct-approval-mutation-inventory.yml`, with a rationale and an exact call-site
 * count. A new production call site, a new call inside an already-reviewed file, and a stale inventory
 * entry all fail the guard.
 *
 * ## What this does not prove
 *
 * This is a text scanner, not a type checker. It does **not** resolve receivers, so it cannot tell an
 * `ApprovalStore` from any other type with a `transition` method; it does not follow aliases or local
 * variables, extension functions, reflection, or calls made through a wrapper that itself calls
 * `transition`, and a call spelled without a receiver dot (for example inside the store implementation
 * itself) is not a call site and is deliberately not counted. Candidate files are selected by any
 * mention of the `dev.tramai.core.approval` package - an import or a fully qualified type - which
 * over-selects on purpose: ordinary fully qualified call sites stay visible. It is a reviewability
 * mechanism that makes the *visible* population explicit and ratcheted. It is **not** Kotlin or Java
 * authorization enforcement, and it does not close the public `ApprovalStore.transition` bypass.
 */
object DirectApprovalMutationGuard {
    private val callPattern = Regex("""\.transition\s*\(""")

    /**
     * Candidate rule: the file mentions the approval package at all - an import, or a fully qualified type such as
     * `dev.tramai.core.approval.ApprovalStore`. Matching the package rather than an `import` statement keeps an
     * ordinary fully qualified call site visible; it deliberately over-selects (see the class KDoc).
     */
    private val approvalReference = Regex("""dev\.tramai\.core\.approval""")

    /** One production file that calls the direct mutation path, with the number of call sites in it. */
    data class DetectedSite(
        val path: String,
        val callSites: Int,
    )

    /**
     * Removes line, trailing and block comments so a commented-out call is not counted. String literals are copied
     * through untouched, so a URL in a string cannot be mistaken for a comment; triple-quoted raw strings are not
     * modelled (a `//` inside one may be stripped).
     */
    internal fun stripComments(source: String): String = stripLineComments(stripBlockComments(source))

    private fun stripBlockComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var inString = false
        var inBlock = false
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1) ?: '\u0000'
            when {
                inBlock -> {
                    if (current == '*' && next == '/') {
                        inBlock = false
                        index++
                    } else if (current == '\n') {
                        out.append('\n')
                    }
                }

                inString -> {
                    out.append(current)
                    index = consumeEscape(source, index, out, current)
                    if (current == '"') inString = false
                }

                current == '"' -> {
                    inString = true
                    out.append(current)
                }

                current == '/' && next == '*' -> {
                    inBlock = true
                    index++
                }

                else -> {
                    out.append(current)
                }
            }
            index++
        }
        return out.toString()
    }

    private fun stripLineComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var inString = false
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1) ?: '\u0000'
            when {
                inString -> {
                    out.append(current)
                    index = consumeEscape(source, index, out, current)
                    if (current == '"') inString = false
                }

                current == '"' -> {
                    inString = true
                    out.append(current)
                }

                current == '/' && next == '/' -> {
                    while (index < source.length && source[index] != '\n') index++
                    out.append('\n')
                }

                else -> {
                    out.append(current)
                }
            }
            index++
        }
        return out.toString()
    }

    /** Copies an escaped character verbatim; returns the index it consumed (unchanged when not an escape). */
    private fun consumeEscape(
        source: String,
        index: Int,
        out: StringBuilder,
        current: Char,
    ): Int {
        if (current != '\\') return index
        source.getOrNull(index + 1)?.let { out.append(it) }
        return index + 1
    }

    /** Drops the files the inventory excludes, so the task judges and counts the population it actually scanned. */
    fun included(
        inventory: DirectApprovalMutationInventory,
        files: List<Pair<String, String>>,
    ): List<Pair<String, String>> = files.filterNot { (path, _) -> inventory.excludes(path) }

    fun detect(files: List<Pair<String, String>>): List<DetectedSite> =
        files
            .filter { (_, text) -> approvalReference.containsMatchIn(text) }
            .map { (path, text) -> path to callPattern.findAll(stripComments(text)).count() }
            .filter { (_, count) -> count > 0 }
            .map { (path, count) -> DetectedSite(path, count) }
            .sortedBy { it.path }

    /**
     * Returns every reason the population is not exactly the reviewed one. An empty list is a pass.
     *
     * The judged population is [files] minus the inventory's exclusions: non-vacuity is asserted on what was actually
     * scanned, so a configuration that excludes everything fails instead of reporting success.
     */
    fun verify(
        inventory: DirectApprovalMutationInventory,
        files: List<Pair<String, String>>,
    ): List<String> {
        val violations = mutableListOf<String>()
        val population = included(inventory, files)
        if (population.isEmpty()) {
            violations +=
                "direct-approval-mutation-guard: scanned 0 production files " +
                "(${files.size} selected, ${files.size - population.size} excluded by the inventory); " +
                "an empty scan cannot pass. Check scanRoots and exclusions in " +
                "config/quality/direct-approval-mutation-inventory.yml."
        }
        inventory.problems.forEach { violations += "direct-approval-mutation-guard: inventory: $it" }

        val detected = detect(population).associateBy { it.path }
        for ((path, site) in detected) {
            val entry = inventory.entries.firstOrNull { it.path == path }
            when {
                entry == null -> {
                    violations +=
                        "direct-approval-mutation-guard: unreviewed direct approval mutation in $path " +
                        "(${site.callSites} call site(s)). Use ApprovalDecisionControlPlane for operator " +
                        "decisions; if this call is genuinely outside that path, add an inventory entry with " +
                        "this exact path, the count ${site.callSites}, and a rationale."
                }

                entry.callSites != site.callSites -> {
                    violations +=
                        "direct-approval-mutation-guard: $path has ${site.callSites} direct approval mutation " +
                        "call site(s) but the inventory reviewed ${entry.callSites}. Re-review the file and " +
                        "update the count and rationale deliberately."
                }
            }
        }
        for (entry in inventory.entries) {
            if (entry.path !in detected) {
                violations +=
                    "direct-approval-mutation-guard: stale inventory entry for ${entry.path}; the file no longer " +
                    "contains a detectable direct approval mutation. Remove the entry."
            }
        }
        return violations
    }
}

/** One reviewed direct-mutation file: how many call sites it has, and why that is acceptable. */
data class DirectApprovalMutationEntry(
    val path: String,
    val callSites: Int,
    val rationale: String,
)

/** A deliberately unscanned root. Exclusions are inventory data, not a scanning accident. */
data class DirectApprovalMutationExclusion(
    val path: String,
    val rationale: String,
)

data class DirectApprovalMutationInventory(
    val scanRoots: List<String>,
    val exclusions: List<DirectApprovalMutationExclusion>,
    val entries: List<DirectApprovalMutationEntry>,
) {
    /** Structural problems with the inventory itself, independent of the sources it is compared against. */
    val problems: List<String>
        get() {
            val found = mutableListOf<String>()
            if (scanRoots.isEmpty()) found += "no scanRoots configured"
            exclusions.filter { it.rationale.isBlank() }.forEach {
                found += "exclusion ${it.path} has no rationale"
            }
            entries.filter { it.rationale.isBlank() }.forEach {
                found += "entry ${it.path} has no rationale"
            }
            entries.filter { it.callSites < 1 }.forEach {
                found += "entry ${it.path} declares ${it.callSites} call sites"
            }
            entries.groupBy { it.path }.filterValues { it.size > 1 }.keys.forEach {
                found += "duplicate entry for $it"
            }
            return found
        }

    fun excludes(relativePath: String): Boolean =
        exclusions.any { relativePath == it.path || relativePath.startsWith(it.path.trimEnd('/') + "/") }
}
