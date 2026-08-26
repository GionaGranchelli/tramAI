package dev.tramai.examples.javaconsumer;

import dev.tramai.core.annotations.AiDescription;
import dev.tramai.core.annotations.AiMinItems;
import dev.tramai.core.annotations.AiRange;
import dev.tramai.core.annotations.AiService;
import dev.tramai.core.annotations.AiTool;

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
}
