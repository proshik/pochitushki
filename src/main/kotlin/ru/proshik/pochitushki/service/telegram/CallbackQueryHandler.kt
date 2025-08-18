package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.CallbackQuery
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.TelegramService

/**
 * Handler for Telegram callback queries.
 */
@Component
class CallbackQueryHandler(
    private val telegramService: TelegramService,
) : TelegramUpdateHandler {

    //    private val logger = LoggerFactory.getLogger(javaClass)
//
//    private val summaryExecutorService = Executors.newFixedThreadPool(2)
//
//    private val executedByUser = ConcurrentHashMap<Long, Int>()
//
    override fun registerHandlers(dispatcher: Dispatcher) {
        dispatcher.callbackQuery { handleCallbacks(callbackQuery) }
    }

    fun parseCallbackData(callbackData: String): Pair<String, String> {
        val (action, data) = callbackData.split("|")

        return Pair(action, data)
    }

    private fun handleCallbacks(callbackQuery: CallbackQuery) {
        val message = callbackQuery.message ?: return

        val chatId = message.chat.id
        val messageId = message.messageId

        val callbackData = callbackQuery.data

        val (action, data) = parseCallbackData(callbackData)

        when (action) {
            TelegramKeyboard.CALLBACK_ARCHIVE_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.archivePost(chatId, message.messageId, postId)
            }

            TelegramKeyboard.CALLBACK_DELETE_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.deletePost(chatId, message.messageId, postId, PostType.UNREAD)
            }

            TelegramKeyboard.CALLBACK_UNREAD_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.unreadPost(chatId, message.messageId, postId)
            }

            TelegramKeyboard.CALLBACK_DELETE_ARCHIVE_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.deletePost(chatId, message.messageId, postId, PostType.ARCHIVE)
            }

            TelegramKeyboard.CALLBACK_NEXT_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getFeed(chatId, messageId, offset)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getFeed(chatId, messageId, offset)
            }

            TelegramKeyboard.CALLBACK_NEXT_ARCHIVE_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getArchive(chatId, messageId, offset)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_ARCHIVE_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getArchive(chatId, messageId, offset)
            }

            else -> throw RuntimeException("Unknown callback action $action")
        }

//
//        val userSettings = telegramService.getUserSettingsByChatId(chatId)
//
//        val sportType = callbackData.startsWith("sport_type_")
//        if (sportType) {
//            val sportTypeValue = callbackData.replace("sport_type_", "")
//            val sportTypes = userSettings.settings.sportTypes.toMutableMap()
//
//            if (sportTypes[sportTypeValue]!!) {
//                sportTypes[sportTypeValue] = false
//            } else {
//                sportTypes[sportTypeValue] = true
//            }
//
//            // обновить профиль пользователя
//            val updatedUserSettings = UserSettings(userSettings.settings.language, sportTypes)
//            telegramService.updateUserSettings(chatId, updatedUserSettings)
//
//            telegramService.editMessageText(
//                chatId = chatId,
//                messageId = callbackQuery.message?.messageId ?: 0,
//                text = "Выбери типы спорта, учитываемые при построении статистики по отдельным активностям:",
//                replyMarkup = yearsKeyboardByType(sportTypes)
//            )
//            return
//        }
//
//        val language = callbackData.startsWith("language_")
//        if (language) {
//            val languageCode = callbackData.replace("language_", "")
//            // обновить профиль пользователя
//            val updatedUserSettings = UserSettings(languageCode, userSettings.settings.sportTypes)
//            telegramService.updateUserSettings(chatId, updatedUserSettings)
//
//            telegramService.editMessageText(
//                chatId = chatId,
//                messageId = callbackQuery.message?.messageId ?: 0,
//                text = "Выберите язык интерфейса и сообщений среди следующих вариантов:",
//                replyMarkup = languageKeyboard(languageCode)
//            )
//            return
//        }
//
//        val confirmYear = callbackData.startsWith("confirm_")
//        if (confirmYear) {
//            val extractedConfirmYear = callbackData.replace("confirm_", "")
//
//            val year = try {
//                extractedConfirmYear.toInt()
//            } catch (ex: Exception) {
//                logger.warn("error on parse year", ex)
//
//                telegramService.sendMessage(
//                    chatId = chatId,
//                    text = "Ошибка! Повторите запрос или обратитесь к @proshik",
//                    replyMarkup = KeyboardReplyMarkup(keyboard = mainKeyboard(), resizeKeyboard = true)
//                )
//                return
//            }
//
//            calculateSummary(message.messageId, chatId, false, year)
//            sendConfirmMessage(false, year, chatId, message)
//
//            return
//        }
//
//        val selectedYear = callbackData.startsWith("year_")
//        if (selectedYear) {
//            val rawYear = callbackData.replace("year_", "")
//
//            val year = try {
//                rawYear.toInt()
//            } catch (ex: Exception) {
//                logger.warn("error on parse year", ex)
//
//                telegramService.sendMessage(
//                    chatId = chatId,
//                    text = "Ошибка! Повторите запрос или обратитесь к @proshik",
//                    replyMarkup = KeyboardReplyMarkup(keyboard = mainKeyboard(), resizeKeyboard = true)
//                )
//                return
//            }
//
//            // запуск метода расчета статистики (
//            val user = telegramService.findAuthenticatedUserByChatId(chatId)
//            if (user == null) {
//                throw RuntimeException("user not found: chatId=$chatId")
//            }
//
//            val summary = try {
//                summaryService.findSummaryByUserId(user.id)
//            } catch (ex: Exception) {
//                logger.warn("error on fetch data from DB by userId={}, ex.message={}", user.id, ex.message)
//                null
//            }
//
//            if (summary != null && summary.byYear[year.toString()] != null) {
//                // Вывести информацию о том, что статистика посчитана. Нарисовать кнопки: отобразить статистику за <year>,
//                // обновить статистику за <year>
//                telegramService.editMessageText(
//                    chatId = chatId,
//                    messageId = message.messageId,
//                    text = "Статистика за $year уже посчитана! Показать статистику или обновить?",
//                    replyMarkup = confirmKeyboard(year.toString())
//                )
//
//                return
//            } else {
//                calculateSummary(message.messageId, chatId, false, year)
//            }
//
//            sendConfirmMessage(false, year, chatId, message)
//        }
//
//        val refreshYear = callbackData.startsWith("refresh_")
//        if (refreshYear) {
//            val rawYear = callbackData.replace("refresh_", "")
//
//            val year = try {
//                rawYear.toInt()
//            } catch (ex: Exception) {
//                logger.warn("error on parse year", ex)
//
//                telegramService.sendMessage(
//                    chatId = chatId,
//                    text = "Ошибка! Повторите запрос или обратитесь к @proshik",
//                    replyMarkup = KeyboardReplyMarkup(keyboard = mainKeyboard(), resizeKeyboard = true)
//                )
//                return
//            }
//            // запуск метода расчета статистики (
//            calculateSummary(message.messageId, chatId, true, year)
//
//            sendConfirmMessage(true, year, chatId, message)
//        }
//    }
//
//    private fun sendConfirmMessage(toRefresh: Boolean, year: Int, chatId: Long, message: Message) {
//        val messageText = if (toRefresh) {
//            """
//                Обновляю статистику за $year год 🔄
//            """.trimIndent()
//        } else {
//            """
//                Считаю статистику за $year год 🔄
//            """.trimIndent()
//        }
//
//        telegramService.editMessageText(chatId = chatId, messageId = message.messageId, text = messageText)
//    }
//
//    private fun calculateSummary(messageId: Long, chatId: Long, toUpdate: Boolean, year: Int) {
//        val alreadyExecuted = executedByUser[chatId]
//        if (alreadyExecuted != null) {
//            telegramService.editMessageText(
//                chatId = chatId,
//                messageId = messageId,
//                text = "Уже считаю статистику за $alreadyExecuted год! Скоро пришлю результат \uD83D\uDD03"
//            )
//            return
//        }
//
//        summaryExecutorService.submit {
//            try {
//                buildSummaryAndSend(messageId, chatId, toUpdate, year)
//            } finally {
//                executedByUser.remove(chatId)
//            }
//        }
//        // сохраняем информацию, что запустили процесс генерации по пользователю
//        executedByUser[chatId] = year
//
//        logger.info("submitted task to execute operation of generation: chatId={}", chatId)
//    }
//
//    private fun buildSummaryAndSend(messageId: Long, chatId: Long, toUpdate: Boolean, year: Int) {
//        logger.debug("start execution of build summary: chatId={}", chatId)
//
//        val summary = try {
//            summaryService.getSummary(chatId, year, toUpdate)
//        } catch (ex: SummaryService.StravaAccessException) {
//            logger.warn("error getting summary: chatId={}, year={}, toUpdate={}", chatId, year, toUpdate, ex)
//
//            telegramService.sendMessage(
//                chatId = chatId,
//                text = """
//                                Ошибка генерации картинки!
//                                Судя по всему, при входе в Strava, не поставили галочку Просматривать закрытые данные физической активности!
//                                Попробуйте разлгиниться и Войти через Strava ещё раз!
//                            """.trimIndent(),
//                replyMarkup = KeyboardReplyMarkup(keyboard = mainKeyboard(), resizeKeyboard = true)
//            )
//
//            return
//        } catch (ex: Exception) {
//            logger.warn("error on calculate summary: chatId={}, year={}", chatId, year, ex)
//
//            telegramService.sendMessage(
//                chatId = chatId,
//                text = """
//                    Ошибка генерации картинки! Попробуйте выполнить команду /logout и опять подключить аккаунт Strava.
//
//                    В случае повторения ошибки - обратитесь к @proshik
//                """.trimIndent(),
//                replyMarkup = KeyboardReplyMarkup(keyboard = mainKeyboard(), resizeKeyboard = true)
//            )
//
//            return
//        }
//
//        if (summary == null) {
//            telegramService.sendMessage(
//                chatId = chatId,
//                text = "За $year год нет активностей по вашему профилю Strava, поэтому не могу построить инфографику!",
//                replyMarkup = KeyboardReplyMarkup(keyboard = mainKeyboard(), resizeKeyboard = true)
//            )
//
//            logger.info("send empty message, because not found activities: chatId={}", chatId)
//            return
//        }
//
//        telegramService.generateImagesAndSend(messageId, chatId, summary)
//
//        logger.info("success handle task of summary: chatId={}", chatId)
//    }
//
//    // Keyboard creation methods
//    private fun yearsKeyboardByType(sportTypes: Map<String, Boolean>): InlineKeyboardMarkup {
//        var i = 1
//        val resultList = mutableListOf<List<InlineKeyboardButton>>()
//
//        val row = mutableListOf<InlineKeyboardButton>()
//        sportTypes.forEach { (type, selected) ->
//            val inlineKeyboardButton = if (selected) {
//                InlineKeyboardButton.CallbackData(text = "$type ✅", callbackData = "sport_type_${type}")
//            } else {
//                InlineKeyboardButton.CallbackData(text = type, callbackData = "sport_type_${type}")
//            }
//            row.add(inlineKeyboardButton)
//
//            if (i % 3 == 0) {
//                resultList.addAll(listOf(row.toList()))
//                row.clear()
//            }
//            i += 1
//        }
//        if (row.isNotEmpty()) {
//            resultList.addAll(listOf(row.toList()))
//        }
//
//        return InlineKeyboardMarkup.create(resultList)
//    }
//
//    private fun languageKeyboard(language: String): InlineKeyboardMarkup {
//        return when (language) {
//            "ru" -> InlineKeyboardMarkup.create(
//                listOf(InlineKeyboardButton.CallbackData(text = "\uD83C\uDDF7\uD83C\uDDFA Russian", callbackData = "language_ru")),
//                listOf(InlineKeyboardButton.CallbackData(text = "English", callbackData = "language_en"))
//            )
//
//            "en" -> InlineKeyboardMarkup.create(
//                listOf(InlineKeyboardButton.CallbackData(text = "Russian", callbackData = "language_ru")),
//                listOf(InlineKeyboardButton.CallbackData(text = "\uD83C\uDDEC\uD83C\uDDE7 English", callbackData = "language_en"))
//            )
//
//            else -> throw RuntimeException("not found language by tag=$language")
//        }
//    }
//
//    private fun confirmKeyboard(year: String): InlineKeyboardMarkup = InlineKeyboardMarkup.create(
//        listOf(InlineKeyboardButton.CallbackData(text = "Показать статистику за $year", callbackData = "confirm_$year")),
//        listOf(InlineKeyboardButton.CallbackData(text = "Обновить статистику за $year", callbackData = "refresh_$year"))
//    )
    }
}
