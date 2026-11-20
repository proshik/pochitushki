package ru.proshik.pochitushki.controller

import java.util.Locale
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.dao.DataAccessException
import ru.proshik.pochitushki.configuration.JwtAuthInterceptor
import ru.proshik.pochitushki.model.FeedSort
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.UserData
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.service.PostViewService
import ru.proshik.pochitushki.service.LabelService
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.UserService

@Controller
class WebController(
    private val postService: PostService,
    private val userService: UserService,
    private val postViewService: PostViewService,
    private val labelService: LabelService,
) {

    /**
     * The feed. `?label=ID` narrows it to one label, taken as text like `/labels?page=`: a label
     * that is not one of the filter's pills (someone else's, gone, empty here, garbage) is
     * ignored rather than answered with a 400. With a label chosen the hero is hidden.
     */
    @GetMapping("/")
    fun feed(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        @RequestAttribute(JwtAuthInterceptor.FEED_SORT) sort: FeedSort,
        @RequestParam(required = false) label: String?,
        locale: Locale,
        model: Model
    ): String {
        val filtered = addShelfModel(model, userId, PostType.UNREAD, viewCookie, showOgCovers, sort, label, locale)

        // The hero is the oldest link whatever the order, and only over the whole shelf.
        val hero = if (filtered) null else postService.getOldestUnreadPost(userId)
            ?.let { postViewService.card(it, showOgCovers, locale) }
        model.addAttribute("hero", hero)

        addUserInfo(userId, model)
        return "feed"
    }

    @GetMapping("/all")
    fun all(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        @RequestAttribute(JwtAuthInterceptor.FEED_SORT) sort: FeedSort,
        @RequestParam(required = false) label: String?,
        locale: Locale,
        model: Model
    ): String {
        addShelfModel(model, userId, PostType.ALL, viewCookie, showOgCovers, sort, label, locale)
        addUserInfo(userId, model)
        return "all"
    }

    @GetMapping("/archive")
    fun archive(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        locale: Locale,
        model: Model
    ): String {
        addListModel(model, userId, PostType.ARCHIVE, resolveViewMode(viewCookie, PostType.ARCHIVE), showOgCovers, locale)
        addUserInfo(userId, model)
        return "archive"
    }

    @GetMapping("/favorites")
    fun favorites(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        locale: Locale,
        model: Model
    ): String {
        addListModel(model, userId, PostType.FAVORITES, resolveViewMode(viewCookie, PostType.FAVORITES), showOgCovers, locale)
        addUserInfo(userId, model)
        return "favorites"
    }

    @GetMapping("/random")
    fun random(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        locale: Locale,
        model: Model
    ): String {
        // Cards are unread posts, so they carry the unread page's actions and defaults.
        val posts = postService.getRandomPosts(userId, PostApiController.RANDOM_SIZE)
        model.addAttribute("covers", postViewService.cards(posts, showOgCovers, locale))
        model.addAttribute("pageType", PostType.UNREAD.value)
        model.addAttribute("moreUrl", null)
        model.addAttribute("hasMore", false)
        model.addAttribute("viewMode", resolveViewMode(viewCookie, PostType.UNREAD))
        model.addAttribute("pageCount", postService.getPostCount(userId, PostType.UNREAD))
        addUserInfo(userId, model)
        return "random"
    }

    /**
     * 50 labels a page, `?page=N` (out of range → the last page, garbage → the first).
     * `?label=ID` — sent by the page after creating a label — redirects to the page holding
     * it, so the new label is on screen and the address keeps a plain page number.
     * Both are taken as text: a typed-in value must not turn into a 400.
     */
    @GetMapping("/labels")
    fun labels(
        @RequestAttribute("userId") userId: Long,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) label: String?,
        model: Model,
    ): String {
        label?.toLongOrNull()?.let { labelService.pageOf(it, userId) }?.let { return "redirect:/labels?page=$it" }

        val labelsPage = labelService.labelsPage(userId, page)
        model.addAttribute("labels", labelsPage.labels)
        model.addAttribute("labelsPage", labelsPage)
        addUserInfo(userId, model)
        return "labels"
    }

    @GetMapping("/labels/{labelId}")
    fun labelPosts(
        @RequestAttribute("userId") userId: Long,
        @PathVariable labelId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        locale: Locale,
        model: Model
    ): String {
        val label = labelService.findLabel(labelId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found")

        val posts = postService.getPostsByLabel(userId, labelId, PAGE_SIZE, 0)
        model.addAttribute("label", label)
        model.addAttribute("covers", postViewService.cards(posts, showOgCovers, locale))
        // Both shelves are mixed here, so the cards carry the "all" page's status chips.
        model.addAttribute("pageType", PostType.ALL.value)
        model.addAttribute("moreUrl", labelFragmentUrl(labelId, posts.size))
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        model.addAttribute("viewMode", resolveViewMode(viewCookie, PostType.ALL))
        model.addAttribute("pageCount", postService.getPostCountByLabel(userId, labelId))
        addUserInfo(userId, model)
        return "label-posts"
    }

    @GetMapping("/profile")
    fun profile(@RequestAttribute("userId") userId: Long, model: Model): String {
        val user = userService.getUserByUserId(userId)

        model.addAttribute("user", user)
        model.addAttribute("unreadCount", postService.getPostCount(userId, PostType.UNREAD))
        model.addAttribute("archiveCount", postService.getPostCount(userId, PostType.ARCHIVE))
        model.addAttribute("favoritesCount", postService.getPostCount(userId, PostType.FAVORITES))
        addUserInfo(userId, model, resolved = user)

        return "profile"
    }

    private fun addListModel(
        model: Model,
        userId: Long,
        postType: PostType,
        viewMode: String,
        showOgCovers: Boolean,
        locale: Locale,
    ) {
        val posts = postService.getPosts(userId, postType, PAGE_SIZE, 0)
        model.addAttribute("covers", postViewService.cards(posts, showOgCovers, locale))
        model.addAttribute("pageType", postType.value)
        model.addAttribute("moreUrl", fragmentUrl(postType, posts.size))
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        model.addAttribute("viewMode", viewMode)
        model.addAttribute("pageCount", postService.getPostCount(userId, postType))
    }

    /**
     * The feed and "all": the chosen order, date groups, and the label filter row.
     * Returns whether a label filter is in effect.
     */
    private fun addShelfModel(
        model: Model,
        userId: Long,
        postType: PostType,
        viewCookie: String?,
        showOgCovers: Boolean,
        sort: FeedSort,
        rawLabel: String?,
        locale: Locale,
    ): Boolean {
        val pills = labelService.shelfLabels(userId, withArchive = postType == PostType.ALL)
        val active = rawLabel?.toLongOrNull()?.let { id -> pills.firstOrNull { it.id == id } }

        val posts = postService.getPosts(userId, postType, PAGE_SIZE, 0, sort, active?.id)
        model.addAttribute("covers", postViewService.groupedCards(posts, previous = null, showOgCovers, locale))
        model.addAttribute("pageType", postType.value)
        model.addAttribute("moreUrl", fragmentUrl(postType, posts.size, active?.id, sort))
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        model.addAttribute("viewMode", resolveViewMode(viewCookie, postType))
        model.addAttribute("pageCount", postService.getPostCount(userId, postType))
        model.addAttribute("feedSort", sort.value)
        model.addAttribute("filterLabels", pills)
        model.addAttribute("filterLabelId", active?.id)
        model.addAttribute("filterBase", if (postType == PostType.ALL) "/all" else "/")
        return active != null
    }

    private fun addUserInfo(userId: Long, model: Model, resolved: UserData? = null) {
        val info = resolved ?: try {
            userService.getUserByUserId(userId)
        } catch (e: DataAccessException) {
            null
        }

        model.addAttribute("userInfo", info)
    }

    companion object {
        // Delegate to PostApiController so both controllers use the same page size
        val PAGE_SIZE get() = PostApiController.PAGE_SIZE

        /**
         * Next-page URL for the infinite-scroll sentinel; values are enum/ints, never user text.
         * The feed and "all" carry the order of the page they continue: the setting may change in
         * another tab meanwhile, and an offset into the other order would repeat and skip posts.
         */
        fun fragmentUrl(postType: PostType, nextOffset: Int, labelId: Long? = null, sort: FeedSort? = null): String =
            "/api/v1/posts/fragment?type=${postType.value}&offset=$nextOffset" +
                (sort?.let { "&sort=${it.value}" } ?: "") +
                (labelId?.let { "&label=$it" } ?: "")

        fun labelFragmentUrl(labelId: Long, nextOffset: Int): String =
            "/api/v1/posts?labelId=$labelId&offset=$nextOffset"

        /**
         * Cookie keeps the legacy values list|grid (grid == shelf).
         * Without a cookie, reading pages open as a shelf, dense pages as a list.
         */
        fun resolveViewMode(cookie: String?, postType: PostType): String = when (cookie) {
            "grid" -> "shelf"
            "list" -> "list"
            else -> when (postType) {
                PostType.UNREAD, PostType.FAVORITES -> "shelf"
                PostType.ALL, PostType.ARCHIVE -> "list"
            }
        }
    }
}
