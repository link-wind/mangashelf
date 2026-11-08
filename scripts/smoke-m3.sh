#!/usr/bin/env bash
# M3 全链路冒烟（模拟 MangaWeaver 客户端，全 Bearer）：
# Token 签发 → 目录读 → 导出清单 → 下载页面核验 SHA-256 → publications 幂等草稿 →
# 上传成片 → verify → 状态查询 → publish → watch_url → 网站播放 → scope/撤销边界
# 前置：compose 服务健康；API(8080)/Worker 已运行；jq、ffmpeg 可用
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke-m3"
rm -rf "$WORK" && mkdir -p "$WORK"
PYTHON="${MANGASHELF_PYTHON:-$REPO_ROOT/processing/.venv/bin/python}"

echo "== 1. 站内准备：发布一部 4 页漫画（Session 流程，复用 M1 能力）=="
JAR="$WORK/site.txt"
curl -s -m 10 -c "$JAR" "$BASE/api/v1/works" -o /dev/null
site_req() {
  local method="$1" path="$2" data="${3:-}"
  local xsrf; xsrf=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}' | tail -1)
  if [ "$method" = "GET" ]; then curl -s -m 15 -b "$JAR" "$BASE$path";
  else curl -s -m 30 -b "$JAR" -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $xsrf" ${data:+-d "$data"}; fi
}
TS=$(date +%s)
LOGIN="editor$TS"
site_req POST /api/v1/auth/register "{\"login\":\"$LOGIN\",\"password\":\"editor-pass-123\"}" > /dev/null
site_req POST /api/v1/auth/login "{\"login\":\"$LOGIN\",\"password\":\"editor-pass-123\"}" > /dev/null
WID=$(site_req POST /api/v1/works "{\"title\":\"信号之森\",\"description\":\"M3 来源\"}" | jq -r .work.id)
CHID=$(site_req POST "/api/v1/works/$WID/chapters" "{\"title\":\"第1话\"}" | jq -r .id)
"$PYTHON" - "$WORK" <<'PY'
from PIL import Image, ImageDraw
import zipfile, io, sys
with zipfile.ZipFile(f"{sys.argv[1]}/manga.cbz", "w") as z:
    for i in range(1, 5):
        img = Image.new('RGB', (700, 1000), (246, 248, 252))
        ImageDraw.Draw(img).text((40, 40), f"forest page {i}", fill=(20, 30, 40))
        buf = io.BytesIO(); img.save(buf, 'PNG'); z.writestr(f"f{i}.png", buf.getvalue())
PY
SIZE=$(stat -f%z "$WORK/manga.cbz")
C=$(site_req POST /api/v1/uploads "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"m3-site-$TS\"}")
UPID=$(echo "$C" | jq -r .uploadId)
curl -s -m 30 -X PUT "$(echo "$C" | jq -r .presigned.url)" -H "Content-Type: application/zip" --upload-file "$WORK/manga.cbz" > /dev/null
site_req POST "/api/v1/uploads/$UPID/complete" > /dev/null
OK=0; for i in $(seq 1 60); do
  S=$(site_req GET "/api/v1/uploads/$UPID" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && OK=1 && break; sleep 1
done
[ "$OK" = "1" ] || { echo "   FAIL: 核验超时"; exit 1; }
CREV=$(site_req POST "/api/v1/chapters/$CHID/revisions" "{\"uploadId\":\"$UPID\"}" | jq -r .revisionId)
OK=0; for i in $(seq 1 120); do
  ST=$(site_req GET "/api/v1/chapter-revisions/$CREV" | jq -r .status)
  [ "$ST" = "READY" ] && OK=1 && break; [ "$ST" = "FAILED" ] && exit 1; sleep 1
done
[ "$OK" = "1" ] || { echo "   FAIL: 解析超时"; exit 1; }
site_req POST "/api/v1/chapter-revisions/$CREV/publish" > /dev/null
site_req PATCH "/api/v1/works/$WID" '{"visibility":"PUBLIC"}' > /dev/null
site_req POST "/api/v1/works/$WID/publish" > /dev/null
echo "   漫画已发布：work=$WID revision=$CREV"

echo "== 2. 创建接入 Token（浏览器会话）=="
TOKEN=$(site_req POST /api/v1/me/api-tokens \
  "{\"scopes\":[\"catalog:read\",\"materials:read\",\"animations:write\",\"animations:publish\"],\"expiresInDays\":30}" \
  | jq -r .rawToken)
[ "$TOKEN" != "null" ] && [ -n "$TOKEN" ] || { echo "   FAIL: Token 未返回"; exit 1; }
echo "   Token 前缀: ${TOKEN:0:12}…（原始值仅此一次展示）"

echo "== 3. Bearer 目录读（catalog:read）=="
AUTH="Authorization: Bearer $TOKEN"
WORKS=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/works?limit=5")
echo "$WORKS" | jq -e ".items[] | select(.id == \"$WID\")" > /dev/null && echo "   目录可见 ✓" || { echo "   FAIL: $WORKS"; exit 1; }

echo "== 4. Bearer 导出清单（materials:read）并逐页核验摘要 =="
EXPORT=$(curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/integrations/mangaweaver/exports" \
  -H 'Content-Type: application/json' -d "{\"chapter_revision_id\":\"$CREV\"}")
echo "$EXPORT" | jq -e ".schema_version == 1 and .platform_id and (.pages | length) == 4" > /dev/null \
  || { echo "   FAIL: $EXPORT"; exit 1; }
echo "   platform_id=$(echo "$EXPORT" | jq -r .platform_id) reading_direction=$(echo "$EXPORT" | jq -r .reading_direction)"
VERIFY_OK=1
for PAGE in $(echo "$EXPORT" | jq -c '.pages[]'); do
  PID=$(echo "$PAGE" | jq -r .page_id)
  EXPECT=$(echo "$PAGE" | jq -r .sha256)
  URL=$(echo "$PAGE" | jq -r .download_url)
  FILE="$WORK/dl-$PID.bin"
  curl -s -m 15 "$URL" -o "$FILE"
  ACTUAL=$(shasum -a 256 "$FILE" | awk '{print $1}')
  [ "$ACTUAL" = "$EXPECT" ] || { echo "   FAIL: 页 $PID 摘要不一致"; VERIFY_OK=0; }
  # 客户端侧解码验证（防存储污染回归）
  "$PYTHON" - "$FILE" <<'PY' || VERIFY_OK=0
from PIL import Image
import sys
im = Image.open(sys.argv[1]); im.load()
PY
done
[ "$VERIFY_OK" = "1" ] && echo "   4 页下载 + SHA-256 核验 + 解码验证 ✓" || exit 1

echo "== 5. publications 幂等草稿（animations:write）=="
RUNREF="mw-run-$TS"
PUB=$(curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/integrations/mangaweaver/publications" \
  -H 'Content-Type: application/json' -d "{
    \"schema_version\":1,\"title\":\"信号之森·动画版\",\"requested_visibility\":\"PUBLIC\",
    \"origin_run_ref\":\"$RUNREF\",
    \"sources\":[{\"chapter_revision_id\":\"$CREV\",\"page_start\":1,\"page_end\":2,\"ordinal\":1}]}")
ANID=$(echo "$PUB" | jq -r .animation_id)
AREV=$(echo "$PUB" | jq -r .revision_id)
echo "   草稿: animation=${ANID} revision=${AREV} status=$(echo "$PUB" | jq -r .revision_status)"
PUB2=$(curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/integrations/mangaweaver/publications" \
  -H 'Content-Type: application/json' -d "{
    \"schema_version\":1,\"title\":\"信号之森·动画版\",\"requested_visibility\":\"PUBLIC\",
    \"origin_run_ref\":\"$RUNREF\",
    \"sources\":[{\"chapter_revision_id\":\"$CREV\",\"page_start\":1,\"page_end\":2,\"ordinal\":1}]}")
[ "$(echo "$PUB2" | jq -r .revision_id)" = "$AREV" ] && echo "   同键重放返回同一草稿 ✓" || { echo "   FAIL: $PUB2"; exit 1; }

echo "== 6. 上传成片（Bearer，ANIMATION_VIDEO）+ verify =="
ffmpeg -y -v error -f lavfi -i "testsrc=duration=4:size=640x360:rate=24" \
  -f lavfi -i "sine=frequency=520:duration=4" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$WORK/film.mp4"
VSIZE=$(stat -f%z "$WORK/film.mp4")
VC=$(curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/uploads" -H 'Content-Type: application/json' \
  -d "{\"purpose\":\"ANIMATION_VIDEO\",\"expectedSizeBytes\":$VSIZE,\"contentType\":\"video/mp4\",\"idempotencyKey\":\"m3-film-$TS\"}")
VUP=$(echo "$VC" | jq -r .uploadId)
curl -s -m 30 -X PUT "$(echo "$VC" | jq -r .presigned.url)" -H "Content-Type: video/mp4" --upload-file "$WORK/film.mp4" > /dev/null
curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/uploads/$VUP/complete" > /dev/null
OK=0; for i in $(seq 1 60); do
  S=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/uploads/$VUP" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && OK=1 && break; sleep 1
done
[ "$OK" = "1" ] || { echo "   FAIL: 视频核验超时"; exit 1; }
VASSET=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/uploads/$VUP" | jq -r .assetId)
VVERIFY=$(curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/animation-revisions/$AREV/verify" \
  -H 'Content-Type: application/json' -d "{\"video_asset_id\":\"$VASSET\"}")
echo "   verify 响应: $VVERIFY"
VJOB=$(echo "$VVERIFY" | jq -r .job_id)
[ "$VJOB" != "null" ] && [ -n "$VJOB" ] || { echo "   FAIL: verify 未受理"; exit 1; }
OK=0; for i in $(seq 1 180); do
  ST=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/animation-revisions/$AREV" | jq -r .status)
  [ "$ST" = "READY" ] && OK=1 && break; [ "$ST" = "FAILED" ] && { echo "   验证失败"; exit 1; }; sleep 1
done
[ "$OK" = "1" ] && echo "   媒体验证 READY ✓" || { echo "   FAIL: 验证超时"; exit 1; }

echo "== 7. 状态查询 → 发布（animations:publish）→ 回执 =="
ST0=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/integrations/mangaweaver/publications/$RUNREF")
echo "   发布前: $(echo "$ST0" | jq -c '{revision_status, watch_url}')"
curl -s -m 15 -H "$AUTH" -X POST "$BASE/api/v1/animation-revisions/$AREV/publish" > /dev/null
ST1=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/integrations/mangaweaver/publications/$RUNREF")
WATCH=$(echo "$ST1" | jq -r .watch_url)
echo "$ST1" | jq -e '.revision_status == "PUBLISHED" and .watch_url' > /dev/null \
  && echo "   回执: watch_url=$WATCH ✓" || { echo "   FAIL: $ST1"; exit 1; }

echo "== 8. 网站播放（同 Token 直接读 playback + Range）=="
PB=$(curl -s -m 10 -H "$AUTH" "$BASE/api/v1/animations/$ANID/playback")
VURL=$(echo "$PB" | jq -r .videoUrl)
CODE=$(curl -s -m 15 -o /dev/null -w '%{http_code}' "$VURL")
RANGE=$(curl -s -m 15 -o /dev/null -w '%{http_code} %{size_download}' -H "Range: bytes=0-499" "$VURL")
echo "   播放: HTTP ${CODE}；Range: $RANGE"
[ "$CODE" = "200" ] && echo "$RANGE" | grep -q "206 500" || { echo "   FAIL"; exit 1; }

echo "== 9. 边界：scope 不足 + 撤销 =="
RO_TOKEN=$(site_req POST /api/v1/me/api-tokens \
  "{\"scopes\":[\"catalog:read\",\"materials:read\"],\"expiresInDays\":30}" | jq -r .rawToken)
R403=$(curl -s -m 10 -H "Authorization: Bearer $RO_TOKEN" -X POST "$BASE/api/v1/animation-revisions/$AREV/publish")
echo "$R403" | grep -q TOKEN_SCOPE_INSUFFICIENT && echo "   缺 animations:publish → 403 ✓" || { echo "   FAIL: $R403"; exit 1; }
PREFIX=$(echo "$TOKEN" | cut -c1-12)
TID=$(site_req GET /api/v1/me/api-tokens | jq -r --arg p "$PREFIX" '[.[] | select(.prefix == $p)][0].id')
echo "   撤销主 Token（${PREFIX}…，id=${TID}）"
site_req DELETE "/api/v1/me/api-tokens/$TID" > /dev/null
R401=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE/api/v1/works")
[ "$R401" = "401" ] && echo "   撤销后请求 → 401 ✓" || { echo "   FAIL: $R401"; exit 1; }

echo ""
echo "M3 SMOKE PASSED ✅  animation=$ANID revision=$AREV run=$RUNREF"
