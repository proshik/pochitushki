# Telegram-бот: карточка вместо ленты сообщений, Mini App и виджеты под флагом

Дата: 2026-11-16. Статус: на согласовании у владельца.

## Зачем

Владелец пользуется ботом каждый день, и не только чтобы сохранять ссылки. Он смотрит последние
и случайные посты, открывает их, отправляет в архив и удаляет. Сейчас лента в чате выглядит
громоздко (`TelegramService.getFeed`). Каждый пост приходит отдельным сообщением с клавиатурой
из 5 кнопок, после них идут разделитель `-----` и отдельное сообщение «Следующая». Кнопок
больше, чем контента, и чат быстро разрастается.

Образец — соседний проект владельца Pacer (`~/work/group/me/project/pacer`). Бот отвечает одним
сообщением с кнопками и правит его на месте, а сайт открывается внутри Telegram как Mini App
со входом по `initData`.

## Цели

1. **Лента в чате — одна карточка, которая обновляется на месте** (вариант A, выбран владельцем).
2. **Mini App:** внутри Telegram открывается тот же веб-интерфейс, что на сайте, без экрана логина.
3. **HTML-виджеты (Bot API 10.4)** выключены флагом в конфиге. Включить его можно будет, когда
   Telegram снова разрешит их обычным ботам. Пока флаг выключен, всё работает на карточке из пункта 1.
4. **Обновить зависимости**, включая kotlin-telegram-bot 6.3.0 → 10.0.0.

## Не цели

- Отдельный интерфейс для Mini App, например экран свайпов. Владелец: «хватит просто перенести сайт».
- Галочка «виджеты» в профиле пользователя. Только флаг в конфиге.
- Переход на другую Telegram-библиотеку или свой клиент на весь Bot API (варианты 2 и 3 ниже).
- Экспорт и импорт: остаются как есть, в профиле бота.

## Анти-цели (провал, даже если формально работает)

- Чат снова разрастается: на одно действие с постом бот присылает новое сообщение вместо правки
  карточки.
- В обычном браузере ослаб CSP: появился `telegram.org` в `script-src` или разрешение на
  встраивание во фрейм вне режима Mini App.
- После включения флага виджетов бот молчит или падает, если Telegram отказал в `sendWidget`.
- Кнопка у старой карточки (пост уже убран через сайт) приводит к ошибке или к действию
  не над тем постом.

## Подходы к Telegram-клиенту

| | Вариант | Плюсы | Минусы |
|---|---|---|---|
| **1 (выбран)** | kotlin-telegram-bot (обновлённая до 10.0.0) плюс тонкий Feign-клиент для того, чего в ней нет (виджеты) | Меньше всего изменений; тесты на WireMock почти не меняются | Два клиента; `widget_query` придётся перехватывать в webhook до библиотеки; в polling виджетов нет |
| 2 | Перейти на ktgbotapi или TelegramBots | Новые методы Bot API приходят «из коробки» | Переписать около 2000 строк и все тесты; виджетов 10.4 там, скорее всего, ещё нет |
| 3 | Свой Feign-клиент на весь Bot API | Полный контроль; виджеты работают и в polling | Свой диспетчер и long polling, больше всего работы и поддержки |

Владелец выбрал вариант 1. Причина: основная работа — карточка и Mini App, и текущей
библиотеке она не мешает. Виджеты сейчас выключены, так что ограничение «только webhook»
пока ничего не стоит.

## Решения владельца

- Вариант A для ленты; PDF и метки уходят под «⋯».
- Mini App = существующий сайт без изменений в функциональности.
- Виджеты — флаг в конфиге, без настройки у пользователя.
- Клиент — вариант 1, библиотеку обновить до последней версии. Остальные зависимости тоже
  проверить и обновить.

## Дизайн

### 1. Карточка в чате (`/feed`, `/archive`, `/favorites`, `/random_post`)

```
System 1 and System 2 Thinking            ← ссылка
thedecisionlab.com · 20 сен · #психология
[ превью сайта с картинкой ]
[ ✓ В архив ] [ ★ ] [ 🗑 ] [ ⋯ ]
[    ‹    ] [ 3 / 42 ] [    ›    ]
```

- Команда присылает **одно** сообщение. Все кнопки правят его же через `editMessageText`
  или `editMessageReplyMarkup`. Новых сообщений на действия с постом нет.
- Текст в HTML parse mode (как в Pacer), а не MarkdownV2. Экранировать `& < >` и кавычки
  в `href`. Заголовок — ссылка, ниже домен, дата и метки.
- Превью: `LinkPreviewOptions(url = post.url, preferLargeMedia = true)`. При листании
  превью меняется вместе с URL.
- **Позиция:** карточка показывает пост с номером `offset` в том же порядке, что сейчас отдаёт
  `PostService.getPosts` для этого типа. Счётчик берётся из `getPostCount`.
- «В архив» / «🗑» → пост убран, на том же `offset` показывается следующий. Если `offset`
  вышел за конец, показываем предыдущий. Если постов не осталось — «Всё прочитано» без кнопок
  (**решение исполнителя:** «Случайный» выбирает только из непрочитанного, так что
  здесь он бесполезен).
- «🗑» спрашивает подтверждение прямо в карточке: клавиатура меняется на
  «Точно удалить? / Отмена». Удаление насовсем.
- «★» переключает избранное и перерисовывает только клавиатуру.
- «⋯» раскрывает второй ряд: «PDF», «Метки», «Открыть в приложении» (**решение
  исполнителя:** открывает полку этого типа, `/`, `/archive` или `/favorites`; отдельной
  страницы поста на сайте нет, и мы её не делаем). Повторное нажатие
  на «⋯» его сворачивает. «Метки» — существующий пикер (`showPostLabels`), его «Назад»
  возвращает клавиатуру карточки с тем же `offset`.
- Архив: вместо «В архив» кнопка «↩ В непрочитанное». Избранное: показывает и непрочитанные,
  и архивные посты, кнопка зависит от того, где лежит пост (как сейчас в `isArchived`).
  **Решение исполнителя (по проверке):** в избранном «В архив» и «↩ В непрочитанное»
  меняют только кнопку: пост остаётся в избранном, но id меняется на id новой строки.
  Снятие ★ оставляет карточку с ☆, чтобы можно было передумать. Счётчик и список
  пересчитываются при следующем листании.
- `/random_post`: та же карточка, только вместо «‹ n/N ›» кнопка «🎲 Ещё». Архив и удаление
  показывают следующий случайный пост.
- **Старая карточка:** каждое нажатие несёт `postId` и `offset`. Если поста с этим id больше
  нет (убран через сайт), отвечаем тостом «Пост уже убран» (`answerCallbackQuery`)
  и перерисовываем карточку на том же `offset`. На **каждое** нажатие отвечаем
  `answerCallbackQuery`, иначе у кнопки крутятся часики.
- `callback_data` ≤ 64 байт: короткий префикс действия, тип одной буквой (`u/a/f/r`),
  `offset`, `postId`. Формат держим в одном месте, по образцу `LabelCallbackContext`.
- **Удаляется:** сообщение-заголовок «У вас N…», разделитель `NAVIGATION_TEXT_BUTTON`,
  сообщение навигации, настройка `tgFeedEntriesNumber` в профиле бота и её кнопки,
  а также «Ссылок в ленте бота» в веб-профиле (`profile.html`) и её приём в
  `POST /api/v1/profile/settings` (решение владельца). Ключ
  в JSONB-настройках можно оставить: Jackson прочитает старые строки, а новый код его не читает.
- Сохранение ссылки (`addPost`) остаётся коротким ответом. Добавляется кнопка «Открыть
  в приложении».

### 2. Mini App

- **Вход.** В BotFather адрес главной Mini App — `${APP_BASE_URL}/tg`. Страница `/tg` берёт
  `initData` из `Telegram.WebApp` и отправляет `POST /auth/telegram/webapp`. Сервер проверяет
  HMAC по токену бота (`secret_key = HMAC_SHA256("WebAppData", bot_token)`). Сравнение
  за постоянное время, `auth_date` не старше 24 ч, поле `signature` участвует в проверке
  как обычное (так делает Pacer, см. `pacer/pkg/miniapp`). Пользователь ищется так же,
  как при OIDC-входе (`UserService.getOrCreateUser` по Telegram id). Сервер выставляет
  обычный `auth_token` и маркер режима Mini App, затем редиректит на `/`. Если есть
  `start_param`, редирект ведёт на нужную страницу.
- **Режим Mini App** определяется на сервере по маркеру (отдельная cookie или claim в JWT —
  решает исполнитель). Только в этом режиме и на `/tg`:
  - `layout.html` подключает `https://telegram.org/js/telegram-web-app.js`, и CSP для этого
    ответа добавляет `https://telegram.org` в `script-src`;
  - `frame-ancestors` разрешает origin'ы Telegram Web (`https://web.telegram.org`), а
    `X-Frame-Options` не выставляется;
  - вне режима заголовки остаются как сейчас (`frame-ancestors 'none'`, `DENY`).
- **Адаптация в `app.js`** (никакого inline-JS, правила CSP из AGENTS.md):
  - тема светлая/тёмная по `colorScheme`, плюс `themeChanged`;
  - `BackButton` Telegram вместо браузерной навигации на внутренних страницах;
  - внешние ссылки на статьи открываются через `Telegram.WebApp.openLink`;
  - `ready()` и `expand()` при загрузке;
  - кнопка «Выйти» в режиме Mini App скрыта.
- **Куки и Telegram Web.** В нативных клиентах Mini App открывается в WebView верхнего уровня,
  и `SameSite=Lax` работает. В web.telegram.org страница открывается во фрейме с другого
  сайта, и Lax-cookie туда не попадёт. **Решение исполнителя (по умолчанию):** цель v1 —
  нативные клиенты. В Telegram Web, если cookie не доходит, показываем экран «Откройте
  в браузере» со ссылкой. Ослаблять `SameSite` ради Web нельзя без отдельного решения владельца.
- **Точки входа:** кнопка меню бота (`setChatMenuButton` с `MenuButtonWebApp` при старте),
  «Открыть в приложении» под «⋯», кнопка в ответе на `/start` и после сохранения ссылки.
  Кнопки `web_app` Telegram принимает только в личных чатах, а бот и так работает в личке.

### 3. Виджеты (Bot API 10.4) под флагом

- Флаг `telegram.widgets.enabled` (`TELEGRAM_WIDGETS_ENABLED`), по умолчанию `false`.
- При `true` **и** в режиме webhook команды ленты шлют виджет вместо текстовой карточки.
  При `true` в polling — предупреждение в лог на старте и текстовая карточка: библиотека
  не отдаёт `widget_query` в polling.
- **Что внутри виджета:** та же карточка A. HTML-шаблон один (ресурс в classpath),
  inline CSS и JS, системные шрифты, цвета из `--tg-theme-*`, обложка из `CoverService`
  (`cv-*` и композиция) нарисована CSS. **Без OG-фото:** внешние картинки виджет не грузит,
  а `chat_data` слишком мал для data URI. Данные поста лежат в `chat_data` (≤ 1024 байт:
  заголовок обрезается).
- **Лимиты:** HTML ≤ 32768 байт и ≤ 4096 байт после zlib level 9. Нужен тест, который
  сжимает шаблон и проверяет оба лимита.
- **Объём v1 (решение исполнителя):** внутри виджета — «В архив», ★, 🗑 с подтверждением,
  ‹ › и открытие статьи. «PDF», «Метки» и «Открыть в приложении» — inline-клавиатура
  под виджетом (`reply_markup`), обработка как у карточки A. Пикер меток в 4 КБ сжатого
  HTML не помещается.
- **Действия:** кнопки вызывают `TelegramWidget.request("<действие>")` (≤ 64 байт, формат
  как у `callback_data`). Апдейт `widget_query` перехватывает `TelegramController`
  до передачи в библиотеку. Бот выполняет действие, обновляет карточку через
  `editMessageWidgetData` и отвечает `answerWidgetQuery` в пределах 15 с.
- Открытие статьи — `TelegramWidget.openLink` (URL ≤ 256 символов). Для более длинных URL —
  inline-кнопка-ссылка под виджетом (как именно — решает исполнитель).
- `allowed_updates` в `setWebhook` при включённом флаге дополняется `widget_query`
  и `shared_widget_query`. Без этого Telegram их не пришлёт — по аналогии с правилом
  про `callback_query`.
- **Откат:** любая ошибка `sendWidget` (в том числе отказ «не разрешено») → лог `warn`
  и та же текстовая карточка. Пользователь всегда получает ответ.
- Методы `sendWidget`, `editMessageWidgetData`, `answerWidgetQuery` вызываются через
  Feign-клиент на `telegram.api-url`, чтобы тесты подменяли их тем же WireMock.
- Ручная проверка — на тестовом боте (webhook), если Telegram примет `sendWidget`.
  В официальной документации запрета для обычных ботов нет (в production виджеты работают
  в личке с ботом); о запрете с 2026-11-13 писала только новостная заметка
  (cryptonews.net). Флаг по умолчанию выключен.

### 4. Зависимости

Отдельным шагом **до** работы над ботом, чтобы регрессии от обновлений не смешались
с изменениями интерфейса.

| Зависимость | Сейчас | Цель | Заметки |
|---|---|---|---|
| kotlin-telegram-bot | 6.3.0 | 10.0.0 | Ломающее: `replyToMessageId` → `ReplyParameters`, `disableWebPagePreview` → `LinkPreviewOptions`, `Chat`/`ChatFullInfo`. См. `docs/MIGRATION_10.md` библиотеки. Проверить координаты JitPack (в релизе указано `com.github…`, у нас `io.github…`) |
| retrofit (pin) | 2.9.0 | как у telegram 10.0.0 транзитивно | Комментарий в `build.gradle.kts` требует совпадения с runtimeClasspath |
| Kotlin (плагины + `extra["kotlin.version"]`) | 2.4.20 | 2.4.21 | Менять вместе, иначе `InstantiationException` |
| Spring Boot | 4.0.8 | 4.0.9 | 4.1.x недоступен: Spring Cloud 2026.0 пока только RC |
| Spring Cloud | 2025.1.3 | 2025.1.4 | |
| jsoup | 1.23.2 | 1.24.1 | |
| bucket4j_jdk17-core | 8.20.0 | 8.22.0 | |
| openhtmltopdf-pdfbox | 1.1.86 | 1.1.114 | Прогнать PDF-тесты |
| imageio-webp | 3.12.0 | 3.15.3 | |
| playwright | 1.52.0 | 1.65.0 | Docker с `INSTALL_PLAYWRIGHT=true` качает Chromium под эту версию |
| wiremock-standalone | 3.13.0 | 3.13.2 | 4.x пока beta |
| Без изменений | | | commons-csv 1.14.1, commons-compress 1.28.0, jjwt 0.13.0, testcontainers-bom 2.0.5, dependency-management 1.1.7 |

После смены версии Boot запустить приложение с `spring-boot-properties-migrator`
и прочитать отчёт (правило из AGENTS.md; migrator не коммитить).

### 5. Живая проверка (решение владельца: вариант «а»)

- Отдельный **тестовый бот** (не прод-токен) и туннель (cloudflared или ngrok) на локальный
  запуск. Владелец один раз прописывает в BotFather адрес главной Mini App
  `https://<туннель>/tg` и даёт токен через `.env.dev` (в git не попадает).
- Бот в webhook-режиме на адрес туннеля, чтобы проверка шла тем же путём, что в проде.
- До сдачи шагов 2–3 исполнитель проверяет вживую: карточку A (листание, архив, удаление
  с подтверждением, ★, «⋯», метки, PDF, старая карточка), вход в Mini App без логина, тему,
  `BackButton`, `openLink`, отсутствие ошибок CSP в консоли. В обычном браузере заголовки
  должны остаться прежними: `frame-ancestors 'none'`, без `telegram.org`.
- Виджеты вживую — по возможности на тестовом боте, если Telegram примет `sendWidget`; это
  не блокирует шаг 4, который принимается по тестам на WireMock (включая тест лимитов
  размера шаблона).

## Ограничения

- CSP-правила из AGENTS.md: никакого inline JS, `hx-on`, `js:`-фильтров. Всё — через
  `app.js`, ассеты свои; `telegram.org` разрешается только в режиме Mini App.
- Все тексты бота — через `I18nService`, EN и RU.
- Одна реплика, `PendingLabelInput` остаётся in-memory.
- Dispatcher библиотеки однопоточный: PDF, импорт и экспорт по-прежнему через `BotTaskExecutor`.
- `allowedUpdates` webhook обязан включать `callback_query` (и `widget_query`/`shared_widget_query`
  при включённом флаге).
- Тесты: `./gradlew test` с Colima-сокетом (AGENTS.md). Сценарии бота — в
  `TelegramBotIntegrationTest` на WireMock.

## Порядок работ (предложение)

1. Обновление зависимостей и миграция кода на telegram 10.0.0 без изменения поведения.
2. Карточка в чате (A) и HTML parse mode.
3. Mini App: вход по `initData`, режим и заголовки, адаптация `app.js`, точки входа в боте.
4. Виджеты под флагом.

Каждый шаг — отдельный коммит с зелёными тестами. Push делает владелец.

## Что оставлено исполнителю

- Маркер режима Mini App: cookie или claim JWT.
- Точный формат `callback_data` и `chat_data` (в пределах лимитов).
- Вид кнопки-ссылки для длинных URL в виджете.
- Тексты i18n (тон — как у текущего бота).
- Обработка `start_param` для открытия конкретной страницы.


---

# План реализации

> **Для исполнителя:** задачи выполняются по порядку, каждая заканчивается зелёным
> `./gradlew test` и отдельным локальным коммитом. Шаги отмечены чекбоксами (`- [ ]`).

**Goal:** лента бота превращается в одну карточку, которая правится на месте; тот же сайт
открывается в Telegram как Mini App без экрана логина; HTML-виджеты Bot API 10.4 готовы
и выключены флагом.

**Architecture:** kotlin-telegram-bot 10.0.0 остаётся диспетчером апдейтов и клиентом для
простых вызовов. Всё, что рисует карточку (`sendMessage`/`editMessageText`/
`editMessageReplyMarkup` с HTML и `link_preview_options`), и методы виджетов идут через
тонкий Feign-клиент `TelegramBotApiClient` на `telegram.api-url`. Логика карточки
живёт в `PostCardService`, она не зависит от того, чем показывается карточка: текстом
(`TextCardPresenter`) или виджетом (`WidgetCardPresenter`). Mini App — те же
Thymeleaf-страницы; режим определяется claim'ом в JWT, и только в нём
`SecurityHeadersFilter` открывает `telegram.org` и `frame-ancestors` для Telegram Web.

**Tech Stack:** Kotlin 2.4.21, Spring Boot 4.0.9, Spring Cloud OpenFeign 2025.1.4,
Jackson 3, kotlin-telegram-bot 10.0.0 (JitPack), Thymeleaf, htmx, JUnit 5, TestContainers,
WireMock 3.13.2.

**Spec:** раздел выше («Дизайн» и далее) этой же заметки.

## Проверенные факты (2026-11-16)

Сверено по первоисточникам, а не по сводке в дизайне. Сигнатуры в задачах опираются на них.

- **kotlin-telegram-bot 10.0.0** (тег `10.0.0`, релиз 2026-05-15). JitPack отдаёт POM и по
  `com.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:10.0.0` (так в релизе), и по
  нашему `io.github…`. Транзитивно: retrofit **2.12.0** и converter-gson 2.12.0 (scope
  `runtime`, поэтому pin в `build.gradle.kts` остаётся), okhttp logging-interceptor 4.12.0,
  kotlinx-coroutines-core 1.11.0 (runtime), kotlin-stdlib 2.3.21.
- В 10.0.0 из `Bot.sendMessage` **удалены** `replyToMessageId` и `disableWebPagePreview`
  (вместо них `replyParameters: ReplyParameters?` и `linkPreviewOptions: LinkPreviewOptions?`).
  Release notes говорят «removed», и исходник это подтверждает. `docs/MIGRATION_10.md`
  и `docs/linkPreviewOptions.md` пишут, что старые параметры «still work», но это ошибка
  документации.
- **`Bot.editMessageText` в 10.0.0 не принимает `linkPreviewOptions`.** Параметров
  `link_preview_options` нет ни в `Bot`, ни в `ApiClient`, ни в `ApiService`. Есть только
  `chatId, messageId, inlineMessageId, text, parseMode, replyMarkup, entities,
  businessConnectionId`. `disableWebPagePreview` тоже удалён. В Bot API параметр
  `link_preview_options` у `editMessageText` есть. Поэтому правка карточки идёт через Feign
  (задача 4), а не через библиотеку. См. «Расхождения и вопросы» в конце плана.
- Типы: `com.github.kotlintelegrambot.entities.LinkPreviewOptions(isDisabled, url,
  preferSmallMedia, preferLargeMedia, showAboveText)`,
  `entities.ReplyParameters(messageId: Long, …)`,
  `entities.MenuButton.WebApp(text: String, webApp: WebAppInfo)`,
  `entities.keyboard.WebAppInfo(url: String)`,
  `entities.keyboard.InlineKeyboardButton.WebApp(text, webApp)`,
  `Bot.setChatMenuButton(chatId: ChatId? = null, menuButton: MenuButton? = null):
  TelegramBotResult<Boolean>`, `Bot.answerCallbackQuery(callbackQueryId, text, showAlert,
  url, cacheTime): TelegramBotResult<Boolean>`. `editMessageText`/`editMessageReplyMarkup`
  по-прежнему возвращают `Pair<retrofit2.Response<…>?, Exception?>`.
- Gson-модели библиотеки (`InlineKeyboardMarkup` с `@SerializedName`) Jackson
  сериализовать не умеет, а сам Gson у нас только в runtime-classpath. Поэтому у карточки
  своя модель клавиатуры (`InlineKeyboard`) с Jackson-аннотациями.
- **Bot API 10.4** (2026-11-09). `sendWidget(chat_id, html 1–32768 байт и ≤ 4096 после zlib
  max level, width?, height?, public_data?, chat_data? 0–1024 байт, caption?, allow_queries?,
  is_shared?, protect_content?, reply_parameters?, reply_markup?, …) → Message`;
  `editMessageWidgetData(chat_id?, message_id?, inline_message_id?, public_data?, chat_data?)
  → True`; `answerWidgetQuery(widget_query_id, data? 0–1024 байт) → True`, ответ нужен
  ≤ 15 с на **каждый** запрос, включая неудачные. `Update.widget_query: WidgetQuery(id,
  from, chat?, message_id?, inline_message_id?, chat_instance, chat_type?, type, data 1–64
  байт, widget_id?)` и `Update.shared_widget_query: SharedWidgetQuery(id, from, type, data,
  widget_id)`. Запросы из виджета работают **только при `allow_queries=true`** в `sendWidget`:
  в дизайне этого нет, в плане есть. Лимит — 5 запросов за 5 с на пользователя. Виджет
  исполняется в QuickJS, сеть и внешние ресурсы запрещены, шрифты только generic,
  ≤ 400 CSS-правил, бабл 200–600 × 50–600 px. Сообщение с виджетом получает
  protected content и не пересылается. `allowed_updates`: «если передаёте — включите оба».
- Доступность: страница гайда говорит, что в production виджеты работают **только в личке
  с ботом**. Запрета для обычных ботов в официальной документации нет; дата 2026-11-13
  взята из новостной заметки. См. «Решения владельца».
- **initData** (core.telegram.org/bots/webapps, Bot API 10.1 на странице):
  `secret_key = HMAC_SHA256(key = "WebAppData", message = bot_token)`,
  `hash = hex(HMAC_SHA256(key = secret_key, message = data_check_string))`.
  `data_check_string` — все полученные поля, кроме `hash`, в виде `key=value` (value
  декодирован один раз), отсортированы по ключу и разделены `\n`. `signature` участвует
  как обычное поле; так же делает Pacer (`pkg/miniapp/initdata.go`). Опубликованный
  тестовый вектор (токен `5768337691:AAH5YkoiEuPk8-FZa32hStHTqXiLPtAEhx8`, `auth_date=1662771648`)
  есть в `pacer/pkg/miniapp/initdata_test.go`, берём его же. `start_param` приходит
  **только** при запуске по ссылке `t.me/<bot>?startapp=…`; у кнопки меню и inline-кнопки
  `web_app` его нет. Скрипт: `https://telegram.org/js/telegram-web-app.js?65`, в `<head>`
  до остальных скриптов. События: `themeChanged`, `backButtonClicked`
  (`BackButton.onClick/offClick/show/hide`). `openLink(url)` — только в ответ на действие
  пользователя.
- **Код:** `answerCallbackQuery` сейчас не вызывается нигде, часики у кнопок крутятся
  до таймаута. Тесты бота идут в **polling**-режиме (`BaseIntegrationTest` выставляет
  `telegram.webhook-url=""`), апдейты подаются через `TelegramController`. В
  `TelegramProperties` нет базового URL сайта, `APP_BASE_URL` используется только в
  `telegram.oauth.redirect-uri`. `.claude/rules/security.md` сейчас прямо говорит:
  «HMAC-подписи `WebAppData` и проверки `auth_date` здесь нет и добавлять её не нужно».
  Правило писалось про OIDC-вход, и задача 10 его обновляет.

## Global Constraints

- Версии: Kotlin 2.4.21 (плагины **и** `extra["kotlin.version"]` вместе), Spring Boot 4.0.9,
  Spring Cloud 2025.1.4, kotlin-telegram-bot 10.0.0, retrofit 2.12.0, jsoup 1.24.1,
  bucket4j_jdk17-core 8.22.0, openhtmltopdf-pdfbox 1.1.114, imageio-webp 3.15.3,
  playwright 1.65.0, wiremock-standalone 3.13.2. Без изменений: commons-csv 1.14.1,
  commons-compress 1.28.0, jjwt 0.13.0, testcontainers-bom 2.0.5, dependency-management 1.1.7.
  Все версии проверены на Maven Central 2026-11-16.
- CSP из AGENTS.md: никакого inline-JS, `on*=""`, `hx-on`, `js:`; ассеты свои.
  `https://telegram.org` в `script-src` и `frame-ancestors https://web.telegram.org` —
  только для `/tg` и режима Mini App. Вне режима заголовки байт в байт прежние.
- Все тексты бота — через `I18nService`, ключи в `messages.properties` (EN) и
  `messages_ru.properties` (RU). Это касается и строк виджета: они приходят в `chat_data`.
- `callback_data` и `TelegramWidget.request` ≤ 64 байт; `chat_data` ≤ 1024 байт UTF-8;
  HTML виджета ≤ 32768 байт и ≤ 4096 байт после `Deflater(9)`; `answerWidgetQuery` ≤ 15 с;
  `TelegramWidget.openLink` ≤ 256 символов.
- `allowedUpdates` в webhook: `message`, `callback_query`; при активных виджетах ещё
  `widget_query`, `shared_widget_query`.
- На **каждый** `callback_query` — ровно один `answerCallbackQuery`, на каждый
  `widget_query` — ровно один `answerWidgetQuery`, в том числе при ошибке.
- Одна реплика; `PendingLabelInput` in-memory; PDF/импорт/экспорт — через `BotTaskExecutor`.
- `SameSite=Lax` и `CsrfOriginFilter` не ослаблять.
- Feign-клиент бота: `url = "\${telegram.api-url}bot\${telegram.token:}"` — с дефолтом
  у токена, иначе приложение с `TELEGRAM_ENABLED=false` без `TELEGRAM_TOKEN` не стартует.
- Тесты: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test`
  (ниже — `$TEST`). Повторный прогон — `cleanTest test`.
- Коммиты локальные, в стиле репозитория (`build(deps):`, `feat(bot):`, `docs:` и русский
  текст). Push делает владелец.

## Review Focus

Пять входов, которые спецификация подразумевает, но ни один сценарий не проверяет явно.
Тест на каждый добавлен в задачу, которой принадлежит код.

1. **Повторная отрисовка без изменений** (двойной тап, «старая карточка» на том же посте)
   → Telegram отвечает `400 Bad Request: message is not modified`. Это успех, а не ошибка:
   пользователь получает ответ на нажатие, в логе нет `warn`. → задача 4.
2. **Ошибка Feign утекает токеном**: `FeignException.message` содержит URL
   `…/bot<token>/…`. Логировать можно только HTTP-статус и `description` из тела Telegram,
   но не исключение и не его message. → задача 4.
3. **URL Mini App не https** (локальный `http://localhost`): Telegram отклоняет всё
   `sendMessage`, если в клавиатуре есть `web_app` с http-URL. Тогда ответ на `/start` и
   на ссылку молча пропадает. Кнопки Mini App ставятся только для `https://`. → задача 12.
4. **Кнопки карточек прошлой версии** (`archive_button|5`, `next_posts|3`,
   `profile_feed_settings|_`) остаются в чатах после деплоя. Они получают тост «карточка
   устарела», не трогают данные и не крутят часики. → задача 6.
5. **Большие заголовки и много меток**: текст карточки упирается в 4096 символов, а
   `chat_data` виджета — в 1024 байта. Заголовок режется, сообщение всегда уходит. →
   задачи 3 и 14.

---

## Шаг 1. Зависимости

### Task 1: kotlin-telegram-bot 10.0.0 без изменения поведения

**Files:**
- Modify: `build.gradle.kts` (координаты бота, pin retrofit)
- Modify: `src/main/kotlin/ru/proshik/pochitushki/service/TelegramService.kt`
  (`sendMessageWithReplayKeyboard`, `editMessage`, `addPost`, `sendPostMessage`)
- Test: `src/test/kotlin/ru/proshik/pochitushki/telegram/TelegramBotIntegrationTest.kt`

**Interfaces:**
- Produces: `TelegramService.editMessage(chatId, messageId, text, keyboard, parseMode)`
  без параметра `disableWebPagePreview` (превью по умолчанию включено, как было при `false`).

- [ ] **Step 1: Тест на ответ-реплай.** `url message reply quotes the original message`:
  после сохранения ссылки тело `sendMessage` (form-encoded) содержит
  `reply_parameters=` с `%22message_id%22%3A<messageId>`.
- [ ] **Step 2:** `$TEST --tests '*TelegramBotIntegrationTest'`. Тест падает: в 6.3.0
  уходит `reply_to_message_id`.
- [ ] **Step 3: Координаты и pin.**
  `implementation("com.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:10.0.0")`,
  `implementation("com.squareup.retrofit2:retrofit:2.12.0")`, комментарий у pin оставить.
- [ ] **Step 4: Миграция вызовов.** `replyToMessageId = x` →
  `replyParameters = x?.let { ReplyParameters(messageId = it) }`;
  `disableWebPagePreview = false` у `sendMessage` убрать, у `editMessageText` убрать
  параметр совсем. Остальное — по ошибкам компиляции.
- [ ] **Step 5: Classpath.** `./gradlew dependencies --configuration runtimeClasspath | grep -E 'retrofit|converter-gson|kotlinx-coroutines-core'`.
  Ожидается retrofit 2.12.0. Coroutines берутся из BOM Boot, а не 1.11.0 библиотеки;
  зафиксировать фактическую версию в сообщении коммита.
- [ ] **Step 6:** `$TEST`. Всё зелёное, включая новый тест.
- [ ] **Step 7: Commit** `build(deps): kotlin-telegram-bot 10.0.0 и retrofit 2.12.0`.

### Task 2: остальные зависимости

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1:** Поднять версии из Global Constraints: Kotlin-плагины и
  `extra["kotlin.version"]`, Boot, `springCloudVersion`, jsoup, bucket4j, openhtmltopdf,
  imageio-webp, playwright, wiremock.
- [ ] **Step 2:** `$TEST`. Всё зелёное, PDF-тесты тоже.
- [ ] **Step 3: Отчёт migrator'а.** Временно добавить
  `runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")`, запустить
  `./gradlew bootRun` (БД из `compose.dev.yaml`, переменные из `.env.dev`, `TELEGRAM_ENABLED=false`) и прочитать отчёт
  в логе старта. Ожидается: переименованных ключей нет. Строку migrator убрать.
- [ ] **Step 4:** `git diff build.gradle.kts` — migrator'а в diff нет.
- [ ] **Step 5: Commit** `build(deps): Kotlin 2.4.21, Spring Boot 4.0.9, Spring Cloud 2025.1.4 и библиотеки`.

---

## Шаг 2. Карточка в чате (A)

### Task 3: формат callback_data и HTML-текст карточки

**Files:**
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/card/CardRef.kt`
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/card/PostCardRenderer.kt`
- Test: `src/test/kotlin/ru/proshik/pochitushki/service/telegram/card/CardRefTest.kt`
- Test: `src/test/kotlin/ru/proshik/pochitushki/service/telegram/card/PostCardRendererTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class CardList(val code: Char) { UNREAD('u'), ARCHIVE('a'), FAVORITES('f'), RANDOM('r'), SAVED('s') }
  enum class PostLoc(val code: Char) { UNREAD('u'), ARCHIVE('a');
      val postType: PostType; val labelTarget: LabelTarget }
  data class CardRef(val list: CardList, val offset: Int, val postId: Long, val loc: PostLoc,
                     val widget: Boolean = false) {
      fun encode(): String              // "u:3:123:u"; widget=true → код списка заглавной: "U:3:123:u"
      companion object { fun decode(data: String): CardRef? }   // null на любой мусор, без исключений
  }
  object CardAction { NAV="cn" ARCHIVE="ca" UNREAD="cu" DELETE_ASK="cd" DELETE_YES="cy"
      DELETE_NO="cx" FAVORITE="cf" MORE="cm" RANDOM_NEXT="cr" PDF="cp" PDF_ENGINE="ce"
      LABELS="lp" LABEL_TOGGLE="lt" LABEL_NEW="ln" LABELS_BACK="lb" NOOP="c0" }
  fun cardCallback(action: String, ref: CardRef, extra: String? = null): String
      // "$action|${ref.encode()}" + (":$extra"), require(utf8 bytes ≤ 64)
  fun parseCardCallback(data: String): Pair<CardRef, String?>?   // data — часть после "|"
  data class CardText(val html: String, val previewUrl: String)
  @Component class PostCardRenderer(i18n: I18nService, coverService: CoverService) {
      fun render(post: PostData, languageCode: String, today: LocalDate = LocalDate.now()): CardText
  }
  fun escapeHtml(s: String): String    // & < > " — в этом порядке, & первым
  ```
- Формат `CardRef` заменяет `TelegramKeyboard.LabelCallbackContext`. Ссылка несёт и место
  карточки (список, offset), и пост (id, таблица), поэтому пикер меток возвращает
  карточку на тот же `offset`.

- [ ] **Step 1: Тесты `CardRefTest`:**
  - `round trip for every list and loc`: `decode(encode(x)) == x` для всех `CardList` × `PostLoc` × `widget`.
  - `worst case fits 64 bytes`: `cardCallback(CardAction.LABEL_TOGGLE, CardRef(FAVORITES, 999_999, Long.MAX_VALUE, ARCHIVE, widget = true), Long.MAX_VALUE.toString())`
    ≤ 64 байт; `cardCallback(PDF_ENGINE, …, "playwright")` ≤ 64.
  - `decode rejects garbage`: `decode("")`, `decode("x:1:2:u")`, `decode("u:-1:2:u")`,
    `decode("u:1:abc:u")`, `decode("u:1:2")` → `null`.
  - `extra survives`: `parseCardCallback("u:0:5:u:42") == CardRef(UNREAD,0,5,UNREAD) to "42"`.
- [ ] **Step 2: Тесты `PostCardRendererTest`** (unit, I18nService и CoverService создаются
  напрямую):
  - `title is a link and fields are escaped`: заголовок `A <b> & "q"`, url
    `https://x.com/?a=1&b="2"` → html начинается с
    `<a href="https://x.com/?a=1&amp;b=&quot;2&quot;">A &lt;b&gt; &amp; &quot;q&quot;</a>`.
  - `second line is domain, date and labels`: `www.thedecisionlab.com`, дата 2026-09-20,
    метки `психология`, `kotlin` → вторая строка `thedecisionlab.com · 20 … · #психология #kotlin`.
    Месяц — `DateTimeFormatter.ofPattern("d MMM", locale)`; год добавляется, если не
    совпадает с `today`.
  - `no title falls back to url`.
  - `previewUrl is the post url`.
  - `huge title is cut and the text stays under 4096 chars`: заголовок 10 000 символов и
    50 меток по 30 символов → `html.length ≤ 4096` (Review Focus 5). Заголовок режется
    до `MAX_TITLE = 300` с `…`, метки — до тех, что влезают.
- [ ] **Step 3:** `$TEST --tests '*card*'`. Тесты падают на компиляции.
- [ ] **Step 4:** Реализовать файлы по Interfaces. Домен брать из
  `coverService.decorate(post, showOgCovers = false).domain`.
- [ ] **Step 5:** `$TEST --tests '*card*'` → PASS.
- [ ] **Step 6: Commit** `feat(bot): формат callback_data карточки и HTML-текст поста`.

### Task 4: Feign-клиент Bot API для карточки

**Files:**
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/api/TelegramBotApiClient.kt`
  (Feign-интерфейс и DTO)
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/api/TelegramBotApi.kt`
  (обёртка: ошибки, логирование)
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/api/InlineKeyboard.kt`
- Modify: `src/main/resources/application.yml` (таймауты клиента `telegram-bot-api`)
- Test: `src/test/kotlin/ru/proshik/pochitushki/service/telegram/api/TelegramBotApiTest.kt`
  (наследник `BaseIntegrationTest`, профиль `test`, WireMock `wireMockTelegramApi`)

**Interfaces:**
- Produces:
  ```kotlin
  @JsonInclude(NON_NULL) data class InlineButton(val text: String,
      @JsonProperty("callback_data") val callbackData: String? = null,
      val url: String? = null,
      @JsonProperty("web_app") val webApp: WebAppRef? = null)
  data class WebAppRef(val url: String)
  data class InlineKeyboard(@JsonProperty("inline_keyboard") val rows: List<List<InlineButton>>)
  data class LinkPreview(val url: String, @JsonProperty("prefer_large_media") val preferLargeMedia: Boolean = true)

  @FeignClient(name = "telegram-bot-api", url = "\${telegram.api-url}bot\${telegram.token:}")
  interface TelegramBotApiClient {
      @PostMapping("/sendMessage", consumes = ["application/json"]) fun sendMessage(@RequestBody r: SendMessageRequest): TgResponse<JsonNode>
      @PostMapping("/editMessageText", consumes = ["application/json"]) fun editMessageText(@RequestBody r: EditMessageTextRequest): TgResponse<JsonNode>
      @PostMapping("/editMessageReplyMarkup", consumes = ["application/json"]) fun editMessageReplyMarkup(@RequestBody r: EditReplyMarkupRequest): TgResponse<JsonNode>
  }
  // SendMessageRequest(chat_id, text, parse_mode = "HTML", link_preview_options: LinkPreview?, reply_markup: InlineKeyboard?, reply_parameters: {message_id}?)
  // EditMessageTextRequest(chat_id, message_id, text, parse_mode = "HTML", link_preview_options?, reply_markup?)
  // EditReplyMarkupRequest(chat_id, message_id, reply_markup?)
  // TgResponse<T>(ok: Boolean, result: T?, description: String?)

  @Service class TelegramBotApi(client: TelegramBotApiClient) {
      fun sendHtml(chatId: Long, html: String, preview: LinkPreview?, keyboard: InlineKeyboard?, replyTo: Long? = null): Long?   // message_id или null
      fun editHtml(chatId: Long, messageId: Long, html: String, preview: LinkPreview?, keyboard: InlineKeyboard?): Boolean
      fun editKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean
  }
  ```
  Ошибки не бросаются наружу. `400` с `description`, содержащим `message is not modified`,
  считается успехом (`true`) и логируется на `debug`. Остальное — `warn` со статусом и
  `description`, без исключения и URL.

- [ ] **Step 1: Тесты:**
  - `editHtml sends link preview options as json`: тело JSON содержит
    `"parse_mode":"HTML"`, `"link_preview_options":{"url":"https://p.com","prefer_large_media":true}`,
    `"message_id":77`, `"reply_markup":{"inline_keyboard":[[{"text":"✓","callback_data":"ca|u:0:1:u"}]]}`,
    и **нет** `null`-полей.
  - `not modified counts as success`: стаб `400 {"ok":false,"description":"Bad Request: message is not modified: …"}`
    → `editHtml` возвращает `true` (Review Focus 1).
  - `api error is logged without the token`: стаб `403` → `false`; захваченный лог
    (`OutputCaptureExtension`) не содержит `some_token` (Review Focus 2).
  - `sendHtml returns message id`: стаб `{"ok":true,"result":{"message_id":55,…}}` → `55`.
- [ ] **Step 2:** `$TEST --tests '*TelegramBotApiTest'`. Тесты падают.
- [ ] **Step 3:** Реализовать по Interfaces. Таймауты в `application.yml`:
  `spring.cloud.openfeign.client.config.telegram-bot-api: connectTimeout: 3000, readTimeout: 5000`,
  `followRedirects: false`.
- [ ] **Step 4:** `$TEST` → всё зелёное. Контекст с `telegram.enabled=false` (профиль `test`)
  поднимается.
- [ ] **Step 5: Commit** `feat(bot): Feign-клиент Bot API для карточки`.

### Task 5: лента одной карточкой с листанием

**Files:**
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/card/PostCardService.kt`
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/card/CardPresenter.kt`
  (интерфейс и `TextCardPresenter`)
- Modify: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/TelegramKeyboard.kt`
  (`buildCardKeyboard`)
- Modify: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/CommandHandler.kt`
  (`/feed`, `/archive`, `/favorites` → `PostCardService.sendCard`)
- Modify: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/CallbackQueryHandler.kt`
  (ветки `cn`, `c0`)
- Modify: `src/main/resources/messages/messages*.properties`
- Test: `src/test/kotlin/ru/proshik/pochitushki/telegram/TelegramBotIntegrationTest.kt`
- Test: `src/test/kotlin/ru/proshik/pochitushki/service/telegram/TelegramKeyboardTest.kt`

**Interfaces:**
- Consumes: `CardRef`, `CardList`, `PostLoc`, `cardCallback`, `PostCardRenderer.render`
  (задача 3); `TelegramBotApi`, `InlineKeyboard`, `LinkPreview` (задача 4).
- Produces:
  ```kotlin
  sealed interface CardView {
      data class Post(val post: PostData, val ref: CardRef, val total: Int,
                      val expanded: Boolean = false, val confirmDelete: Boolean = false) : CardView
      data class Empty(val list: CardList) : CardView                 // «Всё прочитано» / «нет постов»
      data class Status(val text: String) : CardView                  // итог для SAVED
  }
  interface CardPresenter {
      fun send(chatId: Long, view: CardView, languageCode: String, replyTo: Long? = null): Boolean
      fun redraw(chatId: Long, messageId: Long, view: CardView, languageCode: String): Boolean
      fun redrawKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean
  }
  data class CallbackAnswer(val text: String? = null)
  @Service class PostCardService(…) {
      fun sendCard(chatId: Long, list: CardList, replyTo: Long? = null)
      fun onCallback(chatId: Long, messageId: Long, action: String, ref: CardRef, extra: String?): CallbackAnswer
  }
  // член TelegramKeyboard:
  fun buildCardKeyboard(view: CardView.Post, languageCode: String): InlineKeyboard
  ```
- Пост на `offset` = `postService.getPosts(user.id, list.postType, 1, offset).firstOrNull()`,
  счётчик = `getPostCount`. Для FAVORITES `loc` берётся из `post.isArchived`.
- Раскладка клавиатуры (`expanded=false`, `confirmDelete=false`):
  ряд 1 — `[✓ В архив (ca) | ↩ В непрочитанное (cu)] [★/☆ (cf)] [🗑 (cd)] [⋯ (cm, extra "1")]`;
  ряд навигации — `[‹ (cn, offset-1) | · (c0)] [n / N (c0)] [› (cn, offset+1) | · (c0)]`.
  `·` стоит на краях, чтобы ряд не прыгал.

- [ ] **Step 1: `TelegramKeyboardTest`** (unit):
  - `unread card first row`: подписи и `callback_data` `ca|u:2:7:u`, `cf|…`, `cd|…`, `cm|…:1`.
  - `archive card offers back to unread`: `cu|a:0:7:a`.
  - `favorites card follows where the post lives`: `loc=ARCHIVE` → `cu|f:…:a`.
  - `edges show inert dots`: offset 0 из 3 → левая кнопка `c0`, offset 2 из 3 → правая `c0`; счётчик `1 / 3` / `3 / 3`.
  - `every button fits 64 bytes`: обход всех кнопок для `postId = Long.MAX_VALUE`, `offset = 999_999`.
- [ ] **Step 2: интеграционные тесты** (заменяют старые тесты ленты:
  `feed command with posts should show posts`, `…date…`, `…labels…`, `next_posts…`,
  `previous_posts…`, `archive command…`, `favorites command…`):
  - `feed sends exactly one card`: 3 поста → ровно один JSON-`sendMessage` (проверить
    `await().during(Duration.ofMillis(500))`), `"parse_mode":"HTML"`, текст содержит ссылку
    на самый новый пост, `link_preview_options.url` = его url, кнопка `1 / 3`.
  - `next arrow edits the same message`: callback `cn|u:1:<id>:u` с `message_id=77` →
    `editMessageText` с `"message_id":77`, текст второго поста, `2 / 3`; `sendMessage` — 0.
  - `feed without posts says so in one message` (ключ `command.feed.not_found_post`).
  - `archive command shows archive card`, `favorites command shows favorites card`.
- [ ] **Step 3:** `$TEST --tests '*TelegramBotIntegrationTest' --tests '*TelegramKeyboardTest'`
  → новые тесты падают.
- [ ] **Step 4:** Реализовать `PostCardService.sendCard`, `onCallback` для `cn`/`c0`,
  `TextCardPresenter`. Отрисовка: `renderer.render` → `TelegramBotApi.sendHtml/editHtml`
  с `LinkPreview(post.url)`. `CallbackQueryHandler` разбирает новые действия через
  `parseCardCallback`. Старые ветки ленты и `buildNavigationKeyboard` пока остаются: их
  уберут задачи 6–8.
- [ ] **Step 5:** `$TEST` → всё зелёное.
- [ ] **Step 6: Commit** `feat(bot): лента одной карточкой с листанием`.

### Task 6: действия с постом в карточке

**Files:**
- Modify: `…/service/telegram/card/PostCardService.kt`
- Modify: `…/service/telegram/CallbackQueryHandler.kt` (ответ на каждое нажатие, legacy)
- Modify: `…/service/telegram/TelegramKeyboard.kt` (клавиатура подтверждения)
- Modify: `…/service/TelegramService.kt` (удалить `toArchivePost`, `toUnreadPost`,
  `toDeletePost`, `favoritesTo*`, `toggleFavorite*`)
- Modify: `src/main/resources/messages/messages*.properties`
- Test: `…/telegram/TelegramBotIntegrationTest.kt`

**Interfaces:**
- Consumes: всё из задачи 5.
- Produces:
  - `CallbackQueryHandler` вызывает `bot.answerCallbackQuery(callbackQuery.id, answer.text)`
    в `finally` — ровно один раз на любой апдейт, в том числе при исключении и неизвестном
    действии.
  - Новые ключи i18n: `card.toast.gone`, `card.toast.outdated`, `card.all_read`,
    `card.delete.confirm`, `card.delete.cancel`, `card.button.archive`, `card.button.unread`.
- Правила (из дизайна): архив/удаление → тот же `offset`, при выходе за конец —
  `offset - 1`, при пустом списке — `CardView.Empty` без клавиатуры. `cd` → клавиатура
  `[Точно удалить? (cy)] [Отмена (cx)]`, `cx` → обычная клавиатура. `cf` → только
  `redrawKeyboard`. В FAVORITES `ca`/`cu` переносят пост и перерисовывают карточку с новым
  id и `loc`, без перечитывания по `offset`; снятие ★ оставляет ☆.
- Старая карточка: если `postService.getPost(ref.postId, user.id, ref.loc.postType) == null`
  (или перенос проиграл гонку и бросил `ConcurrentPostMoveException`), ответ — тост
  `card.toast.gone` и перерисовка на `ref.offset` с ограничением по концу списка.

- [ ] **Step 1: интеграционные тесты** (заменяют `archive_button…`, `delete_button…`,
  `unread_button…`, `delete_archive_button…`, `toggle_*_favorite…`, `favorites_to_*…`,
  `toggle_favorites_unread_favorite…`):
  - `archive shows the next post at the same offset`: P3,P2,P1, карточка offset 1 (P2), `ca` → P2 в `archive_post`; `editMessageText` с P1 и `2 / 2`.
  - `archive of the last post steps back`: offset 2 из 3 → показан пост offset 1, `2 / 2`.
  - `archiving the only post shows all read without buttons`: текст `card.all_read`, в теле нет `reply_markup` с кнопками.
  - `delete asks first`: `cd` → `editMessageReplyMarkup` с `cy|` и `cx|`; пост в БД есть.
  - `confirmed delete removes the post for good`: `cy` → строки нет ни в `post`, ни в `archive_post`; показан следующий.
  - `cancel delete restores the card buttons`.
  - `star redraws only the keyboard`: `cf` → `editMessageReplyMarkup`, `editMessageText` — 0; `is_favorite = true`.
  - `favorites archive keeps the card with the new id`: в клавиатуре `cu|f:0:<archiveId>:a`.
  - `unstar in favorites keeps the card`: ☆ в клавиатуре, `deleteMessage` — 0.
  - `stale card toasts and redraws`: пост удалён SQL'ем, `ca` по нему →
    `answerCallbackQuery` с `card.toast.gone` (EN), затем `editMessageText` с текущим постом на том же offset.
  - `double tap archives once`: два одинаковых `ca` подряд → в `archive_post` одна строка, оба нажатия отвечены.
  - `every press is answered`: `cn`, `cf`, `cd`, `c0` → по одному `answerCallbackQuery` с соответствующим `callback_query_id`.
  - `legacy buttons get an outdated toast` (Review Focus 4): `archive_button|<id>`,
    `next_posts|3`, `profile_feed_settings|_` → тост `card.toast.outdated`; пост на месте.
  - `garbage card data is answered`: `ca|zz` → ответ без текста, без исключения в логе уровня `error`.
- [ ] **Step 2:** `$TEST --tests '*TelegramBotIntegrationTest'` → новые тесты падают.
- [ ] **Step 3:** Реализовать действия в `PostCardService.onCallback`. Неизвестное
  действие и `null` из `parseCardCallback` превращаются в `CallbackAnswer(outdated)` в
  `CallbackQueryHandler`, а не в `RuntimeException`. Удалить callback-константы, которые
  заменили `ca`/`cu`/`cd`/`cf` (PDF и метки уберёт задача 7), и методы `TelegramService`
  по списку Files.
- [ ] **Step 4:** `$TEST` → всё зелёное.
- [ ] **Step 5: Commit** `feat(bot): действия с постом в карточке`.

### Task 7: «⋯» — PDF и метки внутри карточки

**Files:**
- Modify: `…/service/telegram/card/PostCardService.kt` (`cm`, `cp`, `ce`, `lp`, `lt`, `ln`, `lb`)
- Modify: `…/service/telegram/TelegramKeyboard.kt` (второй ряд, выбор движка, пикер меток
  на `InlineKeyboard` и `CardRef`; удалить `LabelCallbackContext`, `buildPdfEngineKeyboard`,
  `buildFeedPostInlineKeyboard`)
- Modify: `…/service/TelegramService.kt` (`showPostLabels`/`togglePostLabel`/
  `promptNewLabel`/`hidePostLabels` переезжают в `PostCardService`; `sendPostPdf` получает
  `CardRef`, а ответы «занято/лимит» превращаются в тост)
- Modify: `…/service/telegram/PendingLabelInput.kt` (вместо строки контекста хранит `CardRef`)
- Modify: `.claude/rules/telegram.md` (раздел Labels: `CardRef` вместо `LabelCallbackContext`)
- Test: `…/telegram/TelegramBotIntegrationTest.kt`, `…/service/telegram/TelegramKeyboardTest.kt`

**Interfaces:**
- Consumes: `CardRef`, `cardCallback`, `CardView.Post.expanded`, `CardPresenter.redrawKeyboard`.
- Produces:
  - `cm` с `extra = "1"` раскрывает ряд `[📄 PDF (cp)] [🏷 Метки (lp)]` (третью кнопку
    добавит задача 12), `extra = "0"` сворачивает.
  - `cp`: один движок → задача в `BotTaskExecutor` и тост `card.toast.pdf_started`; несколько →
    клавиатура карточки заменяется кнопками `ce|<ref>:<engine>` и «Назад» (`cm`, `extra = "0"`).
    Лимит и занятость → тост `command.rate_limited` / `command.busy`. Сам PDF приходит новым
    сообщением-документом: это файл, не действие с карточкой.
  - Пикер меток: `lt|<ref>:<labelId>`, `ln|<ref>`, `lb|<ref>`. `lb` возвращает клавиатуру
    карточки с тем же `ref.offset` (для `ref.widget = true` — клавиатуру под виджетом,
    задача 15).
  - `PendingLabelInput.await(chatId: Long, ref: CardRef)`, `take(chatId): CardRef?`.

- [ ] **Step 1: тесты**
  - `more opens and closes the second row`: `cm…:1` → в `editMessageReplyMarkup` есть `cp|` и `lp|`; `cm…:0` → нет.
  - `labels back returns to the same offset`: `lb|f:2:<id>:a` → клавиатура с `3 / 3` и кнопками ref offset 2.
  - Перенести `post_labels…`, `post_label_toggle…`, `after the new-label prompt…`,
    `an empty label list…` на коды `lp`/`lt`/`ln`.
  - `pdf from card answers with a toast`: `cp` → `answerCallbackQuery` с `card.toast.pdf_started`; `sendMessage` про PDF — 0.
  - Unit `TelegramKeyboardTest`: `engine choice keyboard carries ce and back` для двух движков; 64-байтовая проверка на `ce|…:playwright`.
- [ ] **Step 2:** `$TEST --tests '*TelegramBotIntegrationTest' --tests '*TelegramKeyboardTest'` → новые тесты падают.
- [ ] **Step 3:** Реализовать по Interfaces. `CommandHandler.handleCommonText` по-прежнему
  спрашивает `consumeLabelName` до `addPost`.
- [ ] **Step 4:** `$TEST` → всё зелёное.
- [ ] **Step 5: Commit** `feat(bot): PDF и метки под «⋯» в карточке`.

### Task 8: случайный пост, ответ на ссылку и уборка настройки ленты

**Files:**
- Modify: `…/service/telegram/card/PostCardService.kt` (`RANDOM`, `SAVED`)
- Modify: `…/service/TelegramService.kt` (`addPost` → карточка `SAVED`; удалить `getFeed`,
  `getRandomPost`, `getFeedSettings`, `updateUserSettingsFeedCount`, `NAVIGATION_TEXT_BUTTON`,
  `DEFAULT_POST_COUNT`, `PostFeedItem`, `buildPostMessage`, `escapeTextMarkdown2`)
- Modify: `…/service/telegram/TelegramKeyboard.kt` (удалить `buildRandomPostInlineKeyboard`,
  `buildNavigationKeyboard`, `buildSettingsFeedKeyboard`, кнопку «Настройки ленты» в профиле)
- Modify: `…/service/telegram/CallbackQueryHandler.kt`, `CommandHandler.kt`
- Modify: `…/model/User.kt` (`tgFeedEntriesNumber: Int = 5` — поле остаётся ради старых
  строк и записи без потерь, новый код его не читает), `…/model/UserSettingsLimits.kt`
  (удалить `FEED_ENTRIES_RANGE`), `…/service/UserService.kt`
- Modify: `…/controller/ProfileApiController.kt` (убрать `tgFeedEntriesNumber` из
  `SettingsPatchRequest` и валидацию)
- Modify: `src/main/resources/templates/profile.html` (убрать строку «Ссылок в ленте бота»)
- Modify: `src/main/resources/messages/messages*.properties` (удалить ключи заголовка ленты,
  навигации, настроек ленты, `web.profile.feed.size*`; добавить `card.button.random_more`,
  `card.saved.added`, `card.saved.already`, `card.saved.archived`, `card.saved.deleted`)
- Modify: `AGENTS.md` (раздел про бота и `settings`: ключ `tgFeedEntriesNumber` legacy)
- Test: `…/telegram/TelegramBotIntegrationTest.kt`, `…/controller/ProfileApiControllerTest.kt`,
  `…/controller/WebControllerTest.kt`

**Interfaces:**
- Consumes: `PostCardService`, `CardList.RANDOM`, `CardList.SAVED`.
- Produces:
  - `RANDOM`: пост — `postService.getRandomPost`; нижний ряд `[🎲 Ещё (cr)]` вместо навигации;
    архив/удаление/`cr` показывают следующий случайный; пусто → `command.random_post.not_found`.
  - `SAVED`: ответ на ссылку — **одно** сообщение-реплай с текстом `card.saved.added`
    (или `card.saved.already`) и HTML-карточкой поста. Клавиатура как у карточки, но без ряда
    навигации. Архив и удаление меняют текст на `card.saved.archived` / `card.saved.deleted`
    без кнопок (`CardView.Status`). Это решение исполнителя; см. вопрос 3 в конце плана.

- [ ] **Step 1: тесты**
  - `random_post sends one card with a dice button`, `dice edits the same message`,
    `archive on random shows another random`.
  - Заменить `url message should …`: `saved link reply is a card without paging` —
    один `sendMessage` с `reply_parameters`, кнопка `ca|s:0:<id>:u`, без `1 / N`.
  - `archive from the saved reply turns it into a status line`.
  - `profile no longer offers feed size`: `/profile` → в клавиатуре нет `profile_feed_settings`.
  - `ProfileApiControllerTest`: убрать тесты диапазона; `feed size in the body is ignored` —
    `{"tgFeedEntriesNumber":3}` → 200, сохранённое значение не изменилось.
  - `WebControllerTest`: `profile page has no feed size row` — нет `feed-size-btns`.
- [ ] **Step 2:** `$TEST` → новые тесты падают.
- [ ] **Step 3:** Реализовать и удалить мёртвый код по списку Files.
  `grep -rn "tgFeedEntriesNumber" src/main` — остаются только `User.kt` и миграция `2_…sql`.
- [ ] **Step 4:** `$TEST` → всё зелёное.
- [ ] **Step 5: Commit** `feat(bot): случайный пост и ответ на ссылку карточкой; без настройки размера ленты`.

---

## Шаг 3. Mini App

### Task 9: проверка initData

**Files:**
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/miniapp/InitDataValidator.kt`
- Test: `src/test/kotlin/ru/proshik/pochitushki/service/miniapp/InitDataValidatorTest.kt` (unit)

**Interfaces:**
- Produces:
  ```kotlin
  data class MiniAppUser(val id: Long, val firstName: String?, val username: String?, val languageCode: String?)
  data class MiniAppInitData(val user: MiniAppUser, val authDate: Instant, val startParam: String?)
  class InitDataException(val reason: Reason) : RuntimeException(reason.name) {
      enum class Reason { MALFORMED, MISSING_HASH, INVALID_HASH, EXPIRED, NO_USER } }
  object InitData {
      val MAX_AGE: Duration = Duration.ofHours(24)
      fun validate(raw: String, botToken: String, now: Instant, maxAge: Duration = MAX_AGE): MiniAppInitData
  }
  ```
  Разбор: `&`-пары, ключ и значение декодируются `URLDecoder.decode(…, UTF_8)` ровно один
  раз; повтор ключа → `MALFORMED`. Подпись проверяется до свежести. Сравнение —
  `MessageDigest.isEqual` по байтам hex-декодированного `hash`. Нечитаемый `hash` →
  `INVALID_HASH`. `user` разбирается Jackson'ом.

- [ ] **Step 1: тесты** (вектор и токен из `pacer/pkg/miniapp/initdata_test.go`, его же
  `now = 1662771648 + 60 s`):
  - `accepts the published example`: user id `279058397`, `username = "vdkfrost"`, `languageCode = "ru"`, `authDate = 1662771648`.
  - `rejects another bot's token` → `INVALID_HASH`.
  - `rejects a tampered field` (`279058397` → `279058398`) → `INVALID_HASH`.
  - `rejects data older than 24h` → `EXPIRED`.
  - `rejects missing hash` → `MISSING_HASH`; `rejects a duplicated key` → `MALFORMED`;
    `rejects a non-hex hash` → `INVALID_HASH`; `rejects data without user` (подписать в
    тесте без `user`) → `NO_USER`.
  - `signature takes part in the check`: подписать в тесте набор с полем `signature` →
    принимается; удалить `signature` из строки → `INVALID_HASH`.
  - `start_param is returned`: подписанный в тесте набор со `start_param=archive`.
- [ ] **Step 2:** `$TEST --tests '*InitDataValidatorTest'` → падают на компиляции.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** `$TEST --tests '*InitDataValidatorTest'` → PASS.
- [ ] **Step 5: Commit** `feat(auth): проверка initData Mini App`.

### Task 10: вход в Mini App по initData

**Files:**
- Create: `src/main/kotlin/ru/proshik/pochitushki/controller/MiniAppController.kt`
- Create: `src/main/resources/templates/tg.html`
- Create: `src/main/resources/static/js/tg-login.js`
- Modify: `…/service/JwtService.kt` (claim `tma`)
- Modify: `…/configuration/JwtAuthInterceptor.kt` (атрибут `MINI_APP`)
- Modify: `…/controller/AuthController.kt` (вынести `authCookie`/`addCookie` в общий
  `AuthCookies`-компонент, чтобы `MiniAppController` выставлял ту же куку)
- Modify: `src/main/resources/messages/messages*.properties` (`web.tg.loading`, `web.tg.open_in_browser`, `web.tg.error`)
- Modify: `.claude/rules/security.md` (новый раздел «Mini App»: алгоритм, 24 ч, claim
  `tma`, что `/tg` и `/auth/telegram/webapp*` открыты; убрать фразу «HMAC-подписи
  `WebAppData` … добавлять её не нужно»)
- Test: `src/test/kotlin/ru/proshik/pochitushki/controller/MiniAppControllerTest.kt`,
  `…/service/JwtServiceTest.kt`

**Interfaces:**
- Consumes: `InitData.validate` (задача 9), `UserService.getOrCreateUser(telegramId, firstName, username, languageCode)`.
- Produces:
  - `JwtService.createToken(userId: Long, miniApp: Boolean = false): String`;
    `TokenInfo(userId, issuedAt, miniApp: Boolean)` (нет claim → `false`).
  - `JwtAuthInterceptor.MINI_APP = "miniApp"` — request-атрибут `Boolean`.
  - `GET /tg` → `tg.html` (без топбара; подключает `telegram-web-app.js?65`, `theme-init.js`,
    `tg-login.js`). Страница публичная, в `WebConfig` её нет.
  - `POST /auth/telegram/webapp` с JSON `{"initData": String, "page": String?}` →
    `200 {"redirect": "/" | "/archive" | "/favorites" | "/random"}` + `Set-Cookie: auth_token`
    (JWT с `tma=true`); `401`, если initData не прошла проверку. Целевая страница берётся из
    `page` (query `?p=` у кнопок бота) или из `start_param`, только из белого списка;
    остальное ведёт на `/`.
  - `GET /auth/telegram/webapp/session` → `204`, если кука `auth_token` дошла и валидна,
    иначе `401`. По нему `tg-login.js` после логина понимает, что cookie не доходит
    (Telegram Web во фрейме), и показывает «Откройте в браузере» со ссылкой
    `Telegram.WebApp.openLink(location.origin + '/')`.
  - Токен бота для проверки — `TelegramProperties.token`; при пустом токене эндпоинт отвечает `401`.

- [ ] **Step 1: тесты `MiniAppControllerTest`** (MockMvc, `BaseIntegrationTest`; initData
  подписывается в тесте токеном `some_token` из `application-test.yml` с текущим `auth_date`):
  - `valid init data logs in and sets the cookie`: 200, `redirect = "/"`, `Set-Cookie`
    `auth_token` с `HttpOnly`, `SameSite=Lax`; `jwtService.parseToken(...)!!.miniApp == true`;
    пользователь с `telegram_id` из initData существует.
  - `existing bot user is reused`: пользователь, созданный `/start`, получает тот же `users.id`.
  - `bad signature is refused`: 401, `Set-Cookie` нет.
  - `stale init data is refused`: `auth_date` 25 ч назад → 401.
  - `page whitelist`: `page = "archive"` → `/archive`; `page = "//evil.com"` → `/`;
    `start_param=favorites` → `/favorites`.
  - `cross-origin post is rejected`: `Origin: https://evil.example` → 403 (`CsrfOriginFilter`).
  - `session probe`: без куки → 401, с кукой → 204.
  - `tg page is public`: `GET /tg` без куки → 200, а не редирект на `/login`.
- [ ] **Step 2: `JwtServiceTest`:** `mini app claim round trips`, `regular token is not mini app`.
- [ ] **Step 3:** `$TEST --tests '*MiniAppControllerTest' --tests '*JwtServiceTest'` → падают.
- [ ] **Step 4:** Реализовать. `tg-login.js` — без inline-обработчиков; тексты берёт из
  `data-*` скрытого `<span>`, как `#app-i18n` в `layout.html`.
- [ ] **Step 5:** `$TEST` → всё зелёное.
- [ ] **Step 6: Commit** `feat(auth): вход в Mini App по initData`.

### Task 11: режим Mini App — заголовки, скрипт Telegram, адаптация app.js

**Files:**
- Modify: `…/configuration/SecurityHeadersFilter.kt` (инжектит `JwtService`)
- Modify: `src/main/resources/templates/layout.html` (скрипт Telegram в `head`)
- Modify: `src/main/resources/templates/profile.html` (форма «Выйти» `th:unless="${miniApp}"`)
- Modify: `src/main/resources/static/js/app.js`
- Test: `src/test/kotlin/ru/proshik/pochitushki/configuration/SecurityHeadersFilterTest.kt`,
  `…/controller/WebControllerTest.kt`

**Interfaces:**
- Consumes: `TokenInfo.miniApp` (задача 10), атрибут `miniApp`.
- Produces:
  - Режим включён, если путь `/tg` **или** кука `auth_token` парсится в токен с `miniApp = true`.
    В режиме CSP = прежний список, где `script-src 'self' https://telegram.org` и
    `frame-ancestors https://web.telegram.org`, а `X-Frame-Options` не выставляется. Вне
    режима — прежние заголовки без изменений.
  - `layout.html`: `<script th:if="${miniApp}" src="https://telegram.org/js/telegram-web-app.js?65"></script>`
    первым скриптом в `head`.
  - `app.js`, блок `initMiniApp()` — только если `window.Telegram && Telegram.WebApp && Telegram.WebApp.initData`:
    `ready()`, `expand()`; класс `dark` на `<html>` по `colorScheme` и на `themeChanged`;
    `BackButton.show()` на путях кроме `/`, `onClick` → `history.back()` (или `/`, если
    истории нет); делегированный клик по `a[href]` с чужим origin → `preventDefault()` и
    `Telegram.WebApp.openLink(href)`.
  - «Выйти» скрывается на сервере (`th:unless`), а не в `app.js`. Атрибут на сервере уже
    есть, и так кнопка не мелькает. Решение исполнителя.

- [ ] **Step 1: тесты**
  - `SecurityHeadersFilterTest`: `regular page keeps strict headers` — `GET /login`:
    CSP содержит `frame-ancestors 'none'` и не содержит `telegram.org`, `X-Frame-Options: DENY`;
    `tg page allows telegram` — `GET /tg`: `script-src 'self' https://telegram.org`,
    `frame-ancestors https://web.telegram.org`, нет `X-Frame-Options`;
    `mini app cookie allows telegram on the feed` — `GET /` с JWT `miniApp=true`;
    `regular cookie keeps strict headers on the feed` — `GET /` с обычным JWT;
    `forged cookie keeps strict headers` — мусор в `auth_token`.
  - `WebControllerTest`: `mini app feed includes telegram script` / `browser feed does not`;
    `profile hides logout in mini app`.
- [ ] **Step 2:** `$TEST --tests '*SecurityHeadersFilterTest' --tests '*WebControllerTest'` → падают.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** `$TEST` → всё зелёное.
- [ ] **Step 5: Commit** `feat(web): режим Mini App — заголовки, скрипт Telegram и адаптация app.js`.

### Task 12: точки входа в Mini App из бота

**Files:**
- Modify: `…/configuration/properties/TelegramProperties.kt` (`miniAppUrl: String? = null`)
- Modify: `src/main/resources/application.yml` (`telegram.mini-app-url: ${APP_BASE_URL:}/tg`)
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/MiniAppLinks.kt`
- Modify: `…/configuration/TelegramBotConfiguration.kt` (после старта — `MiniAppLinks.installMenuButton(bot)`)
- Modify: `…/service/telegram/TelegramKeyboard.kt` (третья кнопка в ряду «⋯»)
- Modify: `…/service/telegram/CommandHandler.kt` (`/start` — inline-кнопка)
- Modify: `src/main/resources/messages/messages*.properties` (`card.button.app`, `bot.menu.app`)
- Modify: `src/test/resources/application-telegram-test.yml` (`mini-app-url: https://app.test/tg`)
- Modify: `AGENTS.md` (Required env vars: Mini App URL выводится из `APP_BASE_URL`;
  в BotFather адрес главной Mini App — `${APP_BASE_URL}/tg`)
- Test: `…/telegram/TelegramBotIntegrationTest.kt`, `…/service/telegram/MiniAppLinksTest.kt` (unit)

**Interfaces:**
- Consumes: `CardList`, `InlineButton(webApp = WebAppRef(url))`.
- Produces:
  ```kotlin
  @Component class MiniAppLinks(props: TelegramProperties, i18n: I18nService) {
      fun url(list: CardList? = null): String?   // null, если miniAppUrl не начинается с "https://";
                                                // ARCHIVE → "?p=archive", FAVORITES → "?p=favorites", остальное — без параметра
      fun installMenuButton(bot: Bot)            // setChatMenuButton(menuButton = MenuButton.WebApp(text, WebAppInfo(url)));
                                                // ошибка → warn, старт не роняет
  }
  ```

- [ ] **Step 1: тесты**
  - `MiniAppLinksTest`: `https url gives links per list`; `http url gives no link`
    (`http://localhost:8080/tg` → `null`, Review Focus 3); `blank url gives no link`.
  - `more row opens the app`: `cm…:1` для архива → кнопка с `"web_app":{"url":"https://app.test/tg?p=archive"}`.
  - `start offers the app button`: тело `sendMessage` на `/start` содержит `web_app` с `https://app.test/tg`.
  - `saved reply offers the app under more`.
  - `menu button is installed`: вызвать `installMenuButton(botProvider.getBot())` после
    стабов → `setChatMenuButton` с `menu_button`, где `"type":"web_app"` и url.
  - `no https url — no web_app anywhere`: отдельный тестовый класс или
    `@TestPropertySource(properties = ["telegram.mini-app-url=http://localhost/tg"])`:
    `/start` и сохранение ссылки шлют сообщения без `web_app`.
- [ ] **Step 2:** `$TEST --tests '*TelegramBotIntegrationTest' --tests '*MiniAppLinksTest'` → падают.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** `$TEST` → всё зелёное.
- [ ] **Step 5: Commit** `feat(bot): точки входа в Mini App`.

### Приёмка шагов 2–3 вживую (без коммита, до сдачи шагов)

По разделу дизайна «Живая проверка»: тестовый бот, токен в `.env.dev`, туннель, webhook на
туннель, главная Mini App в BotFather — `https://<туннель>/tg`. Пройти: листание, архив,
удаление с подтверждением, ★, «⋯», метки (и «Назад» на тот же offset), PDF, старая
карточка (удалить пост на сайте и нажать кнопку), кнопку старой версии, вход в Mini App без
логина, тему и её смену, `BackButton`, `openLink`, консоль без ошибок CSP. Затем
`curl -sI https://<туннель>/login`: `frame-ancestors 'none'`, без `telegram.org`. Для
UI — изолированное окно браузера. Найденное чинится в задаче, которой принадлежит код,
отдельным коммитом `fix(...)`.

---

## Шаг 4. Виджеты под флагом

### Task 13: флаг, режим и клиент методов виджетов

**Files:**
- Modify: `…/configuration/properties/TelegramProperties.kt` (`widgets: Widgets = Widgets()`,
  `data class Widgets(val enabled: Boolean = false)`)
- Modify: `src/main/resources/application.yml` (`telegram.widgets.enabled: ${TELEGRAM_WIDGETS_ENABLED:false}`)
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/widget/WidgetMode.kt`
- Modify: `…/configuration/TelegramBotConfiguration.kt` (`allowedUpdates`, warn в polling)
- Modify: `…/service/telegram/api/TelegramBotApiClient.kt`, `TelegramBotApi.kt`
- Modify: `src/test/resources/application-test.yml` (`telegram.webhook-url: ""`) и
  `src/test/kotlin/ru/proshik/pochitushki/BaseIntegrationTest.kt` (убрать динамический
  override `telegram.webhook-url`, чтобы webhook-профиль из задачи 15 мог его задать)
- Modify: `AGENTS.md` (Required env vars: `TELEGRAM_WIDGETS_ENABLED`)
- Test: `…/configuration/TelegramBotConfigurationTest.kt` (unit), `…/service/telegram/api/TelegramBotApiTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Component class WidgetMode(props: TelegramProperties) {
      val active: Boolean   // widgets.enabled && !webhookUrl.isNullOrBlank()
  }
  // TelegramBotConfiguration.Companion:
  fun allowedUpdates(widgetsActive: Boolean): List<String>
      // ["message","callback_query"] + ["widget_query","shared_widget_query"]
  // Feign:
  fun sendWidget(@RequestBody r: SendWidgetRequest): TgResponse<JsonNode>
      // SendWidgetRequest(chat_id, html, chat_data, allow_queries = true, reply_markup: InlineKeyboard?, reply_parameters?)
  fun editMessageWidgetData(@RequestBody r: EditWidgetDataRequest): TgResponse<JsonNode>   // (chat_id, message_id, chat_data)
  fun answerWidgetQuery(@RequestBody r: AnswerWidgetQueryRequest): TgResponse<JsonNode>    // (widget_query_id, data?)
  // TelegramBotApi:
  fun sendWidget(chatId: Long, html: String, chatData: String, keyboard: InlineKeyboard?, replyTo: Long? = null): Long?
  fun editWidgetData(chatId: Long, messageId: Long, chatData: String): Boolean
  fun answerWidgetQuery(queryId: String, data: String?): Boolean
  ```

- [ ] **Step 1: тесты**
  - `allowed updates without widgets` == `["message","callback_query"]`; `with widgets` — плюс оба типа.
  - `WidgetMode`: `enabled + webhook → active`, `enabled + polling → inactive`, `disabled → inactive`.
  - `polling with widgets enabled logs a warning` (`OutputCaptureExtension`, конфигурация
    собирается в тесте с `enabled=true`, пустым `webhookUrl`).
  - `TelegramBotApiTest`: `sendWidget asks for queries` — тело содержит `"allow_queries":true`,
    `"chat_data"`; `sendWidget refusal returns null` — стаб `400 {"ok":false,"description":"…"}` → `null`.
- [ ] **Step 2:** `$TEST --tests '*TelegramBotConfigurationTest' --tests '*TelegramBotApiTest'` → падают.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** `$TEST` → всё зелёное (перенос `webhook-url` в yml ничего не ломает).
- [ ] **Step 5: Commit** `feat(bot): флаг виджетов и клиент их методов`.

### Task 14: шаблон виджета и упаковка chat_data

**Files:**
- Create: `src/main/resources/telegram/widget/post-card.html` (не в `templates/` и не в
  `static/`: шаблон не Thymeleaf и не должен раздаваться сайтом)
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/widget/WidgetTemplate.kt`
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/widget/WidgetChatData.kt`
- Test: `src/test/kotlin/ru/proshik/pochitushki/service/telegram/widget/WidgetTemplateTest.kt`,
  `…/widget/WidgetChatDataTest.kt` (unit)

**Interfaces:**
- Consumes: `CardView`, `CardRef.encode`, `CoverService.decorate(post, showOgCovers = false)`, `I18nService`.
- Produces:
  ```kotlin
  @Component class WidgetTemplate { val html: String }      // читается из classpath один раз
  @Component class WidgetChatData(coverService: CoverService, i18n: I18nService) {
      fun encode(view: CardView, languageCode: String): String   // компактный JSON, ≤ 1024 байт UTF-8
      companion object { const val MAX_BYTES = 1024; const val MAX_OPEN_LINK = 256 }
  }
  ```
  - Ключи `chat_data` (короткие ради бюджета): `r` — `ref.encode()` (`widget = true`),
    `t` — заголовок, `u` — url, только если ≤ 256 символов, `d` — домен, `a` — дата,
    `l` — метки, `f` — избранное, `n`/`o` — всего/позиция, `p`/`c`/`b` — палитра,
    композиция, аббревиатура, `x` — подтверждение удаления, `e` — пусто, `s` — строки UI
    из `I18nService` (подтверждение, отмена, «всё прочитано»).
  - Ужимание при превышении бюджета: заголовок → метки по одной с конца → `u`.
  - Шаблон: inline `<style>` и `<script>`, системные шрифты, `--tg-theme-*`; палитра —
    копия 12 пар `cv-0..cv-11` из `app.css`; композиции `co-mono|co-band|co-frame`.
    Кнопки зовут `TelegramWidget.request(action + "|" + r)`; открытие статьи —
    `TelegramWidget.openLink(u)`; перерисовка — на `chatDataChanged`; тост из ответа
    `request` (`result`).

- [ ] **Step 1: тесты**
  - `WidgetTemplateTest`: `fits raw size` — `html.toByteArray(UTF_8).size ≤ 32768`;
    `fits compressed size` — `Deflater(9)`, сжатый размер ≤ 4096;
    `loads nothing external` — нет `src=`, `href=`, `url(`, `@import`, `@font-face`, `fetch(`;
    нет `\u0000`;
    `palette matches the site` — для каждого `cv-0..cv-11` пара цветов в шаблоне совпадает
    с `app.css` (разбор регуляркой `\.cv-(\d+)\s*\{ --cv-bg: (#\w+); --cv-fg: (#\w+); }`).
  - `WidgetChatDataTest`: `worst case fits 1024 bytes` — кириллический заголовок 2000
    символов, 20 меток по 50 символов, url 256 символов → ≤ 1024 байт, `r` на месте (Review Focus 5);
    `long url is left out` — url 300 символов → нет ключа `u`; `request data fits 64 bytes` —
    для каждого действия виджета `action|r` ≤ 64 байт при `Long.MAX_VALUE` и offset 999 999;
    `empty view` → `{"e":1,…}`.
- [ ] **Step 2:** `$TEST --tests '*widget*'` → падают.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** `$TEST --tests '*widget*'` → PASS.
- [ ] **Step 5: Commit** `feat(bot): шаблон виджета карточки и упаковка chat_data`.

### Task 15: карточка-виджет и обработка widget_query

**Files:**
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/widget/WidgetCardPresenter.kt`
- Create: `src/main/kotlin/ru/proshik/pochitushki/service/telegram/widget/WidgetQueryHandler.kt`
- Modify: `…/service/telegram/card/PostCardService.kt` (выбор презентера, откат на текст)
- Modify: `…/controller/TelegramController.kt` (перехват до `processUpdate`)
- Modify: `…/service/telegram/TelegramKeyboard.kt` (клавиатура под виджетом)
- Create: `src/test/resources/application-telegram-widgets-test.yml`
- Test: `src/test/kotlin/ru/proshik/pochitushki/telegram/TelegramWidgetIntegrationTest.kt`

**Interfaces:**
- Consumes: `WidgetMode.active`, `TelegramBotApi.sendWidget/editWidgetData/answerWidgetQuery`
  (задача 13), `WidgetTemplate`, `WidgetChatData` (задача 14), `PostCardService.onCallback`,
  `CardPresenter` (задача 5).
- Produces:
  - `WidgetCardPresenter : CardPresenter`: `send` → `sendWidget(html, chatData, keyboard)`;
    `redraw` → `editWidgetData`, а при смене поста ещё `editMessageReplyMarkup`;
    `redrawKeyboard` → клавиатура под виджетом.
  - Клавиатура под виджетом: `[📄 PDF (cp)] [🏷 Метки (lp)] [📱 Открыть в приложении]`
    и `[🔗 Открыть статью (url)]`, если url длиннее 256. Ref везде с `widget = true`.
  - `PostCardService.sendCard`: при `widgetMode.active` сначала виджет; если вернулся
    `null` (отказ или любое исключение) — `warn` со статусом/description и та же карточка
    текстом. Пользователь всегда получает ответ.
  - `TelegramController`: после проверки секрета и дедупликации, если `widgetMode.active`
    и в JSON есть `widget_query` → `widgetQueryHandler.handle(node)`, есть
    `shared_widget_query` → `answerWidgetQuery(id, null)`; иначе — `processUpdate` как сейчас.
  - `WidgetQueryHandler.handle(node: JsonNode)`: нет `chat`/`message_id` (inline) или
    `parseCardCallback` вернул `null` → ответ без данных. Иначе
    `onCallback(chatId, messageId, action, ref, extra)` и
    `answerWidgetQuery(id, answer.text)` — всегда в `finally`.

- [ ] **Step 1: профиль и класс теста.** `application-telegram-widgets-test.yml`:
  `telegram.widgets.enabled: true`, `telegram.webhook-secret: test-secret`. В
  `TelegramWidgetIntegrationTest` — собственный `@DynamicPropertySource`, который задаёт
  `telegram.webhook-url=https://example.test/test_token` и **в нём же** регистрирует стабы
  `setWebhook` (`{"ok":true,"result":true}`): этот метод выполняется непосредственно перед
  стартом контекста, после `resetAll()` других классов. Апдейты шлются с заголовком
  `X-Telegram-Bot-Api-Secret-Token: test-secret`.
- [ ] **Step 2: тесты**
  - `webhook asks for widget updates`: в теле `setWebhook` есть `widget_query` и `shared_widget_query`.
  - `feed sends a widget`: `/feed` → `sendWidget` с `"allow_queries":true`, `chat_data`
    с `r` = `U:0:<id>:u`, `reply_markup` с `cp|U:…`; текстового `sendMessage` нет.
  - `widget refusal falls back to the text card`: стаб `sendWidget` →
    `400 {"ok":false,"description":"Bad Request: WIDGETS_NOT_ALLOWED"}` → один JSON-`sendMessage`
    с HTML-карточкой; в логе `warn` без токена.
  - `widget archive updates data and answers`: update `{"widget_query":{"id":"q1","from":…,
    "chat":{"id":100,…},"message_id":88,"chat_instance":"1","type":{"type":"data"},
    "data":"ca|U:0:<id>:u"}}` → пост в архиве, `editMessageWidgetData` с `"message_id":88`,
    `answerWidgetQuery` с `"widget_query_id":"q1"`; dispatcher библиотеки его не видит.
  - `stale widget query answers with the gone toast`: `answerWidgetQuery` с `data`, где
    есть текст `card.toast.gone`.
  - `garbage widget data is still answered`: `data = "zz"` → `answerWidgetQuery` без данных.
  - `shared widget query is answered`.
  - `labels back under a widget restores the widget keyboard`: `lb|U:0:<id>:u` →
    `editMessageReplyMarkup` с кнопками `cp|U:…`, без ряда навигации.
- [ ] **Step 3:** `$TEST --tests '*TelegramWidgetIntegrationTest'` → падают.
- [ ] **Step 4:** Реализовать.
- [ ] **Step 5:** `$TEST` → всё зелёное, в том числе `TelegramBotIntegrationTest` в polling
  (там флаг выключен, карточки текстовые).
- [ ] **Step 6: Commit** `feat(bot): карточка-виджет и обработка widget_query`.

Живая проверка виджетов откладывается до тестового сервера Telegram (раздел дизайна
«Живая проверка»). До тех пор шаг 4 принимается по тестам на WireMock.

---

## Покрытие дизайна

| Раздел дизайна | Задачи |
|---|---|
| 1. Карточка в чате | 3, 4, 5, 6, 7, 8 |
| 2. Mini App: вход, режим, заголовки, `app.js`, точки входа | 9, 10, 11, 12 |
| 3. Виджеты под флагом | 13, 14, 15 |
| 4. Зависимости | 1, 2 |
| 5. Живая проверка | «Приёмка шагов 2–3 вживую»; виджеты — позже |
| Анти-цели: разрастание чата / CSP / молчание при отказе `sendWidget` / старая карточка | 5–8 / 11 / 15 / 6 |

## Решения владельца (2026-11-16)

Места, где дизайн расходился с кодом или первоисточниками, и что по ним решено.

1. **У `editMessageText` в библиотеке нет `link_preview_options`** (ни в `Bot`, ни в
   `ApiClient`, ни в `ApiService`, хотя документация библиотеки утверждает обратное).
   **Решение: Feign с шага 2.** Карточка отрисовывается своим Feign-клиентом (`sendMessage`,
   `editMessageText` с `link_preview_options`, `editMessageReplyMarkup`), задача 4.
2. **Доступность виджетов.** Дата «запрет с 2026-11-13» взята из новостной заметки
   (https://cryptonews.net/news/security/33753830/), а не из документации. В официальной
   документации (Bot API 10.4 от 2026-11-09, `/bots/widgets`) запрета нет: в production
   виджеты работают в личке с ботом. **Решение:** флаг по умолчанию выключен; живая
   проверка виджетов — на тестовом боте, если Telegram их примет, без блокировки задач 13–15.
3. **Ответ на присланную ссылку.** **Решение: карточка.** «Сохранено» и карточка этого
   поста без листания (список `SAVED`); после архива или удаления текст меняется на итог.
4. **`start_param`** приходит только при запуске по ссылке `t.me/<bot>?startapp=…`.
   У кнопки меню и у inline-кнопок `web_app`, которые ставит бот, его нет. Поэтому нужную
   полку бот передаёт параметром `?p=` в URL кнопки, а `start_param` обрабатывается тем же
   белым списком (задача 10). Это в рамках «оставлено исполнителю», но для «Открыть
   в приложении» из архива и избранного это единственный рабочий путь.
5. **`.claude/rules/security.md`** сейчас запрещает HMAC `WebAppData` и проверку
   `auth_date` («добавлять её не нужно»). Утверждённый дизайн их вводит. Задача 10 обновляет
   правило, а не обходит его.
6. **`allow_queries=true`** в `sendWidget` обязателен для `TelegramWidget.request`.
   В дизайне его нет, в плане есть (задача 13). Решения не требует.
7. Мелкие решения исполнителя, принятые в плане: «Выйти» в Mini App скрывается на сервере
   (`th:unless`), а не в `app.js`. Выбор PDF-движка показывается в клавиатуре карточки, а не
   отдельным сообщением (анти-цель «чат разрастается»). Поле `tgFeedEntriesNumber`
   остаётся в `UserSettingsData` с дефолтом и не читается. Маркер Mini App — claim `tma`
   в JWT: его нельзя подделать, в отличие от отдельной cookie.
