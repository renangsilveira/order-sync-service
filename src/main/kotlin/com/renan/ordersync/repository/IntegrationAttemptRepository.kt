package com.renan.ordersync.repository

import com.renan.ordersync.domain.entity.IntegrationAttempt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface IntegrationAttemptRepository : JpaRepository<IntegrationAttempt, UUID> {

    fun findByOrderIdOrderByCreatedAtAsc(orderId: UUID): List<IntegrationAttempt>

    fun countByOrderId(orderId: UUID): Long
}
