package com.renan.ordersync.service

import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.exception.BusinessRuleViolationException
import com.renan.ordersync.exception.MissingIdempotencyKeyException
import com.renan.ordersync.exception.OrderNotFoundException
import com.renan.ordersync.fixtures.TestFixtures
import com.renan.ordersync.mapper.OrderMapper
import com.renan.ordersync.repository.OrderRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

class OrderServiceTest {

    private val orderRepository: OrderRepository = mockk()
    private val orderMapper: OrderMapper = mockk()
    private val processingEventService: ProcessingEventService = mockk(relaxed = true)
    private val orderProcessingService: OrderProcessingService = mockk(relaxed = true)

    private val service = OrderService(
        orderRepository,
        orderMapper,
        processingEventService,
        orderProcessingService,
    )

    // ── createOrder ───────────────────────────────────────────

    @Test
    fun `createOrder creates new order when idempotency key is fresh`() {
        val request = TestFixtures.createOrderRequest()
        val order = TestFixtures.createOrder()
        val key = TestFixtures.DEFAULT_IDEMPOTENCY_KEY

        every { orderRepository.findByIdempotencyKey(key) } returns null
        every { orderMapper.toEntity(request, key) } returns order
        every { orderRepository.saveAndFlush(order) } returns order
        every { orderMapper.toCreateResponse(order) } returns mockk(relaxed = true)

        service.createOrder(request, key)

        verify { orderRepository.saveAndFlush(order) }
        verify { processingEventService.record(order, ProcessingEventType.ORDER_RECEIVED) }
        verify { orderProcessingService.launchProcessing(order.id) }
    }

    @Test
    fun `createOrder returns existing order when idempotency key already exists`() {
        val request = TestFixtures.createOrderRequest()
        val existing = TestFixtures.createOrder()
        val key = TestFixtures.DEFAULT_IDEMPOTENCY_KEY
        val expectedResponse = mockk<com.renan.ordersync.dto.response.CreateOrderResponse>(relaxed = true)

        every { orderRepository.findByIdempotencyKey(key) } returns existing
        every { orderMapper.toCreateResponse(existing) } returns expectedResponse

        val result = service.createOrder(request, key)

        assertThat(result).isSameAs(expectedResponse)
        verify(exactly = 0) { orderRepository.saveAndFlush(any()) }
        verify(exactly = 0) { orderProcessingService.launchProcessing(any()) }
    }

    @Test
    fun `createOrder throws MissingIdempotencyKeyException when header is null`() {
        val request = TestFixtures.createOrderRequest()

        assertThatThrownBy { service.createOrder(request, null) }
            .isInstanceOf(MissingIdempotencyKeyException::class.java)
    }

    @Test
    fun `createOrder throws MissingIdempotencyKeyException when header is blank`() {
        val request = TestFixtures.createOrderRequest()

        assertThatThrownBy { service.createOrder(request, "   ") }
            .isInstanceOf(MissingIdempotencyKeyException::class.java)
    }

    @Test
    fun `createOrder recovers from DataIntegrityViolationException and returns existing order`() {
        val request = TestFixtures.createOrderRequest()
        val existingOrder = TestFixtures.createOrder()
        val key = TestFixtures.DEFAULT_IDEMPOTENCY_KEY
        val expectedResponse = mockk<com.renan.ordersync.dto.response.CreateOrderResponse>(relaxed = true)

        every { orderRepository.findByIdempotencyKey(key) } returnsMany listOf(null, existingOrder)
        every { orderMapper.toEntity(request, key) } returns TestFixtures.createOrder()
        every { orderRepository.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate key")
        every { orderMapper.toCreateResponse(existingOrder) } returns expectedResponse

        val result = service.createOrder(request, key)

        assertThat(result).isSameAs(expectedResponse)
        verify(exactly = 0) { orderProcessingService.launchProcessing(any()) }
    }

    @Test
    fun `createOrder re-throws DataIntegrityViolationException if recovery read also fails`() {
        val request = TestFixtures.createOrderRequest()
        val key = TestFixtures.DEFAULT_IDEMPOTENCY_KEY

        every { orderRepository.findByIdempotencyKey(key) } returnsMany listOf(null, null)
        every { orderMapper.toEntity(request, key) } returns TestFixtures.createOrder()
        every { orderRepository.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate key")

        assertThatThrownBy { service.createOrder(request, key) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // ── getOrderById ──────────────────────────────────────────

    @Test
    fun `getOrderById returns detail response for existing order`() {
        val order = TestFixtures.createOrder()
        val expectedResponse = mockk<com.renan.ordersync.dto.response.OrderDetailResponse>(relaxed = true)

        every { orderRepository.findByIdWithDetails(order.id) } returns order
        every { orderMapper.toDetailResponse(order) } returns expectedResponse

        val result = service.getOrderById(order.id)

        assertThat(result).isSameAs(expectedResponse)
    }

    @Test
    fun `getOrderById throws OrderNotFoundException for unknown id`() {
        val unknownId = UUID.randomUUID()
        every { orderRepository.findByIdWithDetails(unknownId) } returns null

        assertThatThrownBy { service.getOrderById(unknownId) }
            .isInstanceOf(OrderNotFoundException::class.java)
            .hasMessageContaining(unknownId.toString())
    }

    // ── getOrdersByExternalId ─────────────────────────────────

    @Test
    fun `getOrdersByExternalId returns list of detail responses`() {
        val order = TestFixtures.createOrder()
        val detailed = TestFixtures.createOrder(id = order.id)
        val response = mockk<com.renan.ordersync.dto.response.OrderDetailResponse>(relaxed = true)

        every { orderRepository.findByExternalOrderId("EXT-001") } returns listOf(order)
        every { orderRepository.findByIdWithDetails(order.id) } returns detailed
        every { orderMapper.toDetailResponse(detailed) } returns response

        val result = service.getOrdersByExternalId("EXT-001")

        assertThat(result).hasSize(1)
        assertThat(result[0]).isSameAs(response)
    }

    @Test
    fun `getOrdersByExternalId returns empty list when nothing found`() {
        every { orderRepository.findByExternalOrderId(any()) } returns emptyList()

        val result = service.getOrdersByExternalId("UNKNOWN")

        assertThat(result).isEmpty()
    }

    // ── retryOrder ────────────────────────────────────────────

    @Test
    fun `retryOrder triggers processing for FAILED order`() {
        val order = TestFixtures.createFailedOrder()
        val expectedResponse = mockk<com.renan.ordersync.dto.response.RetryOrderResponse>(relaxed = true)

        every { orderRepository.findByIdWithItems(order.id) } returns order
        every { orderMapper.toRetryResponse(order) } returns expectedResponse

        val result = service.retryOrder(order.id)

        assertThat(result).isSameAs(expectedResponse)
        verify { processingEventService.record(order, ProcessingEventType.MANUAL_RETRY_REQUESTED) }
        verify { orderProcessingService.launchProcessing(order.id) }
    }

    @Test
    fun `retryOrder throws BusinessRuleViolationException for RECEIVED order`() {
        val order = TestFixtures.createOrder(status = OrderStatus.RECEIVED)
        every { orderRepository.findByIdWithItems(order.id) } returns order

        assertThatThrownBy { service.retryOrder(order.id) }
            .isInstanceOf(BusinessRuleViolationException::class.java)
            .hasMessageContaining("RECEIVED")
    }

    @Test
    fun `retryOrder throws BusinessRuleViolationException for SYNCED order`() {
        val order = TestFixtures.createOrder(status = OrderStatus.SYNCED)
        every { orderRepository.findByIdWithItems(order.id) } returns order

        assertThatThrownBy { service.retryOrder(order.id) }
            .isInstanceOf(BusinessRuleViolationException::class.java)
            .hasMessageContaining("SYNCED")
    }

    @Test
    fun `retryOrder throws OrderNotFoundException for unknown id`() {
        val unknownId = UUID.randomUUID()
        every { orderRepository.findByIdWithItems(unknownId) } returns null

        assertThatThrownBy { service.retryOrder(unknownId) }
            .isInstanceOf(OrderNotFoundException::class.java)
    }
}
