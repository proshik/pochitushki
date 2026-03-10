---
paths:
  - "src/**/*Auth*.kt"
  - "src/**/*Jwt*.kt"
  - "src/**/*Interceptor*.kt"
  - "src/**/controller/Auth*.kt"
---

# Security Rules (для будущей реализации веб-аутентификации)

## Telegram Auth Verification

- Верифицировать HMAC-SHA256 подпись данных от Telegram
- Секретный ключ: `HMAC-SHA256("WebAppData", botToken)`
- Проверять `auth_date` — данные не должны быть старше 24 часов
- Никогда не доверять данным от клиента без верификации хеша

## JWT

- Подписывать через HMAC-SHA256 с секретным ключом из конфига
- Хранить `chatId` как subject токена
- Библиотека: JJWT 0.12.5
- Валидацию токена делать только в одном месте (Interceptor)

## Interceptor

- Защищённые пути: `/api/v1/**`, `/archive`, `/profile`
- Исключения: `/login`, `/auth/**`, статика (`/css/**`, `/js/**`)
- При невалидном токене: редирект на `/login` для веб, 401 для API
