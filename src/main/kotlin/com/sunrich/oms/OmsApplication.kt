package com.sunrich.oms

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class OmsApplication

fun main(args: Array<String>) {
    runApplication<OmsApplication>(*args)
}
