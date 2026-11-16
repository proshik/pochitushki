---
paths:
  - "src/**/telegram/**"
  - "src/**/*Telegram*.kt"
  - "src/**/*Bot*.kt"
---

# Telegram Bot Rules

## Handler Pattern

- Новые команды: реализовать `TelegramUpdateHandler`
- Команды в `CommandHandler`, callback кнопки в `CallbackQueryHandler`
- Клавиатуры строить через `TelegramKeyboard`

## i18n

- Все тексты ботовых сообщений через `I18nService`
- Ключи сообщений в resource bundle, не хардкодить строки
- Поддерживаемые языки: EN, RU

## Labels

- «🏷 Метки» под «⋯» на карточке подменяет клавиатуру пикером; «◀️ Назад»
  (`lb`) собирает клавиатуру карточки обратно на тот же offset — поэтому кнопки
  пикера несут весь `CardRef` карточки, а не только id поста
- callback_data у Telegram ограничен 64 байтами. Единственный формат —
  `CardRef` (`service/telegram/card/CardRef.kt`): `<действие>|<список>:<offset>:<id>:<таблица>[:extra]`,
  действие — два символа (`CardAction`), список и таблица — по одному. Свободной строки
  туда не класть; разбор — только `parseCardCallback`
- «➕ Новая метка» ждёт следующее текстовое сообщение через `PendingLabelInput` —
  это **in-memory** состояние с TTL 5 минут. После рестарта подсказка теряется и
  текст снова читается как ссылка. Это осознанно: таблица ради состояния на
  десять секунд хуже проблемы
- `CommandHandler.handleCommonText` обязан спрашивать `PostCardService.consumeLabelName` **до**
  `addPost`, иначе имя метки уедет в посты как URL

## Bot Modes

- Polling для локальной разработки, webhook для продакшна
- Конфигурация через `TelegramBotConfiguration` и `TelegramProperties`
- В тестах Telegram отключён (профиль `test`)
- `allowedUpdates` (`TelegramBotConfiguration.allowedUpdates`) в webhook-режиме обязан
  включать `callback_query`, а при активных виджетах ещё `widget_query` и
  `shared_widget_query`: без них
  Telegram не присылает нажатия inline-кнопок, и в проде отваливается всё, что
  на кнопках. В polling такого фильтра нет, поэтому тесты это не ловят
- `telegram.webhook-secret` обязателен в webhook-режиме, иначе приложение не стартует
- `widget_query`/`shared_widget_query` библиотека не разбирает: их перехватывает
  `TelegramController` до `processUpdate` (только при `WidgetMode.active`). Виджеты
  работают только в webhook; в polling — текстовая карточка
- Результат `startWebhook()` проверяется: при `false` библиотека не поднимает
  dispatcher, и каждый входящий запрос навсегда повисает на отправке в канал

## Долгие операции

- Dispatcher библиотеки — **один поток на всех**. PDF, импорт и экспорт уходят в
  `BotTaskExecutor` (ограниченный пул + очередь), иначе один пользователь
  останавливает бота для остальных, а в webhook-режиме Telegram успевает
  переслать тот же апдейт повторно
- Очередь конечная: при отказе пользователю отвечают `command.busy`, а не
  копят задачи молча
- Бюджеты на пользователя — `RateLimitService` (PDF, импорт; в вебе ещё и
  сохранение ссылок)
