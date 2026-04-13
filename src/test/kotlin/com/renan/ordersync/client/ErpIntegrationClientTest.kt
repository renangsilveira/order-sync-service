package com.renan.ordersync.client

import com.renan.ordersync.config.ErpProperties
import com.renan.ordersync.exception.ErpPermanentException
import com.renan.ordersync.exception.ErpTransientException
import com.renan.ordersync.fixtures.TestFixtures
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

/**
 * Unit tests for [ErpIntegrationClient].
 *
 * Mocks the [RestClient] fluent chain. The `onStatus` handlers registered in the client
 * throw [ErpTransientException] / [ErpPermanentException] before reaching `body()`.
 * We simulate this by having `body()` throw the appropriate exception, which is exactly
 * what would happen when a registered status handler fires during HTTP response processing.
 *
 * Full status-code-to-exception mapping is verified by WireMock integration tests in
 * [com.renan.ordersync.integration.OrderProcessingFlowIntegrationTest].
 */
class ErpIntegrationClientTest {

    private val erpRestClient: RestClient = mockk()
    private val erpProperties = ErpProperties(
        baseUrl = "http://erp-mock",
        timeoutSeconds = 5,
        syncPath = "/api/erp/orders",
    )

    private val client = ErpIntegrationClient(erpRestClient, erpProperties)

    // RestClient fluent chain mocks
    private val postSpec: RestClient.RequestBodyUriSpec = mockk()
    private val bodyUriSpec: RestClient.RequestBodySpec = mockk()
    private val responseSpec: RestClient.ResponseSpec = mockk()

    @BeforeEach
    fun setUp() {
        every { erpRestClient.post() } returns postSpec
        every { postSpec.uri(erpProperties.syncPath) } returns bodyUriSpec
        every { bodyUriSpec.body(any<Any>()) } returns bodyUriSpec
        every { bodyUriSpec.retrieve() } returns responseSpec
        // onStatus handlers are registered but the mock returns the same spec
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
    }

    @Test
    fun `syncOrder returns ErpOrderResponse on 2xx`() {
        val order = TestFixtures.createOrder()
        val expectedResponse = ErpOrderResponse(erpOrderId = "ERP-777", status = "ACCEPTED", message = null)
        every { responseSpec.body(ErpOrderResponse::class.java) } returns expectedResponse

        val result = client.syncOrder(order)

        assertThat(result.erpOrderId).isEqualTo("ERP-777")
        assertThat(result.status).isEqualTo("ACCEPTED")
    }

    @Test
    fun `syncOrder throws ErpTransientException when 5xx handler fires`() {
        // Simulates what the onStatus(is5xxServerError) handler does before body() is called
        val order = TestFixtures.createOrder()
        every { responseSpec.body(ErpOrderResponse::class.java) } throws
            ErpTransientException("ERP returned transient error 503: Service Unavailable")

        assertThatThrownBy { client.syncOrder(order) }
            .isInstanceOf(ErpTransientException::class.java)
            .hasMessageContaining("503")
    }

    @Test
    fun `syncOrder throws ErpPermanentException when 4xx handler fires`() {
        val order = TestFixtures.createOrder()
        every { responseSpec.body(ErpOrderResponse::class.java) } throws
            ErpPermanentException("ERP returned permanent error 400: Bad Request")

        assertThatThrownBy { client.syncOrder(order) }
            .isInstanceOf(ErpPermanentException::class.java)
            .hasMessageContaining("400")
    }

    @Test
    fun `syncOrder throws ErpTransientException on ResourceAccessException (network failure)`() {
        val order = TestFixtures.createOrder()
        every { responseSpec.body(ErpOrderResponse::class.java) } throws
            ResourceAccessException("Connection refused: erp-mock")

        assertThatThrownBy { client.syncOrder(order) }
            .isInstanceOf(ErpTransientException::class.java)
            .hasMessageContaining("unreachable")
    }

    @Test
    fun `syncOrder throws ErpTransientException when body is null`() {
        val order = TestFixtures.createOrder()
        every { responseSpec.body(ErpOrderResponse::class.java) } returns null

        assertThatThrownBy { client.syncOrder(order) }
            .isInstanceOf(ErpTransientException::class.java)
            .hasMessageContaining("empty response body")
    }

    @Test
    fun `syncOrder includes orderId in ERP request payload`() {
        val order = TestFixtures.createOrder()
        val captured = mutableListOf<Any>()
        every { bodyUriSpec.body(capture(captured)) } returns bodyUriSpec
        every { responseSpec.body(ErpOrderResponse::class.java) } returns
            ErpOrderResponse("ERP-1", "OK", null)

        client.syncOrder(order)

        assertThat(captured).hasSize(1)
        val payload = captured[0] as ErpOrderRequest
        assertThat(payload.orderId).isEqualTo(order.id.toString())
        assertThat(payload.externalOrderId).isEqualTo(order.externalOrderId)
        assertThat(payload.currency).isEqualTo(order.currency)
    }
}
