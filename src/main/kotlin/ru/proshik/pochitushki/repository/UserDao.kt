package ru.proshik.pochitushki.repository

import org.springframework.dao.support.DataAccessUtils
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.model.UserStoreData

@Repository
class UserDao(private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate) {

    private val usersRowMapper = RowMapper { rs, _ ->
        UserData(
            id = rs.getLong("id"),
            telegramId = rs.getLong("telegram_id"),
            username = rs.getString("username"),
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            createdData = rs.getTimestamp("created_date").toLocalDateTime(),
            updatedData = rs.getTimestamp("updated_date").toLocalDateTime(),
        )
    }

    fun addUser(userStoreData: UserStoreData) {
        val sql = """
            INSERT INTO users(telegram_id, username, first_name, last_name)
            VALUES (:telegram_id, :username, :first_name, :last_name)
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("telegram_id", userStoreData.telegramId)
            .addValue("username", userStoreData.username)
            .addValue("first_name", userStoreData.firstName)
            .addValue("last_name", userStoreData.lastName)

        namedParameterJdbcTemplate.update(sql, params)
    }

    fun findUserByChatId(chatId: Long): UserData? {
        val sql = """
            SELECT id, telegram_id, username, first_name, last_name, created_date, updated_date
            FROM users
            WHERE telegram_id = :telegram_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("telegram_id", chatId)

        return DataAccessUtils.singleResult(namedParameterJdbcTemplate.query(sql, params, usersRowMapper))
    }

    fun getUserByChatId(chatId: Long): UserData {
        val sql = """
            SELECT id, telegram_id, username, first_name, last_name, created_date, updated_date
            FROM users
            WHERE telegram_id = :telegram_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("telegram_id", chatId)

        return DataAccessUtils.requiredSingleResult(namedParameterJdbcTemplate.query(sql, params, usersRowMapper))
    }

    fun getUserById(userId: Long): UserData {
        val sql = """
            SELECT id, telegram_id, username, first_name, last_name, created_date, updated_date
            FROM users
            WHERE id = :user_id
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("user_id", userId)

        return DataAccessUtils.requiredSingleResult(namedParameterJdbcTemplate.query(sql, params, usersRowMapper))
    }

}