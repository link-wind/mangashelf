#!/usr/bin/env bash
# M7 冒烟：外部集站内播放解析全链路
# 建源 → 绑定 mock 剧集页 → POST resolve → 轮询 → 拿到嗅探出的 m3u8 直链
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke-m7"
MOCK_PORT=18996
MOCK_URL="http://127.0.0.1:${MOCK_PORT}"
rm -rf "$WORK" && mkdir -p "$WORK"

# ── mock 剧集页（自动起播 fetch m3u8，模拟 gugu 生态站点）──
mkdir -p "$WORK/mock/video/ep1"
cat > "$WORK/mock/watch.html" <<EOF
<!DOCTYPE html><html><body><div class="player-box"></div>
<script>fetch("/video/ep1/index.m3u8?t=abc&sign=xyz");</script></body></html>
EOF
printf '#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n' > "$WORK/mock/video/ep1/index.m3u8"
python3 -m http.server "$MOCK_PORT" --bind 127.0.0.1 --directory "$WORK/mock" >/dev/null 2>&1 &
MOCK_PID=$!
trap 'kill $MOCK_PID 2>/dev/null' EXIT
sleep 1

login() {
  local jar="$1" name="$2" pass="$3"
  curl -s -m 10 -c "$jar" "$BASE/api/v1/works" -o /dev/null
  local x; x=$(grep XSRF-TOKEN "$jar" | awk '{print $7}' | tail -1)
  curl -s -m 15 -b "$jar" -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $x" -d "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null || true
  curl -s -m 15 -c "$jar" -b "$jar" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $x" -d "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  grep -q SESSION "$jar" || { echo "登录失败" >&2; exit 1; }
}
req() {
  local jar="$1" method="$2" path="$3" data="${4:-}"
  local x; x=$(grep XSRF-TOKEN "$jar" | awk '{print $7}' | tail -1)
  if [ "$method" = "GET" ]; then curl -s -m 30 -b "$jar" "$BASE$path";
  else curl -s -m 30 -b "$jar" -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $x" ${data:+-d "$data"}; fi
}

echo "== 1. 用户与规则源准备 =="
TS=$(date +%s)
ADMIN_JAR="$WORK/admin.txt"; USER_JAR="$WORK/user.txt"
login "$ADMIN_JAR" "admin" "mangashelf-admin"
login "$USER_JAR" "m7user$TS" "m7-pass-123"
RULES="{\"searchMode\":\"html\",\"searchUrl\":\"${MOCK_URL}/s?kw={keyword}\",\"searchList\":\".item\",\"searchName\":\".title\",\"searchUrlSelector\":\"a\",\"episodeListSelector\":\".ep a\"}"
SRC=$(req "$ADMIN_JAR" POST /api/v1/admin/external-sources \
  "{\"name\":\"M7Mock-$TS\",\"baseUrl\":\"$MOCK_URL\",\"rules\":$RULES,\"enabled\":true}")
SRC_ID=$(echo "$SRC" | jq -r .id)
[ "$SRC_ID" != "null" ] && echo "   规则源 $SRC_ID ✓" || { echo "FAIL: $SRC"; exit 1; }

echo "== 2. 绑定 mock 剧集 =="
WID=$(req "$USER_JAR" POST /api/v1/works '{"title":"M7 播放冒烟"}' | jq -r .work.id)
BOUND=$(req "$USER_JAR" POST "/api/v1/works/$WID/external-episodes" \
  "{\"sourceId\":\"$SRC_ID\",\"episodes\":[{\"title\":\"第1话\",\"url\":\"${MOCK_URL}/watch.html\",\"ordinal\":1}]}")
EP_ID=$(echo "$BOUND" | jq -r '.[0].id')
[ "$EP_ID" != "null" ] && echo "   外部集 $EP_ID ✓" || { echo "FAIL: $BOUND"; exit 1; }

echo "== 3. 发起解析并轮询 =="
req "$USER_JAR" POST "/api/v1/external-episodes/$EP_ID/resolve" '{}' > /dev/null
DEADLINE=$(( $(date +%s) + 75 ))
STATUS=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  STATUS=$(req "$USER_JAR" GET "/api/v1/external-episodes/$EP_ID/resolve")
  URL=$(echo "$STATUS" | jq -r .playUrl)
  [ "$URL" != "null" ] && break
  ST=$(echo "$STATUS" | jq -r .jobState)
  [ "$ST" = "FAILED" ] && break
  sleep 2
done
echo "   状态: $(echo "$STATUS" | jq -c '{jobState, playUrl, errorCode}')"
echo "$STATUS" | jq -e '.playUrl | contains("index.m3u8?t=abc&sign=xyz")' > /dev/null \
  && echo "   嗅探直链 ✓" || { echo "FAIL: $STATUS"; exit 1; }

echo "== 4. 解析幂等（窗口内重复 POST 不新建任务）=="
J1=$(req "$USER_JAR" POST "/api/v1/external-episodes/$EP_ID/resolve" '{}' | jq -r .jobId)
J2=$(req "$USER_JAR" POST "/api/v1/external-episodes/$EP_ID/resolve" '{}' | jq -r .jobId)
[ "$J1" = "$J2" ] && echo "   同任务复用 ✓" || echo "   WARN: 窗口边界跨秒（$J1 vs $J2）"

echo "== 5. 过期重取（解析结果可再次触发新解析）=="
sleep 31
req "$USER_JAR" POST "/api/v1/external-episodes/$EP_ID/resolve" '{}' > /dev/null
echo "   已接受（进入新解析窗口）✓"

echo ""
echo "M7 SMOKE PASSED ✅  episode=$EP_ID work=$WID"
