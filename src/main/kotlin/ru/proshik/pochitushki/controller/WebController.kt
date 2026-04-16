package ru.proshik.pochitushki.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.UserService

@Controller
class WebController(
    private val postService: PostService,
    private val userService: UserService,
) {

    @GetMapping("/", "/feed")
    fun feed(@RequestAttribute("userId") userId: Long, model: Model): String {
        val posts = postService.getPosts(userId, PostType.UNREAD, PAGE_SIZE, 0)
        model.addAttribute("posts", posts)
        model.addAttribute("pageType", PostType.UNREAD.value)
        model.addAttribute("offset", posts.size)
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        return "feed"
    }

    @GetMapping("/archive")
    fun archive(@RequestAttribute("userId") userId: Long, model: Model): String {
        val posts = postService.getPosts(userId, PostType.ARCHIVE, PAGE_SIZE, 0)
        model.addAttribute("posts", posts)
        model.addAttribute("pageType", PostType.ARCHIVE.value)
        model.addAttribute("offset", posts.size)
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        return "archive"
    }

    @GetMapping("/favorites")
    fun favorites(@RequestAttribute("userId") userId: Long, model: Model): String {
        val posts = postService.getPosts(userId, PostType.FAVORITES, PAGE_SIZE, 0)
        model.addAttribute("posts", posts)
        model.addAttribute("pageType", PostType.FAVORITES.value)
        model.addAttribute("offset", posts.size)
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        return "favorites"
    }

    @GetMapping("/profile")
    fun profile(@RequestAttribute("userId") userId: Long, model: Model): String {
        val user = userService.getUserByUserId(userId)
        model.addAttribute("user", user)
        model.addAttribute("unreadCount", postService.getPostCount(userId, PostType.UNREAD))
        model.addAttribute("archiveCount", postService.getPostCount(userId, PostType.ARCHIVE))
        model.addAttribute("favoritesCount", postService.getPostCount(userId, PostType.FAVORITES))
        return "profile"
    }

    companion object {
        // Delegate to PostApiController so both controllers use the same page size
        val PAGE_SIZE get() = PostApiController.PAGE_SIZE
    }
}
