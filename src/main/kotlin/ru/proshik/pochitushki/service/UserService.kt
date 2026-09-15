package ru.proshik.pochitushki.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.model.UserSettingsData
import ru.proshik.pochitushki.model.UserSettingsLimits
import ru.proshik.pochitushki.model.UserStoreData
import ru.proshik.pochitushki.repository.UserDao

@Service
class UserService(private val userDao: UserDao) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun addUser(userStoreData: UserStoreData) {
        userDao.addUser(userStoreData)
    }

    fun findUserByChatId(chatId: Long): UserData? {
        return userDao.findUserByChatId(chatId)
    }

    fun getUserByChatId(chatId: Long): UserData {
        return userDao.getUserByChatId(chatId)
    }

    fun getUserByUserId(userId: Long): UserData {
        return userDao.getUserById(userId)
    }

    fun updateUserSettings(userId: Long, updatedUserSettings: UserSettingsData) {
        userDao.updateUserSettings(userId, updatedUserSettings)
    }

    /**
     * Invalidates every JWT already issued to this user (logout). The token itself stays
     * signed and unexpired — what changes is that the interceptor now refuses it.
     */
    fun revokeTokens(userId: Long) {
        userDao.revokeTokens(userId)
    }

    fun getOrCreateUser(telegramId: Long, firstName: String?, username: String?, languageCode: String): UserData {
        val existing = userDao.findUserByChatId(telegramId)
        if (existing != null) return existing

        logger.info("New user registered: telegramId={}, username={}", telegramId, username)

        val resolvedLang = if (languageCode in UserSettingsLimits.SUPPORTED_LANGUAGES) languageCode else "ru"
        return try {
            userDao.addUser(
                UserStoreData(
                    telegramId = telegramId,
                    username = username,
                    firstName = firstName,
                    lastName = null,
                    settings = UserSettingsData(languageCode = resolvedLang, tgFeedEntriesNumber = 5),
                )
            )
            userDao.getUserByChatId(telegramId)
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            userDao.getUserByChatId(telegramId)
        }
    }
}