package dev.tramai.persistence.file

import com.fasterxml.jackson.module.kotlin.readValue
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.TramaiTool
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.CompatibilityMode
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import dev.tramai.security.approval.DefaultApprovalGateCoordinator
import dev.tramai.security.approval.SecureRandomApprovalTokenGenerator
import dev.tramai.security.approval.Sha256ApprovalTokenDigester
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.security.approval.UuidApprovalIdGenerator
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEngineApprovalLifecycleAuditEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.tools.ToolProvider
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.reflect.KClass

class FileSuspendedInvocationStoreRestartTest {

    private val fixedClock = Clock.fixed(
        Instant.now().minus(Duration.ofMinutes(1)),
        ZoneId.of("UTC"),
    )

    private val secretKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { secretKey }
    private val rootDir: Path = Files.createTempDirectory("tramai-restart-").toAbsolutePath()

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    interface RestartTestService {
        @Operation(
            prompt = "Execute the calculator tool",
            model = "test-model",
            tools = ["restart_calculator"],
        )
        suspend fun calculate(input: String): String
    }

    data class CalculatorInput(val x: Int, val y: Int)

    data class CalculatorResult(val result: Int)

    private open class RestartCalculatorTool(
        private val permission: String = "calculator.execute",
        private val sideEffectLevelOverride: SideEffectLevel = SideEffectLevel.READ_ONLY,
        private val securityOverride: ToolSecurityMetadata = ToolSecurityMetadata(
            permission = permission,
            risk = RiskLevel.LOW,
            approval = ApprovalMode.HUMAN_REQUIRED,
            managedNetworkEgress = ManagedNetworkEgress.DENY,
            audit = AuditDetail.FULL,
            compatibilityMode = CompatibilityMode.STRICT,
        ),
    ) : TramaiTool<CalculatorInput, CalculatorResult> {
        val executeCount = AtomicInteger(0)

        override val name: String = "restart_calculator"
        override val description: String = "Calculates a result"
        override val inputType: KClass<CalculatorInput> = CalculatorInput::class
        override val idempotent: Boolean = true
        override val sideEffectLevel: SideEffectLevel = sideEffectLevelOverride
        override val security: ToolSecurityMetadata = securityOverride

        override suspend fun execute(
            input: CalculatorInput,
            context: ToolExecutionContext,
        ): CalculatorResult {
            executeCount.incrementAndGet()
            return CalculatorResult(result = input.x + input.y)
        }

        fun toResolvedTool(): ResolvedTool = object : ResolvedTool {
            override val name: String = this@RestartCalculatorTool.name
            override val description: String = this@RestartCalculatorTool.description
            override val inputSchemaJson: String =
                """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}"""
            override val idempotent: Boolean = this@RestartCalculatorTool.idempotent
            override val sideEffectLevel: SideEffectLevel = this@RestartCalculatorTool.sideEffectLevel
            override val security: ToolSecurityMetadata = this@RestartCalculatorTool.security

            override suspend fun execute(
                input: Any,
                context: ToolExecutionContext,
            ): ToolResult {
                val parsed: CalculatorInput = FILE_STORE_JSON.readValue(input as String)
                val result = this@RestartCalculatorTool.execute(parsed, context)
                return ToolResult.Success(FILE_STORE_JSON.writeValueAsString(result))
            }
        }
    }

    private class RestartTestProvider(
        private val toolCallId: String = "restart-tc-1",
        private val toolName: String = "restart_calculator",
        private val toolArguments: String = """{"x":2,"y":3}""",
        startingCallCount: Int = 0,
    ) : ModelProvider {
        var callCount = startingCallCount

        override suspend fun complete(request: ModelRequest): ModelResponse {
            callCount++
            return if (callCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }

        override fun providerId(): String = "restart-test-provider"
    }

    private class RestartPolicyEngine : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            return when (context.enforcementPoint) {
                EnforcementPoint.BEFORE_TOOL_EXECUTION -> {
                    PolicyDecision.RequireApproval(
                        ApprovalRequirement(
                            toolName = context.toolName ?: "restart_calculator",
                            argumentsDigest = "",
                            reason = "restart-test-approval",
                            timeoutMillis = 900_000,
                        ),
                    )
                }
                EnforcementPoint.BEFORE_WORKFLOW_RESUME -> PolicyDecision.Allow
                else -> PolicyDecision.Allow
            }
        }
    }

    @Test
    fun `workflow suspended in runtime A resumes safely in runtime B after reopening encrypted stores`() {
        val config = createConfig()
        val providerA = RestartTestProvider()
        val toolA = RestartCalculatorTool()

        val suspension = FileBackedSovereignStores.open(config).use { storesA ->
            createEngine(
                provider = providerA,
                toolRegistry = toolRegistryFor(toolA),
                stores = storesA,
            ).use { engineA ->
                val exception = triggerSuspension(engineA)
                val approved = approve(storesA, exception.approvalId)

                SuspendedWorkflow(
                    approvalId = exception.approvalId,
                    approvalVersion = approved.version,
                    continuationVersion = exception.continuationVersion,
                    approvalToken = exception.challenge.token,
                    workflowRunId = exception.workflowRunId,
                )
            }
        }

        val providerB = RestartTestProvider(startingCallCount = 1)
        val toolB = RestartCalculatorTool()

        FileBackedSovereignStores.open(config).use { storesB ->
            createEngine(
                provider = providerB,
                toolRegistry = toolRegistryFor(toolB),
                stores = storesB,
            ).use { engineB ->
                engineB.registerService(RestartTestService::class)

                val result = runBlocking {
                    engineB.resumeApproval(
                        ResumeApprovalCommand(
                            approvalId = suspension.approvalId,
                            approvalExpectedVersion = suspension.approvalVersion,
                            continuationExpectedVersion = suspension.continuationVersion,
                            presentedToken = suspension.approvalToken,
                            resumedBy = "admin",
                        ),
                    )
                }

                assertThat(result).isEqualTo("Final result: success")
                assertThat(toolB.executeCount.get()).isEqualTo(1)

                val continuation = runBlocking { storesB.approvalContinuationStore.get(suspension.approvalId) }
                assertThat(continuation).isNotNull
                assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)

                val suspended = runBlocking { storesB.suspendedInvocationStore.get(suspension.approvalId) }
                assertThat(suspended).isNull()

                val auditEvents = runBlocking { storesB.auditStore.readStream(suspension.workflowRunId) }
                assertThat(auditEvents).isNotEmpty
            }
        }
    }

    @Test
    fun `resume fails closed when runtime B has not registered the suspended service and succeeds after registration`() {
        val config = createConfig()
        val toolA = RestartCalculatorTool()

        val suspension = FileBackedSovereignStores.open(config).use { storesA ->
            createEngine(
                provider = RestartTestProvider(),
                toolRegistry = toolRegistryFor(toolA),
                stores = storesA,
            ).use { engineA ->
                val exception = triggerSuspension(engineA)
                val approved = approve(storesA, exception.approvalId)
                SuspendedWorkflow(
                    approvalId = exception.approvalId,
                    approvalVersion = approved.version,
                    continuationVersion = exception.continuationVersion,
                    approvalToken = exception.challenge.token,
                    workflowRunId = exception.workflowRunId,
                )
            }
        }

        val toolB = RestartCalculatorTool()

        FileBackedSovereignStores.open(config).use { storesB ->
            createEngine(
                provider = RestartTestProvider(startingCallCount = 1),
                toolRegistry = toolRegistryFor(toolB),
                stores = storesB,
            ).use { engineB ->
                assertThatThrownBy {
                    runBlocking {
                        engineB.resumeApproval(
                            ResumeApprovalCommand(
                                approvalId = suspension.approvalId,
                                approvalExpectedVersion = suspension.approvalVersion,
                                continuationExpectedVersion = suspension.continuationVersion,
                                presentedToken = suspension.approvalToken,
                                resumedBy = "admin",
                            ),
                        )
                    }
                }.isInstanceOf(ConfigurationException::class.java)
                    .hasMessageContaining("resume-operation-not-registered")

                val pending = runBlocking { storesB.approvalContinuationStore.get(suspension.approvalId) }
                assertThat(pending).isNotNull
                assertThat(pending!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
                assertThat(toolB.executeCount.get()).isEqualTo(0)
                assertThat(runBlocking { storesB.suspendedInvocationStore.get(suspension.approvalId) }).isNotNull

                engineB.registerService(RestartTestService::class)

                val result = runBlocking {
                    engineB.resumeApproval(
                        ResumeApprovalCommand(
                            approvalId = suspension.approvalId,
                            approvalExpectedVersion = suspension.approvalVersion,
                            continuationExpectedVersion = suspension.continuationVersion,
                            presentedToken = suspension.approvalToken,
                            resumedBy = "admin",
                        ),
                    )
                }

                assertThat(result).isEqualTo("Final result: success")
                assertThat(toolB.executeCount.get()).isEqualTo(1)
            }
        }
    }

    @Test
    fun `resume fails closed before token consumption when tool declaration drifts across restart`() {
        val config = createConfig()
        val toolA = RestartCalculatorTool()

        val suspension = FileBackedSovereignStores.open(config).use { storesA ->
            createEngine(
                provider = RestartTestProvider(),
                toolRegistry = toolRegistryFor(toolA),
                stores = storesA,
            ).use { engineA ->
                val exception = triggerSuspension(engineA)
                val approved = approve(storesA, exception.approvalId)
                SuspendedWorkflow(
                    approvalId = exception.approvalId,
                    approvalVersion = approved.version,
                    continuationVersion = exception.continuationVersion,
                    approvalToken = exception.challenge.token,
                    workflowRunId = exception.workflowRunId,
                )
            }
        }

        val driftedTool = RestartCalculatorTool(permission = "different.execute")

        FileBackedSovereignStores.open(config).use { storesB ->
            createEngine(
                provider = RestartTestProvider(),
                toolRegistry = toolRegistryFor(driftedTool),
                stores = storesB,
            ).use { engineB ->
                engineB.registerService(RestartTestService::class)

                assertThatThrownBy {
                    runBlocking {
                        engineB.resumeApproval(
                            ResumeApprovalCommand(
                                approvalId = suspension.approvalId,
                                approvalExpectedVersion = suspension.approvalVersion,
                                continuationExpectedVersion = suspension.continuationVersion,
                                presentedToken = suspension.approvalToken,
                                resumedBy = "admin",
                            ),
                        )
                    }
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("resume-tool-declaration-drift")

                val continuation = runBlocking { storesB.approvalContinuationStore.get(suspension.approvalId) }
                assertThat(continuation).isNotNull
                assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
                assertThat(driftedTool.executeCount.get()).isEqualTo(0)
                assertThat(runBlocking { storesB.suspendedInvocationStore.get(suspension.approvalId) }).isNotNull
            }
        }
    }

    @Test
    fun `resume fails closed before token consumption when operation definition drifts across restart`() {
        val config = createConfig()
        val serviceName = "dev.tramai.persistence.file.DynamicRestartService"
        val toolA = RestartCalculatorTool()

        val suspension = FileBackedSovereignStores.open(config).use { storesA ->
            val serviceA = compileDynamicService(
                fqcn = serviceName,
                model = "test-model",
            )
            createEngine(
                provider = RestartTestProvider(),
                toolRegistry = toolRegistryFor(toolA),
                stores = storesA,
            ).use { engineA ->
                val exception = triggerDynamicSuspension(engineA, serviceA)
                val approved = approve(storesA, exception.approvalId)
                SuspendedWorkflow(
                    approvalId = exception.approvalId,
                    approvalVersion = approved.version,
                    continuationVersion = exception.continuationVersion,
                    approvalToken = exception.challenge.token,
                    workflowRunId = exception.workflowRunId,
                )
            }
        }

        val toolB = RestartCalculatorTool()

        FileBackedSovereignStores.open(config).use { storesB ->
            val serviceB = compileDynamicService(
                fqcn = serviceName,
                model = "changed-model",
            )
            createEngine(
                provider = RestartTestProvider(),
                toolRegistry = toolRegistryFor(toolB),
                stores = storesB,
            ).use { engineB ->
                engineB.registerService(serviceB)

                assertThatThrownBy {
                    runBlocking {
                        engineB.resumeApproval(
                            ResumeApprovalCommand(
                                approvalId = suspension.approvalId,
                                approvalExpectedVersion = suspension.approvalVersion,
                                continuationExpectedVersion = suspension.continuationVersion,
                                presentedToken = suspension.approvalToken,
                                resumedBy = "admin",
                            ),
                        )
                    }
                }.isInstanceOf(ConfigurationException::class.java)
                    .hasMessageContaining("resume-operation-definition-drift")

                val continuation = runBlocking { storesB.approvalContinuationStore.get(suspension.approvalId) }
                assertThat(continuation).isNotNull
                assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
                assertThat(toolB.executeCount.get()).isEqualTo(0)
                assertThat(runBlocking { storesB.suspendedInvocationStore.get(suspension.approvalId) }).isNotNull
            }
        }
    }

    private fun createConfig() = FileBackedStoreConfiguration(
        rootDirectory = rootDir,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "restart-test-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = false,
    )

    private fun toolRegistryFor(tool: RestartCalculatorTool): ToolRegistry =
        ToolRegistry(mapOf(tool.name to tool.toResolvedTool()))

    private fun createEngine(
        provider: ModelProvider,
        toolRegistry: ToolRegistry,
        stores: FileBackedSovereignStores,
    ): TramaiEngine {
        val coordinator = DefaultApprovalGateCoordinator(
            store = stores.approvalStore,
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = Sha256ApprovalTokenDigester(),
            clock = fixedClock,
        )
        val auditEmitter = AuditEngineApprovalLifecycleAuditEmitter(
            AuditEngine(stores.auditStore, clock = fixedClock),
        )
        return TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = RestartPolicyEngine(),
            suspendedInvocationStore = stores.suspendedInvocationStore,
            approvalContinuationStore = stores.approvalContinuationStore,
            toolArgumentsDigester = Sha256ToolArgumentsDigester(),
            approvalGateCoordinator = coordinator,
            approvalLifecycleAuditEmitter = auditEmitter,
            clock = fixedClock,
        )
    }

    private fun triggerSuspension(engine: TramaiEngine): ApprovalSuspendedException {
        val proxy = engine.create<RestartTestService>()
        return try {
            runBlocking { proxy.calculate("test") }
            fail("Expected ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }
    }

    private fun approve(
        stores: FileBackedSovereignStores,
        approvalId: String,
    ) = runBlocking {
        val current = stores.approvalStore.get(approvalId)
        requireNotNull(current) { "Expected approval request to exist" }
        stores.approvalStore.transition(
            approvalId = approvalId,
            expectedVersion = current.version,
            transition = ApprovalTransition.Approve(
                decidedBy = "admin",
                comment = "approved for restart test",
            ),
        )
    }

    private fun compileDynamicService(
        fqcn: String,
        model: String,
    ): KClass<*> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
            "JDK compiler is required for dynamic service drift tests"
        }
        val sourceRoot = Files.createTempDirectory(rootDir, "dynamic-service-src-")
        val classesRoot = Files.createTempDirectory(rootDir, "dynamic-service-classes-")
        val packageName = fqcn.substringBeforeLast('.')
        val simpleName = fqcn.substringAfterLast('.')
        val sourcePath = sourceRoot.resolve(fqcn.replace('.', '/') + ".java")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(
            sourcePath,
            """
            package $packageName;

            import dev.tramai.core.annotations.AiService;
            import dev.tramai.core.annotations.Operation;
            import dev.tramai.core.annotations.SystemPrompt;

            @AiService
            @SystemPrompt("You are a helpful assistant.")
            public interface $simpleName {
                @Operation(
                    prompt = "Execute the calculator tool",
                    model = "$model",
                    tools = {"restart_calculator"}
                )
                String calculate(String input);
            }
            """.trimIndent(),
        )

        compiler.getStandardFileManager(null, null, null).use { fileManager ->
            val units = fileManager.getJavaFileObjects(sourcePath.toFile())
            val classpath = System.getProperty("java.class.path")
            val task = compiler.getTask(
                null,
                fileManager,
                null,
                listOf("-classpath", classpath, "-d", classesRoot.absolutePathString()),
                null,
                units,
            )
            check(task.call()) { "Failed to compile dynamic service $fqcn" }
        }

        return URLClassLoader(
            arrayOf(classesRoot.toUri().toURL()),
            javaClass.classLoader,
        ).use { loader ->
            loader.loadClass(fqcn).kotlin
        }
    }

    private fun triggerDynamicSuspension(
        engine: TramaiEngine,
        serviceType: KClass<*>,
    ): ApprovalSuspendedException {
        @Suppress("UNCHECKED_CAST")
        val proxy = engine.create(serviceType as KClass<Any>)
        val method = serviceType.java.getMethod("calculate", String::class.java)
        val cause = try {
            method.invoke(proxy, "test")
            fail("Expected ApprovalSuspendedException")
        } catch (e: Throwable) {
            unwrapInvocationThrowable(e)
        }
        assertThat(cause).isInstanceOf(ApprovalSuspendedException::class.java)
        return cause as ApprovalSuspendedException
    }

    private fun unwrapInvocationThrowable(throwable: Throwable): Throwable =
        when (throwable) {
            is InvocationTargetException -> throwable.targetException
            else -> throwable
        }

    private data class SuspendedWorkflow(
        val approvalId: String,
        val approvalVersion: Long,
        val continuationVersion: Long,
        val approvalToken: dev.tramai.core.approval.ApprovalToken,
        val workflowRunId: String,
    )
}
