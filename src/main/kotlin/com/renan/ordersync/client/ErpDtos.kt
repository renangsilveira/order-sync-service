package com.renan.ordersync.client

import java.math.BigDecimal

/**
 * Payload sent to the ERP system when syncing an order.
 */
data class ErpOrderRequest(
    val orderId: String,
    val externalOrderId: String,
    val sourceSystem: String,
    val customer: ErpCustomer,
    val currency: String,
    val totalAmount: BigDecimal,
    val items: List<ErpOrderItem>,
)

data class ErpCustomer(
    val name: String,
    val email: String,
)

data class ErpOrderItem(
    val sku: String,
    val name: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal,
)

/**
 * Acknowledgement returned by the ERP on a successful sync.
 */
data class ErpOrderResponse(
    val erpOrderId: String,
    val status: String,
    val message: String?,
)
