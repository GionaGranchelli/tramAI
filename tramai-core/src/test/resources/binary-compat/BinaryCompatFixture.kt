package dev.tramai.core.binarycompat

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.provider.logProviderHttpFailureDebug
import dev.tramai.core.provider.providerHttpFailure
import dev.tramai.core.provider.providerTransportFailure
import java.io.IOException

private inline fun fixtureCall(number: Int, call: () -> Unit) {
    try {
        call()
        println("FIXTURE_OK_$number")
    } catch (error: Throwable) {
        println("FIXTURE_FAIL $number ${error::class.java.name}: ${error.message}")
    }
}

fun main() {
    fixtureCall(1) { ProviderException("failed") }
    fixtureCall(2) { ProviderException("m", null, 429, true, 2_000) }
    fixtureCall(3) { providerTransportFailure("openai", IOException("x")) }
    fixtureCall(4) { providerHttpFailure("openai", 429, "body") }
    fixtureCall(5) { providerHttpFailure("openai", 429, "body", "2") }
    fixtureCall(6) { logProviderHttpFailureDebug(null, "openai", 429, "body") }
}
