package ru.proshik.pochitushki.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import ru.proshik.pochitushki.BaseIntegrationTest

class PdfServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var pdfService: PdfService

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
        val pdfBytes = pdfService.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100, "PDF should have substantial content")

        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-", "Output should be a valid PDF file, got: $header")

        wireMock.verify(getRequestedFor(urlEqualTo("/article")))
    }

    @Test
    fun `generatePdf handles page with scripts and styles`() {
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
                            <img src="/nonexistent.png" alt="missing"/>
                            <footer>Footer content</footer>
                        </body>
                        </html>
                        """.trimIndent()
                    )
            )
        )

        val url = "http://localhost:${wireMock.port()}/complex"
        val pdfBytes = pdfService.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100)

        val header = String(pdfBytes.copyOfRange(0, 5))
        assertTrue(header == "%PDF-")
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
        val pdfBytes = pdfService.generatePdf(url)

        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.size > 100)
    }

    @Test
    fun `generatePdf throws exception for unreachable URL`() {
        assertThrows<Exception> {
            pdfService.generatePdf("http://localhost:1/nonexistent")
        }
    }
}
