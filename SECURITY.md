# Security Policy

## Supported versions

Поддерживается только последний релиз (`master` и последний тег `v*`).
Only the latest release (`master` and the newest `v*` tag) is supported.

## Reporting a vulnerability

Не открывайте публичный issue для уязвимостей.
Please do not open a public issue for security problems.

Используйте [Private vulnerability reporting](https://github.com/proshik/pochitushki/security/advisories/new)
(вкладка Security → Report a vulnerability). Отчёт видят только мейнтейнеры.

Что полезно приложить:

- версию или коммит, на котором воспроизводится;
- шаги воспроизведения или PoC;
- оценку влияния (чтение чужих данных, обход входа, SSRF, отказ в обслуживании).

Сроки: ответ в течение 7 дней, оценка и план исправления — в течение 30 дней.
Пожалуйста, дайте 90 дней до публичного раскрытия.

## Scope

Сервис обрабатывает пользовательские URL и ходит по ним на сервере, поэтому
особенно интересны:

- обход `UrlSecurityValidator` (SSRF), включая редиректы и DNS rebinding;
- обход аутентификации: подделка `auth_token`, проблемы OIDC-флоу (`state`, PKCE,
  проверки `id_token`);
- доступ к чужим постам, меткам или PDF (проверка владения в DAO);
- XSS в обход CSP;
- подделка запросов к webhook-эндпоинту бота.

Вне зоны: отчёты сканеров без подтверждённого воздействия, отсутствие
rate-limit на эндпоинтах без побочных эффектов, self-XSS.
