package dev.tramai.controlplane

import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

class WorkloadExposureModelTest {
    @Test
    fun `exposure has exactly the approved four properties`() {
        assertThat(getters(WorkloadExposure::class.java)).isEqualTo(
            mapOf(
                "identity" to WorkloadDeploymentIdentity::class.java,
                "metadata" to WorkloadMetadata::class.java,
                "lifecycle" to WorkloadLifecycleState::class.java,
                "stateVersion" to WorkloadStateVersion::class.java,
            ),
        )
    }

    @Test
    fun `mapper excludes the authority fingerprint`() {
        val record =
            WorkloadRegistrationFixtures.registration(
                configurationFingerprint = ConfigurationFingerprint("sha256:SENTINEL-0.7.1f-FINGERPRINT"),
            )
        val exposure = WorkloadExposure.from(record)

        assertThat(exposure.toString()).doesNotContain("SENTINEL-0.7.1f-FINGERPRINT")
        assertThat(WorkloadExposure::class.java.declaredFields)
            .noneMatch { ConfigurationFingerprint::class.java.isAssignableFrom(it.type) }
    }

    @Test
    fun `query and command type graphs are exact and safe`() {
        val output = reachableTypes(*ports, output = true)
        val input = reachableTypes(*ports, output = false)
        assertPortTypes(output, input)
    }

    private val ports = arrayOf(WorkloadControlPlaneQueries::class.java, WorkloadControlPlaneCommands::class.java)

    private val approvedOutputTypes =
        setOf(
            ClassifiedRead::class.java,
            WorkloadExposure::class.java,
            WorkloadDeploymentIdentity::class.java,
            WorkloadConfigurationIdentity::class.java,
            WorkloadId::class.java,
            ConfigurationId::class.java,
            ConfigurationVersion::class.java,
            EnvironmentId::class.java,
            DeploymentId::class.java,
            WorkloadMetadata::class.java,
            WorkloadLifecycleState::class.java,
            WorkloadStateVersion::class.java,
            QueryConsistency::class.java,
            RegisterOutcome::class.java,
            RegisterOutcome.Created::class.java,
            RegisterOutcome.AlreadyRegistered::class.java,
            RegisterOutcome.Rejected::class.java,
            RegistrationConflictReason::class.java,
            MetadataUpdateOutcome::class.java,
            MetadataUpdateOutcome.Applied::class.java,
            MetadataUpdateOutcome.Unchanged::class.java,
            MetadataUpdateOutcome.Stale::class.java,
            MetadataUpdateOutcome.NotFound::class.java,
            LifecycleTransitionOutcome::class.java,
            LifecycleTransitionOutcome.Applied::class.java,
            LifecycleTransitionOutcome.Unchanged::class.java,
            LifecycleTransitionOutcome.InvalidTransition::class.java,
            LifecycleTransitionOutcome.Stale::class.java,
            LifecycleTransitionOutcome.NotFound::class.java,
        )

    private val approvedInputTypes =
        setOf(
            WorkloadDeploymentIdentity::class.java,
            WorkloadConfigurationIdentity::class.java,
            WorkloadId::class.java,
            ConfigurationId::class.java,
            ConfigurationVersion::class.java,
            EnvironmentId::class.java,
            DeploymentId::class.java,
            ConfigurationFingerprint::class.java,
            WorkloadMetadata::class.java,
            WorkloadStateVersion::class.java,
            WorkloadLifecycleState::class.java,
        )

    private fun assertPortTypes(
        output: Set<Class<*>>,
        input: Set<Class<*>>,
    ) {
        assertThat(output).isEqualTo(approvedOutputTypes)
        assertThat(input).isEqualTo(approvedInputTypes)
        assertThat(output).doesNotContain(RegisteredWorkload::class.java, ConfigurationFingerprint::class.java)
        assertThat(input).doesNotContain(
            RegisteredWorkload::class.java,
            Map::class.java,
            Any::class.java,
        )
        (output + input).forEach { type ->
            assertThat(getters(type).values).noneMatch {
                it == Any::class.java || Map::class.java.isAssignableFrom(it) ||
                    it.simpleName == "JsonNode"
            }
            assertThat(type.packageName).isIn("dev.tramai.controlplane", "dev.tramai.core.identity")
        }
    }

    @Test
    fun `classified read exposes safe model and rejects version contradiction`() {
        val exposure = WorkloadExposure.from(WorkloadRegistrationFixtures.registration())
        assertThat(ClassifiedRead(exposure, QueryConsistency.AUTHORITATIVE, exposure.stateVersion).exposure)
            .isEqualTo(exposure)
        assertThatIllegalArgumentException().isThrownBy {
            ClassifiedRead(
                exposure,
                QueryConsistency.AUTHORITATIVE,
                WorkloadStateVersion(exposure.stateVersion.value + 1),
            )
        }
    }

    @Test
    fun `metadata has exactly owner and purpose`() {
        assertThat(getters(WorkloadMetadata::class.java)).isEqualTo(
            mapOf("owner" to String::class.java, "purpose" to String::class.java),
        )
    }

    @Test
    fun `authoritative and projection reads expose identical safe shape`() {
        runBlocking {
            val store = InMemoryWorkloadRegistrationStore()
            val authority = WorkloadRegistrationAuthority(store)
            val record = WorkloadRegistrationFixtures.registration()
            store.create(record)
            val authoritative =
                authority.authoritative(
                    record.identity.workloadId,
                    record.identity.environmentId,
                    record.identity.deploymentId,
                )
            val projection =
                authority.projection(
                    record.identity.workloadId,
                    record.identity.environmentId,
                    record.identity.deploymentId,
                )
            assertThat(authoritative!!.exposure).isEqualTo(projection!!.exposure)
            assertThat(authoritative.exposure::class.java).isEqualTo(WorkloadExposure::class.java)
        }
    }

    @Test
    fun `read consistency absence and projection lag semantics remain intact`() {
        runBlocking {
            val store = InMemoryWorkloadRegistrationStore()
            val authority = WorkloadRegistrationAuthority(store)
            val identity = WorkloadRegistrationFixtures.identity(deployment = "exposure-lag")
            assertThat(
                authority.authoritative(identity.workloadId, identity.environmentId, identity.deploymentId),
            ).isNull()
            authority.register(
                identity,
                WorkloadRegistrationFixtures.fingerprint(),
                WorkloadRegistrationFixtures.metadata(),
            )
            val observed =
                authority.projection(identity.workloadId, identity.environmentId, identity.deploymentId)!!
            val applied =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    observed.observedVersion,
                    WorkloadMetadata("new-owner", "purpose"),
                )
            assertThat(applied).isInstanceOf(MetadataUpdateOutcome.Applied::class.java)
            val late =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    observed.observedVersion,
                    WorkloadMetadata("late", "purpose"),
                )
            assertThat(late).isInstanceOf(MetadataUpdateOutcome.Stale::class.java)
            assertThat(observed.consistency).isEqualTo(QueryConsistency.PROJECTION)
        }
    }

    private fun reachableTypes(
        vararg ports: Class<*>,
        output: Boolean,
    ): Set<Class<*>> {
        val seen = linkedSetOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        ports.flatMap { it.methods.toList() }.forEach { method ->
            if (output) {
                // Every port method is a suspend fun whose declared return type survives only in the
                // synthetic Continuation parameter's generic signature. If it cannot be recovered the
                // walk would silently skip that method, so fail loudly instead of falling back to the
                // erased `Object` return type (review finding on the 0.7.1f discriminators).
                queue.add(
                    suspendReturn(method)
                        ?: error(
                            "port method '${method.name}' has no recoverable suspend return type; " +
                                "the type-graph walk would silently skip it",
                        ),
                )
            } else {
                method.parameterTypes.filterNot { it.name == "kotlin.coroutines.Continuation" }.forEach(queue::add)
            }
        }
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (
                type.packageName !in setOf("dev.tramai.controlplane", "dev.tramai.core.identity") ||
                !seen.add(type)
            ) {
                continue
            }
            if (type.simpleName.endsWith("Outcome") || type.simpleName.endsWith("Result")) {
                type.declaredClasses.forEach(queue::add)
            }
            getters(type).values.forEach(queue::add)
        }
        return seen
    }

    private fun suspendReturn(method: Method): Class<*>? =
        method.genericParameterTypes
            .lastOrNull()
            ?.let { it as? ParameterizedType }
            ?.actualTypeArguments
            ?.singleOrNull()
            ?.let { (it as? java.lang.reflect.WildcardType)?.lowerBounds?.singleOrNull() ?: it }
            ?.let { it as? Class<*> }

    private fun getters(type: Class<*>): Map<String, Class<*>> =
        type.declaredMethods
            .filter {
                it.parameterCount == 0 &&
                    (it.name.startsWith("get") || it.name.startsWith("is")) &&
                    it.name !in setOf("getClass")
            }.associate {
                it.name
                    .removePrefix("get")
                    .removePrefix("is")
                    .replaceFirstChar(Char::lowercase) to it.returnType
            }
}
