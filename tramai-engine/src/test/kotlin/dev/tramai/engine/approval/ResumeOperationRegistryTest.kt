package dev.tramai.engine.approval

import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.engine.*
import dev.tramai.engine.planning.OperationExecutionPlan
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

internal interface ApprovalRegistryService { fun first(): String; fun second(): String }

internal fun approvalOperation(method: java.lang.reflect.Method, prompt: String = "p") = OperationDefinition(
    method, Proxy.newProxyInstance(Operation::class.java.classLoader, arrayOf(Operation::class.java)) { p, m, a ->
        when (m.name) { "prompt" -> prompt; "model" -> "model"; "provider" -> ""; "tools" -> emptyArray<String>(); "maxRetries", "providerRetries" -> 0; "timeoutMillis" -> 1000L; "cacheable" -> false; "cacheTtlMillis" -> 1000L; "annotationType" -> Operation::class.java; "equals" -> p === a!![0]; "hashCode" -> System.identityHashCode(p); else -> m.defaultValue }
    } as Operation, null, emptyList(), emptyList(), false, emptyList(), ReturnKind.STRING, null, "String", emptyList(), null)
internal fun approvalService(vararg ops: OperationDefinition) = ServiceDefinition(ApprovalRegistryService::class, null, ops.associate { it.method to OperationExecutionPlan(it, OperationFingerprintFactory().create(it.toolDefinitions, it.operation), it.method.declaringClass.name, it.method.name) })
internal class StubResumeExecutor : ClaimedResumeExecutor { var calls = 0; override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? { calls++; return "ok" } }

class ResumeOperationRegistryTest {
    private fun op(name: String, prompt: String = "p") = approvalOperation(ApprovalRegistryService::class.java.getMethod(name), prompt)

    @Test fun `registered operation keeps claimed resume executor`() {
        val registry = ResumeOperationRegistry(); val executor = StubResumeExecutor(); val operation = op("first"); val reference = registry.register(approvalService(operation), operation, executor)
        assertThat(registry.resolve(reference).resumeExecutor).isSameAs(executor)
    }
    @Test fun `register returns stable method reference and is idempotent`() {
        val registry = ResumeOperationRegistry(); val operation = op("first"); val service = approvalService(operation); val executor = StubResumeExecutor()
        val reference = registry.register(service, operation, executor); assertThat(registry.register(service, operation, executor)).isEqualTo(reference)
        assertThat(reference.serviceInterface).isEqualTo(ApprovalRegistryService::class.qualifiedName); assertThat(reference.methodName).isEqualTo("first"); assertThat(reference.jvmMethodDescriptor).isEqualTo("()Ljava/lang/String;")
    }
    @Test fun `conflicting registration is rejected`() { val r=ResumeOperationRegistry(); val a=op("first", "a"); r.register(approvalService(a),a,StubResumeExecutor()); val b=op("first", "b"); assertThatThrownBy { r.register(approvalService(b),b,StubResumeExecutor()) }.isInstanceOf(ConfigurationException::class.java).hasMessage("resume-operation-registration-conflict") }
    @Test fun `register all publishes every operation`() { val r=ResumeOperationRegistry(); val a=op("first"); val b=op("second"); val s=approvalService(a,b); r.registerAll(s,StubResumeExecutor()); assertThat(r.resolve(ResumeOperationReference(ApprovalRegistryService::class.qualifiedName!!,"first","()Ljava/lang/String;",ResumeDefinitionDigestHelper.compute(s,a)))).isNotNull; assertThat(r.resolve(ResumeOperationReference(ApprovalRegistryService::class.qualifiedName!!,"second","()Ljava/lang/String;",ResumeDefinitionDigestHelper.compute(s,b)))).isNotNull }
    @Test fun `register all conflict has no partial publish`() { val r=ResumeOperationRegistry(); val old=op("first","old"); r.register(approvalService(old),old,StubResumeExecutor()); val changed=op("first","new"); val second=op("second"); val s=approvalService(changed,second); assertThatThrownBy { r.registerAll(s,StubResumeExecutor()) }.hasMessage("resume-operation-registration-conflict"); assertThatThrownBy { r.resolve(ResumeOperationReference(ApprovalRegistryService::class.qualifiedName!!,"second","()Ljava/lang/String;",ResumeDefinitionDigestHelper.compute(s,second))) }.hasMessage("resume-operation-not-registered") }
    @Test fun `missing and drift references fail explicitly`() { val r=ResumeOperationRegistry(); val a=op("first"); val s=approvalService(a); val ref=r.register(s,a,StubResumeExecutor()); assertThatThrownBy { r.resolve(ref.copy(resumeDefinitionDigest=Sha256Digest.of("sha256:"+"0".repeat(64)))) }.hasMessage("resume-operation-definition-drift"); assertThatThrownBy { r.resolve(ref.copy(methodName="second")) }.hasMessage("resume-operation-not-registered") }
}
