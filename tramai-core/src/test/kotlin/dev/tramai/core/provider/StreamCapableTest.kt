package dev.tramai.core.provider

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class StreamCapableTest {

    @Test
    fun `non suspend stream function can produce a flow`() {
        val streamCapable = StreamCapable {
            flowOf(
                StreamChunk.Token("hi"),
                StreamChunk.Complete("hi"),
            )
        }

        val chunks = runBlocking { streamCapable.stream(request()).toList() }

        assertEquals(
            listOf(
                StreamChunk.Token("hi"),
                StreamChunk.Complete("hi"),
            ),
            chunks,
        )
    }

    @Test
    fun `cancellation propagates to the underlying flow`() {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val streamCapable = StreamCapable {
            flow {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.set(true)
                }
            }
        }

        runBlocking {
            val job = launch {
                streamCapable.stream(request()).toList()
            }
            started.await()
            job.cancel()
            job.join()
        }

        assertTrue(cancelled.get())
    }

    private fun request(): ModelRequest = ModelRequest(
        model = "gpt-5.1-chat-latest",
        messages = listOf(Message(MessageRole.USER, "hello")),
    )
}
