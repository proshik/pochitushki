---
paths:
  - "src/**/*Auth*.kt"
  - "src/**/*Jwt*.kt"
  - "src/**/*Interceptor*.kt"
  - "src/**/controller/Auth*.kt"
---

# Security Rules

Веб-аутентификация реализована. Флоу — **Telegram OIDC/OAuth 2.0 Authorization
Code + PKCE**, не Login Widget: HMAC-подписи `WebAppData` и проверки `auth_date`
здесь нет и добавлять её не нужно.

## Telegram OIDC (`TelegramOidcService`, `AuthController`)

- Вход: `/auth/telegram` → `oauth.telegram.org/auth` (scope `openid profile`),
  колбэк `/auth/telegram/callback`
- PKCE: `code_verifier` = 32 случайных байта base64url, `code_challenge` =
  BASE64URL(SHA-256(verifier)), метод `S256`
- `state` и `code_verifier` живут в HttpOnly-куках 5 минут; `state` из куки
  обязательно сверяется с параметром запроса
- Данные пользователя берутся из `id_token` (UserInfo-endpoint у Telegram нет).
  Telegram ID — в нестандартном claim `id`, **не** в `sub`
- Подпись `id_token` не верифицируется осознанно: токен получен server-to-server
  по TLS. Дополнительно (defense-in-depth) проверяются `exp`, `aud` (== client_id)
  и `iss`. Проверки условные — claim валидируется, только если присутствует
- Новые проверки claim'ов добавлять там же, в разбор `id_token`

## JWT (`JwtService`)

- HS256, ключ из `jwt.secret` (base64, ≥32 байт), валидируется eagerly в init —
  fail fast на старте, а не при первом токене
- Subject токена — **`userId`** (внутренний PK), не `chatId` и не Telegram ID
- Библиотека JJWT 0.12.5, fluent-API: `Jwts.builder()` / `Jwts.parser()`
- `extractUserId` возвращает `null` на любой невалидный токен (ловит
  `JwtException`, `IllegalArgumentException`, `NumberFormatException`) — не бросать
  наружу
- Токен stateless: logout не отзывает его, поэтому TTL короткий (`jwt.ttl-days`)

## Cookies

- Все куки через `baseCookie()`: `HttpOnly`, `Secure`, `SameSite=Lax`, `path=/`
- `SameSite=Lax` — основная CSRF-защита для cookie-auth; не ослаблять
- Max-age `auth_token` выводится из `jwt.ttl-days`, чтобы кука и токен не разъезжались

## Interceptor (`JwtAuthInterceptor` + `WebConfig`)

- Валидация токена — только здесь, в одном месте
- Защищённые пути (`WebConfig`): `/`, `/all`, `/archive`, `/favorites`, `/random`,
  `/profile`, `/api/v1/**`. Открыты: `/login`, `/auth/**`, webhook.
  Новую страницу добавлять сюда сразу — иначе она открыта без входа
- Невалидный токен: `401` для `/api/**`, редирект на `/login` для остального
- Кладёт в request-атрибуты `userId` и `_userLocale`; контроллеры получают
  пользователя через `@RequestAttribute("userId")`, а не из SecurityContext

## Прочие защиты

- `UrlSecurityValidator` — SSRF-проверки для всех внешних загрузок (PDF, OG-image,
  прокси обложек). Любой новый код, ходящий по пользовательскому URL, обязан прогонять
  его через валидатор. Исключения — только через `app.security.ssrf.allowed-hosts`
- `OgImageProxyService` отдаёт байты картинок с нашего origin, поэтому content-type
  проверяется по аллоулисту растровых форматов. `image/svg+xml` запрещён намеренно:
  SVG на своём origin исполняет скрипты. Аллоулист не расширять «за компанию»
- `SecurityHeadersFilter` — `X-Frame-Options: DENY`, `X-Content-Type-Options`,
  `Referrer-Policy`, CSP `frame-ancestors 'none'`, `img-src 'self'` (картинки только
  свои, обложки — через прокси)
- Webhook: путь эндпоинта — сам токен бота, плюс сверка заголовка
  `X-Telegram-Bot-Api-Secret-Token` с `telegram.webhook-secret`. Секретного пути
  недостаточно, header-проверку не убирать
- Actuator: выставлен только `health` без деталей; `env`/`heapdump`/`loggers`/
  `threaddump` не включать
- `server.error.include-*: never` — не отдавать stacktrace наружу
