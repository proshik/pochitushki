package ru.proshik.pochitushki.model

/**
 * A user-owned label. Distinct from `PostData.tags`, which is the flat TEXT[]
 * carried in from Pocket exports and is display-only — labels are relational,
 * created and attached by the owner.
 */
data class LabelData(
    val id: Long,
    val name: String,
)

/** A label plus how many posts carry it, across both shelves — the /labels page. */
data class LabelWithCount(
    val id: Long,
    val name: String,
    val postCount: Int,
)

/** Which join table a label link lives in — post_label or archive_post_label. */
enum class LabelTarget(val table: String) {
    UNREAD("post_label"),
    ARCHIVE("archive_post_label");

    companion object {
        fun of(postType: PostType): LabelTarget = when (postType) {
            PostType.ARCHIVE -> ARCHIVE
            // FAVORITES and UNREAD both read the `post` table; ALL never addresses one post.
            PostType.UNREAD, PostType.FAVORITES -> UNREAD
            PostType.ALL -> error("LabelTarget is undefined for ALL — pick a concrete table")
        }

        fun of(isArchived: Boolean): LabelTarget = if (isArchived) ARCHIVE else UNREAD
    }
}
