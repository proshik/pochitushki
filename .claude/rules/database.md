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
- User settings хранятся в JSONB-колонке `settings` в таблице `users`.
  Новый ключ обязан иметь дефолт в `UserSettingsData` — миграции для JSONB нет,
  старые строки читаются Jackson'ом с дефолтами из конструктора

## Labels

- Метки: `label` + две связки, `post_label` и `archive_post_label` (по одной на
  таблицу постов). Единой связки быть не может: FK на две таблицы не навесить,
  а id постов из разных сиквенсов пересекаются
- Любая выборка меток по id постов обязана различать таблицу — ключ (таблица, id)
- Перенос поста между unread/archive копирует связки **до** `DELETE` исходной
  строки, иначе их снесёт `ON DELETE CASCADE`
- Метки грузятся пачкой на страницу (`LabelDao.findLabelsForPosts`), не по одной
  на пост
