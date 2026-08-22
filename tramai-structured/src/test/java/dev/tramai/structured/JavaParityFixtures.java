package dev.tramai.structured;

import dev.tramai.core.annotations.AiDescription;
import dev.tramai.core.annotations.AiMinItems;
import dev.tramai.core.annotations.AiRange;

/**
 * JavaBean fixtures used by the Epic 7.1 JavaBean-parity tests. Mirrors the
 * Kotlin {@code ParityKotlinDto} shapes so the semantic descriptors can be
 * compared across languages (accessors excluded from comparison).
 */
public final class JavaParityFixtures {

    public static class JavaParityDto {
        @AiDescription("The label")
        private String label;
        @AiRange(min = 0.0, max = 100.0)
        private double score;
        @AiMinItems(1)
        private java.util.List<String> tags;
        private JavaParityNested nested;
        private JavaParityLevel level;

        public JavaParityDto() {
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public java.util.List<String> getTags() {
            return tags;
        }

        public void setTags(java.util.List<String> tags) {
            this.tags = tags;
        }

        public JavaParityNested getNested() {
            return nested;
        }

        public void setNested(JavaParityNested nested) {
            this.nested = nested;
        }

        public JavaParityLevel getLevel() {
            return level;
        }

        public void setLevel(JavaParityLevel level) {
            this.level = level;
        }
    }

    public static class JavaParityNested {
        private String value;

        public JavaParityNested() {
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public enum JavaParityLevel {
        LOW,
        HIGH
    }

    private JavaParityFixtures() {
    }
}
