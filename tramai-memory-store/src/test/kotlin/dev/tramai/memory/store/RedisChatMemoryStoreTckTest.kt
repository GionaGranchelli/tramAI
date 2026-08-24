package dev.tramai.memory.store

import dev.tramai.core.memory.ChatMemoryStore
import dev.tramai.testing.persistence.memory.ChatMemoryStoreTck
import dev.tramai.testing.persistence.memory.ChatMemoryStoreTckHarness
import dev.tramai.testing.persistence.memory.MutableMillisClock
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import redis.clients.jedis.JedisPool

/**
 * Epic 8.1h: RedisChatMemoryStore must satisfy the shared chat-memory
 * compatibility contract against a REAL Redis server (Testcontainer, not a
 * mocked Jedis). One container per class; each TCK case gets a unique
 * keyPrefix and TWO independent JedisPools/two store objects over the same
 * server, so the concurrency cases prove backend-level coordination.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisChatMemoryStoreTckTest : ChatMemoryStoreTck() {

    companion object {
        @Container
        val redis: GenericContainer<*> = GenericContainer("redis:7.4-alpine")
            .withExposedPorts(6379)

        private val prefixCounter = AtomicInteger(0)
    }

    private lateinit var redisUrl: String

    @BeforeAll
    fun setUpAll() {
        redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
    }

    override fun createHarness(clock: MutableMillisClock): ChatMemoryStoreTckHarness {
        val prefix = "tck-${prefixCounter.incrementAndGet()}"
        val poolA = JedisPool(redisUrl)
        val poolB = JedisPool(redisUrl)
        return object : ChatMemoryStoreTckHarness {
            override val primary: ChatMemoryStore =
                RedisChatMemoryStore(poolA, keyPrefix = prefix).also { it.clockMillis = clock }
            override val peer: ChatMemoryStore =
                RedisChatMemoryStore(poolB, keyPrefix = prefix).also { it.clockMillis = clock }

            override fun close() {
                runCatching {
                    poolA.resource.use { jedis ->
                        val conversationKeys = jedis.keys("$prefix:*")
                        if (conversationKeys.isNotEmpty()) jedis.del(*conversationKeys.toTypedArray())
                        jedis.del(prefix)
                    }
                }
                poolA.close()
                poolB.close()
            }
        }
    }
}
