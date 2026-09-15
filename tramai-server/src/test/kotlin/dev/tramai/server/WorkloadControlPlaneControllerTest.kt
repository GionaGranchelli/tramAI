package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.controlplane.MetadataUpdateOutcome
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore as InMemoryStore

/**
 * The HTTP concurrency contract over the public control-plane authority contract (0.7.1e).
 *
 * These tests pin the transport mapping AND the structural rule behind it: the adapter maps
 * outcomes, it never reproduces `find -> compare -> mutate`. The strongest discriminator here is
 * that a rejected precondition leaves authoritative state untouched — not merely that the response
 * has the right status code.
 */
@SpringBootTest(
    classes = [TramaiServerApplication::class, WorkloadControlPlaneControllerTest.Beans::class],
    properties = ["tramai.control-plane.http.enabled=true"],
)
@AutoConfigureMockMvc
class WorkloadControlPlaneControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val authority: WorkloadRegistrationAuthority,
    ) {
        // Kotlin classes are final, so CGLIB proxying is unavailable: mirror the production
        // configuration convention and turn it off explicitly.
        @TestConfiguration(proxyBeanMethods = false)
        internal class Beans {
            @Bean
            internal fun workloadRegistrationStore() = InMemoryStore()

            @Bean
            internal fun registrationAuthority(store: InMemoryStore) = WorkloadRegistrationAuthority(store)
        }

        // ── Reads ───────────────────────────────────────────────────────

        @Test
        fun `GET returns the authoritative record with an ETag derived from its state version`() {
            val scope = register("read-1")
            advanceMetadata(scope, 1)

            mockMvc
                .get(path(scope))
                .andExpect {
                    status { isOk() }
                    header { string("ETag", "\"2\"") }
                }.andReturn()
                .response
                .contentAsString
                .let { body ->
                    val json = objectMapper.readTree(body)
                    assertThat(json.get("stateVersion").asLong()).isEqualTo(2L)
                    assertThat(json.get("consistency").asText()).isEqualTo("AUTHORITATIVE")
                    // the configuration witness never leaves the authority
                    assertThat(json.has("configurationFingerprint")).isFalse()
                }
        }

        @Test
        fun `GET on an unregistered scope is 404`() {
            mockMvc
                .get("/control-plane/workloads/nope/environments/e/deployments/d")
                .andExpect { status { isNotFound() } }
        }

        // ── Precondition forms ──────────────────────────────────────────

        @Test
        fun `a matching precondition applies the command exactly once`() {
            val scope = register("apply-1")

            mockMvc
                .put(path(scope, "metadata")) {
                    header("If-Match", "\"1\"")
                    contentType = MediaType.APPLICATION_JSON
                    content = metadata("New Owner")
                }.andExpect {
                    status { isOk() }
                    header { string("ETag", "\"2\"") }
                }

            assertThat(currentVersion(scope)).isEqualTo(WorkloadStateVersion(2))
            assertThat(currentOwner(scope)).isEqualTo("New Owner")
        }

        @Test
        fun `a stale precondition is 412 with both versions and the current ETag`() {
            val scope = register("stale-1")
            advanceMetadata(scope, 1) // authoritative version is now 2

            val response =
                mockMvc
                    .put(path(scope, "metadata")) {
                        header("If-Match", "\"1\"")
                        contentType = MediaType.APPLICATION_JSON
                        content = metadata("Stale Writer")
                    }.andExpect {
                        status { isPreconditionFailed() }
                        header { string("ETag", "\"2\"") }
                    }.andReturn()
                    .response

            val problem = objectMapper.readTree(response.contentAsString)
            assertThat(problem.get("expectedVersion").asLong()).isEqualTo(1L)
            assertThat(problem.get("currentVersion").asLong()).isEqualTo(2L)

            // rejected means untouched, not "reported as rejected"
            assertThat(currentVersion(scope)).isEqualTo(WorkloadStateVersion(2))
            assertThat(currentOwner(scope)).isNotEqualTo("Stale Writer")
        }

        @Test
        fun `a missing precondition is 428 and changes nothing`() {
            val scope = register("missing-1")

            mockMvc
                .put(path(scope, "metadata")) {
                    contentType = MediaType.APPLICATION_JSON
                    content = metadata("Unconditioned Writer")
                }.andExpect { status { isPreconditionRequired() } }

            assertThat(currentVersion(scope)).isEqualTo(WorkloadStateVersion.INITIAL)
            assertThat(currentOwner(scope)).isNotEqualTo("Unconditioned Writer")
        }

        @Test
        fun `unsupported precondition forms are 400 and mutate nothing`() {
            val scope = register("form-1")
            val ownerBefore = currentOwner(scope)
            val unsupported = listOf("*", "W/\"1\"", "\"1\", \"2\"", "abc", "1")
            val observed = mutableMapOf<String, String>()

            unsupported.forEach { form ->
                val response =
                    mockMvc
                        .put(path(scope, "metadata")) {
                            header("If-Match", form)
                            contentType = MediaType.APPLICATION_JSON
                            // a JSON-safe body: several unsupported forms contain quotes, and the
                            // precondition must be the only thing under test here
                            content = metadata("Unconditioned Writer")
                        }.andReturn()
                        .response
                observed[form] = "${response.status} :: ${response.contentAsString.take(200)}"
            }

            assertThat(observed.values.map { it.substringBefore(" :: ") })
                .describedAs("every unsupported form must be 400, observed: $observed")
                .allSatisfy { status -> assertThat(status).isEqualTo("400") }

            // `*` in particular must not authorize anything: no version named, so no mutation.
            assertThat(currentVersion(scope)).isEqualTo(WorkloadStateVersion.INITIAL)
            assertThat(currentOwner(scope)).isEqualTo(ownerBefore)
        }

        // ── Domain conflicts (409) vs precondition failures (412) ───────

        @Test
        fun `an illegal transition at the current version is 409, not 412`() {
            val scope = register("lifecycle-1")
            transition(scope, "\"1\"", "RETIRED").andExpect { status { isOk() } }
            val retiredVersion = currentVersion(scope)

            transition(scope, "\"${retiredVersion.value}\"", "ACTIVE")
                .andExpect { status { isConflict() } }

            // and a stale expectation on the same resource is still a precondition failure
            transition(scope, "\"1\"", "ACTIVE")
                .andExpect { status { isPreconditionFailed() } }
        }

        @Test
        fun `a mutation on an unregistered scope is 404`() {
            mockMvc
                .put("/control-plane/workloads/ghost/environments/e/deployments/d/metadata") {
                    header("If-Match", "\"1\"")
                    contentType = MediaType.APPLICATION_JSON
                    content = metadata("nobody")
                }.andExpect { status { isNotFound() } }
        }

        // ── Registration ────────────────────────────────────────────────

        @Test
        fun `registration is 201 with an ETag, and a duplicate declaration is 200`() {
            val scope = deployment("register-1")

            mockMvc
                .post("/control-plane/workloads") {
                    contentType = MediaType.APPLICATION_JSON
                    content = registration(scope)
                }.andExpect {
                    status { isCreated() }
                    header { string("ETag", "\"1\"") }
                }

            mockMvc
                .post("/control-plane/workloads") {
                    contentType = MediaType.APPLICATION_JSON
                    content = registration(scope)
                }.andExpect { status { isOk() } }
        }

        // ── helpers ─────────────────────────────────────────────────────

        /** The control-plane fixtures own the identifiers, so the adapter tests never invent any. */
        private fun deployment(slug: String) = WorkloadRegistrationFixtures.identity(deployment = slug)

        private fun register(slug: String): WorkloadDeploymentIdentity =
            runBlocking {
                val scope = deployment(slug)
                authority.register(
                    scope,
                    WorkloadRegistrationFixtures.fingerprint(),
                    WorkloadRegistrationFixtures.metadata(),
                )
                scope
            }

        private fun advanceMetadata(
            scope: WorkloadDeploymentIdentity,
            times: Int,
        ) = runBlocking {
            var version = WorkloadStateVersion.INITIAL
            repeat(times) {
                val outcome =
                    authority.updateMetadata(
                        scope.workloadId,
                        scope.environmentId,
                        scope.deploymentId,
                        version,
                        WorkloadRegistrationFixtures.metadata(owner = "owner-${version.value}"),
                    )
                version = (outcome as MetadataUpdateOutcome.Applied).registration.stateVersion
            }
            Unit
        }

        private fun transition(
            scope: WorkloadDeploymentIdentity,
            ifMatch: String,
            target: String,
        ) = mockMvc.put(path(scope, "lifecycle")) {
            header("If-Match", ifMatch)
            contentType = MediaType.APPLICATION_JSON
            content = """{"target":"$target"}"""
        }

        private fun currentVersion(scope: WorkloadDeploymentIdentity): WorkloadStateVersion =
            runBlocking {
                val read =
                    authority.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)
                checkNotNull(read).observedVersion
            }

        private fun currentOwner(scope: WorkloadDeploymentIdentity): String? =
            runBlocking {
                val read =
                    authority.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)
                checkNotNull(read).registration.metadata.owner
            }

        private fun path(
            scope: WorkloadDeploymentIdentity,
            suffix: String? = null,
        ): String =
            "/control-plane/workloads/${scope.workloadId.value}" +
                "/environments/${scope.environmentId.value}" +
                "/deployments/${scope.deploymentId.value}" +
                (suffix?.let { "/$it" } ?: "")

        private fun metadata(owner: String): String = """{"owner":"$owner","purpose":"p"}"""

        private fun registration(scope: WorkloadDeploymentIdentity): String =
            """
            {
              "workloadId": "${scope.workloadId.value}",
              "configurationId": "${scope.configuration.id.value}",
              "configurationVersion": "${scope.configuration.version.value}",
              "environmentId": "${scope.environmentId.value}",
              "deploymentId": "${scope.deploymentId.value}",
              "configurationFingerprint": "sha256:aaaa",
              "owner": "Owner",
              "purpose": "p"
            }
            """.trimIndent()
    }
