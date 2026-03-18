package ru.proshik.pochitushki.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Margin
import org.slf4j.LoggerFactory

class PlaywrightPdfGenerator : PdfGenerator, AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(
        BrowserType.LaunchOptions().setHeadless(true)
    )

    init {
        logger.info("Playwright PDF generator initialized (Chromium headless)")
    }

    override fun generatePdf(url: String): ByteArray {
        logger.debug("generatePdf: url={}", url)

        val context = browser.newContext(
            Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (compatible; Pochitushki/1.0)")
        )

        return context.use { ctx ->
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
    }

    override fun close() {
        browser.close()
        playwright.close()
        logger.info("Playwright PDF generator closed")
    }
}
