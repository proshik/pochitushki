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
import ru.proshik.pochitushki.model.UserSettingsData
import ru.proshik.pochitushki.service.UserService

data class SettingsPatchRequest(
    val languageCode: String? = null,
    val tgFeedEntriesNumber: Int? = null,
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
        val updated = UserSettingsData(
            languageCode = patch.languageCode ?: current.languageCode,
            tgFeedEntriesNumber = patch.tgFeedEntriesNumber ?: current.tgFeedEntriesNumber,
        )
        userService.updateUserSettings(userId, updated)
        return ResponseEntity.ok().build()
    }
}
