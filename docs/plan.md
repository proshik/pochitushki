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
| 4.5 | Mobile UI | ⏳ В очереди |
| 4.6 | Random | ⏳ В очереди |
| 5 | Auth + защита Web | ⏳ В очереди |
| 6 | Labels | ⏳ В очереди |
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
- [x] `service/PdfService.kt` — `generatePdf(url): ByteArray` (Jsoup → Cleaner → XHTML → openhtmltopdf)
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

- [x] `ExportServiceTest` — 5 тестов: null, ZIP, заголовки, поля, round-trip
- [x] `ImportServiceTest` — расширен до 7 тестов, включая `is_favorite` на unread и archive

---

## Фаза 4 — Web UI Foundation (без аутентификации)

**Логика**: сначала строим рабочий минимальный UI и убеждаемся, что посты корректно
отображаются и загружаются. Auth добавляем следующим шагом (фаза 5).

Для dev-режима: `userId` берётся из cookie или query-param `?userId=<telegram_id>`.

### Стек
- **Thymeleaf** — шаблоны на стороне сервера
- **HTMX** (CDN) — динамические обновления без JS-фреймворка и без сборки
- **Tailwind CSS** (CDN) — утилитарные стили без Node.js/npm

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

- [x] `/feed` — непрочитанные посты (пагинация)
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

### Новые файлы

- [x] `controller/WebController.kt` — страницы `/feed`, `/archive`, `/favorites`, `/profile`
- [x] `controller/PostApiController.kt` — REST API для постов
- [x] `configuration/WebConfig.kt` — DevUserInterceptor, injects userId from config
- [x] `configuration/properties/WebProperties.kt` — `web.dev-user-id`
- [x] `templates/layout.html` — базовый layout (nav + content area)
- [x] `templates/feed.html`, `templates/archive.html`, `templates/favorites.html`, `templates/profile.html`
- [x] `templates/fragments/post-card.html`, `post-list.html`, `add-post-form.html`

---

## Фаза 4.5 — Mobile UI

**Цель**: адаптировать веб-интерфейс для мобильных телефонов. Текущий UI — desktop-only: фиксированный сайдбар 248px занимает 66% экрана телефона (375px), нет media queries.

**Breakpoint**: `768px` — ниже этого значения включается мобильный layout.

### Подход: скрыть сайдбар → нижний tab bar

На мобильном:
- Скрыть десктопный сайдбар (`display: none`)
- Убрать `padding-left: 248px` с `body`, добавить `padding-bottom: 56px`
- Показать **фиксированный нижний nav bar** (56px): Все / Непрочитанные / Архив / Избранное / Профиль
- Добавить **тонкий top bar** (48px) с названием приложения и кнопкой смены темы

### Изменения

#### `templates/layout.html`
- [ ] CSS: добавить `@media (max-width: 767px)` блок:
  - `body { padding-left: 0; padding-bottom: 56px; }`
  - `.desktop-nav { display: none; }` — скрыть сайдбар
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
1. `docker compose up --build`
2. Chrome DevTools → Device toolbar → iPhone SE (375×667), Pixel 7 (412×915)
3. Убедиться: нижний таббар отображается, навигация работает, active-state корректен
4. Десктоп (>767px): сайдбар без изменений, регрессий нет

---

## Фаза 4.6 — Random

**Цель**: страница `/random` — показывает 8 случайных непрочитанных постов с кнопкой «Ещё 8».

### Backend

- [ ] `PostDao` — метод `getRandomPosts(userId, count): List<PostData>` → `ORDER BY RANDOM() LIMIT :count`
- [ ] `PostService` — делегирующий метод `getRandomPosts(userId, count)`
- [ ] `PostApiController` — новый endpoint:
  ```
  GET /api/v1/posts/random-fragment → fragments/random-list :: posts
  ```
  Возвращает фрагмент с 8 случайными карточками.
- [ ] `WebController` — маршрут `/random` → `random.html`, первая порция загружается сервером

### Frontend

- [ ] `templates/random.html` — страница в том же layout, `nav('random')`
- [ ] Список постов в `<div id="random-list">` — стандартные карточки `post-card :: card`
- [ ] Кнопка «Ещё 8» (или иконка 🔀) рядом с заголовком:
  ```html
  hx-get="/api/v1/posts/random-fragment"
  hx-target="#random-list"
  hx-swap="innerHTML"
  ```
  Полностью заменяет список новыми 8 постами (не дозагружает, а перетасовывает).
- [ ] Пункт «Случайные» в сайдбаре (`layout.html`) — между «Избранное» и нижним блоком

### Ключевые решения

- Без инфинит-скролла — только ручной рефреш
- `ORDER BY RANDOM() LIMIT 8` — достаточно для тысяч постов, доп. индексы не нужны
- Карточки используют тот же `post-card :: card` фрагмент — все действия (архив, фаворит, удалить) работают без изменений

### Тесты

- [ ] `PostDaoTest` — `getRandomPosts` возвращает правильное количество, все посты принадлежат пользователю

---

## Фаза 5 — Auth + защита Web

### Технологии
- Telegram Login Widget (https://core.telegram.org/bots/telegram-login)
- JWT (JJWT 0.12.5)

### Алгоритм верификации hash
```kotlin
// 1. data_check_string из параметров без hash, sorted by key, joined by \n
// 2. secret_key = SHA256(bot_token)  ← НЕ HMAC
// 3. hash = HMAC-SHA256(key=secret_key, data=data_check_string)
// 4. Проверка hash + auth_date <= 24ч
```

### Новые файлы
- [ ] `service/TelegramAuthService.kt`
- [ ] `service/JwtService.kt`
- [ ] `configuration/JwtAuthInterceptor.kt`
- [ ] `controller/AuthController.kt` — GET /login, GET /auth/telegram/callback, GET /logout
- [ ] `templates/login.html`

### Dependencies
```kotlin
implementation("io.jsonwebtoken:jjwt-api:0.12.5")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
```

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
- [ ] `model/Label.kt`, `repository/LabelDao.kt`, `service/LabelService.kt`
- [ ] `PostData` — добавить `labels: List<LabelData>`
- [ ] В боте: метки текстом под постом (`#kotlin #work`)

### REST API
```
GET/POST    /api/v1/labels
DELETE      /api/v1/labels/{id}
POST/DELETE /api/v1/posts/{id}/labels/{labelId}
GET         /api/v1/posts?labelId=
```

---

## Verification (после каждой фазы)
```bash
./gradlew test    # все тесты зелёные
./gradlew build   # компиляция без ошибок
```
