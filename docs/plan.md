# Pochitushki — Roadmap доработок

## Статус фаз

| Фаза | Название | Статус |
|------|----------|--------|
| 1 | Тесты (актуализация + расширение) | ✅ Готово |
| 2 | Favorites | ✅ Готово |
| 3 | PDF | ✅ Готово |
| 3.5 | Bot UX улучшения | ✅ Готово |
| 4 | Auth + Web Foundation | ⏳ В очереди |
| 5 | Web UI | ⏳ В очереди |
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
  - [ ] `ImportServiceTest` — разбор Pocket CSV/ZIP
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

---

## Фаза 4 — Auth + Web Foundation

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
- [ ] `configuration/WebConfig.kt`
- [ ] `controller/AuthController.kt` — GET /login, GET /auth/telegram/callback, GET /logout
- [ ] `templates/login.html`

### Dependencies
```kotlin
implementation("io.jsonwebtoken:jjwt-api:0.12.5")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
```

---

## Фаза 5 — Web UI

### Стек: Thymeleaf + HTMX + Tailwind CSS (всё без сборки фронтенда)
- Нет Node.js / npm / webpack
- CDN для HTMX и Tailwind

### Страницы
- [ ] `/login`, `/feed`, `/archive`, `/favorites`, `/profile`

### REST API
```
GET    /api/v1/posts?type=unread|archive|favorites&limit=&offset=
POST   /api/v1/posts
DELETE /api/v1/posts/{id}
POST   /api/v1/posts/{id}/archive
POST   /api/v1/posts/{id}/unread
POST   /api/v1/posts/{id}/favorite
GET    /api/v1/posts/{id}/pdf
GET    /api/v1/posts/random
GET    /api/v1/profile
PUT    /api/v1/profile/settings
POST   /api/v1/import
GET    /api/v1/export
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
