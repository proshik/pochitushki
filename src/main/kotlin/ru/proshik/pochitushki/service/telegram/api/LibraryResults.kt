package ru.proshik.pochitushki.service.telegram.api

import com.github.kotlintelegrambot.network.Response as BotApiResponse
import retrofit2.Response

/**
 * Whether a kotlin-telegram-bot call that answers with a `Pair` (`sendDocument`, …) was accepted.
 * Such calls never throw: a refusal (413 for a file too large, 403 for a blocked bot) comes back
 * as the pair's other half and is easy to read as success.
 */
fun Pair<Response<out BotApiResponse<*>?>?, Exception?>.delivered(): Boolean =
    second == null && first?.isSuccessful == true && first?.body()?.ok == true
