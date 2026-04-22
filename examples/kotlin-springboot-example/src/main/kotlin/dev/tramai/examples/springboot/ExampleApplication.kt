package dev.tramai.examples.springboot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Application bootstrap for the Tramai Spring Boot example.
 */
@SpringBootApplication
class ExampleApplication

fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args)
}
