#!/usr/bin/env bash
# M1 全链路冒烟：双用户注册登录 → A 上传 CBZ/解析/发布 → B 阅读/续读/进度冲突 →
# 换版不混页 → 私人内容不可见
# 前置：compose 服务健康；API(8080)/Worker 已运行；jq 可用
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke-m1"
JAR="$WORK/cookies"
rm -rf "$WORK" && mkdir -p "$WORK/cookies"

PYTHON="${MANGASHELF_PYTHON:-$REPO_ROOT/processing/.venv/bin/python}"

req() { # req <method> <path> <cookiejar> [data]
  local method="$1"
  local path="$2"
  local jar="$3"
  local data="${4:-}"
  local xsrf; xsrf=$(grep XSRF-TOKEN "$jar" 2>/dev/null | awk '{print $7}' | tail -1)
  if [ "$method" = "GET" ]; then
    curl -s -m 10 -b "$jar" "$BASE$path"
  else
    curl -s -m 20 -b "$jar" -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
      -H "X-XSRF-TOKEN: $xsrf" ${data:+-d "$data"}
  fi
}

login_user() { # login_user <name> <password> -> echoes cookie jar path
  local name="$1"
  local pass="$2"
  local jar="$JAR/$name.txt"
  curl -s -m 10 -c "$jar" "$BASE/api/v1/works" -o /dev/null
  local out code
  out=$(req POST /api/v1/auth/register "$jar" "{\"login\":\"$name\",\"password\":\"$pass\"}") && true
  code=$(req POST /api/v1/auth/login "$jar" "{\"login\":\"$name\",\"password\":\"$pass\"}")
  if ! grep -q SESSION "$jar"; then echo "登录失败: $code" >&2; exit 1; fi
  echo "$jar"
}

poll_upload() { # poll_upload <jar> <uploadId> <timeout>
  local jar="$1"
  local id="$2"
  local timeout="${3:-60}"
  for _ in $(seq 1 "$timeout"); do
    SNAP=$(req GET "/api/v1/uploads/$id" "$jar")
    if echo "$SNAP" | jq -e '.jobs[] | select(.status == "FAILED")' > /dev/null; then
      echo "任务失败: $SNAP" >&2; return 1
    fi
    echo "$SNAP" | jq -e '.jobs[] | select(.kind == "content.verify" and .status == "SUCCEEDED")' >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "核验超时" >&2; return 1
}

poll_revision() { # poll_revision <jar> <revisionId> <expectStatus> <timeout>
  local jar="$1"
  local id="$2"
  local expect="$3"
  local timeout="${4:-120}"
  for _ in $(seq 1 "$timeout"); do
    SNAP=$(req GET "/api/v1/chapter-revisions/$id" "$jar")
    ST=$(echo "$SNAP" | jq -r .status)
    if [ "$ST" = "FAILED" ]; then echo "解析失败: $SNAP" >&2; return 1; fi
    if [ "$ST" = "$expect" ]; then echo "$SNAP"; return 0; fi
    sleep 1
  done
  echo "等待 $expect 超时: $SNAP" >&2; return 1
}

echo "== 1. 双用户注册登录 =="
TS=$(date +%s)
ALICE_JAR=$(login_user "alice$TS" "alice-pass-123")
BOB_JAR=$(login_user "bob$TS" "bob-pass-12345")
echo "   alice=$ALICE_JAR bob=$BOB_JAR"

echo "== 2. A 建作品/章节并上传 CBZ（乱序文件名，8 页）=="
WORK_ID=$(req POST /api/v1/works "$ALICE_JAR" '{"title":"星空的彼岸","description":"M1 验收"}' | jq -r .work.id)
CH_ID=$(req POST "/api/v1/works/$WORK_ID/chapters" "$ALICE_JAR" '{"title":"第1话 启程"}' | jq -r .id)
"$PYTHON" - "$WORK/test.cbz" <<'PY'
from PIL import Image, ImageDraw
import zipfile, io, sys
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    for i in [5, 1, 8, 3, 7, 2, 6, 4]:  # 乱序文件名，验证自然排序
        img = Image.new('RGB', (800, 1200), (240, 235, 250))
        ImageDraw.Draw(img).text((60, 60), f"page {i:03d}", fill=(15, 15, 30))
        buf = io.BytesIO(); img.save(buf, 'JPEG'); z.writestr(f"scene_{i:03d}.jpg", buf.getvalue())
PY
SIZE=$(stat -f%z "$WORK/test.cbz")
CREATE=$(req POST /api/v1/uploads "$ALICE_JAR" "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"m1-$TS\"}")
UPLOAD_ID=$(echo "$CREATE" | jq -r .uploadId)
PUT_URL=$(echo "$CREATE" | jq -r .presigned.url)
curl -s -m 30 -X PUT "$PUT_URL" -H "Content-Type: application/zip" --upload-file "$WORK/test.cbz" > /dev/null
COMPLETE=$(req POST "/api/v1/uploads/$UPLOAD_ID/complete" "$ALICE_JAR")
echo "   核验任务: $(echo "$COMPLETE" | jq -r .jobId)"
poll_upload "$ALICE_JAR" "$UPLOAD_ID" 60
echo "   上传已核验 VERIFIED"

echo "== 3. 创建章节版本 → chapter.parse → READY =="
REV1=$(req POST "/api/v1/chapters/$CH_ID/revisions" "$ALICE_JAR" "{\"uploadId\":\"$UPLOAD_ID\"}")
REV1_ID=$(echo "$REV1" | jq -r .revisionId)
SNAP=$(poll_revision "$ALICE_JAR" "$REV1_ID" READY 120)
PAGE_COUNT=$(echo "$SNAP" | jq -r .pageCount)
echo "   版本 $REV1_ID READY，页数=$PAGE_COUNT"
[ "$PAGE_COUNT" = "8" ] || { echo "   FAIL: 页数应为 8"; exit 1; }

echo "== 4. 页序确认（含冲突测试）=="
PAGES=$(req GET "/api/v1/chapter-revisions/$REV1_ID/pages" "$ALICE_JAR")
EDIT_V=$(echo "$PAGES" | jq -r .editVersion)
ORDER=$(echo "$PAGES" | jq -c '[.pages[].id] | reverse')  # 倒序重排，验证调序生效
req PATCH "/api/v1/chapter-revisions/$REV1_ID/page-order" "$ALICE_JAR" \
  "{\"orderedPageIds\":$ORDER,\"expectedEditVersion\":$EDIT_V}" | jq -c '{status, editVersion}'
if req PATCH "/api/v1/chapter-revisions/$REV1_ID/page-order" "$ALICE_JAR" \
     "{\"orderedPageIds\":$ORDER,\"expectedEditVersion\":$EDIT_V}" | grep -q EDIT_VERSION_CONFLICT; then
  echo "   旧 edit_version 重放被拒（409 冲突）✓"
else
  echo "   FAIL: 过期页序确认未被拒绝"; exit 1
fi

echo "== 5. 发布版本与作品 =="
req POST "/api/v1/chapter-revisions/$REV1_ID/publish" "$ALICE_JAR" | jq -c '{status, revisionNo}'
if req POST "/api/v1/works/$WORK_ID/publish" "$ALICE_JAR" | grep -q NO_PUBLISHED_CONTENT; then
  echo "   FAIL: 已有发布版本却拒绝发布作品"; exit 1
fi
req PATCH "/api/v1/works/$WORK_ID" "$ALICE_JAR" '{"visibility":"PUBLIC"}' > /dev/null
req POST "/api/v1/works/$WORK_ID/publish" "$ALICE_JAR" > /dev/null
echo "   作品已 PUBLIC+PUBLISHED"

echo "== 6. B 书库可见并阅读 =="
BOB_SEES=$(req GET "/api/v1/works?q=%E6%98%9F%E7%A9%BA" "$BOB_JAR")
echo "$BOB_SEES" | jq -e ".items[] | select(.id == \"$WORK_ID\")" > /dev/null || { echo "   FAIL: B 看不到公开作品"; exit 1; }
echo "   B 书库检索可见 ✓"
BOB_PAGES=$(req GET "/api/v1/chapter-revisions/$REV1_ID/pages" "$BOB_JAR")
FIRST_IMG=$(echo "$BOB_PAGES" | jq -r '.pages[] | select(.ordinal == 1) | .imageUrl')
CODE=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$FIRST_IMG")
echo "   B 读第 1 页签名图: HTTP $CODE"
[ "$CODE" = "200" ] || { echo "   FAIL: 图片不可读"; exit 1; }

echo "== 7. B 收藏 + 进度 + 冲突 =="
req PUT "/api/v1/me/library/$WORK_ID" "$BOB_JAR" > /dev/null
SHELF=$(req GET "/api/v1/me/library" "$BOB_JAR")
echo "$SHELF" | jq -e ".items[] | select(.workId == \"$WORK_ID\")" > /dev/null && echo "   收藏成功 ✓"
PROG=$(req PUT "/api/v1/me/reading-progress/$CH_ID" "$BOB_JAR" \
  "{\"chapterRevisionId\":\"$REV1_ID\",\"pageOrdinal\":3,\"expectedVersion\":0}")
echo "   首次写进度: $(echo "$PROG" | jq -c .)"
CONFLICT=$(req PUT "/api/v1/me/reading-progress/$CH_ID" "$BOB_JAR" \
  "{\"chapterRevisionId\":\"$REV1_ID\",\"pageOrdinal\":5,\"expectedVersion\":0}")
echo "$CONFLICT" | grep -q PROGRESS_CONFLICT && echo "   过期版本写入被拒（409 PROGRESS_CONFLICT）✓" || { echo "   FAIL: 冲突未拒绝: $CONFLICT"; exit 1; }
PROG2=$(req PUT "/api/v1/me/reading-progress/$CH_ID" "$BOB_JAR" \
  "{\"chapterRevisionId\":\"$REV1_ID\",\"pageOrdinal\":2,\"expectedVersion\":1}")
echo "   回翻到第 2 页: $(echo "$PROG2" | jq -c .)"

echo "== 8. 换版不混页 =="
CREATE2=$(req POST "/api/v1/uploads" "$ALICE_JAR" "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"m1-rev2-$TS\"}")
UPLOAD2=$(echo "$CREATE2" | jq -r .uploadId)
PUT2=$(echo "$CREATE2" | jq -r .presigned.url)
curl -s -m 30 -X PUT "$PUT2" -H "Content-Type: application/zip" --upload-file "$WORK/test.cbz" > /dev/null
req POST "/api/v1/uploads/$UPLOAD2/complete" "$ALICE_JAR" > /dev/null
poll_upload "$ALICE_JAR" "$UPLOAD2" 60
REV2_ID=$(req POST "/api/v1/chapters/$CH_ID/revisions" "$ALICE_JAR" "{\"uploadId\":\"$UPLOAD2\"}" | jq -r .revisionId)
poll_revision "$ALICE_JAR" "$REV2_ID" READY 120 > /dev/null
req POST "/api/v1/chapter-revisions/$REV2_ID/publish" "$ALICE_JAR" > /dev/null
OLD_PAGES=$(req GET "/api/v1/chapter-revisions/$REV1_ID/pages" "$BOB_JAR")
OLD_STATUS=$(echo "$OLD_PAGES" | jq -r .status)
OLD_COUNT=$(echo "$OLD_PAGES" | jq '.pages | length')
NEW_PAGES=$(req GET "/api/v1/chapter-revisions/$REV2_ID/pages" "$BOB_JAR")
NEW_COUNT=$(echo "$NEW_PAGES" | jq '.pages | length')
echo "   rev1 状态=$OLD_STATUS 页窗=${OLD_COUNT}；rev2 页窗=${NEW_COUNT}（各自独立）"
[ "$OLD_STATUS" = "PUBLISHED" ] && [ "$OLD_COUNT" -gt 0 ] && [ "$NEW_COUNT" -gt 0 ] || { echo "   FAIL: 换版后旧版本不可读"; exit 1; }

echo "== 9. 私人内容不可见 =="
DRAFT_ID=$(req POST /api/v1/works "$ALICE_JAR" '{"title":"私人草稿"}' | jq -r .work.id)
if req GET "/api/v1/works/$DRAFT_ID" "$BOB_JAR" | grep -q WORK_NOT_FOUND; then
  echo "   B 访问 A 的 PRIVATE 草稿 → 404 ✓"
else
  echo "   FAIL: 私人草稿泄漏"; exit 1
fi
BOB_UPLOAD=$(req POST /api/v1/uploads "$BOB_JAR" "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"m1-bob-$TS\"}")
BOB_UPID=$(echo "$BOB_UPLOAD" | jq -r .uploadId)
if req GET "/api/v1/uploads/$UPLOAD_ID" "$BOB_JAR" | grep -q UPLOAD_NOT_FOUND; then
  echo "   B 访问 A 的上传会话 → 404 ✓"
else
  echo "   FAIL: 上传会话越权"; exit 1
fi
rm -f "$WORK/test.cbz" 2>/dev/null || true
echo ""
echo "M1 SMOKE PASSED ✅  work=$WORK_ID rev1=$REV1_ID rev2=$REV2_ID"
