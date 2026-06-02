package dev.tramai.security.classification

import dev.tramai.core.model.ClassifiedDocument

interface DocumentClassifier {
    fun classify(input: ClassificationInput): ClassificationDecision
}

fun <T> DocumentClassifier.classifyDocument(
    payload: T,
    input: ClassificationInput,
): ClassifiedDocument<T> {
    val decision = classify(input)
    return ClassifiedDocument(
        payload = payload,
        classification = decision.classification,
        source = decision.source,
    )
}
