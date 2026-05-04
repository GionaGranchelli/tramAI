package dev.tramai.platform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TramaiPlatformApplication

fun main(args: Array<String>) {
    runApplication<TramaiPlatformApplication>(*args)
}
