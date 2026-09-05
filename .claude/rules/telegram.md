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

- Кнопка «🏷 Метки» на карточке поста подменяет клавиатуру пикером; «◀️ Назад»
  собирает исходную обратно, поэтому в callback_data едет ещё и контекст
  (feed или favorites) — иначе кнопка вернёт не ту клавиатуру
- callback_data у Telegram ограничен 64 байтами, поэтому таблица кодируется одним
  символом (`u`/`a`), контекст — вторым. Формат разбирает
  `TelegramKeyboard.LabelCallbackContext`; свободной строки туда не класть
- «➕ Новая метка» ждёт следующее текстовое сообщение через `PendingLabelInput` —
  это **in-memory** состояние с TTL 5 минут. После рестарта подсказка теряется и
  текст снова читается как ссылка. Это осознанно: таблица ради состояния на
  десять секунд хуже проблемы
- `CommandHandler.handleCommonText` обязан спрашивать `consumeLabelName` **до**
  `addPost`, иначе имя метки уедет в посты как URL

## Bot Modes

- Polling для локальной разработки, webhook для продакшна
- Конфигурация через `TelegramBotConfiguration` и `TelegramProperties`
- В тестах Telegram отключён (профиль `test`)
