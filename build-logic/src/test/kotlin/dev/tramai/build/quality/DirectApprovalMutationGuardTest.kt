package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectApprovalMutationGuardTest {
    private val inventory =
        DirectApprovalMutationInventory(
            scanRoots = listOf("*/src/main/kotlin"),
            exclusions = listOf(DirectApprovalMutationExclusion("examples", "demonstration code")),
            entries =
                listOf(
                    DirectApprovalMutationEntry(
                        path = "tramai-security/src/main/kotlin/Coordinator.kt",
                        callSites = 1,
                        rationale = "system-initiated cancellation",
                    ),
                ),
        )

    private fun source(body: String) = "package x\n\nimport dev.tramai.core.approval.ApprovalStore\n\n$body\n"

    private fun reviewedFile() =
        "tramai-security/src/main/kotlin/Coordinator.kt" to source("fun f(store: ApprovalStore) = store.transition(1)")

    private fun unreviewedFile(): Pair<String, String> {
        val path = "tramai-engine/src/main/kotlin/Engine.kt"
        return path to source("fun f(store: ApprovalStore) = store.transition(1)")
    }

    @Test
    fun `an introduced direct approval mutation is detected`() {
        val violations = DirectApprovalMutationGuard.verify(inventory, listOf(reviewedFile(), unreviewedFile()))

        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("unreviewed direct approval mutation in tramai-engine"))
        assertTrue(violations.single().contains("1 call site(s)"))
    }

    /** The receiver may be spelled with a fully qualified type; no `import` statement is required. */
    @Test
    fun `a fully qualified approval store call site is detected`() {
        val path = "tramai-engine/src/main/kotlin/FullyQualified.kt"
        val text =
            """
            package x

            suspend fun mutate(
                store: dev.tramai.core.approval.ApprovalStore,
                id: String,
                version: Long,
            ) {
                store.transition(
                    id,
                    version,
                    dev.tramai.core.approval.ApprovalTransition.Approve("actor"),
                )
            }
            """.trimIndent()

        val violations = DirectApprovalMutationGuard.verify(inventory, listOf(reviewedFile(), path to text))

        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("unreviewed direct approval mutation in $path"))
    }

    @Test
    fun `existing reviewed infrastructure mutations remain allowed`() {
        assertEquals(emptyList(), DirectApprovalMutationGuard.verify(inventory, listOf(reviewedFile())))
    }

    @Test
    fun `a second call site in an already reviewed file fails`() {
        val files =
            listOf(
                "tramai-security/src/main/kotlin/Coordinator.kt" to
                    source("fun f(s: ApprovalStore) = s.transition(1)\nfun g(s: ApprovalStore) = s.transition(2)"),
            )

        val violations = DirectApprovalMutationGuard.verify(inventory, files)

        assertTrue(
            violations.single().contains("has 2 direct approval mutation call site(s)"),
            violations.toString(),
        )
    }

    @Test
    fun `unrelated transition methods do not produce false positives`() {
        val unrelatedCall =
            "package x\n\nimport dev.tramai.engine.Workflow\n\nfun f(w: Workflow) = w.transition(State.DONE)\n"
        val unrelatedCallInApprovalAwareFile =
            "package x\n\nimport dev.tramai.core.approval.ApprovalStore\n\nfun f(w: Workflow) = w.transition(1)\n"

        assertEquals(
            emptyList(),
            DirectApprovalMutationGuard.verify(inventory, listOf(reviewedFile(), "a/b/C.kt" to unrelatedCall)),
        )
        // The scanner cannot resolve receivers: a file that mentions approval types and calls *some* transition is
        // treated as a candidate. That is the documented blind spot, and it errs toward review, not silence.
        assertTrue(
            DirectApprovalMutationGuard.detect(listOf("a/b/D.kt" to unrelatedCallInApprovalAwareFile)).isNotEmpty(),
        )
    }

    @Test
    fun `commented out calls are not call sites`() {
        val files =
            listOf(
                "tramai-security/src/main/kotlin/Coordinator.kt" to
                    source(
                        "// store.transition(1)\n" +
                            "/* store.transition(2) */\n" +
                            "fun f(store: ApprovalStore) = store.transition(3)",
                    ),
            )

        assertEquals(1, DirectApprovalMutationGuard.detect(files).single().callSites)
        assertEquals(emptyList(), DirectApprovalMutationGuard.verify(inventory, files))
    }

    /** A trailing comment is as good a hiding place as a commented-out line, and as common. */
    @Test
    fun `trailing inline comments are not call sites`() {
        val files =
            listOf(
                "tramai-security/src/main/kotlin/Coordinator.kt" to
                    source("val x = 1 // store.transition(1)\nfun f(store: ApprovalStore) = store.transition(2)"),
            )

        assertEquals(1, DirectApprovalMutationGuard.detect(files).single().callSites)
        assertEquals(emptyList(), DirectApprovalMutationGuard.verify(inventory, files))
    }

    /** A string literal is not a comment: stripping a URL suffix must not delete the rest of the line. */
    @Test
    fun `double slash inside a string literal is not treated as a comment`() {
        val files =
            listOf(
                "tramai-security/src/main/kotlin/Coordinator.kt" to
                    source("val url = \"https://example.test\"\nfun f(store: ApprovalStore) = store.transition(1)"),
            )

        assertEquals(1, DirectApprovalMutationGuard.detect(files).single().callSites)
    }

    @Test
    fun `a vacuous scan fails instead of passing`() {
        val violations = DirectApprovalMutationGuard.verify(inventory, files = emptyList())

        assertTrue(violations.any { it.contains("scanned 0 production files") }, violations.toString())
    }

    /** Excluding the whole population is the same failure as selecting nothing. */
    @Test
    fun `a configuration that excludes every selected file fails`() {
        val everything =
            inventory.copy(
                exclusions = listOf(DirectApprovalMutationExclusion("tramai-security", "everything is excluded")),
            )

        val violations = DirectApprovalMutationGuard.verify(everything, listOf(reviewedFile()))

        assertTrue(violations.any { it.contains("scanned 0 production files") }, violations.toString())
        assertTrue(violations.any { it.contains("1 selected, 1 excluded") }, violations.toString())
    }

    @Test
    fun `a stale inventory entry fails`() {
        val violations = DirectApprovalMutationGuard.verify(inventory, files = emptyList())

        assertTrue(violations.any { it.contains("stale inventory entry") }, violations.toString())
    }

    @Test
    fun `an entry without a rationale fails`() {
        val unrationalised =
            inventory.copy(entries = listOf(DirectApprovalMutationEntry("a/B.kt", 1, "  ")))

        val violations = DirectApprovalMutationGuard.verify(unrationalised, listOf(reviewedFile()))

        assertTrue(violations.any { it.contains("has no rationale") }, violations.toString())
    }

    @Test
    fun `examples are excluded as documented, not by scanning accident`() {
        val parsed = DirectApprovalMutationInventoryParser.parse(repositoryInventoryFile().readText())

        assertTrue(parsed.excludes("examples/sovereign-document-intelligence/src/main/kotlin/X.kt"))
        assertTrue(parsed.exclusions.all { it.rationale.isNotBlank() }, "every exclusion needs a rationale")
        assertTrue(
            parsed.exclusions.any { it.path == "examples" },
            "the examples exclusion must be explicit inventory data",
        )
        assertFalse(parsed.excludes("tramai-security/src/main/kotlin/Coordinator.kt"))
    }

    /**
     * Authority transport: the same scan the Gradle task performs, run against the real configuration file and the
     * real production tree. Changing either moves this result, which a fixture-only test cannot detect.
     */
    @Test
    fun `the reviewed inventory matches the real production tree`() {
        val inventory = DirectApprovalMutationInventoryParser.parse(repositoryInventoryFile().readText())
        val root = repositoryInventoryFile().parentFile.parentFile.parentFile
        val files =
            inventory.scanRoots
                .flatMap { pattern ->
                    val dirs = root.listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
                    dirs.mapNotNull { dir -> dir.resolve(pattern.removePrefix("*/")) }
                }.filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension in setOf("kt", "java") } }
                .map { file ->
                    val relative =
                        root
                            .toPath()
                            .relativize(file.toPath())
                            .toString()
                            .replace(File.separatorChar, '/')
                    relative to file.readText()
                }.filterNot { (relative, _) -> inventory.excludes(relative) }

        assertTrue(files.isNotEmpty(), "the scan must not be empty")
        assertEquals(emptyList(), DirectApprovalMutationGuard.verify(inventory, files))
    }

    private fun repositoryInventoryFile(): File {
        val candidates =
            listOf(
                File("config/quality/direct-approval-mutation-inventory.yml"),
                File("../config/quality/direct-approval-mutation-inventory.yml"),
            )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot locate the inventory from ${File(".").absolutePath}")
    }
}
