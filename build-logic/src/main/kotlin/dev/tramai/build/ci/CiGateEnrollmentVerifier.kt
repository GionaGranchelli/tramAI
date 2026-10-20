package dev.tramai.build.ci

import org.yaml.snakeyaml.Yaml

/** A required gate that CI does not effectively execute, named so the diagnostic is actionable. */
data class CiGateEnrollmentFinding(
    val gate: String,
    val workflow: String,
    val detail: String,
) {
    fun describe(): String = "gate '$gate' in '$workflow': $detail"
}

/**
 * Verifies that a required governance gate is *effectively* executed by CI, from workflow semantics rather
 * than from a string search for a task name.
 *
 * A workflow can name a task and still never enforce it: the step can be conditional, the job can be
 * conditional, either can carry `continue-on-error`, the shell can swallow the exit status, or the workflow's
 * triggers may never match the integration branch. None of those are visible to `grep`, and each of them is
 * what this verifier refuses. It also requires the `always()` failure-artifact upload, because a gate whose
 * failure leaves no evidence is barely a gate.
 *
 * The verifier is pure: it takes workflow sources and the required contract, so contract tests drive it with
 * controlled fixtures and the release task drives it with the real `.github/workflows`.
 */
object CiGateEnrollmentVerifier {
    /** A shell assignment inside a step: `NAME=(a b)`, `NAME="a b"` or `NAME=a`. */
    private val ASSIGNMENT = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\(?(.*?)\\)?\\s*$")

    /** Step-level syntax that turns a failing command into a successful step. */
    private val MASKING_PATTERNS = listOf("|| true", "||true", "|| :", "||:", "set +e")

    fun verify(
        workflows: Map<String, String>,
        requiredGates: List<String>,
        requiredBranch: String,
        requiredEvent: String = "pull_request",
    ): List<CiGateEnrollmentFinding> =
        requiredGates
            .distinct()
            .sorted()
            .flatMap { gate -> verifyGate(gate, workflows, requiredBranch, requiredEvent) }

    private fun verifyGate(
        gate: String,
        workflows: Map<String, String>,
        requiredBranch: String,
        requiredEvent: String,
    ): List<CiGateEnrollmentFinding> {
        val selections = workflows.flatMap { (name, text) -> selectionsOf(name, text, gate) }
        val enforced = selections.firstOrNull { disablingReason(it) == null }
        val blocking =
            when {
                selections.isEmpty() -> {
                    listOf(
                        CiGateEnrollmentFinding(
                            gate = gate,
                            workflow =
                                workflows.keys
                                    .sorted()
                                    .joinToString(",")
                                    .ifEmpty { "<no workflow>" },
                            detail = "no workflow step executes it, so CI cannot fail on a violation",
                        ),
                    )
                }

                enforced != null -> {
                    emptyList()
                }

                else -> {
                    listOf(
                        CiGateEnrollmentFinding(
                            gate,
                            selections.first().workflow,
                            disablingReason(selections.first()) ?: "not enforceable",
                        ),
                    )
                }
            }
        return if (blocking.isNotEmpty()) {
            blocking
        } else {
            reachabilityFindings(gate, workflows, enforced, requiredBranch, requiredEvent)
        }
    }

    /**
     * The two reachability conditions, folded into one place because they are read together: the selected
     * workflow must run for the integration branch, and it must keep its failure diagnostics.
     *
     * `on` is a YAML 1.1 boolean, so a mapping spelled `on:` loads under the key `true`; both spellings are
     * accepted rather than reading no triggers at all. An absent `branches` filter means every target branch,
     * which includes the integration branch.
     */
    private fun reachabilityFindings(
        gate: String,
        workflows: Map<String, String>,
        enforced: Selection?,
        requiredBranch: String,
        requiredEvent: String,
    ): List<CiGateEnrollmentFinding> {
        val chosen = requireNotNull(enforced)
        val root = parse(workflows.getValue(chosen.workflow))
        val triggers = root?.get("on") ?: root?.get(true)
        val events =
            when (triggers) {
                is String -> listOf(triggers)
                is List<*> -> triggers.mapNotNull { it?.toString() }
                is Map<*, *> -> triggers.keys.map { it.toString() }
                else -> emptyList()
            }
        val branches = ((triggers as? Map<*, *>)?.get(requiredEvent) as? Map<*, *>)?.get("branches") as? List<*>
        val coversBranch =
            branches == null ||
                branches.any { it?.toString() == requiredBranch || it?.toString() == "release/**" }
        val reachesBranch = requiredEvent in events && coversBranch
        val keepsDiagnostics =
            jobs(root).any { (_, job) ->
                (job["steps"] as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }
                    .orEmpty()
                    .any { step ->
                        val uses = step["uses"] as? String ?: return@any false
                        uses.contains("upload-artifact") && (step["if"] as? String)?.contains("always()") == true
                    }
            }
        val findings = mutableListOf<CiGateEnrollmentFinding>()
        if (!reachesBranch) {
            findings +=
                CiGateEnrollmentFinding(
                    gate,
                    chosen.workflow,
                    "does not run for $requiredEvent events targeting '$requiredBranch'",
                )
        }
        if (!keepsDiagnostics) {
            findings +=
                CiGateEnrollmentFinding(
                    gate,
                    chosen.workflow,
                    "has no 'always()' failure-artifact upload, so a failure leaves no diagnostic",
                )
        }
        return findings
    }

    private fun selectionsOf(
        workflow: String,
        text: String,
        gate: String,
    ): List<Selection> =
        jobs(parse(text)).flatMap { (_, job) ->
            (job["steps"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                .orEmpty()
                .filter { step ->
                    (step["run"] as? String)
                        ?.let { run -> executedCommands(job, run).any { invokesGate(it, gate) } } == true
                }.map { step -> Selection(workflow, job, step) }
        }

    /**
     * The commands this step would actually run, with matrix references resolved.
     *
     * Two things make a naive string search report the wrong answer, and both are in the real workflow:
     *
     * 1. CI passes the gate list through the matrix (`GRADLE_ARGS=(${{ matrix.gates }} --no-daemon)`), so the
     *    task name lives in the matrix definition, not in the step. Every matrix entry runs the same step with
     *    its own value, so the union of those values is genuinely executed.
     * 2. Only a line that invokes `gradlew` executes anything. A gate named in the matrix and *assigned* to a
     *    variable that no command uses is not executed, so the match is restricted to `gradlew` command lines,
     *    with backslash continuations joined first.
     *
     * A matrix value that never reaches a command therefore enlists no gate.
     */
    private fun executedCommands(
        job: Map<*, *>,
        run: String,
    ): List<String> {
        val matrix = (job["strategy"] as? Map<*, *>)?.get("matrix") as? Map<*, *>
        val values = mutableMapOf<String, MutableList<String>>()
        for (entry in (matrix?.get("include") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }) {
            for ((key, value) in entry) {
                values.getOrPut(key.toString()) { mutableListOf() } += value?.toString().orEmpty()
            }
        }
        for ((key, value) in matrix.orEmpty()) {
            if (value is List<*>) {
                values.getOrPut(key.toString()) { mutableListOf() } += value.map { it?.toString().orEmpty() }
            }
        }
        val expanded =
            values.entries.fold(run) { acc, (field, fieldValues) ->
                acc.replace("\${{ matrix.$field }}", fieldValues.joinToString(" "))
            }
        val logical = mutableListOf<String>()
        val buffer = StringBuilder()
        for (line in expanded.lines()) {
            val trimmed = line.trimEnd()
            buffer.append(trimmed.removeSuffix("\\")).append(' ')
            if (!trimmed.endsWith("\\")) {
                logical += buffer.toString()
                buffer.setLength(0)
            }
        }
        if (buffer.isNotEmpty()) logical += buffer.toString()

        // A step may hand the arguments to the command through a shell variable, which is what the real
        // workflow does: `GRADLE_ARGS=(${{ matrix.gates }} --no-daemon)` then `./gradlew "${GRADLE_ARGS[@]}"`.
        // The variable is resolved from its assignment in the same step, so the gate still has to appear in a
        // real command rather than in any line at all.
        val assignments =
            logical
                .mapNotNull { line ->
                    ASSIGNMENT.find(line)?.let { match ->
                        match.groupValues[1] to match.groupValues[2].trim().trim('"', '\'')
                    }
                }.toMap()
        return logical.filter { it.contains("gradlew") }.map { command ->
            assignments.entries.fold(command) { acc, (name, value) ->
                acc
                    .replace("\${" + name + "[@]}", value)
                    .replace("\${" + name + "}", value)
                    .replace("$" + name, value)
            }
        }
    }

    /**
     * Why this selection would not enforce the gate, or null when it would. Each reason names the mechanism,
     * because "the gate is missing", "the step is disabled" and "the exit status is masked" need different
     * fixes.
     */
    private fun disablingReason(selection: Selection): String? {
        val stepCondition = selection.step["if"]
        val jobCondition = selection.job["if"]
        val mask = MASKING_PATTERNS.firstOrNull { (selection.step["run"] as String).contains(it, ignoreCase = true) }
        return when {
            yamlBoolean(stepCondition) == false -> {
                "the step that runs it is conditional ('if: $stepCondition')"
            }

            yamlBoolean(jobCondition) == false -> {
                "its job is conditional ('if: $jobCondition')"
            }

            yamlBoolean(selection.step["continue-on-error"]) == true ||
                yamlBoolean(selection.job["continue-on-error"]) == true -> {
                "it sets continue-on-error, so a failure does not fail the check"
            }

            mask != null -> {
                "the step masks its exit status ('${mask.trim()}'), so a failure becomes a successful step"
            }

            else -> {
                null
            }
        }
    }

    private fun invokesGate(
        run: String,
        gate: String,
    ): Boolean = Regex("(?<![\\w:-])${Regex.escape(gate)}(?![\\w-])").containsMatchIn(run)

    private fun jobs(root: Map<*, *>?): List<Pair<String, Map<*, *>>> =
        (root?.get("jobs") as? Map<*, *>)
            ?.entries
            ?.mapNotNull { entry ->
                (entry.value as? Map<*, *>)?.let { entry.key.toString() to it }
            }.orEmpty()

    private fun yamlBoolean(value: Any?): Boolean? =
        when (value) {
            null -> {
                null
            }

            is Boolean -> {
                value
            }

            else -> {
                when (value.toString().trim().lowercase()) {
                    "true", "\${{ true }}" -> true
                    "false", "\${{ false }}" -> false
                    else -> null
                }
            }
        }

    private fun parse(text: String): Map<*, *>? = runCatching { Yaml().load<Any?>(text) as? Map<*, *> }.getOrNull()

    private data class Selection(
        val workflow: String,
        val job: Map<*, *>,
        val step: Map<*, *>,
    )
}
