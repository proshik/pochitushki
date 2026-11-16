package ru.proshik.pochitushki.service.telegram.widget

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * The post card widget: one HTML document with inline CSS and JS, sent as-is by `sendWidget`.
 * It lives outside `templates/` and `static/` on purpose — it is not Thymeleaf, and the site
 * must not serve it. Telegram caps it at 32768 bytes and 4096 bytes after zlib level 9
 * (WidgetTemplateTest checks both); the widget runs in QuickJS without network, external
 * resources or web fonts, so the cover is drawn with CSS and the post data comes in chat_data.
 */
@Component
class WidgetTemplate {
    val html: String = ClassPathResource("telegram/widget/post-card.html").inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
}
