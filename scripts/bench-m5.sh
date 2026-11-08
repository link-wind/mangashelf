#!/usr/bin/env bash
# M5 容量测量（DESIGN 21.3）：固定数据集上的端点延迟与索引吞吐。
# 口径：curl 计时（API 耗时，不含对象存储传输）；数字仅代表演示规格。
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
WORK="${TMPDIR:-/tmp}/mangashelf-bench-m5"
rm -rf "$WORK" && mkdir -p "$WORK"
JAR="$WORK/site.txt"
PYTHON="${MANGASHELF_PYTHON:-$(cd "$(dirname "$0")/.." && pwd)/processing/.venv/bin/python}"
TS=$(date +%s)

curl -s -c "$JAR" "$BASE/api/v1/works" -o /dev/null
req() { local m="$1" p="$2" d="${3:-}"; local x; x=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}' | tail -1);
  if [ "$m" = "GET" ]; then curl -s -m 30 -b "$JAR" "$BASE$p";
  else curl -s -m 60 -b "$JAR" -X "$m" "$BASE$p" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $x" ${d:+-d "$d"}; fi; }
LOGIN="bencher$TS"
req POST /api/v1/auth/register "{\"login\":\"$LOGIN\",\"password\":\"bench-pass-123\"}" > /dev/null
req POST /api/v1/auth/login "{\"login\":\"$LOGIN\",\"password\":\"bench-pass-123\"}" > /dev/null

echo "== 1. 固定数据集：2 作品 × 12 页（每页 800×1200 含文字）=="
"$PYTHON" - "$WORK" <<'PY'
from PIL import Image, ImageDraw, ImageFont
import io, zipfile, sys, json
font = None
for cand in ["/System/Library/Fonts/STHeiti Light.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"]:
    try: font = ImageFont.truetype(cand, 40); break
    except Exception: pass
works = {"森林航行记": ["北港的雾散得很慢。", "他把地图压在箱底。", "码头只停一条船。", "路标指向内海。"],
         "沙丘邮差": ["邮差翻过第三座沙丘。", "水壶还剩半壶。", "信封上是陌生的名字。", "风把脚印抹平了。"]}
manifest = {}
for wi, (title, lines) in enumerate(works.items()):
    with zipfile.ZipFile(f"{sys.argv[1]}/w{wi}.cbz", 'w') as z:
        for pi in range(12):
            img = Image.new('RGB', (800, 1200), (250, 249, 245))
            d = ImageDraw.Draw(img)
            d.text((50, 60), f"{title} 第{pi+1}页", fill=(20, 20, 40), font=font)
            d.text((50, 200), lines[pi % len(lines)], fill=(20, 20, 40), font=font)
            d.text((50, 360), f"细节 {pi+1}-{wi}：旅途仍在继续，故事没有终点。", fill=(20, 20, 40), font=font)
            buf = io.BytesIO(); img.save(buf, 'PNG'); z.writestr(f"p{pi+1:02d}.png", buf.getvalue())
    manifest[title] = f"{sys.argv[1]}/w{wi}.cbz"
json.dump(manifest, open(f"{sys.argv[1]}/works.json", 'w'), ensure_ascii=False)
print("数据集生成完成")
PY

CREVS=""
for TITLE in "森林航行记" "沙丘邮差"; do
  WID=$(req POST /api/v1/works "{\"title\":\"$TITLE\"}" | jq -r .work.id)
  CHID=$(req POST "/api/v1/works/$WID/chapters" '{"title":"全一话"}' | jq -r .id)
  CBZ="$WORK/$(echo "$TITLE" | sed 's/森林航行记/w0/;s/沙丘邮差/w1/').cbz"
  SIZE=$(stat -f%z "$CBZ")
  C=$(req POST /api/v1/uploads "{\"purpose\":\"MANGA_PAGE\",\"expectedSizeBytes\":$SIZE,\"contentType\":\"application/zip\",\"idempotencyKey\":\"bench-$TITLE-$TS\"}")
  UP=$(echo "$C" | jq -r .uploadId)
  curl -s -m 60 -X PUT "$(echo "$C" | jq -r .presigned.url)" -H "Content-Type: application/zip" --upload-file "$CBZ" > /dev/null
  req POST "/api/v1/uploads/$UP/complete" > /dev/null
  OK=0; for i in $(seq 1 90); do
    S=$(req GET "/api/v1/uploads/$UP" | jq -r '[.jobs[] | select(.status=="SUCCEEDED")] | length')
    [ "$S" -ge 1 ] && OK=1 && break; sleep 1
  done; [ "$OK" = "1" ] || { echo "FAIL: 核验超时"; exit 1; }
  CREV=$(req POST "/api/v1/chapters/$CHID/revisions" "{\"uploadId\":\"$UP\"}" | jq -r .revisionId)
  OK=0; for i in $(seq 1 180); do
    ST=$(req GET "/api/v1/chapter-revisions/$CREV" | jq -r .status)
    [ "$ST" = "READY" ] && OK=1 && break; [ "$ST" = "FAILED" ] && exit 1; sleep 1
  done; [ "$OK" = "1" ] || { echo "FAIL: 解析超时"; exit 1; }
  CREVS="$CREVS $CREV"
  echo "   $TITLE 解析完成：$CREV"
done

echo "== 2. 构建索引（12 页 OCR+嵌入，测吞吐）=="
T0=$(python3 -c 'import time; print(int(time.time()*1000))')
FIRST=$(echo $CREVS | awk '{print $1}')
IDX=$(req POST "/api/v1/chapter-revisions/$FIRST/indexes")
GEN=$(echo "$IDX" | jq -r .generationId)
OK=0; for i in $(seq 1 600); do
  ST=$(req GET "/api/v1/index-generations/$GEN" | jq -r .status)
  [ "$ST" = "ACTIVE" ] && OK=1 && break; [ "$ST" = "FAILED" ] && exit 1; sleep 1
done
T1=$(python3 -c 'import time; print(int(time.time()*1000))')
[ "$OK" = "1" ] || { echo "FAIL: 索引超时"; exit 1; }
ELAPSED=$((T1 - T0))
python3 -c "ms=$ELAPSED; pages=12; print(f'   12 页索引端到端：{ms}ms（{pages/(ms/1000):.1f} 页/秒，含 OCR+嵌入+上传）')"

echo "== 3. 端点延迟（API 耗时口径，curl time_total）=="
CREV1=$(echo $CREVS | awk '{print $1}')
bench() { # bench <名称> <方法> <路径> <数据> <次数>
  local name="$1" method="$2" path="$3" data="${4:-}" n="${5:-30}"
  local times=""
  for i in $(seq 1 "$n"); do
    if [ "$method" = "GET" ]; then
      T=$(curl -s -o /dev/null -w '%{time_total}' -m 30 -b "$JAR" "$BASE$path")
    else
      local x; x=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}' | tail -1)
      T=$(curl -s -o /dev/null -w '%{time_total}' -m 30 -b "$JAR" -X "$method" "$BASE$path" \
        -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $x" ${data:+-d "$data"})
    fi
    times="$times $T"
  done
  echo "$times" | tr ' ' '\n' | sort -n | awk -v name="$name" '
    {a[NR]=$1} END {
      p50 = a[int(NR*0.5 + 0.5)]; p95 = a[int(NR*0.95 + 0.5)];
      printf "   %-28s N=%d  p50=%.0fms  p95=%.0fms\n", name, NR, p50*1000, p95*1000;
    }'
}
bench "目录列表（20 条）" GET "/api/v1/works?limit=20" "" 50
bench "页面窗口（5 页+签名）" GET "/api/v1/chapter-revisions/$CREV1/pages?limit=5" "" 50
bench "搜索（字面+语义 RRF）" POST "/api/v1/search" '{"q":"邮差与沙丘","limit":10}' 30
bench "健康检查（基线）" GET "/api/v1/../actuator/health" "" 50

echo ""
echo "测量完成。口径：单机开发规格，不外推生产容量（DESIGN 21.3）。"
