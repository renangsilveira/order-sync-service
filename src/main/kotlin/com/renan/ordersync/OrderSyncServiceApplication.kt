package com.renan.ordersync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OrderSyncServiceApplication

fun main(args: Array<String>) {
    runApplication<OrderSyncServiceApplication>(*args)
}
