package ru.proshik.pochitushki.service.telegram.card

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.model.UserData
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.PdfCacheService
import ru.proshik.pochitushki.service.PdfGenerator
import ru.proshik.pochitushki.service.RateLimitService
import ru.proshik.pochitushki.service.telegram.BotTaskExecutor
import ru.proshik.pochitushki.service.telegram.api.delivered
import ru.proshik.pochitushki.service.metrics.AppMetrics

/**
 * PDF of a post from the card's ⋯ row. The press is answered with a toast at once; the file
 * arrives later as a document, since rendering takes seconds to a minute.
 *
 * Engines are looked up by bean name, `"${engine}PdfGenerator"` — the bean names in
 * PdfConfiguration are part of this contract.
 */
@Component
class PostPdfSender(
    private val pdfGenerators: Map<String, PdfGenerator>,
    private val botProvider: BotProvider,
    private val pdfCacheService: PdfCacheService,
    private val botTaskExecutor: BotTaskExecutor,
    private val rateLimitService: RateLimitService,
    private val i18n: I18nService,
    private val metrics: AppMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun engines(): List<String> = pdfGenerators.keys.map { it.removeSuffix("PdfGenerator") }.sorted()

    /** Queues the PDF; returns the i18n key of the toast that answers the press. */
    fun start(chatId: Long, user: UserData, post: PostData, engine: String): String {
        val generator = pdfGenerators["${engine}PdfGenerator"] ?: run {
            logger.warn("pdf: unknown engine={}", engine)
            return "command.pdf.error"
        }
        if (!rateLimitService.tryAcquire(user.id, RateLimitService.Action.GENERATE_PDF)) {
            return "command.rate_limited"
        }
        // The library dispatches every update on one thread; rendering there would stall every
        // other user's buttons (and, in webhook mode, make Telegram redeliver the same press).
        val accepted = botTaskExecutor.submit("pdf postId=${post.id} engine=$engine") {
            generateAndSend(chatId, user, post, engine, generator)
        }
        if (!accepted) metrics.operationFailed(AppMetrics.Operation.PDF, AppMetrics.Reason.BUSY)
        return if (accepted) "card.toast.pdf_started" else "command.busy"
    }

    private fun generateAndSend(chatId: Long, user: UserData, post: PostData, engine: String, generator: PdfGenerator) {
        try {
            // Same article, same engine, within the month → no refetch, no re-render.
            val pdfBytes = pdfCacheService.getOrGenerate(user.id, post.url, engine) {
                generator.generatePdf(post.url)
            }
            val sent = botProvider.getBot().sendDocument(
                chatId = ChatId.fromId(chatId),
                document = TelegramFile.ByByteArray(pdfBytes, filename(post.title, post.url)),
                caption = post.title ?: post.url,
            )
            // A refusal (413 for a file too large) does not throw; without this the user got neither
            // the file nor the error message.
            check(sent.delivered()) { "telegram refused the pdf" }
            logger.info("pdf success: chatId={}, postId={}, engine={}", chatId, post.id, engine)
        } catch (e: Exception) {
            metrics.operationFailed(AppMetrics.Operation.PDF)
            logger.warn("pdf error: postId={}, engine={}", post.id, engine, e)
            botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                text = i18n.getMessage("command.pdf.error", user.settings.languageCode),
            )
        }
    }

    private fun filename(title: String?, url: String): String {
        val name = title
            ?.take(50)
            ?.replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s-]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "_")
            ?.ifEmpty { null }
            ?: URI.create(url).host?.replace(".", "_")
            ?: "document"
        return "$name.pdf"
    }
}
