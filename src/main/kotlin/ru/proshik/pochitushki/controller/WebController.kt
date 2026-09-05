package ru.proshik.pochitushki.controller

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.dao.DataAccessException
import ru.proshik.pochitushki.configuration.JwtAuthInterceptor
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.UserData
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.service.CoverService
import ru.proshik.pochitushki.service.LabelService
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.UserService

@Controller
class WebController(
    private val postService: PostService,
    private val userService: UserService,
    private val coverService: CoverService,
    private val labelService: LabelService,
) {

    @GetMapping("/")
    fun feed(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        model: Model
    ): String {
        addListModel(model, userId, PostType.UNREAD, resolveViewMode(viewCookie, PostType.UNREAD), showOgCovers)

        val hero = postService.getOldestUnreadPost(userId)?.let { coverService.decorate(it, showOgCovers) }
        model.addAttribute("hero", hero)
        model.addAttribute(
            "heroAgeDays",
            hero?.let { ChronoUnit.DAYS.between(it.post.createdDate.toLocalDate(), LocalDate.now()) }
        )

        addUserInfo(userId, model)
        return "feed"
    }

    @GetMapping("/all")
    fun all(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        model: Model
    ): String {
        addListModel(model, userId, PostType.ALL, resolveViewMode(viewCookie, PostType.ALL), showOgCovers)
        addUserInfo(userId, model)
        return "all"
    }

    @GetMapping("/archive")
    fun archive(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        model: Model
    ): String {
        addListModel(model, userId, PostType.ARCHIVE, resolveViewMode(viewCookie, PostType.ARCHIVE), showOgCovers)
        addUserInfo(userId, model)
        return "archive"
    }

    @GetMapping("/favorites")
    fun favorites(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        model: Model
    ): String {
        addListModel(model, userId, PostType.FAVORITES, resolveViewMode(viewCookie, PostType.FAVORITES), showOgCovers)
        addUserInfo(userId, model)
        return "favorites"
    }

    @GetMapping("/random")
    fun random(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        model: Model
    ): String {
        // Cards are unread posts, so they carry the unread page's actions and defaults.
        val posts = postService.getRandomPosts(userId, PostApiController.RANDOM_SIZE)
        model.addAttribute("covers", coverService.decorate(posts, showOgCovers))
        model.addAttribute("pageType", PostType.UNREAD.value)
        model.addAttribute("moreUrl", null)
        model.addAttribute("hasMore", false)
        model.addAttribute("viewMode", resolveViewMode(viewCookie, PostType.UNREAD))
        model.addAttribute("pageCount", postService.getPostCount(userId, PostType.UNREAD))
        addUserInfo(userId, model)
        return "random"
    }

    @GetMapping("/labels")
    fun labels(@RequestAttribute("userId") userId: Long, model: Model): String {
        model.addAttribute("labels", labelService.getLabelsWithCounts(userId))
        addUserInfo(userId, model)
        return "labels"
    }

    @GetMapping("/labels/{labelId}")
    fun labelPosts(
        @RequestAttribute("userId") userId: Long,
        @PathVariable labelId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        @RequestAttribute(JwtAuthInterceptor.SHOW_OG_COVERS) showOgCovers: Boolean,
        model: Model
    ): String {
        val label = labelService.findLabel(labelId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found")

        val posts = postService.getPostsByLabel(userId, labelId, PAGE_SIZE, 0)
        model.addAttribute("label", label)
        model.addAttribute("covers", coverService.decorate(posts, showOgCovers))
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
    ) {
        val posts = postService.getPosts(userId, postType, PAGE_SIZE, 0)
        model.addAttribute("covers", coverService.decorate(posts, showOgCovers))
        model.addAttribute("pageType", postType.value)
        model.addAttribute("moreUrl", fragmentUrl(postType, posts.size))
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        model.addAttribute("viewMode", viewMode)
        model.addAttribute("pageCount", postService.getPostCount(userId, postType))
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

        /** Next-page URL for the infinite-scroll sentinel; values are enum/ints, never user text. */
        fun fragmentUrl(postType: PostType, nextOffset: Int): String =
            "/api/v1/posts/fragment?type=${postType.value}&offset=$nextOffset"

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
