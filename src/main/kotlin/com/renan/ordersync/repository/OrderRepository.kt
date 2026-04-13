package com.renan.ordersync.repository

import com.renan.ordersync.domain.entity.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OrderRepository : JpaRepository<Order, UUID> {

    fun findByExternalOrderId(externalOrderId: String): List<Order>

    fun findByIdempotencyKey(idempotencyKey: String): Order?

    /** Eager-fetches items to avoid N+1 during ERP sync. */
    @Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.id = :id")
    fun findByIdWithItems(id: UUID): Order?

    /** Eager-fetches items, events and integration attempts for the detail endpoint. */
    @Query("""
        SELECT o FROM Order o
        JOIN FETCH o.items
        LEFT JOIN FETCH o.events
        LEFT JOIN FETCH o.integrationAttempts
        WHERE o.id = :id
    """)
    fun findByIdWithDetails(id: UUID): Order?
}
