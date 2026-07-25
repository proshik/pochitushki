package ru.proshik.pochitushki.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.util.Base64
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import ru.proshik.pochitushki.BaseIntegrationTest

class PdfServiceTest : BaseIntegrationTest() {

    companion object {
        // Minimal valid 1x1 red PNG (67 bytes)
        val TINY_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="
        )

        // Minimal valid 1x1 WebP (lossy, 44 bytes)
        val TINY_WEBP: ByteArray = Base64.getDecoder().decode(
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"
        )
    }

    @Autowired
    @Qualifier("openhtmlPdfGenerator")
    private lateinit var pdfGenerator: PdfGenerator

    private lateinit var wireMock: WireMockServer

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    /**
     * Regression: image fetches must not follow redirects.
     *
     * UrlSecurityValidator only vets the URL it is handed, so a public image URL that
     * 3xx-redirects to 169.254.169.254 or an RFC1918 address used to walk straight past it —
     * `HttpURLConnection` follows redirects by default. The redirect target must never be
     * requested. Both extensions are covered because the two image paths used to differ: the
     * renderer fetched "supported" formats itself while the app fetched the rest.
     */
    @Test
    fun `generatePdf does not follow redirects when fetching images`() {
        for (name in listOf("redirected.png", "redirected.tiff")) {
            wireMock.stubFor(
                get(urlEqualTo("/$name")).willReturn(
                    aResponse().withStatus(302).withHeader("Location", "/internal-target.png")
                )
            )
        }
        wireMock.stubFor(
            get(urlEqualTo("/internal-target.png")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "image/png").withBody(TINY_PNG)
            )
        )
        wireMock.stubFor(
            get(urlEqualTo("/article-with-redirects")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html><head><title>Redirecting images</title></head>
                        <body>
                            <p>Body text.</p>
                            <img src="/redirected.png" alt="supported extension"/>
                            <img src="/redirected.tiff" alt="unsupported extension"/>
                        </body></html>
                        """.trimIndent()
                    )
            )
        )

        val pdfBytes = pdfGenerator.generatePdf("http://localhost:${wireMock.port()}/article-with-redirects")

        assertTrue(String(pdfBytes.copyOfRange(0, 5)) == "%PDF-", "Should still produce a PDF")
        wireMock.verify(0, getRequestedFor(urlEqualTo("/internal-target.png")))
    }

    /**
     * Images are embedded as data URIs from the bytes we fetched, so a server lying about the
     * content type must not abort the whole document — the image is skipped instead.
     */
    @Test
    fun `generatePdf survives a body that is not really an image`() {
        wireMock.stubFor(
            get(urlEqualTo("/not-really.png")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withBody("<html>this is an error page, not a PNG</html>")
            )
        )
        wireMock.stubFor(
            get(urlEqualTo("/article-bad-image")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html><head><title>Bad image</title></head>
                        <body><p>Text survives.</p><img src="/not-really.png" alt="bad"/></body></html>
                        """.trimIndent()
                    )
            )
        )

        val pdfBytes = pdfGenerator.generatePdf("http://localhost:${wireMock.port()}/article-bad-image")

        assertTrue(String(pdfBytes.copyOfRange(0, 5)) == "%PDF-", "Should still produce a PDF")
        assertTrue(pdfBytes.size > 100, "PDF should still contain the page text")
    }

    @Test
    fun `generatePdf produces valid PDF from simple HTML page`() {
        wireMock.stubFor(
            get(urlEqualTo("/article")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html>
                        <head><title>Test Article</title></head>
                        <body>
                            <h1>Test Article</h1>
                            <p>This is a test paragraph with some content.</p>
                            <p>Second paragraph with <a href="https://example.com">a link</a>.</p>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/article"
        val pdfBytes = pdfGenerator.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100, "PDF should have substantial content")

        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-", "Output should be a valid PDF file, got: $header")

        wireMock.verify(getRequestedFor(urlEqualTo("/article")))
    }

    @Test
    fun `generatePdf handles page with scripts and styles`() {
        // Stub a small 1x1 PNG for the image reference
        wireMock.stubFor(
            get(urlEqualTo("/test-image.png")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withBody(TINY_PNG)
            )
        )

        wireMock.stubFor(
            get(urlEqualTo("/complex")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html>
                        <head>
                            <title>Complex Page</title>
                            <style>body { color: red; }</style>
                            <script>alert('xss')</script>
                        </head>
                        <body>
                            <nav>Navigation bar</nav>
                            <h1>Main Content</h1>
                            <p>Paragraph text here.</p>
                            <img src="/test-image.png" alt="test image"/>
                            <footer>Footer content</footer>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/complex"
        val pdfBytes = pdfGenerator.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100)

        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-")

        wireMock.verify(getRequestedFor(urlEqualTo("/test-image.png")))
    }

    @Test
    fun `generatePdf includes images from the page`() {
        wireMock.stubFor(
            get(urlEqualTo("/photo.png")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withBody(TINY_PNG)
            )
        )

        wireMock.stubFor(
            get(urlEqualTo("/with-image")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html>
                        <head><title>Article with Image</title></head>
                        <body>
                            <h1>Article</h1>
                            <p>Text before image.</p>
                            <img src="/photo.png" alt="A photo"/>
                            <p>Text after image.</p>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/with-image"
        val pdfBytes = pdfGenerator.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100)

        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-")

        // Verify the image was requested (included in PDF rendering)
        wireMock.verify(getRequestedFor(urlEqualTo("/photo.png")))
    }

    @Test
    fun `generatePdf handles lazy-loaded images with data-src`() {
        wireMock.stubFor(
            get(urlEqualTo("/lazy-img.png")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withBody(TINY_PNG)
            )
        )

        wireMock.stubFor(
            get(urlEqualTo("/lazy-page")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html>
                        <head><title>Lazy Images</title></head>
                        <body>
                            <h1>Lazy</h1>
                            <img src="" data-src="/lazy-img.png" alt="lazy"/>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/lazy-page"
        val pdfBytes = pdfGenerator.generatePdf(url)

        assertNotNull(pdfBytes)
        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-")

        wireMock.verify(getRequestedFor(urlEqualTo("/lazy-img.png")))
    }

    @Test
    fun `generatePdf converts unsupported image formats to PNG`() {
        wireMock.stubFor(
            get(urlEqualTo("/photo.webp")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/webp")
                    .withBody(TINY_WEBP)
            )
        )

        wireMock.stubFor(
            get(urlEqualTo("/with-webp")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html>
                        <head><title>WebP Article</title></head>
                        <body>
                            <h1>Article</h1>
                            <img src="/photo.webp" alt="webp image"/>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/with-webp"
        val pdfBytes = pdfGenerator.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100)

        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-")

        // WebP image was fetched and converted to PNG inline
        wireMock.verify(getRequestedFor(urlEqualTo("/photo.webp")))
    }

    @Test
    fun `generatePdf handles unicode content`() {
        wireMock.stubFor(
            get(urlEqualTo("/unicode")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody(
                        """
                        <html>
                        <head><title>Тестовая статья</title></head>
                        <body>
                            <h1>Заголовок на русском</h1>
                            <p>Текст параграфа с кириллицей и спецсимволами: &amp; &lt; &gt;</p>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/unicode"
        val pdfBytes = pdfGenerator.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100)
    }

    @Test
    fun `generatePdf throws exception for unreachable URL`() {
        assertThrows<Exception> {
            pdfGenerator.generatePdf("http://localhost:1/nonexistent")
        }
    }
}
