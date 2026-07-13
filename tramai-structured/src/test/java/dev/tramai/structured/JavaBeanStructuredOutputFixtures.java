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

    // ------------------------------------------------------------------
    // Setter-only (write-only) property — no getter, no readable field
    // ------------------------------------------------------------------

    public static class JavaWriteOnlyResult {

        @SuppressWarnings("unused")
        private String something;

        public JavaWriteOnlyResult() {
        }

        public void setSecret(String secret) {
            // Write-only: no getter, no backing field named 'secret'
        }

        public String getSomething() {
            return something;
        }

        public void setSomething(String something) {
            this.something = something;
        }
    }

    // ------------------------------------------------------------------
    // Self-referencing JavaBean (recursive type)
    // ------------------------------------------------------------------

    public static class JavaRecursiveNode {

        private String name;
        private List<JavaRecursiveNode> children;

        public JavaRecursiveNode() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<JavaRecursiveNode> getChildren() {
            return children;
        }

        public void setChildren(List<JavaRecursiveNode> children) {
            this.children = children;
        }
    }

    // ------------------------------------------------------------------
    // Concrete collection type (ArrayList)
    // ------------------------------------------------------------------

    public static class JavaArrayListResult {

        private java.util.ArrayList<String> values;

        public JavaArrayListResult() {
        }

        public java.util.ArrayList<String> getValues() {
            return values;
        }

        public void setValues(java.util.ArrayList<String> values) {
            this.values = values;
        }
    }

    // ------------------------------------------------------------------
    // Java Set property
    // ------------------------------------------------------------------

    public static class JavaSetResult {

        private java.util.Set<JavaDecision> decisions;

        public JavaSetResult() {
        }

        public java.util.Set<JavaDecision> getDecisions() {
            return decisions;
        }

        public void setDecisions(java.util.Set<JavaDecision> decisions) {
            this.decisions = decisions;
        }
    }

    // ------------------------------------------------------------------
    // Nested collection (List<List<JavaDecision>>)
    // ------------------------------------------------------------------

    public static class JavaNestedCollectionResult {

        private List<List<JavaDecision>> decisionGroups;

        public JavaNestedCollectionResult() {
        }

        public List<List<JavaDecision>> getDecisionGroups() {
            return decisionGroups;
        }

        public void setDecisionGroups(List<List<JavaDecision>> decisionGroups) {
            this.decisionGroups = decisionGroups;
        }
    }

    // ------------------------------------------------------------------
    // Setter-parameter annotation (VALUE_PARAMETER target)
    // ------------------------------------------------------------------

    public static class JavaSetterParamAnnotationResult {

        private String label;
        private double score;

        public JavaSetterParamAnnotationResult() {
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(
            @AiDescription("Display label") String label
        ) {
            this.label = label;
        }

        public double getScore() {
            return score;
        }

        public void setScore(
            @AiRange(min = 0.0, max = 100.0) double score
        ) {
            this.score = score;
        }
    }

    // ------------------------------------------------------------------
    // Generic nested envelope (Envelope<T>)
    // ------------------------------------------------------------------

    public static class JavaEnvelope<T> {

        private T value;

        public JavaEnvelope() {
        }

        public T getValue() {
            return value;
        }

        public void setValue(T value) {
            this.value = value;
        }
    }

    // ------------------------------------------------------------------
    // Repeated sibling bean (two properties of same nested type)
    // ------------------------------------------------------------------

    public static class JavaComparisonResult {

        private JavaDecision primary;
        private JavaDecision secondary;

        public JavaComparisonResult() {
        }

        public JavaDecision getPrimary() {
            return primary;
        }

        public void setPrimary(JavaDecision primary) {
            this.primary = primary;
        }

        public JavaDecision getSecondary() {
            return secondary;
        }

        public void setSecondary(JavaDecision secondary) {
            this.secondary = secondary;
        }
    }

    // ------------------------------------------------------------------
    // JavaBean with nested required primitive in JSON shape validation
    // ------------------------------------------------------------------

    public static class JavaNestedPrimitiveResult {

        private JavaPrimitiveResult inner;

        public JavaNestedPrimitiveResult() {
        }

        public JavaPrimitiveResult getInner() {
            return inner;
        }

        public void setInner(JavaPrimitiveResult inner) {
            this.inner = inner;
        }
    }

    // ------------------------------------------------------------------
    // EnvelopeHolder — preserves generic type bindings in nested validation
    // ------------------------------------------------------------------

    public static class JavaEnvelopeHolder {

        private JavaEnvelope<JavaPrimitiveResult> payload;

        public JavaEnvelopeHolder() {
        }

        public JavaEnvelope<JavaPrimitiveResult> getPayload() {
            return payload;
        }

        public void setPayload(JavaEnvelope<JavaPrimitiveResult> payload) {
            this.payload = payload;
        }
    }

    // ------------------------------------------------------------------
    // Custom Map subclass — must be rejected as unsupported map, not treated as JavaBean
    // ------------------------------------------------------------------

    public static class JavaCustomMap extends java.util.HashMap<String, String> {
        // empty — exists to test Map isAssignableFrom detection
    }

    public static class JavaMapSubclassResult {

        private JavaCustomMap properties;

        public JavaMapSubclassResult() {
        }

        public JavaCustomMap getProperties() {
            return properties;
        }

        public void setProperties(JavaCustomMap properties) {
            this.properties = properties;
        }
    }
}
