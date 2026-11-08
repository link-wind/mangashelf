#!/usr/bin/env bash
# M6 冒烟：管理员导入规则 → 搜索绑定外部集 → 解绑 → 非管理员禁止管理规则
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
WORK="${TMPDIR:-/tmp}/mangashelf-smoke-m6"
rm -rf "$WORK" && mkdir -p "$WORK"

login() {
  local jar="$1" name="$2" pass="$3"
  curl -s -m 10 -c "$jar" "$BASE/api/v1/works" -o /dev/null
  local x; x=$(grep XSRF-TOKEN "$jar" | awk '{print $7}' | tail -1)
  curl -s -m 15 -b "$jar" -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $x" -d "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  curl -s -m 15 -c "$jar" -b "$jar" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $x" -d "{\"login\":\"$name\",\"password\":\"$pass\"}" > /dev/null
  grep -q SESSION "$jar" || { echo "登录失败" >&2; exit 1; }
}
req() {
  local jar="$1" method="$2" path="$3" data="${4:-}"
  local x; x=$(grep XSRF-TOKEN "$jar" | awk '{print $7}' | tail -1)
  if [ "$method" = "GET" ]; then curl -s -m 20 -b "$jar" "$BASE$path";
  else curl -s -m 20 -b "$jar" -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $x" ${data:+-d "$data"}; fi
}

echo "== 1. 用户准备 =="
TS=$(date +%s)
ADMIN_JAR="$WORK/admin.txt"
USER_JAR="$WORK/user.txt"
login "$ADMIN_JAR" "admin" "mangashelf-admin"
login "$USER_JAR" "extuser$TS" "extuser-pass-123"
echo "   admin + user 就绪"

echo "== 2. 管理员导入规则源 =="
RULES='{"searchMode":"html","searchUrl":"https://example.com/search?q={keyword}","searchList":".item","searchName":".title","searchUrlSelector":"a","episodeListSelector":".ep-list a","episodeNameSelector":"a","episodeUrlSelector":"a"}'
SRC=$(req "$ADMIN_JAR" POST /api/v1/admin/external-sources \
  "{\"name\":\"TestSource-$TS\",\"baseUrl\":\"https://example.com\",\"rules\":$RULES,\"enabled\":true}")
SRC_ID=$(echo "$SRC" | jq -r .id)
[ "$SRC_ID" != "null" ] && echo "   规则源 $SRC_ID 导入成功 ✓" || { echo "   FAIL: $SRC"; exit 1; }

echo "== 3. 非管理员禁止导入 =="
DENIED=$(req "$USER_JAR" POST /api/v1/admin/external-sources \
  "{\"name\":\"bad\",\"baseUrl\":\"https://x.com\",\"rules\":$RULES,\"enabled\":true}")
echo "$DENIED" | grep -q FORBIDDEN && echo "   非管理员 → 403 ✓" || { echo "   FAIL: $DENIED"; exit 1; }

echo "== 4. 用户创建作品并绑定外部集 =="
WID=$(req "$USER_JAR" POST /api/v1/works '{"title":"外部动画测试"}' | jq -r .work.id)
req "$USER_JAR" POST "/api/v1/works/$WID/chapters" '{"title":"c1"}' > /dev/null
BOUND=$(req "$USER_JAR" POST "/api/v1/works/$WID/external-episodes" \
  "{\"sourceId\":\"$SRC_ID\",\"episodes\":[{\"title\":\"第1话\",\"url\":\"https://example.com/watch/1\",\"ordinal\":1},{\"title\":\"第2话\",\"url\":\"https://example.com/watch/2\",\"ordinal\":2}]}")
BOUND_COUNT=$(echo "$BOUND" | jq 'length')
[ "$BOUND_COUNT" -ge 2 ] && echo "   绑定 2 集 ✓" || { echo "   FAIL: $BOUND"; exit 1; }

echo "== 5. 作品可见者查看外部集 =="
LIST=$(req "$USER_JAR" GET "/api/v1/works/$WID/external-episodes")
LIST_COUNT=$(echo "$LIST" | jq 'length')
[ "$LIST_COUNT" -ge 2 ] && echo "   列表 $LIST_COUNT 集 ✓" || { echo "   FAIL: $LIST"; exit 1; }

echo "== 6. 解绑 =="
EP_ID=$(echo "$LIST" | jq -r '.[0].id')
UNBOUND=$(req "$USER_JAR" DELETE "/api/v1/external-episodes/$EP_ID")
echo "$UNBOUND" | jq -e '.removed == true' > /dev/null && echo "   解绑 ✓" || { echo "   FAIL: $UNBOUND"; exit 1; }

echo "== 7. 启停规则源 =="
DISABLED=$(req "$ADMIN_JAR" POST "/api/v1/admin/external-sources/$SRC_ID/toggle?enabled=false")
echo "$DISABLED" | jq -e '.enabled == false' > /dev/null && echo "   停用 ✓" || { echo "   FAIL: $DISABLED"; exit 1; }
ENABLED=$(req "$ADMIN_JAR" POST "/api/v1/admin/external-sources/$SRC_ID/toggle?enabled=true")
echo "$ENABLED" | jq -e '.enabled == true' > /dev/null && echo "   启用 ✓" || { echo "   FAIL"; exit 1; }

echo ""
echo "M6 SMOKE PASSED ✅  source=$SRC_ID work=$WID"
