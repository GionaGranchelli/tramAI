package dev.tramai.build.quality

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/**
 * Data classes matching the 0.6.0 baseline JSON schema (schema version 1).
 */

@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class BaselineDocument(
    @JsonProperty("schemaVersion") val schemaVersion: String = "1",
    @JsonProperty("baselineIdentity") val baselineIdentity: BaselineIdentity = BaselineIdentity(),
    @JsonProperty("structural") val structural: StructuralBaseline = StructuralBaseline(),
    @JsonProperty("api") val api: ApiBaseline = ApiBaseline(),
    @JsonProperty("dependencies") val dependencies: DependencyBaseline = DependencyBaseline(),
    @JsonProperty("testQuality") val testQuality: TestQualityBaseline = TestQualityBaseline(),
    @JsonProperty("runtimeSafety") val runtimeSafety: RuntimeSafetyBaseline = RuntimeSafetyBaseline(),
    @JsonProperty("protocolCatalog") val protocolCatalog: ProtocolCatalog = ProtocolCatalog(),
    @JsonProperty("deviations") val deviations: DeviationsSection = DeviationsSection(),
    @JsonProperty("generatedAt") val generatedAt: String? = null,
    @JsonProperty("generatedBy") val generatedBy: String? = null,
    @JsonProperty("environment") val environment: EnvironmentInfo = EnvironmentInfo()
)

@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BaselineIdentity(
    @JsonProperty("repository") val repository: String = "GionaGranchelli/tramAI",
    @JsonProperty("releaseTag") val releaseTag: String = "v0.5.0",
    @JsonProperty("commitSha") val commitSha: String = "",
    @JsonProperty("baselineCommitSha") val baselineCommitSha: String = "",
    @JsonProperty("measuredCommitSha") val measuredCommitSha: String = "",
    @JsonProperty("workingTreeClean") val workingTreeClean: Boolean = true,
    @JsonProperty("measuredSourceTreeHash") val measuredSourceTreeHash: String = "",
    @JsonProperty("measuredGitTreeSha") val measuredGitTreeSha: String = "",
    @JsonProperty("analyzerCommitSha") val analyzerCommitSha: String = "",
    @JsonProperty("analyzerSchemaVersion") val analyzerSchemaVersion: String = "1",
    @JsonProperty("commitTimestamp") val commitTimestamp: String = "",
    @JsonProperty("tramaiVersion") val tramaiVersion: String = "0.5.0",
    @JsonProperty("toolchain") val toolchain: ToolchainInfo = ToolchainInfo()
)

@JsonPropertyOrder(alphabetic = true)
data class ToolchainInfo(
    @JsonProperty("gradle") val gradle: String = "",
    @JsonProperty("kotlin") val kotlin: String = "",
    @JsonProperty("jvmTarget") val jvmTarget: String = "21",
    @JsonProperty("ciJdk") val ciJdk: String = "21"
)

@JsonPropertyOrder(alphabetic = true)
data class StructuralBaseline(
    @JsonProperty("modules") val modules: List<ModuleInfo> = emptyList(),
    @JsonProperty("moduleDependencies") val moduleDependencies: DependencyGraphData = DependencyGraphData(),
    @JsonProperty("moduleDependenciesTest") val moduleDependenciesTest: DependencyGraphData = DependencyGraphData(),
    @JsonProperty("sourceMetrics") val sourceMetrics: SourceMetricsData = SourceMetricsData(),
    @JsonProperty("structuralHotspots") val structuralHotspots: StructuralHotspots = StructuralHotspots()
)

data class ModuleInfo(
    val name: String,
    val path: String,
    val layer: String = "unknown",
    val publishable: Boolean = false
)

data class DependencyGraphData(
    val modules: List<String> = emptyList(),
    val edges: List<DependencyEdge> = emptyList(),
    val cycles: List<List<String>> = emptyList()
)

data class DependencyEdge(
    val from: String,
    val to: String,
    val scope: String
)

data class SourceMetricsData(
    val byModule: Map<String, ModuleSourceMetrics> = emptyMap(),
    val totals: SourceTotals = SourceTotals()
)

data class ModuleSourceMetrics(
    val module: String,
    val production: SourceSetMetrics = SourceSetMetrics(),
    val test: SourceSetMetrics = SourceSetMetrics(),
    val testFixtures: SourceSetMetrics = SourceSetMetrics(),
    val testToProductionRatio: Double = 0.0
)

data class SourceSetMetrics(
    val files: Int = 0,
    val totalLines: Int = 0,
    val nonBlankLines: Int = 0,
    val commentLines: Int = 0,
    val codeLines: Int = 0
)

data class SourceTotals(
    val totalProductionFiles: Int = 0,
    val totalProductionLines: Int = 0,
    val totalTestFiles: Int = 0,
    val totalTestLines: Int = 0
)

data class StructuralHotspots(
    val largestProductionFiles: List<StructuralHotspot> = emptyList(),
    val largestTestFiles: List<StructuralHotspot> = emptyList(),
    val largestBuildFiles: List<StructuralHotspot> = emptyList(),
    val largestClasses: List<StructuralHotspot> = emptyList(),
    val mostFunctions: List<StructuralHotspot> = emptyList(),
    val longestFunctions: List<StructuralHotspot> = emptyList(),
    val highestCyclomaticComplexity: List<StructuralHotspot> = emptyList(),
    val highestCognitiveComplexity: List<StructuralHotspot> = emptyList(),
    val mostConstructorParameters: List<StructuralHotspot> = emptyList(),
    val mostFunctionParameters: List<StructuralHotspot> = emptyList(),
    val highestFanOut: List<StructuralHotspot> = emptyList(),
    val highestFanIn: List<StructuralHotspot> = emptyList()
)

data class StructuralHotspot(
    val module: String,
    val path: String,
    val declaration: String,
    val metric: String,
    val value: Int
)

data class ApiBaseline(
    val publicApiDumps: Map<String, String> = emptyMap(),
    val apiCheckHash: String = ""
)

data class DependencyBaseline(
    val resolvedDependencies: List<ResolvedDependency> = emptyList(),
    val convergenceIssues: List<String> = emptyList(),
    val unexpectedTransitives: List<String> = emptyList()
)

data class ResolvedDependency(
    val group: String,
    val artifact: String,
    val selectedVersion: String,
    val requestedVersion: String?,
    val direct: Boolean,
    val configuration: String,
    val selectionReason: String,
    val dependencyPath: List<String>,
    val consumers: List<String>
)

data class TestQualityBaseline(
    val testPerformance: TestPerformanceData = TestPerformanceData(),
    val coverage: CoverageData = CoverageData(),
    val mutation: MutationData = MutationData()
)

data class TestPerformanceData(
    val byModule: Map<String, ModuleTestPerformance> = emptyMap(),
    val slowestClasses: List<TestTiming> = emptyList(),
    val slowestTests: List<TestTiming> = emptyList(),
    val totalDurationMs: Long = 0,
    val totalTestCount: Int = 0
)

data class ModuleTestPerformance(
    val module: String,
    val totalDurationMs: Long = 0,
    val testCount: Int = 0,
    val skippedCount: Int = 0,
    val failureCount: Int = 0
)

data class TestTiming(
    val module: String,
    val className: String,
    val testName: String,
    val durationMs: Long
)

data class CoverageData(
    val status: String = "pending",
    val note: String = "Requires JaCoCo plugin configuration",
    val byModule: Map<String, ModuleCoverage> = emptyMap(),
    val criticalModules: Map<String, ModuleCoverage> = emptyMap(),
    val overallLineCoverage: Double = 0.0,
    val overallBranchCoverage: Double = 0.0
)

data class ModuleCoverage(
    val module: String,
    val lineCoverage: Double = 0.0,
    val branchCoverage: Double = 0.0,
    val linesCovered: Int = 0,
    val linesTotal: Int = 0
)

data class MutationData(
    val status: String = "pending",
    val note: String = "Requires PITest plugin configuration",
    val byModule: Map<String, ModuleMutationMetrics> = emptyMap(),
    val survivingMutants: List<SurvivingMutant> = emptyList(),
    val equivalentMutants: List<SurvivingMutant> = emptyList(),
    val unclassifiedMutants: List<SurvivingMutant> = emptyList()
)

data class ModuleMutationMetrics(
    val module: String,
    val generated: Int = 0,
    val killed: Int = 0,
    val survived: Int = 0,
    val noCoverage: Int = 0,
    val timedOut: Int = 0,
    val mutationScore: Double = 0.0
)

data class SurvivingMutant(
    val module: String,
    val file: String,
    val line: Int,
    val mutator: String,
    val classification: String = "unclassified",
    val description: String = ""
)

data class RuntimeSafetyBaseline(
    val cancellationCatches: List<CancellationCatchFinding> = emptyList(),
    val testCancellationCatches: List<CancellationCatchFinding> = emptyList(),
    val globalState: List<GlobalStateFinding> = emptyList(),
    val nondeterminism: List<NondeterminismFinding> = emptyList()
)

data class CancellationCatchFinding(
    val module: String,
    val file: String,
    val function: String,
    val catchType: String,
    val isSuspendCapable: Boolean = false,
    val rethrowsCancellation: Boolean = false,
    val transformsException: Boolean = false,
    val risk: String = "medium"
)

data class GlobalStateFinding(
    val module: String,
    val file: String,
    val declaration: String,
    val kind: String,
    val type: String,
    val mutable: Boolean = true,
    val lifecycle: String = "process",
    val threadSafety: String = "unknown"
)

data class NondeterminismFinding(
    val module: String,
    val file: String,
    val line: Int,
    val source: String,
    val classification: String = "unknown",
    val category: String = "unknown"
)

data class ProtocolCatalog(
    val entries: List<ProtocolEntry> = emptyList()
)

data class ProtocolEntry(
    val category: String,
    val name: String,
    val value: String,
    val source: String,
    val consumers: List<String> = emptyList(),
    val stability: String = "unclassified"
)

data class VerificationReport(
    val passed: Boolean,
    val failures: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val acceptedDeviations: List<String> = emptyList()
)

data class DeviationsSection(
    val acceptedRegressions: List<String> = emptyList(),
    val knownHotspots: List<String> = emptyList()
)

data class EnvironmentInfo(
    val os: String? = null,
    val javaVersion: String? = null
)
