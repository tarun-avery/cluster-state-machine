package com.example.clusterstatemachine.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TradingGatewayApplication

fun main(args: Array<String>) {
  runApplication<TradingGatewayApplication>(*args)
}
