package com.renan.ordersync.dto.response

import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class CreateOrderResponse(
    val orderId: UUID,
    val externalOrderId: String,
    val status: OrderStatus,
    val message: String,
)

data class OrderDetailResponse(
    val orderId: UUID,
    val externalOrderId: String,
    val sourceSystem: String,
    val customer: CustomerResponse,
    val currency: String,
    val totalAmount: BigDecimal,
    val status: OrderStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val items: List<OrderItemResponse>,
    val events: List<ProcessingEventResponse>,
)

data class CustomerResponse(
    val name: String,
    val email: String,
)

data class OrderItemResponse(
    val id: UUID,
    val sku: String,
    val name: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal,
)

data class ProcessingEventResponse(
    val id: UUID,
    val eventType: ProcessingEventType,
    val message: String?,
    val attemptNumber: Int,
    val createdAt: LocalDateTime,
)

data class RetryOrderResponse(
    val orderId: UUID,
    val status: OrderStatus,
    val message: String,
)
