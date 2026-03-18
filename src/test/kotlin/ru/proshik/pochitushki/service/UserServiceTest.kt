package ru.proshik.pochitushki.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.UserSettingsData
import ru.proshik.pochitushki.model.UserStoreData

class UserServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    @Test
    fun `addUser creates user successfully`() {
        val settings = UserSettingsData("en", 3)
        val userStore = UserStoreData(500L, "testuser", "Test", "User", settings)

        userService.addUser(userStore)

        val user = userService.findUserByChatId(500L)
        assertNotNull(user)
        assertEquals("testuser", user!!.username)
        assertEquals("Test", user.firstName)
        assertEquals("User", user.lastName)
        assertEquals("en", user.settings.languageCode)
        assertEquals(3, user.settings.tgFeedEntriesNumber)
    }

    @Test
    fun `findUserByChatId returns user when exists`() {
        createUser(501L, "existing")

        val user = userService.findUserByChatId(501L)
        assertNotNull(user)
        assertEquals(501L, user!!.telegramId)
    }

    @Test
    fun `findUserByChatId returns null when not exists`() {
        val user = userService.findUserByChatId(999999L)
        assertNull(user)
    }

    @Test
    fun `getUserByChatId returns user`() {
        createUser(502L, "getuser")

        val user = userService.getUserByChatId(502L)
        assertEquals(502L, user.telegramId)
    }

    @Test
    fun `getUserByChatId throws when not found`() {
        assertThrows(IncorrectResultSizeDataAccessException::class.java) {
            userService.getUserByChatId(888888L)
        }
    }

    @Test
    fun `getUserByUserId returns user`() {
        createUser(503L, "byid")

        val found = userService.findUserByChatId(503L)!!
        val user = userService.getUserByUserId(found.id)
        assertEquals(503L, user.telegramId)
    }

    @Test
    fun `updateUserSettings updates language code`() {
        createUser(504L, "lang")

        val user = userService.findUserByChatId(504L)!!
        assertEquals("en", user.settings.languageCode)

        val updatedSettings = user.settings.copy(languageCode = "ru")
        userService.updateUserSettings(user.id, updatedSettings)

        val updated = userService.findUserByChatId(504L)!!
        assertEquals("ru", updated.settings.languageCode)
    }

    @Test
    fun `updateUserSettings updates feed entries number`() {
        createUser(505L, "feed")

        val user = userService.findUserByChatId(505L)!!
        assertEquals(3, user.settings.tgFeedEntriesNumber)

        val updatedSettings = user.settings.copy(tgFeedEntriesNumber = 5)
        userService.updateUserSettings(user.id, updatedSettings)

        val updated = userService.findUserByChatId(505L)!!
        assertEquals(5, updated.settings.tgFeedEntriesNumber)
    }

    private fun createUser(telegramId: Long, username: String) {
        val settings = UserSettingsData("en", 3)
        val userStore = UserStoreData(telegramId, username, "First", "Last", settings)
        userService.addUser(userStore)
    }
}
