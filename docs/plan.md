# Pochitushki — Roadmap доработок

## Статус фаз

| Фаза | Название | Статус |
|------|----------|--------|
| 1 | Тесты (актуализация + расширение) | ✅ Готово |
| 2 | Favorites | ✅ Готово |
| 3 | PDF | ✅ Готово |
| 3.5 | Bot UX улучшения + ImportServiceTest | ✅ Готово |
| 3.7 | Export & Import доработки | ✅ Готово |
| 4 | Web UI Foundation (без auth) | ✅ Готово |
| 5 | Auth + защита Web | ✅ Готово |
| 5.5 | Security hardening (вне плана) | ✅ Готово |
| 4.5 | Mobile UI | ✅ Закрыта фазой 4.7 |
| 4.7 | Web Redesign «Обложки» | ✅ Реализовано (2026-08-01) |
| 4.6 | Random | ✅ Готово |
| 6 | Labels | ✅ Бэкенд готов, веб-UI не входил в объём |
| 7 | Chrome Extension | 🔮 Будущее |
| 8 | iOS App | 🔮 Будущее |

---

## Фаза 1 — Тесты

**Цель**: настоящие тесты с поднятием Spring-контекста, покрытие текущего функционала.

### Что сделать
- [x] `BaseIntegrationTest` — базовый класс с TestContainers + WireMock
- [x] `TelegramBotIntegrationTest` — реальный тест webhook endpoint (`/start`, добавление поста по URL)
- [x] `TelegramControllerTest` — 2 теста (webhook 200, невалидный JSON)
- Интеграционные тесты (extends `BaseIntegrationTest`):
  - [x] `PostServiceTest` — 18 тестов
  - [x] `UserServiceTest` — 8 тестов
  - [x] `PostDaoTest` — 19 тестов
- Unit-тесты (без Spring):
  - [x] `I18nServiceTest` — 4 теста

### Ключевые файлы
- `src/test/kotlin/ru/proshik/pochitushki/BaseIntegrationTest.kt` — переиспользовать
- `src/test/resources/application-test.yml` — уже есть

---

## Фаза 2 — Favorites

### DB Migration (`3_add_favorites.sql`)
```sql
ALTER TABLE post ADD COLUMN is_favorite BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE archive_post ADD COLUMN is_favorite BOOLEAN NOT NULL DEFAULT false;
```

### Изменения
- [x] `PostData` — добавить поле `isFavorite: Boolean`
- [x] `PostDao` — метод `toggleFavorite(postId, postType)`, фильтр `is_favorite = true` в getPosts/getPostCount, сохранение is_favorite при archive/unread
- [x] `PostService` — метод `toggleFavorite(postId, postType)`, `getPost(postId, postType)`
- [x] Telegram: callback `CALLBACK_TOGGLE_UNREAD/ARCHIVE/RANDOM_FAVORITE`, иконка ⭐, команда `/favorites`, PostType.FAVORITES

### Тесты
- [x] `PostServiceTest` — toggleFavorite, фильтрация, archivePost/unreadPost preserves is_favorite
- [x] `PostDaoTest` — toggleFavorite, фильтр, archive/unread preserves is_favorite

---

## Фаза 3 — PDF

### Библиотека
- [x] **openhtmltopdf** (`io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.37`)
- Если качество плохое — переключиться на **Playwright Java**

### Новые файлы
- [x] `service/PdfGenerator.kt` — интерфейс `generatePdf(url): ByteArray`
  (планировался как единый `PdfService.kt`; разделён на интерфейс + реализации)
- [x] `service/OpenhtmlPdfGenerator.kt` — дефолтная реализация (Jsoup → Cleaner → XHTML → openhtmltopdf)
- [x] `service/PlaywrightPdfGenerator.kt` — включается флагом `pdf.playwright.enabled=true`;
  движок выбирает пользователь inline-кнопкой, если доступен больше одного
- [ ] Опционально: кеш `post_pdf(post_id, content BYTEA, created_at)`

### Telegram
- [x] Кнопка "📄 PDF" на карточке поста (в одном ряду с ⭐ Favorite) → `sendDocument(chatId, TelegramFile.ByByteArray, filename)`
- [x] Callbacks: `CALLBACK_PDF_UNREAD_POST`, `CALLBACK_PDF_ARCHIVE_POST`

### Тесты
- [x] `PdfServiceTest` — генерация PDF из HTML, обработка скриптов/стилей, unicode, ошибки

---

## Фаза 3.5 — Bot UX улучшения

### Задача 1 — Кнопки в /favorites не должны убирать пост из списка

**Проблема**: в `/favorites` при нажатии "В Архив" или "Непрочитанное" пост исчезает из списка
(такое же поведение, как в `/feed` и `/archive`).

**Ожидаемое поведение**:
- `/feed`, `/archive` — пост убирается из списка при смене раздела (текущее поведение корректно)
- `/favorites` — пост остаётся в списке, кнопки просто меняют состояние (обновляется inline-клавиатура), чтобы отражать, где сейчас находится пост (в архиве или непрочитанном)

**Изменения**:
- [x] В `CallbackQueryHandler` — при обработке `CALLBACK_TOGGLE_UNREAD/ARCHIVE` проверять, откуда пришёл callback (context PostType)
- [x] Если `postType == FAVORITES` — после переноса не редактировать/удалять сообщение, а обновить inline-кнопки карточки через `editMessageReplyMarkup`
- [x] Тест в `TelegramBotIntegrationTest` — проверить, что в контексте favorites карточка не исчезает

### Задача 2 — Дата добавления под ссылкой в /feed и /archive

**Требование**: под ссылкой на пост (не жирным) выводить дату добавления.

**Telegram Markdown**:
Использовать **MarkdownV2** (не legacy `Markdown`): поддерживает bold, italic, escape спецсимволов через `\`.
- Ссылка: `[title](url)` — кликабельный текст
- Дата: обычный текст (не жирный), формат `dd.MM.yyyy`
- Пример карточки:
  ```
  [Заголовок поста](https://example.com)
  05\.04\.2026
  ```
  Точки в дате нужно экранировать (`\.`) в MarkdownV2.

**Изменения**:
- [x] В `CommandHandler` (или хелпере форматирования карточки) — добавить строку с датой под ссылкой
- [x] Убедиться, что `parseMode = ParseMode.MARKDOWN_V2` выставлен для этих сообщений
- [x] Экранировать спецсимволы в дате через утилиту (`.` → `\.`)
- [x] Тест — проверить формат карточки с датой

### Задача 3 — ImportServiceTest (перенесено из фазы 1)

`ImportService.importZipArchive()` уже реализован и работает (принимает ZIP от Telegram,
разбирает Pocket CSV, сохраняет в БД). Нужно только покрыть тестами существующий код:

- [x] `ImportServiceTest` — тест существующего `ImportService.importZipArchive()`
  - Тест успешного импорта ZIP с валидным Pocket CSV внутри
  - Тест партиционирования по статусу (unread / archive)
  - Тест с пустым ZIP-архивом
  - Тест сохранения тегов
  - Тестовый fixture-файл `src/test/resources/pocket_export_sample.zip`

---

## Фаза 3.7 — Export & Import доработки

### Аудит текущего состояния

| Компонент | Файл | Статус | Проблема |
|-----------|------|--------|---------|
| ExportService | `service/ExportService.kt` | ⚠️ Partial | Создаёт CSV в `/tmp`, возвращает UUID — файл нигде не отправляется |
| TelegramService.export() | `service/TelegramService.kt` | ❌ Broken | Вызывает `exportService.export()` но не шлёт файл пользователю |
| CommandHandler | `service/telegram/CommandHandler.kt` | ⚠️ Partial | `handleImportButton()` закомментирован |
| Формат тегов | ExportService / PocketCsv | ⚠️ Mismatch | Export: `\|`-separator, Import: `,`-separator — несовместимо при re-import |
| is_favorite | ExportService | ❌ Missing | Поле `is_favorite` не попадает в экспорт |

### Задача 1 — Починить экспорт в Telegram

- [x] `TelegramService.export()`: после генерации ZIP отправлять файл через `sendDocument(chatId, TelegramFile.ByByteArray(...))`
- [x] Кнопки Import/Export в `/profile` через `CallbackQueryHandler`
- [x] Удалять временный файл из `/tmp` после отправки (finally-блок)

### Задача 2 — Унифицировать формат тегов

- [x] Сменить разделитель в `ExportService` с `|` на `,` (как в Pocket-формате)
- [x] При re-import экспортированного ZIP теги корректно читаются

### Задача 3 — Включить is_favorite в экспорт

- [x] В CSV добавить колонку `is_favorite` (true/false)
- [x] При импорте читать поле `is_favorite` если есть (опционально, для обратной совместимости с Pocket)

### Задача 4 — Тесты

- [x] `ExportServiceTest` — 6 тестов: null, ZIP, заголовки, поля, round-trip, CSV formula injection
- [x] `ImportServiceTest` — расширен до 9 тестов, включая `is_favorite` на unread и archive,
      лимит записей CSV и пропуск строк с пустым url

---

## Фаза 4 — Web UI Foundation (без аутентификации)

**Логика**: сначала строим рабочий минимальный UI и убеждаемся, что посты корректно
отображаются и загружаются. Auth добавляем следующим шагом (фаза 5).

Для dev-режима: `userId` берётся из cookie или query-param `?userId=<telegram_id>`.

### Стек
- **Thymeleaf** — шаблоны на стороне сервера
- **HTMX** (CDN) — динамические обновления без JS-фреймворка и без сборки
- **Свой CSS** в `<style>` внутри `layout.html` — семантические классы плюс
  CSS-переменные для палитры и тёмной темы, без сборки

> Изначально сюда закладывался Tailwind CSS через CDN, но фактически утилиты
> использовались только на странице входа, а всё остальное оформление сразу писалось
> своими классами. Play CDN при этом компилировал стили в браузере на каждой загрузке
> и не годится для прода, поэтому Tailwind убран. Нужный минимум из его Preflight
> (`box-sizing`, обнуление отступов у `body`/`h*`/`p`, `display:block` для картинок)
> перенесён в блок «Reset» в `layout.html` и `login.html`.

### Эстетика

Сервис для **чтения** — контент важнее оболочки.  
Направление: **editorial / библиотечный минимализм** с тёплым характером.

- **Типографика**: `Playfair Display` (заголовки, логотип) + `Source Serif 4` (body)
- **Цвет**: фон `#F5F0E8` (тёплый пергамент), текст `#1A1714` (почти чёрный), акцент `#C0622A` (терракота)
- **Карточки постов**: typographic cards, без теней; hover — тонкий левый border-accent
- **Навигация**: боковая фиксированная, имя пользователя сверху
- **Анимации**: subtle fade-in при загрузке, staggered delays через HTMX swap
- **Нет**: градиентов, glassmorphism, лишних эмодзи в интерфейсе

### Страницы

- [x] `/` — лента непрочитанных (шаблон `feed.html`, пагинация)
- [x] `/all` — все ссылки (`all.html`)
- [x] `/archive` — архивные посты
- [x] `/favorites` — избранное
- [x] `/profile` — статистика пользователя

### REST API

```
GET    /api/v1/posts/fragment?type=unread|archive|favorites&offset=
POST   /api/v1/posts
POST   /api/v1/posts/{id}/archive
POST   /api/v1/posts/{id}/unread
POST   /api/v1/posts/{id}/favorite
DELETE /api/v1/posts/{id}
```

> Блок выше — снапшот фазы 4. Позже добавлены `GET /api/v1/posts/{id}/og-image`
> и `POST /api/v1/profile/settings`; актуальный список ведётся в `.claude/CLAUDE.md`.

### Новые файлы

- [x] `controller/WebController.kt` — страницы `/` (лента), `/all`, `/archive`, `/favorites`, `/profile`
- [x] `controller/PostApiController.kt` — REST API для постов
- [x] `configuration/WebConfig.kt` — регистрация интерцептора. Dev-вариант
      (`DevUserInterceptor` + `configuration/properties/WebProperties.kt` с `web.dev-user-id`)
      был временным решением фазы 4 и удалён в фазе 5 вместе с приходом реальной auth
- [x] `templates/layout.html` — базовый layout (nav + content area)
- [x] `templates/feed.html`, `templates/archive.html`, `templates/favorites.html`, `templates/profile.html`
- [x] `templates/fragments/post-card.html`, `post-list.html`, `add-post-form.html`

---

## Фаза 4.5 — Mobile UI

> ✅ Закрыта фазой 4.7: мобильная адаптация сделана внутри редизайна «Обложки»
> (нижние вкладки, полка 2 колонки, компактный hero). План ниже оставлен для истории
> и описывает СТАРУЮ вёрстку с сайдбаром.

**Цель**: адаптировать веб-интерфейс для мобильных телефонов. Текущий UI — desktop-only:
sticky-сайдбар 248px (иконочный rail 48px + текстовый nav 200px внутри flex `<nav>`-фрагмента)
занимает 66% экрана телефона (375px), нет ни одного media query.

**Breakpoint**: `768px` — ниже этого значения включается мобильный layout.

> ⚠️ Если стартует фаза 4.7 (Web Redesign), мобильную адаптацию делать внутри неё,
> а не отдельным слоем поверх текущей вёрстки — иначе работа выбросится.

### Подход: скрыть сайдбар → нижний tab bar

На мобильном:
- Скрыть оба столбца `<nav>` (rail + текстовый sidebar) — `display: none`
- Добавить `padding-bottom: 56px` для нижнего таббара (`padding-left` у `body` нет —
  сайдбар лежит в общем flex-контейнере, а не позиционируется отступом)
- Показать **фиксированный нижний nav bar** (56px): Все / Непрочитанные / Архив / Избранное / Профиль
- Добавить **тонкий top bar** (48px) с названием приложения и кнопкой смены темы

### Изменения

#### `templates/layout.html`
- [ ] CSS: добавить `@media (max-width: 767px)` блок:
  - `body { padding-bottom: 56px; }`
  - `nav { display: none; }` (или класс `desktop-nav` на `<nav>`) — скрыть сайдбар
  - `.mobile-nav { display: flex; }` — показать нижний таббар
  - `.mobile-top-bar { display: flex; }` — показать верхний бар
  - `main` padding: `1rem 0.75rem` вместо `2.5rem`
  - Grid: `minmax(265px, 1fr)` → `minmax(155px, 1fr)` (2 карточки в ряд)
  - `.profile-grid { grid-template-columns: 1fr; }` — 1 колонка
- [ ] Nav fragment: добавить класс `desktop-nav` к `<nav>`
- [ ] Nav fragment: добавить `.mobile-top-bar` div (лого + кнопка темы)
- [ ] Nav fragment: добавить `.mobile-nav` с 5 пунктами (иконка + подпись, active-state)
- [ ] JS `toggleTheme()`: использовать `querySelectorAll('#theme-icon-rail')` вместо `getElementById`

#### `templates/feed.html`, `all.html`, `archive.html`, `favorites.html`
- [ ] Добавить класс `page-main` к `<main>` (для mobile CSS-таргетинга padding)

#### `templates/profile.html`
- [ ] Заменить inline `grid-template-columns: 1fr 1fr` классом `profile-grid`

### Файлы
- `src/main/resources/templates/layout.html` — основная работа (CSS + nav fragment)
- `src/main/resources/templates/feed.html`, `all.html`, `archive.html`, `favorites.html`
- `src/main/resources/templates/profile.html`
- `src/main/resources/templates/fragments/post-card.html` — изменений нет

### Проверка
1. `./gradlew bootRun` (compose-файла в репозитории нет, PostgreSQL поднимается отдельно — см. README)
2. Chrome DevTools → Device toolbar → iPhone SE (375×667), Pixel 7 (412×915)
3. Убедиться: нижний таббар отображается, навигация работает, active-state корректен
4. Десктоп (>767px): сайдбар без изменений, регрессий нет

---

## Фаза 4.6 — Random

**Статус**: реализовано. Страница `/random` показывает 8 случайных непрочитанных
постов с кнопкой «Ещё 8».

### Backend

- [x] `PostDao.getRandomPosts(userId, count): List<PostData>` → `ORDER BY RANDOM() LIMIT :count`
- [x] `PostService.getRandomPosts(userId, count)` — делегирующий метод
- [x] `PostApiController` — `GET /api/v1/posts/random-fragment`
- [x] `WebController` — маршрут `/random` → `random.html`, первая порция рендерится сервером
- [x] `WebConfig` — `/random` под `JwtAuthInterceptor` (иначе страница открыта без входа)

### Frontend

- [x] `templates/random.html` — страница в том же layout, `topbar('random')` + `tabs('random')`
- [x] Кнопка «Ещё 8» с иконкой кубика рядом с заголовком:
  ```html
  hx-get="/api/v1/posts/random-fragment"
  hx-target="#post-list"
  hx-swap="innerHTML"
  ```
- [x] Пункт «Наугад» в верхней навигации и в нижних вкладках

> Отклонения от исходного плана (он писался до редизайна «Обложки»):
> сайдбара больше нет — пункт уехал в топбар и в мобильные вкладки (их стало 6);
> контейнер списка называется `#post-list`, а не `#random-list`, и переиспользует
> существующий `fragments/post-list :: posts` вместо нового `random-list`;
> отдельный фрагмент не понадобился.

### Ключевые решения

- Без инфинит-скролла — только ручной рефреш; фрагмент всегда отдаётся с `hasMore=false`,
  чтобы сентинел не попал на страницу и не начал дозагружать поверх тасовки
- `ORDER BY RANDOM() LIMIT 8` — достаточно для тысяч постов, доп. индексы не нужны
- Карточки используют тот же `post-card :: card` с `pageType='unread'` — архив,
  фаворит и удаление работают без изменений

### Тесты

- [x] `PostDaoTest` — 6 тестов: лимит, шелф меньше запроса, пусто, чужие посты,
      архив не попадает, тасовка (три выборки по 8 из 20 дают >8 разных id)
- [x] `PostApiControllerTest` — 5 тестов: карточки, лимит 8, отсутствие сентинела,
      изоляция по пользователю, 401 без куки
- [x] `WebControllerTest` — 3 теста: страница, кнопка тасовки, redirect на /login

---

## Фаза 5 — Auth + защита Web

> **Реализовано иначе, чем планировалось.** Изначально закладывался Telegram Login
> Widget с проверкой HMAC-подписи и `auth_date`. В итоге сделан полноценный
> **OAuth 2.0 / OIDC Authorization Code Flow с PKCE** через `oauth.telegram.org`:
> нет виджета на странице, нет HMAC-верификации, данные пользователя приходят в
> `id_token`. Актуальные правила — в `.claude/rules/security.md`, диаграммы флоу — в `README.md`.

### Технологии
- Telegram OIDC/OAuth 2.0 + PKCE (`oauth.telegram.org`, scope `openid profile`)
- JWT (JJWT 0.12.5) в HttpOnly-куке `auth_token`

### Как работает вход
```
GET /auth/telegram          → генерация state (UUID) + PKCE code_verifier/challenge (S256),
                              оба в HttpOnly-куках на 5 минут, redirect на oauth.telegram.org
GET /auth/telegram/callback → сверка state из куки с параметром,
                              POST /token (code + code_verifier + client_secret),
                              разбор id_token, getOrCreateUser, выдача auth_token
GET /logout                 → auth_token с maxAge=0, redirect на /login
```

Подпись `id_token` не верифицируется осознанно (токен получен server-to-server по TLS);
дополнительно проверяются claim'ы `exp`, `aud` и `iss`. Telegram ID лежит в
нестандартном claim `id`, а не в `sub`.

### Новые файлы
- [x] `service/TelegramOidcService.kt` — вместо запланированного `TelegramAuthService.kt`
- [x] `service/JwtService.kt` — subject токена = внутренний `userId`
- [x] `configuration/JwtAuthInterceptor.kt` — 401 для `/api/**`, redirect на `/login` для страниц
- [x] `controller/AuthController.kt` — GET /login, /auth/telegram, /auth/telegram/callback, /logout
- [x] `templates/login.html`
- [x] `configuration/properties/TelegramOAuthProperties.kt`, `JwtProperties.kt`
- [x] `configuration/SecurityHeadersFilter.kt` — сверх плана: X-Frame-Options, CSP, Referrer-Policy

### Защита путей
- [x] `WebConfig` навешивает интерцептор на `/`, `/all`, `/archive`, `/favorites`,
      `/profile`, `/api/v1/**`; открыты `/login`, `/auth/**` и webhook
- [x] Dev-режим из фазы 4 (`web.dev-user-id`, `DevUserInterceptor`) удалён —
      `userId` теперь приходит из JWT

### Dependencies
```kotlin
implementation("io.jsonwebtoken:jjwt-api:0.12.5")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
```

### Тесты
- [x] `AuthControllerTest`, `JwtServiceTest` — WireMock подменяет `oauth.telegram.org`

---

## Фаза 5.5 — Security hardening (сделано вне плана)

Работы после фазы 5, не имевшие своей фазы (для истории):

- [x] `UrlSecurityValidator` — SSRF-проверки внешних загрузок + закрытие redirect-bypass
      в PDF-загрузке картинок, расширение блокируемых диапазонов (`b0a22b9`)
- [x] CSP `script-src 'self'` без `unsafe-inline`/`unsafe-eval`, самохостинг ассетов,
      удаление Tailwind Play CDN (`80e4a21`, `b7696ee`)
- [x] Валидация claim'ов `id_token` (exp/aud/iss), JWT TTL → 1 день, cookie hardening
- [x] Webhook secret (`X-Telegram-Bot-Api-Secret-Token`), CSV formula injection,
      zip-bomb защита импорта, лимит размера картинок в PDF
- [x] Тесты: `UrlSecurityValidatorTest`, `TelegramWebhookSecretTest`,
      `WebControllerTest`, `PostApiControllerTest`, `ProfileApiControllerTest`

---

## Фаза 4.7 — Web Redesign «Обложки»

**Статус**: реализовано 2026-08-01. Выбрано направление «Обложки» (генеративные
обложки: цвет = hash(домена), композиция = hash(заголовка), OG-фото в
«суперобложке»). Детальный план выполнения —
`docs/superpowers/plans/2026-08-01-covers-redesign.md`; правила поддержки —
раздел «Обложки» в `.claude/CLAUDE.md`.

Сделано: `CoverService` + юнит-тесты, миграция 4 (`og_image_url` + фикс потери
`created_date` при переносе), захват og:image при добавлении, единый
`static/css/app.css` (светлая/тёмная темы), шрифты Onest + JetBrains Mono
(кириллица, self-host), все шаблоны переписаны (полка/список/витрина
избранного/hero «следующая к чтению»/профиль-формуляр/логин-афиша/empty state),
SVG-иконки вместо эмодзи, мобильные нижние вкладки, focus/aria/reduced-motion.

Хвосты фазы (все закрыты позже, отдельным коммитом):

- [x] **Тумблер «фото-обложки» в профиле** — `UserSettingsData.showOgCovers`
      (дефолт `true`, миграция не нужна: Jackson подставляет дефолт в старые
      JSONB-строки без ключа). Флаг едет в контроллеры request-атрибутом
      `JwtAuthInterceptor.SHOW_OG_COVERS` — интерцептор уже читает пользователя
      ради локали. При `false` `CoverService` не выбирает `co-og`
- [x] **Кэш-прокси OG-картинок** — `OgImageProxyService` +
      `GET /api/v1/posts/{id}/cover-image`. Caffeine с ограничением по сумме байт
      (48 МБ), TTL 7 дней, лимит 3 МБ на картинку, аллоулист content-type'ов без
      SVG. Побочный выигрыш: CSP закрыт с `img-src 'self' https:` до `img-src 'self'`,
      и IP читателя больше не утекает на сохранённые сайты
- [x] **Toast «Вернуть» после архивирования** — `POST /posts/{id}/archive` теперь
      отдаёт `{"archivedId": N}` (перенос создаёт новую строку с новым id, без него
      undo некуда слать). Карточка показывает тост сразу; hero перезагружает
      страницу, поэтому кладёт id в `sessionStorage` и поднимает тост после
      перезагрузки

Исходные долги фазы (все закрыты, кроме отмеченных выше):

- Единый CSS-файл вместо трёх `<style>`-блоков + ~100 инлайновых стилей;
  один набор токенов (сейчас у login.html и layout.html разные тёмные палитры)
- Мобильная адаптация (объединяет фазу 4.5)
- Доступность: focus-стили, aria-атрибуты, контраст `--muted`, touch-таргеты ≥44px,
  `prefers-reduced-motion`, `lang` на `<html>`
- Убрать дубли: post-card рендерится в двух DOM-вариантах (list+grid),
  optimistic-карточка дублируется строками в app.js
- Заменить emoji-иконки (⭐🗑☰⊞☀️⚙️) на единый SVG-набор
- Форма добавления ссылки на всех списковых страницах, внятные empty states,
  видимые ошибки добавления

---

## Фаза 6 — Labels

### DB Migration
```sql
CREATE TABLE label (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name    TEXT NOT NULL,
    UNIQUE (user_id, name)
);
CREATE TABLE post_label (
    post_id  BIGINT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    label_id BIGINT NOT NULL REFERENCES label(id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, label_id)
);
CREATE TABLE archive_post_label (
    post_id  BIGINT NOT NULL REFERENCES archive_post(id) ON DELETE CASCADE,
    label_id BIGINT NOT NULL REFERENCES label(id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, label_id)
);
```

### Новые файлы
- [x] `model/Label.kt` (`LabelData` + `LabelTarget`), `repository/LabelDao.kt`,
      `service/LabelService.kt`, `controller/LabelApiController.kt`
- [x] `PostData` — поле `labels: List<LabelData>`, заполняет `PostService`
      (`LabelService.withLabels`), не row mapper: один запрос на страницу
- [x] В боте: метки текстом под постом (`#kotlin #work`), с экранированием `#`
      как MarkdownV2-спецсимвола
- [x] Метки на карточках веба (полка, список, hero) — класс `.label-tag`

### REST API
```
GET/POST    /api/v1/labels
DELETE      /api/v1/labels/{id}
POST/DELETE /api/v1/posts/{id}/labels/{labelId}?type=unread|archive
GET         /api/v1/posts?labelId=&offset=
```
- [x] Все пять реализованы

### Решения по ходу

- **Две связки, не одна.** `post` и `archive_post` — разные таблицы с разными
  сиквенсами, id в них пересекаются постоянно. Поэтому `post_label` и
  `archive_post_label`, а ключ выборки меток — пара (таблица, id). Единый
  `post_label` молча приклеивал бы метки архивного поста к непрочитанному
- **Перенос сохраняет метки**: `copyLabels` вызывается до `DELETE` исходной
  строки, иначе `ON DELETE CASCADE` уносит связки вместе с ней
- **Имена нормализуются** (trim, срез `#`, схлопывание пробелов, лимит 40):
  «Kotlin», «kotlin » и «#kotlin» — одна метка. Повторное создание возвращает
  существующую, а не 409
- **`GET /posts?labelId=` отдаёт HTML-фрагмент без сентинела.** Сентинел в
  `post-list` захардкожен на `/fragment?type=…` и дотянул бы посты без метки;
  переделывать фрагмент под параметризуемый URL до появления страницы меток
  смысла нет. Листается явным `offset`

### Не входило в объём (кандидаты в следующую фазу)

- Веб-UI для меток: создание, назначение на пост, страница `/label/{id}`.
  Сейчас метки **создаются и назначаются только через REST**; бот и веб их
  показывают, но не редактируют
- Команда бота для меток
- Сентинел бесконечной прокрутки для выборки по метке
- ~~Судьба `PostData.tags`~~ — **слито**: миграция 6 перелила `tags[]` в метки
  (с нормализацией имён и разбором legacy-разделителя `|`), импорт пишет метки,
  экспорт отдаёт метки, шаблоны тегов больше не рендерят. Колонка оставлена
  нетронутой ради обратимости

### Тесты

- [x] `LabelDaoTest` — 15 тестов: CRUD, изоляция по пользователю, attach/detach,
      идемпотентность, каскад при удалении метки, копирование при переносе и
      отдельный тест на совпадающие id в двух таблицах
- [x] `LabelApiControllerTest` — 15 тестов: все эндпоинты, нормализация имени,
      400 на пустое/длинное, 404 на чужую метку, 401 без куки
- [x] `PostServiceTest` — 6 тестов: метки на постах, сохранение при переносе
      в обе стороны, отсутствие дублей после round-trip, выборка по метке
- [x] `TelegramBotIntegrationTest` — рендер метки в карточке `/feed`

---

## Verification (после каждой фазы)
```bash
./gradlew test    # все тесты зелёные
./gradlew build   # компиляция без ошибок
```
