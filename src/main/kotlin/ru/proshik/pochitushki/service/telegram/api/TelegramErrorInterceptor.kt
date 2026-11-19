package ru.proshik.pochitushki.service.telegram.api

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import ru.proshik.pochitushki.service.metrics.AppMetrics

/**
 * Counts failed Bot API calls made by kotlin-telegram-bot. The library hands every result back
 * to its caller as a Pair or a TelegramBotResult, and most call sites drop the error side, so
 * the HTTP client is the one place that sees all of them.
 *
 * The request URL carries the bot token (`…/bot<token>/sendMessage`); only the method name
 * after it is taken, and the URL itself is never logged or labelled.
 */
class TelegramErrorInterceptor(private val metrics: AppMetrics) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val method = methodOf(chain.request().url)
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            metrics.telegramError(method, AppMetrics.CODE_IO)
            throw e
        }
        if (!response.isSuccessful && !notModified(response)) {
            metrics.telegramError(method, response.code.toString())
        }
        return response
    }

    /** Redrawing a message into what it already shows is a success, as in [TelegramBotApi]. */
    private fun notModified(response: Response): Boolean =
        response.code == 400 && response.peekBody(PEEK_BYTES).string().contains(NOT_MODIFIED)

    companion object {
        private const val PEEK_BYTES = 4096L
        private const val NOT_MODIFIED = "message is not modified"

        /** `/bot<token>/<method>` → method; `/file/bot<token>/<path>` → "downloadFile". */
        fun methodOf(url: HttpUrl): String {
            val segments = url.pathSegments
            val tokenAt = segments.indexOfFirst { it.startsWith("bot") }
            if (tokenAt < 0) return AppMetrics.methodTag(null)
            if (segments.getOrNull(tokenAt - 1) == "file") return "downloadFile"
            return AppMetrics.methodTag(segments.getOrNull(tokenAt + 1))
        }
    }
}
