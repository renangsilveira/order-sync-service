package com.renan.ordersync.repository

import com.renan.ordersync.domain.entity.ProcessingEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ProcessingEventRepository : JpaRepository<ProcessingEvent, UUID> {

    fun findByOrderIdOrderByCreatedAtAsc(orderId: UUID): List<ProcessingEvent>
}
