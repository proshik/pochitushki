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

- Вход: `/auth/telegram` → `${telegram.oauth.base-url}/auth` (scope
  `openid profile`), колбэк `/auth/telegram/callback`. База по умолчанию
  `oauth.telegram.org`; локально переводится на `telegram-login-broker`
- PKCE: `code_verifier` = 32 случайных байта base64url, `code_challenge` =
  BASE64URL(SHA-256(verifier)), метод `S256`
- `state` и `code_verifier` живут в HttpOnly-куках 5 минут; `state` из куки
  обязательно сверяется с параметром запроса
- Данные пользователя берутся из `id_token` (UserInfo-endpoint у Telegram нет).
  Telegram ID — в нестандартном claim `id`, **не** в `sub`
- Подпись `id_token` не верифицируется: токен получен server-to-server по TLS.
  JWKS у Telegram при этом **есть** (`/.well-known/jwks.json`) — не проверять
  подпись это решение, а не отсутствие возможности
- **Поэтому проверки claim'ов несущие, а не defense-in-depth, и все обязательны.**
  Проверять claim «только если присутствует» значит отдать решение тому, кто
  выпустил токен. Обязательны `iss`, `aud`, `exp`; `iat` — если есть, с допуском
  60 с. Не ослаблять ни одну до условной
- `iss` сверяется **побайтово** с `telegram.oauth.expected-issuer`, а не на
  вхождение подстроки «telegram»: под подстроку подходит любой хост, который её
  содержит. `expected-issuer` меняется вместе с `base-url` и никогда из неё не
  выводится — переключение на другого провайдера обязано быть осознанным
- `aud` разбирается **и как строка, и как массив**. Как `as? String` массив даёт
  `null`, и проверка аудитории молча отключается
- При отсутствии `id` — падать. `sub` **не** подставлять ни при каких условиях:
  это другое число, и подстановка выдаст стабильную, правдоподобную и чужую
  личность. Худший баг, доступный в этом флоу
- Feign-клиенту `telegram-oidc` запрещено следовать редиректам
  (`followRedirects: false`). У token-эндпоинта легитимных редиректов не бывает,
  а по умолчанию `id_token` разбирался бы оттуда, куда увёл последний хоп
- Новые проверки claim'ов добавлять там же, в разбор `id_token`

## Куки (`AuthController`)

- Все куки — `HttpOnly`, `SameSite=Lax`, `Path=/`. `Lax`, а не `Strict`: браузер
  приходит на колбэк с чужого сайта, и `Strict` не отдал бы куку именно на этой
  навигации
- `Secure` управляется `app.security.cookie.secure` (`COOKIE_SECURE`), дефолт
  `true` — fail-safe. Локально по http нужен `false`: Safari молча выбрасывает
  Secure-куку с `http://localhost` (Chrome и Firefox принимают), а с
  `http://192.168.x.x` её не принимает никто. Симптом обманчивый —
  `/login?error=state`, хотя со `state` всё в порядке
- `oidc_state` и `oidc_code_verifier` чистятся **безусловно, до** проверки
  `state`. `code_verifier` — половина PKCE-пары, ей нечего делать в браузере
  после попытки, уже признанной подозрительной

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
