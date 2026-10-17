#!/usr/bin/env bash
# Ежедневная копия базы CRM: /opt/crm/backups/crm-YYYY-MM-DD.sql.gz, хранится 14 дней.
set -euo pipefail
cd /opt/crm
mkdir -p backups
file="backups/crm-$(date +%F).sql.gz"
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$file.tmp"
mv "$file.tmp" "$file"
find backups -name 'crm-*.sql.gz' -mtime +14 -delete
