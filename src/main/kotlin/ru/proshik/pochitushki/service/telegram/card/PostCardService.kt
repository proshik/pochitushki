package ru.proshik.pochitushki.service.telegram.card

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.UserService

/**
 * The post card: one message per command, redrawn in place by every button.
 *
 * Position is the card's `offset` in the same order `PostService.getPosts` pages that list;
 * the post itself is identified by the ref's id and table, so a button on a stale card acts
 * on the post it was drawn for, never on whatever happens to sit at that offset now.
 */
@Service
class PostCardService(
    private val userService: UserService,
    private val postService: PostService,
    private val i18n: I18nService,
    private val presenter: TextCardPresenter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** /feed, /archive, /favorites: one message with the first post of the list. */
    fun sendCard(chatId: Long, list: CardList, replyTo: Long? = null) {
        val user = userService.findUserByChatId(chatId) ?: run {
            logger.warn("sendCard: no user for chatId={}", chatId)
            return
        }
        val lang = user.settings.languageCode
        val view = load(user, list, 0) ?: CardView.Notice(i18n.getMessage(emptyKey(list), lang))
        presenter.send(chatId, view, lang, replyTo)
        logger.info("sendCard success: chatId={}, list={}", chatId, list)
    }

    fun onCallback(chatId: Long, messageId: Long, action: String, ref: CardRef, extra: String?): CallbackAnswer {
        val user = userService.findUserByChatId(chatId) ?: return CallbackAnswer()
        when (action) {
            CardAction.NAV -> redrawAt(user, chatId, messageId, ref.list, ref.offset)
            CardAction.NOOP -> Unit
            else -> logger.warn("card action not handled: {}", action)
        }
        return CallbackAnswer()
    }

    /** Redraws the card at [offset], stepping back if the list got shorter. */
    private fun redrawAt(user: UserData, chatId: Long, messageId: Long, list: CardList, offset: Int) {
        val lang = user.settings.languageCode
        val view = load(user, list, offset) ?: CardView.Notice(i18n.getMessage(emptyKey(list), lang))
        presenter.redraw(chatId, messageId, view, lang)
    }

    /** The post at [offset] of [list], clamped to the list's end; null when the list is empty. */
    private fun load(user: UserData, list: CardList, offset: Int): CardView.Post? {
        val total = postService.getPostCount(user.id, list.postType)
        if (total == 0) return null
        val at = offset.coerceIn(0, total - 1)
        val post = postService.getPosts(user.id, list.postType, 1, at).firstOrNull() ?: return null
        return CardView.Post(post, CardRef(list, at, post.id, PostLoc.of(post.isArchived)), total)
    }

    private fun emptyKey(list: CardList): String = when (list) {
        CardList.FAVORITES -> "command.feed.not_found_favorites"
        CardList.RANDOM -> "command.random_post.not_found"
        else -> "command.feed.not_found_post"
    }
}
