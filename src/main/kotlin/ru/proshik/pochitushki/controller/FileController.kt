package ru.proshik.pochitushki.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.proshik.pochitushki.service.ImportService

@RestController
@RequestMapping("/api/v1/files")
class FileController(private val importService: ImportService) {

    @PostMapping("/import")
    fun import(@RequestParam("file") file: MultipartFile): ResponseEntity<Void> {
//        importService.import(file)
        return ResponseEntity.ok().build()
    }
}
