package dev.tramai.testing.persistence.memory

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1h: shared cross-implementation compatibility contract for
 * [dev.tramai.core.memory.ChatMemoryStore].
 *
 * The contract:
 * > A conversation is one ordered logical message history. A successful
 * > appendMessages() adds its complete batch exactly once and contiguously;
 * > reads preserve the complete Message value; deletion removes the
 * > conversation; listing reflects most-recent conversation activity
 * > deterministically; and those answers cannot depend on whether the
 * > backend is JDBC or Redis.
 *
 * Every test drives the runner-provided harness: [ChatMemoryStoreTckHarness]
 * exposes two distinct store objects ([primary] and [peer]) over the same
 * physical backend, so the concurrency cases prove backend-level
 * coordination, not a per-instance mutex.
 */
abstract class ChatMemoryStoreTck {

    /** Fresh isolated backend + deterministic clock per test; runner owns cleanup. */
    protected abstract fun createHarness(clock: MutableMillisClock): ChatMemoryStoreTckHarness

    // ── C1. Read / append / identity ────────────────────────────────

    @Test
    fun `unknown conversation returns an empty list`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            assertThat(harness.primary.getMessages("never-seen")).isEmpty()
            assertThat(harness.peer.getMessages("never-seen")).isEmpty()
        }
    }

    @Test
    fun `single append round trips`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("conv-single", listOf(ChatMemoryStoreFixtures.user("hello")))
            assertThat(harness.primary.getMessages("conv-single")).containsExactly(ChatMemoryStoreFixtures.user("hello"))
        }
    }

    @Test
    fun `multi message append preserves exact order`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val batch = listOf(
                ChatMemoryStoreFixtures.system("sys"),
                ChatMemoryStoreFixtures.user("u1"),
                ChatMemoryStoreFixtures.assistant("a1"),
            )
            harness.primary.appendMessages("conv-order", batch)
            assertThat(harness.primary.getMessages("conv-order")).containsExactlyElementsOf(batch)
        }
    }

    @Test
    fun `second append extends rather than replaces`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("conv-extend", listOf(ChatMemoryStoreFixtures.user("first")))
            harness.peer.appendMessages("conv-extend", listOf(ChatMemoryStoreFixtures.user("second")))
            assertThat(harness.primary.getMessages("conv-extend").map { it.content })
                .containsExactly("first", "second")
        }
    }

    @Test
    fun `repeated append yields exact concatenation`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("conv-concat", listOf(ChatMemoryStoreFixtures.user("a"), ChatMemoryStoreFixtures.user("b")))
            harness.peer.appendMessages("conv-concat", listOf(ChatMemoryStoreFixtures.user("c")))
            harness.primary.appendMessages("conv-concat", listOf(ChatMemoryStoreFixtures.user("d"), ChatMemoryStoreFixtures.user("e")))
            assertThat(harness.primary.getMessages("conv-concat").map { it.content })
                .containsExactly("a", "b", "c", "d", "e")
        }
    }

    @Test
    fun `empty append on a missing conversation does nothing`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("conv-empty-missing", emptyList())
            assertThat(harness.primary.getMessages("conv-empty-missing")).isEmpty()
            assertThat(harness.primary.listConversations(10, 0)).doesNotContain("conv-empty-missing")
        }
    }

    @Test
    fun `empty append on an existing conversation leaves it unchanged`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("conv-empty-existing", listOf(ChatMemoryStoreFixtures.user("only")))
            harness.peer.appendMessages("conv-empty-existing", emptyList())
            assertThat(harness.primary.getMessages("conv-empty-existing").map { it.content }).containsExactly("only")
        }
    }

    @Test
    fun `conversations are isolated`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("conv-iso-a", listOf(ChatMemoryStoreFixtures.user("A")))
            harness.peer.appendMessages("conv-iso-b", listOf(ChatMemoryStoreFixtures.user("B"), ChatMemoryStoreFixtures.user("B2")))
            assertThat(harness.primary.getMessages("conv-iso-a").map { it.content }).containsExactly("A")
            assertThat(harness.primary.getMessages("conv-iso-b").map { it.content }).containsExactly("B", "B2")
        }
    }

    @Test
    fun `nonblank conversation ids with unicode punctuation colon and spaces work`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val ids = listOf(
                "日本語セッション",
                "order:123:detail",
                "conv with spaces",
                "a/b?c=d&e",
                "emoji-😀-session",
            )
            ids.forEachIndexed { index, id ->
                harness.primary.appendMessages(id, listOf(ChatMemoryStoreFixtures.user("m$index")))
            }
            ids.forEachIndexed { index, id ->
                assertThat(harness.primary.getMessages(id).map { it.content }).containsExactly("m$index")
            }
            // All distinct logical conversations, never collapsed onto one key.
            assertThat(harness.primary.listConversations(100, 0)).containsAll(ids)
        }
    }

    @Test
    fun `caller provided message order is authoritative`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val batch = listOf(
                ChatMemoryStoreFixtures.user("z-last-intent"),
                ChatMemoryStoreFixtures.user("a-first-intent"),
                ChatMemoryStoreFixtures.user("m-middle-intent"),
            )
            harness.primary.appendMessages("conv-order-authoritative", batch)
            assertThat(harness.primary.getMessages("conv-order-authoritative").map { it.content })
                .containsExactly("z-last-intent", "a-first-intent", "m-middle-intent")
        }
    }

    // ── C2. Full Message fidelity ───────────────────────────────────

    @Test
    fun `system role round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.system("You are a helpful assistant.")
            harness.primary.appendMessages("fid-system", listOf(message))
            assertThat(harness.primary.getMessages("fid-system")).containsExactly(message)
        }
    }

    @Test
    fun `user role round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.user("What is the capital of France?")
            harness.primary.appendMessages("fid-user", listOf(message))
            assertThat(harness.primary.getMessages("fid-user")).containsExactly(message)
        }
    }

    @Test
    fun `assistant role round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.assistant("The capital is Paris.")
            harness.primary.appendMessages("fid-assistant", listOf(message))
            assertThat(harness.primary.getMessages("fid-assistant")).containsExactly(message)
        }
    }

    @Test
    fun `tool role with toolCallId round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.tool("call_abc123", """{"result": "ok"}""")
            harness.primary.appendMessages("fid-tool", listOf(message))
            assertThat(harness.primary.getMessages("fid-tool")).containsExactly(message)
        }
    }

    @Test
    fun `multiline unicode content round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val content = "line one\nline two\n日本語のテキスト\nemoji: 🚀🎉\n\ttabbed"
            val message = ChatMemoryStoreFixtures.user(content)
            harness.primary.appendMessages("fid-unicode", listOf(message))
            assertThat(harness.primary.getMessages("fid-unicode")).containsExactly(message)
        }
    }

    @Test
    fun `text content part round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.rich(listOf(ContentPart.TextPart("rich text fragment")))
            harness.primary.appendMessages("fid-textpart", listOf(message))
            assertThat(harness.primary.getMessages("fid-textpart")).containsExactly(message)
        }
    }

    @Test
    fun `image content part round trips exact mime type and bytes`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x00, 0x7F, 0x0A)
            val message = ChatMemoryStoreFixtures.rich(
                listOf(ContentPart.ImagePart(mimeType = "image/png", data = bytes)),
            )
            harness.primary.appendMessages("fid-image", listOf(message))
            assertThat(harness.primary.getMessages("fid-image")).containsExactly(message)
        }
    }

    @Test
    fun `url image content round trips exact url and mime type`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.urlImageMessage(
                url = "https://example.test/image.webp",
                mimeType = "image/webp",
            )
            harness.primary.appendMessages("fid-imageurl", listOf(message))
            assertThat(harness.primary.getMessages("fid-imageurl")).containsExactly(message)
        }
    }

    @Test
    fun `url image content without mime type round trips with null mime type`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.urlImageMessage(mimeType = null)
            harness.primary.appendMessages("fid-imageurl-null", listOf(message))
            assertThat(harness.primary.getMessages("fid-imageurl-null")).containsExactly(message)
        }
    }

    @Test
    fun `assistant single tool call with json arguments round trips exactly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.assistantToolCalls(
                listOf(
                    ChatMemoryStoreFixtures.toolCall(
                        id = "call_1",
                        name = "search_web",
                        argumentsJson = """{"query": "tramai", "limit": 5, "flags": [true, null, "x"]}""",
                    ),
                ),
            )
            harness.primary.appendMessages("fid-toolcall-1", listOf(message))
            assertThat(harness.primary.getMessages("fid-toolcall-1")).containsExactly(message)
        }
    }

    @Test
    fun `assistant multiple tool calls round trip exactly in order`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val message = ChatMemoryStoreFixtures.assistantToolCalls(
                listOf(
                    ChatMemoryStoreFixtures.toolCall("call_a", "tool_a", """{"arg": 1}"""),
                    ChatMemoryStoreFixtures.toolCall("call_b", "tool_b", """{"arg": 2}"""),
                    ChatMemoryStoreFixtures.toolCall("call_c", "tool_c", """{"arg": 3}"""),
                ),
            )
            harness.primary.appendMessages("fid-toolcall-n", listOf(message))
            assertThat(harness.primary.getMessages("fid-toolcall-n")).containsExactly(message)
        }
    }

    @Test
    fun `null versus empty contentParts round trip distinctly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val withNull = ChatMemoryStoreFixtures.text("plain text")
            val withEmpty = ChatMemoryStoreFixtures.rich(emptyList())
            harness.primary.appendMessages("fid-parts-null", listOf(withNull))
            harness.peer.appendMessages("fid-parts-empty", listOf(withEmpty))
            assertThat(harness.primary.getMessages("fid-parts-null")).containsExactly(withNull)
            assertThat(harness.primary.getMessages("fid-parts-empty")).containsExactly(withEmpty)
        }
    }

    @Test
    fun `null versus empty toolCalls round trip distinctly`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val withNull = ChatMemoryStoreFixtures.assistant("plain assistant text")
            val withEmpty = ChatMemoryStoreFixtures.assistantToolCalls(emptyList())
            harness.primary.appendMessages("fid-calls-null", listOf(withNull))
            harness.peer.appendMessages("fid-calls-empty", listOf(withEmpty))
            assertThat(harness.primary.getMessages("fid-calls-null")).containsExactly(withNull)
            assertThat(harness.primary.getMessages("fid-calls-empty")).containsExactly(withEmpty)
        }
    }

    @Test
    fun `mixed roles and content kinds round trip as one history`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            val history = listOf(
                ChatMemoryStoreFixtures.system("rules"),
                ChatMemoryStoreFixtures.user("question"),
                ChatMemoryStoreFixtures.assistantToolCalls(
                    listOf(ChatMemoryStoreFixtures.toolCall("c1", "lookup", """{"k": "v"}""")),
                ),
                ChatMemoryStoreFixtures.tool("c1", "the answer"),
                ChatMemoryStoreFixtures.rich(
                    listOf(ContentPart.TextPart("final"), ContentPart.ImageUrlContent("https://x.test/i.png", "image/png")),
                ),
            )
            harness.primary.appendMessages("fid-mixed", history)
            assertThat(harness.primary.getMessages("fid-mixed")).containsExactlyElementsOf(history)
            assertThat(harness.peer.getMessages("fid-mixed")).containsExactlyElementsOf(history)
        }
    }

    // ── C3. Snapshot semantics ──────────────────────────────────────

    @Test
    fun `read returns a snapshot immune to mutation of the returned list`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("snap-mutate", listOf(ChatMemoryStoreFixtures.user("a"), ChatMemoryStoreFixtures.user("b")))
            val snapshot = harness.primary.getMessages("snap-mutate")
            runCatching { (snapshot as MutableList<Message>).clear() } // legal: unmodifiable (throws) or independent copy
            assertThat(harness.primary.getMessages("snap-mutate").map { it.content }).containsExactly("a", "b")
        }
    }

    @Test
    fun `previous snapshot is not mutated by a later append`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("snap-append", listOf(ChatMemoryStoreFixtures.user("A")))
            val snapshot = harness.primary.getMessages("snap-append")
            harness.peer.appendMessages("snap-append", listOf(ChatMemoryStoreFixtures.user("B")))
            assertThat(snapshot.map { it.content }).containsExactly("A")
            assertThat(harness.primary.getMessages("snap-append").map { it.content }).containsExactly("A", "B")
        }
    }

    // ── C4. Delete semantics ────────────────────────────────────────

    @Test
    fun `delete removes an existing conversation`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("del-existing", listOf(ChatMemoryStoreFixtures.user("x")))
            harness.peer.deleteConversation("del-existing")
            assertThat(harness.primary.getMessages("del-existing")).isEmpty()
        }
    }

    @Test
    fun `delete of a missing conversation is an idempotent no-op`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.deleteConversation("del-missing")
            harness.peer.deleteConversation("del-missing")
            assertThat(harness.primary.getMessages("del-missing")).isEmpty()
        }
    }

    @Test
    fun `deleting one conversation does not modify another`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("del-a", listOf(ChatMemoryStoreFixtures.user("A")))
            harness.peer.appendMessages("del-b", listOf(ChatMemoryStoreFixtures.user("B")))
            harness.primary.deleteConversation("del-a")
            assertThat(harness.primary.getMessages("del-a")).isEmpty()
            assertThat(harness.primary.getMessages("del-b").map { it.content }).containsExactly("B")
        }
    }

    @Test
    fun `append after delete starts a fresh history`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("del-recreate", listOf(ChatMemoryStoreFixtures.user("old")))
            harness.peer.deleteConversation("del-recreate")
            harness.primary.appendMessages("del-recreate", listOf(ChatMemoryStoreFixtures.user("new")))
            assertThat(harness.primary.getMessages("del-recreate").map { it.content }).containsExactly("new")
        }
    }

    @Test
    fun `deleted conversation disappears from listConversations`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("del-listed", listOf(ChatMemoryStoreFixtures.user("x")))
            assertThat(harness.primary.listConversations(100, 0)).contains("del-listed")
            harness.peer.deleteConversation("del-listed")
            assertThat(harness.primary.listConversations(100, 0)).doesNotContain("del-listed")
        }
    }

    // ── C5. Input validation ────────────────────────────────────────

    @Test
    fun `blank conversation id on getMessages is a caller error`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            for (blank in listOf("", "   ", "\t")) {
                assertThat(runCatching { harness.primary.getMessages(blank) }.exceptionOrNull())
                    .withFailMessage("getMessages(\"$blank\") must reject blank identity")
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `blank conversation id on appendMessages is a caller error`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            for (blank in listOf("", "   ", "\t")) {
                assertThat(runCatching { harness.primary.appendMessages(blank, listOf(ChatMemoryStoreFixtures.user("x"))) }.exceptionOrNull())
                    .withFailMessage("appendMessages(\"$blank\") must reject blank identity")
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `blank conversation id on deleteConversation is a caller error`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            for (blank in listOf("", "   ", "\t")) {
                assertThat(runCatching { harness.primary.deleteConversation(blank) }.exceptionOrNull())
                    .withFailMessage("deleteConversation(\"$blank\") must reject blank identity")
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `invalid pagination arguments are caller errors`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            assertThat(runCatching { harness.primary.listConversations(0, 0) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(runCatching { harness.primary.listConversations(-1, 0) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(runCatching { harness.primary.listConversations(10, -1) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    // ── C6. listConversations contract ──────────────────────────────

    @Test
    fun `empty store lists no conversations`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            assertThat(harness.primary.listConversations(100, 0)).isEmpty()
        }
    }

    @Test
    fun `single conversation is listed once`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-single", listOf(ChatMemoryStoreFixtures.user("x")))
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-single")
        }
    }

    @Test
    fun `multiple conversations order by most recent activity descending`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-old", listOf(ChatMemoryStoreFixtures.user("a"))) // t0
            clock.advance(1)
            harness.peer.appendMessages("list-new", listOf(ChatMemoryStoreFixtures.user("b"))) // t0+1
            clock.advance(1)
            harness.primary.appendMessages("list-newer", listOf(ChatMemoryStoreFixtures.user("c"))) // t0+2
            assertThat(harness.primary.listConversations(100, 0))
                .containsExactly("list-newer", "list-new", "list-old")
        }
    }

    @Test
    fun `appending to an older conversation moves it to first`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-move-a", listOf(ChatMemoryStoreFixtures.user("a"))) // t0
            clock.advance(1)
            harness.peer.appendMessages("list-move-b", listOf(ChatMemoryStoreFixtures.user("b"))) // t0+1
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-move-b", "list-move-a")
            clock.advance(1)
            harness.primary.appendMessages("list-move-a", listOf(ChatMemoryStoreFixtures.user("a2"))) // t0+2
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-move-a", "list-move-b")
        }
    }

    @Test
    fun `multiple messages in one conversation produce one listing id`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-one-id", listOf(ChatMemoryStoreFixtures.user("1"), ChatMemoryStoreFixtures.user("2")))
            clock.advance(1)
            harness.peer.appendMessages("list-one-id", listOf(ChatMemoryStoreFixtures.user("3")))
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-one-id")
        }
    }

    @Test
    fun `limit caps results`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-limit-1", listOf(ChatMemoryStoreFixtures.user("a")))
            clock.advance(1)
            harness.peer.appendMessages("list-limit-2", listOf(ChatMemoryStoreFixtures.user("b")))
            clock.advance(1)
            harness.primary.appendMessages("list-limit-3", listOf(ChatMemoryStoreFixtures.user("c")))
            assertThat(harness.primary.listConversations(2, 0)).containsExactly("list-limit-3", "list-limit-2")
        }
    }

    @Test
    fun `offset skips exactly n results`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-off-1", listOf(ChatMemoryStoreFixtures.user("a")))
            clock.advance(1)
            harness.peer.appendMessages("list-off-2", listOf(ChatMemoryStoreFixtures.user("b")))
            clock.advance(1)
            harness.primary.appendMessages("list-off-3", listOf(ChatMemoryStoreFixtures.user("c")))
            assertThat(harness.primary.listConversations(100, 1)).containsExactly("list-off-2", "list-off-1")
            assertThat(harness.primary.listConversations(1, 2)).containsExactly("list-off-1")
        }
    }

    @Test
    fun `final partial page is returned`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-page-1", listOf(ChatMemoryStoreFixtures.user("a")))
            clock.advance(1)
            harness.peer.appendMessages("list-page-2", listOf(ChatMemoryStoreFixtures.user("b")))
            clock.advance(1)
            harness.primary.appendMessages("list-page-3", listOf(ChatMemoryStoreFixtures.user("c")))
            clock.advance(1)
            harness.peer.appendMessages("list-page-4", listOf(ChatMemoryStoreFixtures.user("d")))
            assertThat(harness.primary.listConversations(3, 2)).containsExactly("list-page-2", "list-page-1")
        }
    }

    @Test
    fun `offset beyond end returns empty`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-beyond", listOf(ChatMemoryStoreFixtures.user("x")))
            assertThat(harness.primary.listConversations(100, 5)).isEmpty()
        }
    }

    @Test
    fun `delete and recreate gives that conversation new activity`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("list-recreate", listOf(ChatMemoryStoreFixtures.user("first"))) // t0
            clock.advance(1)
            harness.peer.appendMessages("list-other", listOf(ChatMemoryStoreFixtures.user("o"))) // t0+1
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-other", "list-recreate")
            harness.primary.deleteConversation("list-recreate")
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-other")
            clock.advance(1)
            harness.peer.appendMessages("list-recreate", listOf(ChatMemoryStoreFixtures.user("second"))) // t0+2
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("list-recreate", "list-other")
        }
    }

    // ── C7. Empty append is not activity ────────────────────────────

    @Test
    fun `empty append does not count as conversation activity`() {
        val clock = MutableMillisClock()
        createHarness(clock).use { harness ->
            harness.primary.appendMessages("act-a", listOf(ChatMemoryStoreFixtures.user("a"))) // t0
            clock.advance(1)
            harness.peer.appendMessages("act-b", listOf(ChatMemoryStoreFixtures.user("b"))) // t0+1
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("act-b", "act-a")
            clock.advance(1)
            harness.primary.appendMessages("act-a", emptyList()) // must NOT move act-a first
            assertThat(harness.primary.listConversations(100, 0)).containsExactly("act-b", "act-a")
        }
    }

    // ── C8. Concurrency: linearizable batch append ──────────────────

    @Test
    fun `concurrent single appends all succeed exactly once`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            createHarness(clock).use { harness ->
                val conversation = "race-single-$round"
                val outcomes = runInParallel(0 until 8) { index ->
                    val store = if (index % 2 == 0) harness.primary else harness.peer
                    runCatching { store.appendMessages(conversation, listOf(ChatMemoryStoreFixtures.user("M$index"))) }
                }
                assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                    "round $round: all 8 concurrent single appends must succeed, got $outcomes"
                }.isEqualTo(8)
                val history = harness.primary.getMessages(conversation)
                assertThat(history).hasSize(8)
                assertThat(history.map { it.content }.toSet())
                    .withFailMessage("round $round: each unique message must appear exactly once")
                    .isEqualTo((0 until 8).map { "M$it" }.toSet())
            }
        }
    }

    @Test
    fun `concurrent batch appends are atomic and contiguous`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            createHarness(clock).use { harness ->
                val conversation = "race-batch-$round"
                val a = listOf("A1", "A2", "A3").map { ChatMemoryStoreFixtures.user(it) }
                val b = listOf("B1", "B2", "B3").map { ChatMemoryStoreFixtures.user(it) }
                val outcomes = runInParallel(0 until 2) { index ->
                    if (index == 0) {
                        runCatching { harness.primary.appendMessages(conversation, a) }
                    } else {
                        runCatching { harness.peer.appendMessages(conversation, b) }
                    }
                }
                assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                    "round $round: both concurrent batch appends must succeed, got $outcomes"
                }.isEqualTo(2)
                val history = harness.primary.getMessages(conversation).map { it.content }
                assertThat(history).withFailMessage {
                    "round $round: batches must be contiguous, never interleaved: $history"
                }.isIn(
                    listOf("A1", "A2", "A3", "B1", "B2", "B3"),
                    listOf("B1", "B2", "B3", "A1", "A2", "A3"),
                )
            }
        }
    }

    @Test
    fun `concurrent append versus delete never leaves partial history`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            createHarness(clock).use { harness ->
                val conversation = "race-delete-$round"
                val outcomes = runInParallel(0 until 2) { index ->
                    if (index == 0) {
                        runCatching {
                            harness.primary.appendMessages(conversation, listOf("X1", "X2", "X3").map { ChatMemoryStoreFixtures.user(it) })
                        }
                    } else {
                        runCatching { harness.peer.deleteConversation(conversation) }
                    }
                }
                assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                    "round $round: both operations must succeed, got $outcomes"
                }.isEqualTo(2)
                val history = harness.primary.getMessages(conversation).map { it.content }
                assertThat(history).withFailMessage {
                    "round $round: append vs delete must never leave partial history: $history"
                }.isIn(emptyList<String>(), listOf("X1", "X2", "X3"))
                // Listing membership must agree with the final history.
                val listed = harness.peer.listConversations(100, 0).contains(conversation)
                assertThat(listed).withFailMessage {
                    "round $round: listing membership must agree with final history $history"
                }.isEqualTo(history.isNotEmpty())
            }
        }
    }

    @Test
    fun `concurrent batches in independent conversations both survive exactly`() = runBlocking<Unit> {
        repeat(20) { round ->
            val clock = MutableMillisClock()
            createHarness(clock).use { harness ->
                val conversationA = "race-ind-a-$round"
                val conversationB = "race-ind-b-$round"
                val outcomes = runInParallel(0 until 2) { index ->
                    if (index == 0) {
                        runCatching {
                            harness.primary.appendMessages(conversationA, listOf("A1", "A2", "A3").map { ChatMemoryStoreFixtures.user(it) })
                        }
                    } else {
                        runCatching {
                            harness.peer.appendMessages(conversationB, listOf("B1", "B2", "B3").map { ChatMemoryStoreFixtures.user(it) })
                        }
                    }
                }
                assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                    "round $round: both independent appends must succeed, got $outcomes"
                }.isEqualTo(2)
                assertThat(harness.primary.getMessages(conversationA).map { it.content })
                    .withFailMessage("round $round: conversation A history must survive exactly")
                    .containsExactly("A1", "A2", "A3")
                assertThat(harness.primary.getMessages(conversationB).map { it.content })
                    .withFailMessage("round $round: conversation B history must survive exactly")
                    .containsExactly("B1", "B2", "B3")
            }
        }
    }

    // ── Parallel-race helper (shared pattern from #269-#274) ─────────

    private suspend fun <T, R> runInParallel(
        items: List<T>,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val ready = Channel<Unit>(items.size)
        val release = CompletableDeferred<Unit>()
        val workers = items.map { item ->
            async(Dispatchers.Default) {
                ready.send(Unit)
                release.await()
                block(item)
            }
        }
        repeat(items.size) { ready.receive() }
        release.complete(Unit)
        workers.map { it.await() }
    }

    private suspend fun <R> runInParallel(
        range: IntRange,
        block: suspend (Int) -> R,
    ): List<R> = runInParallel(range.toList(), block)
}
