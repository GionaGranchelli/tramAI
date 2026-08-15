package dev.tramai.engine.planning;

import dev.tramai.core.annotations.AiService;
import dev.tramai.core.annotations.Operation;

@AiService
public interface JavaPlanningService {
    @Operation(prompt = "Java echo", model = "test-model")
    String javaEcho(String input);
}
