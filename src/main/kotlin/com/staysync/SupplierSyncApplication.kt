package com.staysync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SupplierSyncApplication

fun main(args: Array<String>) {
    runApplication<SupplierSyncApplication>(*args)
}
