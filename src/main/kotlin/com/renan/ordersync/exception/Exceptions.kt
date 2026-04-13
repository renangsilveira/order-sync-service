package com.renan.ordersync.exception

import java.util.UUID

/**
 * Thrown when a requested order does not exist.
 */
class OrderNotFoundException(orderId: UUID) :
    RuntimeException("Order not found: $orderId")

/**
 * Thrown when the Idempotency-Key header is absent on order creation.
 */
class MissingIdempotencyKeyException :
    RuntimeException("Required header 'Idempotency-Key' is missing")

/**
 * Thrown when a business rule prevents an operation (e.g. retrying a non-FAILED order).
 */
class BusinessRuleViolationException(message: String) :
    RuntimeException(message)

/**
 * Thrown when the ERP returns a transient error (5xx, timeout, network failure).
 * Resilience4j retries on this exception.
 */
class ErpTransientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Thrown when the ERP returns a permanent error (4xx, invalid payload, functional rejection).
 * Resilience4j ignores (does not retry) this exception.
 */
class ErpPermanentException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
