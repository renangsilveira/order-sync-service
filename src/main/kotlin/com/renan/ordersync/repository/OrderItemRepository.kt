package com.renan.ordersync.repository

import com.renan.ordersync.domain.entity.OrderItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OrderItemRepository : JpaRepository<OrderItem, UUID> {

    fun findByOrderId(orderId: UUID): List<OrderItem>
}
