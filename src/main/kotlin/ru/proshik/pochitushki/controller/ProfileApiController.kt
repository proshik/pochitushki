package ru.proshik.pochitushki.controller

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.model.UserSettingsLimits
import ru.proshik.pochitushki.service.UserService

data class SettingsPatchRequest(
    val languageCode: String? = null,
    val showOgCovers: Boolean? = null,
    val feedSort: String? = null,
)

@RestController
@RequestMapping("/api/v1/profile")
class ProfileApiController(private val userService: UserService) {

    @PostMapping("/settings")
    fun updateSettings(
        @RequestAttribute("userId") userId: Long,
        @RequestBody patch: SettingsPatchRequest,
    ): ResponseEntity<Void> {
        val current = try {
            userService.getUserByUserId(userId).settings
        } catch (e: EmptyResultDataAccessException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        // The language picks the resource bundle for the bot and the site, so it is checked
        // against what exists rather than trusted.
        patch.languageCode?.let {
            if (it !in UserSettingsLimits.SUPPORTED_LANGUAGES) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported language")
            }
        }
        patch.feedSort?.let {
            if (it !in UserSettingsLimits.FEED_SORTS) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported feed sort")
            }
        }
        // copy(), not a new object: the legacy tgFeedEntriesNumber keeps its stored value.
        val updated = current.copy(
            languageCode = patch.languageCode ?: current.languageCode,
            showOgCovers = patch.showOgCovers ?: current.showOgCovers,
            feedSort = patch.feedSort ?: current.feedSort,
        )

        userService.updateUserSettings(userId, updated)

        return ResponseEntity.ok().build()
    }
}
