package com.renan.ordersync.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(ErpProperties::class)
class AppConfig {

    /**
     * Application-scoped CoroutineScope used to launch async order processing.
     * Uses [SupervisorJob] so that a failure in one coroutine does not cancel others.
     * Uses [Dispatchers.IO] because the work is I/O-bound (DB writes, HTTP calls).
     */
    // CoroutineScope.cancel() is a Kotlin extension function (static in bytecode), so Spring
    // cannot find it via reflection. Wrapping in an anonymous object gives a real cancel() method.
    @Bean(destroyMethod = "cancel")
    fun applicationCoroutineScope(): CoroutineScope {
        val job = SupervisorJob()
        return object : CoroutineScope {
            override val coroutineContext = job + Dispatchers.IO
            fun cancel() = job.cancel()
        }
    }

    /**
     * Configures the [RestClient] used by [com.renan.ordersync.client.ErpIntegrationClient].
     * Timeout is applied at the request factory level via the connect/read timeouts.
     */
    @Bean
    fun erpRestClient(erpProperties: ErpProperties): RestClient {
        val timeout = Duration.ofSeconds(erpProperties.timeoutSeconds)
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeout)
            setReadTimeout(timeout)
        }
        return RestClient.builder()
            .baseUrl(erpProperties.baseUrl)
            .requestFactory(factory)
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build()
    }
}
