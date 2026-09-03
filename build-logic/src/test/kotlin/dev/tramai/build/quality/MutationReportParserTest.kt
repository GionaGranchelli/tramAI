package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MutationReportParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parses PIT XML and creates line independent identity`() {
        val parser = MutationReportParser()
        val first = parser.parse(":engine", "routing", xml(mutation(12))).mutants.single()
        val second = parser.parse(":engine", "routing", xml(mutation(99))).mutants.single()
        assertEquals("SURVIVED", first.status)
        assertEquals(first.identity, second.identity)
    }

    @Test
    fun `parses methodDescription index and block from XML`() {
        val record = MutationReportParser().parse(":engine", "routing", xml(mutation(12))).mutants.single()
        assertEquals("(Ljava/lang/String;)V", record.methodDescription)
        assertEquals(7, record.index)
        assertEquals(3, record.block)
    }

    @Test
    fun `overloaded methods with same name produce distinct identities`() {
        val parser = MutationReportParser()
        val a = parser.parse(":engine", "routing", xml(mutation(12))).mutants.single()
        val b =
            parser
                .parse(
                    ":engine",
                    "routing",
                    xml(mutation(12).replace("(Ljava/lang/String;)V", "(Ljava/util/List;)V")),
                ).mutants
                .single()
        assertNotEquals(a.identity, b.identity, "M07: method descriptor must separate overloads")
    }

    @Test
    fun `different mutation indexes produce distinct identities`() {
        val parser = MutationReportParser()
        val a = parser.parse(":engine", "routing", xml(mutation(12))).mutants.single()
        val b =
            parser
                .parse(":engine", "routing", xml(mutation(12).replace("<index>7</index>", "<index>8</index>")))
                .mutants
                .single()
        assertNotEquals(a.identity, b.identity, "M08: index must separate bytecode mutation points")
    }

    @Test
    fun `malformed report fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "routing", xml("<mutations>"))
        }
    }

    @Test
    fun `absolute source path fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "routing",
                xml(mutation(1).replace("Router.kt", "/home/user/Router.kt")),
            )
        }
    }

    @Test
    fun `missing required identity field fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "routing",
                xml(mutation(1).replace("<mutatedClass>dev.tramai.Router</mutatedClass>", "")),
            )
        }
    }

    @Test
    fun `unknown status fails closed`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "routing",
                xml(mutation(1).replace("status=\"SURVIVED\"", "status=\"WEIRD\"")),
            )
        }
    }

    @Test
    fun `known non-killed statuses are preserved exactly`() {
        val parser = MutationReportParser()
        assertEquals(
            "NO_COVERAGE",
            parser
                .parse(":engine", "routing", xml(mutation(1, "NO_COVERAGE")))
                .mutants
                .single()
                .status,
        )
        assertEquals(
            "TIMED_OUT",
            parser
                .parse(":engine", "routing", xml(mutation(1, "TIMED_OUT")))
                .mutants
                .single()
                .status,
        )
    }

    private fun mutation(
        line: Int,
        status: String = "SURVIVED",
    ) = """
        <mutations>
          <mutation status="$status">
            <sourceFile>Router.kt</sourceFile>
            <mutatedClass>dev.tramai.Router</mutatedClass>
            <mutatedMethod>route</mutatedMethod>
            <methodDescription>(Ljava/lang/String;)V</methodDescription>
            <lineNumber>$line</lineNumber>
            <mutator>org.pitest.mutationtest.engine.gregor.mutators.BooleanFalseReturnValsMutator</mutator>
            <indexes><index>7</index></indexes>
            <blocks><block>3</block></blocks>
            <description>replaced boolean return with false</description>
          </mutation>
        </mutations>
        """.trimIndent()

    private fun xml(content: String) = tempDir.resolve("mutations.xml").toFile().apply { writeText(content) }
}
