package com.renan.ordersync.domain.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(
    name = "integration_attempts",
    indexes = [Index(name = "idx_integration_attempts_order_id", columnList = "order_id")]
)
class IntegrationAttempt(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    var order: Order,

    @Column(name = "attempt_number", nullable = false)
    var attemptNumber: Int,

    @Column(name = "request_payload", columnDefinition = "TEXT")
    var requestPayload: String? = null,

    @Column(name = "response_status_code")
    var responseStatusCode: Int? = null,

    @Column(name = "response_body", columnDefinition = "TEXT")
    var responseBody: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "success", nullable = false)
    var success: Boolean = false,
) {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
}
