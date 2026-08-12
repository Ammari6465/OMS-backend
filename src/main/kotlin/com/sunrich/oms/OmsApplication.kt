package com.sunrich.oms

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OmsApplication

fun main(args: Array<String>) {
    runApplication<OmsApplication>(*args)
}
