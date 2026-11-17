package ru.proshik.pochitushki.model

import java.time.LocalDateTime

data class UserData(
    val id: Long,
    val telegramId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val settings: UserSettingsData,
    val createdData: LocalDateTime,
    val updatedData: LocalDateTime,
    /**
     * Tokens issued before this moment are refused (see JwtAuthInterceptor). Stamped by
     * logout; null for a user who has never logged out.
     */
    val tokensValidAfter: LocalDateTime? = null,
)

data class UserStoreData(
    val telegramId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val settings: UserSettingsData,
)

data class UserSettingsData(
    val languageCode: String,
    /**
     * Legacy: how many cards /feed used to send. The bot now shows one card edited in place and
     * nothing reads this. It stays so old rows keep their value on write; the default keeps
     * rows written without it readable.
     */
    val tgFeedEntriesNumber: Int = 5,
    /**
     * Show scraped og:image photos on covers. Defaults to true so rows written
     * before this key existed keep the look they had (Jackson's KotlinModule
     * fills the default in for a missing property).
     */
    val showOgCovers: Boolean = true,
)