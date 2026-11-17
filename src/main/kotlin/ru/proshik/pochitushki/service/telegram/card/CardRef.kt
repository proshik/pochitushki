package ru.proshik.pochitushki.service.telegram.card

import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.PostType

/** Which list a card pages through. One character on the wire. */
enum class CardList(val code: Char, val postType: PostType) {
    UNREAD('u', PostType.UNREAD),
    ARCHIVE('a', PostType.ARCHIVE),
    FAVORITES('f', PostType.FAVORITES),
    /** A random unread post: no paging, "🎲 more" instead. */
    RANDOM('r', PostType.UNREAD),
    /** The reply to a saved link: one post, no paging. */
    SAVED('s', PostType.UNREAD);

    companion object {
        fun of(code: Char): CardList? = entries.firstOrNull { it.code == code }
    }
}

/** Which table the post itself lives in. Favorites mix both, so the list does not say it. */
enum class PostLoc(val code: Char, val postType: PostType, val labelTarget: LabelTarget) {
    UNREAD('u', PostType.UNREAD, LabelTarget.UNREAD),
    ARCHIVE('a', PostType.ARCHIVE, LabelTarget.ARCHIVE);

    companion object {
        fun of(code: Char): PostLoc? = entries.firstOrNull { it.code == code }
        fun of(isArchived: Boolean): PostLoc = if (isArchived) ARCHIVE else UNREAD
    }
}

/**
 * Where a card is and what it shows, packed into `callback_data`: list, offset, post id and
 * the post's table. Telegram caps callback_data at 64 bytes, so this is the single format —
 * `"u:3:123:u"`, with the list code upper-cased when the card is a widget (`"U:3:123:u"`).
 * Every button carries the whole ref, which is what lets a stale card act on the right post
 * and the label picker come back to the same place.
 */
data class CardRef(
    val list: CardList,
    val offset: Int,
    val postId: Long,
    val loc: PostLoc,
    val widget: Boolean = false,
) {
    fun encode(): String {
        val listCode = if (widget) list.code.uppercaseChar() else list.code
        return "$listCode:$offset:$postId:${loc.code}"
    }

    fun at(offset: Int): CardRef = copy(offset = offset)

    companion object {
        /** null on anything malformed: callback_data is attacker-controllable. */
        fun decode(data: String): CardRef? = parseCardCallback(data)?.takeIf { it.second == null }?.first
    }
}

/** Card actions, two characters each to keep callback_data short. */
object CardAction {
    const val NAV = "cn"
    const val ARCHIVE = "ca"
    const val UNREAD = "cu"
    const val DELETE_ASK = "cd"
    const val DELETE_YES = "cy"
    const val DELETE_NO = "cx"
    const val FAVORITE = "cf"
    const val MORE = "cm"
    const val RANDOM_NEXT = "cr"
    const val PDF = "cp"
    const val PDF_ENGINE = "ce"
    const val LABELS = "lp"
    const val LABEL_TOGGLE = "lt"
    const val LABEL_NEW = "ln"
    const val LABELS_BACK = "lb"
    const val NOOP = "c0"

    val ALL = setOf(
        NAV, ARCHIVE, UNREAD, DELETE_ASK, DELETE_YES, DELETE_NO, FAVORITE, MORE, RANDOM_NEXT,
        PDF, PDF_ENGINE, LABELS, LABEL_TOGGLE, LABEL_NEW, LABELS_BACK, NOOP,
    )
}

const val MAX_CALLBACK_BYTES = 64

fun cardCallback(action: String, ref: CardRef, extra: String? = null): String {
    val data = "$action|${ref.encode()}" + (extra?.let { ":$it" } ?: "")
    require(data.toByteArray(Charsets.UTF_8).size <= MAX_CALLBACK_BYTES) { "callback_data over 64 bytes: $data" }
    return data
}

/** Parses the part after `"|"`: the ref and an optional trailing extra. null on any malformed input. */
fun parseCardCallback(data: String): Pair<CardRef, String?>? {
    val parts = data.split(":", limit = 5)
    if (parts.size < 4) return null
    val listCode = parts[0].singleOrNull() ?: return null
    val list = CardList.of(listCode.lowercaseChar()) ?: return null
    val offset = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val postId = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val loc = parts[3].singleOrNull()?.let { PostLoc.of(it) } ?: return null
    val extra = parts.getOrNull(4)
    return CardRef(list, offset, postId, loc, widget = listCode.isUpperCase()) to extra
}
