# CRM

- `backend/` — Spring Boot 4.1 (Java 25, Maven), JPA, Liquibase
- `frontend/` — React 19 + TypeScript (Vite)
- `docker-compose.yml` — PostgreSQL 18

## Запуск

```bash
# 1. База данных
docker compose up -d

# 2. Бекенд (http://localhost:8080)
cd backend && ./mvnw spring-boot:run

# 3. Фронтенд (http://localhost:3000, запросы /api проксируются на бекенд)
cd frontend && npm install && npm run dev
```

Проверка: `curl localhost:8080/api/ping` возвращает версию PostgreSQL.

## Настройки

| Переменная | По умолчанию | Где |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_PORT` | `crm` / `crm` / `crm` / `5432` | docker compose (`.env`, см. `.env.example`) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `crm` / `crm` / `crm` | бекенд |
| `SERVER_PORT` | `8080` | бекенд |
| `BACKEND_URL` | `http://localhost:8080` | прокси Vite |

Если 8080 занят: `SERVER_PORT=8081 ./mvnw spring-boot:run` и `BACKEND_URL=http://localhost:8081 npm run dev`.

## Миграции БД

Liquibase, changelog: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`.
Он подключает все файлы из `changes/` в алфавитном порядке — новое изменение схемы добавляйте
отдельным файлом (`002-....yaml`, `003-....yaml`). Уже применённые changeset'ы не редактируйте.
Hibernate работает в режиме `ddl-auto: validate` — схему меняет только Liquibase.

## Git

После клонирования подключите хуки репозитория:

```bash
git config core.hooksPath .githooks
```

Рабочая ветка — `dev`.
