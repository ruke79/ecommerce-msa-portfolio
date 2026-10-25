#!/usr/bin/env bash
# 운영 액션 58 — 부하 중 CPU 프로파일: 정해 둔 부하에 닿으면 서비스마다 파드 하나에서 CPU 표본을 뜬다 (docs/83 §4)
# [JA] 運用アクション 58 — 負荷中の CPU プロファイル: 決めた負荷に届いたら、サービスごとに Pod 一つで CPU サンプルを取る
#
# capacity-steps(2026-10-23)에서 catalog 는 성공 ~960 요청/초(파드당 ~300), 게이트웨이는 ~1,450(파드당 ~480)에서 CPU 1 코어에
# 붙었다. 더 받으려면 요청당 CPU 를 줄여야 하는데, 어디에 쓰는지는 지표로는 모른다 — 그래서 그 단계에서 표본을 뜬다.
# async-profiler(itimer — 컨테이너에서 perf_events 권한 없이 된다)를 파드 안에 복사해 nohup 으로 돌리고(kubectl exec 연결이
# 끊겨도 표본은 이어진다), 끝나면 collapsed 를 가져와 표 셋을 만든다:
#   flat      — 맨 위(잎) 프레임별 표본 수
#   inclusive — 표본마다 한 번씩, 그 스택에 든 메서드별(어느 호출 아래에서 시간이 쓰이나)
#   묶음      — 표본을 잎에서 가장 가까운 아는 묶음(TLS · JSON · Netty · JDBC · Redis · GC · JIT …)에 붙인 합
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   # 한 번: async-profiler 를 이 기계(kubectl 이 있는 호스트)에 받는다 — 실습 노드는 amd64
#   curl -fsSL https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz | tar -xz -C /tmp
#
#   # capacity-steps 를 시작하기 전이든 직후든 — 게이트웨이가 초당 900 요청을 받는 순간(200 단계)부터 20초 뒤 120초
#   PROM=http://<obs-vm>:9090 ./ops/58-cpu-profile.sh                       # 기본: catalog-service gateway-server
#   PROM=http://<obs-vm>:9090 WHEN=rps:gateway-server:1300 ./ops/58-cpu-profile.sh   # 300 단계(게이트웨이 포화)
#   WHEN=now SECS=60 ./ops/58-cpu-profile.sh catalog-service                  # 바로
#
# 환경 변수(기본값):
#   WHEN=rps:gateway-server:900   rps:<서비스>:<요청/초> — 그 서비스가 받는 요청/초(Prometheus, 1분 rate)가 넘으면 시작 / now — 바로
#   PROM                          WHEN=rps 이면 필요(obs-vm 의 Prometheus). PROM_ENV=demo
#   SECS=120  DELAY=20            표본 길이 · 조건에 닿은 뒤 기다리는 초(단계가 막 바뀐 순간을 피한다)
#   POLL=10  WAIT_MAX=1800        조건을 보는 간격 · 최대 기다림(초)
#   EVENT=itimer  TOP=40          async-profiler 이벤트 · 표마다 줄 수
#   AP=/tmp/async-profiler-3.0-linux-x64   OUT=~/.ecommerce-msa/profiles/<UTC 시각>   NS=ecommerce
#   KUBECTL=kubectl  CURL=curl    (테스트가 바꿔 끼운다)
#
# 실습 클러스터에서만 — 파드 안에 실행 파일을 넣고 JVM 에 붙는다. 이 클러스터의 APP_PUBLIC_URL 이 운영 주소거나 비어 있으면 거절한다(ops/53 과 같은 판정).
# 파드는 서비스마다 가장 오래 뜬 Running 파드 하나(HPA 가 늘려도 남고, JIT 이 데워져 있다). 표본 중에 그 파드가 다시 뜨면 결과가 없다 — 재시작 수를 함께 남긴다.
# [JA] 実習クラスタだけ。サービスごとに最も古い Running Pod 一つ。サンプル中に再起動すると結果は残らない — 再起動数も記録する。
set -uo pipefail

KUBECTL="${KUBECTL:-kubectl}"
CURL="${CURL:-curl}"
NS="${NS:-ecommerce}"
WHEN="${WHEN:-rps:gateway-server:900}"
PROM="${PROM:-}"
PROM="${PROM%/api/v1/write}"
PROM_ENV="${PROM_ENV:-demo}"
SECS="${SECS:-120}"
DELAY="${DELAY:-20}"
POLL="${POLL:-10}"
WAIT_MAX="${WAIT_MAX:-1800}"
EVENT="${EVENT:-itimer}"
TOP="${TOP:-40}"
AP="${AP:-/tmp/async-profiler-3.0-linux-x64}"
OUT="${OUT:-$HOME/.ecommerce-msa/profiles/$(date -u +%Y%m%dT%H%M%SZ)}"

die() { echo "✗ $*" >&2; exit 1; }
now() { date -u '+%H:%M:%SZ'; }
k() { "$KUBECTL" -n "$NS" "$@"; }

SERVICES=("$@")
[ ${#SERVICES[@]} -gt 0 ] || SERVICES=(catalog-service gateway-server)
for s in "${SERVICES[@]}"; do [[ "$s" =~ ^[a-z][a-z0-9-]*$ ]] || die "서비스 이름은 영소문자 · 숫자 · - 만: $s"; done
for v in SECS DELAY POLL WAIT_MAX TOP; do [[ "${!v}" =~ ^[0-9]+$ ]] || die "$v 는 숫자만: ${!v}"; done
[[ "$EVENT" =~ ^[a-z]+$ ]] || die "EVENT 는 영소문자만: $EVENT"
[[ "$PROM_ENV" =~ ^[a-z0-9-]+$ ]] || die "PROM_ENV 는 영소문자 · 숫자 · - 만"

case "$WHEN" in
    now) ;;
    rps:*:*)
        WHEN_SVC="${WHEN#rps:}"; WHEN_SVC="${WHEN_SVC%%:*}"; WHEN_RPS="${WHEN##*:}"
        [[ "$WHEN_SVC" =~ ^[a-z][a-z0-9-]*$ && "$WHEN_RPS" =~ ^[0-9]+$ ]] || die "WHEN 은 rps:<서비스>:<요청/초> 또는 now: $WHEN"
        [ -n "$PROM" ] || die "WHEN=$WHEN 은 PROM(http://<obs-vm>:9090)이 필요하다 — 아니면 WHEN=now" ;;
    *) die "WHEN 은 rps:<서비스>:<요청/초> 또는 now: $WHEN" ;;
esac

# ── 실습 클러스터인가 ───────────────────────────────────────────────────
command -v "$KUBECTL" >/dev/null 2>&1 || die "kubectl 이 필요하다 — kubectl 이 있는 호스트에서 돌린다(loadgen 에는 없다)"
url="$(k get configmap ecommerce-config -o jsonpath='{.data.APP_PUBLIC_URL}' 2>/dev/null)" \
    || die "ecommerce-config 를 읽지 못했다 — kubectl 컨텍스트가 실습 클러스터인지"
case "$url" in
    *ysyproject.com*) die "운영 클러스터다(APP_PUBLIC_URL=$url) — 파드에 프로파일러를 넣지 않는다" ;;
    "") die "APP_PUBLIC_URL 이 비어 있다 — 실습 클러스터(데모 오버레이)인지 확인할 수 없다" ;;
esac

[ -x "$AP/bin/asprof" ] && [ -f "$AP/lib/libasyncProfiler.so" ] || die "async-profiler 가 없다: $AP — 받기:
    curl -fsSL https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz | tar -xz -C /tmp"
mkdir -p "$OUT" || die "결과 폴더를 만들지 못했다: $OUT"

# ── 파드 고르기 · 프로파일러 넣기 ───────────────────────────────────────
pick() {   # 가장 오래된 Running 파드
    k get pods -l "app=$1" --field-selector=status.phase=Running \
        --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[0].metadata.name}' 2>/dev/null
}
restarts() { k get pod "$1" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null; }
node_of() { k get pod "$1" -o jsonpath='{.spec.nodeName}' 2>/dev/null; }
java_pid() {
    k exec "$1" -- sh -c 'for p in /proc/[0-9]*; do case "$(readlink $p/exe 2>/dev/null)" in */java) echo "${p#/proc/}";; esac; done' 2>/dev/null | head -1
}

declare -A POD JPID
for s in "${SERVICES[@]}"; do
    p="$(pick "$s")"; [ -n "$p" ] || die "$s: Running 파드가 없다(-l app=$s)"
    if ! k exec "$p" -- test -x /tmp/ap/bin/asprof 2>/dev/null; then
        k cp "$AP" "$p:/tmp/ap" >/dev/null 2>&1 || die "$s: 프로파일러를 $p 에 복사하지 못했다"
        k exec "$p" -- chmod -R u+x /tmp/ap/bin /tmp/ap/lib >/dev/null 2>&1
    fi
    j="$(java_pid "$p")"; [[ "$j" =~ ^[0-9]+$ ]] || die "$s: $p 에서 java PID 를 찾지 못했다"
    # 붙는지 3초만 — 표본 때 처음 실패하면 그 순간을 놓친다
    k exec "$p" -- /tmp/ap/bin/asprof -e "$EVENT" -d 3 -o flat=3 "$j" >/dev/null 2>&1 \
        || die "$s: $p 의 JVM 에 붙지 못했다(asprof -e $EVENT) — EVENT=cpu 는 perf_events 권한이 있어야 한다"
    POD[$s]="$p"; JPID[$s]="$j"
    echo "  $s: $p ($(node_of "$p"), java $j, 재시작 $(restarts "$p"))"
done
echo "프로파일러 준비됨 — 결과: $OUT"

# ── 조건 기다리기 ───────────────────────────────────────────────────────
rps() {   # rps <서비스> → 정수 요청/초(읽지 못하면 실패)
    "$CURL" -fsS --max-time 10 -G "$PROM/api/v1/query" --data-urlencode \
        "query=sum(rate(http_server_requests_seconds_count{environment=\"$PROM_ENV\",service=\"$1\"}[1m]))" 2>/dev/null \
      | python3 -I -c '
import json, sys
r = json.load(sys.stdin)
v = r["data"]["result"] if r.get("status") == "success" else []
if not v:
    sys.exit(1)
print(int(float(v[0]["value"][1])))'
}
START_NOTE="now"
if [ "$WHEN" != now ]; then
    echo "$(now) 기다린다: $WHEN_SVC > ${WHEN_RPS}/초 (최대 ${WAIT_MAX}초, ${POLL}초마다)"
    waited=0; r=0
    while :; do
        r="$(rps "$WHEN_SVC")" || r=0
        [ "$r" -gt "$WHEN_RPS" ] && break
        [ "$waited" -ge "$WAIT_MAX" ] && die "조건에 닿지 않았다($WHEN_SVC 마지막 ${r}/초) — 측정이 도는지 · PROM 이 맞는지"
        sleep "$POLL"; waited=$((waited + POLL))
    done
    START_NOTE="$WHEN_SVC ${r}/초"
    echo "$(now) $START_NOTE — ${DELAY}초 뒤 ${SECS}초 표본"
fi
sleep "$DELAY"

# ── 표본 — 파드 안에서 떼어 놓고 ────────────────────────────────────────
declare -A R0
for s in "${SERVICES[@]}"; do
    p="${POD[$s]}"; R0[$s]="$(restarts "$p")"
    k exec "$p" -- sh -c "rm -f /tmp/prof-$s.collapsed; nohup /tmp/ap/bin/asprof -e $EVENT -d $SECS -o collapsed -f /tmp/prof-$s.collapsed ${JPID[$s]} >/tmp/prof-$s.log 2>&1 &" \
        || echo "  ✗ $s: 표본을 시작하지 못했다"
done
STARTED="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
sleep $((SECS + 10))
END_NOTE=""
[ "$WHEN" != now ] && END_NOTE="$WHEN_SVC $(rps "$WHEN_SVC" || echo '?')/초"

# ── 가져오기 · 표 ───────────────────────────────────────────────────────
summarize() {   # summarize <collapsed> <TOP> → flat · inclusive · 묶음
    python3 -I - "$1" "$2" <<'PY'
import collections, re, sys
path, top = sys.argv[1], int(sys.argv[2])
flat, incl, cat, tot = collections.Counter(), collections.Counter(), collections.Counter(), 0
# 잎에서 가장 가까운 것부터 — 처음 맞는 묶음에 표본을 붙인다(라이브러리 안쪽 · JDK · JVM 네이티브 잎도 부른 쪽 묶음으로)
# GC · JIT 은 JVM 의 C++ 프레임(`Klass::method`)에서만 본다 — 자바 클래스 이름에 Mark · Compile 이 들어가도 걸리지 않게.
CATS = [
    ("GC", r"^(G1|Serial|Parallel|PS|Z[A-Z]|Shenandoah|GC|Gen|Young|Tenured|DefNew)\w*::|^\w*(Mark|Evacuat|Compact|Scavenge)\w*::"),
    ("JIT", r"^(CompileBroker|C2Compiler|C1Compiler|Compilation|Compile|Phase\w*|Matcher|Parse|ciEnv|LinearScan|GraphBuilder)\w*::"),
    ("TLS · 암호", r"^(sun\.security\.ssl|javax\.crypto|com\.sun\.crypto|sun\.security\.(ec|rsa|provider)|io\.netty\.handler\.ssl|org\.bouncycastle)"),
    ("JSON", r"^(com\.fasterxml\.jackson)"),
    ("로그", r"^(ch\.qos\.logback|org\.apache\.logging|org\.slf4j)"),
    ("관측(지표 · 추적)", r"^(io\.micrometer|io\.opentelemetry|brave\.|zipkin2)"),
    ("JDBC · 풀", r"^(org\.postgresql|com\.zaxxer\.hikari)"),
    ("JPA · Hibernate", r"^(org\.hibernate|jakarta\.persistence)"),
    ("Redis", r"^(io\.lettuce|org\.springframework\.data\.redis|redis\.clients)"),
    ("JWT", r"^(io\.jsonwebtoken|com\.nimbusds|org\.springframework\.security\.oauth2)"),
    ("Spring Cloud Gateway · 회복력", r"^(org\.springframework\.cloud\.gateway|io\.github\.resilience4j|org\.springframework\.cloud\.client)"),
    ("Netty · Reactor", r"^(io\.netty|reactor\.)"),
    ("Tomcat", r"^(org\.apache\.(coyote|catalina|tomcat))"),
    ("Spring", r"^(org\.springframework)"),
    ("앱", r"^(com\.project)"),
    ("JDK", r"^(java\.|javax\.|jdk\.|sun\.)"),
]
CATS = [(n, re.compile(p)) for n, p in CATS]
def norm(f):
    # async-profiler 의 자바 프레임은 `java/lang/String.equals` — 점으로 바꿔 패키지로 묶는다. 붙는 표시(_[j] · _[i] · _[k])는 뗀다.
    f = re.sub(r"_\[[a-z0-9]\]$", "", f)
    return f.replace("/", ".") if "::" not in f else f
for line in open(path, encoding="utf-8", errors="replace"):
    stack, _, n = line.rstrip("\n").rpartition(" ")
    if not n.isdigit() or not stack:
        continue
    n = int(n); tot += n
    frames = [norm(f) for f in stack.split(";")]
    flat[frames[-1]] += n
    for f in set(frames):
        incl[f] += n
    # JDK 프레임(String · HashMap …)은 건너뛰고 부른 쪽 묶음으로 — 다른 묶음이 하나도 없을 때만 JDK
    seen_jdk = False
    for f in reversed(frames):
        name = next((c for c, rx in CATS if rx.search(f)), None)
        if name == "JDK":
            seen_jdk = True
        elif name:
            cat[name] += n
            break
    else:
        cat["JDK" if seen_jdk else "그 밖(네이티브 · 커널)"] += n
if not tot:
    print("표본 0"); sys.exit(1)
pct = lambda n: f"{n:8d} {100 * n / tot:6.2f}%"
print(f"표본 {tot}\n\n== 묶음 — 잎에서 가장 가까운 아는 묶음")
for c, n in cat.most_common():
    print(f"{pct(n)}  {c}")
print(f"\n== flat — 맨 위(잎) 프레임, 위 {top}")
for f, n in flat.most_common(top):
    print(f"{pct(n)}  {f}")
print(f"\n== inclusive — 그 메서드가 스택에 있던 표본, 위 {top}(스레드 · 루프의 뿌리는 거의 100%)")
for f, n in incl.most_common(top):
    print(f"{pct(n)}  {f}")
PY
}

{
    echo "started_utc=$STARTED"; echo "secs=$SECS"; echo "event=$EVENT"; echo "when=$WHEN"
    echo "load_start=$START_NOTE"; [ -n "$END_NOTE" ] && echo "load_end=$END_NOTE"
} > "$OUT/meta.txt"

fail=0
for s in "${SERVICES[@]}"; do
    p="${POD[$s]}"; r1="$(restarts "$p")"
    echo "$s pod=$p node=$(node_of "$p") restarts_before=${R0[$s]} restarts_after=$r1" >> "$OUT/meta.txt"
    # kubectl cp 는 원본이 없어도 0 을 돌려준다 — 옛 결과가 남지 않게 먼저 지우고, 파드 안의 파일부터 본다
    rm -f "$OUT/$s.collapsed" "$OUT/$s.txt"
    if ! k exec "$p" -- test -s "/tmp/prof-$s.collapsed" 2>/dev/null; then
        echo "  ✗ $s: 파드 안에 결과가 없다(재시작 ${R0[$s]} → $r1 — 다시 떴으면 /tmp 가 비었다). 프로파일러 로그:"
        k exec "$p" -- cat "/tmp/prof-$s.log" 2>/dev/null | tail -5 | sed 's/^/      /'
        fail=1; continue
    fi
    if k cp "$p:/tmp/prof-$s.collapsed" "$OUT/$s.collapsed" >/dev/null 2>&1 && [ -s "$OUT/$s.collapsed" ] \
        && summarize "$OUT/$s.collapsed" "$TOP" > "$OUT/$s.txt"; then
        echo "  ✓ $s: $(head -1 "$OUT/$s.txt") → $OUT/$s.txt"
        sed -n '/^== 묶음/,/^$/p' "$OUT/$s.txt" | sed 's/^/      /'
    else
        echo "  ✗ $s: 결과를 가져오거나 읽지 못했다"; fail=1
    fi
done
echo "$(now) 끝 — $OUT (meta.txt · <서비스>.txt · <서비스>.collapsed)"
[ -n "$END_NOTE" ] && echo "  부하: 시작 $START_NOTE · 끝 $END_NOTE"
exit "$fail"
