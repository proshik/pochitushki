package ru.proshik.pochitushki.configuration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.service.telegram.widget.WidgetMode

@ExtendWith(OutputCaptureExtension::class)
class TelegramBotConfigurationTest {

    private fun props(widgets: Boolean, webhookUrl: String?) = TelegramProperties(
        enabled = true,
        webhookUrl = webhookUrl,
        widgets = TelegramProperties.Widgets(enabled = widgets),
    )

    @Test
    fun `allowed updates without widgets`() {
        assertEquals(listOf("message", "callback_query"), TelegramBotConfiguration.allowedUpdates(widgetsActive = false))
    }

    @Test
    fun `allowed updates with widgets`() {
        assertEquals(
            listOf("message", "callback_query", "widget_query", "shared_widget_query"),
            TelegramBotConfiguration.allowedUpdates(widgetsActive = true),
        )
    }

    @Test
    fun `widgets are active only with the flag and a webhook`() {
        assertTrue(WidgetMode(props(widgets = true, webhookUrl = "https://x/t")).active)
        assertFalse(WidgetMode(props(widgets = true, webhookUrl = null)).active)
        assertFalse(WidgetMode(props(widgets = true, webhookUrl = "")).active)
        assertFalse(WidgetMode(props(widgets = false, webhookUrl = "https://x/t")).active)
    }

    @Test
    fun `polling with widgets enabled logs a warning`(output: CapturedOutput) {
        WidgetMode(props(widgets = true, webhookUrl = null))
        assertTrue(output.out.lines().any { "WARN" in it && "[WidgetMode]" in it }, output.out)
    }

    @Test
    fun `flag off stays quiet`(output: CapturedOutput) {
        WidgetMode(props(widgets = false, webhookUrl = null))
        assertFalse(output.out.lines().any { "[WidgetMode]" in it && "WARN" in it })
    }
}
