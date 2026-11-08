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

echo "== 5. 轮询核验任务直至成功（M1 起 verify 不再链式派生，解析见 smoke-m1.sh）=="
for i in $(seq 1 60); do
  SNAP=$(curl -sf "$BASE/api/v1/uploads/$UPLOAD_ID")
  FAILED=$(echo "$SNAP" | jq -r '[.jobs[] | select(.status == "FAILED")] | length')
  if [ "$FAILED" -gt 0 ]; then
    echo "   FAIL: 核验失败"; echo "$SNAP" | jq .; exit 1
  fi
  DONE=$(echo "$SNAP" | jq -r '[.jobs[] | select(.kind == "content.verify" and .status == "SUCCEEDED")] | length')
  if [ "$DONE" -ge 1 ]; then echo "   verify 已成功"; break; fi
  sleep 1
done
ASSET_STATE=$(echo "$SNAP" | jq -r .assetState)
[ "$ASSET_STATE" = "VERIFIED" ] || { echo "   FAIL: 资产未 VERIFIED"; echo "$SNAP" | jq .; exit 1; }

echo "== 6. 重复完成上传：返回同一任务 =="
COMPLETE2=$(curl -sf -X POST "$BASE/api/v1/uploads/$UPLOAD_ID/complete")
JOB2=$(echo "$COMPLETE2" | jq -r .jobId)
if [ "$JOB2" != "$VERIFY_JOB" ]; then echo "   FAIL: complete returned different job $JOB2"; exit 1; fi
echo "   complete idempotent OK"

echo ""
echo "M0 SMOKE PASSED ✅  upload=$UPLOAD_ID（上传→核验→幂等）"
