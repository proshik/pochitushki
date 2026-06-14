package ru.proshik.pochitushki.service

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import ru.proshik.pochitushki.model.PostStoreData

data class PocketCsv(
    val title: String? = null,
    val url: String? = null,
    @JsonProperty("time_added")
    val timeAdded: Long? = null,
    val tags: String? = null,
    val status: String? = null,
    @JsonProperty("is_favorite")
    val isFavorite: Boolean = false
)

fun PocketCsv.toPostStoreData(userId: Long) =
    PostStoreData(
        title = title,
        url = url!!, // callers filter out blank-url rows before mapping
        userId = userId,
        tags = if (!tags.isNullOrEmpty()) tags.split(",") else null,
        isFavorite = isFavorite,
        createdDate = parseCreatedDate(timeAdded),
        updatedDate = LocalDateTime.now()
    )

/** Convert a Pocket epoch-seconds value to a local date, tolerating missing/out-of-range values. */
private fun parseCreatedDate(epochSeconds: Long?): LocalDateTime {
    if (epochSeconds == null) return LocalDateTime.now()
    return try {
        LocalDateTime.ofInstant(
            Instant.ofEpochSecond(epochSeconds),
            TimeZone.getDefault().toZoneId()
        )
    } catch (e: Exception) {
        LocalDateTime.now()
    }
}
