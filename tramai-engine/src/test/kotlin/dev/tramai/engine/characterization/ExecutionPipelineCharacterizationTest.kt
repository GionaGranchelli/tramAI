package dev.tramai.engine.characterization

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.exception.PolicyViolationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionPipelineCharacterizationTest {
    @Test
    fun `simple successful invocation records the current happy path`() {
        val fixture = ExecutionTraceFixture()
        val result = runBlocking { fixture.engine().create(TraceService::class).answer("input") }
        assertThat(result).isEqualTo("answer")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("successful-invocation.trace"))
    }

    @Test
    fun `memory invocation loads before execution and persists after it`() {
        val fixture = ExecutionTraceFixture().apply { memoryEnabled = true }
        runBlocking { fixture.engine().create(TraceService::class).answer("input") }
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("memory-invocation.trace"))
        assertThat(index(fixture, "memory.load")).isLessThan(index(fixture, "provider.start"))
        assertThat(index(fixture, "memory.persist")).isGreaterThan(index(fixture, "provider.success"))
    }

    @Test
    fun `cache miss stores after provider completion`() {
        val fixture = ExecutionTraceFixture().apply { cacheEnabled = true }
        runBlocking { fixture.engine().create(TraceService::class).cached("input") }
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("cache-miss.trace"))
        assertThat(index(fixture, "cache.miss")).isLessThan(index(fixture, "provider.start"))
        assertThat(index(fixture, "cache.store")).isGreaterThan(index(fixture, "provider.success"))
    }

    @Test
    fun `cache hit short circuits provider after provenance authorization`() {
        val fixture = ExecutionTraceFixture().apply { cacheEnabled = true; cachePreloaded = true }
        val result = runBlocking { fixture.engine().create(TraceService::class).cached("input") }
        assertThat(result).isEqualTo("cached")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("cache-hit.trace"))
        assertThat(fixture.trace.snapshot().map { it.type }).doesNotContain("provider.start")
        assertThat(index(fixture, "cache.hit")).isLessThan(index(fixture, "model.authorize"))
    }

    @Test
    fun `policy allow precedes provider execution`() {
        val fixture = ExecutionTraceFixture()
        runBlocking { fixture.engine().create(TraceService::class).answer("input") }
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("policy-allow.trace"))
        assertThat(index(fixture, "policy.allow")).isLessThan(index(fixture, "provider.start"))
    }

    @Test
    fun `policy deny prevents provider execution`() {
        val fixture = ExecutionTraceFixture().apply { denyAt = EnforcementPoint.BEFORE_PROVIDER_RESOLUTION }
        assertThatThrownBy { runBlocking { fixture.engine().create(TraceService::class).answer("input") } }
            .isInstanceOf(PolicyViolationException::class.java)
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("policy-deny.trace"))
        assertThat(fixture.trace.snapshot().map { it.type }).doesNotContain("provider.start")
    }

    @Test
    fun `primary route authorizes its model before provider execution`() {
        val fixture = ExecutionTraceFixture()
        runBlocking { fixture.engine().create(TraceService::class).answer("input") }
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("provider-routing.trace"))
        assertThat(index(fixture, "model.authorize")).isLessThan(index(fixture, "provider.execute"))
    }

    private fun index(fixture: ExecutionTraceFixture, type: String): Int =
        fixture.trace.snapshot().indexOfFirst { it.type == type }
}
