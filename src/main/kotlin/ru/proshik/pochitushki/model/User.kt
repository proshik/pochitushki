package ru.proshik.pochitushki.model

import java.time.LocalDateTime

data class UserData(
    val id: Long,
    val telegramId: Long,
    val username: String,
    val firstName: String?,
    val lastName: String?,
    val createdData: LocalDateTime,
    val updatedData: LocalDateTime
)

data class UserStoreData(
    val telegramId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)