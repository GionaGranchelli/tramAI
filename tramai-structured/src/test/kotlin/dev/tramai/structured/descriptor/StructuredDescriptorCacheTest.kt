package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Descriptor cache tests for Epic 7.1: instance-scoped, concurrency-safe,
 * never caching failed compilations.
 */
class StructuredDescriptorCacheTest {

    private val compiler = StructuredTypeCompiler(
        JsonMapper.builder().addModule(kotlinModule()).build(),
    )

    @Test
    fun `same target within handler compiles once`() {
        val cache = StructuredDescriptorCache()
        var compileCount = 0

        cache.getOrCompile(typeOf<CachedObject>()) {
            compileCount++
            compiler.compile(it)
        }
        cache.getOrCompile(typeOf<CachedObject>()) {
            compileCount++
            compiler.compile(it)
        }

        assertThat(compileCount).isEqualTo(1)
        assertThat(cache.size()).isEqualTo(1)
    }

    @Test
    fun `different targets compile independently`() {
        val cache = StructuredDescriptorCache()

        val first = cache.getOrCompile(typeOf<CachedObject>()) { compiler.compile(it) }
        val second = cache.getOrCompile(typeOf<CachedOther>()) { compiler.compile(it) }

        assertThat(first).isNotSameAs(second)
        assertThat(cache.size()).isEqualTo(2)
    }

    @Test
    fun `failed compilation is not cached`() {
        val cache = StructuredDescriptorCache()
        var attempts = 0

        val compile = { _: kotlin.reflect.KType ->
            attempts++
            compiler.compile(typeOf<Map<String, String>>())
        }

        assertThatThrownBy { cache.getOrCompile(typeOf<CachedObject>(), compile) }
            .isInstanceOf(IllegalStateException::class.java)

        // Second attempt re-compiles (nothing was cached).
        assertThatThrownBy { cache.getOrCompile(typeOf<CachedObject>(), compile) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(attempts).isEqualTo(2)
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun `two handler-scoped caches do not share state`() {
        val cacheA = StructuredDescriptorCache()
        val cacheB = StructuredDescriptorCache()

        cacheA.getOrCompile(typeOf<CachedObject>()) { compiler.compile(it) }

        assertThat(cacheA.size()).isEqualTo(1)
        assertThat(cacheB.size()).isEqualTo(0)
    }

    @Test
    fun `concurrent requests compile safely`() {
        val cache = StructuredDescriptorCache()
        val compileCount = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)

        try {
            repeat(16) {
                pool.submit {
                    start.await()
                    try {
                        repeat(50) {
                            cache.getOrCompile(typeOf<CachedObject>()) {
                                compileCount.incrementAndGet()
                                compiler.compile(it)
                            }
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            pool.shutdownNow()
        }

        // ConcurrentHashMap.computeIfAbsent is atomic: exactly one compile.
        assertThat(compileCount.get()).isEqualTo(1)
        assertThat(cache.size()).isEqualTo(1)
    }

    private data class CachedObject(val value: String)

    private data class CachedOther(val number: Int)
}
