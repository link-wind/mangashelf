#!/usr/bin/env bash
# M2 全链路冒烟：漫画发布 → 上传视频 → ffprobe 验证 → 绑定来源 → 发布动画 →
# 播放（Range 206）→ 页级关联 → 进度/换版 → 越权与未完成发布拒绝
# 前置：compose 服务健康；API(8080)/Worker 已运行；jq、ffmpeg 可用
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke-m2"
JAR="$WORK/cookies"
rm -rf "$WORK" && mkdir -p "$WORK/cookies"

PYTHON="${MANGASHELF_PYTHON:-$REPO_ROOT/processing/.venv/bin/python}"

req() {
  local method="$1"
  local path="$2"
  local jar="$3"
  local data="${4:-}"
  local xsrf; xsrf=$(grep XSRF-TOKEN "$jar" 2>/dev/null | awk '{print $7}' | tail -1)
  if [ "$method" = "GET" ]; then
    curl -s -m 15 -b "$jar" "$BASE$path"
  else
    curl -s -m 60 -b "$jar" -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
      -H "X-XSRF-TOKEN: $xsrf" ${data:+-d "$data"}
  fi
}

login_user() {
  local name="$1"
  local pass="$2"
  local jar="$JAR/$name.txt"
  curl -s -m 10 -c "$jar" "$BASE/api/v1/works" -o /dev/null
  req POST /api/v1/auth/register "$jar" "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  req POST /api/v1/auth/login "$jar" "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  if ! grep -q SESSION "$jar"; then echo "登录失败: $name" >&2; exit 1; fi
  echo "$jar"
}

poll_revision() {
  local jar="$1" id="$2" expect="$3" timeout="${4:-180}"
  for _ in $(seq 1 "$timeout"); do
    SNAP=$(req GET "/api/v1/animation-revisions/$id" "$jar")
    ST=$(echo "$SNAP" | jq -r .status)
    if [ "$ST" = "FAILED" ]; then echo "动画验证失败: $SNAP" >&2; return 1; fi
    if [ "$ST" = "$expect" ]; then echo "$SNAP"; return 0; fi
    sleep 1
  done
  echo "等待 $expect 超时: $SNAP" >&2; return 1
}

echo "== 1. 用户与漫画来源（A 上传 4 页漫画并发布）=="
TS=$(date +%s)
A_JAR=$(login_user "carol$TS" "carol-pass-1234")
B_JAR=$(login_user "dave$TS" "dave-pass-12345")
WID=$(req POST /api/v1/works "$A_JAR" '{"title":"回声谷","description":"M2 来源"}' | jq -r .work.id)
CHID=$(req POST "/api/v1/works/$WID/chapters" "$A_JAR" '{"title":"第1话"}' | jq -r .id)
"$PYTHON" - "$WORK/page" <<'PY'
from PIL import Image, ImageDraw
import sys
for i in range(1, 5):
    img = Image.new('RGB', (800, 1100), (250, 244, 235))
    ImageDraw.Draw(img).text((50, 50), f"echo page {i}", fill=(30, 30, 30))
    img.save(f"{sys.argv[1]}-{i}.png", "PNG")
PY
# 4 页 PNG 打成 CBZ
"$PYTHON" - "$WORK" <<'PY'
import zipfile, sys
with zipfile.ZipFile(f"{sys.argv[1]}/manga.cbz", "w") as z:
    for i in range(1, 5):
        z.write(f"{sys.argv[1]}/page-{i}.png", f"p{i}.png")
PY
SIZE=$(stat -f%z "$WORK/manga.cbz")
C=$(req POST /api/v1/uploads "$A_JAR" "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"m2-manga-$TS\"}")
UPID=$(echo "$C" | jq -r .uploadId)
curl -s -m 30 -X PUT "$(echo "$C" | jq -r .presigned.url)" -H "Content-Type: application/zip" --upload-file "$WORK/manga.cbz" > /dev/null
req POST "/api/v1/uploads/$UPID/complete" "$A_JAR" > /dev/null
VERIFY_OK=0
for i in $(seq 1 60); do
  S=$(req GET "/api/v1/uploads/$UPID" "$A_JAR" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && VERIFY_OK=1 && break; sleep 1
done
[ "$VERIFY_OK" = "1" ] || { echo "   FAIL: 核验未完成"; exit 1; }
CREV=$(req POST "/api/v1/chapters/$CHID/revisions" "$A_JAR" "{\"uploadId\":\"$UPID\"}" | jq -r .revisionId)
CH_READY=0
for i in $(seq 1 120); do
  ST=$(req GET "/api/v1/chapter-revisions/$CREV" "$A_JAR" | jq -r .status)
  [ "$ST" = "READY" ] && CH_READY=1 && break; [ "$ST" = "FAILED" ] && { echo "解析失败" >&2; exit 1; }; sleep 1
done
[ "$CH_READY" = "1" ] || { echo "   FAIL: 章节解析超时"; exit 1; }
req POST "/api/v1/chapter-revisions/$CREV/publish" "$A_JAR" > /dev/null
req PATCH "/api/v1/works/$WID" "$A_JAR" '{"visibility":"PUBLIC"}' > /dev/null
req POST "/api/v1/works/$WID/publish" "$A_JAR" > /dev/null
echo "   漫画已发布（revision=${CREV}，4 页，PUBLIC）"

echo "== 2. 生成测试 MP4（h264+aac，4 秒）=="
ffmpeg -y -v error -f lavfi -i "testsrc=duration=4:size=640x360:rate=24" \
  -f lavfi -i "sine=frequency=440:duration=4" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$WORK/test.mp4"
printf '1\n00:00:00,500 --> 00:00:02,000\n欢迎来到回声谷\n' > "$WORK/sub.srt"
VSIZE=$(stat -f%z "$WORK/test.mp4")
echo "   test.mp4 = $VSIZE bytes"

echo "== 3. 创建动画 → 上传视频 → ffprobe 验证 =="
ANID=$(req POST /api/v1/animations "$A_JAR" '{"title":"回声谷·动画版","description":"M2"}' | jq -r .id)
AREV=$(req POST "/api/v1/animations/$ANID/revisions" "$A_JAR" | jq -r .revisionId)
VC=$(req POST /api/v1/uploads "$A_JAR" "{\"purpose\":\"ANIMATION_VIDEO\",\"expectedSizeBytes\":$VSIZE,\"contentType\":\"video/mp4\",\"idempotencyKey\":\"m2-video-$TS\"}")
VUP=$(echo "$VC" | jq -r .uploadId)
curl -s -m 60 -X PUT "$(echo "$VC" | jq -r .presigned.url)" -H "Content-Type: video/mp4" --upload-file "$WORK/test.mp4" > /dev/null
req POST "/api/v1/uploads/$VUP/complete" "$A_JAR" > /dev/null
VERIFY_OK=0
for i in $(seq 1 60); do
  S=$(req GET "/api/v1/uploads/$VUP" "$A_JAR" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && VERIFY_OK=1 && break; sleep 1
done
[ "$VERIFY_OK" = "1" ] || { echo "   FAIL: 视频核验未完成"; exit 1; }
VASSET=$(req GET "/api/v1/uploads/$VUP" "$A_JAR" | jq -r .assetId)
SC=$(req POST /api/v1/uploads "$A_JAR" "{\"purpose\":\"ANIMATION_SUBTITLE\",\"expectedSizeBytes\":$(stat -f%z "$WORK/sub.srt"),\"contentType\":\"application/x-subrip\",\"idempotencyKey\":\"m2-sub-$TS\"}")
SUP=$(echo "$SC" | jq -r .uploadId)
curl -s -m 15 -X PUT "$(echo "$SC" | jq -r .presigned.url)" -H "Content-Type: application/x-subrip" --upload-file "$WORK/sub.srt" > /dev/null
req POST "/api/v1/uploads/$SUP/complete" "$A_JAR" > /dev/null
SUB_OK=0
for i in $(seq 1 60); do
  S=$(req GET "/api/v1/uploads/$SUP" "$A_JAR" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && SUB_OK=1 && break; sleep 1
done
[ "$SUB_OK" = "1" ] || { echo "   FAIL: 字幕核验未完成"; exit 1; }
SASSET=$(req GET "/api/v1/uploads/$SUP" "$A_JAR" | jq -r .assetId)
[ "$SASSET" != "null" ] || { echo "   FAIL: 字幕资产缺失"; exit 1; }
ACCEPTED=$(req POST "/api/v1/animation-revisions/$AREV/verify" "$A_JAR" \
  "{\"videoAssetId\":\"$VASSET\",\"subtitleAssetId\":\"$SASSET\",\"sources\":[{\"chapterRevisionId\":\"$CREV\",\"pageStart\":2,\"pageEnd\":4,\"ordinal\":1}]}")
echo "   验证任务: $(echo "$ACCEPTED" | jq -r .jobId)"
SNAP=$(poll_revision "$A_JAR" "$AREV" READY 180)
echo "   READY: $(echo "$SNAP" | jq -c '{durationMs, videoWidth, videoHeight, videoCodec, audioCodec, hasAudio}')"
DUR=$(echo "$SNAP" | jq -r .durationMs)
[ "$DUR" -ge 3000 ] && [ "$DUR" -le 6000 ] || { echo "   FAIL: 时长异常 $DUR"; exit 1; }

echo "== 4. 媒体未完成时发布被拒（新版本直接发布）=="
AREV2=$(req POST "/api/v1/animations/$ANID/revisions" "$A_JAR" | jq -r .revisionId)
if req POST "/api/v1/animation-revisions/$AREV2/publish" "$A_JAR" | grep -qE "INVALID_STATE|VIDEO_NOT_BOUND"; then
  echo "   未验证版本发布被拒 ✓"
else
  echo "   FAIL: 未验证版本竟然可以发布"; exit 1
fi

echo "== 5. 发布动画（公开）与播放 =="
req POST "/api/v1/animation-revisions/$AREV/publish" "$A_JAR" > /dev/null
req PATCH "/api/v1/animations/$ANID" "$A_JAR" '{"visibility":"PUBLIC"}' > /dev/null || true
# visibility 通过 PATCH /animations/{id} 不存在——用专门路径？先检查：
PB=$(req GET "/api/v1/animations/$ANID/playback" "$B_JAR")
if ! echo "$PB" | jq -e .videoUrl > /dev/null 2>&1; then
  echo "   B 无法播放（可能未公开）：$PB"
fi
echo "   playback: $(echo "$PB" | jq -c '{revisionId, durationMs, revisionMatches}')"
VIDEO_URL=$(echo "$PB" | jq -r .videoUrl)
CODE=$(curl -s -m 15 -o /dev/null -w '%{http_code}' "$VIDEO_URL")
echo "   完整拉取: HTTP $CODE"
[ "$CODE" = "200" ] || { echo "   FAIL: 视频不可读"; exit 1; }
RANGE=$(curl -s -m 15 -o /dev/null -w '%{http_code} %{size_download}' -H "Range: bytes=0-999" "$VIDEO_URL")
echo "   Range 0-999: ${RANGE}（应 206 + 1000 字节）"
echo "$RANGE" | grep -q "206 1000" || { echo "   FAIL: Range 不工作"; exit 1; }

echo "== 6. 页级关联（漫画页 2–4 覆盖）=="
ADAPT=$(req GET "/api/v1/chapter-revisions/$CREV/animations?page=3" "$B_JAR")
echo "$ADAPT" | jq -e ".[] | select(.animationId == \"$ANID\" and .pageStart == 2 and .pageEnd == 4)" > /dev/null \
  && echo "   页 3 关联到动画（2-4 页）✓" || { echo "   FAIL: 页级关联缺失: $ADAPT"; exit 1; }
ADAPT1=$(req GET "/api/v1/chapter-revisions/$CREV/animations?page=1" "$B_JAR")
[ "$(echo "$ADAPT1" | jq 'length')" = "0" ] && echo "   页 1 无关联（不在范围）✓" || echo "   注意：页 1 有关联"

echo "== 7. 观看进度：续播 + 冲突 =="
req PUT "/api/v1/me/watch-progress/$ANID" "$B_JAR" '{"positionMs":2500,"expectedVersion":0}' > /dev/null
PB2=$(req GET "/api/v1/animations/$ANID/playback" "$B_JAR")
POS=$(echo "$PB2" | jq -r '.progress.positionMs')
[ "$POS" = "2500" ] && echo "   进度已记录（2500ms）✓" || { echo "   FAIL: 进度未记录"; exit 1; }
CONFLICT=$(req PUT "/api/v1/me/watch-progress/$ANID" "$B_JAR" '{"positionMs":100,"expectedVersion":0}')
echo "$CONFLICT" | grep -q PROGRESS_CONFLICT && echo "   过期版本写入被拒 ✓" || { echo "   FAIL: $CONFLICT"; exit 1; }

echo "== 8. 换版不混淆（revisionMatches=false）=="
AREV3=$(req POST "/api/v1/animations/$ANID/revisions" "$A_JAR" | jq -r .revisionId)
VC3=$(req POST /api/v1/uploads "$A_JAR" "{\"purpose\":\"ANIMATION_VIDEO\",\"expectedSizeBytes\":$VSIZE,\"contentType\":\"video/mp4\",\"idempotencyKey\":\"m2-video2-$TS\"}")
VUP3=$(echo "$VC3" | jq -r .uploadId)
curl -s -m 60 -X PUT "$(echo "$VC3" | jq -r .presigned.url)" -H "Content-Type: video/mp4" --upload-file "$WORK/test.mp4" > /dev/null
req POST "/api/v1/uploads/$VUP3/complete" "$A_JAR" > /dev/null
VERIFY_OK=0
for i in $(seq 1 60); do
  S=$(req GET "/api/v1/uploads/$VUP3" "$A_JAR" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && VERIFY_OK=1 && break; sleep 1
done
[ "$VERIFY_OK" = "1" ] || { echo "   FAIL: 视频核验未完成"; exit 1; }
VASSET3=$(req GET "/api/v1/uploads/$VUP3" "$A_JAR" | jq -r .assetId)
req POST "/api/v1/animation-revisions/$AREV3/verify" "$A_JAR" \
  "{\"videoAssetId\":\"$VASSET3\",\"sources\":[{\"chapterRevisionId\":\"$CREV\",\"pageStart\":1,\"pageEnd\":2,\"ordinal\":1}]}" > /dev/null
poll_revision "$A_JAR" "$AREV3" READY 180 > /dev/null
req POST "/api/v1/animation-revisions/$AREV3/publish" "$A_JAR" > /dev/null
PB3=$(req GET "/api/v1/animations/$ANID/playback" "$B_JAR")
MATCH=$(echo "$PB3" | jq -r .revisionMatches)
[ "$MATCH" = "false" ] && echo "   新版本下 revisionMatches=false（提示从头播放）✓" || { echo "   FAIL: $PB3"; exit 1; }
BOUND=$(echo "$PB3" | jq -r '.progress.revisionId')
[ "$BOUND" = "$(echo "$PB2" | jq -r .revisionId)" ] && echo "   旧进度仍绑定旧版本 ✓" || { echo "   FAIL: 绑定丢失"; exit 1; }

echo "== 9. 越权与来源校验 =="
PRIV_AN=$(req POST /api/v1/animations "$A_JAR" '{"title":"私人动画"}' | jq -r .id)
if req GET "/api/v1/animations/$PRIV_AN/playback" "$B_JAR" | grep -q ANIMATION_NOT_FOUND; then
  echo "   B 播放 A 的私人动画 → 404 ✓"
else
  echo "   FAIL: 私人动画泄漏"; exit 1
fi
BAD=$(req POST "/api/v1/animation-revisions/$AREV2/verify" "$A_JAR" \
  "{\"videoAssetId\":\"$VASSET\",\"sources\":[{\"chapterRevisionId\":\"$CREV\",\"pageStart\":1,\"pageEnd\":99,\"ordinal\":1}]}")
echo "$BAD" | grep -q PAGE_RANGE_INVALID && echo "   页码范围越界被拒（来源仅 4 页）✓" || { echo "   FAIL: $BAD"; exit 1; }

echo ""
echo "M2 SMOKE PASSED ✅  animation=$ANID rev1=$AREV rev2=$AREV3"
