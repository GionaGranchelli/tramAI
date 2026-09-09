package dev.tramai.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Java-visible proof for the 0.7.1b identity contract.
 *
 * The identity vocabulary is plain JVM classes by design: this test compiles
 * and runs real Java source against the canonical public tramai-core identity
 * surface. It fails if the types are ever converted back to @JvmInline value
 * classes (which are not naturally constructible from Java) or if the
 * composition/equality semantics regress.
 */
class JavaIdentityInteropTest {

    private WorkloadDeploymentIdentity deployment(
            String workload, String version, String environment, String deployment) {
        return new WorkloadDeploymentIdentity(
                new WorkloadId(workload),
                new WorkloadConfigurationIdentity(
                        new ConfigurationId(workload + "-prod"),
                        new ConfigurationVersion(version)),
                new EnvironmentId(environment),
                new DeploymentId(deployment));
    }

    @Test
    void constructsIdentityVocabularyFromJava() {
        WorkloadDeploymentIdentity amsterdam =
                deployment("claims", "17", "production", "eu-west-amsterdam-01");
        GovernedRunIdentity run = new GovernedRunIdentity(amsterdam, new RunId("run-1"));

        assertEquals("claims", amsterdam.getWorkloadId().getValue());
        assertEquals("17", amsterdam.getConfiguration().getVersion().getValue());
        assertEquals("production", amsterdam.getEnvironmentId().getValue());
        assertEquals("eu-west-amsterdam-01", amsterdam.getDeploymentId().getValue());
        assertEquals("run-1", run.getRunId().getValue());
        assertEquals(amsterdam, run.getDeployment());
    }

    @Test
    void sameEnvironmentDifferentDeploymentDoesNotCollapseFromJava() {
        WorkloadDeploymentIdentity amsterdam =
                deployment("claims", "17", "production", "eu-west-amsterdam-01");
        WorkloadDeploymentIdentity frankfurt =
                deployment("claims", "17", "production", "eu-central-frankfurt-01");

        assertNotEquals(amsterdam, frankfurt);
    }

    @Test
    void validationFailsClosedFromJava() {
        assertThrows(IllegalArgumentException.class, () -> new WorkloadId(" "));
        assertThrows(IllegalArgumentException.class, () -> new RunId("run\nid"));
        assertThrows(IllegalArgumentException.class, () -> new WorkloadMetadata(" ", "purpose"));
    }
}
