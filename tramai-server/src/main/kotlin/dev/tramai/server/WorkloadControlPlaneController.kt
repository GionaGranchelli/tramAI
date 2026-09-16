package dev.tramai.server

import dev.tramai.controlplane.ConfigurationFingerprint
import dev.tramai.controlplane.LifecycleTransitionOutcome
import dev.tramai.controlplane.MetadataUpdateOutcome
import dev.tramai.controlplane.RegisterOutcome
import dev.tramai.controlplane.RegisteredWorkload
import dev.tramai.controlplane.WorkloadControlPlaneCommands
import dev.tramai.controlplane.WorkloadControlPlaneQueries
import dev.tramai.controlplane.WorkloadLifecycleState
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** Registration declaration for a new workload deployment. */
data class WorkloadRegistrationCommand(
    val workloadId: String,
    val configurationId: String,
    val configurationVersion: String,
    val environmentId: String,
    val deploymentId: String,
    val configurationFingerprint: String,
    val owner: String,
    val purpose: String,
)

/** Metadata update payload; the expected version travels in `If-Match`. */
data class WorkloadMetadataCommand(
    val owner: String,
    val purpose: String,
)

/** Lifecycle command payload; the expected version travels in `If-Match`. */
data class WorkloadLifecycleCommand(
    val target: WorkloadLifecycleState,
)

/**
 * Authoritative read model.
 *
 * Deliberately NOT [RegisteredWorkload] and deliberately without
 * [RegisteredWorkload.configurationFingerprint]: the fingerprint is the witness that binds a
 * configuration to a registration, and a witness has no business being handed back to a client
 * (or being mistaken for something a client may present back to the authority).
 */
data class WorkloadRegistrationResponse(
    val workloadId: String,
    val configurationId: String,
    val configurationVersion: String,
    val environmentId: String,
    val deploymentId: String,
    val owner: String,
    val purpose: String,
    val lifecycle: WorkloadLifecycleState,
    val stateVersion: Long,
    val consistency: String,
) {
    companion object {
        fun from(
            registration: RegisteredWorkload,
            consistency: String = "AUTHORITATIVE",
        ): WorkloadRegistrationResponse =
            WorkloadRegistrationResponse(
                workloadId = registration.identity.workloadId.value,
                configurationId = registration.identity.configuration.id.value,
                configurationVersion = registration.identity.configuration.version.value,
                environmentId = registration.identity.environmentId.value,
                deploymentId = registration.identity.deploymentId.value,
                owner = registration.metadata.owner,
                purpose = registration.metadata.purpose,
                lifecycle = registration.lifecycle,
                stateVersion = registration.stateVersion.value,
                consistency = consistency,
            )
    }
}

/**
 * HTTP adapter for the public control-plane authority contract (0.7.1e).
 *
 * This class maps transport to contract and nothing else. It never reads the authority's present
 * state to decide anything, never compares versions itself and never mutates: the precondition is
 * parsed here, handed to the command as `expectedVersion`, and the AUTHORITY decides. Reproducing
 * `find -> compare -> mutate` in the adapter would re-create a second authority.
 *
 * Disabled by default (`tramai.control-plane.http.enabled=true` to enable): this surface mutates
 * authoritative state, and the safe-exposure model is 0.7.1f's subject, not this slice's.
 */
@RestController
@ConditionalOnProperty(prefix = "tramai.control-plane.http", name = ["enabled"], havingValue = "true")
internal class WorkloadControlPlaneController(
    commands: ObjectProvider<WorkloadControlPlaneCommands>,
    queries: ObjectProvider<WorkloadControlPlaneQueries>,
) {
    private val commands: WorkloadControlPlaneCommands =
        commands.ifAvailable
            ?: error("tramai.control-plane.http.enabled requires a WorkloadControlPlaneCommands bean")
    private val queries: WorkloadControlPlaneQueries =
        queries.ifAvailable
            ?: error("tramai.control-plane.http.enabled requires a WorkloadControlPlaneQueries bean")

    // ── Commands ────────────────────────────────────────────────────

    @PostMapping(BASE_PATH)
    fun register(
        @RequestBody command: WorkloadRegistrationCommand,
    ): ResponseEntity<Any> =
        runBlocking {
            val outcome =
                commands.register(
                    command.toIdentity(),
                    command.fingerprint(),
                    command.toMetadata(),
                )
            when (outcome) {
                is RegisterOutcome.Created -> {
                    createdResponse(outcome.registration)
                }

                is RegisterOutcome.AlreadyRegistered -> {
                    okResponse(outcome.registration)
                }

                is RegisterOutcome.Rejected -> {
                    problemResponse(
                        status = HttpStatus.CONFLICT,
                        title = "Workload registration conflicts with existing state",
                        detail = outcome.reason.toString(),
                    )
                }
            }
        }

    @PutMapping("$BASE_PATH/{workloadId}/environments/{environmentId}/deployments/{deploymentId}/metadata")
    fun updateMetadata(
        @PathVariable workloadId: String,
        @PathVariable environmentId: String,
        @PathVariable deploymentId: String,
        @RequestHeader(name = IF_MATCH, required = false) ifMatch: String?,
        @RequestBody command: WorkloadMetadataCommand,
    ): ResponseEntity<Any> {
        val precondition = parseWorkloadPrecondition(ifMatch)
        if (precondition !is WorkloadPrecondition.Expected) return preconditionFailureResponse(precondition)
        return runBlocking {
            when (
                val outcome =
                    commands.updateMetadata(
                        WorkloadId(workloadId),
                        EnvironmentId(environmentId),
                        DeploymentId(deploymentId),
                        precondition.version,
                        WorkloadMetadata(command.owner, command.purpose),
                    )
            ) {
                is MetadataUpdateOutcome.Applied -> okResponse(outcome.registration)
                is MetadataUpdateOutcome.Unchanged -> okResponse(outcome.registration)
                is MetadataUpdateOutcome.Stale -> preconditionFailed(outcome.currentVersion, outcome.expectedVersion)
                MetadataUpdateOutcome.NotFound -> notFoundResponse()
            }
        }
    }

    @PutMapping("$BASE_PATH/{workloadId}/environments/{environmentId}/deployments/{deploymentId}/lifecycle")
    fun transitionLifecycle(
        @PathVariable workloadId: String,
        @PathVariable environmentId: String,
        @PathVariable deploymentId: String,
        @RequestHeader(name = IF_MATCH, required = false) ifMatch: String?,
        @RequestBody command: WorkloadLifecycleCommand,
    ): ResponseEntity<Any> {
        val precondition = parseWorkloadPrecondition(ifMatch)
        if (precondition !is WorkloadPrecondition.Expected) return preconditionFailureResponse(precondition)
        return runBlocking {
            when (
                val outcome =
                    commands.transitionLifecycle(
                        WorkloadId(workloadId),
                        EnvironmentId(environmentId),
                        DeploymentId(deploymentId),
                        precondition.version,
                        command.target,
                    )
            ) {
                is LifecycleTransitionOutcome.Applied -> {
                    okResponse(outcome.registration)
                }

                is LifecycleTransitionOutcome.Unchanged -> {
                    okResponse(outcome.registration)
                }

                is LifecycleTransitionOutcome.Stale -> {
                    preconditionFailed(outcome.currentVersion, outcome.expectedVersion)
                }

                is LifecycleTransitionOutcome.InvalidTransition -> {
                    problemResponse(
                        status = HttpStatus.CONFLICT,
                        title = "Lifecycle transition is not permitted",
                        detail = "Cannot transition from ${outcome.from} to ${outcome.to}",
                    )
                }

                LifecycleTransitionOutcome.NotFound -> {
                    notFoundResponse()
                }
            }
        }
    }

    // ── Queries ─────────────────────────────────────────────────────

    @GetMapping("$BASE_PATH/{workloadId}/environments/{environmentId}/deployments/{deploymentId}")
    fun readAuthoritative(
        @PathVariable workloadId: String,
        @PathVariable environmentId: String,
        @PathVariable deploymentId: String,
    ): ResponseEntity<Any> =
        runBlocking {
            val read =
                queries.authoritative(
                    WorkloadId(workloadId),
                    EnvironmentId(environmentId),
                    DeploymentId(deploymentId),
                ) ?: return@runBlocking notFoundResponse()
            okResponse(read)
        }
}

private fun WorkloadRegistrationCommand.toIdentity(): WorkloadDeploymentIdentity =
    WorkloadDeploymentIdentity(
        workloadId = WorkloadId(workloadId),
        configuration =
            WorkloadConfigurationIdentity(
                id = ConfigurationId(configurationId),
                version = ConfigurationVersion(configurationVersion),
            ),
        environmentId = EnvironmentId(environmentId),
        deploymentId = DeploymentId(deploymentId),
    )

private fun WorkloadRegistrationCommand.fingerprint() = ConfigurationFingerprint(configurationFingerprint)

private fun WorkloadRegistrationCommand.toMetadata(): WorkloadMetadata = WorkloadMetadata(owner, purpose)

private const val BASE_PATH = "/control-plane/workloads"
private const val IF_MATCH = "If-Match"
