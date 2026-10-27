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

    /**
     * Scanner integrity: a block-comment opener written inside a line comment must stay text. Otherwise the opener
     * swallows the rest of the file and a real, unreviewed call becomes invisible - the exact false negative this
     * guard exists to prevent.
     */
    @Test
    fun `a line comment containing a block comment opener cannot hide a call`() {
        val hiding =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            // /* legacy note
            suspend fun decide(store: ApprovalStore) {
                store.transition(/* actual arguments */ 1)
            }
            """.trimIndent()
        val files = listOf(reviewedFile(), "tramai-engine/src/main/kotlin/Hidden.kt" to hiding)

        val violations = DirectApprovalMutationGuard.verify(inventory, files)

        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("unreviewed direct approval mutation in tramai-engine"))
        assertEquals(1, DirectApprovalMutationGuard.detect(files).single { it.path.endsWith("Hidden.kt") }.callSites)
    }

    /** The mirrored case: a line-comment marker inside a block comment must not end the block early or hide code. */
    @Test
    fun `a block comment containing a line comment marker does not hide a call`() {
        val hiding =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            /* legacy // note */
            suspend fun decide(store: ApprovalStore) {
                store.transition(1)
            }
            """.trimIndent()

        val sites = DirectApprovalMutationGuard.detect(listOf("tramai-engine/src/main/kotlin/Hidden2.kt" to hiding))

        assertEquals(1, sites.single().callSites)
    }

    /** A quote inside a comment must not open a literal, and a comment inside a literal must not open a comment. */
    @Test
    fun `quotes and comments do not leak across constructs`() {
        val quotedComment =
            source(
                "val s = \"// not a comment /* nor this */\"\n" +
                    "fun f(store: ApprovalStore) = store.transition(1)",
            )
        val commentQuote = source("// a stray \" in a comment\nfun f(store: ApprovalStore) = store.transition(1)")

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/Q.kt" to quotedComment)).single().callSites)
        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/R.kt" to commentQuote)).single().callSites)
    }

    /** A character literal holding a quote must not be read as the start of a string. */
    @Test
    fun `a quote character literal does not open a string`() {
        val text = source("val q = '\"'\nfun f(store: ApprovalStore) = store.transition(1)")

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/S.kt" to text)).single().callSites)
    }

    /** Inert literal text is not executable, and the parser knows the difference. */
    @Test
    fun `a call spelled inside a string literal is not a call site`() {
        val text = source("val s = \"store.transition(1)\"\nfun f(store: ApprovalStore) { }")

        assertEquals(emptyList(), DirectApprovalMutationGuard.detect(listOf("a/T.kt" to text)))
    }

    /** Kotlin templates execute real code, so an interpolated call must be a call site. */
    @Test
    fun `string interpolation counts as a call site`() {
        val template = "\"" + "${'$'}{store.transition(1)}" + "\""
        val text = source("fun f(store: ApprovalStore) =\n    " + template)

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/U.kt" to text)).single().callSites)
    }

    /** An escaped interpolation is literal text, not a call. */
    @Test
    fun `an escaped interpolation is not a call site`() {
        val text = source("val s = \"\\${'$'}{store.transition(1)}\"\nfun f(store: ApprovalStore) { }")

        assertEquals(emptyList(), DirectApprovalMutationGuard.detect(listOf("a/V.kt" to text)))
    }

    /** A raw string may hold a quote and a block-comment marker; neither may swallow the code that follows. */
    @Test
    fun `a raw string containing a quote cannot hide the code that follows`() {
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            val sample = ${'"'}${'"'}${'"'}A quote " and /* literal text ${'"'}${'"'}${'"'}

            fun f(store: ApprovalStore) =
                store.transition(1)
            """.trimIndent()

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/W.kt" to text)).single().callSites)
    }

    /**
     * The discriminating raw-string case: the misparse used to swallow a terminated region between the two raw strings,
     * so the call in between vanished. Raw strings must close at their own terminator.
     */
    @Test
    fun `a raw string is closed at its own terminator`() {
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            val sample = ${'"'}${'"'}${'"'}A quote " and /* marker${'"'}${'"'}${'"'}
            fun f(store: ApprovalStore) = store.transition(1)
            val closer = ${'"'}${'"'}${'"'}done*/${'"'}${'"'}${'"'}
            """.trimIndent()

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/Y.kt" to text)).single().callSites)
    }

    /**
     * Fifth discriminator: a quoted literal *inside* a string template. The template contains `/*` as data and the next
     * line closes with `*/` as data, but the call between them is executable and must be counted.
     */
    @Test
    fun `nested quotes inside an interpolation cannot hide a call`() {
        val quote = "\""
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            fun f(store: ApprovalStore) {
                val template = "${'$'}{$quote/*$quote}"
                store.transition(1)
                val closer = $quote*/$quote
            }
            """.trimIndent()

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/NQ.kt" to text)).single().callSites)
    }

    /** A backtick-escaped call is the same call; the identifier is compared by name, not by source text. */
    @Test
    fun `a backtick escaped transition call is detected`() {
        val plain = source("fun f(store: ApprovalStore) = store.transition(1)")
        val escaped = source("fun g(store: ApprovalStore) = store.`transition`(1)")

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/B1.kt" to plain)).single().callSites)
        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/B2.kt" to escaped)).single().callSites)
        assertEquals(2, DirectApprovalMutationGuard.detect(listOf("a/B3.kt" to (plain + escaped))).single().callSites)
    }

    /** Kotlin nests block comments: a call inside the outer comment is not executable. */
    @Test
    fun `a call inside a nested block comment is not a call site`() {
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            /* outer /* inner */
            fun f(store: ApprovalStore) = store.transition(1)
            */
            """.trimIndent()

        assertEquals(emptyList(), DirectApprovalMutationGuard.detect(listOf("a/N1.kt" to text)))
    }

    /** The mirror direction: code after a closed nested comment is code. */
    @Test
    fun `a nested block comment does not swallow the code that follows`() {
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            /* outer /* inner */ tail */
            fun f(store: ApprovalStore) = store.transition(1)
            """.trimIndent()

        assertEquals(1, DirectApprovalMutationGuard.detect(listOf("a/N2.kt" to text)).single().callSites)
    }

    /** An unterminated nested comment is a parse error, so it fails closed too. */
    @Test
    fun `an unterminated nested block comment fails closed instead of hiding a call`() {
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            /* outer /* inner */ still open
            fun f(store: ApprovalStore) = store.transition(1)
            """.trimIndent()

        val violations = DirectApprovalMutationGuard.verify(inventory, listOf(reviewedFile(), "a/N3.kt" to text))

        assertTrue(violations.any { it.contains("cannot parse a/N3.kt safely") }, violations.toString())
    }

    /** An unclosed block comment leaves the file unparsable, which fails the guard rather than hiding the call. */
    @Test
    fun `an unterminated block comment fails closed instead of hiding a call`() {
        val text =
            """
            package x

            import dev.tramai.core.approval.ApprovalStore

            /* unterminated note

            fun f(store: ApprovalStore) =
                store.transition(1)
            """.trimIndent()

        val violations = DirectApprovalMutationGuard.verify(inventory, listOf(reviewedFile(), "a/X.kt" to text))

        assertTrue(violations.any { it.contains("cannot parse a/X.kt safely") }, violations.toString())
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
