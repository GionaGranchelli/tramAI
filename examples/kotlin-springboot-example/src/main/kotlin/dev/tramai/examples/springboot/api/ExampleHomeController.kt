package dev.tramai.examples.springboot.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Small inventory endpoint so the running example explains itself.
 */
@RestController
class ExampleHomeController {
    @GetMapping("/")
    fun home(): ExampleHomeResponse = ExampleHomeResponse(
        product = "TramAI",
        application = "kotlin-springboot-example",
        status = "ok",
        capabilities = listOf(
            CapabilityDescriptor(
                capability = "raw-text",
                endpoint = "POST /invoice/summary",
                explanation = "Simple typed interface method returning String.",
            ),
            CapabilityDescriptor(
                capability = "streaming",
                endpoint = "POST /invoice/summary/stream",
                explanation = "Streaming response exposed as text/event-stream.",
            ),
            CapabilityDescriptor(
                capability = "tool-calling",
                endpoint = "POST /invoice/enrich",
                explanation = "Model requests vendor_lookup before producing the final answer.",
            ),
            CapabilityDescriptor(
                capability = "structured-output",
                endpoint = "POST /invoice/triage",
                explanation = "Model returns JSON parsed into a typed response object.",
            ),
            CapabilityDescriptor(
                capability = "orchestration",
                endpoint = "POST /invoice/workflow",
                explanation = "Workflow composes multiple Tramai calls with persisted checkpoints and resume.",
            ),
        ),
        docs = listOf(
            DocumentationLink("README", "examples/kotlin-springboot-example/README.md"),
            DocumentationLink("Manual", "examples/kotlin-springboot-example/MANUAL.md"),
            DocumentationLink("HTTP requests", "examples/kotlin-springboot-example/Request.http"),
        ),
        rawEndpoint = "POST /invoice/summary",
        streamEndpoint = "POST /invoice/summary/stream",
        enrichEndpoint = "POST /invoice/enrich",
        typedEndpoint = "POST /invoice/triage",
        workflowEndpoint = "POST /invoice/workflow",
        workflowStartEndpoint = "POST /invoice/workflow/start",
        workflowResultEndpoint = "GET /invoice/workflow/result/{workflowId}",
        workflowListEndpoint = "GET /invoice/workflow/list",
        workflowCancelEndpoint = "POST /invoice/workflow/cancel/{workflowId}",
        workflowCheckpointEndpoint = "GET /invoice/workflow/checkpoint/{workflowId}",
        workflowResumeEndpoint = "POST /invoice/workflow/resume/{workflowId}",
        workflowEventsEndpoint = "GET /invoice/workflow/events/{workflowId}",
    )
}
