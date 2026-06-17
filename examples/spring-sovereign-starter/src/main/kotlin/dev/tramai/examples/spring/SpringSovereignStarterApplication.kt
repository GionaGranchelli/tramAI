package dev.tramai.examples.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Minimal Spring Boot application demonstrating the TramAI Sovereign starter.
 *
 * The application simply configures TramAI's sovereign runtime via
 * `application.yml`, provides a deterministic local model provider, and
 * runs an invoice analysis when started.
 */
@SpringBootApplication
class SpringSovereignStarterApplication

fun main(args: Array<String>) {
    runApplication<SpringSovereignStarterApplication>(*args)
}
