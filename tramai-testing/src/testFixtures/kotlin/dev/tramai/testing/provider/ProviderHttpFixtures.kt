package dev.tramai.testing.provider

import dev.tramai.core.model.ToolCall

/**
 * Canonical, protocol-shaped response bodies for the provider TCK.
 *
 * One family per wire format: OpenAI `chat/completions` (also used by Azure
 * and DeepSeek), Anthropic `messages`, Ollama `generate`, and Gemini
 * `generateContent`. Bedrock is SDK-shaped and drives its runner through the
 * client-factory seam instead of HTTP fixtures.
 *
 * Every payload is built with plain string concatenation — no escape
 * sequences, plain `"` quotes only. Each fixture is JSON-validated by
 * `FixtureValidationTest` in tramai-testing.
 */
object ProviderHttpFixtures {

    // ── OpenAI-compatible (OpenAI, Azure OpenAI, DeepSeek) ──────────────

    object OpenAi {
        fun happy(text: String = "Hello!"): String = buildString {
            append("""{"id":"chatcmpl-1","object":"chat.completion","model":"gpt-tck","choices":[""")
            append("""{"index":0,"message":{"role":"assistant","content":""")
            append(jsonString(text))
            append("""},"finish_reason":"stop"}]""")
            append(""","usage":{"prompt_tokens":10,"completion_tokens":5}}""")
        }

        fun withUsage(input: Int = 100, output: Int = 42, thinking: Int? = 7): String = buildString {
            append("""{"id":"chatcmpl-u","object":"chat.completion","model":"gpt-tck","choices":[""")
            append("""{"index":0,"message":{"role":"assistant","content":"Usage check"},"finish_reason":"stop"}]""")
            append(""","usage":{"prompt_tokens":""").append(input)
            append(""","completion_tokens":""").append(output)
            if (thinking != null) {
                append(""","completion_tokens_details":{"reasoning_tokens":""").append(thinking).append("""}""")
            }
            append("""}}""")
        }

        fun toolCall(call: ToolCall, text: String? = null): String = buildString {
            append("""{"id":"chatcmpl-t","object":"chat.completion","model":"gpt-tck","choices":[""")
            append("""{"index":0,"message":{"role":"assistant",""")
            if (text != null) {
                append(""""content":""").append(jsonString(text)).append(""",""")
            }
            append(""""tool_calls":[{"id":""").append(jsonString(call.id))
            append(""","type":"function","function":{"name":""").append(jsonString(call.name))
            append(""","arguments":""").append(jsonString(call.argumentsJson))
            append("""}}]},"finish_reason":"tool_calls"}]""")
            append(""","usage":null}""")
        }

        fun emptyChoices(): String =
            """{"id":"chatcmpl-e","object":"chat.completion","model":"gpt-tck","choices":[],"usage":null}"""

        fun malformed(): String =
            """{"id":"chatcmpl-x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"trunc"""

        fun stream(tokens: List<String>): String {
            val events = tokens.joinToString("\n\n") { token ->
                buildString {
                    append("""data: {"id":"c","object":"chat.completion.chunk","choices":[""")
                    append("""{"index":0,"delta":{"content":""")
                    append(jsonString(token))
                    append("""},"finish_reason":null}]}""")
                }
            }
            return events + "\n\ndata: [DONE]\n\n"
        }

        fun streamMalformed(): String =
            """data: {"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hel"""

        fun streamFailure(): String =
            """data: {"id":"c","object":"chat.completion.chunk","choices":""" +
                """[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
    }

    // ── Anthropic messages API ─────────────────────────────────────────

    object Anthropic {
        fun happy(text: String = "Hello!"): String = buildString {
            append("""{"id":"msg_01","type":"message","role":"assistant","model":"claude-tck","""")
            append("""content":[{"type":"text","text":""")
            append(jsonString(text))
            append("""}],"stop_reason":"end_turn",""")
            append(""""usage":{"input_tokens":10,"output_tokens":5}}""")
        }

        fun withUsage(input: Int = 100, output: Int = 42, thinking: Int? = null): String = buildString {
            append("""{"id":"msg_u","type":"message","role":"assistant","model":"claude-tck","""")
            append("""content":[{"type":"text","text":"Usage check"}],"stop_reason":"end_turn",""")
            append(""""usage":{"input_tokens":""").append(input)
            append(""","output_tokens":""").append(output)
            if (thinking != null) {
                append(""","thinking_tokens":""").append(thinking)
            }
            append("""}}""")
        }

        fun toolCall(call: ToolCall, text: String? = null): String = buildString {
            append("""{"id":"msg_t","type":"message","role":"assistant","model":"claude-tck","""")
            append("""content":[""")
            if (text != null) {
                append("""{"type":"text","text":""").append(jsonString(text)).append("""},""")
            }
            append("""{"type":"tool_use","id":""").append(jsonString(call.id))
            append(""","name":""").append(jsonString(call.name))
            append(""","input":""").append(call.argumentsJson) // nested JSON object — valid as-is
            append("""}],"stop_reason":"tool_use",""")
            append(""""usage":{"input_tokens":10,"output_tokens":5}}""")
        }

        fun emptyContent(): String =
            """{"id":"msg_e","type":"message","role":"assistant","model":"claude-tck","""" +
                """content":[],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""

        fun malformed(): String =
            """{"id":"msg_x","type":"message","role":"assistant","model":"claude-tck","""" +
                """content":[{"type":"text","text":"trunc"""

        fun stream(tokens: List<String>): String {
            val sb = StringBuilder()
            sb.append("""event: message_start""" + "\n" + """data: {"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant","model":"claude-tck"}}""").append("\n\n")
            tokens.forEach { token ->
                sb.append("""event: content_block_delta""" + "\n" + """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""")
                    .append(jsonString(token))
                    .append("""}}""")
                    .append("\n\n")
            }
            sb.append("""event: message_stop""" + "\n" + """data: {"type":"message_stop"}""").append("\n\n")
            return sb.toString()
        }

        fun streamMalformed(): String =
            """event: content_block_delta""" + "\n" + """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"""

        fun streamFailure(): String =
            """event: content_block_delta""" + "\n" + """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}"""
    }

    // ── Ollama generate API ────────────────────────────────────────────

    object Ollama {
        fun happy(text: String = "Hello!"): String = buildString {
            append("""{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":""")
            append(jsonString(text))
            append(""","done":true}""")
        }

        fun withUsage(input: Int = 100, output: Int = 42): String = buildString {
            append("""{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":"Usage check",""")
            append(""""done":true,"prompt_eval_count":""").append(input)
            append(""","eval_count":""").append(output).append("""}""")
        }

        fun emptyResponse(): String =
            """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":"","done":true}"""

        fun malformed(): String =
            """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":"trunc"""

        fun stream(tokens: List<String>): String =
            tokens.joinToString("\n") { token ->
                buildString {
                    append("""{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":""")
                    append(jsonString(token))
                    append(""","done":false}""")
                }
            } + "\n" + """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":"","done":true}""" + "\n"

        fun streamMalformed(): String =
            """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":"Hel"""

        fun streamFailure(): String =
            """{"model":"ollama-tck","created_at":"2026-01-01T00:00:00Z","response":"Hel","done":false}"""
    }

    // ── Gemini generateContent API ─────────────────────────────────────

    object Gemini {
        fun happy(text: String = "Hello!"): String = buildString {
            append("""{"candidates":[{"content":{"parts":[{"text":""")
            append(jsonString(text))
            append("""}],"role":"model"},"finishReason":"STOP"}],""")
            append(""""usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}""")
        }

        fun withUsage(input: Int = 100, output: Int = 42): String = buildString {
            append("""{"candidates":[{"content":{"parts":[{"text":"Usage check"}],"role":"model"},"""")
            append("""finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":""").append(input)
            append(""","candidatesTokenCount":""").append(output).append("""}}""")
        }

        fun toolCall(call: ToolCall, text: String? = null): String = buildString {
            append("""{"candidates":[{"content":{"parts":[""")
            if (text != null) {
                append("""{"text":""").append(jsonString(text)).append("""},""")
            }
            append("""{"functionCall":{"name":""").append(jsonString(call.name))
            append(""","args":""").append(call.argumentsJson) // nested JSON object — valid as-is
            append("""}}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{}}""")
        }

        fun emptyCandidates(): String = """{"candidates":[],"usageMetadata":{}}"""

        fun malformed(): String =
            """{"candidates":[{"content":{"parts":[{"text":"trunc"""

        fun stream(tokens: List<String>): String =
            tokens.joinToString("\n") { token ->
                buildString {
                    append("""data: {"candidates":[{"content":{"parts":[{"text":""")
                    append(jsonString(token))
                    append("""}],"role":"model"}}]}""")
                }
            } + "\n" + """data: {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"}}]}""" + "\n"

        fun streamMalformed(): String =
            """data: {"candidates":[{"content":{"parts":[{"text":"Hel"""

        fun streamFailure(): String =
            """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}"""
    }

    // ── shared JSON encoding ───────────────────────────────────────────

    /** Encodes [value] as a JSON string literal (quotes escaped). */
    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
