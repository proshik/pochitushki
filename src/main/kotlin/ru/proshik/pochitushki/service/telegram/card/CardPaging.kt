package ru.proshik.pochitushki.service.telegram.card

/**
 * Where the arrows of a paged card lead. One place for the text card and the widget.
 *
 * A favorites card whose post was just unstarred stays on screen with ☆, so the reader can
 * change their mind, but the post is no longer in the list: the counter keeps counting it,
 * and the post that followed has moved up into this very offset — "›" leads to `offset`, not
 * `offset + 1`, or it would skip one.
 */
object CardPaging {

    fun detached(view: CardView.Post): Boolean = view.ref.list == CardList.FAVORITES && !view.post.isFavorite

    fun shownTotal(view: CardView.Post): Int = if (detached(view)) view.total + 1 else view.total

    fun prevOffset(view: CardView.Post): Int? = (view.ref.offset - 1).takeIf { it >= 0 }

    fun nextOffset(view: CardView.Post): Int? = if (detached(view)) {
        view.ref.offset.takeIf { it < view.total }
    } else {
        (view.ref.offset + 1).takeIf { it < view.total }
    }
}
