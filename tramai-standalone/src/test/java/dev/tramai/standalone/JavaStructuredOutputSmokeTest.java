package dev.tramai.standalone;

import dev.tramai.core.annotations.AiService;
import dev.tramai.core.annotations.Operation;
import dev.tramai.core.model.Message;
import dev.tramai.testing.MockAiProvider;
import kotlin.jvm.JvmClassMappingKt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Java boundary smoke test for structured output.
 *
 * Proves that a Java-defined @AiService interface with a Java DTO return type
 * works through the TramAI standalone structured-output path.
 *
 * Current boundary finding: Jackson can deserialize the Java DTO, but schema
 * generation uses Kotlin reflection (KClass.memberProperties) which does not
 * see Java POJO properties. Schema properties are currently empty for Java
 * DTOs — a follow-up improvement (e.g., Jackson introspection or BeanUtil
 * based schema generation) would be needed for full Java support.
 */
class JavaStructuredOutputSmokeTest {

    @Test
    void javaServiceCanReturnStructuredDto() {
        // Arrange: deterministic provider that returns valid JSON
        MockAiProvider.Builder providerBuilder = new MockAiProvider.Builder();
        providerBuilder.onMethod("evaluate").respondWith("{\"status\":\"ok\",\"confidence\":0.8}");
        MockAiProvider provider = providerBuilder.build();

        Tramai tramai = Tramai.builder()
                .provider(provider, "mock", true)
                .model("test-model", "mock")
                .build();

        JavaScoredService service = tramai.create(
                JvmClassMappingKt.getKotlinClass(JavaScoredService.class)
        );

        // Act
        JavaScoredResult result = service.evaluate("tenant-a");

        // Assert: DTO fields are correctly deserialized through Jackson
        assertThat(result.getStatus()).isEqualTo("ok");
        assertThat(result.getConfidence()).isCloseTo(0.8, within(0.000001));

        // Assert: exactly one provider call was made
        assertThat(provider.getRequests()).hasSize(1);

        // Assert: the generated JSON schema was included in the prompt
        String requestText = String.join("\n",
                provider.getRequests().get(0).getMessages().stream()
                        .map(Message::getContent)
                        .toArray(String[]::new));

        assertThat(requestText).contains("Respond only with valid JSON matching this schema");
        assertThat(requestText).contains("\"properties\" : { }");

        // KNOWN GAP: Java POJO properties are not visible to Kotlin reflection
        // (KClass.memberProperties), so the generated schema has empty properties.
        // A follow-up PR should add Jackson-introspection-based schema generation
        // for Java DTOs. See structured-output-contract-lifecycle.md — "What is NOT
        // proven by code" section notes Java interop coverage is limited.
    }
}

/**
 * Java-defined @AiService interface returning a Java DTO.
 */
@AiService
interface JavaScoredService {
    @Operation(
            prompt = "Return a scored status",
            model = "test-model"
    )
    JavaScoredResult evaluate(String tenantId);
}

/**
 * Java DTO with conventional JavaBean accessors.
 *
 * The no-arg constructor and setters keep this smoke test focused on TramAI's
 * Java service boundary rather than on Jackson field-access behavior.
 */
class JavaScoredResult {
    private String status;
    private double confidence;

    public JavaScoredResult() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
