package ru.proshik.pochitushki.configuration

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.proshik.pochitushki.service.OpenhtmlPdfGenerator
import ru.proshik.pochitushki.service.PdfGenerator
import ru.proshik.pochitushki.service.PlaywrightPdfGenerator
import ru.proshik.pochitushki.service.UrlSecurityValidator

@Configuration
class PdfConfiguration {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun openhtmlPdfGenerator(urlSecurityValidator: UrlSecurityValidator): PdfGenerator {
        logger.info("Using OpenHTML PDF engine")
        return OpenhtmlPdfGenerator(urlSecurityValidator)
    }

    @Bean
    @ConditionalOnProperty(name = ["pdf.playwright.enabled"], havingValue = "true")
    fun playwrightPdfGenerator(urlSecurityValidator: UrlSecurityValidator): PdfGenerator {
        logger.info("Using Playwright PDF engine")
        return PlaywrightPdfGenerator(urlSecurityValidator)
    }
}
