package ru.proshik.pochitushki

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.testcontainers.containers.PostgreSQLContainer
import ru.proshik.pochitushki.service.JwtService

@SpringBootTest(
    classes = [PochitushkiApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
class BaseIntegrationTest {

    @Autowired
    protected lateinit var jwtService: JwtService

    fun withAuth(userId: Long): RequestPostProcessor = RequestPostProcessor { request ->
        val token = jwtService.createToken(userId)
        request.setCookies(Cookie("auth_token", token))
        request
    }

    companion object {
        @JvmStatic
        val postgresContainer = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("testdb")
            withUsername("testuser")
            withPassword("testpassword")
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
        }

        @JvmField
        val wireMockTelegramApi: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

        @JvmField
        val wireMockOidc: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun telegramDynamicProps(registry: DynamicPropertyRegistry) {
            registry.add("telegram.api-url") { "http://localhost:${wireMockTelegramApi.port()}/" }
            registry.add("telegram.webhook-url") { "" }
            registry.add("telegram.oauth.base-url") { "http://localhost:${wireMockOidc.port()}" }
        }
    }
}
