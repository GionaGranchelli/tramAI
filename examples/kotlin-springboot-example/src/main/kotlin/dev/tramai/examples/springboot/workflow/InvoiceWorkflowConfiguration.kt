package dev.tramai.examples.springboot.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.orchestration.FileWorkflowLeaseStore
import dev.tramai.orchestration.MarkdownWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowLeasePolicy
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * Spring wiring for persisted workflow state.
 */
@Configuration
class InvoiceWorkflowConfiguration {
    @Bean
    fun invoiceWorkflowPersistence(
        objectMapper: ObjectMapper,
        @Value("\${tramai.example.workflow.persistence-root:build/tramai-example/workflows}") persistenceRoot: String,
        @Value("\${tramai.example.workflow.lease-owner-id:example-node-1}") leaseOwnerId: String,
    ): WorkflowPersistence<InvoiceWorkflowState> {
        val rootPath = Path.of(persistenceRoot)
        return WorkflowPersistence(
            checkpointStore = MarkdownWorkflowCheckpointStore(rootPath),
            stateCodec = InvoiceWorkflowStateCodec(objectMapper),
            leaseStore = FileWorkflowLeaseStore(rootPath),
            leasePolicy = WorkflowLeasePolicy(ownerId = leaseOwnerId),
            deleteCheckpointOnCompletion = false,
        )
    }
}

class InvoiceWorkflowStateCodec(
    private val objectMapper: ObjectMapper,
) : WorkflowStateCodec<InvoiceWorkflowState> {
    override fun encode(state: InvoiceWorkflowState): String = objectMapper.writeValueAsString(state)

    override fun decode(payload: String): InvoiceWorkflowState = objectMapper.readValue(
        payload,
        InvoiceWorkflowState::class.java,
    )
}
