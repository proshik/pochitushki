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

`compose.dev.yaml` поднимает только зависимости для разработки (PostgreSQL +
telegram-login-broker); само приложение запускается на хосте. Для прода —
`Dockerfile`, PostgreSQL разворачивается отдельно.

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
| `TELEGRAM_TOKEN` | Токен бота. Обязателен, пока `TELEGRAM_ENABLED` не выставлен в `false`: `TelegramController` объявляет `@PostMapping("/\${telegram.token}")`, и плейсхолдер должен разрешиться на старте. Ключа `telegram.token` в `application.yml` нет — значение приходит relaxed binding'ом прямо в `TelegramProperties` |
| `TELEGRAM_ENABLED` | Опц. Запуск бота (polling/webhook). `false` — стартовать без бота, bot-токен не нужен (логин через Telegram работает независимо). По умолчанию `true` |
| `TELEGRAM_WEBHOOK_SECRET` | Обязателен в режиме webhook (`telegram.webhook-url` задан) — без него приложение не стартует. Сверяется с заголовком `X-Telegram-Bot-Api-Secret-Token` через `MessageDigest.isEqual` |
| `APP_LOG_LEVEL` | Опц. Уровень логов приложения, дефолт `INFO`. На `DEBUG` в лог уезжают URL ссылок и тела апдейтов |
| `app.security.ssrf.allowed-hosts` | Опц. Список хостов, исключённых из SSRF-проверок `UrlSecurityValidator` (в тестах — `localhost`) |

## Architecture

**Слои**: Controller → Service → Repository (DAO) → PostgreSQL
**БД**: PostgreSQL 16, Spring JDBC + NamedParameterJdbcTemplate (без ORM), миграции Liquibase
**Telegram**: поддержка polling и webhook, через kotlin-telegram-bot;
бот включается флагом `telegram.enabled` (см. `TelegramBotConfiguration`) — независим от входа через Telegram
**Web**: Thymeleaf-страницы (`feed/all/archive/favorites/random/profile/login`) + HTML-фрагменты;
дизайн «Обложки»: генеративные обложки постов (`CoverService`), все стили в
`static/css/app.css`, htmx и шрифты в `static/`, внешних CDN нет
**Auth**: Telegram OIDC/OAuth + JWT в cookie `auth_token`; `JwtAuthInterceptor`
кладёт `userId` в request-атрибут и защищает `/`, `/all`, `/archive`,
`/favorites`, `/random`, `/profile`, `/api/v1/**` (см. `WebConfig`). Выход —
`POST /logout`: отзывает токены через `users.tokens_valid_after` (миграция 9),
интерцептор сверяет с ним `iat` токена
**Эксплуатация**: ровно одна реплика (бот + in-memory состояние), probes
`/actuator/health/{liveness,readiness}`, метрики Prometheus — только по
`MANAGEMENT_SERVER_PORT` + `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`,
`requestId` в логах и в `X-Request-Id`, страница ошибки — `templates/error.html`

### Key packages

```
ru.proshik.pochitushki/
├── controller/          # WebController, AuthController, PostApiController,
│                        #   ProfileApiController, LabelApiController,
│                        #   TelegramController (webhook)
├── service/             # Бизнес-логика (Post/User/Label/Jwt/TelegramOidc/Export/Import/I18n),
│                        #   TelegramService — оркестратор бота (самый крупный класс),
│                        #   UrlSecurityValidator (SSRF), OgImageProxyService,
│                        #   RateLimitService (бюджеты на пользователя),
│                        #   PocketCsv, Pdf-генераторы
├── service/telegram/    # CommandHandler, CallbackQueryHandler, TelegramKeyboard,
│                        #   TelegramUpdateHandler (интерфейс обработчика),
│                        #   BotTaskExecutor (пул для PDF/импорта/экспорта)
├── configuration/       # TelegramBotConfiguration, BotProvider, JwtAuthInterceptor,
│                        #   WebConfig, PdfConfiguration, MessageConfiguration,
│                        #   SecurityHeadersFilter, CsrfOriginFilter, RequestIdFilter
├── configuration/properties/  # Типизированные @ConfigurationProperties
│                        #   (TelegramProperties, TelegramOAuthProperties, JwtProperties)
├── model/               # Data-классы (PostData, UserData, PostType, ...)
└── repository/          # DAO (UserDao, PostDao, LabelDao, UserToPostDao)
```

Помимо этого файла в `.claude/rules/` лежат path-scoped правила, подключаемые по
маске файлов: `database.md` (DAO и миграции), `security.md` (auth/JWT/interceptor),
`telegram.md` (бот).

### Post storage

- Таблица `post` — непрочитанные ссылки, `archive_post` — архив (та же структура)
- Флаг `is_favorite` в обеих таблицах (миграция `3_add_favorites`)
- JSONB-колонка `settings` в таблице `users` (`languageCode`, `tgFeedEntriesNumber`,
  `showOgCovers`). Миграции под новые ключи нет и не нужно: Jackson KotlinModule
  подставляет дефолт из конструктора, поэтому старые строки без ключа читаются как
  `showOgCovers = true`. Новый ключ обязан иметь дефолт, иначе чтение старых строк упадёт
- `PostType`: `UNREAD` / `ARCHIVE` / `FAVORITES` / `ALL`. Маппинг из строки
  двоякий: контроллеры ищут по `value` (`"unread"`), а `PostType.from`/`stringToType`
  — по имени enum (`"UNREAD"`); не перепутать при добавлении новых lookup'ов
- Перенос поста между unread/archive — через `@Transactional` (insert + delete)

### Labels (фаза 6)

- Три таблицы (миграция 5): `label(user_id, name, UNIQUE(user_id,name))` и две
  связки — `post_label` и `archive_post_label`. Связок именно две, потому что
  таблиц с постами две: одна связка не смогла бы держать FK на обе, а id у них
  из разных сиквенсов и **routinely совпадают**
- Отсюда же главное правило: ключ в `LabelDao.findLabelsForPosts` — пара
  (таблица, id), а не голый id. Иначе архивный пост получит метки непрочитанного
- `PostData.labels` заполняет **`PostService`**, а не row mapper: один запрос на
  страницу вместо запроса на карточку. Все read-методы `PostService` уже прогоняют
  результат через `LabelService.withLabels` — новый read-метод обязан делать так же
- Перенос unread↔archive копирует связки (`copyLabels`) **до** удаления исходной
  строки: `ON DELETE CASCADE` уносит их вместе с ней
- `LabelService` нормализует имя (trim, срез ведущего `#`, схлопывание пробелов,
  лимит `MAX_NAME_LENGTH`), поэтому «Kotlin», «kotlin » и «#kotlin» — одна метка.
  Повторное создание возвращает существующую (`ON CONFLICT DO UPDATE ... RETURNING`)
- `PostData.tags` (колонка `TEXT[]`) — **legacy**. Метки и теги слиты: миграция 6
  перелила старые `tags[]` в `label`/`post_label`, импорт пишет метки, экспорт отдаёт
  метки, шаблоны `tags` больше не рендерят. Колонка намеренно оставлена и хранит
  исторические значения — чтобы откат был `git revert`, а не восстановлением из бэкапа.
  Ничего в неё больше не пишет; новый код читать её не должен

### REST API (`/api/v1`, cookie-JWT)

- `POST /posts`, `GET /posts/fragment`, `GET /posts/random-fragment`,
  `POST /posts/{id}/archive|unread|favorite`, `DELETE /posts/{id}`,
  `GET /posts/{id}/cover-image`, `GET /posts/{id}/og-image`
- `POST /posts/{id}/archive` отдаёт `{"archivedId": N}` — id новой строки в
  `archive_post`. htmx его игнорирует (`hx-swap="delete"`), читает только `app.js`
  ради тоста «Вернуть»; не менять на пустой ответ
- `GET/POST /labels`, `DELETE /labels/{id}`
- `POST/DELETE /posts/{id}/labels/{labelId}` (`?type=unread|archive`)
- `GET /posts?labelId=N&offset=M` — карточки по метке; отдаёт HTML-фрагмент, но
  **без сентинела** (`hasMore=false`): сентинел в `post-list` всегда ведёт на
  `/fragment?type=…` и дотянул бы посты без метки. Листать — явным `offset`
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
   `data-unfav-remove`, `data-reload-on-success`, `data-remove-on-success`).
2. Никаких htmx `hx-on--*`, `hx-vals='js:…'` и фильтров событий в `hx-trigger`
   — htmx исполняет их через `new Function()`. Вместо них — слушатель
   `htmx:afterRequest` в `app.js`. `htmx.config.allowEval = false` выставлен
   там же, чтобы такое падало заметно.
3. Ассеты только свои: `static/js/` (htmx 2.0.4, json-enc, app.js, theme-init.js),
   `static/css/app.css` (вся дизайн-система, инлайновых стилей в шаблонах
   почти нет) + `fonts.css` + `static/fonts/*.woff2`.
   `img-src 'self'` — единственный источник картинок теперь сам сервис:
   OG-обложки идут через `OgImageProxyService` (`GET /api/v1/posts/{id}/cover-image`),
   а не хотлинком. Не возвращать `https:` в `img-src`, не сняв прокси.

Шрифты — Onest (400/600/800) и JetBrains Mono (400/600), самохостятся
подмножествами (latin, latin-ext, cyrillic, cyrillic-ext — кириллица
обязательна). Обновлять: скачать CSS с fonts.googleapis.com с браузерным
User-Agent, вытащить URL'ы woff2, положить в `static/fonts/` и переписать
`fonts.css`.

### Обложки (дизайн «Обложки», фаза 4.7)

- `CoverService.decorate(post)` → `CoverView(palette, comp, abbrev, domain)`.
  Всё детерминировано: `palette` = `cv-0..cv-11` по hash(домена) — один сайт
  всегда одного цвета; `comp` = `co-mono|co-band|co-frame` по hash(заголовка),
  либо `co-og`, если у поста есть `og_image_url` (фото внутри «суперобложки»
  цвета домена). Цветовые пары `cv-*` заданы только в `app.css` — менять там
- og:image добывается в `PostService.loadPageMeta` тем же Jsoup-запросом, что
  и title (только абсолютные https, ≤2000 символов), хранится в колонке
  `og_image_url` (миграция 4); импорт og не заполняет
- Отдаётся og-картинка только через `OgImageProxyService`: Caffeine-кэш, ограниченный
  суммой байт (48 МБ), TTL 7 дней, аллоулист content-type'ов **без `image/svg+xml`**
  (SVG с нашего origin — это документ со скриптами, а не картинка). Владение постом
  проверяется через `getPost(userId)`, поэтому эндпоинт не превращается в открытый
  SSRF-фетчер; сам URL картинки всё равно прогоняется через `UrlSecurityValidator`
- Тумблер «фото-обложки» в профиле = `settings.showOgCovers`. Флаг доезжает до
  контроллеров request-атрибутом `JwtAuthInterceptor.SHOW_OG_COVERS` — интерцептор
  и так читает пользователя ради локали, второй запрос на карточку не нужен.
  `CoverService.decorate(post, showOgCovers)` при `false` не выбирает `co-og`
- Фрагменты: `post-card :: card(cv, type, viewMode, pageType)`,
  `post-card :: cover(cv)` (переиспользуется hero'м), `post-list :: posts(covers,
  pageType, offset, hasMore, viewMode)` — контроллеры кладут в модель ровно эти
  имена (`cv`/`covers`, `type`, `viewMode`, `pageType`)
- Режимы: `viewMode` = `shelf`|`list`; cookie `pochitushki-view` хранит
  legacy-значения `list`|`grid` (`grid` == shelf). Дефолты без cookie:
  feed/favorites → shelf, all/archive → list (`WebController.resolveViewMode`).
  Переключение = установка cookie + `location.reload()` — сервер рендерит
  только одну разметку на режим
- Hero «следующая к чтению» на `/` — `PostDao.getOldestPost` (самый старый
  unread); перенос unread↔archive сохраняет `created_date`, иначе hero врёт
- `/random` — тасовка `PostApiController.RANDOM_SIZE` (8) непрочитанных
  (`PostDao.getRandomPosts`, `ORDER BY RANDOM()`). Кнопка «Ещё 8» бьёт в
  `GET /api/v1/posts/random-fragment` и **заменяет** список (`hx-swap="innerHTML"`),
  поэтому фрагмент всегда отдаётся с `hasMore=false` — сентинел бесконечной
  прокрутки на этой странице появиться не должен

### PDF generation

Две реализации `PdfGenerator`. `OpenhtmlPdfGenerator` регистрируется всегда и
является дефолтом (`pdf.playwright.enabled: false` в `application.yml`);
`PlaywrightPdfGenerator` добавляется только при `pdf.playwright.enabled=true`.
В Docker-образе Chromium по умолчанию не ставится — нужен
`docker build --build-arg INSTALL_PLAYWRIGHT=true`.

Сгенерированное кэшируется: `PdfCacheService` + таблица `pdf_cache` (миграция 7),
TTL 30 дней. Ключ — **(user_id, sha256(url), engine)**, а не post_id, как набрасывал
план: id у `post` и `archive_post` из разных сиквенсов, при переносе поста id меняется,
и кэш бы терялся на ровном месте. PDF — это рендер URL движком, им и ключуем;
`user_id` в ключе, чтобы чужой рендер не отдавался. Протухшие строки чистятся при
записи, отдельного шедулера нет.

Выбор движка — **рантаймовый, а не конфигурационный**: `TelegramService` инжектит
`Map<String, PdfGenerator>` и ищет бин по имени `"${engine}PdfGenerator"`, а движок
выбирает пользователь inline-кнопкой (когда доступен больше одного). Отсюда
следует, что **имена бинов в `PdfConfiguration` нельзя менять свободно** — они
часть контракта с `getAvailablePdfEngines()`/`sendPostPdf()`.

## Code Conventions

- Kotlin 2.4, Spring Boot 4.0.8 (Spring Cloud 2025.1.3 — он и держит Boot на 4.0.x: под 4.1 релиза Cloud пока нет), Jackson 3 (`tools.jackson.*`; аннотации остаются в `com.fasterxml.jackson.annotation`), Java 25
- Boot 4 разнёс автоконфигурацию по модулям, и промахи тут **тихие**: голый `liquibase-core`
  без `spring-boot-starter-liquibase` не накатывает миграции, а переименованные ключи
  (`server.error.*` → `spring.web.error.*`) просто игнорируются. После смены версии Boot
  запускать приложение с `spring-boot-properties-migrator` в `runtimeOnly` и читать его отчёт
  в логе старта; в репозиторий migrator не коммитить
- Стиль: официальный Kotlin style guide
- Нет ORM — только `NamedParameterJdbcTemplate` с ручным SQL
- Интернационализация через `I18nService` (EN/RU); бандлы лежат в
  `src/main/resources/messages/` (подкаталог, не корень resources)
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
  На Colima/Podman/нестандартном сокете тесты падают сразу (~146 из 164; без
  Docker проходят только юнит-тесты `JwtServiceTest` и `UrlSecurityValidatorTest`) с
  `ExceptionInInitializerError at Unsafe.java` — это маскирует настоящую причину
  (`Could not find a valid Docker environment`), на JDK тут пенять не нужно. Лечится
  явным сокетом **плюс** override для ryuk (иначе он не сможет смонтировать сокет):
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test`

## Key Files

| Файл | Назначение |
|------|-----------|
| `build.gradle.kts` | Зависимости и плагины |
| `src/main/resources/application.yml` | Конфигурация приложения |
| `src/main/resources/liquibase/` | Миграции БД |
| `service/telegram/CommandHandler.kt` | Обработчик Telegram-команд |
| `service/telegram/CallbackQueryHandler.kt` | Обработчик inline-кнопок |
