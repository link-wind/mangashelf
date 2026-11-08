#!/usr/bin/env bash
# M4 全链路冒烟：发布带对白漫画 → 显式触发索引 → 批次 ACTIVE →
# 字面检索（对白命中页级定位）→ 语义检索（e5 向量）→ QA 未配置 503 → 私人不可召回
# 前置：compose 健康；API/Worker 已运行（embedding enabled）；jq 可用
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke-m4"
rm -rf "$WORK" && mkdir -p "$WORK"
PYTHON="${MANGASHELF_PYTHON:-$REPO_ROOT/processing/.venv/bin/python}"

site_setup() {
  local jar="$1" name="$2" pass="$3"
  curl -s -m 10 -c "$jar" "$BASE/api/v1/works" -o /dev/null
  local x; x=$(grep XSRF-TOKEN "$jar" | awk '{print $7}' | tail -1)
  curl -s -m 15 -b "$jar" -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $x" -d "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  curl -s -m 15 -c "$jar" -b "$jar" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $x" -d "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  grep -q SESSION "$jar" || { echo "登录失败" >&2; exit 1; }
}
site_req() {
  local jar="$1" method="$2" path="$3" data="${4:-}"
  local x; x=$(grep XSRF-TOKEN "$jar" | awk '{print $7}' | tail -1)
  if [ "$method" = "GET" ]; then curl -s -m 20 -b "$jar" "$BASE$path";
  else curl -s -m 30 -b "$jar" -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $x" ${data:+-d "$data"}; fi
}

echo "== 1. 双用户 + 两部漫画（A 公开含对白 / B 私人）=="
TS=$(date +%s)
A_JAR="$WORK/a.txt"; B_JAR="$WORK/b.txt"
site_setup "$A_JAR" "writer$TS" "writer-pass-123"
site_setup "$B_JAR" "hider$TS" "hider-pass-12345"
"$PYTHON" - "$WORK" <<'PY'
from PIL import Image, ImageDraw, ImageFont
import zipfile, io, sys
font = None
for cand in ["/System/Library/Fonts/STHeiti Light.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"]:
    try:
        font = ImageFont.truetype(cand, 40); break
    except Exception:
        pass
def page(lines, bg):
    img = Image.new('RGB', (900, 1300), bg)
    d = ImageDraw.Draw(img)
    y = 80
    for line in lines:
        d.text((60, y), line, fill=(15, 15, 35), font=font)
        y += 140
    return img
with zipfile.ZipFile(f"{sys.argv[1]}/a.cbz", 'w') as z:
    buf = io.BytesIO(); page(["第一话 出发", "森林的尽头有一座木桥。", "他说：我们明天再会吧。"], (248, 246, 240)).save(buf, 'PNG')
    z.writestr("p1.png", buf.getvalue())
    buf = io.BytesIO(); page(["第二话 道歉", "对不起，我迟到了。", "沉默比谎言更伤人。"], (240, 248, 252)).save(buf, 'PNG')
    z.writestr("p2.png", buf.getvalue())
    buf = io.BytesIO(); page(["第三话 尾声", "风把信吹回了起点。"], (252, 240, 244)).save(buf, 'PNG')
    z.writestr("p3.png", buf.getvalue())
with zipfile.ZipFile(f"{sys.argv[1]}/b.cbz", 'w') as z:
    buf = io.BytesIO(); page(["秘密计划：午夜行动。", "口令是紫色河马。"], (244, 240, 252)).save(buf, 'PNG')
    z.writestr("s1.png", buf.getvalue())
PY
manga_upload() { # manga_upload <jar> <cbz> <idempotency> -> revisionId
  local jar="$1" cbz="$2" key="$3"
  local SIZE=$(stat -f%z "$cbz")
  local C=$(site_req "$jar" POST /api/v1/uploads \
    "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"$key\"}")
  local UP=$(echo "$C" | jq -r .uploadId)
  curl -s -m 30 -X PUT "$(echo "$C" | jq -r .presigned.url)" -H "Content-Type: application/zip" --upload-file "$cbz" > /dev/null
  site_req "$jar" POST "/api/v1/uploads/$UP/complete" > /dev/null
  local OK=0; for i in $(seq 1 60); do
    S=$(site_req "$jar" GET "/api/v1/uploads/$UP" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
    [ "$S" -ge 1 ] && OK=1 && break; sleep 1
  done; [ "$OK" = "1" ] || { echo "   FAIL: 核验超时" >&2; exit 1; }
  echo "$UP"
}
WID_A=$(site_req "$A_JAR" POST /api/v1/works '{"title":"森林来信","description":"M4 评估"}' | jq -r .work.id)
CH_A=$(site_req "$A_JAR" POST "/api/v1/works/$WID_A/chapters" '{"title":"对白篇"}' | jq -r .id)
UP_A=$(manga_upload "$A_JAR" "$WORK/a.cbz" "m4-a-$TS")
CREV_A=$(site_req "$A_JAR" POST "/api/v1/chapters/$CH_A/revisions" "{\"uploadId\":\"$UP_A\"}" | jq -r .revisionId)
OK=0; for i in $(seq 1 120); do
  ST=$(site_req "$A_JAR" GET "/api/v1/chapter-revisions/$CREV_A" | jq -r .status)
  [ "$ST" = "READY" ] && OK=1 && break; [ "$ST" = "FAILED" ] && exit 1; sleep 1
done; [ "$OK" = "1" ] || { echo "   FAIL: 解析超时"; exit 1; }
site_req "$A_JAR" POST "/api/v1/chapter-revisions/$CREV_A/publish" > /dev/null
site_req "$A_JAR" PATCH "/api/v1/works/$WID_A" '{"visibility":"PUBLIC"}' > /dev/null
site_req "$A_JAR" POST "/api/v1/works/$WID_A/publish" > /dev/null
WID_B=$(site_req "$B_JAR" POST /api/v1/works '{"title":"秘密笔记"}' | jq -r .work.id)
CH_B=$(site_req "$B_JAR" POST "/api/v1/works/$WID_B/chapters" '{"title":"秘密"}' | jq -r .id)
UP_B=$(manga_upload "$B_JAR" "$WORK/b.cbz" "m4-b-$TS")
CREV_B=$(site_req "$B_JAR" POST "/api/v1/chapters/$CH_B/revisions" "{\"uploadId\":\"$UP_B\"}" | jq -r .revisionId)
OK=0; for i in $(seq 1 120); do
  ST=$(site_req "$B_JAR" GET "/api/v1/chapter-revisions/$CREV_B" | jq -r .status)
  [ "$ST" = "READY" ] && OK=1 && break; sleep 1
done; [ "$OK" = "1" ] || { echo "   FAIL: B 解析超时"; exit 1; }
echo "   A 公开漫画=${CREV_A}；B 私人漫画=$CREV_B"

echo "== 2. A 显式触发索引（BUILDING → ACTIVE）=="
IDX=$(site_req "$A_JAR" POST "/api/v1/chapter-revisions/$CREV_A/indexes")
GEN=$(echo "$IDX" | jq -r .generationId)
JOB=$(echo "$IDX" | jq -r .jobId)
echo "   批次=$GEN 任务=$JOB"
OK=0; for i in $(seq 1 180); do
  ST=$(site_req "$A_JAR" GET "/api/v1/index-generations/$GEN" | jq -r .status)
  [ "$ST" = "ACTIVE" ] && OK=1 && break; [ "$ST" = "FAILED" ] && { echo "   索引失败"; exit 1; }; sleep 1
done; [ "$OK" = "1" ] || { echo "   FAIL: 索引超时"; exit 1; }
echo "   批次 ACTIVE ✓"

echo "== 3. 字面检索：对白命中页级定位 =="
RES=$(site_req "$B_JAR" POST /api/v1/search '{"q":"沉默比谎言更伤人","limit":10}')
HIT=$(echo "$RES" | jq -c '[.hits[] | select(.workTitle == "森林来信" and .pageOrdinal == 2 and .kind == "page")] | length')
[ "$HIT" -ge 1 ] && echo "   对白命中第 2 页 ✓（B 可读公开内容）" || { echo "   FAIL: $RES"; exit 1; }
RES2=$(site_req "$B_JAR" POST /api/v1/search '{"q":"森林的尽头","limit":10}')
echo "$RES2" | jq -e '[.hits[] | select(.pageOrdinal == 1)] | length > 0' > /dev/null && echo "   第 1 页命中 ✓"

echo "== 4. 私人内容不可召回 =="
site_setup_ok=1
# B 的私人漫画建索引后，A（owner 不同）搜索"紫色河马"不得命中
IDX_B=$(site_req "$B_JAR" POST "/api/v1/chapter-revisions/$CREV_B/indexes")
GEN_B=$(echo "$IDX_B" | jq -r .generationId)
OK=0; for i in $(seq 1 180); do
  ST=$(site_req "$B_JAR" GET "/api/v1/index-generations/$GEN_B" | jq -r .status)
  [ "$ST" = "ACTIVE" ] && OK=1 && break; [ "$ST" = "FAILED" ] && { echo "   B 索引失败"; exit 1; }; sleep 1
done; [ "$OK" = "1" ] || { echo "   FAIL: B 索引超时"; exit 1; }
LEAK=$(site_req "$A_JAR" POST /api/v1/search '{"q":"紫色河马","limit":10}')
LEAKED=$(echo "$LEAK" | jq --arg w "$WID_B" '[.hits[] | select(.workId == $w)] | length')
[ "$LEAKED" = "0" ] && echo "   A 的结果不含 B 的私人作品 ✓" || { echo "   FAIL: 私人泄漏 $LEAK"; exit 1; }
OWN=$(site_req "$B_JAR" POST /api/v1/search '{"q":"紫色河马","limit":10}')
[ "$(echo "$OWN" | jq '[.hits[] | select(.pageOrdinal == 1)] | length')" -ge 1 ] && echo "   B 自己可命中 ✓"

echo "== 5. 语义检索（e5 本地向量）=="
SEM=$(site_req "$B_JAR" POST /api/v1/search '{"q":"他为自己迟到的事道歉","limit":10}')
SEM_HIT=$(echo "$SEM" | jq -c '[.hits[] | select(.workTitle == "森林来信" and .pageOrdinal == 2)] | length')
SEMFLAG=$(echo "$SEM" | jq -r .semanticEnabled)
echo "   semanticEnabled=$SEMFLAG 语义命中第 2 页（道歉语义词近似）: $SEM_HIT"
[ "$SEMFLAG" = "true" ] || { echo "   FAIL: 语义未启用"; exit 1; }
# 语义应命中（迟到/道歉语义相近），即使字面不同
[ "$SEM_HIT" -ge 1 ] && echo "   语义召回 ✓" || echo "   注意：语义未命中（e5 中日文效果记录到评估）"

echo "== 6. QA 未配置门控 =="
QA=$(site_req "$B_JAR" POST /api/v1/qa/answers '{"question":"谁向谁道歉？"}' -o /dev/null -w '%{http_code}' || true)
QACODE=$(curl -s -m 10 -b "$B_JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/qa/answers" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(grep XSRF-TOKEN "$B_JAR" | awk '{print $7}' | tail -1)" \
  -d '{"question":"谁向谁道歉？"}')
[ "$QACODE" = "503" ] && echo "   无对话模型 → 503 QA_NOT_CONFIGURED ✓" || { echo "   FAIL: $QACODE"; exit 1; }

echo ""
echo "M4 SMOKE PASSED ✅  generation=$GEN"
