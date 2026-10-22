#!/usr/bin/env bash
# 운영 액션 53 — k6 부하 시나리오: 조회 · 주문 · 급증 (docs/83)
# [JA] 運用アクション — k6 負荷シナリオ: 閲覧 · 注文 · 急増(docs/83)
#
# ops/14(curl, GET 하나)로는 로그인 → 장바구니 → 주문 같은 쓰기 경로를 잴 수 없다. 이 스크립트는 ops/k6/ 의 시나리오를
# 화면과 같은 API · 같은 헤더로 돌리고, 결과(요약 · 그때의 이미지 판 · 설정)를 한 폴더에 남긴다 — 고치기 전후를 같은 조건으로
# 비교하려고.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/53-load-k6.sh browse                 # 비회원 조회: 홈 → 카테고리 목록 → 상품 상세 (계단식 도착률)
#   ./ops/53-load-k6.sh order                  # 데모 사용자 로그인 → 장바구니 → 체크아웃 → 주문 → 무통장 입금 안내
#   ./ops/53-load-k6.sh spike                  # 조회를 짧게 몰아 HPA 가 늘어나는 동안의 오류를 본다
#   ./ops/53-load-k6.sh smoke                  # 조회 · 주문을 한 번씩 — 본 측정 전에 경로 · 계정 · 데이터 · 인증서 확인
#
#   ./ops/53-load-k6.sh limits load [초당 [버스트]]   # 게이트웨이 기본 유입 제한을 잠깐 올린다(기본 2000 · 4000) — 실습 클러스터만
#   ./ops/53-load-k6.sh limits default         # 되돌린다(20 · 40). 측정이 끝나면 꼭 — 올린 채면 ops/06 스모크의 429 확인이 실패한다
#   ./ops/53-load-k6.sh limits status          # 지금 값
#
#   ./ops/53-load-k6.sh pool load [크기 [서비스]]  # 서비스의 DB 커넥션 풀을 잠깐 키운다(기본 10 · catalog-service) — 실습 클러스터만,
#                                              # DB 의 max_connections 안에 드는지 먼저 센다. 파드가 다시 뜬다(데움 1분)
#   ./ops/53-load-k6.sh pool default [서비스]   # 되돌린다(DB_POOL_SIZE 없음 = 5)
#   ./ops/53-load-k6.sh pool status            # 지금 값(서비스마다)
#
#   ./ops/53-load-k6.sh replicas load [수 [서비스…]]  # 레플리카를 미리 늘린다(기본 3 · catalog-service gateway-server) — 실습 클러스터만,
#                                              # HPA 최소값을 함께 올리고, 노드에 CPU · 메모리 요청 여유가 있는지 먼저 센다
#   ./ops/53-load-k6.sh replicas default [서비스…]   # 되돌린다(HPA 최소 · 최대는 k8s/70, HPA 없는 서비스는 demo 오버레이 값)
#   ./ops/53-load-k6.sh replicas status        # 지금 레플리카 · HPA
#
#   ./ops/53-load-k6.sh report                 # 결과 모아 보기: 모든 회차 한 줄씩 + 가장 최근 회차의 요약
#   ./ops/53-load-k6.sh report <폴더|이름>...   # 그 회차들의 요약(엔드포인트별 응답 시간 · 실패율 · 조건)
#   ./ops/53-load-k6.sh report all             # 모든 회차의 요약
#
#   RATE=200 ./ops/53-load-k6.sh browse        # 최고 도착률(초당 반복) 바꾸기
#   STAGES="1m:50,3m:50,1m:0" ./ops/53-load-k6.sh order   # 단계를 직접(길이:목표 도착률, 쉼표로)
#   PRODUCTS=1000000 RATE=50 ./ops/53-load-k6.sh browse   # 데이터 규모 모드 — ops/44 로 넣은 범위 전체에서 상세 · 깊은 쪽 · 이름 검색
#
# 환경 변수(기본값):
#   GATEWAY=https://shop.lab        # 데모 클러스터 입구. 운영 주소(ysyproject.com)는 거절한다
#   CA_FILE=~/.ecommerce-msa/demo/certs/ca.crt   # 데모 CA — 검증을 끄지 않고 이 CA 를 믿는다(SSL_CERT_FILE)
#   USERS=100  ID_OFFSET=0          # order 가 쓰는 데모 계정 demo<번호>@demo.invalid 범위(ops/48 과 같은 번호)
#   PASSWORD_FILE=~/.ecommerce-msa/demo-users.password   # 데모 비밀번호(ops/48). 화면 · 명령줄에 나오지 않는다
#   RATE · STAGES · DURATION        # 부하 모양(시나리오 기본값은 ops/k6/<시나리오>.js 맨 위)
#   ALLOW_RATE_LIMIT=1              # 유입 제한을 올리지 않은 채 돌린다(제한 자체를 잴 때) — 없으면 시작 전 확인이 429 에서 멈춘다
#   PRODUCTS  PRODUCT_ID_OFFSET=1000000  DEEP_PAGES=50  NAME_SEARCH=0.2   # browse · spike 의 데이터 규모 모드(docs/83 §2.1)
#   OUT=./load-results              # 결과 폴더 — <시나리오>-<UTC 시각>/ 아래 summary.json · summary.txt · meta.txt
#   PROM_RW=http://<obs-vm>:9090/api/v1/write   # 주면 k6 지표를 Prometheus 로도(그라파나에서 서버 지표와 같은 시간축)
#   PROM=http://<obs-vm>:9090  PROM_ENV=demo     # 레플리카(시작 · 측정 중 최대 · 끝)를 Prometheus 에서 읽어 meta.txt 에(없으면 PROM_RW 에서)
#   K6=k6                          # k6 실행 파일(https://grafana.com/docs/k6/latest/set-up/install-k6/)
#
# 🔒 데모 계정은 모두 같은 비밀번호다 — 파일에서 읽어 k6 의 환경 변수로만 넘긴다(ps 에 보이는 명령줄 인자가 아니다).
#    결과 파일에는 비밀번호 · 토큰을 쓰지 않는다.
# ⚠️ 절대 수치는 이 실습 클러스터(데스크톱 한 대 위 VM)에서만 뜻이 있다. 이력서 · 문서에는 "실습 클러스터 기준" 을 함께 적는다.
#    측정 중에는 CI 러너를 멈춘다 — 같은 CPU 를 나눠 쓴다.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
K6_DIR="$HERE/k6"
K6="${K6:-k6}"
GATEWAY="${GATEWAY:-https://shop.lab}"
CA_FILE="${CA_FILE:-$HOME/.ecommerce-msa/demo/certs/ca.crt}"
USERS="${USERS:-100}"
ID_OFFSET="${ID_OFFSET:-0}"
PASSWORD_FILE="${PASSWORD_FILE:-$HOME/.ecommerce-msa/demo-users.password}"
OUT="${OUT:-./load-results}"
APP_NS="${APP_NS:-ecommerce}"

die()  { echo "ERROR: $1" >&2; exit 1; }
info() { echo ">>> $*"; }
warn() { echo "  ⚠️  $*" >&2; }

usage() { sed -n '2,54p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

SCENARIO="${1:-}"
case "$SCENARIO" in
    browse|order|spike|smoke|limits|pool|replicas|report) ;;
    *) usage ;;
esac

# ── 유입 제한 — 측정하는 동안만 ──────────────────────────────────────────
#
# 비로그인 요청(카탈로그 읽기)은 버킷 키가 IP 다. 데모에는 Cloudflare 가 없어 모든 바깥 요청이 Traefik 파드 IP 하나로 보이고,
# 그 버킷(초당 20 · 버스트 40)을 모든 손님이 나눠 쓴다 — 그대로 재면 결과는 "초당 20 에서 429" 뿐이다. 그래서 측정하는 동안만
# 게이트웨이의 기본 제한을 올린다(GATEWAY_RATE_LIMIT_*, gateway application-local.yml — 기본값은 그대로). 로그인 제한(초당 1)은
# 바꿀 수 없다. 운영 클러스터에는 하지 않는다: 이 클러스터의 APP_PUBLIC_URL 이 운영 주소면 거절한다.
# [JA] 計測の間だけゲートウェイの既定の流入制限を上げる。本番クラスタでは拒否する。ログイン制限は変えられない。
# 실습 클러스터인가 — 측정용 예외(kubectl set env)를 운영에 걸지 않는다. 이 클러스터의 APP_PUBLIC_URL 이 운영 주소거나 비어 있으면 멈춘다.
lab_cluster_or_die() {   # lab_cluster_or_die <무엇을 바꾸려는지>
    command -v kubectl >/dev/null 2>&1 || die "kubectl 이 필요하다"
    local url
    url="$(kubectl -n "$APP_NS" get configmap ecommerce-config -o jsonpath='{.data.APP_PUBLIC_URL}' 2>/dev/null)" \
        || die "ecommerce-config 를 읽지 못했다 — kubectl 컨텍스트가 실습 클러스터인지"
    case "$url" in
        *ysyproject.com*) die "운영 클러스터다(APP_PUBLIC_URL=$url) — $1 은 바꾸지 않는다" ;;
        "") die "APP_PUBLIC_URL 이 비어 있다 — 실습 클러스터(데모 오버레이)인지 확인할 수 없다" ;;
    esac
    PUBLIC_URL="$url"
}

cmd_limits() {
    lab_cluster_or_die "유입 제한"
    local url="$PUBLIC_URL"
    case "${2:-status}" in
        load)
            local r="${3:-2000}" b="${4:-}"
            [ -n "$b" ] || b=$(( r * 2 ))
            [[ "$r" =~ ^[1-9][0-9]*$ && "$b" =~ ^[1-9][0-9]*$ ]] || die "초당 · 버스트는 1 이상의 정수"
            [ "$b" -ge "$r" ] || die "버스트는 초당 이상"
            info "게이트웨이 기본 유입 제한 → 초당 $r · 버스트 $b ($url) — 끝나면 ./ops/53-load-k6.sh limits default"
            kubectl -n "$APP_NS" set env deploy/gateway-server GATEWAY_RATE_LIMIT_REPLENISH="$r" GATEWAY_RATE_LIMIT_BURST="$b" >/dev/null
            kubectl -n "$APP_NS" rollout status deploy/gateway-server --timeout=300s
            ;;
        default)
            info "게이트웨이 기본 유입 제한 → 기본값(초당 20 · 버스트 40)"
            kubectl -n "$APP_NS" set env deploy/gateway-server GATEWAY_RATE_LIMIT_REPLENISH- GATEWAY_RATE_LIMIT_BURST- >/dev/null
            kubectl -n "$APP_NS" rollout status deploy/gateway-server --timeout=300s
            ;;
        status)
            local cur
            cur="$(kubectl -n "$APP_NS" get deploy gateway-server -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
                | grep '^GATEWAY_RATE_LIMIT_' || true)"
            if [ -n "$cur" ]; then echo "$cur"; else echo "기본값(초당 20 · 버스트 40)"; fi
            ;;
        *) usage ;;
    esac
}
if [ "$SCENARIO" = "limits" ]; then
    cmd_limits "$@"
    exit 0
fi

# ── DB 커넥션 풀 — 측정하는 동안만 ────────────────────────────────────────
#
# 2026-10-18 browse 의 첫 유효 회차: 100 반복/초에서 catalog 의 HikariCP 대기가 0 → 30, catalog p95 31 → 390ms, 그만큼 게이트웨이의
# 벌크헤드(동시 25)가 넘쳐 503 이었다(docs/83 §5). 풀(DB_POOL_SIZE, 기본 5)이 병목인지는 풀만 바꿔 같은 조건으로 다시 재야 안다.
# 데이터소스 수(catalog 는 2개) × 크기 × HPA 최대 레플리카만큼 DB 연결이 늘 수 있으니, 바꾸기 전에 max_connections 안에 드는지 센다.
# [JA] 計測の間だけ DB コネクションプールを大きくする。変える前に max_connections に収まるかを数える。本番クラスタでは拒否する。
POOL_DEFAULT=5
pool_now() {   # pool_now <deploy> → 지금 크기(설정 없으면 기본값)
    local v
    v="$(kubectl -n "$APP_NS" get deploy "$1" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DB_POOL_SIZE")].value}' 2>/dev/null)"
    echo "${v:-$POOL_DEFAULT}"
}
cmd_pool() {
    lab_cluster_or_die "DB 커넥션 풀"
    local action="${2:-status}" size svc
    case "$action" in
        load)    size="${3:-10}"; svc="${4:-catalog-service}" ;;
        default) svc="${3:-catalog-service}" ;;
        status)  ;;
        *) usage ;;
    esac
    if [ "$action" = "status" ]; then
        kubectl -n "$APP_NS" get deploy -o name 2>/dev/null | sed 's#.*/##' | while read -r d; do
            local v
            v="$(kubectl -n "$APP_NS" get deploy "$d" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DB_POOL_SIZE")].value}' 2>/dev/null)"
            [ -n "$v" ] && echo "$d DB_POOL_SIZE=$v"
        done
        echo "(목록에 없는 서비스는 기본값 $POOL_DEFAULT)"
        return 0
    fi
    [[ "$svc" =~ ^[a-z]+(-[a-z]+)*$ ]] || die "서비스 이름이 이상하다: $svc"
    kubectl -n "$APP_NS" get deploy "$svc" >/dev/null 2>&1 || die "그런 Deployment 가 없다: $svc"
    if [ "$action" = "default" ]; then
        info "$svc DB 커넥션 풀 → 기본값($POOL_DEFAULT) — 파드가 다시 뜬다"
        kubectl -n "$APP_NS" set env "deploy/$svc" DB_POOL_SIZE- >/dev/null
        kubectl -n "$APP_NS" rollout status "deploy/$svc" --timeout=600s
        return 0
    fi
    [[ "$size" =~ ^[1-9][0-9]?$ ]] || die "크기는 1 ~ 99"

    # 예산 — 데이터소스 수는 그 서비스 설정의 maximum-pool-size 줄 수(catalog 는 2), 레플리카는 HPA 최대(없으면 지금 수)
    local yml="$HERE/../$svc/src/main/resources/application-local.yml" pools maxrep now add max used
    pools="$(grep -c 'maximum-pool-size: ${DB_POOL_SIZE' "$yml" 2>/dev/null || true)"
    [ "${pools:-0}" -ge 1 ] || die "$svc 의 설정에 DB_POOL_SIZE 를 쓰는 풀이 없다($yml) — DB 를 쓰지 않는 서비스다"
    maxrep="$(kubectl -n "$APP_NS" get hpa "$svc" -o jsonpath='{.spec.maxReplicas}' 2>/dev/null || true)"
    [ -n "$maxrep" ] || maxrep="$(kubectl -n "$APP_NS" get deploy "$svc" -o jsonpath='{.spec.replicas}')"
    now="$(pool_now "$svc")"
    add=$(( pools * (size - now) * maxrep ))
    max="$(kubectl -n "${PG_NS:-postgres}" exec deploy/postgres -- psql -U postgres -tAc 'show max_connections' 2>/dev/null | tr -d '[:space:]' || true)"
    used="$(kubectl -n "${PG_NS:-postgres}" exec deploy/postgres -- psql -U postgres -tAc 'select count(*) from pg_stat_activity' 2>/dev/null | tr -d '[:space:]' || true)"
    [[ "$max" =~ ^[0-9]+$ && "$used" =~ ^[0-9]+$ ]] \
        || die "PostgreSQL 에서 max_connections · 지금 연결 수를 읽지 못했다(kubectl -n ${PG_NS:-postgres} exec deploy/postgres -- psql …) — 예산을 모르면 바꾸지 않는다"
    info "예산: 지금 연결 $used / max_connections $max · 늘 수 있는 연결 $pools 풀 × ($size − $now) × 최대 레플리카 $maxrep = $add"
    [ $(( used + add )) -le $(( max - 20 )) ] \
        || die "모자란다 — $used + $add > $max − 20(여유: 운영 도구 · 생성기 · superuser 예약). 크기를 줄이거나 max_connections 를 올린다(데모 인프라의 postgres 덧씌우기)"

    info "$svc DB 커넥션 풀 $now → $size ($PUBLIC_URL) — 파드가 다시 뜬다. 끝나면 ./ops/53-load-k6.sh pool default $svc"
    kubectl -n "$APP_NS" set env "deploy/$svc" DB_POOL_SIZE="$size" >/dev/null
    kubectl -n "$APP_NS" rollout status "deploy/$svc" --timeout=600s
    info "새 파드는 처음 1분이 데움이다(JIT · 캐시) — smoke 한 번 뒤에 잰다"
}
if [ "$SCENARIO" = "pool" ]; then
    cmd_pool "$@"
    exit 0
fi

# ── 레플리카 — 측정하는 동안만 ────────────────────────────────────────────
#
# 2026-10-18 풀 실험: 100 반복/초에서 catalog · 게이트웨이 모두 CPU 상한(1 코어)에 붙었다(docs/83 §5). HPA 가 늘리긴 하지만(요청 250m 의 70%)
# 새 파드의 기동 · 데움이 100 단계(2분)보다 늦다 — 그래서 미리 늘려 같은 조건으로 잰다. HPA 가 있으면 최소값을 함께 올린다(그대로 두면
# 부하가 없는 동안 HPA 가 다시 줄인다). 늘리기 전에 스케줄 가능한 노드(NoSchedule 테인트 · cordon 제외)의 요청 여유를 센다.
# [JA] 計測の間だけレプリカを先に増やす。HPA の最小値も上げる。増やす前にノードの requests の余裕を数える。本番クラスタでは拒否する。
REPLICA_DEFAULT_SVCS="catalog-service gateway-server"
hpa_manifest() {   # hpa_manifest <deploy> → "최소 최대" (k8s/70 에 없으면 빈 값)
    python3 -I - "$HERE/../k8s/70-autoscaling.yaml" "$1" <<'PY'
import re, sys
for doc in open(sys.argv[1], encoding="utf-8").read().split("\n---"):
    if "kind: HorizontalPodAutoscaler" in doc and re.search(r"^\s+name:\s*" + re.escape(sys.argv[2]) + r"\s*$", doc, re.M):
        lo = re.search(r"minReplicas:\s*(\d+)", doc); hi = re.search(r"maxReplicas:\s*(\d+)", doc)
        if lo and hi:
            print(lo.group(1), hi.group(1))
        break
PY
}
overlay_replicas() {   # overlay_replicas <deploy> → demo 오버레이의 count (없으면 1)
    local n
    n="$(grep -oE "\{ *name: *$1, *count: *[0-9]+ *\}" "$HERE/../k8s-overlays/lab/kustomization.yaml" 2>/dev/null | grep -oE '[0-9]+ *\}$' | tr -dc '0-9')"
    echo "${n:-1}"
}
cmd_replicas() {
    lab_cluster_or_die "레플리카"
    local action="${2:-status}" n svcs d
    case "$action" in
        load)    n="${3:-3}"; shift $(( $# < 3 ? $# : 3 )); svcs="${*:-$REPLICA_DEFAULT_SVCS}" ;;
        default) shift $(( $# < 2 ? $# : 2 )); svcs="${*:-$REPLICA_DEFAULT_SVCS}" ;;
        status)  svcs="$REPLICA_DEFAULT_SVCS" ;;
        *) usage ;;
    esac
    for d in $svcs; do
        [[ "$d" =~ ^[a-z]+(-[a-z]+)*$ ]] || die "서비스 이름이 이상하다: $d"
        kubectl -n "$APP_NS" get deploy "$d" >/dev/null 2>&1 || die "그런 Deployment 가 없다: $d"
    done
    if [ "$action" = "status" ]; then
        for d in $svcs; do
            echo "$d 레플리카 $(kubectl -n "$APP_NS" get deploy "$d" -o jsonpath='{.spec.replicas}') · HPA(최소 최대) $(kubectl -n "$APP_NS" get hpa "$d" -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}' 2>/dev/null || echo 없음)"
        done
        return 0
    fi
    if [ "$action" = "default" ]; then
        for d in $svcs; do
            local mm
            mm="$(hpa_manifest "$d")"
            if [ -n "$mm" ] && kubectl -n "$APP_NS" get hpa "$d" >/dev/null 2>&1; then
                info "$d HPA → 최소 ${mm% *} · 최대 ${mm#* }(k8s/70) — 부하가 없으면 HPA 가 몇 분 안에 줄인다"
                kubectl -n "$APP_NS" patch hpa "$d" --type merge -p "{\"spec\":{\"minReplicas\":${mm% *},\"maxReplicas\":${mm#* }}}" >/dev/null
            else
                info "$d 레플리카 → $(overlay_replicas "$d")(demo 오버레이)"
                kubectl -n "$APP_NS" scale "deploy/$d" --replicas="$(overlay_replicas "$d")" >/dev/null
            fi
        done
        return 0
    fi
    [[ "$n" =~ ^[1-9]$ ]] || die "레플리카 수는 1 ~ 9"

    # 예산 — 늘릴 파드의 요청(CPU · 메모리) ≤ 스케줄 가능한 노드의 할당 가능 − 이미 잡힌 요청
    local need
    need="$(for d in $svcs; do
        printf '%s %s %s\n' "$(kubectl -n "$APP_NS" get deploy "$d" -o jsonpath='{.spec.replicas}')" \
            "$(kubectl -n "$APP_NS" get deploy "$d" -o jsonpath='{.spec.template.spec.containers[0].resources.requests.cpu}')" \
            "$(kubectl -n "$APP_NS" get deploy "$d" -o jsonpath='{.spec.template.spec.containers[0].resources.requests.memory}')"
    done)"
    kubectl get nodes,pods -A -o json 2>/dev/null | python3 -I -c '
import json, sys
n, need = int(sys.argv[1]), [l.split() for l in sys.argv[2].strip().splitlines()]
def cpu(v):
    v = str(v or "0"); return float(v[:-1]) if v.endswith("m") else float(v) * 1000
def mem(v):
    v = str(v or "0")
    for u, k in (("Ki", 1 / 1024), ("Mi", 1), ("Gi", 1024), ("K", 1 / 1000), ("M", 1), ("G", 1000)):
        if v.endswith(u): return float(v[: -len(u)]) * k
    return float(v) / (1024 * 1024)
items = json.load(sys.stdin)["items"]
nodes = {}
for o in items:
    if o.get("kind") != "Node": continue
    spec = o.get("spec", {})
    if spec.get("unschedulable") or any(t.get("effect") in ("NoSchedule", "NoExecute") for t in spec.get("taints") or []): continue
    a = o["status"]["allocatable"]; nodes[o["metadata"]["name"]] = [cpu(a.get("cpu")), mem(a.get("memory"))]
for o in items:
    if o.get("kind") != "Pod" or o.get("spec", {}).get("nodeName") not in nodes: continue
    if o.get("status", {}).get("phase") in ("Succeeded", "Failed"): continue
    for c in o["spec"].get("containers", []):
        r = (c.get("resources") or {}).get("requests") or {}
        nodes[o["spec"]["nodeName"]][0] -= cpu(r.get("cpu")); nodes[o["spec"]["nodeName"]][1] -= mem(r.get("memory"))
free_c = sum(v[0] for v in nodes.values()); free_m = sum(v[1] for v in nodes.values())
add_c = sum(max(0, n - int(cur)) * cpu(c) for cur, c, m in need); add_m = sum(max(0, n - int(cur)) * mem(m) for cur, c, m in need)
print(f">>> 예산: 스케줄 가능한 노드 {len(nodes)}대의 요청 여유 CPU {free_c:.0f}m · 메모리 {free_m:.0f}Mi / 더할 요청 CPU {add_c:.0f}m · 메모리 {add_m:.0f}Mi")
sys.exit(0 if nodes and add_c <= free_c and add_m <= free_m else 3)
' "$n" "$need" || die "노드의 요청 여유가 모자라거나 읽지 못했다 — 늘린 파드가 Pending 으로 남는다(kubectl describe node 의 Allocated resources)"
    kubectl top nodes 2>/dev/null | sed 's/^/    /' || true
    echo "    (요청 여유와 별개로 노드의 실제 CPU 가 이미 높으면 늘려도 같은 코어를 나눠 쓴다 — 위 top 을 함께 본다)"

    for d in $svcs; do
        local mm max
        mm="$(kubectl -n "$APP_NS" get hpa "$d" -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}' 2>/dev/null || true)"
        if [ -n "$mm" ]; then
            max="${mm#* }"; [ "$n" -gt "$max" ] && max="$n"
            info "$d HPA 최소 ${mm% *} → $n (최대 $max) · 레플리카 → $n ($PUBLIC_URL) — 끝나면 ./ops/53-load-k6.sh replicas default"
            kubectl -n "$APP_NS" patch hpa "$d" --type merge -p "{\"spec\":{\"minReplicas\":$n,\"maxReplicas\":$max}}" >/dev/null
        else
            info "$d 레플리카 → $n (HPA 없음)"
        fi
        kubectl -n "$APP_NS" scale "deploy/$d" --replicas="$n" >/dev/null
    done
    for d in $svcs; do kubectl -n "$APP_NS" rollout status "deploy/$d" --timeout=600s; done
    info "새 파드는 처음 1분이 데움이다(JIT · 캐시) — smoke 한 번 뒤에 잰다"
}
if [ "$SCENARIO" = "replicas" ]; then
    cmd_replicas "$@"
    exit 0
fi

# ── 결과 모아 보기 ───────────────────────────────────────────────────────
#
# 회차마다 summary.txt(k6 화면 그대로 — 진행 막대까지 길다) · meta.txt 를 따로 열지 않아도 되게 summary.json · meta.txt 에서
# 기록에 쓰는 값만 뽑는다(docs/83 §5). 엔드포인트별 응답 시간은 임계값을 건 이름만 summary.json 에 있다(k6 --summary-export).
# [JA] 結果をまとめて見る — summary.json と meta.txt から記録に使う値だけを出す。
cmd_report() {
    command -v python3 >/dev/null 2>&1 || die "python3 가 필요하다"
    [ -d "$OUT" ] || die "결과 폴더가 없다: $OUT (OUT=<폴더> 로 바꿀 수 있다)"
    shift
    python3 -I - "$OUT" "$@" <<'PY'
import json, os, re, sys, unicodedata

out, picks = sys.argv[1], sys.argv[2:]
# 이름은 <시나리오>-<UTC 시각> — 시각 순으로(이름순이면 "가장 최근" 이 시나리오 이름에 따라 바뀐다)
runs = sorted((d for d in os.listdir(out)
               if os.path.isdir(os.path.join(out, d)) and os.path.exists(os.path.join(out, d, "meta.txt"))),
              key=lambda d: (d.rsplit("-", 1)[-1], d))
if not runs:
    sys.exit(f"{out} 에 회차가 없다")

def meta(d):
    m, extra = {}, []
    with open(os.path.join(out, d, "meta.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith(("deploy ", "node ", "hpa ")):
                extra.append(line)
                continue
            if line.startswith(("gateway_limits=", "db_pool=", "replicas_")):   # 값 안에 = 이 있다(이름=값 여럿)
                k, v = line.split("=", 1)
                m[k] = v.strip()
                continue
            for k, v in re.findall(r"(\w+)=(.*?)(?= \w+=|$)", line):
                m[k] = v.strip()
    return m, extra

def summary(d):
    p = os.path.join(out, d, "summary.json")
    if not os.path.exists(p):
        return {}
    with open(p, encoding="utf-8") as f:
        return json.load(f).get("metrics", {})

def width(t):
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in t)

def L(t, n):   # 한글은 두 칸 — 표가 어긋나지 않게
    t = str(t)
    return t + " " * max(0, n - width(t))

def R(t, n):
    t = str(t)
    return " " * max(0, n - width(t)) + t

def num(x, digits=0):
    return "-" if x is None else f"{x:,.{digits}f}"

def pct(x):
    return "-" if x is None else f"{x * 100:.2f}%"

KEY = ("catalog-service", "gateway-server")   # 측정에서 늘리는 둘 — 늘 보인다

def counts(v):
    return dict(x.split("=", 1) for x in (v or "").split() if "=" in x)

def replicas(m, extra):
    """서비스 → (시작, 최대, 끝). Prometheus(replicas_*) 가 없으면 kubectl 의 deploy 줄(시작만)."""
    st, mx, en = (counts(m.get(k)) for k in ("replicas_start", "replicas_max", "replicas_end"))
    if st or mx or en:
        return "Prometheus", {s: (st.get(s, "?"), mx.get(s, "?"), en.get(s, "?")) for s in sorted({*st, *mx, *en})}
    dep = {}
    for line in extra:
        mm = re.match(r"deploy (\S+) replicas=(\d+)", line)
        if mm:
            dep[mm.group(1)] = (mm.group(2),)
    return ("kubectl", dep) if dep else (None, {})

def key_max(m, extra):   # 한 줄 표의 "catalog·gateway" 최대(없으면 -)
    _, r = replicas(m, extra)
    return "·".join(r[k][1 if len(r[k]) > 1 else 0] if k in r else "?" for k in KEY) if r else "-"

def verdict(m):
    return {"0": "통과", "99": "넘음"}.get(m.get("exit"), f"exit={m.get('exit', '?')}")

def overview():
    print(" ".join([L("회차", 34), L("커밋", 9), R("도착률", 6), R("상품", 10), R("요청/초", 8), R("실패", 7),
                    R("p95", 7), R("p99", 7), R("레플리카", 8)]) + "  임계값")
    for d in runs:
        m, extra = meta(d)
        s = summary(d)
        dur = s.get("http_req_duration", {})
        print(" ".join([L(d, 34), L(m.get("git", "?"), 9), R(m.get("rate", "?"), 6), R(m.get("products", "?")[:10], 10),
                        R(num(s.get("http_reqs", {}).get("rate"), 1), 8), R(pct(s.get("http_req_failed", {}).get("value")), 7),
                        R(num(dur.get("p(95)")), 7), R(num(dur.get("p(99)")), 7), R(key_max(m, extra), 8)]) + f"  {verdict(m)}")

def detail(d):
    m, extra = meta(d)
    s = summary(d)
    print(f"\n── {d} " + "─" * max(4, 60 - len(d)))
    print(f"  커밋 {m.get('git', '?')} · 도착률 {m.get('rate', '?')} · 단계 {m.get('stages', '?')} · 사용자 {m.get('users', '?')}"
          f" · 상품 {m.get('products', '?')} · 임계값 {verdict(m)}")
    # 🐛 loadgen 에는 kubectl 이 없어 이 줄이 meta.txt 에 없다 — 없는 것을 "기본값" 으로 읽으면 안 된다
    if "db_pool" in m:
        print("  DB 커넥션 풀: " + (m["db_pool"] or "모두 기본값(5)"))
    if "gateway_limits" not in m:
        print("  게이트웨이 유입 제한: 기록 없음(이 기계에 kubectl 이 없다 — 429 가 0 이면 올라가 있었다)")
    else:
        print(f"  게이트웨이 유입 제한: {m['gateway_limits'] or '기본값(20 · 40)'}")
    src, r = replicas(m, extra)
    if src == "Prometheus":
        shown = [s for s, v in r.items() if s in KEY or any(x != "1" for x in v)]
        rest = len(r) - len(shown)
        print("  레플리카(시작 → 측정 중 최대 → 끝): " + " · ".join(f"{s} {' → '.join(r[s])}" for s in shown)
              + (f" · 나머지 {rest}개는 1" if rest else ""))
    elif src == "kubectl":
        shown = [s for s, v in r.items() if s in KEY or v[0] != "1"]
        print("  레플리카(시작, kubectl — 측정 중 HPA 가 늘린 것은 모른다): " + " · ".join(f"{s} {r[s][0]}" for s in shown))
    for k, v in m.items():
        if k == "replicas_note":
            print(f"  레플리카: {v}")
    if src is None and "replicas_note" not in m:
        print("  레플리카: 기록 없음(PROM=http://<obs-vm>:9090 을 주면 Prometheus 에서 읽는다)")
    if not s:
        print("  summary.json 이 없다 — k6 가 끝까지 돌지 못했다(summary.txt 를 본다)")
        return
    c = lambda k, f="count": s.get(k, {}).get(f)
    print(f"  요청 {num(c('http_reqs'))} (초당 {num(c('http_reqs', 'rate'), 1)}) · 실패 {pct(c('http_req_failed', 'value'))}"
          f" · 검사 {pct(c('checks', 'value'))} · 429 {num(c('rate_limited') or 0)} · HTML {num(c('not_json') or 0)}"
          f" · 못 보낸 반복 {num(c('dropped_iterations') or 0)} · 최대 VU {num(c('vus_max', 'value'))}")
    e = {"5xx": c("errors_5xx") or 0, "4xx": c("errors_4xx") or 0, "응답 없음": c("errors_no_response") or 0}
    print("  오류(429 제외): " + " · ".join(f"{k} {num(v)}" for k, v in e.items()))
    per = []
    for k, v in sorted(s.items()):
        mm = re.match(r"errors_(5xx|4xx)\{name:(.+)\}$", k)
        if mm and (v.get("count") or 0) > 0:
            per.append(f"{mm.group(2)} {mm.group(1)} {num(v['count'])}")
    if per:
        print("    엔드포인트별: " + " · ".join(per))
    rows = [("전체", s.get("http_req_duration"))]
    rows += sorted((k[len("http_req_duration{name:"):-1], v) for k, v in s.items() if k.startswith("http_req_duration{name:"))
    if "purchase_duration" in s:
        rows.append(("구매 한 번(purchase)", s["purchase_duration"]))
    print("  " + L("응답 시간(ms)", 22) + " " + " ".join(R(h, 8) for h in ("avg", "p50", "p95", "p99", "max")))
    for name, v in rows:
        if v and (v.get("max") or 0) > 0:   # 표본이 없는 이름(데이터 규모 모드 밖의 search_deep 등)은 0 뿐이다
            print("    " + L(name, 20) + " " + " ".join(R(num(v.get(k)), 8) for k in ("avg", "p(50)", "p(95)", "p(99)", "max")))
    for line in extra:
        if line.startswith("hpa "):
            print(f"  {line}")

def resolve(x):
    name = os.path.basename(os.path.normpath(x))
    if name not in runs:
        sys.exit(f"그런 회차가 없다: {x} ({out})")
    return name

if not picks:
    overview()
    detail(runs[-1])
elif picks == ["all"]:
    overview()
    for d in runs:
        detail(d)
else:
    for d in map(resolve, picks):
        detail(d)
PY
}
if [ "$SCENARIO" = "report" ]; then
    cmd_report "$@"
    exit 0
fi

# 운영에는 걸지 않는다 — 터널 · 인터넷 지연이 섞여 코드가 아니라 선을 재고, 실제 손님의 사이트를 느리게 한다.
# [JA] 本番には掛けない — トンネルとインターネットの遅延が混ざり、実際の利用者のサイトを遅くする。
host="$(printf '%s' "$GATEWAY" | sed -E 's#^[a-zA-Z]+://##; s#[/:].*$##' | tr 'A-Z' 'a-z')"
case "$host" in
    ysyproject.com|*.ysyproject.com) die "운영 주소($host)에는 부하를 걸지 않는다 — 실습 클러스터(GATEWAY=https://shop.lab)에서 잰다(docs/83)" ;;
    "") die "GATEWAY 에서 호스트를 읽지 못했다: $GATEWAY" ;;
esac
[[ "$GATEWAY" == https://* ]] || die "GATEWAY 는 https:// 여야 한다(게이트웨이는 TLS 만 받는다)"

command -v "$K6" >/dev/null 2>&1 || die "k6 가 없다 — 설치(root 없이, 판 고정):
    mkdir -p ~/.local/bin && curl -fsSL https://github.com/grafana/k6/releases/download/v0.54.0/k6-v0.54.0-linux-amd64.tar.gz \\
      | tar xz -C ~/.local/bin --strip-components=1 k6-v0.54.0-linux-amd64/k6 && export PATH=~/.local/bin:\$PATH
  (또는 K6=<경로>, 패키지는 https://grafana.com/docs/k6/latest/set-up/install-k6/)"
[ -f "$K6_DIR/$SCENARIO.js" ] || die "$K6_DIR/$SCENARIO.js 가 없다"
[[ "$USERS" =~ ^[1-9][0-9]*$ ]] || die "USERS 는 1 이상의 정수"
[[ "$ID_OFFSET" =~ ^[0-9]+$ ]] || die "ID_OFFSET 은 0 이상의 정수"

# 인증서 — 검증을 끄지 않는다. 데모 CA 가 있으면 그것을 믿게 한다(k6 는 Go — SSL_CERT_FILE 을 읽는다).
if [ -f "$CA_FILE" ]; then
    export SSL_CERT_FILE="$CA_FILE"
else
    warn "CA_FILE($CA_FILE)이 없다 — 시스템 신뢰 저장소로 검증한다(데모 CA 면 인증서 오류로 멈춘다)"
fi

# smoke 는 계정 하나로 한 번
[ "$SCENARIO" = "smoke" ] && USERS=1

# 데모 비밀번호 — order · smoke 만. 길이만 알린다.
if [ "$SCENARIO" = "order" ] || [ "$SCENARIO" = "smoke" ]; then
    [ -f "$PASSWORD_FILE" ] || die "$PASSWORD_FILE 이 없다 — 먼저 USERS=$USERS ./ops/48-bulk-users.sh load"
    DEMO_PASSWORD="$(cat "$PASSWORD_FILE")"
    [ -n "$DEMO_PASSWORD" ] || die "$PASSWORD_FILE 이 비어 있다"
    export DEMO_PASSWORD
    info "데모 계정 demo$(printf '%07d' "$ID_OFFSET") ~ demo$(printf '%07d' $((ID_OFFSET + USERS - 1))) · 비밀번호 ${#DEMO_PASSWORD}자"
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dir="$OUT/$SCENARIO-$stamp"
mkdir -p "$dir"

# 레플리카 — Prometheus 에서 읽는다. loadgen 에는 kubectl 이 없어 아래 deploy · hpa 줄이 비고, HPA 가 측정 중에 늘린 것은
# kubectl 로도 시작 시점만 보인다(2026-10-18: 0.17% 회차가 레플리카 2 인지 3 인지 CSV 를 다시 뽑아야 알았다). 파드 수 = 그 서비스의
# 살아 있는 스크레이프 대상 수(up == 1). 시작 · 측정 중 최대(15초 간격) · 끝을 남긴다.
# [JA] レプリカ数を Prometheus から読む — loadgen には kubectl がなく、HPA が計測中に増やした数も残らなかった。開始 · 計測中の最大 · 終了。
PROM="${PROM:-${PROM_RW:-}}"
PROM="${PROM%/api/v1/write}"
PROM_ENV="${PROM_ENV:-demo}"
[[ "$PROM_ENV" =~ ^[a-z0-9-]+$ ]] || die "PROM_ENV 는 영소문자 · 숫자 · - 만"
UP="count by (service) (up{environment=\"$PROM_ENV\"} == 1)"
prom_counts() {   # prom_counts <PromQL> → "서비스=수 …" (응답이 없거나 결과가 비면 실패)
    curl -fsS --max-time 10 -G "$PROM/api/v1/query" --data-urlencode "query=$1" 2>/dev/null | python3 -I -c '
import json, sys
r = json.load(sys.stdin)
rows = sorted((x["metric"].get("service", "?"), round(float(x["value"][1]))) for x in r["data"]["result"]) if r.get("status") == "success" else []
if not rows:
    sys.exit(1)
print(" ".join("%s=%d" % row for row in rows))'
}
replicas_line() {   # replicas_line <키> <PromQL> → meta.txt 한 줄
    local v
    if v="$(prom_counts "$2")"; then
        echo "$1=$v"
    else
        warn "레플리카를 Prometheus($PROM)에서 읽지 못했다 — up{environment=\"$PROM_ENV\"} 가 있는지"
        echo "replicas_note=Prometheus($PROM)에서 읽지 못했다($1)"
    fi
}

# 그때의 조건 — 전후 비교의 근거. 비밀은 없다(이미지 판 · 레플리카 · 노드).
{
    echo "scenario=$SCENARIO"
    echo "gateway=$GATEWAY"
    echo "started_utc=$stamp"
    echo "git=$(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo '?')"
    echo "users=$USERS id_offset=$ID_OFFSET rate=${RATE:-default} stages=${STAGES:-default} duration=${DURATION:-default}"
    echo "products=${PRODUCTS:-홈 상품만} product_id_offset=${PRODUCT_ID_OFFSET:-1000000} deep_pages=${DEEP_PAGES:-default} name_search=${NAME_SEARCH:-default}"
    if command -v kubectl >/dev/null 2>&1 && kubectl -n "$APP_NS" get deploy >/dev/null 2>&1; then
        echo "context=$(kubectl config current-context 2>/dev/null || echo '?')"
        kubectl -n "$APP_NS" get deploy -o jsonpath='{range .items[*]}deploy {.metadata.name} replicas={.spec.replicas} image={.spec.template.spec.containers[0].image}{"\n"}{end}' 2>/dev/null || true
        kubectl get nodes --no-headers 2>/dev/null | awk '{print "node", $1, $2}' || true
        echo "gateway_limits=$(kubectl -n "$APP_NS" get deploy gateway-server -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value} {end}' 2>/dev/null \
            | tr ' ' '\n' | grep '^GATEWAY_RATE_LIMIT_' | tr '\n' ' ' || true)"
        echo "db_pool=$(kubectl -n "$APP_NS" get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"="}{.spec.template.spec.containers[0].env[?(@.name=="DB_POOL_SIZE")].value}{" "}{end}' 2>/dev/null \
            | tr ' ' '\n' | grep -E '=[0-9]+$' | tr '\n' ' ' || true)"
        kubectl -n "$APP_NS" get hpa --no-headers 2>/dev/null | awk '{print "hpa", $1, $3, $4, $5, $6}' || true
    else
        echo "kubectl=없음(클러스터 정보 없이)"
    fi
    [ -z "$PROM" ] || replicas_line replicas_start "$UP"
} > "$dir/meta.txt"

args=(run --summary-export "$dir/summary.json"
      -e GATEWAY="$GATEWAY" -e USERS="$USERS" -e ID_OFFSET="$ID_OFFSET" -e SCENARIO="$SCENARIO")
[ -n "${RATE:-}" ] && args+=(-e RATE="$RATE")
[ -n "${STAGES:-}" ] && args+=(-e STAGES="$STAGES")
[ -n "${DURATION:-}" ] && args+=(-e DURATION="$DURATION")
[ "${ALLOW_RATE_LIMIT:-}" = "1" ] && args+=(-e ALLOW_RATE_LIMIT=1)
for v in PRODUCTS PRODUCT_ID_OFFSET DEEP_PAGES NAME_SEARCH; do
    if [ -n "${!v:-}" ]; then
        [[ "${!v}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "$v 는 숫자"
        args+=(-e "$v=${!v}")
    fi
done
if [ -n "${PROM_RW:-}" ]; then
    export K6_PROMETHEUS_RW_SERVER_URL="$PROM_RW"
    export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(50),p(95),p(99),max}"
    args+=(-o experimental-prometheus-rw --tag "testid=$SCENARIO-$stamp")
fi
args+=("$K6_DIR/$SCENARIO.js")

info "시나리오 $SCENARIO → $GATEWAY  (결과: $dir)"
# 종료 코드는 tee 가 아니라 k6 의 것 — 임계값(thresholds)을 넘으면 99
t0="$(date +%s)"
set +e
"$K6" "${args[@]}" 2>&1 | tee "$dir/summary.txt"
rc="${PIPESTATUS[0]}"
set -e
if [ -n "$PROM" ]; then
    secs=$(( $(date +%s) - t0 + 30 ))   # 마지막 스크레이프까지
    [ "$secs" -ge 60 ] || secs=60
    { replicas_line replicas_max "max_over_time(($UP)[${secs}s:15s])"
      replicas_line replicas_end "$UP"; } >> "$dir/meta.txt"
fi
echo "exit=$rc" >> "$dir/meta.txt"
case "$rc" in
    0)  info "임계값 통과 — $dir" ;;
    99) warn "임계값을 넘었다(오류율 · p95) — 그 단계의 서버 지표를 본다(docs/83 §4). 결과: $dir" ;;
    *)  warn "k6 가 $rc 로 끝났다 — $dir/summary.txt" ;;
esac
exit "$rc"
