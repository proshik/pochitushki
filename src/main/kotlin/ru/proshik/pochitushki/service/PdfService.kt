package ru.proshik.pochitushki.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import java.io.ByteArrayOutputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PdfService {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun generatePdf(url: String): ByteArray {
        logger.debug("generatePdf: url={}", url)

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; Pochitushki/1.0)")
            .timeout(15000)
            .get()

        val title = doc.title()

        val cleaner = Cleaner(Safelist.relaxed())
        val cleanDoc = cleaner.clean(doc)

        // Remove images to avoid resource loading issues in PDF renderer
        cleanDoc.select("img").remove()

        cleanDoc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8")

        val bodyContent = cleanDoc.body().html()
        val xhtml = buildXhtml(xmlEscape(title), xmlEscape(url), bodyContent)

        val os = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(xhtml, url)
            .toStream(os)
            .run()

        logger.info("generatePdf success: url={}, size={}", url, os.size())

        return os.toByteArray()
    }

    private fun buildXhtml(title: String, url: String, bodyContent: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
  "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <meta charset="UTF-8"/>
    <title>$title</title>
    <style>
        body { font-family: serif; font-size: 12pt; line-height: 1.6; margin: 40px; }
        h1 { font-size: 18pt; margin-bottom: 10px; }
        h2 { font-size: 16pt; }
        h3 { font-size: 14pt; }
        p { margin-bottom: 8px; }
        a { color: #0066cc; }
        pre { background-color: #f5f5f5; padding: 10px; font-size: 10pt; }
        code { font-family: monospace; font-size: 10pt; }
        blockquote { border-left: 3px solid #ccc; margin-left: 0; padding-left: 15px; color: #666; }
    </style>
</head>
<body>
    <h1>$title</h1>
    <p><a href="$url">$url</a></p>
    <hr/>
    $bodyContent
</body>
</html>"""
    }

    private fun xmlEscape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
