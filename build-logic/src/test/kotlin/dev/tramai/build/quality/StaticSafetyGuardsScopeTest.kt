package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class StaticSafetyGuardsScopeTest : StaticAnalysisContractTestBase() {
    private fun probe(
        path: String,
        text: String,
    ): Run {
        File(worktree, path).delete()
        writeKt(path, text)
        commit("scope probe")
        return gradle("verifyStaticSafetyGuards", "--no-build-cache")
    }

    @Test fun `production only excludes tests`() {
        assertTrue(probe("tramai-core/src/test/kotlin/Foo.kt", "fun x()=Thread { }").exit == 0)
    }

    @Test fun `build logic is excluded`() {
        assertTrue(probe("build-logic/src/main/kotlin/Foo.kt", "fun x()=Thread { }").exit == 0)
    }

    @Test fun `examples are excluded`() {
        assertTrue(probe("examples/x/src/main/kotlin/Foo.kt", "fun x()=Thread { }").exit == 0)
    }

    @Test fun `comments and strings are ignored`() {
        assertTrue(probe("tramai-core/src/main/kotlin/Foo.kt", "// Thread { }\nval x=\"System.err.println(\"\"").exit == 0)
    }

    @Test fun `java production source is scanned`() {
        val p = "tramai-core/src/main/java/dev/tramai/core/Foo.java"
        File(worktree, p).parentFile.mkdirs()
        File(worktree, p).writeText("class Foo { void x(){ new Thread(); } }")
        commit("java probe")
        assertTrue(gradle("verifyStaticSafetyGuards", "--no-build-cache").exit != 0)
    }
}
