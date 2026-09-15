package ru.proshik.pochitushki

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients

@EnableFeignClients
@EnableScheduling
@SpringBootApplication
class PochitushkiApplication

fun main(args: Array<String>) {
    runApplication<PochitushkiApplication>(*args)
}
