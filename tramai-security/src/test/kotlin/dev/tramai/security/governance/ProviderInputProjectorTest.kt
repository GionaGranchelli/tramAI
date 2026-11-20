package dev.tramai.security.governance

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolDefinition
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

class ProviderInputProjectorTest {
    private val euDeployment =
        ProviderDeployment(
            deploymentId = "eu-prod",
            providerId = "openai",
            trustZone = NamedTrustZone(TrustZoneName("eu-zone"), ProviderTrustZone.EU_CLOUD),
        )
    private val otherDeployment =
        ProviderDeployment(
            deploymentId = "global-prod",
            providerId = "openai",
            trustZone = NamedTrustZone(TrustZoneName("global-zone"), ProviderTrustZone.GLOBAL_CLOUD),
        )

    private val piiMinimizer =
        ObligationMinimizer { text ->
            val replaced = text.replace("alice@example.com", "[REDACTED_EMAIL]")
            val count = if (replaced != text) 1 else 0
            MinimizationOutcome(replaced, count, inspectionCompleted = true)
        }

    private val secretMinimizer =
        ObligationMinimizer { text ->
            val replaced = text.replace("sk-secret-12345", "[REDACTED_SECRET]")
            val count = if (replaced != text) 1 else 0
            MinimizationOutcome(replaced, count, inspectionCompleted = true)
        }

    @Test
    fun `deny never produces provider-bound input`() {
        val projector = ProviderInputProjector()
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("hello")))
        val decision =
            ProviderInputReleaseDecision.Deny(
                euDeployment.deploymentId,
                ReleaseRefusal.ZONE_PAIR_NOT_ALLOWED,
            )

        val result = projector.project(request, euDeployment, decision)
        assertEquals(
            ProviderInputProjectionResult.Refused(
                euDeployment.deploymentId,
                ProjectionRefusal.RELEASE_DENIED,
            ),
            result,
        )
    }

    @Test
    fun `allow raw produces isolated representation without minimization`() {
        val projector = ProviderInputProjector()
        val messages = mutableListOf(Message.text("alice@example.com"))
        val request = ModelRequest(model = "gpt-4o", messages = messages)
        val decision = ProviderInputReleaseDecision.AllowRaw(euDeployment.deploymentId)

        val result = projector.project(request, euDeployment, decision) as ProviderInputProjectionResult.Projected
        assertEquals(euDeployment.deploymentId, result.deploymentId)
        assertEquals(
            "alice@example.com",
            result.providerBoundRequest.messages
                .single()
                .content,
        )
        assertEquals(ProjectionMetadata(emptySet(), 0), result.metadata)
        assertNotSame(messages, result.providerBoundRequest.messages)

        messages.clear()
        assertEquals(1, result.providerBoundRequest.messages.size)
        assertThrows<UnsupportedOperationException> {
            (result.providerBoundRequest.messages as MutableList<Message>).clear()
        }
    }

    @Test
    fun `configured redaction removes sensitive canaries across messages, tool calls, and schemas`() {
        val projector =
            ProviderInputProjector(
                mapOf(
                    MinimizationObligation.REDACT_PII to piiMinimizer,
                    MinimizationObligation.REDACT_SECRETS to secretMinimizer,
                ),
            )
        val request =
            ModelRequest(
                model = "gpt-4o",
                messages =
                    listOf(
                        Message.text("Contact alice@example.com"),
                        Message(
                            role = MessageRole.USER,
                            content = "",
                            contentParts = listOf(ContentPart.TextPart("Key sk-secret-12345")),
                        ),
                        Message(
                            role = MessageRole.ASSISTANT,
                            content = "",
                            toolCalls = listOf(ToolCall("call-1", "lookup", """{"email":"alice@example.com"}""")),
                        ),
                    ),
                tools =
                    listOf(
                        ToolDefinition(
                            name = "lookup",
                            description = "Lookup using sk-secret-12345",
                            inputSchemaJson = """{"default":"alice@example.com"}""",
                        ),
                    ),
            )
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_PII, MinimizationObligation.REDACT_SECRETS),
            )

        val result = projector.project(request, euDeployment, decision) as ProviderInputProjectionResult.Projected
        val projected = result.providerBoundRequest
        assertEquals("Contact [REDACTED_EMAIL]", projected.messages[0].content)
        assertEquals("Key [REDACTED_SECRET]", (projected.messages[1].contentParts!![0] as ContentPart.TextPart).text)
        assertEquals("""{"email":"[REDACTED_EMAIL]"}""", projected.messages[2].toolCalls!![0].argumentsJson)
        assertEquals("Lookup using [REDACTED_SECRET]", projected.tools!![0].description)
        assertEquals("""{"default":"[REDACTED_EMAIL]"}""", projected.tools!![0].inputSchemaJson)
        assertFalse(projected.toString().contains("alice@example.com"))
        assertFalse(projected.toString().contains("sk-secret-12345"))
        assertEquals(5, result.metadata.redactionCount)

        // Canonical input remains unchanged
        assertEquals("Contact alice@example.com", request.messages[0].content)
        assertTrue(request.tools!![0].description.contains("sk-secret-12345"))
    }

    @Test
    fun `missing minimizer or unsupported obligation refuses projection without downgrade`() {
        val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_PII to piiMinimizer))
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("alice@example.com")))
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.MINIMIZE_TO_POLICY),
            )

        val result = projector.project(request, euDeployment, decision)
        assertEquals(
            ProviderInputProjectionResult.Refused(
                euDeployment.deploymentId,
                ProjectionRefusal.MISSING_OBLIGATION_HANDLER,
            ),
            result,
        )
        assertEquals("alice@example.com", request.messages.single().content)
    }

    @Test
    fun `inspection exception fails closed without exposing sensitive values`() {
        val failingMinimizer = ObligationMinimizer { throw IllegalStateException("boom: alice@example.com") }
        val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_PII to failingMinimizer))
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("alice@example.com")))
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_PII),
            )

        val result = projector.project(request, euDeployment, decision)
        assertEquals(
            ProviderInputProjectionResult.Refused(euDeployment.deploymentId, ProjectionRefusal.TRANSFORMATION_FAILED),
            result,
        )
        assertFalse(result.toString().contains("alice@example.com"))
    }

    @Test
    fun `cross-deployment decision reuse is rejected`() {
        val projector = ProviderInputProjector()
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("hello")))
        val decision = ProviderInputReleaseDecision.AllowRaw(euDeployment.deploymentId)

        val result = projector.project(request, otherDeployment, decision)
        assertEquals(
            ProviderInputProjectionResult.Refused(otherDeployment.deploymentId, ProjectionRefusal.DEPLOYMENT_MISMATCH),
            result,
        )
    }

    @Test
    fun `mixed text and image content fails closed under mandatory minimization`() {
        val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_PII to piiMinimizer))
        val imageRequest =
            ModelRequest(
                model = "gpt-4o",
                messages =
                    listOf(
                        Message(
                            role = MessageRole.USER,
                            content = "",
                            contentParts =
                                listOf(
                                    ContentPart.TextPart("alice@example.com"),
                                    ContentPart.ImagePart("image/png", byteArrayOf(1, 2, 3)),
                                ),
                        ),
                    ),
            )
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_PII),
            )

        val result = projector.project(imageRequest, euDeployment, decision)
        assertEquals(
            ProviderInputProjectionResult.Refused(
                euDeployment.deploymentId,
                ProjectionRefusal.UNSUPPORTED_CONTENT_PART,
            ),
            result,
        )
    }

    @Test
    fun `cancellation exception propagates as exact instance`() {
        val cancelled = CancellationException("cancelled-by-caller")
        val projector =
            ProviderInputProjector(
                mapOf(MinimizationObligation.REDACT_PII to ObligationMinimizer { throw cancelled }),
            )
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("hello")))
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_PII),
            )

        val thrown = assertThrows<CancellationException> { projector.project(request, euDeployment, decision) }
        assertSame(cancelled, thrown)
    }

    @Test
    fun `incomplete inspection fails closed`() {
        val unverifiedMinimizer =
            ObligationMinimizer { text ->
                MinimizationOutcome(text, redactionCount = 0, inspectionCompleted = false)
            }
        val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_SECRETS to unverifiedMinimizer))
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("sk-secret-12345")))
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_SECRETS),
            )

        val result = projector.project(request, euDeployment, decision)
        assertEquals(
            ProviderInputProjectionResult.Refused(euDeployment.deploymentId, ProjectionRefusal.TRANSFORMATION_FAILED),
            result,
        )
    }

    @Test
    fun `empty, scalar, trailing-token, dropped-key, blank-to-nonblank, or malformed json fails closed`() {
        val badOutputs = listOf("{not-valid-json", "", "   ", "123", "{} extra", "{}")
        for (bad in badOutputs) {
            val corruptingMinimizer =
                ObligationMinimizer { _ ->
                    MinimizationOutcome(bad, redactionCount = 1, inspectionCompleted = true)
                }
            val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_PII to corruptingMinimizer))
            val request =
                ModelRequest(
                    model = "gpt-4o",
                    messages =
                        listOf(
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                toolCalls = listOf(ToolCall("call-1", "lookup", """{"email":"alice@example.com"}""")),
                            ),
                        ),
                )
            val decision =
                ProviderInputReleaseDecision.AllowWithMinimization(
                    euDeployment.deploymentId,
                    setOf(MinimizationObligation.REDACT_PII),
                )

            val result = projector.project(request, euDeployment, decision)
            assertEquals(
                ProviderInputProjectionResult.Refused(
                    euDeployment.deploymentId,
                    ProjectionRefusal.TRANSFORMATION_FAILED,
                ),
                result,
            )
        }
    }

    @Test
    fun `diagnostic toString is content-free and negative or overflowing counts are rejected`() {
        val outcome = MinimizationOutcome("secret-payload", redactionCount = 1, inspectionCompleted = true)
        assertFalse(outcome.toString().contains("secret-payload"))

        val projector = ProviderInputProjector()
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("secret-payload")))
        val projected =
            projector.project(
                request,
                euDeployment,
                ProviderInputReleaseDecision.AllowRaw(euDeployment.deploymentId),
            ) as ProviderInputProjectionResult.Projected
        assertFalse(projected.toString().contains("secret-payload"))

        assertThrows<IllegalArgumentException> {
            MinimizationOutcome("x", redactionCount = -1, inspectionCompleted = true)
        }
        assertThrows<IllegalArgumentException> { ProjectionMetadata(emptySet(), redactionCount = -1) }

        val overflowingMinimizer =
            ObligationMinimizer { text ->
                MinimizationOutcome(text, redactionCount = Int.MAX_VALUE, inspectionCompleted = true)
            }
        val overflowProjector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_PII to overflowingMinimizer))
        val overflowRequest =
            ModelRequest(
                model = "gpt-4o",
                messages = listOf(Message.text("a"), Message.text("b"), Message.text("c")),
            )
        val overflowResult =
            overflowProjector.project(
                overflowRequest,
                euDeployment,
                ProviderInputReleaseDecision.AllowWithMinimization(
                    euDeployment.deploymentId,
                    setOf(MinimizationObligation.REDACT_PII),
                ),
            )
        assertEquals(
            ProviderInputProjectionResult.Refused(euDeployment.deploymentId, ProjectionRefusal.TRANSFORMATION_FAILED),
            overflowResult,
        )
    }

    @Test
    fun `sensitive canaries in tool or message identifiers fail closed`() {
        val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_SECRETS to secretMinimizer))
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_SECRETS),
            )
        val requests =
            listOf(
                ModelRequest(
                    model = "gpt-4o",
                    messages = listOf(Message.text("hello")),
                    tools = listOf(ToolDefinition("sk-secret-12345", "ok", "{}")),
                ),
                ModelRequest(
                    model = "gpt-4o",
                    messages =
                        listOf(
                            Message(
                                MessageRole.ASSISTANT,
                                "",
                                toolCalls = listOf(ToolCall("sk-secret-12345", "lookup", "{}")),
                            ),
                        ),
                ),
                ModelRequest(
                    model = "gpt-4o",
                    messages =
                        listOf(
                            Message(
                                MessageRole.ASSISTANT,
                                "",
                                toolCalls = listOf(ToolCall("call-1", "sk-secret-12345", "{}")),
                            ),
                        ),
                ),
                ModelRequest(
                    model = "gpt-4o",
                    messages = listOf(Message(MessageRole.TOOL, "ok", toolCallId = "sk-secret-12345")),
                ),
            )

        for (req in requests) {
            assertEquals(
                ProviderInputProjectionResult.Refused(
                    euDeployment.deploymentId,
                    ProjectionRefusal.TRANSFORMATION_FAILED,
                ),
                projector.project(req, euDeployment, decision),
            )
        }
    }

    @Test
    fun `nested json schema key removal fails closed`() {
        val strippingMinimizer =
            ObligationMinimizer { _ ->
                MinimizationOutcome(
                    """{"type":"object","properties":{}}""",
                    redactionCount = 1,
                    inspectionCompleted = true,
                )
            }
        val projector = ProviderInputProjector(mapOf(MinimizationObligation.REDACT_PII to strippingMinimizer))
        val request =
            ModelRequest(
                model = "gpt-4o",
                messages = listOf(Message.text("hello")),
                tools =
                    listOf(
                        ToolDefinition(
                            name = "lookup",
                            description = "ok",
                            inputSchemaJson = """{"type":"object","properties":{"email":{"type":"string"}}}""",
                        ),
                    ),
            )
        val decision =
            ProviderInputReleaseDecision.AllowWithMinimization(
                euDeployment.deploymentId,
                setOf(MinimizationObligation.REDACT_PII),
            )

        assertEquals(
            ProviderInputProjectionResult.Refused(euDeployment.deploymentId, ProjectionRefusal.TRANSFORMATION_FAILED),
            projector.project(request, euDeployment, decision),
        )
    }
}
