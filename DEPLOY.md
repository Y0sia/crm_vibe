# Развёртывание на VPS

Всё приложение — три контейнера из `docker-compose.prod.yml`:

| Сервис | Что это | Наружу |
|---|---|---|
| `frontend` | nginx: сайт + прокси `/api` к бекенду | порт `HTTP_PORT` (по умолчанию 80) |
| `backend` | Spring Boot | закрыт |
| `postgres` | PostgreSQL 18, данные в томе `postgres-data` | закрыт |

Публичный адрес для Telegram не нужен: бот сам забирает сообщения (long polling).

## Первый запуск

1. На сервере установите Docker с плагином Compose: https://docs.docker.com/engine/install/
2. Склонируйте проект и перейдите в него:
   ```bash
   git clone https://github.com/Y0sia/crm_vibe.git crm && cd crm
   ```
3. Создайте `.env` из шаблона и заполните: пароль базы, логин и пароль первого администратора, токен бота.
   ```bash
   cp .env.production.example .env
   nano .env
   ```
   Пароль для базы можно сгенерировать: `openssl rand -base64 24`.
4. Соберите и запустите:
   ```bash
   docker compose -f docker-compose.prod.yml up -d --build
   ```
5. Откройте `http://<IP сервера>` и войдите администратором из `.env`.

**Важно:** одним токеном бота может пользоваться только одна запущенная CRM. Перед запуском на сервере остановите локальный бекенд, иначе они будут «отбирать» друг у друга сообщения.

## Обновление

```bash
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

Миграции базы применяются автоматически при старте бекенда.

## Полезные команды

```bash
docker compose -f docker-compose.prod.yml ps                 # состояние
docker compose -f docker-compose.prod.yml logs -f backend    # журнал бекенда
docker compose -f docker-compose.prod.yml restart backend    # перезапуск

# резервная копия базы
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U crm crm | gzip > backup-$(date +%F).sql.gz
# восстановление в пустую базу
gunzip -c backup.sql.gz | docker compose -f docker-compose.prod.yml exec -T postgres psql -U crm crm
```

## HTTPS

Сайт работает по HTTP на 80 порту. Для домена с HTTPS поставьте перед ним обратный прокси с автоматическими сертификатами (например, Caddy или nginx + certbot), а `HTTP_PORT` переключите на внутренний порт, например `8080`. Бекенд учитывает заголовок `X-Forwarded-Proto`, поэтому cookie входа при HTTPS автоматически станут `Secure`.
