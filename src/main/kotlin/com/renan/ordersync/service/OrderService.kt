package com.renan.ordersync.service

import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.dto.request.CreateOrderRequest
import com.renan.ordersync.dto.response.CreateOrderResponse
import com.renan.ordersync.dto.response.OrderDetailResponse
import com.renan.ordersync.dto.response.RetryOrderResponse
import com.renan.ordersync.exception.BusinessRuleViolationException
import com.renan.ordersync.exception.MissingIdempotencyKeyException
import com.renan.ordersync.exception.OrderNotFoundException
import com.renan.ordersync.mapper.OrderMapper
import com.renan.ordersync.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderMapper: OrderMapper,
    private val processingEventService: ProcessingEventService,
    private val orderProcessingService: OrderProcessingService,
) {

    private val log = LoggerFactory.getLogger(OrderService::class.java)

    /**
     * Creates a new order or returns the existing one for the same idempotency key.
     *
     * This method is intentionally NOT annotated with `@Transactional` so that when a
     * [DataIntegrityViolationException] is thrown by [OrderRepository.saveAndFlush] (due to
     * a concurrent insert with the same idempotency key), the exception escapes a fully committed
     * or rolled-back inner transaction and can be caught and handled here.
     *
     * Flow:
     * 1. If the key is already in the database → return the existing order immediately.
     * 2. Try to save the new order (saveAndFlush triggers the unique constraint eagerly).
     * 3. If a race condition causes a duplicate-key violation, recover by reading the row
     *    that the concurrent request already inserted.
     *
     * The caller receives `202 Accepted`; ERP sync runs asynchronously in the background.
     */
    fun createOrder(request: CreateOrderRequest, idempotencyKey: String?): CreateOrderResponse {
        if (idempotencyKey.isNullOrBlank()) {
            throw MissingIdempotencyKeyException()
        }

        val existing = orderRepository.findByIdempotencyKey(idempotencyKey)
        if (existing != null) {
            log.info(
                "Idempotent hit for key=[{}] orderId=[{}] status=[{}]",
                idempotencyKey, existing.id, existing.status,
            )
            return orderMapper.toCreateResponse(existing)
        }

        val order = orderMapper.toEntity(request, idempotencyKey)

        return try {
            // saveAndFlush runs in its own transaction (SimpleJpaRepository.saveAndFlush is @Transactional).
            // If another thread inserted the same key concurrently, this throws DataIntegrityViolationException.
            val saved = orderRepository.saveAndFlush(order)

            processingEventService.record(saved, ProcessingEventType.ORDER_RECEIVED)
            log.info("Order created orderId=[{}] externalId=[{}]", saved.id, saved.externalOrderId)

            // Dispatch async — HTTP response returns before ERP sync completes
            orderProcessingService.launchProcessing(saved.id)

            orderMapper.toCreateResponse(saved)

        } catch (ex: DataIntegrityViolationException) {
            // Race condition: a concurrent request inserted the same idempotency key between our
            // initial check and our save. Recover the existing row and return it as if we had
            // found it in the first check.
            val recovered = orderRepository.findByIdempotencyKey(idempotencyKey)
                ?: throw ex // should never happen; re-throw only if recovery itself fails

            log.info(
                "Idempotency race condition resolved for key=[{}] orderId=[{}]",
                idempotencyKey, recovered.id,
            )
            orderMapper.toCreateResponse(recovered)
        }
    }

    @Transactional(readOnly = true)
    fun getOrderById(orderId: UUID): OrderDetailResponse {
        val order = orderRepository.findByIdWithDetails(orderId)
            ?: throw OrderNotFoundException(orderId)
        return orderMapper.toDetailResponse(order)
    }

    @Transactional(readOnly = true)
    fun getOrdersByExternalId(externalOrderId: String): List<OrderDetailResponse> {
        return orderRepository.findByExternalOrderId(externalOrderId).map { order ->
            val detailed = orderRepository.findByIdWithDetails(order.id)
                ?: throw OrderNotFoundException(order.id)
            orderMapper.toDetailResponse(detailed)
        }
    }

    /**
     * Manually triggers reprocessing of a [FAILED][OrderStatus.FAILED] order.
     * Throws [BusinessRuleViolationException] if the order is not in FAILED state.
     */
    @Transactional
    fun retryOrder(orderId: UUID): RetryOrderResponse {
        val order = orderRepository.findByIdWithItems(orderId)
            ?: throw OrderNotFoundException(orderId)

        if (order.status != OrderStatus.FAILED) {
            throw BusinessRuleViolationException(
                "Order [$orderId] cannot be retried because its status is [${order.status}]. " +
                    "Only FAILED orders can be manually retried.",
            )
        }

        processingEventService.record(order, ProcessingEventType.MANUAL_RETRY_REQUESTED)
        log.info("Manual retry requested for orderId=[{}]", orderId)

        orderProcessingService.launchProcessing(orderId)

        return orderMapper.toRetryResponse(order)
    }
}
