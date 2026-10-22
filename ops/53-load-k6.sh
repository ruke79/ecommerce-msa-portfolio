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
#   ./ops/53-load-k6.sh mixed                  # browse + 로그인 사용자의 리뷰 쓰기(초당 REVIEW_RATE) — 리뷰마다 catalog 의 별점 갱신(docs/83 §2.2)
#   ./ops/53-load-k6.sh contention             # 재고 경합: USERS 명이 재고 STOCK 개인 한 상품을 같은 순간에 주문 — 받아진 주문 ≤ STOCK(docs/83 §2.3)
#
#   ./ops/53-load-k6.sh stock set <수> [상품 id]   # 경합할 상품의 재고를 맞춘다(없으면 SKU 하나인 상품을 고른다) — 실습 클러스터만,
#                                              # 원래 수량을 ~/.ecommerce-msa/stock/ 에 남기고 contention 의 실행 줄을 알려 준다
#   ./ops/53-load-k6.sh stock show <상품 id>    # 사이즈마다 수량 · 살아 있는 예약
#   ./ops/53-load-k6.sh stock restore <상품 id> # 원래 수량으로 되돌린다. 측정이 끝나면 꼭
#
#   ./ops/53-load-k6.sh search es              # 이름 검색을 Elasticsearch 로 100%(catalog 재시작) — 실습 클러스터만, ES 색인 문서 수를 센다
#   ./ops/53-load-k6.sh search default         # 되돌린다(SEARCH_ENGINE = ConfigMap 값, 플래그 파일 다시). 측정이 끝나면 꼭
#   ./ops/53-load-k6.sh search status          # 지금 엔진 · ES 상태 · 색인 문서 수
#
#   ./ops/53-load-k6.sh reviews open           # 리뷰의 '받은 상품만' 확인을 잠깐 끈다(user-service 재시작) — 실습 클러스터만, mixed 앞에
#   ./ops/53-load-k6.sh reviews default        # 되돌린다(받은 상품만). 측정이 끝나면 꼭
#   ./ops/53-load-k6.sh reviews status         # 지금 값
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
#   ./ops/53-load-k6.sh replicas max <수> [서비스…]   # HPA 최대만 올린다(최소 · 지금 레플리카 그대로 — 늘리는 것은 HPA) — spike 에서
#                                              # HPA 가 따라가는 동안을 잴 때. 요청 여유는 최대까지 늘었을 때로 센다. 되돌리기는 replicas default
#   ./ops/53-load-k6.sh replicas status        # 지금 레플리카 · HPA
#
#   ./ops/53-load-k6.sh logs                   # 게이트웨이 파드의 로그를 지금 남긴다(~/.ecommerce-msa/gateway-logs, 폴백 예외별 수) — limits · replicas 가 바꾸기 전에도
#
#   ./ops/53-load-k6.sh report                 # 결과 모아 보기: 모든 회차 한 줄씩 + 가장 최근 회차의 요약
#   ./ops/53-load-k6.sh report <폴더|이름>...   # 그 회차들의 요약(엔드포인트별 응답 시간 · 실패율 · 조건)
#   ./ops/53-load-k6.sh report all             # 모든 회차의 요약
#   ./ops/53-load-k6.sh report merge <폴더>…    # 부하 VM 여러 대(SPLIT)의 같은 회차를 합쳐 본다(다른 VM 의 폴더를 이 기계로 복사해 와서)
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
#   SPREAD=1  NAME_PAGES=50  FILTER_SEARCH=0   # 데이터 규모 모드에서 검색 조합을 넓혀 캐시 밖을 잰다(깊은 쪽 기본 1000) · 필터 검색 비율
#   REVIEW_RATE=1                   # mixed 의 리뷰 쓰기(초당) — 계정은 USERS · ID_OFFSET 에서(리뷰 VU 수만큼 로그인)
#   CONTENTION_PRODUCT  STOCK  ROUNDS=3  HOLD_SECONDS=20  ROUND_GAP_SECONDS=10   # contention(stock set 이 알려 준다, USERS 기본 50)
#   RELEASE_HELD=1                  # stock set 이 그 상품의 살아 있는 예약도 푼다(중간에 끊긴 회차의 입금 대기)
#   CLIENT_IPS=0                    # 손님 IP 흉내(docs/83 §2.6): VU 마다 CF-Connecting-IP(198.18.0.0/15) — 유입 제한이 손님마다(limits load 없이)
#   ENGINE=es                       # 회차 기록에 검색 엔진을 적는다(부하 VM 에는 kubectl 이 없다 — search es 를 했으면)
#   SPLIT=i/n  START_AT=<UTC 시각>   # 부하 VM n 대 중 i 번째: RATE(전체)를 n 으로 나누고 계정 · 손님 IP 를 겹치지 않게, START_AT 에 함께 시작
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

usage() { sed -n '2,81p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

SCENARIO="${1:-}"
case "$SCENARIO" in
    browse|order|spike|smoke|mixed|contention|limits|pool|replicas|reviews|stock|search|logs|report) ;;
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

# ── 게이트웨이 로그 — 파드가 바뀌기 전에 ─────────────────────────────────
#
# 🐛 2026-10-18: 캐시 후 회차의 503 10,281건이 무엇인지(벌크헤드 · 회로 차단기 · 시간 초과) 폴백 로그로 세려 했는데 0 이었다 — 측정이 끝나고
# 부른 `limits default` 의 롤링이 측정 때의 게이트웨이 파드를 지워 로그가 함께 사라졌다(지표로 벌크헤드임은 알았지만 예외 종류 · 메시지는 없었다).
# 그래서 게이트웨이 파드를 바꾸는 명령(limits load · default, replicas 의 게이트웨이)은 바꾸기 **전에** 파드마다 로그 전체를 남긴다 — `logs` 로 언제든.
# 폴백 로그(FallbackController: "Fallback triggered. Exception: <클래스>: …")를 예외별로 센 요약도 함께. 로그에는 토큰 · 이메일이 없지만(보안 경로 로그 규칙)
# 운영자 기계의 0700 폴더에만 둔다.
# [JA] ゲートウェイの Pod を入れ替える前にログを残す — 以前は limits default のローリングで計測時の Pod とログが消え、503 の内訳を数えられなかった。
GATEWAY_LOG_DIR="${GATEWAY_LOG_DIR:-$HOME/.ecommerce-msa/gateway-logs}"
save_gateway_logs() {   # save_gateway_logs <이유>
    local pods p dir f old
    pods="$(kubectl -n "$APP_NS" get pods -l app=gateway-server -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)"
    if [ -z "$pods" ]; then
        warn "게이트웨이 파드가 없다 — 로그를 남기지 않는다"
        return 0
    fi
    dir="$GATEWAY_LOG_DIR/$(date -u +%Y%m%dT%H%M%SZ)-$1"
    old="$(umask)"; umask 077
    mkdir -p "$dir"
    for p in $pods; do
        for f in "" previous; do   # 재시작한 컨테이너는 앞 로그도
            kubectl -n "$APP_NS" logs "$p" ${f:+--previous} --tail=-1 --timestamps > "$dir/$p${f:+.$f}.log" 2>/dev/null || true
            if [ -s "$dir/$p${f:+.$f}.log" ]; then gzip -f "$dir/$p${f:+.$f}.log"; else rm -f "$dir/$p${f:+.$f}.log"; fi
        done
    done
    {
        echo "reason=$1 saved_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) pods=$(echo $pods)"
        for f in "$dir"/*.log.gz; do
            [ -e "$f" ] || continue
            echo "== $(basename "$f" .log.gz) 줄 $(gzip -dc "$f" | wc -l | tr -d ' ') · 폴백 $(gzip -dc "$f" | grep -c 'Fallback triggered' || true)"
            gzip -dc "$f" | grep -o 'Fallback triggered\. Exception: [A-Za-z0-9_.$]*' | sed 's/.*Exception: //' | sort | uniq -c | sort -rn | sed 's/^/   /' || true
        done
        echo "== 모든 파드 — 폴백 예외별"
        for f in "$dir"/*.log.gz; do [ -e "$f" ] && gzip -dc "$f"; done | grep -o 'Fallback triggered\. Exception: [A-Za-z0-9_.$]*' \
            | sed 's/.*Exception: //' | sort | uniq -c | sort -rn | sed 's/^/   /' || true
    } > "$dir/summary.txt"
    umask "$old"
    info "게이트웨이 로그를 남겼다($1, 파드 $(echo $pods | wc -w | tr -d ' ')개): $dir"
    sed -n '/^== 모든 파드/,$p' "$dir/summary.txt"
}

cmd_limits() {
    lab_cluster_or_die "유입 제한"
    local url="$PUBLIC_URL"
    case "${2:-status}" in
        load|default) save_gateway_logs "limits-${2}" ;;
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

# ── 리뷰 자격 — 측정하는 동안만 ─────────────────────────────────────────
#
# 리뷰는 받은 상품만 쓸 수 있다(review.require-purchase, 기본 켬 — docs/57 §13). 데모 계정은 받은 상품이 없어 mixed 의 리뷰가 모두 403 이다.
# 측정하는 동안만 끈다(REVIEW_REQUIRE_PURCHASE=false — 시연 환경을 위해 둔 스위치). user-service 가 다시 뜬다. 운영 클러스터는 거절한다.
# [JA] 計測の間だけレビューの「受け取った商品のみ」確認を切る。user-service が再起動する。本番クラスタでは拒否する。
cmd_reviews() {
    lab_cluster_or_die "리뷰 자격"
    case "${2:-status}" in
        open)
            info "리뷰 자격 확인 끔 — 데모 계정이 아무 상품에나 리뷰를 쓴다($PUBLIC_URL). 끝나면 ./ops/53-load-k6.sh reviews default"
            kubectl -n "$APP_NS" set env deploy/user-service REVIEW_REQUIRE_PURCHASE=false >/dev/null
            kubectl -n "$APP_NS" rollout status deploy/user-service --timeout=300s
            ;;
        default)
            info "리뷰 자격 확인 → 기본값(받은 상품만)"
            kubectl -n "$APP_NS" set env deploy/user-service REVIEW_REQUIRE_PURCHASE- >/dev/null
            kubectl -n "$APP_NS" rollout status deploy/user-service --timeout=300s
            ;;
        status)
            local cur
            cur="$(kubectl -n "$APP_NS" get deploy user-service -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
                | grep '^REVIEW_REQUIRE_PURCHASE=' || true)"
            if [ -n "$cur" ]; then echo "$cur"; else echo "기본값(받은 상품만)"; fi
            ;;
        *) usage ;;
    esac
}
if [ "$SCENARIO" = "reviews" ]; then
    cmd_reviews "$@"
    exit 0
fi

# ── 경합할 상품의 재고 — 측정하는 동안만 ──────────────────────────────────
#
# contention 은 재고가 작은 상품 하나에 손님을 몰아야 뜻이 있다(재고 100 에 손님 50 이면 경합이 아니다). 그 상품의 SKU 하나 · 모든 사이즈의
# 수량을 <수> 로 맞추고 원래 값을 운영자 기계(0700)에 남긴다 — stock restore 가 되돌린다. 운영 클러스터는 거절한다.
# 남은 수 = 수량 − 살아 있는 예약(docs/59) — 앞 회차가 중간에 끊겨 입금 대기가 남았으면 그만큼 적다(RELEASE_HELD=1 로 푼다).
# [JA] 競合させる商品の在庫を計測の間だけ合わせる。元の数量を残し stock restore で戻す。本番クラスタでは拒否する。
PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_USER="${PG_USER:-product}"
PG_DB="${PG_DB:-productdb}"
STOCK_DIR="${STOCK_DIR:-$HOME/.ecommerce-msa/stock}"
pg() {   # pg <SQL> — 값만(머리말 없이)
    kubectl -n "$PG_NS" exec -i "deploy/$PG_DEPLOY" -- psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -tA -c "$1"
}
stock_show() {   # stock_show <상품 id>
    pg "SELECT 'size ' || s.size_id || ' (' || coalesce(nullif(s.size, ''), '-') || ') 수량 ' || s.quantity
          || ' · 예약 ' || coalesce((SELECT sum(r.qty) FROM product.stock_reservation r WHERE r.product_id = k.product_id
               AND r.color_id = k.color_id AND r.size = s.size AND r.status = 'HELD' AND r.expires_at > now()), 0)
          FROM product.product_size s JOIN product.product_sku k ON k.skuproduct_id = s.skuproduct_id
         WHERE k.product_id = $1 ORDER BY s.size_id"
}
cmd_stock() {
    lab_cluster_or_die "재고"
    local n pid skus held saved
    case "${2:-}" in
        set)
            n="${3:-}"; pid="${4:-}"
            [[ "$n" =~ ^[1-9][0-9]{0,3}$ ]] || die "수는 1 ~ 9999"
            if [ -z "$pid" ]; then
                pid="$(pg "SELECT p.product_id FROM product.product p JOIN product.product_sku k ON k.product_id = p.product_id
                            WHERE p.status = 'ACTIVE' GROUP BY p.product_id HAVING count(*) = 1 ORDER BY p.product_id LIMIT 1")"
                [ -n "$pid" ] || die "SKU 하나인 상품이 없다 — 상품 id 를 직접"
            fi
            [[ "$pid" =~ ^[1-9][0-9]*$ ]] || die "상품 id 는 숫자"
            skus="$(pg "SELECT count(*) FROM product.product_sku WHERE product_id = $pid")"
            [ "$skus" = "1" ] || die "상품 $pid 의 SKU 가 ${skus:-0}개 — 하나인 상품만(담기 · 장바구니가 같은 SKU 를 고르는 것이 확실하다)"
            mkdir -p "$STOCK_DIR"; chmod 700 "$STOCK_DIR"
            saved="$STOCK_DIR/$pid.saved"
            if [ ! -f "$saved" ]; then   # 두 번 set 해도 처음 값을 지킨다
                pg "SELECT s.size_id || '=' || s.quantity FROM product.product_size s
                      JOIN product.product_sku k ON k.skuproduct_id = s.skuproduct_id WHERE k.product_id = $pid" > "$saved"
            fi
            pg "UPDATE product.product_size s SET quantity = $n FROM product.product_sku k
                 WHERE s.skuproduct_id = k.skuproduct_id AND k.product_id = $pid" >/dev/null
            held="$(pg "SELECT coalesce(sum(qty), 0) FROM product.stock_reservation
                         WHERE product_id = $pid AND status = 'HELD' AND expires_at > now()")"
            if [ "${held:-0}" != "0" ]; then
                if [ "${RELEASE_HELD:-0}" = "1" ]; then
                    pg "UPDATE product.stock_reservation SET status = 'RELEASED', updated_at = now()
                         WHERE product_id = $pid AND status = 'HELD'" >/dev/null
                    info "살아 있는 예약 ${held}개를 풀었다(RELEASE_HELD=1)"
                else
                    warn "살아 있는 예약이 ${held}개 — 남은 수는 $n − $held. 풀려면 RELEASE_HELD=1 로 다시"
                fi
            fi
            "$HERE/44-bulk-catalog.sh" flush-cache >/dev/null 2>&1 || warn "캐시를 비우지 못했다 — ./ops/44-bulk-catalog.sh flush-cache"
            info "상품 $pid 의 재고 → $n (원래 값: $saved)"
            stock_show "$pid"
            echo
            echo "  부하 VM 에서:  CONTENTION_PRODUCT=$pid STOCK=$n USERS=50 ./ops/53-load-k6.sh contention"
            echo "  끝나면:        ./ops/53-load-k6.sh stock restore $pid"
            ;;
        show)
            pid="${3:-}"; [[ "$pid" =~ ^[1-9][0-9]*$ ]] || die "상품 id 는 숫자"
            stock_show "$pid"
            ;;
        restore)
            pid="${3:-}"; [[ "$pid" =~ ^[1-9][0-9]*$ ]] || die "상품 id 는 숫자"
            saved="$STOCK_DIR/$pid.saved"
            [ -f "$saved" ] || die "$saved 가 없다 — stock set 을 하지 않았거나 이미 되돌렸다"
            local kv sid q
            while IFS= read -r kv; do
                sid="${kv%%=*}"; q="${kv#*=}"
                [[ "$sid" =~ ^[0-9]+$ && "$q" =~ ^-?[0-9]+$ ]] || die "$saved 의 줄이 이상하다: $kv"
                pg "UPDATE product.product_size SET quantity = $q WHERE size_id = $sid" >/dev/null
            done < "$saved"
            rm -f "$saved"
            "$HERE/44-bulk-catalog.sh" flush-cache >/dev/null 2>&1 || warn "캐시를 비우지 못했다 — ./ops/44-bulk-catalog.sh flush-cache"
            info "상품 $pid 의 재고를 되돌렸다"
            stock_show "$pid"
            ;;
        *) usage ;;
    esac
}
if [ "$SCENARIO" = "stock" ]; then
    cmd_stock "$@"
    exit 0
fi

# ── 검색 엔진 — 측정하는 동안만 ──────────────────────────────────────────
#
# 엔진은 둘이 정한다(SearchEngineSelector, docs/74): search.engine=es(ES 가 이 배포에 있는가 — SEARCH_ENGINE, ConfigMap)와
# 플래그 catalog.search-engine(있다면 검색어마다 ES 로 보낼 비율 — k8s/15 는 0%). 측정하는 동안만 catalog 에 SEARCH_ENGINE=es 를 주고
# 플래그 파일을 끈다(FEATURE_FLAGS_FILE 을 비우면 코드의 기본값 = ES 가 있으면 ES) — ConfigMap 은 건드리지 않는다(ops/28 --config-only 규칙).
# 되돌릴 때 FEATURE_FLAGS_FILE 은 지우지 않고 매니페스트 값(k8s/41)으로 다시 쓴다 — 매니페스트에 직접 적힌 값이라 지우면 플래그가 영영 꺼진다.
# ES 는 검색어가 있는 검색(name_search)에만 쓰인다 — 카테고리 목록 · 필터는 늘 PostgreSQL 이다. 운영 클러스터는 거절한다.
# [JA] 計測の間だけ名前検索を ES 100% にする。ConfigMap は触らない。戻すとき FEATURE_FLAGS_FILE は消さずマニフェストの値に戻す。
ES_NS="${ES_NS:-elasticsearch}"
FLAGS_FILE_DEFAULT="/app/flags/flags.json"   # k8s/41-catalog-service.yaml 의 FEATURE_FLAGS_FILE(가드 LoadK6ScriptTest)
es_count() {   # 별칭 product-alias 의 문서 수(없으면 빈 값) — ES 파드 안의 curl(데모는 보안을 끈 단일 노드, k8s/90)
    kubectl -n "$ES_NS" exec deploy/elasticsearch -- curl -s --max-time 10 "http://localhost:9200/product-alias/_count" 2>/dev/null \
        | python3 -I -c 'import json, sys
try:
    print(json.load(sys.stdin).get("count", ""))
except Exception:
    print("")' 2>/dev/null || true
}
es_ready() {
    kubectl -n "$ES_NS" get deploy elasticsearch -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true
}
cmd_search() {
    lab_cluster_or_die "검색 엔진"
    local ready count
    case "${2:-status}" in
        es)
            ready="$(es_ready)"
            [ "${ready:-0}" -ge 1 ] 2>/dev/null || die "Elasticsearch 가 떠 있지 않다($ES_NS/elasticsearch ready=${ready:-0}) — 데모 인프라의 ES(k8s/90 · 데모 인프라의 ES 덧씌우기)부터"
            info "이름 검색 → Elasticsearch 100%(catalog 재시작) — 끝나면 ./ops/53-load-k6.sh search default"
            kubectl -n "$APP_NS" set env deploy/catalog-service SEARCH_ENGINE=es FEATURE_FLAGS_FILE= >/dev/null
            kubectl -n "$APP_NS" rollout status deploy/catalog-service --timeout=600s
            count="$(es_count)"
            if [ -z "$count" ] || [ "$count" = "0" ]; then
                warn "색인(product-alias)이 비었다 — 관리자 계정으로 POST /catalog-service/api/products/sync 를 한 번(docs/13 §3, ops/03 의 1 ~ 4단계 · RUN_K8S_PATCH 는 쓰지 않는다). 비어 있으면 ES 검색이 0건이다"
            else
                info "색인 문서 수: $count — 상품 수와 견준다(PRODUCTS=… ./ops/44-bulk-catalog.sh status)"
            fi
            echo "  부하 VM 에서: ENGINE=es NAME_SEARCH=0.5 PRODUCTS=… ./ops/53-load-k6.sh browse   (ES 는 검색어가 있는 검색만)"
            ;;
        default)
            info "검색 엔진 → 기본값(SEARCH_ENGINE = ConfigMap, 플래그 파일 $FLAGS_FILE_DEFAULT)"
            kubectl -n "$APP_NS" set env deploy/catalog-service SEARCH_ENGINE- FEATURE_FLAGS_FILE="$FLAGS_FILE_DEFAULT" >/dev/null
            kubectl -n "$APP_NS" rollout status deploy/catalog-service --timeout=600s
            ;;
        status)
            kubectl -n "$APP_NS" get deploy catalog-service -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
                | grep -E '^(SEARCH_ENGINE|FEATURE_FLAGS_FILE)=' || true
            echo "elasticsearch ready=$(es_ready) · 색인 문서 수=$(es_count)"
            ;;
        *) usage ;;
    esac
}
if [ "$SCENARIO" = "search" ]; then
    cmd_search "$@"
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
        max)     n="${3:-}"; shift $(( $# < 3 ? $# : 3 )); svcs="${*:-$REPLICA_DEFAULT_SVCS}" ;;
        default) shift $(( $# < 2 ? $# : 2 )); svcs="${*:-$REPLICA_DEFAULT_SVCS}" ;;
        status)  svcs="$REPLICA_DEFAULT_SVCS" ;;
        *) usage ;;
    esac
    for d in $svcs; do
        [[ "$d" =~ ^[a-z]+(-[a-z]+)*$ ]] || die "서비스 이름이 이상하다: $d"
        kubectl -n "$APP_NS" get deploy "$d" >/dev/null 2>&1 || die "그런 Deployment 가 없다: $d"
    done
    # 줄이면 파드가 지워진다(default 는 HPA 가 몇 분 뒤에) — 게이트웨이면 그 전에 로그를 남긴다
    if [ "$action" != "status" ] && [ "$action" != "max" ] && [[ " $svcs " == *" gateway-server "* ]]; then
        save_gateway_logs "replicas-$action"
    fi
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
    if [ "$action" = "max" ]; then
        # 최대만 — HPA 가 없으면 늘릴 주체가 없고, 최소보다 작은 최대는 HPA 가 거절한다
        for d in $svcs; do
            local mm
            mm="$(kubectl -n "$APP_NS" get hpa "$d" -o jsonpath='{.spec.minReplicas} {.spec.maxReplicas}' 2>/dev/null || true)"
            [ -n "$mm" ] || die "$d 에 HPA 가 없다 — max 는 HPA 의 최대만 바꾼다(레플리카를 정하려면 replicas load)"
            [ "$n" -ge "${mm% *}" ] || die "$d 최대 $n 이 HPA 최소 ${mm% *} 보다 작다"
        done
    fi

    # 예산 — 늘릴 파드의 요청(CPU · 메모리) ≤ 스케줄 가능한 노드의 할당 가능 − 이미 잡힌 요청
    # (max 는 HPA 가 최대까지 늘렸을 때 — 그때 Pending 이면 HPA 가 따라가는 것이 아니라 노드가 막는 것을 잰다)
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

    if [ "$action" = "max" ]; then
        # [JA] HPA の最大だけ上げる(最小 · 今のレプリカはそのまま) — spike で HPA が追いつくまでを測る。
        for d in $svcs; do
            info "$d HPA 최대 → $n (최소 · 지금 레플리카 그대로 — 늘리는 것은 HPA, $PUBLIC_URL) — 끝나면 ./ops/53-load-k6.sh replicas default"
            kubectl -n "$APP_NS" patch hpa "$d" --type merge -p "{\"spec\":{\"maxReplicas\":$n}}" >/dev/null
        done
        return 0
    fi

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
if [ "$SCENARIO" = "logs" ]; then
    lab_cluster_or_die "게이트웨이 로그"
    save_gateway_logs manual
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

def products(m):   # 검색 조합을 넓힌 회차(SPREAD=1)는 따로 보이게 — 같은 상품 수라도 캐시를 잰 회차와 견줄 수 없다
    p = m.get("products", "?")
    return f"{p}·넓힘" if m.get("spread") == "1" else p

def verdict(m):
    return {"0": "통과", "99": "넘음"}.get(m.get("exit"), f"exit={m.get('exit', '?')}")

def overview():
    print(" ".join([L("회차", 34), L("커밋", 9), R("도착률", 6), R("상품", 10), R("요청/초", 8), R("실패", 7),
                    R("p95", 7), R("p99", 7), R("레플리카", 8)]) + "  임계값")
    for d in runs:
        m, extra = meta(d)
        s = summary(d)
        dur = s.get("http_req_duration", {})
        print(" ".join([L(d, 34), L(m.get("git", "?"), 9), R(m.get("rate", "?"), 6), R(products(m)[:10], 10),
                        R(num(s.get("http_reqs", {}).get("rate"), 1), 8), R(pct(s.get("http_req_failed", {}).get("value")), 7),
                        R(num(dur.get("p(95)")), 7), R(num(dur.get("p(99)")), 7), R(key_max(m, extra), 8)]) + f"  {verdict(m)}")

def detail(d):
    m, extra = meta(d)
    s = summary(d)
    print(f"\n── {d} " + "─" * max(4, 60 - len(d)))
    print(f"  커밋 {m.get('git', '?')} · 도착률 {m.get('rate', '?')} · 단계 {m.get('stages', '?')} · 사용자 {m.get('users', '?')}"
          f" · 상품 {m.get('products', '?')} · 임계값 {verdict(m)}")
    if "review_rate" in m:
        print(f"  리뷰 쓰기: 초당 {m['review_rate']} — 리뷰마다 catalog 의 별점 갱신(updateRating — 그 상품의 캐시만)")
    if "contention_product" in m:
        contention(m, summary(d))
    if m.get("engine"):
        print(f"  검색 엔진: {m['engine']}(ENGINE 으로 적음 — 이름 검색만 이 엔진, 카테고리 목록 · 필터는 PostgreSQL)")
    if m.get("client_ips"):
        print(f"  손님 IP 흉내: {m['client_ips']}개(CF-Connecting-IP 198.18.0.0/15, 시작 번호 {m.get('client_ip_offset', '0')}) — 유입 제한은 손님마다")
    if m.get("split"):
        print(f"  부하 VM 나눔: {m['split']} — 전체 도착률 {m.get('total_rate', '?')} 중 이 VM {m.get('rate', '?')}"
              + (f" · 시작 {m['start_at']}" if m.get("start_at") else "") + " (report merge 로 합친다)")
    if m.get("spread") == "1" or m.get("filter_search", "0") not in ("0", ""):
        print(f"  검색 조합: 넓힘={m.get('spread', '0')} · 깊은 쪽 {m.get('deep_pages', '?')} · 이름 검색 쪽 {m.get('name_pages', '?')}"
              f" · 필터 검색 비율 {m.get('filter_search', '0')}")
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

def contention(m, s):
    """재고 경합 — 회차마다 받아진 주문 · 재고 부족 거절 · 늦음, 받아진 것이 재고를 넘으면 초과 판매."""
    stock = int(m["stock"]) if m.get("stock", "").isdigit() else None
    print(f"  재고 경합: 상품 {m.get('contention_product', '?')} · 재고 {m.get('stock', '?')} · 사용자 {m.get('users', '?')}"
          f" · 회차 {m.get('rounds', '?')} · 붙잡는 시간 {m.get('hold_seconds', '?')}초")
    cnt = lambda k: int((s.get(k) or {}).get("count") or 0)
    for r in range(int(m["rounds"]) if m.get("rounds", "").isdigit() else 0):
        a = cnt(f"contention_accepted{{round:{r}}}")
        flag = "" if stock is None else (f" ← 초과 판매 {a - stock}" if a > stock else (f" (덜 팔림 {stock - a})" if a < stock else " = 재고"))
        print(f"    회차 {r}: 받아짐 {a}{flag} · 재고 부족 거절 {cnt(f'contention_short_create{{round:{r}}}')}"
              f" · 늦음 {cnt(f'contention_late{{round:{r}}}')}")
    print(f"    입금 안내에서의 재고 부족 {cnt('contention_short_start')} · 그 밖의 거절 {cnt('contention_refused')} · 오류 {cnt('contention_errors')}"
          "  (덜 팔리거나 초과면 order 의 order_stock_hold_total{result=\"unavailable\"} 를 본다 — 예약 없이 간 주문)")

def resolve(x):
    name = os.path.basename(os.path.normpath(x))
    if name not in runs:
        sys.exit(f"그런 회차가 없다: {x} ({out})")
    return name

def merge(names):
    """부하 VM 여러 대의 같은 회차 — 횟수는 더하고, 실패율은 요청 수로 가중, 백분위는 합칠 수 없어 VM 마다 · 가장 나쁜 값."""
    if len(names) < 2:
        sys.exit("report merge 에는 회차 폴더가 둘 이상 필요하다(다른 VM 의 폴더를 OUT 아래로 복사해 와서)")
    rows = []
    for d in names:
        m, _ = meta(d)
        rows.append((d, m, summary(d)))
    print(" ".join([L("회차(VM)", 34), L("나눔", 6), R("요청", 9), R("요청/초", 8), R("실패", 7), R("p95", 7), R("p99", 7)]) + "  임계값")
    reqs = rate = failed = 0
    errs = {"5xx": 0, "4xx": 0, "응답 없음": 0, "429": 0}
    for d, m, s in rows:
        n = (s.get("http_reqs") or {}).get("count") or 0
        f = ((s.get("http_req_failed") or {}).get("value") or 0) * n
        reqs += n
        rate += (s.get("http_reqs") or {}).get("rate") or 0
        failed += f
        for k, key in (("5xx", "errors_5xx"), ("4xx", "errors_4xx"), ("응답 없음", "errors_no_response"), ("429", "rate_limited")):
            errs[k] += (s.get(key) or {}).get("count") or 0
        dur = s.get("http_req_duration") or {}
        print(" ".join([L(d, 34), L(m.get("split", "-"), 6), R(num(n), 9), R(num((s.get("http_reqs") or {}).get("rate"), 1), 8),
                        R(pct(f / n if n else None), 7), R(num(dur.get("p(95)")), 7), R(num(dur.get("p(99)")), 7)]) + f"  {verdict(m)}")
    worst = lambda k: max(((s.get("http_req_duration") or {}).get(k) or 0) for _, _, s in rows)
    splits = sorted({m.get("split", "-") for _, m, _ in rows})
    print(f"\n  합계: 요청 {num(reqs)} (초당 {num(rate, 1)} — 같은 시간에 돈 VM 의 합) · 실패 {pct(failed / reqs if reqs else None)}"
          f" · 오류 " + " · ".join(f"{k} {num(v)}" for k, v in errs.items()))
    print(f"  p95 · p99(VM 중 가장 나쁜 값 — 백분위는 요약에서 합칠 수 없다): {num(worst('p(95)'))} · {num(worst('p(99)'))}ms")
    if len(splits) != len(rows) or "-" in splits:
        print("  ⚠️  나눔 값(split)이 겹치거나 없다 — 같은 회차의 VM 들인지 확인(SPLIT=1/2 · 2/2 …)")

if picks[:1] == ["merge"]:
    merge([resolve(x) for x in picks[1:]])
elif not picks:
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

# 손님 IP 흉내 · 부하 VM 나누기(docs/83 §2.6) — 계산은 ops/k6/clients.js 와 같다
[ -z "${ENGINE:-}" ] || [[ "$ENGINE" =~ ^(es|postgres)$ ]] || die "ENGINE 은 es 또는 postgres(회차 기록용 이름표)"
CLIENT_IPS="${CLIENT_IPS:-0}"
[[ "$CLIENT_IPS" =~ ^[0-9]+$ ]] && [ "$CLIENT_IPS" -le 65536 ] || die "CLIENT_IPS 는 0 ~ 65536"
SPLIT_PART=1; SPLIT_OF=1; TOTAL_RATE="${RATE:-}"
if [ -n "${SPLIT:-}" ]; then
    [[ "$SPLIT" =~ ^([1-9][0-9]?)/([1-9][0-9]?)$ ]] || die "SPLIT 은 i/n (예 1/2)"
    SPLIT_PART="${BASH_REMATCH[1]}"; SPLIT_OF="${BASH_REMATCH[2]}"
    [ "$SPLIT_PART" -le "$SPLIT_OF" ] && [ "$SPLIT_OF" -le 16 ] || die "SPLIT 은 1 ≤ i ≤ n ≤ 16"
    [ -z "${STAGES:-}" ] || die "SPLIT 은 STAGES 와 함께 쓰지 않는다 — 전체 RATE 로(VM 마다 RATE/n)"
    [[ "${RATE:-}" =~ ^[1-9][0-9]*$ ]] || die "SPLIT 에는 전체 RATE(정수)가 필요하다 — VM 마다 RATE/n"
    [ $(( RATE % SPLIT_OF )) -eq 0 ] || die "SPLIT 의 RATE($RATE)는 n($SPLIT_OF)으로 나누어떨어져야 한다"
    RATE=$(( RATE / SPLIT_OF ))
    ID_OFFSET=$(( ID_OFFSET + (SPLIT_PART - 1) * USERS ))   # 데모 계정을 VM 마다 따로(리프레시 토큰이 회전한다)
fi
CLIENT_IP_OFFSET=$(( (SPLIT_PART - 1) * CLIENT_IPS ))
[ $(( CLIENT_IP_OFFSET + CLIENT_IPS )) -le 65536 ] || die "CLIENT_IPS × n 이 65536 을 넘는다"
if [ -n "${START_AT:-}" ]; then
    START_EPOCH="$(date -u -d "$START_AT" +%s 2>/dev/null)" || die "START_AT 을 읽지 못했다 — 예 2026-10-20T11:00:00Z"
    [ "$START_EPOCH" -gt "$(date -u +%s)" ] || die "START_AT($START_AT)이 이미 지났다"
fi

# 인증서 — 검증을 끄지 않는다. 데모 CA 가 있으면 그것을 믿게 한다(k6 는 Go — SSL_CERT_FILE 을 읽는다).
if [ -f "$CA_FILE" ]; then
    export SSL_CERT_FILE="$CA_FILE"
else
    warn "CA_FILE($CA_FILE)이 없다 — 시스템 신뢰 저장소로 검증한다(데모 CA 면 인증서 오류로 멈춘다)"
fi

# smoke 는 계정 하나로 한 번
[ "$SCENARIO" = "smoke" ] && USERS=1

# 데모 비밀번호 — 로그인하는 시나리오만. 길이만 알린다.
if [ "$SCENARIO" = "order" ] || [ "$SCENARIO" = "smoke" ] || [ "$SCENARIO" = "mixed" ] || [ "$SCENARIO" = "contention" ]; then
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
    echo "spread=${SPREAD:-0} name_pages=${NAME_PAGES:-default} filter_search=${FILTER_SEARCH:-0}"
    [ "$SCENARIO" != "mixed" ] || echo "review_rate=${REVIEW_RATE:-1}"
    [ "$CLIENT_IPS" = "0" ] || echo "client_ips=$CLIENT_IPS client_ip_offset=$CLIENT_IP_OFFSET"
    [ -z "${ENGINE:-}" ] || echo "engine=$ENGINE"
    [ "$SPLIT_OF" = "1" ] || echo "split=$SPLIT_PART/$SPLIT_OF total_rate=$TOTAL_RATE start_at=${START_AT:-}"
    [ "$SCENARIO" != "contention" ] || echo "contention_product=${CONTENTION_PRODUCT:-?} stock=${STOCK:-?} rounds=${ROUNDS:-3} hold_seconds=${HOLD_SECONDS:-20}"
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
[ "$CLIENT_IPS" -gt 0 ] && args+=(-e CLIENT_IPS="$CLIENT_IPS" -e CLIENT_IP_OFFSET="$CLIENT_IP_OFFSET")
[ "$SPLIT_OF" -gt 1 ] && args+=(-e SPLIT="$SPLIT_PART/$SPLIT_OF")
for v in PRODUCTS PRODUCT_ID_OFFSET DEEP_PAGES NAME_SEARCH SPREAD NAME_PAGES FILTER_SEARCH REVIEW_RATE \
         CONTENTION_PRODUCT STOCK ROUNDS HOLD_SECONDS ROUND_GAP_SECONDS; do
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
if [ -n "${START_EPOCH:-}" ]; then
    info "START_AT $START_AT 까지 기다린다($(( START_EPOCH - $(date -u +%s) ))초) — 다른 부하 VM 과 함께 시작"
    sleep $(( START_EPOCH - $(date -u +%s) ))
fi
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
