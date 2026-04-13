package com.renan.ordersync.service

import com.renan.ordersync.client.ErpIntegrationClient
import com.renan.ordersync.domain.entity.IntegrationAttempt
import com.renan.ordersync.domain.entity.Order
import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.exception.ErpPermanentException
import com.renan.ordersync.exception.ErpTransientException
import com.renan.ordersync.repository.IntegrationAttemptRepository
import com.renan.ordersync.repository.OrderRepository
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.RetryRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrderProcessingService(
    private val orderRepository: OrderRepository,
    private val integrationAttemptRepository: IntegrationAttemptRepository,
    private val erpClient: ErpIntegrationClient,
    private val processingEventService: ProcessingEventService,
    private val retryRegistry: RetryRegistry,
    private val applicationCoroutineScope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(OrderProcessingService::class.java)

    /**
     * Launches async processing for the given [orderId].
     *
     * [MDCContext] captures the current MDC snapshot from the HTTP request thread
     * (including `correlationId`) and restores it inside the coroutine, so async log
     * lines carry the same correlation identifier as the originating request.
     */
    fun launchProcessing(orderId: UUID) {
        applicationCoroutineScope.launch(MDCContext()) {
            try {
                processOrder(orderId)
            } catch (ex: Exception) {
                log.error("Unhandled exception in async processing for orderId=[{}]", orderId, ex)
            }
        }
    }

    /**
     * Executes the full ERP synchronisation flow for an order.
     * Must be called from within a coroutine context.
     */
    private suspend fun processOrder(orderId: UUID) {
        val order = withContext(Dispatchers.IO) {
            orderRepository.findByIdWithItems(orderId)
        } ?: run {
            log.error("Cannot process order: not found orderId=[{}]", orderId)
            return
        }

        log.info("Starting processing orderId=[{}] externalId=[{}]", order.id, order.externalOrderId)

        withContext(Dispatchers.IO) { transitionToProcessing(order) }

        val retry = retryRegistry.retry("erp-client")

        try {
            retry.executeSuspendFunction {
                withContext(Dispatchers.IO) { attemptErpSync(order) }
            }
            withContext(Dispatchers.IO) { transitionToSynced(order) }
        } catch (ex: ErpPermanentException) {
            log.warn("Permanent ERP failure orderId=[{}]: {}", order.id, ex.message)
            withContext(Dispatchers.IO) { transitionToFailed(order, ex.message) }
        } catch (ex: Exception) {
            log.error("ERP sync exhausted retries orderId=[{}]: {}", order.id, ex.message)
            withContext(Dispatchers.IO) { transitionToFailed(order, ex.message) }
        }
    }

    @Transactional
    fun transitionToProcessing(order: Order) {
        order.status = OrderStatus.PROCESSING
        orderRepository.save(order)
        processingEventService.record(order, ProcessingEventType.PROCESSING_STARTED)
        log.info("Order [{}] → PROCESSING", order.id)
    }

    @Transactional
    fun attemptErpSync(order: Order) {
        val attemptNumber = (integrationAttemptRepository.countByOrderId(order.id) + 1).toInt()

        processingEventService.record(
            order = order,
            eventType = ProcessingEventType.ERP_SYNC_ATTEMPTED,
            attemptNumber = attemptNumber,
        )

        val attempt = IntegrationAttempt(
            order = order,
            attemptNumber = attemptNumber,
            requestPayload = buildRequestSummary(order),
        )

        try {
            val response = erpClient.syncOrder(order)

            attempt.responseStatusCode = 200
            attempt.responseBody = response.toString()
            attempt.success = true
            integrationAttemptRepository.save(attempt)

            processingEventService.record(
                order = order,
                eventType = ProcessingEventType.ERP_SYNC_SUCCEEDED,
                message = "ERP order id: ${response.erpOrderId}",
                attemptNumber = attemptNumber,
            )

            log.info(
                "ERP sync succeeded orderId=[{}] attempt=[{}] erpOrderId=[{}]",
                order.id, attemptNumber, response.erpOrderId,
            )

        } catch (ex: ErpTransientException) {
            attempt.success = false
            attempt.errorMessage = ex.message
            integrationAttemptRepository.save(attempt)

            processingEventService.record(
                order = order,
                eventType = ProcessingEventType.ERP_SYNC_FAILED,
                message = "Transient failure: ${ex.message}",
                attemptNumber = attemptNumber,
            )

            log.warn(
                "ERP transient failure orderId=[{}] attempt=[{}]: {}",
                order.id, attemptNumber, ex.message,
            )
            throw ex // signal Resilience4j to retry

        } catch (ex: ErpPermanentException) {
            attempt.success = false
            attempt.errorMessage = ex.message
            integrationAttemptRepository.save(attempt)

            processingEventService.record(
                order = order,
                eventType = ProcessingEventType.ERP_SYNC_FAILED,
                message = "Permanent failure: ${ex.message}",
                attemptNumber = attemptNumber,
            )

            log.error(
                "ERP permanent failure orderId=[{}] attempt=[{}]: {}",
                order.id, attemptNumber, ex.message,
            )
            throw ex // signal Resilience4j to NOT retry
        }
    }

    @Transactional
    fun transitionToSynced(order: Order) {
        order.status = OrderStatus.SYNCED
        orderRepository.save(order)
        log.info("Order [{}] → SYNCED", order.id)
    }

    @Transactional
    fun transitionToFailed(order: Order, reason: String?) {
        order.status = OrderStatus.FAILED
        orderRepository.save(order)
        log.warn("Order [{}] → FAILED reason=[{}]", order.id, reason)
    }

    private fun buildRequestSummary(order: Order): String =
        "orderId=${order.id} externalOrderId=${order.externalOrderId} items=${order.items.size}"
}
