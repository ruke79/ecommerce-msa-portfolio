#!/usr/bin/env bash
# 운영 액션 ⑭ — 로컬 부하 하네스
#
# 무엇을 측정할 수 있고 무엇은 못 하는가(docs/19 §1):
#   ✅ 응답 코드 분포 — 부하가 올라갈 때 5xx/429 가 언제 나오는가
#   ✅ 오류율의 **변곡점** — 동시성 N 에서 깨지기 시작하는가 (커넥션 풀 고갈 등 구조적 한계)
#   ✅ 회귀 — 같은 머신에서 코드 전후를 비교하면 상대 변화는 유효하다
#   ❌ 절대 지연/처리량 — 하드웨어·네트워크·데이터량이 운영과 다르다. 운영 수치로 쓰지 말 것.
#
# 이 스크립트가 굳이 curl 기반인 이유: k6/JMeter 설치를 전제하지 않기 위해서다.
# 정밀 측정이 필요하면 k6 로 옮기되, "무엇을 볼 것인가"는 여기와 같다.
#
# 사용:
#   GATEWAY=https://localhost:8000 ./ops/14-load-test.sh
#   CONCURRENCY="1 5 10 20 50" DURATION=15 ./ops/14-load-test.sh   # 동시성 스윕
#   PATHS="/catalog-service/api/category/categories" ./ops/14-load-test.sh
#   INSECURE=0                                                     # 실인증서 환경
set -uo pipefail

GATEWAY="${GATEWAY:-https://localhost:8000}"
CONCURRENCY="${CONCURRENCY:-1 5 10 20 40}"
DURATION="${DURATION:-10}"          # 단계당 초
PATHS="${PATHS:-/catalog-service/api/category/categories}"
OUT="${OUT:-./load-results}"
CURL=(curl -sS --max-time 20 -o /dev/null)
[ "${INSECURE:-1}" = "1" ] && CURL+=(-k)

command -v curl >/dev/null || { echo "ERROR: curl 필요" >&2; exit 1; }
mkdir -p "$OUT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$OUT/load-$STAMP.tsv"

printf '동시성\t요청\t2xx\t4xx\t429\t5xx\t오류\tp50(ms)\tp95(ms)\tp99(ms)\tRPS\n' > "$REPORT"

echo ">>> 대상: $GATEWAY$PATHS"
echo ">>> 단계당 ${DURATION}초, 동시성: $CONCURRENCY"
echo ">>> ⚠️ 절대 수치는 이 머신에서만 유효하다. 비교용으로만 쓸 것."
echo

worker() {
    # 한 워커가 DURATION 초 동안 반복 요청하며 'code time_total' 을 기록한다
    local out="$1" endpoint="$2" deadline
    deadline=$(( $(date +%s) + DURATION ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        "${CURL[@]}" -w '%{http_code} %{time_total}\n' "$GATEWAY$endpoint" 2>/dev/null >> "$out" \
            || echo "000 0" >> "$out"
    done
}

for endpoint in $PATHS; do
    for c in $CONCURRENCY; do
        raw="$(mktemp)"
        pids=""
        for _ in $(seq 1 "$c"); do
            worker "$raw" "$endpoint" & pids="$pids $!"
        done
        for p in $pids; do wait "$p"; done

        python3 - "$raw" "$c" "$DURATION" "$REPORT" <<'PY'
import sys
raw, conc, dur, report = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
codes, times = [], []
for line in open(raw):
    parts = line.split()
    if len(parts) != 2:
        continue
    codes.append(parts[0])
    try:
        times.append(float(parts[1]) * 1000)
    except ValueError:
        pass
n = len(codes)
if n == 0:
    print(f"  동시성 {conc:>3}: 응답 없음 — 게이트웨이가 떠 있는지 확인")
    sys.exit()
times.sort()
def pct(p):
    return times[min(int(len(times) * p / 100), len(times) - 1)] if times else 0.0
b = lambda pref: sum(1 for c in codes if c.startswith(pref))
c2, c4, c5 = b('2'), b('4'), b('5')
c429 = sum(1 for c in codes if c == '429')
err = sum(1 for c in codes if c == '000')
rps = n / dur
row = f"{conc}\t{n}\t{c2}\t{c4}\t{c429}\t{c5}\t{err}\t{pct(50):.0f}\t{pct(95):.0f}\t{pct(99):.0f}\t{rps:.1f}"
open(report, 'a').write(row + "\n")
# 429 는 레이트리미터가 **일하고 있다는 신호**다 — 실패로 세지 않는다.
flag = ''
if c5 or err:
    flag = '  ⚠️ 5xx/무응답 발생 — 여기가 구조적 한계일 수 있다'
print(f"  동시성 {conc:>3}: {n:>5}req  2xx={c2:<5} 429={c429:<5} 5xx={c5:<4} 무응답={err:<4}"
      f"  p50={pct(50):>5.0f}ms p95={pct(95):>6.0f}ms p99={pct(99):>6.0f}ms  {rps:>6.1f}rps{flag}")
PY
        rm -f "$raw"
    done
done

echo
echo ">>> 결과: $REPORT"
cat <<'NEXT'

>>> 읽는 법:
  · 429 는 정상 — 레이트리미터가 동작한다는 뜻이다(ops/06 이 검증하는 그 동작).
  · 5xx / 무응답이 특정 동시성에서 갑자기 나타나면 **구조적 한계**다. 순서대로 의심할 것:
      1. DB 커넥션 풀 고갈 — 로그의 'Connection is not available, request timed out'
         (풀 크기는 각 서비스 application-local.yml 의 hikari.maximum-pool-size)
      2. 서킷브레이커 개방 — /actuator/metrics/resilience4j.circuitbreaker.state
      3. 스레드 풀 포화 — /actuator/metrics/tomcat.threads.busy
  · p99 가 동시성에 **선형으로** 늘면 큐잉이다. 계단식으로 뛰면 타임아웃/재시도다.
  · 절대 수치는 운영에 이식되지 않는다. 코드 변경 전후를 **같은 머신에서** 비교할 때만 쓸 것.
NEXT
