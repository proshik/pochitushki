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

Базовый образ в `Dockerfile` и `java-version` в `.github/workflows/build.yml`
должны совпадать с toolchain из `build.gradle.kts` (сейчас 25): toolchain-репозиторий
для авто-скачивания JDK не настроен, поэтому при расхождении сборка падает с
`Cannot find a Java installation ... matching {languageVersion=25}`.

### Required env vars

| Переменная | Назначение |
|------------|-----------|
| `TELEGRAM_CLIENT_ID` / `TELEGRAM_CLIENT_SECRET` | Telegram OAuth-приложение |
| `APP_BASE_URL` | Базовый URL для redirect-uri OAuth-колбэка |
| `JWT_SECRET` | Подпись JWT (≥32 байт base64). Дефолта нет — без переменной приложение падает на старте (осознанный fail-fast) |
| `TELEGRAM_TOKEN` | Токен бота. Обязателен, пока `TELEGRAM_ENABLED` не выставлен в `false`: `TelegramController` объявляет `@PostMapping("/\${telegram.token}")`, и плейсхолдер должен разрешиться на старте |
| `TELEGRAM_ENABLED` | Опц. Запуск бота (polling/webhook). `false` — стартовать без бота, bot-токен не нужен (логин через Telegram работает независимо). По умолчанию `true` |
| `TELEGRAM_WEBHOOK_SECRET` | Опц. Сверяется с заголовком `X-Telegram-Bot-Api-Secret-Token`. Пусто => проверка выключена; в проде задавать |
| `app.security.ssrf.allowed-hosts` | Опц. Список хостов, исключённых из SSRF-проверок `UrlSecurityValidator` (в тестах — `localhost`) |

## Architecture

**Слои**: Controller → Service → Repository (DAO) → PostgreSQL
**БД**: PostgreSQL 16, Spring JDBC + NamedParameterJdbcTemplate (без ORM), миграции Liquibase
**Telegram**: поддержка polling и webhook, через kotlin-telegram-bot;
бот включается флагом `telegram.enabled` (см. `TelegramBotConfiguration`) — независим от входа через Telegram
**Web**: Thymeleaf-страницы (`feed/all/archive/favorites/profile/login`) + HTML-фрагменты;
htmx и шрифты лежат в `static/`, внешних CDN нет
**Auth**: Telegram OIDC/OAuth + JWT в cookie `auth_token`; `JwtAuthInterceptor`
кладёт `userId` в request-атрибут и защищает `/`, `/all`, `/archive`,
`/favorites`, `/profile`, `/api/v1/**` (см. `WebConfig`)

### Key packages

```
ru.proshik.pochitushki/
├── controller/          # WebController, AuthController, PostApiController,
│                        #   ProfileApiController, TelegramController (webhook)
├── service/             # Бизнес-логика (Post/User/Jwt/TelegramOidc/Export/Import/I18n),
│                        #   TelegramService — оркестратор бота (самый крупный класс),
│                        #   UrlSecurityValidator (SSRF), PocketCsv, Pdf-генераторы
├── service/telegram/    # CommandHandler, CallbackQueryHandler, TelegramKeyboard,
│                        #   TelegramUpdateHandler (интерфейс обработчика)
├── configuration/       # TelegramBotConfiguration, BotProvider, JwtAuthInterceptor,
│                        #   WebConfig, PdfConfiguration, MessageConfiguration,
│                        #   SecurityHeadersFilter
├── configuration/properties/  # Типизированные @ConfigurationProperties
│                        #   (TelegramProperties, TelegramOAuthProperties, JwtProperties)
├── model/               # Data-классы (PostData, UserData, PostType, ...)
└── repository/          # DAO (UserDao, PostDao, UserToPostDao)
```

Помимо этого файла в `.claude/rules/` лежат path-scoped правила, подключаемые по
маске файлов: `database.md` (DAO и миграции), `security.md` (auth/JWT/interceptor),
`telegram.md` (бот).

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

Экспорт/импорт (Pocket CSV) по HTTP **не выставлен** — доступен только через бота
(`TelegramService` инжектит `ExportService`/`ImportService`).

Webhook висит на `POST /${telegram.token}`, то есть путь эндпоинта — это сам токен
бота. Поэтому `TelegramController` помечен `@ConditionalOnProperty(telegram.enabled=true)`:
иначе при пустом токене маппинг схлопнулся бы в `POST /`.

### Frontend и CSP (важно при правке шаблонов)

`SecurityHeadersFilter` отдаёт `script-src 'self'` без `'unsafe-inline'` и `'unsafe-eval'`.
Три правила, которые это поддерживают — нарушение любого тихо ломает страницу
в браузере, но **не** ломает тесты:

1. Никаких инлайновых `<script>` и атрибутов `on*=""`. Вешать `data-*`-хук и
   обрабатывать его в делегированных слушателях в `static/js/app.js`
   (там уже есть `data-action`, `data-view-mode`, `data-fav-btn`,
   `data-reload-on-success`, `data-remove-on-success`).
2. Никаких htmx `hx-on--*`, `hx-vals='js:…'` и фильтров событий в `hx-trigger`
   — htmx исполняет их через `new Function()`. Вместо них — слушатель
   `htmx:afterRequest` в `app.js`. `htmx.config.allowEval = false` выставлен
   там же, чтобы такое падало заметно.
3. Ассеты только свои: `static/js/` (htmx 2.0.4, json-enc, app.js, theme-init.js),
   `static/css/fonts.css` + `static/fonts/*.woff2`.

Инлайновые `style=""` разрешены (`style-src` держит `'unsafe-inline'`) — их в
шаблонах около сотни, а инъекция стилей несопоставимо менее опасна.
`img-src` открыт для любого https: фавиконки берутся с google.com, OG-картинки —
напрямую с сайтов статей.

Шрифты самохостятся подмножествами (latin, latin-ext, cyrillic, cyrillic-ext —
кириллица обязательна). Обновлять: скачать CSS с fonts.googleapis.com с
браузерным User-Agent, вытащить URL'ы woff2, положить в `static/fonts/`
(вес и подмножество — в имени файла) и переписать `src:` в `fonts.css`.

### PDF generation

Две реализации `PdfGenerator`. `OpenhtmlPdfGenerator` регистрируется всегда и
является дефолтом (`pdf.playwright.enabled: false` в `application.yml`);
`PlaywrightPdfGenerator` добавляется только при `pdf.playwright.enabled=true`.
В Docker-образе Chromium по умолчанию не ставится — нужен
`docker build --build-arg INSTALL_PLAYWRIGHT=true`.

Выбор движка — **рантаймовый, а не конфигурационный**: `TelegramService` инжектит
`Map<String, PdfGenerator>` и ищет бин по имени `"${engine}PdfGenerator"`, а движок
выбирает пользователь inline-кнопкой (когда доступен больше одного). Отсюда
следует, что **имена бинов в `PdfConfiguration` нельзя менять свободно** — они
часть контракта с `getAvailablePdfEngines()`/`sendPostPdf()`.

## Code Conventions

- Kotlin 2.4, Spring Boot 3.5.16, Java 25
- Стиль: официальный Kotlin style guide
- Нет ORM — только `NamedParameterJdbcTemplate` с ручным SQL
- Интернационализация через `I18nService` (EN/RU)
- Для новых HTTP-клиентов использовать Spring Cloud OpenFeign

## Testing

- JUnit 5 + Spring Boot Test + TestContainers (`postgres:16-alpine`)
- Базовый класс: `BaseIntegrationTest` (контейнер + `@DynamicPropertySource`)
- Профиль `test` отключает Telegram-бота (`application-test.yml`)
- WireMock (`wiremock-standalone`) подменяет внешние HTTP: Telegram Bot API
  (`telegram.api-url`) и OAuth-endpoint (`telegram.oauth.base-url`)
- Тесты бота — отдельный профиль `application-telegram-test.yml`
- Тесты запускаются через `./gradlew test`. Если нужен именно повторный прогон,
  использовать `./gradlew cleanTest test` — иначе Gradle отдаёт `Task :test UP-TO-DATE`
  и `BUILD SUCCESSFUL`, ничего не выполнив
- TestContainers ищет docker-сокет по дефолтному пути и **не читает контексты Docker CLI**.
  На Colima/Podman/нестандартном сокете тесты падают сразу (~144 из 157) с
  `ExceptionInInitializerError at Unsafe.java` — это маскирует настоящую причину
  (`Could not find a valid Docker environment`), на JDK тут пенять не нужно. Лечится
  явным сокетом, напр.: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew test`

## Key Files

| Файл | Назначение |
|------|-----------|
| `build.gradle.kts` | Зависимости и плагины |
| `src/main/resources/application.yml` | Конфигурация приложения |
| `src/main/resources/liquibase/` | Миграции БД |
| `service/telegram/CommandHandler.kt` | Обработчик Telegram-команд |
| `service/telegram/CallbackQueryHandler.kt` | Обработчик inline-кнопок |
