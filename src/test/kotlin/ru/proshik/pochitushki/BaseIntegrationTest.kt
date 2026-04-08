package ru.proshik.pochitushki

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(
    classes = [PochitushkiApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
class BaseIntegrationTest() {

    companion object {
        // Container is started once for the entire test suite (Ryuk handles cleanup at JVM exit).
        // Using manual start instead of @Container + @Testcontainers to prevent per-class
        // stop/start cycles that break cached Spring contexts pointing to a closed port.
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

        @DynamicPropertySource
        @JvmStatic
        fun telegramDynamicProps(registry: DynamicPropertyRegistry) {
            registry.add("telegram.api-url") { "http://localhost:${wireMockTelegramApi.port()}/" }
            registry.add("telegram.webhook-url") { "" }
        }
    }
}
