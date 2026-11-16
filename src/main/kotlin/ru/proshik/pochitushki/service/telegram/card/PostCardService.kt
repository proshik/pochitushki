package ru.proshik.pochitushki.service.telegram.card

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.service.ConcurrentPostMoveException
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.UserService
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard

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
    private val keyboard: TelegramKeyboard,
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
        val card = Card(user, chatId, messageId, ref)
        val answer = when (action) {
            CardAction.NAV -> card.redrawAt(ref.offset)
            CardAction.NOOP -> null
            CardAction.ARCHIVE -> card.move(toArchive = true)
            CardAction.UNREAD -> card.move(toArchive = false)
            CardAction.DELETE_ASK -> card.redrawButtons { it.copy(confirmDelete = true) }
            CardAction.DELETE_NO -> card.redrawButtons { it }
            CardAction.DELETE_YES -> card.delete()
            CardAction.FAVORITE -> card.toggleFavorite()
            else -> {
                logger.warn("card action not handled: {}", action)
                null
            }
        }
        logger.info("card action success: chatId={}, action={}, ref={}", chatId, action, ref.encode())
        return answer ?: CallbackAnswer()
    }

    /** One button press on one card message. Every action answers null or a toast. */
    private inner class Card(val user: UserData, val chatId: Long, val messageId: Long, val ref: CardRef) {
        val lang: String = user.settings.languageCode

        /** The post the button was drawn for, or null when it is gone (removed via the site, or a lost race). */
        fun post() = postService.getPost(ref.postId, user.id, ref.loc.postType)

        fun total(): Int = postService.getPostCount(user.id, ref.list.postType)

        /** Redraws the card at [offset], stepping back if the list got shorter. */
        fun redrawAt(offset: Int): CallbackAnswer? {
            val view = load(user, ref.list, offset) ?: CardView.Notice(i18n.getMessage("card.all_read", lang))
            presenter.redraw(chatId, messageId, view, lang)
            return null
        }

        /** The post is gone: say so, and show what now sits at this offset. */
        fun gone(): CallbackAnswer {
            redrawAt(ref.offset)
            return CallbackAnswer(i18n.getMessage("card.toast.gone", lang))
        }

        fun redrawButtons(change: (CardView.Post) -> CardView.Post): CallbackAnswer? {
            val post = post() ?: return gone()
            val view = change(CardView.Post(post, ref, total()))
            presenter.redrawKeyboard(chatId, messageId, keyboard.buildCardKeyboard(view, lang))
            return null
        }

        /**
         * Archive or back to unread. In favorites the post stays on the card under its new id —
         * it is still a favorite, only its button changes; elsewhere it leaves the list and the
         * next post takes its offset.
         */
        fun move(toArchive: Boolean): CallbackAnswer? {
            val post = post() ?: return gone()
            val newId = try {
                if (toArchive) postService.archivePost(post.id, user.id) else postService.unreadPost(post.id, user.id)
            } catch (e: ConcurrentPostMoveException) {
                null
            } ?: return gone()

            if (ref.list == CardList.FAVORITES) {
                val loc = if (toArchive) PostLoc.ARCHIVE else PostLoc.UNREAD
                val moved = post.copy(id = newId, isArchived = toArchive)
                presenter.redraw(chatId, messageId, CardView.Post(moved, ref.copy(postId = newId, loc = loc), total()), lang)
                return null
            }
            return redrawAt(ref.offset)
        }

        fun delete(): CallbackAnswer? {
            if (postService.deletePost(ref.postId, user.id, ref.loc.postType) == 0) return gone()
            return redrawAt(ref.offset)
        }

        /** Only the star changes, so only the keyboard is redrawn. */
        fun toggleFavorite(): CallbackAnswer? {
            postService.toggleFavorite(ref.postId, user.id, ref.loc.postType) ?: return gone()
            return redrawButtons { it }
        }
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
