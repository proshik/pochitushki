package ru.proshik.pochitushki.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Margin
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class PlaywrightPdfGenerator(
    private val urlSecurityValidator: UrlSecurityValidator,
) : PdfGenerator, AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Playwright requires all API calls on the thread that created the instance
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playwright-worker").apply { isDaemon = true }
    }

    private val browserFuture: CompletableFuture<Pair<Playwright, Browser>> =
        CompletableFuture.supplyAsync({
            logger.info("Playwright: starting background initialization...")
            val pw = Playwright.create()
            // Playwright defaults chromiumSandbox to false, which runs a third party's
            // JavaScript in this container with no sandbox at all.
            val br = pw.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setChromiumSandbox(true)
            )
            logger.info("Playwright PDF generator initialized (Chromium headless)")
            pw to br
        }, executor)

    override fun generatePdf(url: String): ByteArray {
        logger.debug("generatePdf: url={}", url)

        // SSRF guard: a headless browser navigates here and executes the page's JS.
        urlSecurityValidator.validate(url)

        return executor.submit<ByteArray> {
            val browser = browserFuture.join().second

            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (compatible; Pochitushki/1.0)")
            )

            context.use { ctx ->
                val page = ctx.newPage()

                // Validating the entry URL is not enough for a browser: the page itself pulls
                // images, iframes, fonts and fetch() targets of its own choosing, and
                // `<img src="http://169.254.169.254/…">` would be fetched by Chromium, not by us.
                // Every request the page makes goes through the same SSRF guard, or is aborted.
                ctx.route("**/*") { route ->
                    val target = route.request().url()
                    if (urlSecurityValidator.isAllowed(target)) {
                        route.resume()
                    } else {
                        logger.debug("Blocked sub-resource from PDF page: {}", target)
                        route.abort()
                    }
                }

                page.navigate(url, Page.NavigateOptions().setTimeout(30000.0))
                page.waitForLoadState()

                // Incrementally scroll through the page to trigger lazy-loaded images
                page.evaluate("""() => {
                    return new Promise(resolve => {
                        const distance = Math.max(document.documentElement.clientHeight, 600);
                        const totalHeight = document.body.scrollHeight;
                        let current = 0;
                        const timer = setInterval(() => {
                            current += distance;
                            window.scrollTo(0, current);
                            if (current >= totalHeight) {
                                clearInterval(timer);
                                window.scrollTo(0, 0);
                                resolve();
                            }
                        }, 300);
                    });
                }""")

                // Wait until all triggered network requests settle
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)

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
            if (!browserFuture.isDone || browserFuture.isCompletedExceptionally) return@submit
            try {
                val (pw, _) = browserFuture.join()
                pw.close()
                logger.info("Playwright PDF generator closed")
            } catch (e: Exception) {
                // Playwright process may already be gone during JVM shutdown — not an error
                logger.debug("Playwright PDF generator closed with error (expected during shutdown): {}", e.message)
            }
        }.get()
        executor.shutdown()
    }
}
