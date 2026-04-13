package com.renan.ordersync.domain.entity

import com.renan.ordersync.domain.enums.ProcessingEventType
import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(
    name = "processing_events",
    indexes = [
        Index(name = "idx_processing_events_order_id", columnList = "order_id"),
        Index(name = "idx_processing_events_created_at", columnList = "created_at")
    ]
)
class ProcessingEvent(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    var order: Order,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 100)
    var eventType: ProcessingEventType,

    @Column(name = "message", columnDefinition = "TEXT")
    var message: String? = null,

    @Column(name = "attempt_number", nullable = false)
    var attemptNumber: Int = 0,
) {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
}
