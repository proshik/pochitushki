# Pochitushki — Project Instructions

## Overview

Read-it-later сервис на Kotlin + Spring Boot. Пользователи сохраняют ссылки через Telegram-бота.
Веб-интерфейс и аутентификация планируются (реализация в разработке).

## Build & Run

```bash
./gradlew build          # Сборка
./gradlew test           # Тесты (поднимает PostgreSQL через TestContainers)
./gradlew bootJar        # Собрать JAR
docker compose up --build  # Запустить с базой
```

## Architecture

**Слои**: Controller → Service → Repository (DAO) → PostgreSQL
**БД**: PostgreSQL 16, Spring JDBC + NamedParameterJdbcTemplate (без ORM), миграции Liquibase
**Telegram**: поддержка polling и webhook, через kotlin-telegram-bot

### Key packages

```
ru.proshik.pochitushki/
├── controller/          # TelegramController (webhook), FileController (импорт)
├── service/             # Бизнес-логика
├── service/telegram/    # Обработчики команд и callback-кнопок
├── configuration/       # Spring конфигурация Telegram-бота
├── model/               # Data-классы (PostData, UserData)
└── repository/          # DAO (UserDao, PostDao)
```

### Post storage

- Таблица `post` — непрочитанные ссылки
- Таблица `archive_post` — архив (та же структура)
- JSONB-колонка `settings` в таблице `users` для настроек пользователя

## Code Conventions

- Kotlin, Spring Boot 3.5.4, Java 21
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
