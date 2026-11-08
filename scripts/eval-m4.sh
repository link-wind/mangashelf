#!/usr/bin/env bash
# M4 评估（DESIGN 21.2 小规模版）：固定素材 + 15 条标注查询 → Recall@K（字面/语义）。
# 规模边界：完整目标为 30–50 条人工标注集，本脚本为可复现的固定小集。
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
WORK="${TMPDIR:-/tmp}/mangashelf-eval-m4"
rm -rf "$WORK" && mkdir -p "$WORK"
JAR="$WORK/site.txt"
TS=$(date +%s)
PYTHON="${MANGASHELF_PYTHON:-$(cd "$(dirname "$0")/.." && pwd)/processing/.venv/bin/python}"

curl -s -c "$JAR" "$BASE/api/v1/works" -o /dev/null
req() { local m="$1" p="$2" d="${3:-}"; local x; x=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}' | tail -1);
  if [ "$m" = "GET" ]; then curl -s -m 20 -b "$JAR" "$BASE$p";
  else curl -s -m 30 -b "$JAR" -X "$m" "$BASE$p" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $x" ${d:+-d "$d"}; fi; }
LOGIN="evaluator$TS"
req POST /api/v1/auth/register "{\"login\":\"$LOGIN\",\"password\":\"eval-pass-1234\"}" > /dev/null
req POST /api/v1/auth/login "{\"login\":\"$LOGIN\",\"password\":\"eval-pass-1234\"}" > /dev/null

# ── 固定素材：6 页，每页 2–3 句可标注内容 ──
"$PYTHON" - "$WORK" <<'PY'
from PIL import Image, ImageDraw, ImageFont
import zipfile, io, sys, json
font = None
for cand in ["/System/Library/Fonts/STHeiti Light.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"]:
    try: font = ImageFont.truetype(cand, 44); break
    except Exception: pass
pages = [
    ["第一话 雨夜", "车站的钟敲了十下。", "她把伞留给了陌生人。", "自己走进雨里。"],
    ["第二话 木桥", "木桥在洪水后断了。", "修桥的工匠来自南方。", "他说：桥会记得每一个过桥的人。"],
    ["第三话 旧书店", "旧书店的猫叫墨水。", "老板收一本没有作者的书。", "书页里夹着半张车票。"],
    ["第四话 追踪", "车票的目的地是海边。", "两个人连夜出发。", "雨刷来回摆动，像在数心跳。"],
    ["第五话 灯塔", "灯塔的守夜人失踪了三天。", "桌上留着一副眼镜。", "海鸥停在窗台不肯离开。"],
    ["第六话 归还", "墨水被送回了旧书店。", "老板把书放进最上层。", "半张车票终于凑成了整张。"],
]
with zipfile.ZipFile(f"{sys.argv[1]}/eval.cbz", 'w') as z:
    for i, lines in enumerate(pages, start=1):
        img = Image.new('RGB', (900, 1300), (250, 249, 244))
        d = ImageDraw.Draw(img)
        y = 90
        for line in lines:
            d.text((60, y), line, fill=(15, 15, 35), font=font)
            y += 150
        buf = io.BytesIO(); img.save(buf, 'PNG'); z.writestr(f"e{i}.png", buf.getvalue())
json.dump({"expected": [
    {"q": "车站的钟敲了几下", "page": 1}, {"q": "她把伞留给了谁", "page": 1},
    {"q": "木桥断的原因", "page": 2}, {"q": "修桥的工匠从哪里来", "page": 2},
    {"q": "旧书店的猫叫什么名字", "page": 3}, {"q": "书页里夹着什么", "page": 3},
    {"q": "车票的目的地", "page": 4}, {"q": "谁连夜出发", "page": 4},
    {"q": "灯塔守夜人失踪了多久", "page": 5}, {"q": "桌上留下了什么", "page": 5},
    {"q": "猫最后被送到了哪里", "page": 6}, {"q": "半张车票的下落", "page": 6},
    {"q": "他为自己迟到的事道歉", "page": 2}, {"q": "关于守夜人的下落线索", "page": 5},
    {"q": "桥对过桥人说了什么", "page": 2},
]}, open(f"{sys.argv[1]}/labels.json", 'w'), ensure_ascii=False)
print("素材与标注已生成")
PY

WID=$(req POST /api/v1/works "{\"title\":\"海滨小镇谜案\",\"description\":\"M4 评估集\"}" | jq -r .work.id)
CHID=$(req POST "/api/v1/works/$WID/chapters" '{"title":"全一话"}' | jq -r .id)
SIZE=$(stat -f%z "$WORK/eval.cbz")
C=$(req POST /api/v1/uploads "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"eval-$TS\"}")
UP=$(echo "$C" | jq -r .uploadId)
curl -s -m 30 -X PUT "$(echo "$C" | jq -r .presigned.url)" -H "Content-Type: application/zip" --upload-file "$WORK/eval.cbz" > /dev/null
req POST "/api/v1/uploads/$UP/complete" > /dev/null
OK=0; for i in $(seq 1 60); do
  S=$(req GET "/api/v1/uploads/$UP" | jq -r '[.jobs[] | select(.kind=="content.verify" and .status=="SUCCEEDED")] | length')
  [ "$S" -ge 1 ] && OK=1 && break; sleep 1
done; [ "$OK" = "1" ] || { echo "FAIL: 核验超时"; exit 1; }
CREV=$(req POST "/api/v1/chapters/$CHID/revisions" "{\"uploadId\":\"$UP\"}" | jq -r .revisionId)
OK=0; for i in $(seq 1 120); do
  ST=$(req GET "/api/v1/chapter-revisions/$CREV" | jq -r .status)
  [ "$ST" = "READY" ] && OK=1 && break; sleep 1
done; [ "$OK" = "1" ] || { echo "FAIL: 解析超时"; exit 1; }
site_req_placeholder=""
X=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}' | tail -1)
curl -s -m 15 -b "$JAR" -X POST "$BASE/api/v1/chapter-revisions/$CREV/indexes" -H "X-XSRF-TOKEN: $X" > "$WORK/idx.json"
GEN=$(jq -r .generationId "$WORK/idx.json")
OK=0; for i in $(seq 1 180); do
  ST=$(req GET "/api/v1/index-generations/$GEN" | jq -r .status)
  [ "$ST" = "ACTIVE" ] && OK=1 && break; [ "$ST" = "FAILED" ] && { cat "$WORK/idx.json"; jq . "$WORK/idx.json"; exit 1; }; sleep 1
done; [ "$OK" = "1" ] || { echo "FAIL: 索引超时"; exit 1; }
echo "索引 ACTIVE：$GEN"

# ── 15 条标注查询跑 Recall@K（字面 + 语义同端点，语义由服务端合并）──
LIT1=0; LIT3=0; LIT5=0; TOTAL=0
while IFS= read -r LINE; do
  Q=$(echo "$LINE" | jq -r .q); PAGE=$(echo "$LINE" | jq -r .page)
  TOTAL=$((TOTAL + 1))
  RES=$(req POST /api/v1/search "{\"q\":\"$Q\",\"limit\":5}")
  RANK=$(echo "$RES" | jq -r --argjson p "$PAGE" \
    '[.hits[] | select(.kind == "page").pageOrdinal] | index($p) + 1' | sed 's/null/0/')
  [ "$RANK" -ge 1 ] && [ "$RANK" -le 1 ] && LIT1=$((LIT1+1))
  [ "$RANK" -ge 1 ] && [ "$RANK" -le 3 ] && LIT3=$((LIT3+1))
  [ "$RANK" -ge 1 ] && [ "$RANK" -le 5 ] && LIT5=$((LIT5+1))
  echo "  [$RANK] ${Q}（期望第 $PAGE 页）"
done < <(jq -c '.expected[]' "$WORK/labels.json")
echo ""
echo "== M4 评估结果（15 条标注查询，目标页 Recall@K；合并通道 = 字面+语义 RRF）=="
echo "查询总数：${TOTAL}（规模边界：完整目标 30–50 条，见 docs/validation/m4.md）"
python3 -c "
lit1, lit3, lit5, total = $LIT1, $LIT3, $LIT5, $TOTAL
print(f'Recall@1 = {lit1}/{total} = {lit1/total:.0%}')
print(f'Recall@3 = {lit3}/{total} = {lit3/total:.0%}')
print(f'Recall@5 = {lit5}/{total} = {lit5/total:.0%}')
"
