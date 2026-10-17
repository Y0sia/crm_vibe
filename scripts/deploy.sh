#!/usr/bin/env bash
# Выкладка CRM на VPS: собирает образы бекенда и фронтенда из ветки (по умолчанию main с GitHub),
# отправляет их на сервер и перезапускает только backend и frontend. Postgres не трогается никогда.
#
#   scripts/deploy.sh            # выложить origin/main
#   scripts/deploy.sh dev        # выложить origin/dev
#
# Переменные: DEPLOY_HOST (по умолчанию crm-vps из ~/.ssh/config), DEPLOY_DIR (/opt/crm).
set -euo pipefail

BRANCH="${1:-main}"
HOST="${DEPLOY_HOST:-crm-vps}"
DIR="${DEPLOY_DIR:-/opt/crm}"
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

step() { printf '\n==> %s\n' "$*"; }

step "Беру актуальную версию origin/$BRANCH"
git fetch -q origin "$BRANCH"
SHA="$(git rev-parse --short "origin/$BRANCH")"
echo "Коммит $SHA: $(git log -1 --format=%s "origin/$BRANCH")"

step "Проверяю сервер $HOST"
ssh "$HOST" "test -f $DIR/.env" || { echo "На сервере нет $DIR/.env — сначала первичная настройка (DEPLOY.md)"; exit 1; }
if ! ssh "$HOST" "cd $DIR && docker compose ps --status running -q postgres | grep -q ."; then
	echo "Postgres на сервере не запущен. Скрипт базу не трогает — разберитесь вручную (DEPLOY.md)."
	exit 1
fi

SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
git archive "origin/$BRANCH" | tar -x -C "$SRC"

step "Собираю образы под linux/amd64"
docker buildx build -q --platform linux/amd64 -t "vibe-crm-backend:$SHA" --load "$SRC/backend"
docker buildx build -q --platform linux/amd64 -t "vibe-crm-frontend:$SHA" --load "$SRC/frontend"

step "Отправляю образы на сервер"
docker save "vibe-crm-backend:$SHA" "vibe-crm-frontend:$SHA" | gzip -1 | ssh "$HOST" 'gunzip | docker load -q'
scp -q "$SRC/docker-compose.prod.yml" "$HOST:$DIR/docker-compose.yml"

step "Перезапускаю backend и frontend (postgres не трогаю)"
PREV="$(ssh "$HOST" "cat $DIR/DEPLOYED 2>/dev/null || echo none")"
ssh "$HOST" "set -e; cd $DIR
	docker tag vibe-crm-backend:$SHA vibe-crm-backend:latest
	docker tag vibe-crm-frontend:$SHA vibe-crm-frontend:latest
	docker compose up -d --no-deps --no-build backend frontend"

step "Жду, пока бекенд поднимется"
for i in $(seq 1 40); do
	status="$(ssh "$HOST" "docker inspect -f '{{.State.Health.Status}}' \$(cd $DIR && docker compose ps -q backend)" 2>/dev/null || true)"
	[ "$status" = "healthy" ] && break
	sleep 5
done
if [ "$status" != "healthy" ]; then
	echo "Бекенд не поднялся (статус: $status). Последние строки журнала:"
	ssh "$HOST" "cd $DIR && docker compose logs --tail 40 backend"
	echo "Откат на предыдущую версию: scripts/deploy-rollback.sh $PREV"
	exit 1
fi
code="$(ssh "$HOST" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/api/auth/me")"
[ "$code" = "401" ] || { echo "Сайт отвечает неожиданно: HTTP $code"; exit 1; }

ssh "$HOST" "cd $DIR && echo $SHA > DEPLOYED && echo '$(date -u +%FT%TZ) $SHA origin/$BRANCH' >> deploy.log
	# храним 3 последние версии образов для отката
	for name in vibe-crm-backend vibe-crm-frontend; do
		docker images \$name --format '{{.Tag}}' | grep -v latest | tail -n +4 | xargs -r -I{} docker rmi -f \$name:{} >/dev/null
	done
	docker image prune -f >/dev/null"

step "Готово: на сервере $SHA (было $PREV)"
