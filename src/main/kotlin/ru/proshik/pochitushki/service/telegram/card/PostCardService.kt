package ru.proshik.pochitushki.service.telegram.card

import com.github.kotlintelegrambot.entities.ChatId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.service.ConcurrentPostMoveException
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.InvalidLabelNameException
import ru.proshik.pochitushki.service.LabelService
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.UserService
import ru.proshik.pochitushki.service.telegram.PendingLabelInput
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
    private val labelService: LabelService,
    private val pendingLabelInput: PendingLabelInput,
    private val pdfSender: PostPdfSender,
    private val botProvider: BotProvider,
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

    /** The reply to a link the user sent: "Saved" (or "already saved") and the post's card, no paging. */
    fun sendSaved(chatId: Long, post: PostData, alreadySaved: Boolean, replyTo: Long) {
        val user = userService.getUserByChatId(chatId)
        val lang = user.settings.languageCode
        val header = i18n.getMessage(if (alreadySaved) "card.saved.already" else "card.saved.added", lang)
        val view = CardView.Post(post, CardRef(CardList.SAVED, 0, post.id, PostLoc.of(post.isArchived)), 0, header = header)
        presenter.send(chatId, view, lang, replyTo)
    }

    fun onCallback(chatId: Long, messageId: Long, action: String, ref: CardRef, extra: String?): CallbackAnswer {
        val user = userService.findUserByChatId(chatId) ?: return CallbackAnswer()
        val card = Card(user, chatId, messageId, ref)
        val answer = when (action) {
            CardAction.NAV -> card.redrawAt(ref.offset)
            CardAction.RANDOM_NEXT -> card.redrawAt(0)
            CardAction.NOOP -> null
            CardAction.ARCHIVE -> card.move(toArchive = true)
            CardAction.UNREAD -> card.move(toArchive = false)
            CardAction.DELETE_ASK -> card.redrawButtons { it.copy(confirmDelete = true) }
            CardAction.DELETE_NO -> card.redrawButtons { it }
            CardAction.DELETE_YES -> card.delete()
            CardAction.FAVORITE -> card.toggleFavorite()
            CardAction.MORE -> card.redrawButtons { it.copy(expanded = extra == "1") }
            CardAction.PDF -> card.pdf(engine = null)
            CardAction.PDF_ENGINE -> card.pdf(engine = extra)
            CardAction.LABELS -> card.showLabels()
            CardAction.LABEL_TOGGLE -> card.toggleLabel(extra?.toLongOrNull())
            CardAction.LABEL_NEW -> card.promptLabel()
            CardAction.LABELS_BACK -> card.redrawButtons { it }
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
            leave("card.toast.gone")
            return CallbackAnswer(i18n.getMessage("card.toast.gone", lang))
        }

        /**
         * The post left the list. A paged card shows the next one at this offset; the reply to
         * a saved link has no next one, so its text becomes the outcome ([statusKey]).
         */
        fun leave(statusKey: String): CallbackAnswer? {
            if (ref.list == CardList.SAVED) {
                presenter.redraw(chatId, messageId, CardView.Notice(i18n.getMessage(statusKey, lang)), lang)
                return null
            }
            return redrawAt(ref.offset)
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
            return leave(if (toArchive) "card.saved.archived" else "card.saved.unread")
        }

        fun delete(): CallbackAnswer? {
            if (postService.deletePost(ref.postId, user.id, ref.loc.postType) == 0) return gone()
            return leave("card.saved.deleted")
        }

        /**
         * One engine: start right away. Several: the card's buttons become the engine choice,
         * and the pick (or "back") puts them back. Either way the press is answered with a toast.
         */
        fun pdf(engine: String?): CallbackAnswer? {
            val post = post() ?: return gone()
            val engines = pdfSender.engines()
            val chosen = engine ?: engines.singleOrNull()
            if (chosen == null) {
                presenter.redrawKeyboard(chatId, messageId, keyboard.buildPdfEngineKeyboard(ref, engines, lang))
                return null
            }
            if (chosen !in engines) return CallbackAnswer(i18n.getMessage("command.pdf.error", lang))
            if (engine != null) redrawButtons { it }
            return CallbackAnswer(i18n.getMessage(pdfSender.start(chatId, user, post, chosen), lang))
        }

        /** The label picker in place of the card's buttons; no labels yet → ask for the first name. */
        fun showLabels(): CallbackAnswer? {
            val post = post() ?: return gone()
            val labels = labelService.getLabels(user.id)
            if (labels.isEmpty()) return promptLabel()
            val attached = post.labels.map { it.id }.toSet()
            presenter.redrawKeyboard(chatId, messageId, keyboard.buildPostLabelsKeyboard(ref, labels, attached, lang))
            return null
        }

        fun toggleLabel(labelId: Long?): CallbackAnswer? {
            labelId ?: return null
            val post = post() ?: return gone()
            if (post.labels.any { it.id == labelId }) {
                labelService.detachLabel(post.id, labelId, user.id, ref.loc.labelTarget)
            } else {
                labelService.attachLabel(post.id, labelId, user.id, ref.loc.labelTarget)
            }
            return showLabels()
        }

        /** The next plain message from this chat is read as a label name (see consumeLabelName). */
        fun promptLabel(): CallbackAnswer? {
            post() ?: return gone()
            pendingLabelInput.await(chatId, ref)
            botProvider.getBot().sendMessage(ChatId.fromId(chatId), i18n.getMessage("command.labels.prompt", lang))
            return null
        }

        /** Only the star changes, so only the keyboard is redrawn. */
        fun toggleFavorite(): CallbackAnswer? {
            postService.toggleFavorite(ref.postId, user.id, ref.loc.postType) ?: return gone()
            return redrawButtons { it }
        }
    }

    /**
     * Reads a plain message as the label name the bot just asked for and attaches it to the
     * card's post. Returns false when nothing was pending, so the caller treats it as a URL.
     */
    fun consumeLabelName(chatId: Long, text: String): Boolean {
        val pending = pendingLabelInput.take(chatId) ?: return false
        val user = userService.getUserByChatId(chatId)
        val lang = user.settings.languageCode
        val reply = try {
            val label = labelService.createLabel(user.id, text)
            labelService.attachLabel(pending.ref.postId, label.id, user.id, pending.ref.loc.labelTarget)
            logger.info("consumeLabelName success: chatId={}, postId={}", chatId, pending.ref.postId)
            i18n.getMessage("command.labels.attached", lang, arrayOf(label.name))
        } catch (e: InvalidLabelNameException) {
            i18n.getMessage("command.labels.invalid", lang)
        }
        botProvider.getBot().sendMessage(ChatId.fromId(chatId), reply)
        return true
    }

    /** The post at [offset] of [list], clamped to the list's end; null when the list is empty. */
    private fun load(user: UserData, list: CardList, offset: Int): CardView.Post? {
        if (list == CardList.RANDOM) {
            val post = postService.getRandomPost(user.id) ?: return null
            return CardView.Post(post, CardRef(list, 0, post.id, PostLoc.UNREAD), 0)
        }
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
