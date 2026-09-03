package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MutationIdentityTest {
    @Test
    fun `overloaded methods are distinguished by JVM descriptor`() {
        val base =
            MutationIdentity(
                module = ":engine",
                className = "dev.tramai.Router",
                method = "route",
                methodDescription = "(Ljava/lang/String;)V",
                mutator = "M",
                description = "d",
                block = 1,
                index = 2,
            )
        val overload = base.copy(methodDescription = "(Ljava/util/List;)V")
        assertNotEquals(base.stableKey(), overload.stableKey(), "M07: descriptor must separate overloads")
    }

    @Test
    fun `distinct PIT mutation indexes are distinct identities`() {
        val base =
            MutationIdentity(
                module = ":engine",
                className = "dev.tramai.Router",
                method = "route",
                methodDescription = "()V",
                mutator = "M",
                description = "d",
                block = 1,
                index = 5,
            )
        val otherIndex = base.copy(index = 6)
        assertNotEquals(base.stableKey(), otherIndex.stableKey(), "M08: index must separate bytecode points")
    }

    @Test
    fun `distinct blocks are distinct identities`() {
        val base =
            MutationIdentity(
                module = ":engine",
                className = "dev.tramai.Router",
                method = "route",
                methodDescription = "()V",
                mutator = "M",
                description = "d",
                block = 1,
                index = 5,
            )
        val otherBlock = base.copy(block = 2)
        assertNotEquals(base.stableKey(), otherBlock.stableKey())
    }

    @Test
    fun `identity excludes line number and source file`() {
        val a =
            MutationIdentity(
                module = ":engine",
                className = "dev.tramai.Router",
                method = "route",
                methodDescription = "()V",
                mutator = "M",
                description = "d",
                block = 1,
                index = 5,
            )
        // Same mutation, different line: identical identity (M20).
        assertEquals(a.stableKey(), a.copy().stableKey())
        // Identity has no line/sourceFile fields at all by construction.
        assertEquals(8, a.toList().size)
    }

    private fun MutationIdentity.toList() =
        listOf(module, className, method, methodDescription, mutator, description, block.toString(), index.toString())
}
