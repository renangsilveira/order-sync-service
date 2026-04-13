package com.renan.ordersync.dto.request

import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.math.BigDecimal

data class CreateOrderRequest(
    @field:NotBlank(message = "externalOrderId is required")
    val externalOrderId: String,

    @field:NotBlank(message = "sourceSystem is required")
    val sourceSystem: String,

    @field:Valid
    @field:NotNull(message = "customer is required")
    val customer: CustomerRequest,

    @field:NotBlank(message = "currency is required")
    @field:Size(min = 3, max = 10, message = "currency must be between 3 and 10 characters")
    val currency: String,

    @field:Valid
    @field:NotEmpty(message = "items must not be empty")
    val items: List<OrderItemRequest>,
)

data class CustomerRequest(
    @field:NotBlank(message = "customer.name is required")
    val name: String,

    @field:NotBlank(message = "customer.email is required")
    @field:Email(message = "customer.email must be a valid email address")
    val email: String,
)

data class OrderItemRequest(
    @field:NotBlank(message = "item.sku is required")
    val sku: String,

    @field:NotBlank(message = "item.name is required")
    val name: String,

    @field:NotNull(message = "item.quantity is required")
    @field:Min(value = 1, message = "item.quantity must be greater than 0")
    val quantity: Int,

    @field:NotNull(message = "item.unitPrice is required")
    @field:DecimalMin(value = "0.01", message = "item.unitPrice must be greater than 0")
    val unitPrice: BigDecimal,
)
