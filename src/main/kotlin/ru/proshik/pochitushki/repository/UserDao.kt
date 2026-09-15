package ru.proshik.pochitushki.repository

import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.model.UserSettingsData
import ru.proshik.pochitushki.model.UserStoreData
import ru.proshik.pochitushki.service.SerializationUtils

@Repository
class UserDao(private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate) {

    private val usersRowMapper = RowMapper { rs, _ ->
        UserData(
            id = rs.getLong("id"),
            telegramId = rs.getLong("telegram_id"),
            username = rs.getString("username"),
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            settings = SerializationUtils.fromJson(rs.getString("settings"), UserSettingsData::class),
            createdData = rs.getTimestamp("created_date").toLocalDateTime(),
            updatedData = rs.getTimestamp("updated_date").toLocalDateTime(),
            tokensValidAfter = rs.getTimestamp("tokens_valid_after")?.toLocalDateTime(),
        )
    }

    /**
     * Marks every token issued up to now as no longer acceptable. One second into the future,
     * because the JWT's `iat` has second precision: a token minted in the same second as the
     * logout would otherwise survive it.
     */
    fun revokeTokens(userId: Long) {
        val sql = """
            UPDATE users
            SET tokens_valid_after = NOW() + INTERVAL '1 second', updated_date = NOW()
            WHERE id = :user_id
        """.trimIndent()

        namedParameterJdbcTemplate.update(sql, MapSqlParameterSource().addValue("user_id", userId))
    }

    fun addUser(userStoreData: UserStoreData) {
        val sql = """
            INSERT INTO users(telegram_id, username, first_name, last_name, settings)
            VALUES (:telegram_id, :username, :first_name, :last_name, :settings::JSONB)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("telegram_id", userStoreData.telegramId)
            .addValue("username", userStoreData.username)
            .addValue("first_name", userStoreData.firstName)
            .addValue("last_name", userStoreData.lastName)
            .addValue("settings", SerializationUtils.toJson(userStoreData.settings))

        namedParameterJdbcTemplate.update(sql, params)
    }

    fun findUserByChatId(chatId: Long): UserData? {
        val sql = """
            SELECT id, telegram_id, username, first_name, last_name, settings, tokens_valid_after, created_date, updated_date
            FROM users
            WHERE telegram_id = :telegram_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("telegram_id", chatId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, usersRowMapper))
    }

    fun getUserByChatId(chatId: Long): UserData {
        val sql = """
            SELECT id, telegram_id, username, first_name, last_name, settings, tokens_valid_after, created_date, updated_date
            FROM users
            WHERE telegram_id = :telegram_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("telegram_id", chatId)

        return DataAccessUtils.requiredSingleResult(namedParameterJdbcTemplate.query(sql, params, usersRowMapper))
    }

    fun getUserById(userId: Long): UserData {
        val sql = """
            SELECT id, telegram_id, username, first_name, last_name, settings, tokens_valid_after, created_date, updated_date
            FROM users
            WHERE id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return DataAccessUtils.requiredSingleResult(namedParameterJdbcTemplate.query(sql, params, usersRowMapper))
    }

    fun updateUserSettings(userId: Long, settingsData: UserSettingsData) {
        val sql = """
            UPDATE users
            SET settings = :settings::JSONB
            WHERE id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)
            .addValue("settings", SerializationUtils.toJson(settingsData))

        namedParameterJdbcTemplate.update(sql, params)
    }

}