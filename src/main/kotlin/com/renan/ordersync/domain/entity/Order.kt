package com.renan.ordersync.domain.entity

import com.renan.ordersync.domain.enums.OrderStatus
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(
    name = "orders",
    indexes = [
        Index(name = "idx_orders_external_order_id", columnList = "external_order_id"),
        Index(name = "idx_orders_status", columnList = "status"),
        Index(name = "idx_orders_created_at", columnList = "created_at")
    ]
)
class Order(
    @Column(name = "external_order_id", nullable = false, length = 255)
    var externalOrderId: String,

    @Column(name = "source_system", nullable = false, length = 100)
    var sourceSystem: String,

    @Column(name = "customer_name", nullable = false, length = 255)
    var customerName: String,

    @Column(name = "customer_email", nullable = false, length = 255)
    var customerEmail: String,

    @Column(name = "currency", nullable = false, length = 10)
    var currency: String,

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    var totalAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: OrderStatus,

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    var idempotencyKey: String,
) {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    var items: MutableList<OrderItem> = mutableListOf()

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    var events: MutableList<ProcessingEvent> = mutableListOf()

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    var integrationAttempts: MutableList<IntegrationAttempt> = mutableListOf()

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC)
    }
}
