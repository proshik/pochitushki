package ru.proshik.pochitushki.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Margin
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class PlaywrightPdfGenerator : PdfGenerator, AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Playwright requires all API calls on the thread that created the instance
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playwright-worker").apply { isDaemon = true }
    }

    private val browserFuture: CompletableFuture<Pair<Playwright, Browser>> =
        CompletableFuture.supplyAsync({
            logger.info("Playwright: starting background initialization...")
            val pw = Playwright.create()
            val br = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            logger.info("Playwright PDF generator initialized (Chromium headless)")
            pw to br
        }, executor)

    override fun generatePdf(url: String): ByteArray {
        logger.debug("generatePdf: url={}", url)

        return executor.submit<ByteArray> {
            val browser = browserFuture.join().second

            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (compatible; Pochitushki/1.0)")
            )

            context.use { ctx ->
                val page = ctx.newPage()

                page.navigate(url, Page.NavigateOptions().setTimeout(30000.0))
                page.waitForLoadState()

                // Scroll down to trigger lazy-loaded images
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                page.waitForTimeout(1000.0)

                val pdfBytes = page.pdf(
                    Page.PdfOptions()
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(
                            Margin()
                                .setTop("20mm")
                                .setBottom("20mm")
                                .setLeft("15mm")
                                .setRight("15mm")
                        )
                        .setDisplayHeaderFooter(true)
                        .setHeaderTemplate(
                            """<div style="font-size:8px; width:100%; text-align:center; color:#999;">
                            <span class="title"></span>
                        </div>"""
                        )
                        .setFooterTemplate(
                            """<div style="font-size:8px; width:100%; text-align:center; color:#999;">
                            <span class="pageNumber"></span> / <span class="totalPages"></span>
                        </div>"""
                        )
                )

                logger.info("generatePdf success: url={}, size={}", url, pdfBytes.size)
                pdfBytes
            }
        }.get()
    }

    override fun close() {
        executor.submit {
            val (pw, br) = browserFuture.join()
            br.close()
            pw.close()
            logger.info("Playwright PDF generator closed")
        }.get()
        executor.shutdown()
    }
}
