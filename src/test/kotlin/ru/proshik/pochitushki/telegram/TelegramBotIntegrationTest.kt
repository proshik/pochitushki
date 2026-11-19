package ru.proshik.pochitushki.telegram

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.time.Duration
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.telegram.PendingLabelInput
import ru.proshik.pochitushki.service.telegram.MiniAppLinks
import ru.proshik.pochitushki.configuration.BotProvider

@AutoConfigureTestRestTemplate
@ActiveProfiles("test", "telegram-test")
class TelegramBotIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var i18nService: I18nService

    @Autowired
    private lateinit var pendingLabelInput: PendingLabelInput

    @Autowired
    private lateinit var miniAppLinks: MiniAppLinks

    @Autowired
    private lateinit var botProvider: BotProvider

    @Autowired
    private lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    private lateinit var wireMockExternalUrl: WireMockServer

    // Общий на класс и монотонный. Контроллер отбрасывает повторный update_id (Telegram
    // пересылает необработанный апдейт), а кэш дедупликации живёт в контексте Spring —
    // то есть переживает отдельный тест. Счётчик, стартующий заново в каждом тесте, отдавал
    // бы уже виденные id, и апдейты молча исчезали.
    private fun nextUpdateId(): Int = updateIdCounter.incrementAndGet()

    @BeforeEach
    fun setUp() {
        setupTelegramApiStubs()

        wireMockExternalUrl = WireMockServer(wireMockConfig().dynamicPort())
        wireMockExternalUrl.start()
    }

    @AfterEach
    fun tearDown() {
        wireMockExternalUrl.stop()
        wireMockTelegramApi.resetAll()
        // In-memory and shared by the whole context: a label prompt left by one test would
        // swallow the next test's first message as a label name.
        pendingLabelInput.cancel(100)
        jdbcTemplate.execute("DELETE FROM archive_post_label")
        jdbcTemplate.execute("DELETE FROM post_label")
        jdbcTemplate.execute("DELETE FROM label")
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    // ==================== Command tests ====================

    @Test
    fun `start command should call sendMessage with chat_id`() {
        postUpdate(buildStartUpdate(updateId = nextUpdateId(), messageId = nextUpdateId()))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `url message reply quotes the original message`() {
        registerTestUser()
        wireMockExternalUrl.stubFor(
            get(urlEqualTo("/quoted")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody("<html><head><title>Quoted</title></head><body/></html>")
            )
        )

        val updateId = nextUpdateId()
        postUpdate(buildTextMessage(updateId, "http://localhost:${wireMockExternalUrl.port()}/quoted"))

        awaitSendMessage()
        val calls = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendMessage")))
        assertTrue(
            calls.any { it.bodyAsString.contains("\"reply_parameters\":{\"message_id\":$updateId}") },
            "Expected the confirmation to reply to message $updateId via reply_parameters"
        )
    }

    @Test
    fun `help command should send help message`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/help", "help"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    // ==================== Card: one message, edited in place ====================

    @Test
    fun `feed sends exactly one card`() {
        registerTestUser()
        val userId = getUserId()
        createPostAt(userId, "Card 1", "https://card-1.com", minutesAgo = 30)
        createPostAt(userId, "Card 2", "https://card-2.com", minutesAgo = 20)
        createPostAt(userId, "Card 3", "https://card-3.com", minutesAgo = 10)

        postUpdate(buildCommandMessage(nextUpdateId(), "/feed", "feed"))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains("\"parse_mode\":\"HTML\""), body)
        assertTrue(body.contains(">Card 3</a>"), "newest post first: $body")
        assertTrue(body.contains("\"link_preview_options\":{\"url\":\"https://card-3.com\""), body)
        assertTrue(body.contains("\"text\":\"1 / 3\""), body)
    }

    @Test
    fun `next arrow edits the same message`() {
        registerTestUser()
        val userId = getUserId()
        createPostAt(userId, "Card 1", "https://card-1.com", minutesAgo = 30)
        val second = createPostAt(userId, "Card 2", "https://card-2.com", minutesAgo = 20)
        val third = createPostAt(userId, "Card 3", "https://card-3.com", minutesAgo = 10)

        postUpdate(buildCallbackQuery(nextUpdateId(), 77L, "cn|u:1:$third:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains("\"message_id\":77"), body)
        assertTrue(body.contains(">Card 2</a>"), body)
        assertTrue(body.contains("\"text\":\"2 / 3\""), body)
        assertTrue(body.contains("ca|u:1:$second:u"), "buttons now act on the shown post: $body")
        assertEquals(0, wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))).size)
    }

    @Test
    fun `feed without posts says so in one message`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/feed", "feed"))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(jsonText(i18nService.getMessage("command.feed.not_found_post", "en"))), body)
    }

    @Test
    fun `archive command shows archive card`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Archived", "https://archived.com")
        archivePost(postId)
        val archiveId = getArchivePostId(userId)

        postUpdate(buildCommandMessage(nextUpdateId(), "/archive", "archive"))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(">Archived</a>"), body)
        assertTrue(body.contains("cu|a:0:$archiveId:a"), body)
    }

    @Test
    fun `favorites command shows favorites card`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Starred", "https://starred.com")
        markAsFavorite(postId)

        postUpdate(buildCommandMessage(nextUpdateId(), "/favorites", "favorites"))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(">Starred</a>"), body)
        assertTrue(body.contains("ca|f:0:$postId:u"), body)
        assertTrue(body.contains("\"text\":\"★\""), body)
    }

    // ==================== Card actions ====================

    @Test
    fun `archive shows the next post at the same offset`() {
        registerTestUser()
        val userId = getUserId()
        createPostAt(userId, "P1", "https://p1.com", minutesAgo = 30)
        val p2 = createPostAt(userId, "P2", "https://p2.com", minutesAgo = 20)
        createPostAt(userId, "P3", "https://p3.com", minutesAgo = 10)

        postUpdate(buildCallbackQuery(nextUpdateId(), 80L, "ca|u:1:$p2:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">P1</a>"), body)
        assertTrue(body.contains("\"text\":\"2 / 2\""), body)
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM archive_post WHERE url = 'https://p2.com'", Int::class.java))
    }

    @Test
    fun `archive of the last post steps back`() {
        registerTestUser()
        val userId = getUserId()
        val p1 = createPostAt(userId, "P1", "https://p1.com", minutesAgo = 30)
        createPostAt(userId, "P2", "https://p2.com", minutesAgo = 20)
        createPostAt(userId, "P3", "https://p3.com", minutesAgo = 10)

        postUpdate(buildCallbackQuery(nextUpdateId(), 81L, "ca|u:2:$p1:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">P2</a>"), body)
        assertTrue(body.contains("\"text\":\"2 / 2\""), body)
    }

    @Test
    fun `archiving the only post shows all read without buttons`() {
        registerTestUser()
        val p = createPost(getUserId(), "Only", "https://only.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 82L, "ca|u:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(jsonText(i18nService.getMessage("card.all_read", "en"))), body)
        assertTrue(body.contains("\"inline_keyboard\":[]"), body)
    }

    @Test
    fun `delete asks first`() {
        registerTestUser()
        val p = createPost(getUserId(), "Doomed", "https://doomed.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 83L, "cd|u:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(body.contains("cy|u:0:$p:u") && body.contains("cx|u:0:$p:u"), body)
        assertEquals(1, getPostCount("post"))
    }

    @Test
    fun `confirmed delete removes the post for good`() {
        registerTestUser()
        val userId = getUserId()
        createPostAt(userId, "Stays", "https://stays.com", minutesAgo = 20)
        val p = createPostAt(userId, "Doomed", "https://doomed.com", minutesAgo = 10)

        postUpdate(buildCallbackQuery(nextUpdateId(), 84L, "cy|u:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">Stays</a>"), body)
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM post WHERE id = ?", Int::class.java, p))
        assertEquals(0, getPostCount("archive_post"))
    }

    @Test
    fun `cancel delete restores the card buttons`() {
        registerTestUser()
        val p = createPost(getUserId(), "Spared", "https://spared.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 85L, "cx|u:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(body.contains("ca|u:0:$p:u") && body.contains("cd|u:0:$p:u"), body)
        assertEquals(1, getPostCount("post"))
    }

    @Test
    fun `star redraws only the keyboard`() {
        registerTestUser()
        val p = createPost(getUserId(), "Star me", "https://star.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 86L, "cf|u:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(body.contains("\"text\":\"★\""), body)
        assertEquals(0, wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/editMessageText"))).size)
        assertEquals(true, jdbcTemplate.queryForObject("SELECT is_favorite FROM post WHERE id = ?", Boolean::class.java, p))
    }

    @Test
    fun `favorites archive keeps the card with the new id`() {
        registerTestUser()
        val userId = getUserId()
        val p = createPost(userId, "Fav", "https://fav.com")
        markAsFavorite(p)

        postUpdate(buildCallbackQuery(nextUpdateId(), 87L, "ca|f:0:$p:u"))

        awaitCondition { getPostCount("archive_post") == 1 }
        val archiveId = getArchivePostId(userId)
        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">Fav</a>"), body)
        assertTrue(body.contains("cu|f:0:$archiveId:a"), body)
    }

    @Test
    fun `unstar in favorites keeps the card`() {
        registerTestUser()
        val p = createPost(getUserId(), "Fav", "https://fav.com")
        markAsFavorite(p)

        postUpdate(buildCallbackQuery(nextUpdateId(), 88L, "cf|f:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(body.contains("\"text\":\"☆\""), body)
        assertEquals(0, wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/deleteMessage"))).size)
    }

    @Test
    fun `unstarred favorite still pages to the rest`() {
        registerTestUser()
        val userId = getUserId()
        val older = createPostAt(userId, "F2", "https://f2.com", minutesAgo = 20)
        val newer = createPostAt(userId, "F1", "https://f1.com", minutesAgo = 10)
        markAsFavorite(older)
        markAsFavorite(newer)

        postUpdate(buildCallbackQuery(nextUpdateId(), 103L, "cf|f:0:$newer:u"))

        val keyboard = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(keyboard.contains("\"text\":\"1 / 2\""), keyboard)
        // F2 moved up to offset 0 once F1 left the list: "›" must lead there, not to offset 1.
        assertTrue(keyboard.contains("\"text\":\"›\",\"callback_data\":\"cn|f:0:$newer:u\""), keyboard)

        postUpdate(buildCallbackQuery(nextUpdateId(), 103L, "cn|f:0:$newer:u"))
        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">F2</a>"), body)
    }

    @Test
    fun `labels back redraws the card text with fresh labels`() {
        registerTestUser()
        val userId = getUserId()
        val p = createPost(userId, "Tagged", "https://tagged.com")
        val label = createLabel(userId, "kotlin")
        jdbcTemplate.update("INSERT INTO post_label(post_id, label_id) VALUES (?, ?)", p, label)

        postUpdate(buildCallbackQuery(nextUpdateId(), 104L, "lb|u:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains("#kotlin") && body.contains("cd|u:0:$p:u"), body)
    }

    @Test
    fun `stale card toasts and redraws`() {
        registerTestUser()
        val userId = getUserId()
        val gone = createPostAt(userId, "Gone", "https://gone.com", minutesAgo = 20)
        createPostAt(userId, "Current", "https://current.com", minutesAgo = 10)
        jdbcTemplate.update("DELETE FROM post WHERE id = ?", gone)

        val updateId = nextUpdateId()
        postUpdate(buildCallbackQuery(updateId, 89L, "ca|u:1:$gone:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">Current</a>"), body)
        val answer = awaitAnswer("$updateId")
        assertTrue(answer.contains("text=" + i18nService.getMessage("card.toast.gone", "en")), answer)
    }

    @Test
    fun `double tap archives once`() {
        registerTestUser()
        val p = createPost(getUserId(), "Twice", "https://twice.com")

        val first = nextUpdateId()
        val second = nextUpdateId()
        postUpdate(buildCallbackQuery(first, 90L, "ca|u:0:$p:u"))
        postUpdate(buildCallbackQuery(second, 90L, "ca|u:0:$p:u"))

        awaitAnswer("$first")
        awaitAnswer("$second")
        assertEquals(1, getPostCount("archive_post"))
        assertEquals(0, getPostCount("post"))
    }

    @Test
    fun `every press is answered`() {
        registerTestUser()
        val p = createPost(getUserId(), "Pressed", "https://pressed.com")

        val ids = listOf("cn|u:0:$p:u", "cf|u:0:$p:u", "cd|u:0:$p:u", "c0|u:0:$p:u").map { data ->
            nextUpdateId().also { postUpdate(buildCallbackQuery(it, 91L, data)) }
        }

        ids.forEach { awaitAnswer("$it") }
        // The library's own callbackQuery DSL answers after the handler; ours must be the only one.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until {
            ids.all { id -> answersFor("$id").size == 1 }
        }
    }

    @Test
    fun `legacy buttons get an outdated toast`() {
        registerTestUser()
        val p = createPost(getUserId(), "Old card", "https://old-card.com")

        val archive = nextUpdateId()
        val next = nextUpdateId()
        postUpdate(buildCallbackQuery(archive, 92L, "archive_button|$p"))
        postUpdate(buildCallbackQuery(next, 92L, "next_posts|3"))

        val outdated = "text=" + i18nService.getMessage("card.toast.outdated", "en")
        assertTrue(awaitAnswer("$archive").contains(outdated))
        assertTrue(awaitAnswer("$next").contains(outdated))
        assertEquals(1, getPostCount("post"))
    }

    @Test
    fun `garbage card data is answered`() {
        registerTestUser()

        val id = nextUpdateId()
        postUpdate(buildCallbackQuery(id, 93L, "ca|zz"))

        assertTrue(!awaitAnswer("$id").contains("text="))
    }

    // ==================== Card: the ⋯ row ====================

    @Test
    fun `more opens and closes the second row`() {
        registerTestUser()
        val p = createPost(getUserId(), "More", "https://more.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 94L, "cm|u:0:$p:u:1"))
        val opened = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(opened.contains("cp|u:0:$p:u") && opened.contains("lp|u:0:$p:u"), opened)
        assertTrue(opened.contains("cm|u:0:$p:u:0"), "⋯ now folds the row: $opened")

        wireMockTelegramApi.resetAll()
        setupTelegramApiStubs()
        postUpdate(buildCallbackQuery(nextUpdateId(), 94L, "cm|u:0:$p:u:0"))
        val closed = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(!closed.contains("cp|") && !closed.contains("lp|"), closed)
    }

    @Test
    fun `labels back returns to the same offset`() {
        registerTestUser()
        val userId = getUserId()
        for (i in 1..3) markAsFavorite(createPostAt(userId, "F$i", "https://f$i.com", minutesAgo = 10L * i))
        val oldest = jdbcTemplate.queryForObject("SELECT id FROM post WHERE url = 'https://f3.com'", Long::class.java)!!
        archivePost(oldest)
        jdbcTemplate.update("UPDATE archive_post SET created_date = now() - interval '30 minutes', is_favorite = true")
        val archiveId = getArchivePostId(userId)

        postUpdate(buildCallbackQuery(nextUpdateId(), 95L, "lb|f:2:$archiveId:a"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains("\"text\":\"3 / 3\""), body)
        assertTrue(body.contains("cu|f:2:$archiveId:a"), body)
    }

    @Test
    fun `pdf from card answers with a toast`() {
        registerTestUser()
        val p = createPost(getUserId(), "Paper", "http://localhost:${wireMockExternalUrl.port()}/paper")
        val pdfErrors = meterRegistry.counter("pochitushki.operation.errors", "operation", "pdf", "reason", "error")
        val pdfErrorsBefore = pdfErrors.count()

        wireMockExternalUrl.stubFor(
            get(urlEqualTo("/paper")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody("<html><head><title>Paper</title></head><body><p>Text</p></body></html>")
            )
        )

        val id = nextUpdateId()
        postUpdate(buildCallbackQuery(id, 96L, "cp|u:0:$p:u"))

        val answer = awaitAnswer("$id")
        assertTrue(answer.contains("text=" + i18nService.getMessage("card.toast.pdf_started", "en")), answer)
        // The job runs on BotTaskExecutor: wait for the document itself, before teardown stops the stubs.
        await().atMost(Duration.ofSeconds(30)).until {
            wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendDocument"))).isNotEmpty()
        }
        val document = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendDocument"))).single()
        assertTrue(document.bodyAsString.contains("Paper.pdf") && document.bodyAsString.contains("%PDF"), "a PDF for the post")
        // A delivered document is a success: no failure counted, no error message after it.
        Thread.sleep(300)
        assertEquals(pdfErrorsBefore, pdfErrors.count(), "a delivered PDF counted as a failure")
        val errorText = i18nService.getMessage("command.pdf.error", "en")
        val replies = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendMessage")))
            .map { java.net.URLDecoder.decode(it.bodyAsString, Charsets.UTF_8) }
        assertTrue(replies.none { it.contains(errorText) }, "an error message after a delivered PDF")
    }

    // ==================== Card: random post and the reply to a saved link ====================

    @Test
    fun `random_post sends one card with a dice button`() {
        registerTestUser()
        val p = createPost(getUserId(), "Lucky", "https://lucky.com")

        postUpdate(buildCommandMessage(nextUpdateId(), "/random_post", "random_post"))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(">Lucky</a>"), body)
        assertTrue(body.contains("cr|r:0:$p:u"), body)
        assertTrue(!body.contains("cn|"), "no paging on a random card: $body")
    }

    @Test
    fun `random_post without posts says so`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/random_post", "random_post"))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(jsonText(i18nService.getMessage("command.random_post.not_found", "en"))), body)
    }

    @Test
    fun `dice edits the same message`() {
        registerTestUser()
        val p = createPost(getUserId(), "Lucky", "https://lucky.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 97L, "cr|r:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains("\"message_id\":97") && body.contains(">Lucky</a>"), body)
    }

    @Test
    fun `archive on random shows another random`() {
        registerTestUser()
        val userId = getUserId()
        val a = createPost(userId, "A", "https://a.com")
        createPost(userId, "B", "https://b.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 98L, "ca|r:0:$a:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(">B</a>"), body)
        assertEquals(1, getPostCount("archive_post"))
    }

    @Test
    fun `saved link reply is a card without paging`() {
        registerTestUser()
        wireMockExternalUrl.stubFor(
            get(urlEqualTo("/article")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody("<html><head><title>Test Article Title</title></head><body>Content</body></html>")
            )
        )

        val updateId = nextUpdateId()
        postUpdate(buildTextMessage(updateId, "http://localhost:${wireMockExternalUrl.port()}/article"))

        awaitPostCreated()
        val postId = jdbcTemplate.queryForObject("SELECT id FROM post WHERE url LIKE '%/article'", Long::class.java)!!
        assertEquals("Test Article Title", jdbcTemplate.queryForObject("SELECT title FROM post WHERE id = ?", String::class.java, postId))
        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(jsonText(i18nService.getMessage("card.saved.added", "en"))), body)
        assertTrue(body.contains(">Test Article Title</a>"), body)
        assertTrue(body.contains("ca|s:0:$postId:u"), body)
        assertTrue(body.contains("\"reply_parameters\":{\"message_id\":$updateId}"), body)
        assertTrue(!body.contains(" / "), "no counter on the saved reply: $body")
    }

    @Test
    fun `saving a known link says it is already there`() {
        registerTestUser()
        val url = "http://localhost:${wireMockExternalUrl.port()}/known"
        val p = createPost(getUserId(), "Known", url)

        postUpdate(buildTextMessage(nextUpdateId(), url))

        val body = awaitSingleCall("/bottest_token/sendMessage")
        assertTrue(body.contains(jsonText(i18nService.getMessage("card.saved.already", "en"))), body)
        assertTrue(body.contains("ca|s:0:$p:u"), body)
    }

    @Test
    fun `archive from the saved reply turns it into a status line`() {
        registerTestUser()
        val p = createPost(getUserId(), "Saved", "https://saved.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 99L, "ca|s:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(jsonText(i18nService.getMessage("card.saved.archived", "en"))), body)
        assertTrue(body.contains("\"inline_keyboard\":[]"), body)
        assertEquals(1, getPostCount("archive_post"))
    }

    @Test
    fun `delete from the saved reply turns it into a status line`() {
        registerTestUser()
        val p = createPost(getUserId(), "Saved", "https://saved.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 100L, "cy|s:0:$p:u"))

        val body = awaitSingleCall("/bottest_token/editMessageText")
        assertTrue(body.contains(jsonText(i18nService.getMessage("card.saved.deleted", "en"))), body)
        assertEquals(0, getPostCount("post"))
    }

    @Test
    fun `profile no longer offers feed size`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/profile", "profile"))

        awaitSendMessage()
        val calls = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendMessage")))
        assertTrue(calls.none { it.bodyAsString.contains("profile_feed_settings") })
    }

    @Test
    fun `old feed size button gets an outdated toast`() {
        registerTestUser()

        val id = nextUpdateId()
        postUpdate(buildCallbackQuery(id, 101L, "profile_feed_settings|_"))

        assertTrue(awaitAnswer("$id").contains("text=" + i18nService.getMessage("card.toast.outdated", "en")))
    }

    // ==================== Mini App entry points ====================

    @Test
    fun `more row opens the app`() {
        registerTestUser()
        val p = createPost(getUserId(), "Shelved", "https://shelved.com")
        archivePost(p)
        val archiveId = getArchivePostId(getUserId())

        postUpdate(buildCallbackQuery(nextUpdateId(), 102L, "cm|a:0:$archiveId:a:1"))

        val body = awaitSingleCall("/bottest_token/editMessageReplyMarkup")
        assertTrue(body.contains("\"web_app\":{\"url\":\"https://app.test/tg?p=archive\"}"), body)
    }

    @Test
    fun `start offers the app button`() {
        postUpdate(buildStartUpdate(updateId = nextUpdateId(), messageId = nextUpdateId()))

        awaitSendMessage()
        val calls = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/sendMessage")))
            .map { java.net.URLDecoder.decode(it.bodyAsString, Charsets.UTF_8) }
        assertTrue(calls.any { it.contains("\"web_app\":{\"url\":\"https://app.test/tg\"}") }, calls.toString())
    }

    @Test
    fun `menu button is installed`() {
        miniAppLinks.installMenuButton(botProvider.getBot())

        val body = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/setChatMenuButton")))
            .single().let { java.net.URLDecoder.decode(it.bodyAsString, Charsets.UTF_8) }
        assertTrue(body.contains("\"type\":\"web_app\"") && body.contains("https://app.test/tg"), body)
    }

    @Test
    fun `post_labels callback should offer the label picker`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Labelling", "https://labelling.com")
        createLabel(userId, "kotlin")

        postUpdate(buildCallbackQuery(nextUpdateId(), 61L, "lp|u:0:$postId:u"))

        awaitTelegramApiCalls("/bottest_token/editMessageReplyMarkup", 1)
        val calls = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/editMessageReplyMarkup")))
        assertTrue(
            calls.any { it.bodyAsString.contains("lt|u:0:$postId:u:") },
            "Expected the picker keyboard to carry a toggle callback"
        )
    }

    @Test
    fun `post_label_toggle callback should attach then detach the label`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Toggling", "https://toggling.com")
        val labelId = createLabel(userId, "kotlin")

        postUpdate(buildCallbackQuery(nextUpdateId(), 62L, "lt|u:0:$postId:u:$labelId"))
        await().atMost(Duration.ofSeconds(10)).until { countLinks() == 1 }

        postUpdate(buildCallbackQuery(nextUpdateId(), 62L, "lt|u:0:$postId:u:$labelId"))
        await().atMost(Duration.ofSeconds(10)).until { countLinks() == 0 }
    }

    @Test
    fun `after the new-label prompt a plain message becomes a label, not a post`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "Prompting", "https://prompting.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 63L, "ln|u:0:$postId:u"))
        awaitTelegramApiCalls("/bottest_token/sendMessage", 1)

        postUpdate(buildCommandMessage(nextUpdateId(), "вечером", "text"))

        await().atMost(Duration.ofSeconds(10)).until { countLinks() == 1 }
        assertTrue(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM label WHERE user_id = ? AND name = 'вечером'", Int::class.java, userId
            ) == 1
        )
        // and the text did not turn into a saved link
        assertTrue(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM post WHERE user_id = ?", Int::class.java, userId
            ) == 1
        )
    }

    @Test
    fun `an empty label list asks for the first name instead of showing nothing`() {
        registerTestUser()
        val userId = getUserId()
        val postId = createPost(userId, "No labels yet", "https://nolabels.com")

        postUpdate(buildCallbackQuery(nextUpdateId(), 64L, "lp|u:0:$postId:u"))

        awaitTelegramApiCalls("/bottest_token/sendMessage", 1)
    }

    @Test
    fun `profile command should send profile settings`() {
        registerTestUser()

        postUpdate(buildCommandMessage(nextUpdateId(), "/profile", "profile"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    @Test
    fun `invalid URL should send error message`() {
        registerTestUser()

        postUpdate(buildTextMessage(nextUpdateId(), "not-a-valid-url"))

        awaitSendMessage()
        wireMockTelegramApi.verify(
            postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
                .withRequestBody(containing("chat_id=100"))
        )
    }

    // ==================== Callback query tests ====================

    @Test
    fun `profile_language_settings callback should show language settings`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 62L, "profile_language_settings|_"))

        awaitEditMessage()
    }

    @Test
    fun `profile_language_settings_changed callback should update language`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 63L, "profile_language_settings_changed|ru"))

        awaitCondition {
            val settings = jdbcTemplate.queryForObject(
                "SELECT settings::TEXT FROM users WHERE telegram_id = 100", String::class.java
            )
            settings != null && settings.contains("ru")
        }
    }

    @Test
    fun `profile_back_button_settings callback should show profile`() {
        registerTestUser()

        postUpdate(buildCallbackQuery(nextUpdateId(), 65L, "profile_back_button_settings|_"))

        awaitSendMessage()
    }

    // ==================== Helper methods ====================

    private fun setupTelegramApiStubs() {
        wireMockTelegramApi.stubFor(
            post(anyUrl()).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":true,"result":{"message_id":1,"chat":{"id":100,"type":"private"},"text":"ok","date":0}}""")
            )
        )
        wireMockTelegramApi.stubFor(
            get(anyUrl()).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"ok":true,"result":[]}""")
            )
        )
    }

    private fun registerTestUser() {
        postUpdate(buildStartUpdate(updateId = nextUpdateId(), messageId = nextUpdateId()))
        awaitUserCreated()
        wireMockTelegramApi.resetAll()
        setupTelegramApiStubs()
    }

    companion object {
        private val updateIdCounter = java.util.concurrent.atomic.AtomicInteger(1000)
    }

    private fun getUserId(): Long {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE telegram_id = 100", Long::class.java
        )!!
    }

    private fun createPost(userId: Long, title: String, url: String): Long {
        return jdbcTemplate.queryForObject(
            "INSERT INTO post (title, url, user_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, url, userId
        )!!
    }

    private fun createPostAt(userId: Long, title: String, url: String, minutesAgo: Long): Long {
        return jdbcTemplate.queryForObject(
            "INSERT INTO post (title, url, user_id, created_date) VALUES (?, ?, ?, now() - make_interval(mins => ?)) RETURNING id",
            Long::class.java, title, url, userId, minutesAgo.toInt()
        )!!
    }

    /** Waits for one call to [url], makes sure no second one follows, and returns its body. */
    private fun awaitSingleCall(url: String): String {
        awaitTelegramApiCalls(url, 1)
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until {
            wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo(url))).size == 1
        }
        return wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo(url))).single().bodyAsString
    }

    private fun answersFor(id: String): List<String> =
        wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/answerCallbackQuery")))
            .map { java.net.URLDecoder.decode(it.bodyAsString, Charsets.UTF_8) }
            .filter { it.contains("callback_query_id=$id&") || it.endsWith("callback_query_id=$id") }

    /** The decoded answerCallbackQuery body for callback query [id]. */
    private fun awaitAnswer(id: String): String {
        var found: String? = null
        await().atMost(Duration.ofSeconds(5)).until {
            found = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo("/bottest_token/answerCallbackQuery")))
                .map { java.net.URLDecoder.decode(it.bodyAsString, Charsets.UTF_8) }
                .firstOrNull { it.contains("callback_query_id=$id&") || it.endsWith("callback_query_id=$id") }
            found != null
        }
        return found!!
    }

    /** How Jackson writes [text] inside a JSON string. */
    private fun jsonText(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun createLabel(userId: Long, name: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO label(user_id, name) VALUES (?, ?) RETURNING id",
            Long::class.java, userId, name
        )!!

    private fun countLinks(): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM post_label", Int::class.java)!!

    private fun markAsFavorite(postId: Long) {
        jdbcTemplate.update("UPDATE post SET is_favorite = true WHERE id = ?", postId)
    }

    private fun markArchiveAsFavorite(archivePostId: Long) {
        jdbcTemplate.update("UPDATE archive_post SET is_favorite = true WHERE id = ?", archivePostId)
    }

    private fun archivePost(postId: Long) {
        jdbcTemplate.update(
            """INSERT INTO archive_post(title, url, tags, user_id, is_favorite)
               SELECT title, url, tags, user_id, is_favorite FROM post WHERE id = ?""", postId
        )
        jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId)
    }

    private fun getArchivePostId(userId: Long): Long {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!
    }

    private fun getPostCount(table: String): Int {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    }

    private fun postUpdate(json: String) {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Telegram-Bot-Api-Secret-Token", "test-secret")
        }
        restTemplate.postForEntity("/telegram/webhook", HttpEntity(json, headers), String::class.java)
    }

    private fun awaitSendMessage() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            wireMockTelegramApi.verify(
                postRequestedFor(urlEqualTo("/bottest_token/sendMessage"))
            )
        }
    }

    private fun awaitEditMessage() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            wireMockTelegramApi.verify(
                postRequestedFor(urlEqualTo("/bottest_token/editMessageText"))
            )
        }
    }

    private fun awaitTelegramApiCalls(url: String, minCount: Int) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val count = wireMockTelegramApi.findAll(postRequestedFor(urlEqualTo(url))).size
            assertTrue(count >= minCount, "Expected at least $minCount calls to $url but got $count")
        }
    }

    private fun awaitUserCreated() {
        await().atMost(Duration.ofSeconds(5)).until {
            jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE telegram_id = 100", Int::class.java)!! > 0
        }
    }

    private fun awaitPostCreated() {
        await().atMost(Duration.ofSeconds(5)).until {
            jdbcTemplate.queryForObject("SELECT count(*) FROM post WHERE url LIKE '%/article%'", Int::class.java)!! > 0
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        await().atMost(Duration.ofSeconds(5)).until { condition() }
    }

    private fun buildStartUpdate(updateId: Int, messageId: Int): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $messageId,
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "chat": {"id": 100, "type": "private"},
            "text": "/start",
            "entities": [{"type": "bot_command", "offset": 0, "length": 6}]
          }
        }
    """.trimIndent()

    private fun buildCommandMessage(updateId: Int, text: String, command: String): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $updateId,
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "chat": {"id": 100, "type": "private"},
            "text": "$text",
            "entities": [{"type": "bot_command", "offset": 0, "length": ${text.length}}]
          }
        }
    """.trimIndent()

    private fun buildTextMessage(updateId: Int, text: String): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $updateId,
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "chat": {"id": 100, "type": "private"},
            "text": "$text"
          }
        }
    """.trimIndent()

    private fun buildCallbackQuery(updateId: Int, messageId: Long, callbackData: String): String = """
        {
          "update_id": $updateId,
          "callback_query": {
            "id": "$updateId",
            "from": {"id": 100, "first_name": "Ivan", "username": "ivan_test", "is_bot": false, "language_code": "en"},
            "message": {
              "message_id": $messageId,
              "from": {"id": 999, "first_name": "Bot", "is_bot": true},
              "chat": {"id": 100, "type": "private"},
              "text": "some message",
              "date": 0
            },
            "data": "$callbackData"
          }
        }
    """.trimIndent()
}
