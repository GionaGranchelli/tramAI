package dev.tramai.examples.javaconsumer;

import dev.tramai.core.annotations.AiDescription;
import dev.tramai.core.annotations.AiMinItems;
import dev.tramai.core.annotations.AiRange;
import dev.tramai.core.annotations.AiService;
import dev.tramai.core.annotations.AiTool;
import dev.tramai.core.identity.ConfigurationId;
import dev.tramai.core.identity.ConfigurationVersion;
import dev.tramai.core.identity.DeploymentId;
import dev.tramai.core.identity.EnvironmentId;
import dev.tramai.core.identity.GovernedRunIdentity;
import dev.tramai.core.identity.RunId;
import dev.tramai.core.identity.WorkloadConfigurationIdentity;
import dev.tramai.core.identity.WorkloadDeploymentIdentity;
import dev.tramai.core.identity.WorkloadId;

import java.util.List;

/**
 * Java consumer smoke fixture: compiles against the stable public API of
 * :tramai-core on the minimal consumer classpath.
 *
 * The proof is the compilation itself — if any stable annotation or attribute
 * disappears or changes incompatibly, this fixture stops compiling.
 */
public final class JavaConsumerSmoke {

    @AiService
    public interface GreetingService {
        @AiTool(description = "greet")
        String greet(
                @AiDescription("The user name") String name,
                @AiRange(min = 1.0, max = 10.0) int enthusiasm);

        @AiTool(description = "summarize")
        List<String> summarize(@AiDescription("Items to summarize") @AiMinItems(1) List<String> items);
    }

    public static final String MARKER =
            GreetingService.class.getAnnotation(AiService.class) != null ? "ok" : "missing";

    /**
     * Java consumer proof for the 0.7.1b identity vocabulary: plain JVM
     * classes are constructible and readable from Java with normal semantics.
     * The discriminator assertion mirrors the canonical same-environment
     * deployment invariant.
     */
    public static final String IDENTITY_MARKER = identityVocabularyWorks() ? "ok" : "missing";

    private static boolean identityVocabularyWorks() {
        WorkloadId workload = new WorkloadId("claims");
        WorkloadConfigurationIdentity configuration = new WorkloadConfigurationIdentity(
                new ConfigurationId("claims-prod"), new ConfigurationVersion("17"));
        WorkloadDeploymentIdentity amsterdam = new WorkloadDeploymentIdentity(
                workload, configuration, new EnvironmentId("production"),
                new DeploymentId("eu-west-amsterdam-01"));
        WorkloadDeploymentIdentity frankfurt = new WorkloadDeploymentIdentity(
                workload, configuration, new EnvironmentId("production"),
                new DeploymentId("eu-central-frankfurt-01"));
        GovernedRunIdentity run = new GovernedRunIdentity(amsterdam, new RunId("run-1"));
        return !amsterdam.equals(frankfurt) && run.getDeployment().equals(amsterdam);
    }
}
