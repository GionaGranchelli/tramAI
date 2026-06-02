package dev.tramai.security.classification

data class ClassificationInput(
    val text: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
