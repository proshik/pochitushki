package ru.proshik.pochitushki.service

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import ru.proshik.pochitushki.model.PostStoreData

data class PocketCsv(
    val title: String?,
    val url: String,
    @JsonProperty("time_added")
    val timeAdded: Long,
    val tags: String?,
    val status: String
)

fun PocketCsv.toPostStoreData(userId: Long) =
    PostStoreData(
        title = title,
        url = url,
        userId = userId,
        tags = if (!tags.isNullOrEmpty()) tags.split(",") else null,
        createdDate = LocalDateTime.ofInstant(
            Instant.ofEpochSecond(timeAdded),
            TimeZone.getDefault().toZoneId()
        ),
        updatedDate = LocalDateTime.now()
    )
