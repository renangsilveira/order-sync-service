package com.renan.ordersync.service

import com.renan.ordersync.domain.entity.Order
import com.renan.ordersync.domain.entity.ProcessingEvent
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.repository.ProcessingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ProcessingEventService(
    private val processingEventRepository: ProcessingEventRepository,
) {

    private val log = LoggerFactory.getLogger(ProcessingEventService::class.java)

    /**
     * Persists a new [ProcessingEvent] for the given [order].
     * Uses [Propagation.REQUIRES_NEW] so that event recording survives even if the
     * outer transaction is later rolled back (e.g. on unexpected error paths).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        order: Order,
        eventType: ProcessingEventType,
        message: String? = null,
        attemptNumber: Int = 0,
    ): ProcessingEvent {
        val event = ProcessingEvent(
            order = order,
            eventType = eventType,
            message = message,
            attemptNumber = attemptNumber,
        )
        val saved = processingEventRepository.save(event)
        log.info(
            "Event recorded orderId=[{}] type=[{}] attempt=[{}]",
            order.id, eventType, attemptNumber
        )
        return saved
    }
}
