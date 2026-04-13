package com.renan.ordersync.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = "erp")
data class ErpProperties(
    val baseUrl: String,
    @DefaultValue("30")
    val timeoutSeconds: Long,
    val syncPath: String,
)
