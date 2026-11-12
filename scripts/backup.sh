#!/usr/bin/env bash
# M5 备份（DESIGN 20.3）：数据库（custom 格式）+ SeaweedFS 数据卷，落 backups/<时间戳>/。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP=$(date +%Y%m%d-%H%M%S)
DEST="$ROOT/backups/$STAMP"
PROJECT="${COMPOSE_PROJECT_NAME:-mangashelf}"
mkdir -p "$DEST"

echo "== 1. 数据库导出（pg_dump custom 格式）=="
docker compose exec -T db pg_dump -U "${POSTGRES_USER:-mangashelf}" -Fc "${POSTGRES_DB:-mangashelf}" \
  > "$DEST/db.dump"
echo "   db.dump = $(wc -c < "$DEST/db.dump" | tr -d ' ') bytes"

echo "== 2. 对象存储数据卷快照（SeaweedFS /data）=="
docker run --rm -v "${PROJECT}_storage-data:/data:ro" -v "$DEST":/backup alpine \
  tar czf /backup/storage-data.tar.gz -C /data .
echo "   storage-data.tar.gz = $(wc -c < "$DEST/storage-data.tar.gz" | tr -d ' ') bytes"

echo "$DEST"
echo "备份完成：$DEST"
