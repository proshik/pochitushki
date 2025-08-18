package ru.proshik.pochitushki.service

import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.model.UserStoreData
import ru.proshik.pochitushki.repository.UserDao

@Service
class UserService(private val userDao: UserDao) {

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
}