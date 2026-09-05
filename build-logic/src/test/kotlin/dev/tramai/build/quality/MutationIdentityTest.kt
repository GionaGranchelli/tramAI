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
    fun `PIT bytecode coordinates are part of mutation identity`() {
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

        assertNotEquals(
            base.stableKey(),
            base.copy(index = 6).stableKey(),
            "M08: index must separate bytecode points",
        )
        assertNotEquals(
            base.stableKey(),
            base.copy(block = 2).stableKey(),
            "block must separate bytecode regions",
        )
    }

    @Test
    fun `identity excludes line number and source file`() {
        val identity =
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

        assertEquals(identity.stableKey(), identity.copy().stableKey())
        assertEquals(8, identity.toList().size)
    }

    private fun MutationIdentity.toList() =
        listOf(module, className, method, methodDescription, mutator, description, block.toString(), index.toString())
}
