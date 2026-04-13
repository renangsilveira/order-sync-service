package com.renan.ordersync.client

import com.renan.ordersync.config.ErpProperties
import com.renan.ordersync.domain.entity.Order
import com.renan.ordersync.exception.ErpPermanentException
import com.renan.ordersync.exception.ErpTransientException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Component
class ErpIntegrationClient(
    private val erpRestClient: RestClient,
    private val erpProperties: ErpProperties,
) {

    private val log = LoggerFactory.getLogger(ErpIntegrationClient::class.java)

    /**
     * Sends the order to the ERP sync endpoint.
     *
     * @return [ErpOrderResponse] on HTTP 2xx.
     * @throws [ErpTransientException] on 5xx or network/timeout errors — Resilience4j will retry.
     * @throws [ErpPermanentException] on 4xx or any other non-retryable failure.
     */
    fun syncOrder(order: Order): ErpOrderResponse {
        val payload = order.toErpRequest()

        log.info("Sending order orderId=[{}] to ERP [{}{}]", order.id, erpProperties.baseUrl, erpProperties.syncPath)

        return try {
            erpRestClient
                .post()
                .uri(erpProperties.syncPath)
                .body(payload)
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError) { _, response ->
                    val body = runCatching { response.body.bufferedReader().readText() }.getOrDefault("")
                    throw ErpTransientException(
                        "ERP returned transient error ${response.statusCode.value()}: $body"
                    )
                }
                .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                    val body = runCatching { response.body.bufferedReader().readText() }.getOrDefault("")
                    throw ErpPermanentException(
                        "ERP returned permanent error ${response.statusCode.value()}: $body"
                    )
                }
                .body(ErpOrderResponse::class.java)
                ?: throw ErpTransientException("ERP returned an empty response body")

        } catch (ex: ResourceAccessException) {
            throw ErpTransientException("ERP is unreachable (network/timeout): ${ex.message}", ex)
        } catch (ex: ErpTransientException) {
            throw ex
        } catch (ex: ErpPermanentException) {
            throw ex
        } catch (ex: RestClientResponseException) {
            val status = ex.statusCode
            if (status.is5xxServerError) {
                throw ErpTransientException("ERP transient error ${status.value()}: ${ex.responseBodyAsString}", ex)
            }
            throw ErpPermanentException("ERP permanent error ${status.value()}: ${ex.responseBodyAsString}", ex)
        } catch (ex: Exception) {
            throw ErpTransientException("Unexpected error calling ERP: ${ex.message}", ex)
        }
    }

    // ── Mapping helper ────────────────────────────────────────

    private fun Order.toErpRequest(): ErpOrderRequest =
        ErpOrderRequest(
            orderId = id.toString(),
            externalOrderId = externalOrderId,
            sourceSystem = sourceSystem,
            customer = ErpCustomer(name = customerName, email = customerEmail),
            currency = currency,
            totalAmount = totalAmount,
            items = items.map { item ->
                ErpOrderItem(
                    sku = item.sku,
                    name = item.name,
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    totalPrice = item.totalPrice,
                )
            },
        )
}
