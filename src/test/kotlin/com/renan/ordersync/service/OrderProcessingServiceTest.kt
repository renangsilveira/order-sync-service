package com.renan.ordersync.service

import com.renan.ordersync.client.ErpIntegrationClient
import com.renan.ordersync.client.ErpOrderResponse
import com.renan.ordersync.domain.entity.IntegrationAttempt
import com.renan.ordersync.domain.entity.Order
import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.exception.ErpPermanentException
import com.renan.ordersync.exception.ErpTransientException
import com.renan.ordersync.fixtures.TestFixtures
import com.renan.ordersync.repository.IntegrationAttemptRepository
import com.renan.ordersync.repository.OrderRepository
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class OrderProcessingServiceTest {

    private val orderRepository: OrderRepository = mockk()
    private val integrationAttemptRepository: IntegrationAttemptRepository = mockk()
    private val erpClient: ErpIntegrationClient = mockk()
    private val processingEventService: ProcessingEventService = mockk(relaxed = true)

    /**
     * Fast retry config for unit tests — no real wait between attempts.
     * Mirrors the production config (transient = retry, permanent = ignore).
     */
    private val testRetryConfig = RetryConfig.custom<Any>()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(1))
        .retryExceptions(ErpTransientException::class.java)
        .ignoreExceptions(ErpPermanentException::class.java)
        .build()

    private val retryRegistry = RetryRegistry.of(
        mapOf("erp-client" to testRetryConfig)
    )

    /**
     * Using Dispatchers.Unconfined so coroutines launched via launchProcessing
     * run eagerly in the test thread up to the first real suspension point.
     * Full async flow is covered by integration tests.
     */
    private val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val service = OrderProcessingService(
        orderRepository,
        integrationAttemptRepository,
        erpClient,
        processingEventService,
        retryRegistry,
        testScope,
    )

    private lateinit var order: Order

    @BeforeEach
    fun setUp() {
        order = TestFixtures.createOrder()
        every { orderRepository.save(any()) } answers { firstArg() }
        every { integrationAttemptRepository.save(any()) } answers { firstArg() }
        every { integrationAttemptRepository.countByOrderId(order.id) } returns 0
    }

    // ── transitionToProcessing ────────────────────────────────

    @Test
    fun `transitionToProcessing changes status to PROCESSING`() {
        service.transitionToProcessing(order)

        assertThat(order.status).isEqualTo(OrderStatus.PROCESSING)
        verify { orderRepository.save(order) }
    }

    @Test
    fun `transitionToProcessing records PROCESSING_STARTED event`() {
        service.transitionToProcessing(order)

        verify { processingEventService.record(order, ProcessingEventType.PROCESSING_STARTED) }
    }

    // ── attemptErpSync — success ──────────────────────────────

    @Test
    fun `attemptErpSync saves successful attempt with success=true`() {
        val erpResponse = ErpOrderResponse("ERP-42", "ACCEPTED", null)
        every { erpClient.syncOrder(order) } returns erpResponse

        val savedAttempt = slot<IntegrationAttempt>()
        every { integrationAttemptRepository.save(capture(savedAttempt)) } answers { firstArg() }

        service.attemptErpSync(order)

        assertThat(savedAttempt.captured.success).isTrue()
        assertThat(savedAttempt.captured.responseStatusCode).isEqualTo(200)
        assertThat(savedAttempt.captured.attemptNumber).isEqualTo(1)
    }

    @Test
    fun `attemptErpSync records ERP_SYNC_SUCCEEDED on success`() {
        val erpResponse = ErpOrderResponse("ERP-42", "ACCEPTED", null)
        every { erpClient.syncOrder(order) } returns erpResponse

        service.attemptErpSync(order)

        verify {
            processingEventService.record(
                order = order,
                eventType = ProcessingEventType.ERP_SYNC_SUCCEEDED,
                message = any(),
                attemptNumber = 1,
            )
        }
    }

    // ── attemptErpSync — transient failure ────────────────────

    @Test
    fun `attemptErpSync re-throws ErpTransientException to signal retry`() {
        every { erpClient.syncOrder(order) } throws ErpTransientException("ERP unavailable")

        assertThatThrownBy { service.attemptErpSync(order) }
            .isInstanceOf(ErpTransientException::class.java)
    }

    @Test
    fun `attemptErpSync saves failed attempt on transient error`() {
        every { erpClient.syncOrder(order) } throws ErpTransientException("timeout")

        val savedAttempt = slot<IntegrationAttempt>()
        every { integrationAttemptRepository.save(capture(savedAttempt)) } answers { firstArg() }

        runCatching { service.attemptErpSync(order) }

        assertThat(savedAttempt.captured.success).isFalse()
        assertThat(savedAttempt.captured.errorMessage).contains("timeout")
    }

    @Test
    fun `attemptErpSync records ERP_SYNC_FAILED on transient error`() {
        every { erpClient.syncOrder(order) } throws ErpTransientException("timeout")

        runCatching { service.attemptErpSync(order) }

        verify {
            processingEventService.record(
                order = order,
                eventType = ProcessingEventType.ERP_SYNC_FAILED,
                message = any(),
                attemptNumber = 1,
            )
        }
    }

    // ── attemptErpSync — permanent failure ────────────────────

    @Test
    fun `attemptErpSync re-throws ErpPermanentException to abort retry`() {
        every { erpClient.syncOrder(order) } throws ErpPermanentException("invalid payload")

        assertThatThrownBy { service.attemptErpSync(order) }
            .isInstanceOf(ErpPermanentException::class.java)
    }

    @Test
    fun `attemptErpSync saves failed attempt on permanent error`() {
        every { erpClient.syncOrder(order) } throws ErpPermanentException("400 Bad Request")

        val savedAttempt = slot<IntegrationAttempt>()
        every { integrationAttemptRepository.save(capture(savedAttempt)) } answers { firstArg() }

        runCatching { service.attemptErpSync(order) }

        assertThat(savedAttempt.captured.success).isFalse()
        assertThat(savedAttempt.captured.errorMessage).contains("400")
    }

    // ── transitionToSynced ────────────────────────────────────

    @Test
    fun `transitionToSynced changes status to SYNCED`() {
        service.transitionToSynced(order)

        assertThat(order.status).isEqualTo(OrderStatus.SYNCED)
        verify { orderRepository.save(order) }
    }

    // ── transitionToFailed ────────────────────────────────────

    @Test
    fun `transitionToFailed changes status to FAILED`() {
        service.transitionToFailed(order, "exhausted retries")

        assertThat(order.status).isEqualTo(OrderStatus.FAILED)
        verify { orderRepository.save(order) }
    }

    // ── attempt counter ───────────────────────────────────────

    @Test
    fun `attemptErpSync uses next attempt number based on persisted count`() {
        every { integrationAttemptRepository.countByOrderId(order.id) } returns 2L
        every { erpClient.syncOrder(order) } returns ErpOrderResponse("erp-1", "OK", null)

        val saved = slot<IntegrationAttempt>()
        every { integrationAttemptRepository.save(capture(saved)) } answers { firstArg() }

        service.attemptErpSync(order)

        assertThat(saved.captured.attemptNumber).isEqualTo(3)
    }
}
