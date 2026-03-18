package ru.proshik.pochitushki.configuration

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.proshik.pochitushki.service.OpenhtmlPdfGenerator
import ru.proshik.pochitushki.service.PdfGenerator
import ru.proshik.pochitushki.service.PlaywrightPdfGenerator

@Configuration
class PdfConfiguration {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnProperty(name = ["pdf.engine"], havingValue = "openhtml", matchIfMissing = true)
    fun openhtmlPdfGenerator(): PdfGenerator {
        logger.info("Using OpenHTML PDF engine")
        return OpenhtmlPdfGenerator()
    }

    @Bean
    @ConditionalOnProperty(name = ["pdf.engine"], havingValue = "playwright")
    fun playwrightPdfGenerator(): PdfGenerator {
        logger.info("Using Playwright PDF engine")
        return PlaywrightPdfGenerator()
    }
}
