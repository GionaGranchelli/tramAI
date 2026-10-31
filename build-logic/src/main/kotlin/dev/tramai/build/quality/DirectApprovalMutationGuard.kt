package dev.tramai.build.quality

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Source-level guard for direct, authority-free approval mutations, driven by Kotlin's own parser.
 *
 * ## What this proves
 *
 * Every production call expression named `transition` in a file that mentions the
 * `dev.tramai.core.approval` package must appear in the reviewed inventory in
 * `config/quality/direct-approval-mutation-inventory.yml`, with a rationale and an exact call-site count. A new call
 * site, a new call inside an already-reviewed file, a stale entry, an unrationalised entry, a vacuous scan and a file
 * the parser cannot read all fail the guard.
 *
 * Executable calls only: comments (nested included), inert string text and raw strings cannot produce a call site,
 * while a call inside a string template (`"${store.transition(1)}"`) can, because the compiler executes it. This is
 * Kotlin's grammar, not an approximation of it.
 *
 * ## What this does not prove
 *
 * There is no type resolution. The receiver of a counted call is not checked, so a `transition` method on any other
 * type inside an approval-aware file is counted too - the guard asks for review rather than risk silence. Files are
 * pre-filtered by a textual mention of the approval package, so a call in a file that reaches the store through a
 * fully qualified name it never spells would not be a candidate. Aliases, extension functions, reflection and wrapper
 * functions that call the store internally are invisible.
 *
 * It is therefore a reviewability ratchet over a *visible population*, not Kotlin or Java authorization enforcement,
 * and it does not close the public `ApprovalStore.transition` bypass.
 */
object DirectApprovalMutationGuard {
    private const val MUTATION_METHOD = "transition"
    private val approvalReference = Regex("""dev\.tramai\.core\.approval""")

    /** One production file that calls the direct mutation path, with the number of call sites in it. */
    data class DetectedSite(
        val path: String,
        val callSites: Int,
    )

    /** Lazily created once per build JVM; parsing needs a project, not a compilation. */
    private val parsing: Parsing by lazy { Parsing() }

    private class Parsing {
        private val disposable: Disposable = Disposer.newDisposable("direct-approval-mutation-guard")
        private val factory: KtPsiFactory

        init {
            val configuration = CompilerConfiguration()
            configuration.put(CommonConfigurationKeys.MODULE_NAME, "direct-approval-mutation-guard")
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    configuration,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            factory = KtPsiFactory(environment.project, markGenerated = false)
        }

        fun parse(
            path: String,
            text: String,
        ): KtFile = factory.createFile(path.substringAfterLast('/'), text)
    }

    /**
     * Call expressions named `transition` with a receiver, as the parser sees them. The referenced name is used rather
     * than the source text, so a backtick-escaped call (`store.`transition`(1)`) is the same call as an unescaped one.
     */
    private fun transitionCalls(file: KtFile): Int =
        file.collectDescendantsOfType<KtCallExpression>().count { call ->
            (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == MUTATION_METHOD &&
                call.parent is KtQualifiedExpression
        }

    private fun isCandidate(text: String): Boolean = approvalReference.containsMatchIn(text)

    /** Candidate files the parser could not read. They fail the guard rather than being silently skipped. */
    private fun hasParseError(file: KtFile): Boolean = file.collectDescendantsOfType<PsiErrorElement>().isNotEmpty()

    fun unparsable(files: List<Pair<String, String>>): List<String> =
        files
            .filter { (_, text) -> isCandidate(text) }
            .filter { (path, text) -> hasParseError(parsing.parse(path, text)) }
            .map { (path, _) -> path }
            .sorted()

    fun detect(files: List<Pair<String, String>>): List<DetectedSite> =
        files
            .filter { (_, text) -> isCandidate(text) }
            .map { (path, text) -> path to transitionCalls(parsing.parse(path, text)) }
            .filter { (_, count) -> count > 0 }
            .map { (path, count) -> DetectedSite(path, count) }
            .sortedBy { it.path }

    /** Drops the files the inventory excludes, so the task judges and counts the population it actually scanned. */
    fun included(
        inventory: DirectApprovalMutationInventory,
        files: List<Pair<String, String>>,
    ): List<Pair<String, String>> = files.filterNot { (path, _) -> inventory.excludes(path) }

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

        for (path in unparsable(population)) {
            violations +=
                "direct-approval-mutation-guard: cannot parse $path safely, so its mutation call sites cannot be " +
                "counted. Fix the syntax; the guard fails closed rather than assuming there is no call."
        }

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
