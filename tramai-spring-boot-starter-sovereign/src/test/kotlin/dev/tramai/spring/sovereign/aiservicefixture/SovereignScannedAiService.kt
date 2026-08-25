package dev.tramai.spring.sovereign.aiservicefixture

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

@AiService
interface SovereignScannedAiService {
    @Operation(
        prompt = "Return a deterministic sovereign response for {{input}}.",
        model = "local-model",
    )
    suspend fun answer(input: String): String
}
