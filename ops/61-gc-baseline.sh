#!/usr/bin/env bash
# 운영 액션 61 — 평시 GC 기준값: Prometheus 에서 서비스마다 며칠 동안의 GC 를 한 표로 (docs/91 §4-3 · docs/92 F-6)
# [JA] 運用アクション 61 — 平常時の GC 基準値: Prometheus からサービスごとに数日分の GC を一つの表に
#
# ops/60(JVM 이 고른 것 — 파드 안에 JVM 을 하나 더 띄운다)의 짝이다. 이 스크립트는 **Prometheus 를 읽기만** 한다 — 파드 · 클러스터에
# 아무것도 하지 않으므로 운영 노드에서 그대로 돌린다(관측 포트는 공개하지 않는다 — 노드 안에서 PROM=http://localhost:9090).
# 지표는 Spring Boot(Micrometer)가 원래 내는 것이라 이미지와 상관없이 지금 운영에 쌓여 있다.
#
# 왜 창(WINDOW)인가: jvm_gc_overhead 는 "지난 5분 중 GC 정지 비율" 이라 한 번 찍은 값은 평시가 아니다. 며칠의 평균 · 최대를 본다.
# 창은 운영 Prometheus 보존(15일) 안이어야 한다 — 기본 7d, 최대 14d.
#
# 표의 열: 서비스 · 파드(지금) · GC(gc 라벨 → Serial · G1 · Parallel · ZGC — 파드마다 다르면 '섞임') · 최대 힙 · GC 부담 평균 / 최대 ·
#          GC 정지 최대 · 파드당 GC 횟수/분 · 파드당 할당 MB/s · 요청당 할당 KB · GC 뒤 힙 최대(오래 사는 영역) · 눈여겨볼 것
# 결과: OUT/gc-baseline.md(표 + 읽는 법) · OUT/raw/<열>.json(Prometheus 응답 그대로)
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   PROM=http://localhost:9090 ./ops/61-gc-baseline.sh                 # 운영 노드에서 — 지난 7일
#   PROM=http://localhost:9090 WINDOW=24h ./ops/61-gc-baseline.sh      # 지난 하루
#   PROM=$PROM MATCH='environment="demo"' ./ops/61-gc-baseline.sh      # 실습(데모) 지표
#
# 환경 변수(기본값): PROM(필수)  WINDOW=7d(1h ~ 14d)  MATCH='job="spring-actuator"'(라벨="값" 하나 — ops/53 의 PROM_MATCH 와 같다)
#                    OUT=~/.ecommerce-msa/gc-baseline/<UTC 시각>  CURL=curl(테스트가 바꿔 끼운다)
set -euo pipefail

die() { echo "✖ $*" >&2; exit 1; }

PROM="${PROM:-}"
WINDOW="${WINDOW:-7d}"
MATCH="${MATCH:-job=\"spring-actuator\"}"
OUT="${OUT:-$HOME/.ecommerce-msa/gc-baseline/$(date -u +%Y%m%dT%H%M%SZ)}"
CURL="${CURL:-curl}"

[ -n "$PROM" ] || die "PROM 이 필요하다 — 운영 노드에서는 PROM=http://localhost:9090 (관측 포트는 밖에 열지 않는다)"
[[ "$PROM" =~ ^https?://[A-Za-z0-9.:_-]+/?$ ]] || die "PROM 은 http(s)://호스트[:포트] 만: $PROM"
[[ "$MATCH" =~ ^[a-z_]+=\"[a-z0-9_.-]+\"$ ]] || die "MATCH 는 라벨=\"값\" 하나(예 job=\"spring-actuator\")"
[[ "$WINDOW" =~ ^([1-9][0-9]*)([hd])$ ]] || die "WINDOW 는 숫자 + h|d (예 24h · 7d)"
n="${BASH_REMATCH[1]}"; unit="${BASH_REMATCH[2]}"
if [ "$unit" = "d" ]; then hours=$(( n * 24 )); else hours="$n"; fi
[ "$hours" -le 336 ] || die "WINDOW 는 14d 까지 — 운영 Prometheus 보존이 15일이라 그보다 길면 앞부분이 비어 평균이 틀린다(docs/91 §2-4)"
PROM="${PROM%/}"
sel="$MATCH"

"$CURL" -fsS --max-time 10 "$PROM/-/ready" >/dev/null 2>&1 || die "Prometheus($PROM)가 답하지 않는다 — 운영 노드에서 localhost:9090 인지 확인한다"
mkdir -p "$OUT/raw" || die "결과 폴더를 만들지 못했다: $OUT"

# 열 이름 | PromQL — 모두 그 순간(now)의 즉시 질의, 창은 [WINDOW]
# [JA] 列名 | PromQL — すべてその瞬間(now)の即時クエリ、窓は [WINDOW]
QUERIES=(
  "pods|count by (service) (up{$sel} == 1)"
  "gctype|count by (service, gc) (jvm_gc_pause_seconds_count{$sel})"
  "heap|max by (service) (sum without (id) (jvm_memory_max_bytes{$sel,area=\"heap\"} > 0))"
  "gc_avg|max by (service) (avg_over_time(jvm_gc_overhead{$sel}[$WINDOW]))"
  "gc_max|max by (service) (max_over_time(jvm_gc_overhead{$sel}[$WINDOW]))"
  "pause_max|max by (service) (max_over_time(jvm_gc_pause_seconds_max{$sel}[$WINDOW]))"
  "gc_per_min|sum by (service) (rate(jvm_gc_pause_seconds_count{$sel}[$WINDOW])) * 60"
  "alloc|sum by (service) (rate(jvm_gc_memory_allocated_bytes_total{$sel}[$WINDOW]))"
  "rps|sum by (service) (rate(http_server_requests_seconds_count{$sel}[$WINDOW]))"
  "oldgen|max by (service) (max_over_time(jvm_memory_usage_after_gc{$sel,area=\"heap\",pool=\"long-lived\"}[$WINDOW]))"
)
for q in "${QUERIES[@]}"; do
    key="${q%%|*}"
    "$CURL" -fsS --max-time 30 -G "$PROM/api/v1/query" --data-urlencode "query=${q#*|}" > "$OUT/raw/$key.json" 2>/dev/null \
        || echo '{"status":"error"}' > "$OUT/raw/$key.json"
done

rc=0
python3 -I - "$OUT" "$WINDOW" "$MATCH" "$PROM" <<'PY' || rc=$?
import json, sys, datetime
out, window, match, prom = sys.argv[1:5]
KIND = (("G1 ", "G1"), ("Copy", "Serial"), ("MarkSweepCompact", "Serial"), ("PS ", "Parallel"), ("ZGC", "ZGC"), ("Shenandoah", "Shenandoah"))
srv, failed = {}, []
for key in ("pods", "gctype", "heap", "gc_avg", "gc_max", "pause_max", "gc_per_min", "alloc", "rps", "oldgen"):
    try:
        body = json.load(open(f"{out}/raw/{key}.json", encoding="utf-8"))
        res = body["data"]["result"] if body.get("status") == "success" else None
    except Exception:
        res = None
    if res is None:
        failed.append(key)
        continue
    for x in res:
        name = x["metric"].get("service", "?")
        if key == "gctype":
            gc = x["metric"].get("gc", "")
            kind = next((k for p, k in KIND if gc.startswith(p)), gc or "?")
            kinds = srv.setdefault(name, {}).setdefault("kinds", [])
            if kind not in kinds:
                kinds.append(kind)
            continue
        v = float(x["value"][1])
        if v == v:   # NaN 은 버린다 ／ [JA] NaN は捨てる
            srv.setdefault(name, {})[key] = v

f = lambda v, fmt: (fmt % v) if v is not None else "-"
rows = []
for name in sorted(srv):
    v = srv[name]
    pods = v.get("pods")
    kinds = v.get("kinds") or []
    gc = kinds[0] if len(kinds) == 1 else ("섞임: " + " · ".join(kinds) if kinds else "-")
    per_pod = lambda k: v[k] / pods if v.get(k) is not None and pods else None
    alloc_pod = per_pod("alloc")
    per_req = v["alloc"] / v["rps"] / 1024 if v.get("alloc") is not None and v.get("rps") else None
    note = []
    if len(kinds) > 1:
        note.append("파드마다 GC 가 다르다 — 한도 · JAVA_OPTS 를 본다(ops/60)")
    if (v.get("gc_max") or 0) >= 0.10:
        note.append(f"GC 부담 최대 {v['gc_max']:.0%} — 경보 문턱(10%) 이상이던 때가 있다")
    if (v.get("oldgen") or 0) >= 0.9:
        note.append(f"GC 뒤 힙 {v['oldgen']:.0%} — 힙이 작거나 오래 사는 객체가 많다")
    if (v.get("pause_max") or 0) >= 0.5:
        note.append(f"정지 최대 {v['pause_max'] * 1000:.0f}ms — 그대로 꼬리 지연")
    rows.append(f"| {name} | {f(pods, '%.0f')} | {gc} | {f(v['heap'] / 1048576 if v.get('heap') else None, '%.0fM')} | "
                f"{f(v['gc_avg'] * 100 if v.get('gc_avg') is not None else None, '%.2f%%')} | "
                f"{f(v['gc_max'] * 100 if v.get('gc_max') is not None else None, '%.1f%%')} | "
                f"{f(v['pause_max'] * 1000 if v.get('pause_max') is not None else None, '%.0f')} | "
                f"{f(per_pod('gc_per_min'), '%.2f')} | {f(alloc_pod / 1048576 if alloc_pod is not None else None, '%.2f')} | "
                f"{f(per_req, '%.0f')} | {f(v['oldgen'] * 100 if v.get('oldgen') is not None else None, '%.0f%%')} | "
                f"{' · '.join(note) or '-'} |")

now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
lines = [f"# 평시 GC 기준값 — 지난 {window} · {match} · {now}", "",
         f"- Prometheus `{prom}` · 질의 실패: {', '.join(failed) if failed else '없음'}", "",
         "| 서비스 | 파드(지금) | GC | 최대 힙 | GC 부담 평균 | GC 부담 최대 | GC 정지 최대 ms | 파드당 GC 횟수/분 | 파드당 할당 MB/s | 요청당 할당 KB | GC 뒤 힙 최대 | 눈여겨볼 것 |",
         "|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
lines += rows or ["| (지표 없음 — MATCH 와 서비스 지표를 확인한다) | | | | | | | | | | | |"]
lines += ["",
          "읽는 법(docs/91 §4-3): GC 부담은 JVM 의 지난 5분 중 GC 정지 비율(jvm_gc_overhead)을 창 전체로 평균 · 최대한 값이다 — 평균이 평시,",
          "최대는 그 창에서 가장 나빴던 5분이다. 요청당 할당 = 할당 속도 ÷ http 요청/초(Kafka · 스케줄 작업의 할당도 섞인다 — 요청이 적은 서비스는 크게 나온다).",
          "GC 뒤 힙은 오래 사는 영역이 GC 를 마친 뒤 찬 비율 — 늘기만 하면 누수다. GC 종류는 ops/60 의 사실 표와 같아야 한다(docs/92 F-4 · F-6).",
          "이 표를 docs/91 §4-3 의 '운영 기준값' 과 docs/92 §5-1 에 옮긴다."]
open(f"{out}/gc-baseline.md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(2 if len(failed) == 10 else 0)
PY
echo
echo ">>> $OUT/gc-baseline.md · raw/<열>.json"
exit "$rc"
