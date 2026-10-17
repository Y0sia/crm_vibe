#!/usr/bin/env bash
# Откат backend и frontend на одну из сохранённых на сервере версий (коммит из DEPLOYED / deploy.log).
#   scripts/deploy-rollback.sh <sha>
set -euo pipefail
SHA="${1:?Укажите коммит: ssh crm-vps cat /opt/crm/deploy.log}"
HOST="${DEPLOY_HOST:-crm-vps}"
DIR="${DEPLOY_DIR:-/opt/crm}"
ssh "$HOST" "set -e; cd $DIR
	docker image inspect vibe-crm-backend:$SHA vibe-crm-frontend:$SHA >/dev/null
	sed -i '/^CRM_TAG=/d' .env && echo CRM_TAG=$SHA >> .env
	docker compose up -d --no-deps --no-build backend frontend
	echo $SHA > DEPLOYED && echo \"\$(date -u +%FT%TZ) $SHA rollback\" >> deploy.log"
echo "Откатились на $SHA"
