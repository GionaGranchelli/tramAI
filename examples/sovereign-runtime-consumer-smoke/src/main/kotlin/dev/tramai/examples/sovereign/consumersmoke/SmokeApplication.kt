package dev.tramai.examples.sovereign.consumersmoke

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class SmokeApplication

fun main(args: Array<String>) {
    runApplication<SmokeApplication>(*args)
}
