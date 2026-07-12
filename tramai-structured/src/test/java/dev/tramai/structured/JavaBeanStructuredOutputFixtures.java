package dev.tramai.structured;

import dev.tramai.core.annotations.AiDescription;
import dev.tramai.core.annotations.AiMinItems;
import dev.tramai.core.annotations.AiRange;
import java.util.List;
import java.util.Map;

/**
 * Test fixtures for JavaBean structured-output schema and validation tests.
 *
 * <p>All classes follow conventional JavaBean conventions: no-arg constructor,
 * private fields, public getters and setters. Annotations are placed on
 * fields (as permitted by the {@code @Target(FIELD)} retention).
 */
public final class JavaBeanStructuredOutputFixtures {

    private JavaBeanStructuredOutputFixtures() {
        // utility class
    }

    // ------------------------------------------------------------------
    // Basic JavaBean with scalar properties and annotations
    // ------------------------------------------------------------------

    public static class JavaScoredResult {

        @AiDescription("Evaluation status")
        private String status;

        @AiRange(min = 0.0, max = 1.0)
        private double confidence;

        @AiMinItems(1)
        private List<String> reasons;

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

        public List<String> getReasons() {
            return reasons;
        }

        public void setReasons(List<String> reasons) {
            this.reasons = reasons;
        }
    }

    // ------------------------------------------------------------------
    // JavaBean with only primitives (no annotations)
    // ------------------------------------------------------------------

    public static class JavaPrimitiveResult {

        private int count;
        private boolean active;
        private long timestamp;
        private double score;

        public JavaPrimitiveResult() {
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }
    }

    // ------------------------------------------------------------------
    // JavaBean with a nested JavaBean property
    // ------------------------------------------------------------------

    public static class JavaClaimResult {

        private JavaDecision decision;

        public JavaClaimResult() {
        }

        public JavaDecision getDecision() {
            return decision;
        }

        public void setDecision(JavaDecision decision) {
            this.decision = decision;
        }
    }

    public static class JavaDecision {

        @AiDescription("Decision outcome")
        private String outcome;

        public JavaDecision() {
        }

        public String getOutcome() {
            return outcome;
        }

        public void setOutcome(String outcome) {
            this.outcome = outcome;
        }
    }

    // ------------------------------------------------------------------
    // JavaBean with a generic collection (List<String> reasons)
    // ------------------------------------------------------------------

    public static class JavaGenericCollectionResult {

        @AiDescription("List of assigned tags")
        private List<String> tags;

        public JavaGenericCollectionResult() {
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    // ------------------------------------------------------------------
    // JavaBean with a getter-only (calculated) property
    // ------------------------------------------------------------------

    public static class JavaGetterOnlyResult {

        private String firstName;
        private String lastName;

        public JavaGetterOnlyResult() {
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        /** Getter-only calculated property — no setter, no writable field. */
        public String getFullName() {
            return (firstName != null ? firstName : "") + " "
                + (lastName != null ? lastName : "");
        }
    }

    // ------------------------------------------------------------------
    // JavaBean with an unsupported Map property
    // ------------------------------------------------------------------

    public static class JavaMapResult {

        private Map<String, String> metadata;

        public JavaMapResult() {
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
        }
    }
}
