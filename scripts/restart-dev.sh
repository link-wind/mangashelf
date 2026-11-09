#!/usr/bin/env bash
# 标准开发重启：停止 → 确认退出 → 启动 API/Worker（jar 必须已构建完成）。
# 用法：restart-dev.sh [--no-embedding]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/server/target/mangashelf-server-0.1.0-SNAPSHOT.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"

pkill -f mangashelf-server 2>/dev/null || true
for i in $(seq 1 10); do pgrep -f mangashelf-server > /dev/null || break; sleep 1; done
if pgrep -f mangashelf-server > /dev/null; then echo "旧进程未能退出" >&2; exit 1; fi

export MANGASHELF_PYTHON="$ROOT/processing/.venv/bin/python"
export MANGASHELF_PROCESSING_ROOT="$ROOT"
export MANGASHELF_WORK_ROOT="$ROOT/data/jobs"
# Bangumi 元数据源（M9）：网络受限环境从 .env 读代理（如 http://127.0.0.1:7892）
if [ -f "$ROOT/.env" ]; then
  BANGUMI_PROXY_LINE=$(grep -E '^MANGASHELF_BANGUMI_PROXY=' "$ROOT/.env" | tail -1 || true)
  if [ -n "$BANGUMI_PROXY_LINE" ]; then
    export MANGASHELF_BANGUMI_PROXY="${BANGUMI_PROXY_LINE#*=}"
  fi
fi
if [ "${1:-}" != "--no-embedding" ]; then
  export MANGASHELF_EMBEDDING_MODEL_PATH="$ROOT/server/models/e5-small-v2.onnx"
  export MANGASHELF_EMBEDDING_TOKENIZER_PATH="$ROOT/server/models/tokenizer.json"
else
  export MANGASHELF_EMBEDDING_ENABLED=false
fi

nohup java -jar "$JAR" > /tmp/mangashelf-api.log 2>&1 &
echo "api pid=$!"
nohup java -jar "$JAR" --mangashelf.role=worker > /tmp/mangashelf-worker.log 2>&1 &
echo "worker pid=$!"
for i in $(seq 1 30); do
    curl -s -m 2 http://localhost:8080/actuator/health > /dev/null 2>&1 && break
  sleep 1
done
curl -s -m 3 http://localhost:8080/actuator/health && echo " ← health"
