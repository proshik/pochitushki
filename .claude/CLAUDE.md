# Pochitushki — Project Instructions

## Overview

Read-it-later сервис на Kotlin + Spring Boot. Пользователи сохраняют ссылки
через Telegram-бота и через веб-интерфейс. Реализованы: веб-UI на Thymeleaf,
аутентификация через Telegram OIDC/OAuth (PKCE) + JWT, экспорт/импорт постов
(Pocket CSV), избранное и генерация PDF из ссылок.

## Build & Run

```bash
./gradlew build          # Сборка
./gradlew test           # Тесты (поднимает PostgreSQL через TestContainers)
./gradlew bootJar        # Собрать JAR
./gradlew bootRun        # Запустить локально (нужен PostgreSQL на localhost:5432)
```

В репозитории есть только `Dockerfile` (compose-файла нет). PostgreSQL для
локального запуска поднимается отдельно.

### Required env vars

| Переменная | Назначение |
|------------|-----------|
| `TELEGRAM_CLIENT_ID` / `TELEGRAM_CLIENT_SECRET` | Telegram OAuth-приложение |
| `APP_BASE_URL` | Базовый URL для redirect-uri OAuth-колбэка |
| `JWT_SECRET` | Подпись JWT (≥32 байт base64; по умолчанию dev-заглушка) |
| `TELEGRAM_ENABLED` | Опц. Запуск бота (polling/webhook). `false` — стартовать без бота, bot-токен не нужен (логин через Telegram работает независимо). По умолчанию `true` |

## Architecture

**Слои**: Controller → Service → Repository (DAO) → PostgreSQL
**БД**: PostgreSQL 16, Spring JDBC + NamedParameterJdbcTemplate (без ORM), миграции Liquibase
**Telegram**: поддержка polling и webhook, через kotlin-telegram-bot;
бот включается флагом `telegram.enabled` (см. `TelegramBotConfiguration`) — независим от входа через Telegram
**Web**: Thymeleaf-страницы (`feed/all/archive/favorites/profile/login`) + HTML-фрагменты
**Auth**: Telegram OIDC/OAuth + JWT в cookie `auth_token`; `JwtAuthInterceptor`
кладёт `userId` в request-атрибут и защищает `/`, `/all`, `/archive`,
`/favorites`, `/profile`, `/api/v1/**` (см. `WebConfig`)

### Key packages

```
ru.proshik.pochitushki/
├── controller/          # WebController, AuthController, PostApiController,
│                        #   ProfileApiController, TelegramController (webhook)
├── service/             # Бизнес-логика (Post/User/Jwt/TelegramOidc/Export/Import/Pdf)
├── service/telegram/    # Обработчики команд и callback-кнопок бота
├── configuration/       # Spring-конфиги: бот, JwtAuthInterceptor, WebConfig, Pdf
├── model/               # Data-классы (PostData, UserData, PostType, ...)
└── repository/          # DAO (UserDao, PostDao, UserToPostDao)
```

### Post storage

- Таблица `post` — непрочитанные ссылки, `archive_post` — архив (та же структура)
- Флаг `is_favorite` в обеих таблицах (миграция `3_add_favorites`)
- JSONB-колонка `settings` в таблице `users` (`languageCode`, `tgFeedEntriesNumber`)
- `PostType`: `UNREAD` / `ARCHIVE` / `FAVORITES` / `ALL`
- Перенос поста между unread/archive — через `@Transactional` (insert + delete)

### REST API (`/api/v1`, cookie-JWT)

- `POST /posts`, `GET /posts/fragment`, `POST /posts/{id}/archive|unread|favorite`,
  `DELETE /posts/{id}`, `GET /posts/{id}/og-image`
- `POST /profile/settings`

### PDF generation

Две реализации интерфейса `PdfGenerator`: `PlaywrightPdfGenerator` (по умолчанию,
`pdf.playwright.enabled=true`) и `OpenhtmlPdfGenerator`. Выбор в `PdfConfiguration`.

## Code Conventions

- Kotlin 2.4, Spring Boot 3.5.16, Java 25
- Стиль: официальный Kotlin style guide
- Нет ORM — только `NamedParameterJdbcTemplate` с ручным SQL
- Интернационализация через `I18nService` (EN/RU)
- Для новых HTTP-клиентов использовать Spring Cloud OpenFeign

## Testing

- JUnit 5 + Spring Boot Test + TestContainers (PostgreSQL 16-alpine)
- База класса: `BaseIntegrationTest`
- Профиль `test` отключает Telegram-бота (`application-test.yml`)
- Тесты запускаются через `./gradlew test`

## Key Files

| Файл | Назначение |
|------|-----------|
| `build.gradle.kts` | Зависимости и плагины |
| `src/main/resources/application.yml` | Конфигурация приложения |
| `src/main/resources/liquibase/` | Миграции БД |
| `service/telegram/CommandHandler.kt` | Обработчик Telegram-команд |
| `service/telegram/CallbackQueryHandler.kt` | Обработчик inline-кнопок |
