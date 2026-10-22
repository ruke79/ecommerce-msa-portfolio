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
#   PRODUCTS  PRODUCT_ID_OFFSET=1000000  DEEP_PAGES=50  NAME_SEARCH=0.2   # browse · spike 의 데이터 규모 모드(docs/83 §2.1)
#   OUT=./load-results              # 결과 폴더 — <시나리오>-<UTC 시각>/ 아래 summary.json · summary.txt · meta.txt
#   PROM_RW=http://<obs-vm>:9090/api/v1/write   # 주면 k6 지표를 Prometheus 로도(그라파나에서 서버 지표와 같은 시간축)
#   K6=k6                           # k6 실행 파일(https://grafana.com/docs/k6/latest/set-up/install-k6/)
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

usage() { sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

SCENARIO="${1:-}"
case "$SCENARIO" in
    browse|order|spike|smoke|limits) ;;
    *) usage ;;
esac

# ── 유입 제한 — 측정하는 동안만 ──────────────────────────────────────────
#
# 비로그인 요청(카탈로그 읽기)은 버킷 키가 IP 다. 데모에는 Cloudflare 가 없어 모든 바깥 요청이 Traefik 파드 IP 하나로 보이고,
# 그 버킷(초당 20 · 버스트 40)을 모든 손님이 나눠 쓴다 — 그대로 재면 결과는 "초당 20 에서 429" 뿐이다. 그래서 측정하는 동안만
# 게이트웨이의 기본 제한을 올린다(GATEWAY_RATE_LIMIT_*, gateway application-local.yml — 기본값은 그대로). 로그인 제한(초당 1)은
# 바꿀 수 없다. 운영 클러스터에는 하지 않는다: 이 클러스터의 APP_PUBLIC_URL 이 운영 주소면 거절한다.
# [JA] 計測の間だけゲートウェイの既定の流入制限を上げる。本番クラスタでは拒否する。ログイン制限は変えられない。
cmd_limits() {
    command -v kubectl >/dev/null 2>&1 || die "kubectl 이 필요하다"
    local url
    url="$(kubectl -n "$APP_NS" get configmap ecommerce-config -o jsonpath='{.data.APP_PUBLIC_URL}' 2>/dev/null)" \
        || die "ecommerce-config 를 읽지 못했다 — kubectl 컨텍스트가 실습 클러스터인지"
    case "$url" in
        *ysyproject.com*) die "운영 클러스터다(APP_PUBLIC_URL=$url) — 유입 제한은 바꾸지 않는다" ;;
        "") die "APP_PUBLIC_URL 이 비어 있다 — 실습 클러스터(데모 오버레이)인지 확인할 수 없다" ;;
    esac
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
        kubectl -n "$APP_NS" get hpa --no-headers 2>/dev/null | awk '{print "hpa", $1, $3, $4, $5, $6}' || true
    else
        echo "kubectl=없음(클러스터 정보 없이)"
    fi
} > "$dir/meta.txt"

args=(run --summary-export "$dir/summary.json"
      -e GATEWAY="$GATEWAY" -e USERS="$USERS" -e ID_OFFSET="$ID_OFFSET" -e SCENARIO="$SCENARIO")
[ -n "${RATE:-}" ] && args+=(-e RATE="$RATE")
[ -n "${STAGES:-}" ] && args+=(-e STAGES="$STAGES")
[ -n "${DURATION:-}" ] && args+=(-e DURATION="$DURATION")
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
set +e
"$K6" "${args[@]}" 2>&1 | tee "$dir/summary.txt"
rc="${PIPESTATUS[0]}"
set -e
echo "exit=$rc" >> "$dir/meta.txt"
case "$rc" in
    0)  info "임계값 통과 — $dir" ;;
    99) warn "임계값을 넘었다(오류율 · p95) — 그 단계의 서버 지표를 본다(docs/83 §4). 결과: $dir" ;;
    *)  warn "k6 가 $rc 로 끝났다 — $dir/summary.txt" ;;
esac
exit "$rc"
