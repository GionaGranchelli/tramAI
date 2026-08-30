package dev.tramai.build.quality

import com.fasterxml.jackson.annotation.JsonIgnore
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
    @JsonProperty("environment") val environment: EnvironmentInfo = EnvironmentInfo(),
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
    @JsonProperty("toolchain") val toolchain: ToolchainInfo = ToolchainInfo(),
)

@JsonPropertyOrder(alphabetic = true)
data class ToolchainInfo(
    @JsonProperty("gradle") val gradle: String = "",
    @JsonProperty("kotlin") val kotlin: String = "",
    @JsonProperty("jvmTarget") val jvmTarget: String = "21",
    @JsonProperty("ciJdk") val ciJdk: String = "21",
)

@JsonPropertyOrder(alphabetic = true)
data class StructuralBaseline(
    @JsonProperty("modules") val modules: List<ModuleInfo> = emptyList(),
    @JsonProperty("moduleDependencies") val moduleDependencies: DependencyGraphData = DependencyGraphData(),
    @JsonProperty("moduleDependenciesTest") val moduleDependenciesTest: DependencyGraphData = DependencyGraphData(),
    @JsonProperty("sourceMetrics") val sourceMetrics: SourceMetricsData = SourceMetricsData(),
    @JsonProperty("structuralHotspots") val structuralHotspots: StructuralHotspots = StructuralHotspots(),
)

data class ModuleInfo(
    val name: String,
    val path: String,
    val layer: String = "unknown",
    val publishable: Boolean = false,
)

data class DependencyGraphData(
    val modules: List<String> = emptyList(),
    val edges: List<DependencyEdge> = emptyList(),
    val cycles: List<List<String>> = emptyList(),
) : java.io.Serializable

data class DependencyEdge(
    val from: String,
    val to: String,
    val scope: String,
) : java.io.Serializable

data class SourceMetricsData(
    val byModule: Map<String, ModuleSourceMetrics> = emptyMap(),
    val totals: SourceTotals = SourceTotals(),
)

data class ModuleSourceMetrics(
    val module: String,
    val production: SourceSetMetrics = SourceSetMetrics(),
    val test: SourceSetMetrics = SourceSetMetrics(),
    val testFixtures: SourceSetMetrics = SourceSetMetrics(),
    val testToProductionRatio: Double = 0.0,
)

data class SourceSetMetrics(
    val files: Int = 0,
    val totalLines: Int = 0,
    val nonBlankLines: Int = 0,
    val commentLines: Int = 0,
    val codeLines: Int = 0,
)

data class SourceTotals(
    val totalProductionFiles: Int = 0,
    val totalProductionLines: Int = 0,
    val totalTestFiles: Int = 0,
    val totalTestLines: Int = 0,
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
    val highestFanIn: List<StructuralHotspot> = emptyList(),
)

data class StructuralHotspot(
    val module: String,
    val path: String,
    val declaration: String,
    val metric: String,
    val value: Int,
)

data class ApiBaseline(
    val modules: List<ApiDumpRecord> = emptyList(),
    val aggregateHash: String = "",
)

data class ApiDumpRecord(
    val module: String,
    val stability: String,
    val applicable: Boolean,
    val dumpPath: String,
    val sha256: String,
    val exclusionReason: String? = null,
)

data class DependencyBaseline(
    val resolvedDependencies: List<ResolvedDependency> = emptyList(),
    val convergenceIssues: List<String> = emptyList(),
    val unexpectedTransitives: List<String> = emptyList(),
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
    val consumers: List<String>,
)

data class TestQualityBaseline(
    val testPerformance: TestPerformanceData = TestPerformanceData(),
    val coverage: CoverageData = CoverageData(),
    val mutation: MutationData = MutationData(),
)

data class TestPerformanceData(
    val status: String = "not_configured",
    val observations: List<TestPerformanceObservation> = emptyList(),
    val byModule: Map<String, ModuleTestPerformance> = emptyMap(),
    val slowestClasses: List<TestTiming> = emptyList(),
    val slowestTests: List<TestTiming> = emptyList(),
    val allTests: List<TestTiming> = emptyList(),
    val byIdentity: Map<String, TestTiming> = emptyMap(),
    val totalDurationMs: Long = 0,
    val totalTestCount: Int = 0,
)

data class ModuleTestPerformance(
    val module: String,
    val totalDurationMs: Long = 0,
    val medianDurationMs: Long = totalDurationMs,
    val testCount: Int = 0,
    val skippedCount: Int = 0,
    val failureCount: Int = 0,
    val sourceSet: String = "test",
    val testTaskName: String = "test",
)

data class TestTiming(
    val module: String,
    val className: String,
    val testName: String,
    val durationMs: Long,
    val sourceSet: String = "test",
    val testTaskName: String = "test",
    val skipped: Boolean = false,
    val failed: Boolean = false,
)

data class TestPerformanceObservation(
    val run: Int,
    val module: String,
    val durationMs: Long,
    val testCount: Int,
    val skippedCount: Int,
    val failureCount: Int,
    val sourceSet: String = "test",
    val testTaskName: String = "test",
    val jdkVersion: String = "",
    val gradleVersion: String = "",
    val classTimings: List<TestTiming> = emptyList(),
    val testTimings: List<TestTiming> = emptyList(),
    val byIdentity: Map<String, TestTiming> = emptyMap(),
)

data class CoverageData(
    val status: String = "not_configured",
    val note: String = "",
    val byModule: Map<String, ModuleCoverage> = emptyMap(),
    val criticalModules: Map<String, ModuleCoverage> = emptyMap(),
    val exclusions: List<CoverageExclusion> = emptyList(),
    val overallLineCoverage: Double = 0.0,
    val overallBranchCoverage: Double = 0.0,
)

data class ModuleCoverage(
    val module: String,
    val lineCoverage: Double = 0.0,
    val branchCoverage: Double = 0.0,
    val linesCovered: Int = 0,
    val linesMissed: Int = 0,
    val linesTotal: Int = 0,
    val branchesCovered: Int = 0,
    val branchesMissed: Int = 0,
    val branchesTotal: Int = 0,
)

data class CoverageExclusion(
    val pattern: String,
    val reason: String,
)

data class MutationData(
    val status: String = "not_configured",
    val note: String = "",
    val analyzerVersion: String = "",
    val measuredCommit: String = "",
    val totalMutants: Int = 0,
    val killedMutants: Int = 0,
    val survivedMutants: Int = 0,
    val mutationScore: Double = 0.0,
    val byModule: Map<String, ModuleMutationMetrics> = emptyMap(),
    val byFamily: Map<String, MutationFamilyMetrics> = emptyMap(),
    val survivingMutants: List<SurvivingMutant> = emptyList(),
    val equivalentMutants: List<SurvivingMutant> = emptyList(),
    val unclassifiedMutants: List<SurvivingMutant> = emptyList(),
)

data class ModuleMutationMetrics(
    val module: String,
    val generated: Int = 0,
    val killed: Int = 0,
    val survived: Int = 0,
    val noCoverage: Int = 0,
    val timedOut: Int = 0,
    val mutationScore: Double = 0.0,
)

data class MutationFamilyMetrics(
    val family: String,
    val modules: List<String> = emptyList(),
    val totalMutants: Int = 0,
    val killedMutants: Int = 0,
    val survivedMutants: Int = 0,
    val noCoverageMutants: Int = 0,
    val mutationScore: Double = 0.0,
)

data class SurvivingMutant(
    val module: String,
    val file: String,
    val line: Int,
    val mutator: String,
    val classification: String = "unclassified",
    val description: String = "",
    val className: String = "",
    val method: String = "",
    val status: String = "SURVIVED",
    val identity: String = "",
    val behaviourFamily: String = "",
    val issue: String? = null,
    val targetPhase: String? = null,
)

data class RuntimeSafetyBaseline(
    val cancellationCatches: List<CancellationCatchFinding> = emptyList(),
    val testCancellationCatches: List<CancellationCatchFinding> = emptyList(),
    val globalState: List<GlobalStateFinding> = emptyList(),
    val nondeterminism: List<NondeterminismFinding> = emptyList(),
)

data class CancellationCatchFinding(
    @JsonProperty("module") val module: String,
    @JsonProperty("file") val file: String,
    @JsonProperty("function") val function: String,
    @JsonProperty("catchType") val catchType: String,
    @JsonProperty("isSuspendCapable") val isSuspendCapable: Boolean = false,
    @JsonProperty("rethrowsCancellation") val rethrowsCancellation: Boolean = false,
    @JsonProperty("transformsException") val transformsException: Boolean = false,
    @JsonProperty("risk") val risk: String = "medium",
    @JsonProperty("sourceLine") val sourceLine: Int = 0,
    /**
     * Ephemeral relocation evidence: normalized source-content fingerprint of
     * the catch site, computed at scan time and never persisted. Serialization
     * ignores it, so the persisted baseline schema is unchanged (schema v1).
     * Used ONLY by the comparator's relocation pass to prove a catch actually
     * moved across files — the line number alone is never identity.
     */
    @get:JsonIgnore val sourceFingerprint: String = "",
)

data class GlobalStateFinding(
    @JsonProperty("module") val module: String,
    @JsonProperty("file") val file: String,
    @JsonProperty("declaration") val declaration: String,
    @JsonProperty("kind") val kind: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("mutable") val mutable: Boolean = true,
    @JsonProperty("lifecycle") val lifecycle: String = "process",
    @JsonProperty("threadSafety") val threadSafety: String = "unknown",
)

data class NondeterminismFinding(
    @JsonProperty("module") val module: String,
    @JsonProperty("file") val file: String,
    @JsonProperty("line") val line: Int,
    @JsonProperty("source") val source: String,
    @JsonProperty("classification") val classification: String = "unknown",
    @JsonProperty("category") val category: String = "unknown",
)

/**
 * Semantic identity for allowlist matching: (module, file, source).
 * Line numbers never participate — line movement must not invalidate an
 * otherwise unchanged allowlist entry (Epic 8.3d PR 2).
 */
fun NondeterminismFinding.identityKey(): String = "$module\u0000$file\u0000$source"

data class ProtocolCatalog(
    val entries: List<ProtocolEntry> = emptyList(),
)

data class ProtocolEntry(
    val category: String,
    val name: String,
    val value: String,
    val source: String,
    val consumers: List<String> = emptyList(),
    val stability: String = "unclassified",
)

data class VerificationReport(
    val passed: Boolean,
    val failures: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val acceptedDeviations: List<String> = emptyList(),
)

data class DeviationsSection(
    val acceptedRegressions: List<String> = emptyList(),
    val knownHotspots: List<String> = emptyList(),
)

data class EnvironmentInfo(
    val os: String? = null,
    val javaVersion: String? = null,
)
