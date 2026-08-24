package dev.tramai.memory.store

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisDataException

/**
 * Epic 8.1h: Redis-specific regressions the shared TCK intentionally does
 * not own — pre-activity-index legacy lists, key-prefix isolation, and
 * transaction data/index consistency. Real Redis (Testcontainer), not a
 * mocked Jedis.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisChatMemoryStoreTest {

    companion object {
        @Container
        val redis: GenericContainer<*> = GenericContainer("redis:7.4-alpine")
            .withExposedPorts(6379)
    }

    private lateinit var pool: JedisPool

    @BeforeAll
    fun setUpAll() {
        pool = JedisPool("redis://${redis.host}:${redis.getMappedPort(6379)}")
    }

    @AfterAll
    fun tearDownAll() {
        pool.close()
    }

    private fun store(prefix: String = "redis-specific"): RedisChatMemoryStore =
        RedisChatMemoryStore(pool, keyPrefix = prefix)

    private fun rawLegacyList(prefix: String, conversationId: String, contents: List<String>) {
        pool.resource.use { jedis ->
            jedis.rpush("$prefix:$conversationId", *contents.toTypedArray())
        }
    }

    private fun userMessage(content: String): Message = Message(role = MessageRole.USER, content = content)

    private fun rawJson(content: String): String =
        """{"role":"USER","content":"$content","contentParts":null,"toolCallId":null,"toolCalls":null}"""

    // ── legacy unindexed data (pre-activity-index deployments) ───────

    @Test
    fun `legacy list without index member remains readable`() {
        rawLegacyList("legacy-read", "conv-1", listOf(rawJson("old-1"), rawJson("old-2")))
        assertThat(store("legacy-read").getMessages("conv-1"))
            .containsExactly(userMessage("old-1"), userMessage("old-2"))
    }

    @Test
    fun `legacy list remains discoverable in listConversations`() {
        rawLegacyList("legacy-list", "conv-legacy", listOf(rawJson("old")))
        assertThat(store("legacy-list").listConversations(100, 0)).contains("conv-legacy")
    }

    @Test
    fun `legacy append preserves old history and enrolls activity ordering`() {
        rawLegacyList("legacy-enroll", "conv-enroll", listOf(rawJson("old")))
        val s = store("legacy-enroll")
        s.appendMessages("conv-enroll", listOf(userMessage("new")))
        assertThat(s.getMessages("conv-enroll").map { it.content }).containsExactly("old", "new")
        // Enrolled in the activity index: listed and ordered by activity.
        assertThat(s.listConversations(100, 0)).containsExactly("conv-enroll")
        pool.resource.use { jedis ->
            assertThat(jedis.zscore("legacy-enroll", "conv-enroll")).isNotNull
        }
    }

    @Test
    fun `legacy delete removes data without leaving index residue`() {
        rawLegacyList("legacy-del", "conv-del", listOf(rawJson("old")))
        val s = store("legacy-del")
        s.deleteConversation("conv-del")
        assertThat(s.getMessages("conv-del")).isEmpty()
        assertThat(s.listConversations(100, 0)).doesNotContain("conv-del")
        pool.resource.use { jedis ->
            assertThat(jedis.exists("legacy-del:conv-del")).isFalse()
            val score: Double? = jedis.zscore("legacy-del", "conv-del")
            assertThat(score).isNull()
        }
    }

    @Test
    fun `legacy only conversations list deterministically by id ascending`() {
        rawLegacyList("legacy-order", "zzz", listOf(rawJson("z")))
        rawLegacyList("legacy-order", "aaa", listOf(rawJson("a")))
        assertThat(store("legacy-order").listConversations(100, 0)).containsExactly("aaa", "zzz")
    }

    // ── key-prefix isolation ─────────────────────────────────────────

    @Test
    fun `stores with distinct key prefixes never share data or index`() {
        val storeA = store("prefix-a")
        val storeB = store("prefix-b")
        storeA.appendMessages("shared-id", listOf(userMessage("from-A")))
        storeB.appendMessages("shared-id", listOf(userMessage("from-B")))
        assertThat(storeA.getMessages("shared-id").map { it.content }).containsExactly("from-A")
        assertThat(storeB.getMessages("shared-id").map { it.content }).containsExactly("from-B")
        assertThat(storeA.listConversations(100, 0)).containsExactly("shared-id")
        assertThat(storeB.listConversations(100, 0)).containsExactly("shared-id")
    }

    // ── transaction data/index consistency ──────────────────────────

    @Test
    fun `append writes conversation data and index member atomically`() {
        val s = store("txn-append")
        s.appendMessages("conv-tx", listOf(userMessage("a"), userMessage("b")))
        pool.resource.use { jedis ->
            assertThat(jedis.llen("txn-append:conv-tx")).isEqualTo(2)
            assertThat(jedis.zscore("txn-append", "conv-tx")).isNotNull
        }
    }

    @Test
    fun `delete removes conversation data and index member atomically`() {
        val s = store("txn-delete")
        s.appendMessages("conv-tx", listOf(userMessage("a")))
        s.deleteConversation("conv-tx")
        pool.resource.use { jedis ->
            assertThat(jedis.exists("txn-delete:conv-tx")).isFalse()
            val score: Double? = jedis.zscore("txn-delete", "conv-tx")
            assertThat(score).isNull()
        }
    }

    @Test
    fun `conversation id containing the separator never collides with the index`() {
        val s = store("txn-colon")
        s.appendMessages("a:b", listOf(userMessage("x")))
        pool.resource.use { jedis ->
            // The list key is "txn-colon:a:b" — still a conversation key, never the index.
            assertThat(jedis.type("txn-colon")).isEqualTo("zset")
            assertThat(jedis.llen("txn-colon:a:b")).isEqualTo(1)
            assertThat(jedis.zscore("txn-colon", "a:b")).isNotNull
        }
        assertThat(s.getMessages("a:b").map { it.content }).containsExactly("x")
        assertThat(s.listConversations(100, 0)).containsExactly("a:b")
    }

    // ── failed queued commands must fail loudly (Jedis 6 exec() returns
    //    the error as a result element instead of throwing) ───────────

    @Test
    fun `append fails loudly when the activity index key has the wrong type`() {
        val s = store("txn-wrongtype-append")
        pool.resource.use { jedis -> jedis.set("txn-wrongtype-append", "not-a-zset") }
        org.assertj.core.api.Assertions.assertThatThrownBy {
            s.appendMessages("conv-1", listOf(userMessage("x")))
        }.isInstanceOf(JedisDataException::class.java)
        // Redis MULTI does NOT roll back a failed queued command: the RPUSH
        // may already be applied, leaving a readable-but-unindexed (legacy)
        // conversation. The contract is the loud exception, not rollback.
        assertThat(s.getMessages("conv-1").map { it.content }).containsExactly("x")
    }

    @Test
    fun `delete fails loudly when the activity index key has the wrong type`() {
        val s = store("txn-wrongtype-delete")
        s.appendMessages("conv-1", listOf(userMessage("x")))
        pool.resource.use { jedis -> jedis.set("txn-wrongtype-delete", "not-a-zset") }
        org.assertj.core.api.Assertions.assertThatThrownBy {
            s.deleteConversation("conv-1")
        }.isInstanceOf(JedisDataException::class.java)
    }
}
