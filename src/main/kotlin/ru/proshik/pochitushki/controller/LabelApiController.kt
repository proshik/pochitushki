package ru.proshik.pochitushki.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.DuplicateLabelNameException
import ru.proshik.pochitushki.service.InvalidLabelNameException
import ru.proshik.pochitushki.service.LabelService

data class CreateLabelRequest(val name: String)

@RestController
@RequestMapping("/api/v1")
class LabelApiController(private val labelService: LabelService) {

    @GetMapping("/labels")
    fun list(@RequestAttribute("userId") userId: Long): List<LabelData> =
        labelService.getLabels(userId)

    /** Creating a label that already exists returns the existing one — the intent is the same. */
    @PostMapping("/labels")
    fun create(
        @RequestAttribute("userId") userId: Long,
        @RequestBody request: CreateLabelRequest,
    ): LabelData = try {
        labelService.createLabel(userId, request.name)
    } catch (e: InvalidLabelNameException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }

    @PatchMapping("/labels/{labelId}")
    fun rename(
        @RequestAttribute("userId") userId: Long,
        @PathVariable labelId: Long,
        @RequestBody request: CreateLabelRequest,
    ): ResponseEntity<Void> {
        val renamed = try {
            labelService.renameLabel(labelId, userId, request.name)
        } catch (e: InvalidLabelNameException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: DuplicateLabelNameException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
        if (!renamed) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found")
        }
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/labels/{labelId}")
    fun delete(
        @RequestAttribute("userId") userId: Long,
        @PathVariable labelId: Long,
    ): ResponseEntity<Void> {
        if (!labelService.deleteLabel(labelId, userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found")
        }
        return ResponseEntity.ok().build()
    }

    @PostMapping("/posts/{postId}/labels/{labelId}")
    fun attach(
        @RequestAttribute("userId") userId: Long,
        @PathVariable postId: Long,
        @PathVariable labelId: Long,
        @RequestParam(defaultValue = "unread") type: String,
    ): ResponseEntity<Void> {
        val target = targetOf(type)
        // attachLabel writes nothing when either id belongs to someone else, so a
        // false here is either "already attached" or "not yours"; only the second
        // is worth an error, and re-checking ownership tells the two apart.
        if (!labelService.attachLabel(postId, labelId, userId, target) &&
            labelService.findLabel(labelId, userId) == null
        ) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found")
        }
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/posts/{postId}/labels/{labelId}")
    fun detach(
        @RequestAttribute("userId") userId: Long,
        @PathVariable postId: Long,
        @PathVariable labelId: Long,
        @RequestParam(defaultValue = "unread") type: String,
    ): ResponseEntity<Void> {
        labelService.detachLabel(postId, labelId, userId, targetOf(type))
        // Detaching what is not attached leaves the caller where they wanted to be.
        return ResponseEntity.ok().build()
    }

    private fun targetOf(type: String): LabelTarget =
        if (type == PostType.ARCHIVE.value) LabelTarget.ARCHIVE else LabelTarget.UNREAD
}
