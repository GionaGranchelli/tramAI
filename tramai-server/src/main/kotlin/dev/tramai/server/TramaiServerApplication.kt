package dev.tramai.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TramaiServerApplication

fun main(args: Array<String>) {
    runApplication<TramaiServerApplication>(*args)
}
