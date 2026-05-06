package dev.tramai.examples.supportagent

import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Simulated order lookup tool.
 * In a real application this would query a database or API.
 */
val lookupOrderTool = object : TramaiTool<String, String> {
    override val name = "lookupOrder"
    override val description = "Look up an order by ID. Returns order status and ETA."
    override val inputType = String::class

    override suspend fun execute(input: String, ctx: ToolExecutionContext): String {
        val orders = mapOf(
            "ORD-42" to OrderData("ORD-42", "shipped", "2026-04-18"),
            "ORD-99" to OrderData("ORD-99", "delivered", "2026-04-10"),
            "ORD-123" to OrderData("ORD-123", "processing", "2026-04-22"),
        )
        val order = orders[input.trim()]
        return if (order != null) {
            "Order ${order.id}: ${order.status}, ETA ${order.eta}"
        } else {
            "Order $input not found. Please verify the order ID."
        }
    }
}

/**
 * Time tool so the agent can reference current time in responses.
 */
val getCurrentTimeTool = object : TramaiTool<Unit, String> {
    override val name = "getCurrentTime"
    override val description = "Get the current date and time in the store's timezone (CET)."
    override val inputType = Unit::class

    override suspend fun execute(input: Unit, ctx: ToolExecutionContext): String {
        val now = Instant.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.of("Europe/Rome"))
        return "Current time: ${formatter.format(now)}"
    }
}

internal data class OrderData(
    val id: String,
    val status: String,
    val eta: String,
)
