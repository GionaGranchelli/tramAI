// Built at tag v0.5.0.  This source intentionally uses the old public ABI.
package dev.tramai.orchestration

import java.io.IOException
import kotlin.reflect.typeOf

@OptIn(ExperimentalStdlibApi::class)
fun binaryCompatFixtureMarkers(): String {
    WorkflowHttpException("step", "https://x", 1, IOException("cause"))
    WorkflowShellException("step", "failure", IOException("cause"))
    WorkflowMcpException("step", "failure", IOException("cause"))
    WorkflowCodexException("step", "failure", IOException("cause"))
    WorkflowHermesException("step", "failure", IOException("cause"))
    val builder = WorkflowBuilder<String>("w", "1", typeOf<String>())
    builder.httpStep("http", request = { _, _ -> HttpRequest("GET", "https://x") }, merge = { state, _, _ -> state })
    builder.build<String> { it }
    return "FIXTURE_OK_1,FIXTURE_OK_2,FIXTURE_OK_3,FIXTURE_OK_4,FIXTURE_OK_5,FIXTURE_OK_6"
}
