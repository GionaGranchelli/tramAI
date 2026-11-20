package dev.tramai.core.operator

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 0.7.6f: the canonical identity digest is persisted and compared as text, so its exact form is part of
 * the contract. These are the properties a durable evidence key depends on.
 */
class ActorIdentityDigestTest {
    @Test
    fun `digest is 64 lowercase hex characters with no prefix`() {
        val digest = ActorIdentityDigest.of("https://issuer.example", "op-1")

        assertThat(digest).matches("^[0-9a-f]{64}$")
        assertThat(digest).doesNotStartWith("sha256:")
    }

    @Test
    fun `digest is stable for the same identity`() {
        assertThat(ActorIdentityDigest.of("https://issuer.example", "op-1"))
            .isEqualTo(ActorIdentityDigest.of("https://issuer.example", "op-1"))
    }

    @Test
    fun `the same subject under two issuers produces different digests`() {
        val first = ActorIdentityDigest.of("https://issuer-a.example", "shared-subject")
        val second = ActorIdentityDigest.of("https://issuer-b.example", "shared-subject")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `the same issuer with two subjects produces different digests`() {
        assertThat(ActorIdentityDigest.of("https://issuer.example", "op-1"))
            .isNotEqualTo(ActorIdentityDigest.of("https://issuer.example", "op-2"))
    }

    /**
     * Length-prefixing, not delimiting: without it, ("ab","c") and ("a","bc") would hash the same bytes.
     */
    @Test
    fun `field boundaries cannot be shifted`() {
        assertThat(ActorIdentityDigest.of("ab", "c"))
            .isNotEqualTo(ActorIdentityDigest.of("a", "bc"))
    }

    /** A separator character inside a value must not create a collision with a different pair. */
    @Test
    fun `separator characters in values do not collide`() {
        assertThat(ActorIdentityDigest.of("a:b", "c"))
            .isNotEqualTo(ActorIdentityDigest.of("a", "b:c"))
        assertThat(ActorIdentityDigest.of("a\u0000b", "c"))
            .isNotEqualTo(ActorIdentityDigest.of("a", "b\u0000c"))
    }

    @Test
    fun `an empty issuer is rejected rather than hashed`() {
        assertThatThrownBy { ActorIdentityDigest.of("", "op-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tramai-actor-identity-issuer-blank")
    }

    @Test
    fun `an empty subject is rejected rather than hashed`() {
        assertThatThrownBy { ActorIdentityDigest.of("https://issuer.example", "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tramai-actor-identity-subject-blank")
    }

    /** Non-ASCII identities are hashed as UTF-8, so the digest is byte-defined rather than platform-defined. */
    @Test
    fun `non-ascii identities are digestible and stable`() {
        val digest = ActorIdentityDigest.of("https://issuer.example", "operatore-città")

        assertThat(digest).matches("^[0-9a-f]{64}$")
        assertThat(digest).isEqualTo(ActorIdentityDigest.of("https://issuer.example", "operatore-città"))
    }
}
