package dev.tramai.platform

import dev.tramai.orchestration.ExternalStepExecutorResolver
import dev.tramai.server.WorkflowRegistry

class PluginWorkflowStartupValidationException(
    message: String,
) : IllegalStateException(message)

class PluginWorkflowStartupValidator(
    private val workflowRegistry: WorkflowRegistry,
    private val executorResolver: ExternalStepExecutorResolver,
) {
    fun validate() {
        val invalidWorkflows = workflowRegistry.list().mapNotNull { entry ->
            val missingTypes = entry.workflow.requiredExternalStepTypes()
                .filterNot(executorResolver::isRegistered)
                .sorted()
            if (missingTypes.isEmpty()) {
                null
            } else {
                entry.workflow.name to missingTypes
            }
        }
        if (invalidWorkflows.isEmpty()) {
            return
        }
        val details = invalidWorkflows.joinToString(separator = "; ") { (workflowName, missingTypes) ->
            "workflow '$workflowName' is missing executors ${missingTypes.joinToString(prefix = "[", postfix = "]")}"
        }
        throw PluginWorkflowStartupValidationException(
            "Plugin executor startup validation failed: $details",
        )
    }
}
