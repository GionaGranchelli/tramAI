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
 * `ApprovalStore` from any other type with a `transition` method; it does not follow aliases, local
 * variables, extension functions, reflection, or calls made through a wrapper that itself calls
 * `transition`. A file that reaches the store through such a wrapper is invisible to it, and a call
 * spelled without a receiver dot (for example inside the store implementation itself) is not a call
 * site and is deliberately not counted. It is a reviewability mechanism that makes the *visible*
 * population explicit and ratcheted. It is **not** Kotlin or Java authorization enforcement, and it
 * does not close the public `ApprovalStore.transition` bypass.
 */
object DirectApprovalMutationGuard {
    private val callPattern = Regex("""\.transition\s*\(""")
    private val approvalImport = Regex("""(?m)^\s*import\s+dev\.tramai\.core\.approval[\w.]*""")

    /** One production file that calls the direct mutation path, with the number of call sites in it. */
    data class DetectedSite(
        val path: String,
        val callSites: Int,
    )

    /** Removes line and block comments so a commented-out call is not counted. */
    internal fun stripComments(source: String): String {
        val withoutBlocks =
            Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source) { match ->
                // keep line count stable so nothing downstream depends on offsets
                "\n".repeat(match.value.count { it == '\n' })
            }
        return withoutBlocks
            .lines()
            .joinToString("\n") { line -> if (line.trimStart().startsWith("//")) "" else line }
    }

    fun detect(files: List<Pair<String, String>>): List<DetectedSite> =
        files
            .filter { (_, text) -> approvalImport.containsMatchIn(text) }
            .map { (path, text) -> path to callPattern.findAll(stripComments(text)).count() }
            .filter { (_, count) -> count > 0 }
            .map { (path, count) -> DetectedSite(path, count) }
            .sortedBy { it.path }

    /**
     * Returns every reason the population is not exactly the reviewed one. An empty list is a pass.
     *
     * [scannedFiles] is the number of files the task actually fed in: zero files means the scan is
     * vacuous and must fail rather than report success over an empty population.
     */
    fun verify(
        inventory: DirectApprovalMutationInventory,
        files: List<Pair<String, String>>,
        scannedFiles: Int = files.size,
    ): List<String> {
        val violations = mutableListOf<String>()
        if (scannedFiles == 0) {
            violations +=
                "direct-approval-mutation-guard: scanned 0 files; an empty scan cannot pass. " +
                "Check the configured scan roots in config/quality/direct-approval-mutation-inventory.yml."
        }
        inventory.problems.forEach { violations += "direct-approval-mutation-guard: inventory: $it" }

        val detected = detect(files).associateBy { it.path }
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
