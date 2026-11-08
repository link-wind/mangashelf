#!/usr/bin/env bash
# M5 恢复（DESIGN 20.3）：先备份！本脚本在【空环境】上回放 backup.sh 的产物。
# 用法：restore.sh <backup 目录>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:?用法：restore.sh <backup 目录>}"
[ -f "$SRC/db.dump" ] && [ -f "$SRC/storage-data.tar.gz" ] || { echo "备份产物不完整" >&2; exit 1; }

echo "== 1. 确认目标为空环境（down -v 由调用方负责）=="
docker compose up -d db storage rabbitmq > /dev/null 2>&1
sleep 5

echo "== 2. 恢复数据库 =="
docker compose exec -T db psql -U "${POSTGRES_USER:-mangashelf}" -d postgres \
  -c "SELECT 1 FROM pg_database WHERE datname = '${POSTGRES_DB:-mangashelf}'" | grep -q 1 \
  || docker compose exec -T db psql -U "${POSTGRES_USER:-mangashelf}" -d postgres \
       -c "CREATE DATABASE ${POSTGRES_DB:-mangashelf}"
docker compose exec -T db psql -U "${POSTGRES_USER:-mangashelf}" -d postgres \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" > /dev/null
docker compose exec -T db pg_restore -U "${POSTGRES_USER:-mangashelf}" \
  -d "${POSTGRES_DB:-mangashelf}" --no-owner --role="${POSTGRES_USER:-mangashelf}" \
  < "$SRC/db.dump"
echo "   数据库恢复完成（Flyway 历史随库恢复，应用不再重复迁移）"

echo "== 3. 回放对象存储数据卷 =="
docker compose down storage > /dev/null 2>&1 || true
docker volume rm mangashelf_storage-data > /dev/null 2>&1 || true
docker volume create mangashelf_storage-data > /dev/null
docker run --rm -v mangashelf_storage-data:/data -v "$SRC":/backup:ro alpine \
  sh -c "cd /data && tar xzf /backup/storage-data.tar.gz"
docker compose up -d storage > /dev/null 2>&1
sleep 5
echo "   对象存储卷回放完成"

echo "恢复完成。启动 API/Worker 后核对：作品列表 + 对象 GET 200（见 docs/validation/m5.md）"
