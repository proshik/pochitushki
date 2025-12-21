package ru.proshik.pochitushki.configuration

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource

@Configuration
class MessageConfiguration {

    @Bean
    fun messageSource(): MessageSource {
        val source = ResourceBundleMessageSource()
        source.setBasenames("messages/messages")
        source.setDefaultEncoding("UTF-8")

        return source
    }
}