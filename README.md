# Pochitushki Service

Сервис представляет собой Telegram-бота для сохранения и управления ссылками, аналог сервисов "отложенного чтения" (read-it-later), таких как Pocket.

## Основные возможности

- **Сохранение ссылок**: Просто отправьте боту ссылку, и он сохранит ее.
- **Просмотр сохраненных ссылок**: Получайте список ваших сс��лок в виде ленты.
- **Архивация**: Архивируйте посты, которые вы уже просмотрели, и возвращайте их из архива при необходимости.
- **Удаление**: Удаляйте ненужные ссылки.
- **Случайная ссылка**: Получите случайную ссылку из вашего списка для чтения.
- **Импорт из Pocket**: Загрузите ZIP-архив с вашими данными из Pocket для импорта всех ссылок.
- **Экспорт**: Экспортируйте ваши данные.

## Аутентификация через Telegram (OIDC + PKCE)

Веб-интерфейс использует Telegram Login через стандартный OAuth 2.0 / OIDC Authorization Code Flow с PKCE.
Данные пользователя извлекаются из `id_token` JWT — отдельного UserInfo-endpoint у Telegram нет.

### Общий флоу входа

```plantuml
@startuml
title Telegram OIDC Login Flow

actor User
participant Browser
participant "Pochitushki\n(Spring Boot)" as App
participant "oauth.telegram.org" as Telegram

User -> Browser : Нажимает «Войти через Telegram»
Browser -> App : GET /auth/telegram
App -> App : Генерирует state (UUID)\nи PKCE code_verifier/challenge
App -> Browser : Set-Cookie: oidc_state, oidc_code_verifier\nRedirect → oauth.telegram.org/auth?...&scope=openid+profile
Browser -> Telegram : GET /auth?client_id=...&code_challenge=...&scope=openid+profile
User -> Telegram : Подтверждает вход в Telegram
Telegram -> Browser : Redirect → /auth/telegram/callback?code=...&state=...
Browser -> App : GET /auth/telegram/callback\n(с куками oidc_state, oidc_code_verifier)
App -> App : Проверяет state из cookie == state из параметра
App -> Telegram : POST /token\n(code + code_verifier + client_secret)
Telegram -> App : {"access_token":..., "id_token": "<JWT>"}
App -> App : Base64url-decode payload id_token\nИзвлекает: id, name, preferred_username, picture
App -> App : getOrCreateUser(telegramId, ...)
App -> Browser : Set-Cookie: auth_token (JWT)\nRedirect → /
Browser -> User : Главная страница
@enduml
```

### Структура id_token

Telegram возвращает JWT со следующими claim'ами (при scope `openid profile`):

| Claim | Тип | Описание |
|---|---|---|
| `id` | String | Telegram User ID |
| `sub` | String | Внутренний OIDC subject (не Telegram ID) |
| `name` | String | Полное имя пользователя |
| `preferred_username` | String? | Telegram username (@handle) |
| `picture` | String? | URL аватара |
| `iss` | String | `https://oauth.telegram.org` |
| `aud` | String | client_id бота |

> **Важно:** подпись `id_token` не верифицируется — токен получен напрямую от Telegram через TLS (server-to-server), транспорт гарантирует целостность.

### Флоу выхода

```plantuml
@startuml
title Logout Flow

actor User
participant Browser
participant "Pochitushki\n(Spring Boot)" as App

User -> Browser : Нажимает «Выйти»
Browser -> App : GET /logout
App -> Browser : Set-Cookie: auth_token (maxAge=0)\nRedirect → /login
Browser -> User : Страница входа
@enduml
```

### PKCE (защита от перехвата кода)

```plantuml
@startuml
title PKCE Code Challenge / Verifier

participant App
participant Telegram

App -> App : code_verifier = random 32 bytes (base64url)
App -> App : code_challenge = BASE64URL(SHA-256(code_verifier))
note right : Хранится в HttpOnly-куке\nна 5 минут

App -> Telegram : /auth?code_challenge=...&code_challenge_method=S256
Telegram -> App : /callback?code=...
App -> Telegram : POST /token\ncode + code_verifier
Telegram -> Telegram : Проверяет: SHA-256(code_verifier) == code_challenge
Telegram -> App : id_token + access_token
@enduml
```

## Запуск с помощью Docker

Для запуска приложения и базы данных PostgreSQL в Docker-контейнерах выполните следующую команду:

```bash
docker compose up --build
```

Приложение будет доступно по адресу `http://localhost:8080`.

## Сборка проекта

Для сборки проекта без запуска Docker используйте команду Gradle:

```bash
./gradlew bootJar
```
