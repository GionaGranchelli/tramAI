package dev.tramai.build.docs

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Selects which pure documentation-contract verifier a
 * [DocsContractVerifierTask] delegation runs. Enum values are
 * configuration-cache serializable; the enum is the only discriminator the
 * task needs to route to the right pure verifier.
 */
enum class DocGuardKind {
    /** Generic required/forbidden claims path over [DocsContractVerifierTask.documents]. */
    GENERIC_CLAIMS,
    PRODUCT_POSITIONING,
    README_POSITIONING,
    GOVERNED_WORKFLOW_ARTICLE,
    EXAMPLE_SELECTION_GUIDE,
    JVM_AI_FRAMEWORK_COMPARISON,
    WORKFLOW_API_STABILITY_BOUNDARY,
    VERSION_ALIGNMENT,
    TOOL_GOVERNANCE_EXAMPLE,
    MODULE_DOC_CONTRACT,
}

/**
 * Reusable typed documentation-contract verifier (Epic 9.2d-a2).
 *
 * One task type for all ten doc-guard registrations:
 * - [DocGuardKind.VERSION_ALIGNMENT], [DocGuardKind.TOOL_GOVERNANCE_EXAMPLE],
 *   [DocGuardKind.MODULE_DOC_CONTRACT] and the per-document positional guards
 *   delegate to the pure verifiers in [RootDocGuardVerifiers] (semantics that
 *   do not fit a shared required/forbidden claim list);
 * - when [verifierKind] is unset, the task runs the ordinary
 *   required/forbidden textual contract over [documents].
 *
 * Diagnostic strings are part of the contract: message prefixes are wired per
 * registration so failures reproduce the historical root build.gradle.kts
 * messages byte-for-byte.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class DocsContractVerifierTask : DefaultTask() {

    @get:Input
    abstract val contractId: Property<String>

    /** Pure-verifier selector; unset means the generic claims path below. */
    @get:Optional
    @get:Input
    abstract val verifierKind: Property<DocGuardKind>

    /** Root project directory, needed by the pure verifier delegates. */
    @get:Optional
    @get:Input
    abstract val rootDir: Property<File>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    // ---- Generic claims path (verifierKind unset) ----

    @get:Input
    abstract val requiredClaims: ListProperty<String>

    @get:Input
    abstract val forbiddenClaims: ListProperty<String>

    /** Required-claim matching is case-sensitive by default (historical semantics). */
    @get:Input
    abstract val requiredClaimsIgnoreCase: Property<Boolean>

    /** Forbidden-claim matching is case-insensitive by default (historical semantics). */
    @get:Input
    abstract val forbiddenClaimsIgnoreCase: Property<Boolean>

    /** Byte-exact diagnostic prefix for a missing required claim (historical message). */
    @get:Input
    abstract val requiredClaimMessagePrefix: Property<String>

    /** Byte-exact diagnostic prefix for a present forbidden claim (historical message). */
    @get:Input
    abstract val forbiddenClaimMessagePrefix: Property<String>

    // ---- Version-alignment inputs (DocGuardKind.VERSION_ALIGNMENT) ----

    @get:Optional
    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Optional
    @get:Input
    abstract val expectedReleaseDate: Property<String>

    init {
        requiredClaims.convention(emptyList())
        forbiddenClaims.convention(emptyList())
        requiredClaimsIgnoreCase.convention(false)
        forbiddenClaimsIgnoreCase.convention(true)
        requiredClaimMessagePrefix.convention("Missing required claim: ")
        forbiddenClaimMessagePrefix.convention("Forbidden claim found: ")
    }

    @TaskAction
    fun verify() {
        val id = contractId.get()
        when (verifierKind.orNull) {
            DocGuardKind.PRODUCT_POSITIONING -> RootDocGuardVerifiers.productPositioning(rootDir.get())
            DocGuardKind.README_POSITIONING -> RootDocGuardVerifiers.readmePositioning(rootDir.get())
            DocGuardKind.GOVERNED_WORKFLOW_ARTICLE -> RootDocGuardVerifiers.governedWorkflowArticle(rootDir.get())
            DocGuardKind.EXAMPLE_SELECTION_GUIDE -> RootDocGuardVerifiers.exampleSelectionGuide(rootDir.get())
            DocGuardKind.JVM_AI_FRAMEWORK_COMPARISON -> RootDocGuardVerifiers.jvmAiFrameworkComparison(rootDir.get())
            DocGuardKind.WORKFLOW_API_STABILITY_BOUNDARY -> RootDocGuardVerifiers.workflowApiStabilityBoundary(rootDir.get())
            DocGuardKind.VERSION_ALIGNMENT -> {
                val releaseDate = expectedReleaseDate.orNull
                    ?: throw GradleException("tramaiReleaseDate must be set in gradle.properties")
                RootDocGuardVerifiers.versionAlignment(rootDir.get(), expectedVersion.get(), releaseDate)
            }
            DocGuardKind.TOOL_GOVERNANCE_EXAMPLE -> RootDocGuardVerifiers.toolGovernanceExample(rootDir.get())
            DocGuardKind.MODULE_DOC_CONTRACT -> {
                val diagnostics = RootDocGuardVerifiers.moduleDocContract(rootDir.get())
                if (diagnostics.isNotEmpty()) {
                    throw GradleException(
                        diagnostics.joinToString("\n") { "[${it.code}] ${it.message}" }
                    )
                }
            }
            DocGuardKind.GENERIC_CLAIMS -> verifyGenericClaims(id)
            // Fail loud instead of degrading to a vacuous pass: a registration
            // that forgets verifierKind.set() must never silently succeed.
            null -> throw GradleException("$id: verifierKind is not configured; register with an explicit DocGuardKind")
        }
        logger.lifecycle("$id: documentation contract verification complete.")
    }

    private fun verifyGenericClaims(id: String) {
        val required = requiredClaims.get()
        val forbidden = forbiddenClaims.get()
        val requiredIgnoreCase = requiredClaimsIgnoreCase.get()
        val forbiddenIgnoreCase = forbiddenClaimsIgnoreCase.get()

        val resolved = documents.files.sortedBy { it.absolutePath }
        if (resolved.isEmpty()) {
            throw GradleException("$id: no input documents resolved")
        }
        for (doc in resolved) {
            if (!doc.isFile) {
                throw GradleException("$id: missing document at ${doc.absolutePath}")
            }
            val text = doc.readText()
            for (claim in required) {
                if (!text.contains(claim, ignoreCase = requiredIgnoreCase)) {
                    throw GradleException(requiredClaimMessagePrefix.get() + claim)
                }
            }
            for (claim in forbidden) {
                if (text.contains(claim, ignoreCase = forbiddenIgnoreCase)) {
                    throw GradleException(forbiddenClaimMessagePrefix.get() + claim)
                }
            }
        }
    }
}
