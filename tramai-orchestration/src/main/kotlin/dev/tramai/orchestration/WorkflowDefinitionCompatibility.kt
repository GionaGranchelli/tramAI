package dev.tramai.orchestration

import java.security.MessageDigest

internal const val WORKFLOW_DEFINITION_VERSION_METADATA_KEY: String =
    "tramai.workflow.definition.version"
private const val WORKFLOW_DEFINITION_DIGEST_METADATA_KEY: String =
    "tramai.workflow.definition.digest"
private const val WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY: String =
    "tramai.workflow.definition.digest.algorithm"
private const val WORKFLOW_DEFINITION_DIGEST_ALGORITHM: String = "SHA-256"

internal data class WorkflowDefinitionCompatibility(
    val version: String,
    val digest: String,
    val digestAlgorithm: String,
)

internal fun <S> workflowDefinitionCompatibility(
    workflowName: String,
    definitionVersion: String,
    schedule: WorkflowScheduleDefinition?,
    stopPolicy: StopPolicy,
    steps: List<InternalWorkflowStep<S>>,
): WorkflowDefinitionCompatibility {
    val canonical = buildString {
        append("workflow:")
        append(workflowName)
        append('\n')
        append("stop_policy.max_step_executions:")
        append(stopPolicy.maxStepExecutions)
        append('\n')
        append("stop_policy.max_parallel_branches:")
        append(stopPolicy.maxParallelBranches)
        append('\n')
        append("schedule:")
        append(schedule?.canonicalForm() ?: "none")
        append('\n')
        append(renderStepsCanonical(steps))
    }
    return WorkflowDefinitionCompatibility(
        version = definitionVersion,
        digest = sha256Hex(canonical),
        digestAlgorithm = WORKFLOW_DEFINITION_DIGEST_ALGORITHM,
    )
}

private fun <S> renderStepsCanonical(
    steps: List<InternalWorkflowStep<S>>,
): String = buildString {
    for (step in steps) {
        when (step) {
            is LocalWorkflowStep -> renderLocalStepCanonical(this, step)
            is AiWorkflowStep<*, *, *> -> renderAiStepCanonical(this, step)
            is HttpWorkflowStep<*> -> renderHttpStepCanonical(this, step)
            is ShellWorkflowStep<*> -> renderShellStepCanonical(this, step)
            is HermesWorkflowStep<*> -> renderHermesStepCanonical(this, step)
            is CodexWorkflowStep<*> -> renderCodexStepCanonical(this, step)
            is McpWorkflowStep<*> -> renderMcpStepCanonical(this, step)
            is PluginWorkflowStep<*> -> renderPluginStepCanonical(this, step)
            is GateWorkflowStep -> renderGateStepCanonical(this, step)
            is DelayWorkflowStep -> renderDelayStepCanonical(this, step)
            is ParallelWorkflowStep<*, *, *> -> renderParallelStepCanonical(this, step)
            is BranchWorkflowStep -> renderBranchStepCanonical(this, step)
        }
    }
}

private fun <S> renderLocalStepCanonical(
    sb: StringBuilder,
    step: LocalWorkflowStep<S>,
) {
    sb.append("local:")
    sb.append(step.name)
    sb.append('\n')
}

private fun <S> renderAiStepCanonical(
    sb: StringBuilder,
    step: AiWorkflowStep<S, *, *>,
) {
    sb.append("ai:")
    sb.append(step.name)
    sb.append('\n')
}

private fun <S> renderHttpStepCanonical(
    sb: StringBuilder,
    step: HttpWorkflowStep<S>,
) {
    sb.append("http:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.config.timeoutSeconds)
    sb.append(':')
    sb.append(step.config.maxResponseBytes)
    sb.append(':')
    sb.append(step.config.maxRetries)
    sb.append(':')
    sb.append(step.config.retryOnStatus.sorted().joinToString(","))
    sb.append('\n')
}

private fun <S> renderShellStepCanonical(
    sb: StringBuilder,
    step: ShellWorkflowStep<S>,
) {
    sb.append("shell:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.config.timeoutSeconds)
    sb.append(':')
    sb.append(step.config.maxOutputBytes)
    sb.append(':')
    sb.append(step.config.failOnNonZeroExit)
    sb.append(':')
    sb.append(step.config.failOnStderr)
    sb.append(':')
    sb.append(step.config.allowedCommands.sorted().joinToString(","))
    sb.append(':')
    sb.append(step.config.deniedCommands.sorted().joinToString(","))
    sb.append(':')
    sb.append(step.definition.executable)
    sb.append(':')
    sb.append(step.definition.hasWorkdir)
    sb.append(':')
    sb.append(step.definition.envKeys.sorted().joinToString(","))
    sb.append('\n')
}

private fun <S> renderHermesStepCanonical(
    sb: StringBuilder,
    step: HermesWorkflowStep<S>,
) {
    sb.append("hermes:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.config.timeoutSeconds)
    sb.append(':')
    sb.append(step.config.maxOutputBytes)
    sb.append(':')
    sb.append(step.config.cliPath)
    sb.append(':')
    sb.append(step.config.model)
    sb.append('\n')
}

private fun <S> renderCodexStepCanonical(
    sb: StringBuilder,
    step: CodexWorkflowStep<S>,
) {
    sb.append("codex:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.config.timeoutSeconds)
    sb.append(':')
    sb.append(step.config.maxOutputBytes)
    sb.append(':')
    sb.append(step.config.cliPath)
    sb.append(':')
    sb.append(step.config.workdir ?: "*")
    sb.append('\n')
}

private fun <S> renderMcpStepCanonical(
    sb: StringBuilder,
    step: McpWorkflowStep<S>,
) {
    sb.append("mcp:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.config.timeoutSeconds)
    sb.append(':')
    sb.append(step.config.maxOutputBytes)
    sb.append(':')
    sb.append(step.config.reconnect)
    sb.append(':')
    sb.append(step.config.toolAllowlist?.sorted()?.joinToString(",") ?: "*")
    sb.append(':')
    sb.append(
        if (step.config.enforceCommandAllowlist) {
            step.config.allowedCommands.sorted().joinToString(",")
        } else {
            "*"
        },
    )
    sb.append(':')
    sb.append(step.config.deniedCommands.sorted().joinToString(","))
    sb.append(':')
    sb.append(step.definition.serverCommand.joinToString(","))
    sb.append(':')
    sb.append(step.definition.serverEnv.map { (k, v) -> "$k=$v" }.sorted().joinToString(","))
    sb.append(':')
    sb.append(step.definition.toolName)
    sb.append(':')
    sb.append(step.definition.argumentKeys.sorted().joinToString(","))
    sb.append('\n')
}

private fun <S> renderPluginStepCanonical(
    sb: StringBuilder,
    step: PluginWorkflowStep<S>,
) {
    sb.append("plugin:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.type)
    sb.append(':')
    sb.append(renderPluginValueCanonical(step.config))
    sb.append('\n')
}

private fun <S> renderGateStepCanonical(
    sb: StringBuilder,
    step: GateWorkflowStep<S>,
) {
    sb.append("gate:")
    sb.append(step.name)
    sb.append('\n')
}

private fun <S> renderDelayStepCanonical(
    sb: StringBuilder,
    step: DelayWorkflowStep<S>,
) {
    sb.append("delay:")
    sb.append(step.name)
    sb.append(':')
    sb.append(step.duration)
    sb.append(':')
    sb.append(step.unit.name)
    sb.append('\n')
}

private fun <S> renderParallelStepCanonical(
    sb: StringBuilder,
    step: ParallelWorkflowStep<S, *, *>,
) {
    sb.append("parallel:")
    sb.append(step.name)
    sb.append('\n')
}

private fun <S> renderBranchStepCanonical(
    sb: StringBuilder,
    step: BranchWorkflowStep<S>,
) {
    sb.append("branch:")
    sb.append(step.name)
    sb.append('\n')
    for ((key, branchSteps) in step.branches) {
        sb.append("branch-key:")
        sb.append(key)
        sb.append('\n')
        sb.append(renderStepsCanonical(branchSteps))
    }
    val defaultSteps = step.defaultSteps
    if (defaultSteps != null) {
        sb.append("branch-default:")
        sb.append(step.name)
        sb.append('\n')
        sb.append(renderStepsCanonical(defaultSteps))
    }
}

private fun renderPluginValueCanonical(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries
        .sortedBy { it.key?.toString() ?: "" }
        .joinToString(prefix = "{", postfix = "}") { entry ->
            "${renderPluginValueCanonical(entry.key?.toString())}:${renderPluginValueCanonical(entry.value)}"
        }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { element ->
        renderPluginValueCanonical(element)
    }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { element ->
        renderPluginValueCanonical(element)
    }
    else -> renderPluginValueCanonical(value.toString())
}

private fun sha256Hex(value: String): String = MessageDigest
    .getInstance(WORKFLOW_DEFINITION_DIGEST_ALGORITHM)
    .digest(value.toByteArray())
    .joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

internal fun WorkflowDefinitionCompatibility.toCheckpointMetadata(): Map<String, String> = mapOf(
    WORKFLOW_DEFINITION_VERSION_METADATA_KEY to version,
    WORKFLOW_DEFINITION_DIGEST_METADATA_KEY to digest,
    WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY to digestAlgorithm,
)

internal fun WorkflowCheckpoint.requireWorkflowDefinitionCompatibility(
    workflowName: String,
    workflowId: String,
): WorkflowDefinitionCompatibility {
    val version = metadata[WORKFLOW_DEFINITION_VERSION_METADATA_KEY]
        ?: throw missingDefinitionMetadataException(
            workflowName = workflowName,
            workflowId = workflowId,
            missingKey = WORKFLOW_DEFINITION_VERSION_METADATA_KEY,
        )
    val digest = metadata[WORKFLOW_DEFINITION_DIGEST_METADATA_KEY]
        ?: throw missingDefinitionMetadataException(
            workflowName = workflowName,
            workflowId = workflowId,
            missingKey = WORKFLOW_DEFINITION_DIGEST_METADATA_KEY,
        )
    val digestAlgorithm = metadata[WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY]
        ?: throw missingDefinitionMetadataException(
            workflowName = workflowName,
            workflowId = workflowId,
            missingKey = WORKFLOW_DEFINITION_DIGEST_ALGORITHM_METADATA_KEY,
        )
    return WorkflowDefinitionCompatibility(
        version = version,
        digest = digest,
        digestAlgorithm = digestAlgorithm,
    )
}

internal fun requireCompatibleDefinition(
    workflowName: String,
    workflowId: String,
    persisted: WorkflowDefinitionCompatibility,
    current: WorkflowDefinitionCompatibility,
) {
    if (persisted.version != current.version) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' was created with definitionVersion='${persisted.version}', but the current workflow uses definitionVersion='${current.version}'",
        )
    }
    if (persisted.digestAlgorithm != current.digestAlgorithm) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' uses definition digest algorithm '${persisted.digestAlgorithm}', but the current workflow uses '${current.digestAlgorithm}'",
        )
    }
    if (persisted.digest != current.digest) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' was created from a different workflow definition digest. persisted='${persisted.digest}', current='${current.digest}'",
        )
    }
}

internal fun missingDefinitionMetadataException(
    workflowName: String,
    workflowId: String,
    missingKey: String,
): WorkflowResumeException = WorkflowResumeException(
    "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is missing required workflow definition metadata '$missingKey'. Checkpoints created before the stable resume-compatibility contract cannot be resumed.",
)
