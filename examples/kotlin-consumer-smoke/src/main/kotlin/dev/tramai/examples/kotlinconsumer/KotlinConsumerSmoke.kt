package dev.tramai.examples.kotlinconsumer

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.AiTool

/**
 * Kotlin consumer smoke fixture: compiles against the stable public API of
 * :tramai-core on the minimal consumer classpath.
 *
 * The proof is the compilation itself — if any stable annotation or attribute
 * disappears or changes incompatibly, this fixture stops compiling.
 */
object KotlinConsumerSmoke {

    @AiService
    interface GreetingService {
        @AiDescription("Greets a user by name")
        @AiTool(description = "greet")
        fun greet(
            @AiDescription("The user name") name: String,
            @AiRange(min = 1.0, max = 10.0) enthusiasm: Int,
        ): String

        @AiTool(description = "summarize")
        @AiMinItems(1)
        fun summarize(@AiDescription("Items to summarize") items: List<String>): List<String>
    }

    val marker: String =
        if (GreetingService::class.java.getAnnotation(AiService::class.java) != null) "ok" else "missing"
}
