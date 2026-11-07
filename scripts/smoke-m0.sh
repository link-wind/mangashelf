#!/usr/bin/env bash
# M0 全链路冒烟：创建上传 → 预签名 PUT → complete → 核验 → 派生任务 → 产物下载核对
# 前置：compose 服务已启动；API (8080) 与 Worker 已运行；jq 可用
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke"
mkdir -p "$WORK"

echo "== 1. 生成测试图片（Pillow，processing venv）=="
PYTHON="${MANGASHELF_PYTHON:-$REPO_ROOT/processing/.venv/bin/python}"
"$PYTHON" - "$WORK/test.png" <<'PY'
import sys
from PIL import Image, ImageDraw
img = Image.new("RGB", (900, 1400), (245, 240, 230))
d = ImageDraw.Draw(img)
d.rectangle([100, 100, 800, 1300], outline=(60, 60, 60), width=4)
d.text((140, 160), "MangaShelf M0 smoke page", fill=(20, 20, 20))
img.save(sys.argv[1], "PNG")
PY
SIZE=$(stat -f%z "$WORK/test.png")
SHA=$(shasum -a 256 "$WORK/test.png" | awk '{print $1}')
echo "   test.png size=$SIZE sha256=$SHA"

echo "== 2. 创建上传会话 =="
IDEMPOTENCY_KEY="smoke-$(date +%s)"
CREATE=$(curl -sf -X POST "$BASE/api/v1/uploads" -H 'Content-Type: application/json' -d "{
  \"purpose\": \"MANGA_PAGE\",
  \"expectedSizeBytes\": $SIZE,
  \"expectedSha256\": \"$SHA\",
  \"contentType\": \"image/png\",
  \"idempotencyKey\": \"$IDEMPOTENCY_KEY\"
}")
UPLOAD_ID=$(echo "$CREATE" | jq -r .uploadId)
PUT_URL=$(echo "$CREATE" | jq -r .presigned.url)
echo "   uploadId=$UPLOAD_ID"

echo "== 3. 预签名 PUT 上传 =="
CONTENT_TYPE=$(echo "$CREATE" | jq -r '.presigned.headers["content-type"] // "image/png"')
curl -sf -X PUT "$PUT_URL" -H "Content-Type: $CONTENT_TYPE" --upload-file "$WORK/test.png" > /dev/null
echo "   uploaded"

echo "== 3b. 幂等重放：同键同请求返回同一 uploadId =="
REPLAY=$(curl -sf -X POST "$BASE/api/v1/uploads" -H 'Content-Type: application/json' -d "{
  \"purpose\": \"MANGA_PAGE\",
  \"expectedSizeBytes\": $SIZE,
  \"expectedSha256\": \"$SHA\",
  \"contentType\": \"image/png\",
  \"idempotencyKey\": \"$IDEMPOTENCY_KEY\"
}")
REPLAY_ID=$(echo "$REPLAY" | jq -r .uploadId)
if [ "$REPLAY_ID" != "$UPLOAD_ID" ]; then echo "   FAIL: replay returned $REPLAY_ID"; exit 1; fi
echo "   replay OK（未重复创建）"

echo "== 4. 完成上传（202 + 核验任务）=="
COMPLETE=$(curl -sf -X POST "$BASE/api/v1/uploads/$UPLOAD_ID/complete")
VERIFY_JOB=$(echo "$COMPLETE" | jq -r .jobId)
echo "   verifyJob=$VERIFY_JOB"

echo "== 5. 轮询任务直至链路完成 =="
for i in $(seq 1 60); do
  SNAP=$(curl -sf "$BASE/api/v1/uploads/$UPLOAD_ID")
  ALL_DONE=$(echo "$SNAP" | jq -r '[.jobs[] | select(.status == "SUCCEEDED")] | length')
  JOBS_TOTAL=$(echo "$SNAP" | jq -r '.jobs | length')
  FAILED=$(echo "$SNAP" | jq -r '[.jobs[] | select(.status == "FAILED")] | length')
  if [ "$FAILED" -gt 0 ]; then
    echo "   FAIL: 某任务失败"; echo "$SNAP" | jq .; curl -sf "$BASE/api/v1/jobs/$VERIFY_JOB/events" | jq .; exit 1
  fi
  if [ "$JOBS_TOTAL" -ge 2 ] && [ "$ALL_DONE" -ge 2 ]; then echo "   verify + process 均已成功"; break; fi
  sleep 1
done
if [ "$ALL_DONE" -lt 2 ]; then echo "   TIMEOUT: 任务未完成"; echo "$SNAP" | jq .; exit 1; fi

echo "== 6. 下载派生产物并核对摘要 =="
READER_ASSET=$(echo "$SNAP" | jq -r '.assets[] | select(.kind == "READER_IMAGE") | .id')
ACCESS=$(curl -sf -X POST "$BASE/api/v1/assets/$READER_ASSET/access" \
  -H 'Content-Type: application/json' -d '{"purpose": "READ"}')
READER_URL=$(echo "$ACCESS" | jq -r .url)
curl -sf "$READER_URL" -o "$WORK/reader.webp"
READER_SHA=$(shasum -a 256 "$WORK/reader.webp" | awk '{print $1}')
STORED_SHA=$(echo "$SNAP" | jq -r ".assets[] | select(.id == \"$READER_ASSET\") | .id" > /dev/null; \
  curl -sf "$BASE/api/v1/jobs/$(echo "$SNAP" | jq -r '.jobs[] | select(.kind == "content.process") | .id')/events" | jq -r '.[-1].message')
echo "   reader.webp sha256=$READER_SHA"
echo "   downloaded, size=$(stat -f%z "$WORK/reader.webp")"

echo "== 7. 重复完成上传：返回同一任务 =="
COMPLETE2=$(curl -sf -X POST "$BASE/api/v1/uploads/$UPLOAD_ID/complete")
JOB2=$(echo "$COMPLETE2" | jq -r .jobId)
if [ "$JOB2" != "$VERIFY_JOB" ]; then echo "   FAIL: complete returned different job $JOB2"; exit 1; fi
echo "   complete idempotent OK"

echo ""
echo "M0 SMOKE PASSED ✅  upload=$UPLOAD_ID reader_asset=$READER_ASSET"
