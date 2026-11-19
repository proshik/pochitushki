package ru.proshik.pochitushki.service.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Product metrics the alerts are built on (alerts read metrics, never logs).
 *
 * Label values are fixed vocabularies: nothing here may carry a URL, a chat id or the bot token.
 */
@Component
class AppMetrics(private val registry: MeterRegistry) {

    /**
     * A user action that failed on our side: the user got an error message, a "busy" reply or
     * nothing at all. Validation refusals (bad URL, rate limit) are not failures.
     */
    enum class Operation(val tag: String, vararg val reasons: Reason) {
        /** Saving a link: a message to the bot or `POST /api/v1/posts`. */
        SAVE_POST("save_post", Reason.ERROR),
        /** A bot command or message handler threw: the user got no answer. */
        BOT_COMMAND("bot_command", Reason.ERROR),
        /** A card button (inline keyboard or widget) threw while being handled. */
        CARD_ACTION("card_action", Reason.ERROR),
        PDF("pdf", Reason.ERROR, Reason.BUSY),
        IMPORT("import", Reason.ERROR, Reason.BUSY),
        EXPORT("export", Reason.ERROR, Reason.BUSY),
    }

    enum class Reason(val tag: String) {
        ERROR("error"),
        /** The background pool was full and the user was told to retry later. */
        BUSY("busy"),
    }

    private val operationErrors: Map<Pair<Operation, Reason>, Counter> =
        // Registered up front at zero: a series born at 1 is invisible to increase(), and the
        // first failure after a restart would not alert.
        Operation.entries.flatMap { op -> op.reasons.map { op to it } }.associateWith { (op, reason) ->
            Counter.builder(OPERATION_ERRORS)
                .description("User operations that failed on the service side")
                .tag("operation", op.tag)
                .tag("reason", reason.tag)
                .register(registry)
        }

    fun operationFailed(operation: Operation, reason: Reason = Reason.ERROR) {
        operationErrors[operation to reason]?.increment()
    }

    /**
     * A Bot API call that Telegram refused or that never got an answer.
     *
     * @param method Bot API method name, already reduced to a fixed vocabulary by the caller.
     * @param code Telegram's HTTP status ("400", "403", "429", "502", …), [CODE_IO] without a
     *   response, [CODE_DECODE] for a successful status with an unreadable body.
     */
    fun telegramError(method: String, code: String) {
        Counter.builder(TELEGRAM_ERRORS)
            .description("Failed Telegram Bot API calls")
            .tag("method", method)
            .tag("code", code)
            .register(registry)
            .increment()
    }

    companion object {
        const val OPERATION_ERRORS = "pochitushki.operation.errors"
        const val TELEGRAM_ERRORS = "pochitushki.telegram.errors"
        const val CODE_IO = "io"
        const val CODE_DECODE = "decode"

        private val METHOD = Regex("[A-Za-z]{1,64}")

        /** A method name as a label value: anything that is not a plain Bot API name becomes "other". */
        fun methodTag(method: String?): String = method?.takeIf { METHOD.matches(it) } ?: "other"
    }
}
