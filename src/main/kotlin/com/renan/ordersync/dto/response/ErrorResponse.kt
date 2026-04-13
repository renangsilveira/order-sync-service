package com.renan.ordersync.dto.response

import java.time.LocalDateTime
import java.time.ZoneOffset

data class ErrorResponse(
    val timestamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val fieldErrors: List<FieldErrorDetail>? = null,
)

data class FieldErrorDetail(
    val field: String,
    val rejectedValue: Any?,
    val message: String,
)
