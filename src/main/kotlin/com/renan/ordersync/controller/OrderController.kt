package com.renan.ordersync.controller

import com.renan.ordersync.dto.request.CreateOrderRequest
import com.renan.ordersync.dto.response.CreateOrderResponse
import com.renan.ordersync.dto.response.OrderDetailResponse
import com.renan.ordersync.dto.response.RetryOrderResponse
import com.renan.ordersync.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService,
) {

    /**
     * Creates a new order.
     *
     * Requires the `Idempotency-Key` header.
     * Returns `202 Accepted` immediately; ERP sync happens asynchronously.
     * If the same key is submitted again, returns the existing order without creating a duplicate.
     */
    @PostMapping
    fun createOrder(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody @Valid request: CreateOrderRequest,
    ): ResponseEntity<CreateOrderResponse> {
        val response = orderService.createOrder(request, idempotencyKey)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }

    /**
     * Returns full order details (items + processing events) by internal UUID.
     */
    @GetMapping("/{orderId}")
    fun getOrder(
        @PathVariable orderId: UUID,
    ): ResponseEntity<OrderDetailResponse> {
        val response = orderService.getOrderById(orderId)
        return ResponseEntity.ok(response)
    }

    /**
     * Looks up orders by the caller's external reference.
     * Returns a list because the same `externalOrderId` may appear in multiple source systems.
     */
    @GetMapping
    fun getOrdersByExternalId(
        @RequestParam externalOrderId: String,
    ): ResponseEntity<List<OrderDetailResponse>> {
        val response = orderService.getOrdersByExternalId(externalOrderId)
        return ResponseEntity.ok(response)
    }

    /**
     * Manually triggers reprocessing of a FAILED order.
     * Returns `202 Accepted`; the reprocessing runs asynchronously.
     */
    @PostMapping("/{orderId}/retry")
    fun retryOrder(
        @PathVariable orderId: UUID,
    ): ResponseEntity<RetryOrderResponse> {
        val response = orderService.retryOrder(orderId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }
}
