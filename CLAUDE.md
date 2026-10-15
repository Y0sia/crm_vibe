# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Общайся с пользователем на русском.

## Стек

- `backend/` — Spring Boot 4.1 (Java 25, Maven wrapper), Spring Web MVC, Data JPA, Liquibase, Actuator, Lombok. Пакет `com.vibe.crm`.
- `frontend/` — React 19 + TypeScript, Vite, React Router 8 (`RouterProvider` из `react-router/dom`), TanStack Query 5, Vitest, линтер oxlint. Без UI-библиотек: стили по `design.html` в `src/styles/app.css` и `*.css` рядом со страницами.
- `docker-compose.yml` — PostgreSQL 18 для разработки, compose-проект `vibe-crm`, контейнер `vibe-crm-postgres`.
- `docker-compose.prod.yml` — продакшен (проект `vibe-crm-prod`): postgres + backend (`backend/Dockerfile`) + frontend на nginx (`frontend/Dockerfile`, `frontend/nginx.conf` проксирует `/api`, SSE без буферизации). Развёртывание — `DEPLOY.md`, настройки — `.env.production.example`.

Spring Boot 4 отличается от 3.x: стартеры модульные (`spring-boot-starter-webmvc`, `spring-boot-starter-liquibase`, у каждого свой `*-test`), пакеты автоконфигурации переехали. Не переноси код и зависимости из примеров для Boot 3 без проверки.

## Команды

```bash
docker compose up -d                       # БД (из корня); нужна для запуска бекенда

cd backend
./mvnw spring-boot:run                     # бекенд на :8080
./mvnw verify                              # сборка + тесты
./mvnw test -Dtest=CrmApplicationTests     # один тестовый класс
./mvnw test -Dtest='CrmApplicationTests#contextLoads'  # один метод

cd frontend
npm run dev                                # dev-сервер на :3000 (strictPort)
npm run build                              # tsc -b + vite build
npm run lint                               # oxlint
npm test                                   # vitest (логика: src/lib, src/api)
```

Интеграционные тесты помечаются `@IntegrationTest` (`backend/src/test/java/com/vibe/crm/IntegrationTest.java`): полный контекст + MockMvc + одноразовый PostgreSQL через Testcontainers. Нужен запущенный Docker; рабочая БД из docker compose тестами не используется.

## Как всё связано

- Фронтенд ходит только на относительные пути `/api/...`; Vite проксирует их на `BACKEND_URL` (по умолчанию `http://localhost:8080`, см. `frontend/vite.config.ts`). CORS на бекенде не настроен и при такой схеме не нужен.
- Все REST-эндпоинты бекенда — под префиксом `/api`. `GET /api/ping` — проверка связки фронт → бек → БД (возвращает версию PostgreSQL). Actuator: `/actuator/health`, `/actuator/info`.
- Конфиг бекенда — `backend/src/main/resources/application.yml`, всё параметризовано переменными окружения: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (по умолчанию `localhost:5432/crm`, `crm`/`crm`), `SERVER_PORT` (8080). Параметры контейнера БД — `POSTGRES_*` в `.env` (шаблон `.env.example`).
- Compose-проект намеренно называется `vibe-crm`: на машине есть чужой проект `crm` со своим томом `crm_postgres-data` (данные PG 18). Не меняй `name:` в `docker-compose.yml` и не трогай чужие тома/контейнеры `crm-*`.

## Устройство приложения

- **Telegram:** бот-секретарь (Telegram Business) — `telegram/UpdatePoller` забирает апдейты long polling'ом (offset в `telegram_state`), `UpdateProcessor` создаёт лидов и переписку. Токен — `TELEGRAM_BOT_TOKEN` в `.env`. В тестах приём выключен (`src/test/resources/application.properties`), вместо Telegram — `FakeTelegramBotClient`.
- **Ответ из CRM** (`messages/ReplyService`) возможен только при `can_reply` подключения и в течение 24 часов после входящего сообщения лида (`ReplyAvailability`).
- **Живые обновления:** изменения публикуют `LeadActivity`, `events/SseHub` рассылает их в `GET /api/events` после коммита; фронт (`realtime/useLiveUpdates.ts`) инвалидирует запросы TanStack Query.
- **Вход:** JSON `POST /api/auth/login`, сессия в Postgres (Spring Session JDBC, cookie `SESSION`), CSRF в режиме SPA (cookie `XSRF-TOKEN` → заголовок `X-XSRF-TOKEN`, это делает `frontend/src/api/client.ts`). Первый администратор — из `CRM_ADMIN_LOGIN`/`CRM_ADMIN_PASSWORD`, пока пользователей нет.
- Спека и планы: `docs/superpowers/specs/`, `docs/superpowers/plans/`.

## Схема БД — только через Liquibase

`spring.jpa.hibernate.ddl-auto: validate` — Hibernate схему не создаёт, а только сверяет сущности с таблицами и не даёт приложению стартовать при расхождении. Любое изменение структуры таблиц делается новой миграцией в `backend/src/main/resources/db/changelog/changes/NNN-описание.yaml` (master-changelog подключает папку через `includeAll`, порядок — по имени файла). Уже запушенные changeSet'ы не редактируются. Подробный процесс, соглашения об именах и таблица соответствия типов — в скилле `.claude/skills/db-migrations`.

## Git

- Рабочая ветка — `dev`, `main` — стабильная. Remote: `https://github.com/Y0sia/crm_vibe.git`.
- В коммитах и PR не должно быть никаких следов Claude: без `Co-Authored-By`, без «Generated with Claude Code», без упоминаний ИИ. Для этого есть скилл `.claude/skills/github-push`, настройка `attribution` в `.claude/settings.json` и хук `.githooks/commit-msg`. Хук работает только при `git config core.hooksPath .githooks` (локальная настройка, после клонирования её нужно выставить заново).
- Сообщения коммитов — на русском.
