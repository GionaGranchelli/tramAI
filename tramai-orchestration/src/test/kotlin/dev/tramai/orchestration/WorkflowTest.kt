package dev.tramai.orchestration
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.engine.InMemoryOperationResponseCache
import dev.tramai.engine.TramaiEngine
import dev.tramai.engine.create
import dev.tramai.testing.RecordingOperationObserver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.fail
class WorkflowTest {
    @Test
    fun `executes plan execute review finalize workflow`() {
        val planner = FakePlannerService()
        val worker = FakeWorkerService()
        val reviewer = FakeReviewerService()
        val finalizer = FakeFinalizerService()
        val observer = RecordingWorkflowObserver()
        val workflow = workflow<PlanningState>("plan-execute-review-finalize") {
            aiStep(
                name = "plan",
                input = { it.request },
                invoke = planner::plan,
                merge = { state, plan -> state.copy(plan = plan) },
            )
            parallelStep(
                name = "execute",
                items = { state -> state.plan?.items.orEmpty() },
                invoke = worker::execute,
                merge = { state, results -> state.copy(results = results) },
            )
            aiStep(
                name = "review",
                input = { ReviewInput(it.results) },
                invoke = reviewer::review,
                merge = { state, review -> state.copy(review = review) },
            )
            aiStep(
                name = "finalize",
                input = { FinalizeInput(it.review ?: error("review must exist")) },
                invoke = finalizer::finalize,
                merge = { state, finalAnswer -> state.copy(finalAnswer = finalAnswer) },
            )
        }.build { it.finalAnswer ?: error("final answer must exist") }
        val result = runBlocking {
            workflow.run(
                initialState = PlanningState(request = "invoice-123"),
                observer = observer,
            )
        }
        assertThat(result).isEqualTo("APPROVED:extract:invoice-123|summarize:invoice-123")
        assertThat(observer.startedSteps)
            .containsExactly("plan", "execute", "execute[0]", "execute[1]", "review", "finalize")
    }
    @Test
    fun `executes route specialist validate workflow`() {
        val router = FakeRouterService()
        val billing = FakeBillingSpecialistService()
        val support = FakeSupportSpecialistService()
        val validator = FakeValidationService()
        val workflow = workflow<RoutingState>("route-specialist-validate") {
            aiStep(
                name = "route",
                input = { it.request },
                invoke = router::route,
                merge = { state, route -> state.copy(route = route) },
            )
            branchStep(
                name = "specialist",
                select = { it.route?.specialist ?: error("route must exist") },
            ) {
                branch("billing") {
                    aiStep(
                        name = "billing-specialist",
                        input = { BillingRequest(it.request) },
                        invoke = billing::analyze,
                        merge = { state, response -> state.copy(specialistResult = response) },
                    )
                }
                branch("support") {
                    aiStep(
                        name = "support-specialist",
                        input = { SupportRequest(it.request) },
                        invoke = support::analyze,
                        merge = { state, response -> state.copy(specialistResult = response) },
                    )
                }
            }
            aiStep(
                name = "validate",
                input = { ValidationInput(it.specialistResult ?: error("specialist result must exist")) },
                invoke = validator::validate,
                merge = { state, validated -> state.copy(validated = validated) },
            )
        }.build { it.validated ?: error("validated result must exist") }
        val result = runBlocking {
            workflow.run(RoutingState(request = "invoice-987"))
        }
        assertThat(result.normalizedText).isEqualTo("billing:invoice-987")
        assertThat(result.accepted).isTrue()
    }
    @Test
    fun `executes generate candidates judge return workflow`() {
        val candidateGenerators = listOf(
            CandidateGenerator("draft-a"),
            CandidateGenerator("draft-b"),
            CandidateGenerator("draft-c"),
        )
        val judge = FakeJudgeService()
        val workflow = workflow<CandidateState>("generate-candidates-judge-return") {
            parallelStep(
                name = "generate-candidates",
                items = { state -> candidateGenerators.map { CandidateJob(it, state.prompt) } },
                invoke = { job -> job.generator.generate(job.prompt) },
                merge = { state, candidates -> state.copy(candidates = candidates) },
            )
            aiStep(
                name = "judge",
                input = { JudgeInput(it.candidates) },
                invoke = judge::choose,
                merge = { state, winner -> state.copy(winner = winner) },
            )
        }.build { it.winner ?: error("winner must exist") }
        val winner = runBlocking {
            workflow.run(CandidateState(prompt = "summarize invoice"))
        }
        assertThat(winner.generatorName).isEqualTo("draft-c")
        assertThat(winner.content).isEqualTo("draft-c:summarize invoice")
    }
    @Test
    fun `bounded parallel step fails when branch width exceeds stop policy`() {
        val generators = listOf(
            CandidateGenerator("draft-a"),
            CandidateGenerator("draft-b"),
            CandidateGenerator("draft-c"),
        )
        val workflow = workflow<CandidateState>("bounded-parallel") {
            parallelStep(
                name = "generate-candidates",
                items = { state -> generators.map { CandidateJob(it, state.prompt) } },
                invoke = { job -> job.generator.generate(job.prompt) },
                merge = { state, candidates -> state.copy(candidates = candidates) },
            )
        }.build(
            stopPolicy = StopPolicy(
                maxStepExecutions = 10,
                maxParallelBranches = 2,
            ),
        ) { it.candidates }
        assertThatThrownBy {
            runBlocking { workflow.run(CandidateState(prompt = "hello")) }
        }
            .isInstanceOf(WorkflowLimitExceededException::class.java)
            .hasMessageContaining("maxParallelBranches=2")
    }
    @Test
    fun `bounded parallel step rejects lazy iterable overflow without consuming the full source`() {
        var enumerated = 0
        var invoked = 0
        val lazyJobs = Iterable {
            object : Iterator<CandidateJob> {
                private var index = 0
                override fun hasNext(): Boolean = index < 100
                override fun next(): CandidateJob {
                    if (!hasNext()) {
                        throw NoSuchElementException()
                    }
                    enumerated += 1
                    val generatorIndex = index++
                    return CandidateJob(
                        generator = CandidateGenerator("draft-$generatorIndex"),
                        prompt = "lazy-prompt",
                    )
                }
            }
        }
        val workflow = workflow<CandidateState>("bounded-parallel-lazy") {
            parallelStep(
                name = "generate-candidates",
                items = { lazyJobs },
                invoke = { job ->
                    invoked += 1
                    job.generator.generate(job.prompt)
                },
                merge = { state, candidates -> state.copy(candidates = candidates) },
            )
        }.build(
            stopPolicy = StopPolicy(
                maxStepExecutions = 10,
                maxParallelBranches = 2,
            ),
        ) { it.candidates }
        assertThatThrownBy {
            runBlocking { workflow.run(CandidateState(prompt = "hello")) }
        }
            .isInstanceOf(WorkflowLimitExceededException::class.java)
            .hasMessageContaining("maxParallelBranches=2")
        assertThat(enumerated).isEqualTo(2)
        assertThat(invoked).isEqualTo(0)
    }
    @Test
    fun `parallel step consumes one step budget for the top level step plus one per branch`() {
        val workflow = workflow<CandidateState>("parallel-budget-accounting") {
            parallelStep(
                name = "generate-candidates",
                items = {
                    listOf(
                        CandidateJob(CandidateGenerator("draft-a"), it.prompt),
                        CandidateJob(CandidateGenerator("draft-b"), it.prompt),
                    )
                },
                invoke = { job -> job.generator.generate(job.prompt) },
                merge = { state, candidates -> state.copy(candidates = candidates) },
            )
            localStep(
                name = "finalize",
                transform = { state, _ -> state.copy(prompt = "final:${state.candidates.size}") },
            )
        }.build(
            stopPolicy = StopPolicy(
                maxStepExecutions = 3,
                maxParallelBranches = 2,
            ),
        ) { it.prompt }
        assertThatThrownBy {
            runBlocking { workflow.run(CandidateState(prompt = "hello")) }
        }
            .isInstanceOf(WorkflowLimitExceededException::class.java)
            .hasMessageContaining("before step 'finalize'")
            .hasMessageContaining("maxStepExecutions=3")
    }
    @Test
    fun `gate step rejects workflow and reports the failed step`() {
        val observer = RecordingWorkflowObserver()
        val workflow = workflow<ApprovalState>("approval-gate") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
            gateStep(
                name = "approval",
                decide = { state, _ ->
                    if (state.approved) {
                        GateDecision.allow()
                    } else {
                        GateDecision.reject("manual approval required")
                    }
                },
            )
            localStep(
                name = "finalize",
                transform = { state, _ -> state.copy(finalized = true) },
            )
        }.build { it.finalized }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ApprovalState(
                        request = "invoice-123",
                        approved = false,
                    ),
                    observer = observer,
                )
            }
        }
            .isInstanceOf(WorkflowGateRejectedException::class.java)
            .hasMessageContaining("manual approval required")
        assertThat(observer.startedSteps).containsExactly("draft", "approval")
        assertThat(observer.failedSteps).containsExactly("approval")
        assertThat(observer.completedSteps).containsExactly("draft")
    }
    @Test
    fun `branch step runs default branch when no explicit branch matches`() {
        val workflow = workflow<RoutingState>("route-default") {
            branchStep(
                name = "specialist",
                select = { "unknown" },
            ) {
                branch("billing") {
                    localStep(
                        name = "billing-specialist",
                        transform = { state, _ -> state.copy(specialistResult = "billing:${state.request}") },
                    )
                }
                default {
                    localStep(
                        name = "fallback-specialist",
                        transform = { state, _ -> state.copy(specialistResult = "fallback:${state.request}") },
                    )
                }
            }
        }.build { it.specialistResult ?: error("specialist result must exist") }
        val result = runBlocking {
            workflow.run(RoutingState(request = "invoice-404"))
        }
        assertThat(result).isEqualTo("fallback:invoice-404")
    }
    @Test
    fun `branch step fails loudly when no explicit branch matches and no default branch exists`() {
        val workflow = workflow<RoutingState>("route-no-default") {
            branchStep(
                name = "specialist",
                select = { "unknown" },
            ) {
                branch("billing") {
                    localStep(
                        name = "billing-specialist",
                        transform = { state, _ -> state.copy(specialistResult = "billing:${state.request}") },
                    )
                }
            }
        }.build { it.specialistResult ?: error("specialist result must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.run(RoutingState(request = "invoice-404"))
            }
        }
            .isInstanceOf(WorkflowBranchSelectionException::class.java)
            .hasMessageContaining("selected unknown branch 'unknown'")
            .hasMessageContaining("step 'specialist'")
    }
    @Test
    fun `workflow definition rejects duplicate step names across branches`() {
        assertThatThrownBy {
            workflow<RoutingState>("duplicate-step-names") {
                localStep(
                    name = "shared-name",
                    transform = { state, _ -> state },
                )
                branchStep(
                    name = "route",
                    select = { "billing" },
                ) {
                    branch("billing") {
                        localStep(
                            name = "shared-name",
                            transform = { state, _ -> state },
                        )
                    }
                }
            }.build { it.request }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate step name 'shared-name'")
    }
    @Test
    fun `workflow definition rejects duplicate branch keys`() {
        assertThatThrownBy {
            workflow<RoutingState>("duplicate-branch-keys") {
                branchStep(
                    name = "route",
                    select = { "billing" },
                ) {
                    branch("billing") {
                        localStep(
                            name = "billing-a",
                            transform = { state, _ -> state },
                        )
                    }
                    branch("billing") {
                        localStep(
                            name = "billing-b",
                            transform = { state, _ -> state },
                        )
                    }
                }
            }.build { it.request }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("branch key 'billing' is already configured")
    }
    @Test
    fun `workflow definition rejects multiple default branches`() {
        assertThatThrownBy {
            workflow<RoutingState>("multiple-default-branches") {
                branchStep(
                    name = "route",
                    select = { "other" },
                ) {
                    default {
                        localStep(
                            name = "fallback-a",
                            transform = { state, _ -> state },
                        )
                    }
                    default {
                        localStep(
                            name = "fallback-b",
                            transform = { state, _ -> state },
                        )
                    }
                }
            }.build { it.request }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("default branch is already configured")
    }
    @Test
    fun `workflow definition rejects blank workflow and step names`() {
        assertThatThrownBy {
            workflow<RoutingState>("") {
                localStep(
                    name = "ok",
                    transform = { state, _ -> state },
                )
            }.build { it.request }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Workflow.name must not be blank")
        assertThatThrownBy {
            workflow<RoutingState>(
                name = "blank-definition-version",
                definitionVersion = " ",
            ) {
                localStep(
                    name = "ok",
                    transform = { state, _ -> state },
                )
            }.build { it.request }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Workflow.definitionVersion must not be blank")
        assertThatThrownBy {
            workflow<RoutingState>("blank-step") {
                localStep(
                    name = " ",
                    transform = { state, _ -> state },
                )
            }.build { it.request }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Workflow step name must not be blank")
    }
    @Test
    fun `resume fails loudly on invalid checkpoint next step index`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<ResumeState>("resume-invalid-next-step") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "resume-invalid-next-step",
                    workflowId = "wf-invalid-index",
                    nextStepIndex = 3,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = ResumeStateCodec.encode(
                        ResumeState(request = "invoice-123"),
                    ),
                ),
            )
        }
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = WorkflowContext(workflowId = "wf-invalid-index"),
                    persistence = WorkflowPersistence(
                        checkpointStore = store,
                        stateCodec = ResumeStateCodec,
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("invalid nextStepIndex=3")
    }
    @Test
    fun `run persists workflow definition compatibility metadata in checkpoint metadata`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<ResumeState>(
            name = "fingerprinted-workflow",
            definitionVersion = "invoice-review-v1",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            workflow.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = "fingerprint-1"),
                persistence = WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = ResumeStateCodec,
                    deleteCheckpointOnCompletion = false,
                ),
            )
        }
        val checkpoint = runBlocking {
            store.load("fingerprinted-workflow", "fingerprint-1")
        }
        assertThat(checkpoint).isNotNull
        assertThat(checkpoint!!.metadata)
            .containsEntry("tramai.workflow.definition.version", "invoice-review-v1")
            .containsEntry("tramai.workflow.definition.digest.algorithm", "SHA-256")
        assertThat(checkpoint.metadata["tramai.workflow.definition.digest"])
            .isNotBlank()
        assertThat(checkpoint.metadata["tramai.workflow.definition.digest"])
            .hasSize(64)
    }
    @Test
    fun `resume fails when checkpoint definition digest does not match workflow definition`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflowId = "fingerprint-mismatch-1"
        val original = workflow<ResumeState>(
            name = "fingerprint-mismatch",
            definitionVersion = "resume-v1",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            original.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = workflowId),
                persistence = persistence,
            )
        }
        val changed = workflow<ResumeState>(
            name = "fingerprint-mismatch",
            definitionVersion = "resume-v1",
        ) {
            localStep(
                name = "prepare",
                transform = { state, _ -> state.copy(draft = "prepared:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        assertThatThrownBy {
            runBlocking {
                changed.resume(
                    context = WorkflowContext(workflowId = workflowId),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("different workflow definition digest")
    }
    @Test
    fun `resume fails when checkpoint definition version does not match current workflow definition version`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflowId = "definition-version-mismatch-1"
        val original = workflow<ResumeState>(
            name = "definition-version-mismatch",
            definitionVersion = "resume-v1",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            original.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = workflowId),
                persistence = persistence,
            )
        }
        val changed = workflow<ResumeState>(
            name = "definition-version-mismatch",
            definitionVersion = "resume-v2",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        assertThatThrownBy {
            runBlocking {
                changed.resume(
                    context = WorkflowContext(workflowId = workflowId),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("definitionVersion='resume-v1'")
            .hasMessageContaining("definitionVersion='resume-v2'")
    }
    @Test
    fun `resume fails when stop policy changes without changing the definition version`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflowId = "stop-policy-mismatch-1"
        val original = workflow<ResumeState>(
            name = "stop-policy-mismatch",
            definitionVersion = "resume-v1",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build(
            stopPolicy = StopPolicy(
                maxStepExecutions = 2,
                maxParallelBranches = 3,
            ),
        ) { it.draft ?: error("draft must exist") }
        runBlocking {
            original.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = workflowId),
                persistence = persistence,
            )
        }
        val changed = workflow<ResumeState>(
            name = "stop-policy-mismatch",
            definitionVersion = "resume-v1",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build(
            stopPolicy = StopPolicy(
                maxStepExecutions = 3,
                maxParallelBranches = 3,
            ),
        ) { it.draft ?: error("draft must exist") }
        assertThatThrownBy {
            runBlocking {
                changed.resume(
                    context = WorkflowContext(workflowId = workflowId),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("different workflow definition digest")
    }
    @Test
    fun `resume fails when schedule changes without changing the definition version`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflowId = "schedule-mismatch-1"
        val original = workflow<ResumeState>(
            name = "schedule-mismatch",
            definitionVersion = "resume-v1",
        ) {
            schedule = TestWorkflowSchedule("0 9 * * 1")
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            original.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = workflowId),
                persistence = persistence,
            )
        }
        val changed = workflow<ResumeState>(
            name = "schedule-mismatch",
            definitionVersion = "resume-v1",
        ) {
            schedule = TestWorkflowSchedule("0 10 * * 1")
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        assertThatThrownBy {
            runBlocking {
                changed.resume(
                    context = WorkflowContext(workflowId = workflowId),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("different workflow definition digest")
    }

    @Test
    fun `resume fails when shell command fingerprint metadata changes without changing the definition version`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflowId = "shell-definition-mismatch-1"
        val original = workflow<ResumeState>(
            name = "shell-definition-mismatch",
            definitionVersion = "resume-v1",
        ) {
            shellStep(
                name = "deploy",
                definition = ShellCommandDefinition(
                    hasWorkdir = false,
                    envKeys = setOf("API_TOKEN"),
                ),
                command = { state, _ ->
                    ShellCommand(
                        command = listOf("sh", "-c", "echo ${state.request}"),
                        env = mapOf("API_TOKEN" to "secret"),
                    )
                },
                merge = { state, result, _ -> state.copy(draft = result.stdout.trim()) },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            original.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = workflowId),
                persistence = persistence,
            )
        }
        val changedWorkdir = Files.createTempDirectory("workflow-shell-definition")
        try {
            val changed = workflow<ResumeState>(
                name = "shell-definition-mismatch",
                definitionVersion = "resume-v1",
            ) {
                shellStep(
                    name = "deploy",
                    definition = ShellCommandDefinition(
                        hasWorkdir = true,
                        envKeys = setOf("OTHER_TOKEN"),
                    ),
                    command = { state, _ ->
                        ShellCommand(
                            command = listOf("sh", "-c", "echo ${state.request}"),
                            workdir = changedWorkdir.toString(),
                            env = mapOf("OTHER_TOKEN" to "secret"),
                        )
                    },
                    merge = { state, result, _ -> state.copy(draft = result.stdout.trim()) },
                )
            }.build { it.draft ?: error("draft must exist") }
            assertThatThrownBy {
                runBlocking {
                    changed.resume(
                        context = WorkflowContext(workflowId = workflowId),
                        persistence = persistence,
                    )
                }
            }
                .isInstanceOf(WorkflowResumeException::class.java)
                .hasMessageContaining("different workflow definition digest")
        } finally {
            Files.deleteIfExists(changedWorkdir)
        }
    }

    @Test
    fun `resume fails loudly when checkpoint is missing required workflow definition metadata`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<ResumeState>(
            name = "missing-definition-metadata",
            definitionVersion = "resume-v1",
        ) {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "missing-definition-metadata",
                    workflowId = "wf-missing-definition-metadata",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = ResumeStateCodec.encode(
                        ResumeState(request = "invoice-123"),
                    ),
                ),
            )
        }
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = WorkflowContext(workflowId = "wf-missing-definition-metadata"),
                    persistence = WorkflowPersistence(
                        checkpointStore = store,
                        stateCodec = ResumeStateCodec,
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("missing required workflow definition metadata")
    }
    @Test
    fun `engine backed ai step reuses fallback routing and observability`() {
        val primary = RecordingProvider("primary") {
            throw ProviderException(
                message = "rate limited",
                statusCode = 429,
                retryable = true,
            )
        }
        val fallback = RecordingProvider("fallback") {
            ModelResponse(content = "fallback plan")
        }
        val registry = ProviderRegistry.builder()
            .provider("primary", primary)
            .provider("fallback", fallback)
            .model("claude-sonnet-4-20250514", "primary")
            .fallbackProvider("claude-sonnet-4-20250514", "fallback")
            .build()
        val operationObserver = RecordingOperationObserver()
        val engine = TramaiEngine(
            providerRegistry = registry,
            operationObserver = operationObserver,
        )
        val planner = engine.create<EnginePlannerService>()
        val workflow = workflow<EnginePlanningState>("engine-backed-plan") {
            aiStep(
                name = "plan",
                input = { it.request },
                invoke = planner::plan,
                merge = { state, plan -> state.copy(plan = plan) },
            )
        }.build { it.plan ?: error("plan must exist") }
        val result = runBlocking {
            workflow.run(EnginePlanningState(request = "invoice-123"))
        }
        assertThat(result).isEqualTo("fallback plan")
        assertThat(primary.requests).hasSize(4)
        assertThat(fallback.requests).hasSize(1)
        assertThat(operationObserver.callRecords).hasSize(5)
        assertThat(operationObserver.callRecords.last().context.providerId).isEqualTo("fallback")
        assertThat(operationObserver.callRecords.flatMap { it.engineEvents })
            .anySatisfy {
                assertThat(it.name).isEqualTo("tramai.route.selected")
                assertThat(it.attributes["is_fallback"]).isEqualTo(true)
            }
    }
    @Test
    fun `engine backed ai step reuses caching across workflow runs`() {
        var calls = 0
        val provider = RecordingProvider("primary") {
            calls += 1
            ModelResponse(content = "cached-$calls")
        }
        val engine = TramaiEngine(
            provider = provider,
            responseCache = InMemoryOperationResponseCache(),
        )
        val summarizer = engine.create<CachedSummaryService>()
        val workflow = workflow<CachedSummaryState>("cached-summary") {
            aiStep(
                name = "summarize",
                input = { it.request },
                invoke = summarizer::summarize,
                merge = { state, summary -> state.copy(summary = summary) },
            )
        }.build { it.summary ?: error("summary must exist") }
        val first = runBlocking {
            workflow.run(CachedSummaryState(request = "tenant-a"))
        }
        val second = runBlocking {
            workflow.run(CachedSummaryState(request = "tenant-a"))
        }
        assertThat(first).isEqualTo("cached-1")
        assertThat(second).isEqualTo("cached-1")
        assertThat(provider.requests).hasSize(1)
    }
    @Test
    fun `resume continues from the last completed top level step`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
        )
        val context = WorkflowContext(workflowId = "resume-1")
        val executions = mutableListOf<String>()
        var failReviewOnce = true
        val workflow = workflow<ResumeState>("resume-top-level") {
            localStep(
                name = "draft",
                transform = { state, _ ->
                    executions += "draft"
                    state.copy(draft = "draft:${state.request}")
                },
            )
            localStep(
                name = "review",
                transform = { state, _ ->
                    executions += "review"
                    if (failReviewOnce) {
                        failReviewOnce = false
                        throw IllegalStateException("transient review failure")
                    }
                    state.copy(review = "review:${state.draft}")
                },
            )
            localStep(
                name = "finalize",
                transform = { state, _ ->
                    executions += "finalize"
                    state.copy(finalAnswer = "final:${state.review}")
                },
            )
        }.build { it.finalAnswer ?: error("final answer must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ResumeState(request = "invoice-123"),
                    context = context,
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("transient review failure")
        val checkpointAfterFailure = runBlocking {
            store.load("resume-top-level", "resume-1")
        }
        assertThat(checkpointAfterFailure).isNotNull
        assertThat(checkpointAfterFailure!!.nextStepIndex).isEqualTo(1)
        assertThat(checkpointAfterFailure.lastCompletedStepName).isEqualTo("draft")
        val result = runBlocking {
            workflow.resume(
                context = context,
                persistence = persistence,
            )
        }
        assertThat(result).isEqualTo("final:review:draft:invoice-123")
        assertThat(executions).containsExactly("draft", "review", "review", "finalize")
        assertThat(
            runBlocking { store.load("resume-top-level", "resume-1") },
        ).isNull()
    }
    @Test
    fun `delay step pauses and resumes after resume timestamp elapses`() {
        val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
        val store = InMemoryWorkflowCheckpointStore()
        val delayScheduler = RecordingDelayWakeupScheduler()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            delayWakeupScheduler = delayScheduler,
        )
        val context = WorkflowContext(workflowId = "delay-1")
        val observer = RecordingWorkflowObserver()
        val workflow = workflow<ResumeState>("delay-resume") {
            localStep("draft") { state, _ -> state.copy(draft = "draft:${state.request}") }
            delayStep("pause", 5, TimeUnit.SECONDS)
            localStep("finalize") { state, _ -> state.copy(finalAnswer = "final:${state.draft}") }
        }.build(clock = clock) { it.finalAnswer ?: error("final answer must exist") }

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ResumeState(request = "invoice-123"),
                    context = context,
                    observer = observer,
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowSuspendedException::class.java)
            .hasMessageContaining("pause")
        val paused = runBlocking { store.load("delay-resume", "delay-1") }
        assertThat(paused).isNotNull
        assertThat(paused!!.nextStepIndex).isEqualTo(1)
        assertThat(paused.lastCompletedStepName).isNull()
        assertThat(paused.metadata["tramai.workflow.delay.step"]).isEqualTo("pause")
        assertThat(paused.metadata["tramai.workflow.delay.resume_at_epoch_millis"])
            .isEqualTo("1777798805000")
        assertThat(delayScheduler.wakeups)
            .containsExactly(DelayWakeup("delay-1", "pause", Instant.parse("2026-05-03T09:00:05Z")))

        clock.instant = Instant.parse("2026-05-03T09:00:04Z")
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = context,
                    observer = observer,
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowSuspendedException::class.java)

        clock.instant = Instant.parse("2026-05-03T09:00:05Z")
        val result = runBlocking {
            workflow.resume(
                context = context,
                observer = observer,
                persistence = persistence,
            )
        }

        assertThat(result).isEqualTo("final:draft:invoice-123")
        assertThat(runBlocking { store.load("delay-resume", "delay-1") }).isNull()
        assertThat(observer.workflowEvents).contains(
            "tramai.workflow.delay.started",
            "tramai.workflow.delay.waiting",
            "tramai.workflow.delay.resumed",
        )
        assertThat(observer.completedSteps).contains("pause", "finalize")
    }
    @Test
    fun `zero delay step is a no op`() {
        val workflow = workflow<ResumeState>("zero-delay") {
            localStep("draft") { state, _ -> state.copy(draft = "draft:${state.request}") }
            delayStep("pause", 0, TimeUnit.SECONDS)
            localStep("finalize") { state, _ -> state.copy(finalAnswer = "final:${state.draft}") }
        }.build { it.finalAnswer ?: error("final answer must exist") }

        val result = runBlocking {
            workflow.run(ResumeState(request = "invoice-123"))
        }

        assertThat(result).isEqualTo("final:draft:invoice-123")
    }
    @Test
    fun `delay step cancellation returns control immediately`() {
        val clock = MutableClock(Instant.parse("2026-05-03T09:00:00Z"))
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<ResumeState>("delay-cancellation") {
            delayStep("pause", 1, TimeUnit.HOURS)
        }.build(clock = clock) { it.request }

        runBlocking {
            withTimeout(100) {
                try {
                    workflow.run(
                        initialState = ResumeState(request = "invoice-123"),
                        context = WorkflowContext(workflowId = "delay-cancel-1"),
                        persistence = WorkflowPersistence(
                            checkpointStore = store,
                            stateCodec = ResumeStateCodec,
                            delayWakeupScheduler = RecordingDelayWakeupScheduler(),
                        ),
                    )
                    fail("Expected delay workflow to suspend")
                } catch (_: WorkflowSuspendedException) {
                    Unit
                }
            }
        }
    }
    @Test
    fun `resume fails loudly when no checkpoint exists`() {
        val workflow = workflow<ResumeState>("missing-resume") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = state.request) },
            )
        }.build { it.draft ?: error("draft must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = WorkflowContext(workflowId = "missing"),
                    persistence = WorkflowPersistence(
                        checkpointStore = InMemoryWorkflowCheckpointStore(),
                        stateCodec = ResumeStateCodec,
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("No checkpoint exists")
    }
    @Test
    fun `checkpoint can be retained after successful completion`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<ResumeState>("retained-checkpoint") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
            localStep(
                name = "finalize",
                transform = { state, _ -> state.copy(finalAnswer = state.draft) },
            )
        }.build { it.finalAnswer ?: error("final answer must exist") }
        val result = runBlocking {
            workflow.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = "retained"),
                persistence = WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = ResumeStateCodec,
                    deleteCheckpointOnCompletion = false,
                ),
            )
        }
        val checkpoint = runBlocking {
            store.load("retained-checkpoint", "retained")
        }
        assertThat(result).isEqualTo("draft:invoice-123")
        assertThat(checkpoint).isNotNull
        assertThat(checkpoint!!.nextStepIndex).isEqualTo(2)
        assertThat(checkpoint.lastCompletedStepName).isEqualTo("finalize")
        assertThat(checkpoint.revision).isEqualTo(3)
    }
    @Test
    fun `run fails loudly when a checkpoint already exists for the same workflow id`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflow = workflow<ResumeState>("run-existing-checkpoint") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        runBlocking {
            workflow.run(
                initialState = ResumeState(request = "invoice-123"),
                context = WorkflowContext(workflowId = "wf-existing"),
                persistence = persistence,
            )
        }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ResumeState(request = "invoice-456"),
                    context = WorkflowContext(workflowId = "wf-existing"),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            .hasMessageContaining("already exists")
            .hasMessageContaining("workflowId='wf-existing'")
    }
    @Test
    fun `completion checkpoint delete conflict fails loudly and still releases the lease`() {
        val backingStore = InMemoryWorkflowCheckpointStore()
        val checkpointStore = DeleteConflictCheckpointStore(
            delegate = backingStore,
            failOnDeleteWorkflowName = "completion-delete-conflict",
        )
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val persistence = WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = ResumeStateCodec,
            leaseStore = leaseStore,
            leasePolicy = WorkflowLeasePolicy(
                ownerId = "node-a",
                leaseDurationMillis = 5_000,
            ),
        )
        val workflow = workflow<ResumeState>("completion-delete-conflict") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
        }.build { it.draft ?: error("draft must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ResumeState(request = "invoice-123"),
                    context = WorkflowContext(workflowId = "wf-delete-conflict"),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            .hasMessageContaining("simulated completion delete conflict")
        assertThat(
            runBlocking { leaseStore.currentLease("completion-delete-conflict", "wf-delete-conflict") },
        ).isNull()
        assertThat(
            runBlocking { backingStore.load("completion-delete-conflict", "wf-delete-conflict") },
        ).isNotNull
    }
    @Test
    fun `resume preserves step execution budget`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ResumeStateCodec,
        )
        val context = WorkflowContext(workflowId = "budget-1")
        var failReviewOnce = true
        val workflow = workflow<ResumeState>("resume-budget") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(draft = "draft:${state.request}") },
            )
            localStep(
                name = "review",
                transform = { state, _ ->
                    if (failReviewOnce) {
                        failReviewOnce = false
                        throw IllegalStateException("retry later")
                    }
                    state.copy(review = "review:${state.draft}")
                },
            )
            localStep(
                name = "finalize",
                transform = { state, _ -> state.copy(finalAnswer = "final:${state.review}") },
            )
        }.build(
            stopPolicy = StopPolicy(maxStepExecutions = 2),
        ) { it.finalAnswer ?: error("final answer must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ResumeState(request = "invoice-123"),
                    context = context,
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = context,
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(WorkflowLimitExceededException::class.java)
            .hasMessageContaining("maxStepExecutions=2")
    }
    @Test
    fun `checkpoint store increments revision on each successful save`() {
        val store = InMemoryWorkflowCheckpointStore()
        val first = runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "revisioned",
                    workflowId = "wf-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "state-0",
                ),
            )
        }
        val second = runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "revisioned",
                    workflowId = "wf-1",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "draft",
                    statePayload = "state-1",
                ),
                expectedRevision = first.revision,
            )
        }
        assertThat(first.revision).isEqualTo(1)
        assertThat(second.revision).isEqualTo(2)
        assertThat(
            runBlocking { store.load("revisioned", "wf-1") }!!.revision,
        ).isEqualTo(2)
    }
    @Test
    fun `checkpoint store rejects stale save revisions`() {
        val store = InMemoryWorkflowCheckpointStore()
        val first = runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "conflict",
                    workflowId = "wf-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "state-0",
                ),
            )
        }
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "conflict",
                    workflowId = "wf-1",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "draft",
                    statePayload = "state-1",
                ),
                expectedRevision = first.revision,
            )
        }
        assertThatThrownBy {
            runBlocking {
                store.save(
                    WorkflowCheckpoint(
                        workflowName = "conflict",
                        workflowId = "wf-1",
                        nextStepIndex = 2,
                        stepExecutions = 2,
                        lastCompletedStepName = "review",
                        statePayload = "state-2",
                    ),
                    expectedRevision = first.revision,
                )
            }
        }
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            .hasMessageContaining("expected revision 1")
    }
    @Test
    fun `checkpoint store rejects stale delete revisions`() {
        val store = InMemoryWorkflowCheckpointStore()
        val first = runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "delete-conflict",
                    workflowId = "wf-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "state-0",
                ),
            )
        }
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = "delete-conflict",
                    workflowId = "wf-1",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "draft",
                    statePayload = "state-1",
                ),
                expectedRevision = first.revision,
            )
        }
        assertThatThrownBy {
            runBlocking {
                store.delete(
                    workflowName = "delete-conflict",
                    workflowId = "wf-1",
                    expectedRevision = first.revision,
                )
            }
        }
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            .hasMessageContaining("expected revision 1")
    }
}
private data class PlanningState(
    val request: String,
    val plan: ExecutionPlan? = null,
    val results: List<WorkerResult> = emptyList(),
    val review: ReviewResult? = null,
    val finalAnswer: String? = null,
)
private data class ExecutionPlan(
    val items: List<String>,
)
private data class WorkerResult(
    val item: String,
    val content: String,
)
private data class ReviewInput(
    val results: List<WorkerResult>,
)
private data class ReviewResult(
    val approved: Boolean,
    val summary: String,
)
private data class FinalizeInput(
    val review: ReviewResult,
)
@AiService
private interface PlannerService {
    @Operation(
        prompt = "Create an execution plan",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun plan(request: String): ExecutionPlan
}
@AiService
private interface WorkerService {
    @Operation(
        prompt = "Execute one planned item",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun execute(item: String): WorkerResult
}
@AiService
private interface ReviewerService {
    @Operation(
        prompt = "Review the worker results",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun review(input: ReviewInput): ReviewResult
}
@AiService
private interface FinalizerService {
    @Operation(
        prompt = "Finalize the reviewed result",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun finalize(input: FinalizeInput): String
}
private class FakePlannerService : PlannerService {
    override suspend fun plan(request: String): ExecutionPlan = ExecutionPlan(
        items = listOf("extract:$request", "summarize:$request"),
    )
}
private class FakeWorkerService : WorkerService {
    override suspend fun execute(item: String): WorkerResult = WorkerResult(
        item = item,
        content = item,
    )
}
private class FakeReviewerService : ReviewerService {
    override suspend fun review(input: ReviewInput): ReviewResult = ReviewResult(
        approved = true,
        summary = input.results.joinToString(separator = "|") { it.content },
    )
}
private class FakeFinalizerService : FinalizerService {
    override suspend fun finalize(input: FinalizeInput): String = if (input.review.approved) {
        "APPROVED:${input.review.summary}"
    } else {
        "REJECTED:${input.review.summary}"
    }
}
private data class RoutingState(
    val request: String,
    val route: RouteDecision? = null,
    val specialistResult: String? = null,
    val validated: ValidatedResponse? = null,
)
private data class ApprovalState(
    val request: String,
    val approved: Boolean,
    val draft: String? = null,
    val finalized: Boolean = false,
)
private data class RouteDecision(
    val specialist: String,
)
private data class BillingRequest(
    val text: String,
)
private data class SupportRequest(
    val text: String,
)
private data class ValidationInput(
    val text: String,
)
private data class ValidatedResponse(
    val normalizedText: String,
    val accepted: Boolean,
)
@AiService
private interface RouterService {
    @Operation(
        prompt = "Route the request",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun route(request: String): RouteDecision
}
@AiService
private interface BillingSpecialistService {
    @Operation(
        prompt = "Analyze a billing request",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(request: BillingRequest): String
}
@AiService
private interface SupportSpecialistService {
    @Operation(
        prompt = "Analyze a support request",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(request: SupportRequest): String
}
@AiService
private interface ValidationService {
    @Operation(
        prompt = "Validate the specialist output",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun validate(input: ValidationInput): ValidatedResponse
}
private class FakeRouterService : RouterService {
    override suspend fun route(request: String): RouteDecision = if (request.contains("invoice")) {
        RouteDecision("billing")
    } else {
        RouteDecision("support")
    }
}
private class FakeBillingSpecialistService : BillingSpecialistService {
    override suspend fun analyze(request: BillingRequest): String = "billing:${request.text}"
}
private class FakeSupportSpecialistService : SupportSpecialistService {
    override suspend fun analyze(request: SupportRequest): String = "support:${request.text}"
}
private class FakeValidationService : ValidationService {
    override suspend fun validate(input: ValidationInput): ValidatedResponse = ValidatedResponse(
        normalizedText = input.text,
        accepted = input.text.isNotBlank(),
    )
}
private data class CandidateState(
    val prompt: String,
    val candidates: List<Candidate> = emptyList(),
    val winner: Candidate? = null,
)
private data class EnginePlanningState(
    val request: String,
    val plan: String? = null,
)
private data class CachedSummaryState(
    val request: String,
    val summary: String? = null,
)
private data class ResumeState(
    val request: String,
    val draft: String? = null,
    val review: String? = null,
    val finalAnswer: String? = null,
)
private data class Candidate(
    val generatorName: String,
    val content: String,
)
private data class JudgeInput(
    val candidates: List<Candidate>,
)
private data class CandidateJob(
    val generator: CandidateGenerator,
    val prompt: String,
)
@AiService
private interface JudgeService {
    @Operation(
        prompt = "Choose the strongest candidate",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun choose(input: JudgeInput): Candidate
}
@AiService
private interface EnginePlannerService {
    @Operation(
        prompt = "Create a plan",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun plan(request: String): String
}
@AiService
private interface CachedSummaryService {
    @Operation(
        prompt = "Return a cached summary",
        model = "claude-sonnet-4-20250514",
        cacheable = true,
        cacheTtlMillis = 60_000,
    )
    suspend fun summarize(request: String): String
}
private class CandidateGenerator(
    private val name: String,
) {
    suspend fun generate(prompt: String): Candidate = Candidate(
        generatorName = name,
        content = "$name:$prompt",
    )
}
private class FakeJudgeService : JudgeService {
    override suspend fun choose(input: JudgeInput): Candidate = input.candidates.maxBy { it.generatorName }
}
private class RecordingProvider(
    private val name: String,
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()
    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return responder(request)
    }
    override fun providerId(): String = name
}
private object ResumeStateCodec : WorkflowStateCodec<ResumeState> {
    override fun encode(state: ResumeState): String = listOf(
        state.request,
        state.draft.orEmpty(),
        state.review.orEmpty(),
        state.finalAnswer.orEmpty(),
    ).joinToString("|")
    override fun decode(payload: String): ResumeState {
        val parts = payload.split("|", limit = 4)
        return ResumeState(
            request = parts[0],
            draft = parts.getOrNull(1).orEmpty().ifBlank { null },
            review = parts.getOrNull(2).orEmpty().ifBlank { null },
            finalAnswer = parts.getOrNull(3).orEmpty().ifBlank { null },
        )
    }
}
private data class DelayWakeup(
    val runId: String,
    val stepId: String,
    val resumeAt: Instant,
)
private class RecordingDelayWakeupScheduler : WorkflowDelayWakeupScheduler {
    val wakeups = mutableListOf<DelayWakeup>()
    override suspend fun scheduleDelayWakeup(
        runId: String,
        stepId: String,
        resumeAt: Instant,
    ) {
        wakeups += DelayWakeup(runId, stepId, resumeAt)
    }
}
private class MutableClock(
    var instant: Instant,
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
}
private class DeleteConflictCheckpointStore(
    private val delegate: WorkflowCheckpointStore,
    private val failOnDeleteWorkflowName: String,
) : WorkflowCheckpointStore {
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = delegate.load(workflowName, workflowId)
    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = delegate.save(checkpoint, expectedRevision)
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        if (workflowName == failOnDeleteWorkflowName) {
            throw WorkflowCheckpointConflictException(
                "simulated completion delete conflict for workflow '$workflowName' and workflowId='$workflowId'",
            )
        }
        delegate.delete(workflowName, workflowId, expectedRevision)
    }
}
private class RecordingWorkflowObserver : WorkflowObserver {
    val startedSteps = mutableListOf<String>()
    val completedSteps = mutableListOf<String>()
    val failedSteps = mutableListOf<String>()
    val workflowEvents = mutableListOf<String>()
    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        workflowEvents += name
    }
    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        startedSteps += stepName
    }
    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        completedSteps += stepName
    }
    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        failedSteps += stepName
    }
}
private data class TestWorkflowSchedule(
    override val expression: String,
) : WorkflowScheduleDefinition {
    override val kind: String = "test"
    override val zoneId: ZoneId = ZoneId.of("UTC")
    override fun validate() = Unit
}
