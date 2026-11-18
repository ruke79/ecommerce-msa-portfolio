#!/usr/bin/env bash
# 운영 액션 60 — JVM 사실 표: 파드마다 JVM 이 실제로 고른 것을 한 표로 (docs/92 §2)
# [JA] 運用アクション 60 — JVM の事実表: Pod ごとに JVM が実際に選んだものを一つの表に
#
# 성능 책 두 권(Java Performance 2판 · Optimizing Cloud Native Java 2판)의 측정 시나리오 중 "부하 없이 바로 확인되는 것"을
# 이 시스템의 파드에 적용한다. JVM 은 컨테이너 한도(CPU · 메모리)를 보고 GC · 힙 · 스레드 수를 스스로 정한다(에르고노믹스) —
# 매니페스트에 적힌 값이 아니라 그 결과가 성능을 정한다. 예: CPU 한도 1 코어면 JDK 17 은 G1 대신 SerialGC(docs/91 §4-3).
#
# 방법: 서비스마다 가장 오래 뜬 Running 파드 하나에서, 그 컨테이너의 한도 · 이미지 · 환경 변수 그대로 두 번째 JVM 을
# `-XX:+PrintFlagsFinal -XshowSettings:system -version` 으로 띄운다 — 같은 입력이면 에르고노믹스도 같다(돌고 있는 JVM 에는
# 붙지 않는다; JRE 이미지라 jcmd 가 없다). 엔트리포인트(ops/Dockerfile.runtime)와 같은 -XX:MaxRAMPercentage 와 JAVA_OPTS 를 주되,
# 파일에 쓰거나 붙는 옵션(-Xlog · JFR · 에이전트 · 덤프 경로)은 뺀다 — 돌고 있는 JVM 의 gc.log 를 덮지 않게.
#
# 표의 열: 서비스 · 파드 · 노드 · CPU/메모리 한도 · JVM 이 본 CPU · 본 메모리 · cgroup · GC · 최대 힙 · 첫 힙 · GC 스레드(병렬/동시) ·
#          JIT 컴파일러 스레드 · 코드 캐시 · 압축 oop · ForkJoin 공통 풀 병렬도(= 본 CPU − 1, 최소 1 — JDK 규칙으로 계산) · JDK 판
# 결과: OUT/jvm-facts.md(표 + 읽는 법) · OUT/raw/<서비스>.txt(JVM 출력 그대로 — 비밀은 없다: JAVA_TOOL_OPTIONS 는 스토어 경로만, B-83)
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/60-jvm-facts.sh                                   # 백엔드 13개(파드가 없는 서비스는 "파드 없음" 줄)
#   ./ops/60-jvm-facts.sh catalog-service gateway-server    # 고른 서비스만
#   ALLOW_PROD=1 ./ops/60-jvm-facts.sh catalog-service      # 운영 — 파드 안에 JVM 하나를 몇 초 더 띄운다(아래)
#
# 환경 변수(기본값): NS=ecommerce  OUT=~/.ecommerce-msa/jvm-facts/<UTC 시각>  ALLOW_PROD=0  KUBECTL=kubectl(테스트가 바꿔 끼운다)
#
# 운영 클러스터(APP_PUBLIC_URL 이 운영 주소)는 ALLOW_PROD=1 일 때만 — 두 번째 JVM 은 `-version` 만 하고 끝나지만(힙은 예약만,
# 실제로 쓰는 메모리는 수십 MB) 768Mi 한도의 파드 안에서 잠깐 함께 산다. 서비스 하나씩, 한가한 시간에.
# [JA] 本番は ALLOW_PROD=1 のときだけ — 二つ目の JVM は -version だけで終わるが、768Mi 上限の Pod の中で一瞬いっしょに動く。
set -uo pipefail

KUBECTL="${KUBECTL:-kubectl}"
NS="${NS:-ecommerce}"
ALLOW_PROD="${ALLOW_PROD:-0}"
OUT="${OUT:-$HOME/.ecommerce-msa/jvm-facts/$(date -u +%Y%m%dT%H%M%SZ)}"
DEFAULT_SERVICES=(gateway-server catalog-service user-service cart-service order-service pay-service coupon-service
    chat-service notification-service delivery-service saga-projection eureka-server config-server)
if [ "$#" -gt 0 ]; then SERVICES=("$@"); else SERVICES=("${DEFAULT_SERVICES[@]}"); fi

die() { echo "ERROR(60): $1" >&2; exit 2; }
k() { "$KUBECTL" -n "$NS" "$@"; }

for s in "${SERVICES[@]}"; do [[ "$s" =~ ^[a-z][a-z0-9-]*$ ]] || die "서비스 이름은 영소문자 · 숫자 · - 만: $s"; done
[[ "$NS" =~ ^[a-z0-9-]+$ ]] || die "NS 는 영소문자 · 숫자 · - 만: $NS"
command -v "$KUBECTL" >/dev/null 2>&1 || die "kubectl 이 필요하다 — kubectl 이 있는 호스트에서 돌린다"

# ── 어느 클러스터인가 ───────────────────────────────────────────────────
url="$(k get configmap ecommerce-config -o jsonpath='{.data.APP_PUBLIC_URL}' 2>/dev/null)" \
    || die "ecommerce-config 를 읽지 못했다 — kubectl 컨텍스트 · NS 를 확인한다"
case "$url" in
    *ysyproject.com*)
        [ "$ALLOW_PROD" = "1" ] || die "운영 클러스터다(APP_PUBLIC_URL=$url) — 파드 안에 JVM 을 하나 더 띄우므로 ALLOW_PROD=1 일 때만"
        where="운영" ;;
    *) where="실습" ;;
esac
mkdir -p "$OUT/raw" || die "결과 폴더를 만들지 못했다: $OUT"

pick() {   # 가장 오래된 Running 파드(HPA 가 늘려도 남는다 — ops/58 과 같다)
    k get pods -l "app=$1" --field-selector=status.phase=Running \
        --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[0].metadata.name}' 2>/dev/null
}
limit() { k get pod "$1" -o jsonpath="{.spec.containers[0].resources.limits.$2}" 2>/dev/null; }

# 파드 안에서 — 엔트리포인트와 같은 입력, 파일에 쓰거나 붙는 옵션만 뺀다(작은따옴표: 변수는 파드 안에서 풀린다)
# shellcheck disable=SC2016
PROBE='opts=""; for o in ${JAVA_OPTS:-}; do case "$o" in -Xlog*|-XX:StartFlightRecording*|-XX:FlightRecorderOptions*|-javaagent*|-agentlib*|-agentpath*|-XX:ErrorFile*|-XX:HeapDumpPath*|-XX:+HeapDumpOnOutOfMemoryError|-Xloggc*) ;; *) opts="$opts $o" ;; esac; done
exec java -XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENTAGE:-25} $opts -XX:-CreateCoredumpOnCrash -XX:+PrintFlagsFinal -XshowSettings:system -version 2>&1'

flag() { awk -v n="$2" '$2 == n && $3 == "=" { print $4; exit }' "$1"; }
mib() { [[ "${1:-}" =~ ^[0-9]+$ ]] && echo "$(( $1 / 1048576 ))M" || echo "?"; }
setting() { sed -n "s/^ *$2: *//p" "$1" | head -1; }

rows=()
for s in "${SERVICES[@]}"; do
    p="$(pick "$s")"
    if [ -z "$p" ]; then rows+=("| $s | 파드 없음 | | | | | | | | | | | | | | |"); continue; fi
    node="$(k get pod "$p" -o jsonpath='{.spec.nodeName}' 2>/dev/null)"
    cpu="$(limit "$p" cpu)"; mem="$(limit "$p" memory)"
    raw="$OUT/raw/$s.txt"
    if ! k exec "$p" -- sh -c "$PROBE" > "$raw" 2>&1 || ! grep -q "MaxHeapSize" "$raw"; then
        rows+=("| $s | $p | ${node:-?} | ${cpu:-없음} / ${mem:-없음} | JVM 을 띄우지 못했다 — raw/$s.txt | | | | | | | | | | | |")
        continue
    fi
    gc="?"
    for g in UseSerialGC:Serial UseParallelGC:Parallel UseG1GC:G1 UseZGC:ZGC UseShenandoahGC:Shenandoah UseEpsilonGC:Epsilon; do
        [ "$(flag "$raw" "${g%%:*}")" = "true" ] && { gc="${g#*:}"; break; }
    done
    [ "$gc" = "ZGC" ] && [ "$(flag "$raw" ZGenerational)" = "true" ] && gc="ZGC(세대)"
    seen_cpu="$(setting "$raw" "Effective CPU Count")"
    # cgroup 지표가 없는 곳(컨테이너 밖 등)에서는 -XshowSettings:system 이 CPU 수를 안 적는다 — 명시한 ActiveProcessorCount 가 있으면 그것
    if ! [[ "$seen_cpu" =~ ^[0-9]+$ ]]; then apc="$(flag "$raw" ActiveProcessorCount)"; [[ "$apc" =~ ^[1-9][0-9]*$ ]] && seen_cpu="$apc"; fi
    seen_mem="$(setting "$raw" "Memory Limit")"
    provider="$(setting "$raw" "Provider")"
    if [[ "$seen_cpu" =~ ^[0-9]+$ ]]; then fj=$(( seen_cpu > 1 ? seen_cpu - 1 : 1 )); else fj="?"; fi
    ver="$(sed -n 's/^.*version "\([^"]*\)".*$/\1/p' "$raw" | head -1)"
    rows+=("| $s | $p | ${node:-?} | ${cpu:-없음} / ${mem:-없음} | ${seen_cpu:-?} | ${seen_mem:-?} | ${provider:-?} | $gc | $(mib "$(flag "$raw" MaxHeapSize)") | $(mib "$(flag "$raw" InitialHeapSize)") | $(flag "$raw" ParallelGCThreads)/$(flag "$raw" ConcGCThreads) | $(flag "$raw" CICompilerCount) | $(mib "$(flag "$raw" ReservedCodeCacheSize)") | $(flag "$raw" UseCompressedOops) | $fj | ${ver:-?} |")
done

{
    echo "# JVM 사실 표 — $where 클러스터 · NS $NS · $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "| 서비스 | 파드 | 노드 | 한도 CPU / 메모리 | 본 CPU | 본 메모리 | cgroup | GC | 최대 힙 | 첫 힙 | GC 스레드 병렬/동시 | JIT 스레드 | 코드 캐시 | 압축 oop | ForkJoin 병렬도 | JDK |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    printf '%s\n' "${rows[@]}"
    echo
    echo "읽는 법(docs/92 §2): **본 CPU < 2 면 GC 는 Serial** — 정지 동안 한 스레드가 전부 치운다(꼬리 지연, docs/91 §4-3). 최대 힙은"
    echo "메모리 한도 × MaxRAMPercentage(이미지 기본 50%). ForkJoin 병렬도는 parallelStream · CompletableFuture 기본 실행기의 폭이다 —"
    echo "1 이면 병렬이 아니다. 같은 한도 · 같은 옵션으로 띄운 두 번째 JVM 의 값이다(돌고 있는 JVM 과 같다고 본다 —"
    echo "GC 종류는 Prometheus 의 \`count by (service, gc) (jvm_gc_pause_seconds_count)\` 로 맞춰 볼 수 있다)."
} > "$OUT/jvm-facts.md"

cat "$OUT/jvm-facts.md"
echo
echo ">>> $OUT/jvm-facts.md · raw/<서비스>.txt"
