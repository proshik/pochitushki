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

## Bot Modes

- Polling для локальной разработки, webhook для продакшна
- Конфигурация через `TelegramBotConfiguration` и `TelegramProperties`
- В тестах Telegram отключён (профиль `test`)
