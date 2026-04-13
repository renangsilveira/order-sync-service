package com.renan.ordersync.domain.enums

enum class ProcessingEventType {
    ORDER_RECEIVED,
    PROCESSING_STARTED,
    ERP_SYNC_ATTEMPTED,
    ERP_SYNC_SUCCEEDED,
    ERP_SYNC_FAILED,
    MANUAL_RETRY_REQUESTED
}
