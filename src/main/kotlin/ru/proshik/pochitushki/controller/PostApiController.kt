package ru.proshik.pochitushki.controller

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.net.MalformedURLException
import java.net.URL
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.PostService

@Controller
@RequestMapping("/api/v1/posts")
class PostApiController(private val postService: PostService) {

    @GetMapping("/fragment")
    fun fragment(
        @RequestAttribute("userId") userId: Long,
        @RequestParam type: String,
        @RequestParam offset: Int,
        model: Model
    ): String {
        val postType = PostType.entries.firstOrNull { it.value == type }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown post type: $type")
        val posts = postService.getPosts(userId, postType, PAGE_SIZE, offset)
        model.addAttribute("posts", posts)
        model.addAttribute("pageType", type)
        model.addAttribute("offset", offset + posts.size)
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        return "fragments/post-list :: posts"
    }

    @PostMapping
    fun addPost(
        @RequestAttribute("userId") userId: Long,
        @RequestParam url: String,
        model: Model
    ): String {
        val parsedUrl = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL")
        }
        val (postId, _) = postService.addPost(parsedUrl, userId)
        val post = postService.getPost(postId, PostType.UNREAD)!!
        model.addAttribute("post", post)
        model.addAttribute("type", PostType.UNREAD.value)
        return "fragments/post-card :: card"
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
