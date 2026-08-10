// Built at tag v0.5.0.  This source intentionally uses the old public ABI.
package dev.tramai.orchestration

import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource
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
    WorkflowResumeException("resume")
    WorkflowCheckpointConflictException("conflict")
    WorkflowLeaseConflictException("lease conflict")
    val rootDirectory = Files.createTempDirectory("fixture")
    try {
        FileWorkflowCheckpointStore(rootDirectory)
        MarkdownWorkflowCheckpointStore(rootDirectory)
        JdbcWorkflowCheckpointStore(FixtureDataSource)
        FileWorkflowLeaseStore(rootDirectory)
        JdbcWorkflowLeaseStore(FixtureDataSource)
        InMemoryWorkflowCheckpointStore()
        InMemoryWorkflowLeaseStore()
    } finally {
        rootDirectory.toFile().deleteRecursively()
    }
    return "FIXTURE_OK_1,FIXTURE_OK_2,FIXTURE_OK_3,FIXTURE_OK_4,FIXTURE_OK_5,FIXTURE_OK_6," +
        "FIXTURE_OK_7,FIXTURE_OK_8,FIXTURE_OK_9,FIXTURE_OK_10,FIXTURE_OK_11,FIXTURE_OK_12," +
        "FIXTURE_OK_13,FIXTURE_OK_14,FIXTURE_OK_15,FIXTURE_OK_16,FIXTURE_OK_17"
}

private object FixtureDataSource : DataSource {
    override fun getConnection(): Connection = throw UnsupportedOperationException("fixture")
    override fun getConnection(username: String?, password: String?): Connection = throw UnsupportedOperationException("fixture")
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getGlobal()
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException("fixture")
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
