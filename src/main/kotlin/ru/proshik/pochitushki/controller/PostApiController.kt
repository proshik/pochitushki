package ru.proshik.pochitushki.controller

import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import org.jsoup.Jsoup
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.CoverService
import ru.proshik.pochitushki.service.PostService
import ru.proshik.pochitushki.service.SsrfValidationException
import ru.proshik.pochitushki.service.UrlSecurityValidator

@Controller
@RequestMapping("/api/v1/posts")
class PostApiController(
    private val postService: PostService,
    private val urlSecurityValidator: UrlSecurityValidator,
    private val coverService: CoverService,
) {

    @GetMapping("/fragment")
    fun fragment(
        @RequestAttribute("userId") userId: Long,
        @RequestParam type: String,
        @RequestParam offset: Int,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        model: Model
    ): String {
        val postType = PostType.entries.firstOrNull { it.value == type }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown post type: $type")
        if (offset < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be >= 0")
        }
        val posts = postService.getPosts(userId, postType, PAGE_SIZE, offset)
        model.addAttribute("covers", coverService.decorate(posts))
        model.addAttribute("pageType", type)
        model.addAttribute("offset", offset + posts.size)
        model.addAttribute("hasMore", posts.size == PAGE_SIZE)
        model.addAttribute("viewMode", WebController.resolveViewMode(viewCookie, postType))
        return "fragments/post-list :: posts"
    }

    /**
     * A fresh shuffle of unread posts. Unlike /fragment this replaces the list
     * instead of appending to it, so there is no offset and never a sentinel.
     */
    @GetMapping("/random-fragment")
    fun randomFragment(
        @RequestAttribute("userId") userId: Long,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        model: Model
    ): String {
        val posts = postService.getRandomPosts(userId, RANDOM_SIZE)
        model.addAttribute("covers", coverService.decorate(posts))
        model.addAttribute("pageType", PostType.UNREAD.value)
        model.addAttribute("offset", posts.size)
        model.addAttribute("hasMore", false)
        model.addAttribute("viewMode", WebController.resolveViewMode(viewCookie, PostType.UNREAD))
        return "fragments/post-list :: posts"
    }

    @PostMapping
    fun addPost(
        @RequestAttribute("userId") userId: Long,
        @RequestParam url: String,
        @RequestParam(required = false) view: String?,
        @CookieValue(value = "pochitushki-view", required = false) viewCookie: String?,
        model: Model
    ): String {
        val parsedUrl = try {
            URL(url).also { u ->
                if (u.protocol !in listOf("http", "https")) {
                    throw MalformedURLException("Unsupported scheme: ${u.protocol}")
                }
            }
        } catch (e: MalformedURLException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL")
        }
        val (postId, _) = try {
            postService.addPost(parsedUrl, userId)
        } catch (e: SsrfValidationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "URL not allowed")
        }
        val post = postService.getPost(postId, userId, PostType.UNREAD)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Post not found after insert")
        model.addAttribute("cv", coverService.decorate(post))
        model.addAttribute("type", PostType.UNREAD.value)
        // The submitting page reports its current mode so the returned card matches it.
        model.addAttribute(
            "viewMode",
            when (view) {
                "shelf", "list" -> view
                else -> WebController.resolveViewMode(viewCookie, PostType.UNREAD)
            }
        )
        model.addAttribute("pageType", PostType.UNREAD.value)
        return "fragments/post-card :: card"
    }

    @PostMapping("/{id}/archive")
    fun archive(
        @RequestAttribute("userId") userId: Long,
        @PathVariable id: Long
    ): ResponseEntity<Void> {
        postService.archivePost(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found")
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{id}/unread")
    fun unread(
        @RequestAttribute("userId") userId: Long,
        @PathVariable id: Long
    ): ResponseEntity<Void> {
        postService.unreadPost(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found")
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{id}/favorite")
    fun favorite(
        @RequestAttribute("userId") userId: Long,
        @PathVariable id: Long,
        @RequestParam type: String,
        model: Model
    ): String {
        val postType = PostType.entries.firstOrNull { it.value == type }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown post type: $type")
        postService.toggleFavorite(id, userId, postType)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found")
        val post = postService.getPost(id, userId, postType)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found")
        // Rendered for API symmetry; the web client calls this with hx-swap="none".
        model.addAttribute("cv", coverService.decorate(post))
        model.addAttribute("type", type)
        model.addAttribute("viewMode", "list")
        model.addAttribute("pageType", type)
        return "fragments/post-card :: card"
    }

    @DeleteMapping("/{id}")
    fun delete(
        @RequestAttribute("userId") userId: Long,
        @PathVariable id: Long,
        @RequestParam type: String
    ): ResponseEntity<Void> {
        val postType = PostType.entries.firstOrNull { it.value == type }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown post type: $type")
        val deleted = postService.deletePost(id, userId, postType)
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found")
        }
        return ResponseEntity.ok().build()
    }

    @GetMapping("/{id}/og-image")
    @ResponseBody
    fun getOgImage(
        @RequestAttribute("userId") userId: Long,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "unread") type: String
    ): ResponseEntity<Map<String, String>> {
        val postType = PostType.entries.firstOrNull { it.value == type } ?: PostType.UNREAD

        val post = postService.getPost(id, userId, postType)
            ?: return ResponseEntity.notFound().build()

        if (!urlSecurityValidator.isAllowed(post.url)) {
            return ResponseEntity.notFound().build()
        }

        return try {
            val doc = Jsoup.connect(post.url).followRedirects(false).timeout(5000).get()
            val ogImage = doc.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }
                ?: doc.select("meta[name=twitter:image]").attr("content").takeIf { it.isNotBlank() }
            if (ogImage != null) ResponseEntity.ok(mapOf("ogImageUrl" to ogImage))
            else ResponseEntity.notFound().build()
        } catch (e: IOException) {
            ResponseEntity.notFound().build()
        }
    }

    companion object {
        const val PAGE_SIZE = 20

        /** How many covers one shuffle of /random deals out. */
        const val RANDOM_SIZE = 8
    }
}
