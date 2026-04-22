package dev.tramai.examples.springboot.api

data class InvoiceInput(
    val invoiceText: String,
)

data class InvoiceSummaryResponse(
    val summary: String,
)

data class ExampleHomeResponse(
    val product: String,
    val application: String,
    val status: String,
    val capabilities: List<CapabilityDescriptor>,
    val docs: List<DocumentationLink>,
    val rawEndpoint: String,
    val streamEndpoint: String,
    val enrichEndpoint: String,
    val typedEndpoint: String,
    val workflowEndpoint: String,
    val workflowStartEndpoint: String,
    val workflowResultEndpoint: String,
    val workflowListEndpoint: String,
    val workflowCancelEndpoint: String,
    val workflowCheckpointEndpoint: String,
    val workflowResumeEndpoint: String,
    val workflowEventsEndpoint: String,
)

data class CapabilityDescriptor(
    val capability: String,
    val endpoint: String,
    val explanation: String,
)

data class DocumentationLink(
    val label: String,
    val path: String,
)
