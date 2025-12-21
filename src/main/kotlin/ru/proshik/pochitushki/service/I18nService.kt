package ru.proshik.pochitushki.service

import org.apache.commons.lang3.LocaleUtils
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service

@Service
class I18nService(private val messageSource: MessageSource) {

    fun getMessage(code: String, locale: String): String {
        return messageSource.getMessage(code, arrayOf(), LocaleUtils.toLocale(locale))
    }

    fun getMessage(code: String, locale: String, args: Array<Any>): String {
        return messageSource.getMessage(code, args, LocaleUtils.toLocale(locale))
    }
}