---
paths:
  - "src/**/*Dao.kt"
  - "src/**/*Repository*.kt"
  - "src/main/resources/liquibase/**"
---

# Database Rules

## JDBC Conventions

- Использовать `NamedParameterJdbcTemplate` с именованными параметрами (`:paramName`)
- ORM не используется — только ручной SQL
- RowMapper определять как companion object или локальный val

## Schema Changes

- Все изменения схемы — через Liquibase миграции в `src/main/resources/liquibase/scripts/`
- Нумеровать файлы последовательно: `3_description.sql`, `4_description.sql`
- Добавлять файл в `changelog-master.yml`

## Post Tables

- `post` — непрочитанные, `archive_post` — архив (одинаковая структура)
- При перемещении поста использовать транзакцию (insert + delete)
- Для случайного поста: `ORDER BY RANDOM() LIMIT 1`
- User settings хранятся в JSONB-колонке `settings` в таблице `users`
