plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    id("java-library")
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.proshik"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven { url = uri("https://jitpack.io") }
    mavenCentral()
}


// Keep Spring's BOM-managed Kotlin artifacts (stdlib, reflect, coroutines) aligned with the
// Kotlin plugin version above; otherwise io.spring.dependency-management pins them to the older
// version shipped in the Spring Boot BOM.
extra["kotlin.version"] = "2.4.0"
extra["springCloudVersion"] = "2025.0.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Реестр метрик лежит в classpath, но эндпоинт /actuator/prometheus по умолчанию не
    // выставлен (см. management в application.yml) — включается переменными окружения.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

//    implementation("io.github.wimdeblauwe:htmx-spring-boot-thymeleaf:4.0.1")

    implementation("org.liquibase:liquibase-core")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // Version managed by the Spring Boot BOM so it stays aligned with kotlinx-coroutines-core
    // (a stale standalone pin like 1.6.4 caused version skew against the managed core).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.3.0")
    // kotlin-telegram-bot exposes retrofit2.Response in its public API (return types of Bot.*).
    // The K2 compiler (Kotlin 2.x) requires those types on the compile classpath, while the
    // library declares retrofit as `implementation` (runtime-only). Pin it to the version the
    // bot resolves transitively (see runtimeClasspath) so nothing changes at runtime.
    implementation("com.squareup.retrofit2:retrofit:2.9.0")

    implementation("org.apache.commons:commons-csv:1.14.1")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv")

    implementation("org.jsoup:jsoup:1.21.1")

    // Bounded in-memory cache for the OG image proxy (weight = bytes, not entries).
    // Version comes from the Spring Boot BOM.
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.37")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")

    implementation("com.microsoft.playwright:playwright:1.52.0")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    implementation("org.apache.commons:commons-compress:1.28.0")

    // TestContainers 2.x renamed the module artifacts with a `testcontainers-` prefix
    // (the old `postgresql`/`junit-jupiter` coordinates are frozen at 1.21.4). Versions are
    // managed by testcontainers-bom:2.0.5 (see dependencyManagement) to keep the line consistent.
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")

    testImplementation("org.wiremock:wiremock-standalone:3.13.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
        // Override Spring Boot's managed Testcontainers version with the latest 2.x line.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}