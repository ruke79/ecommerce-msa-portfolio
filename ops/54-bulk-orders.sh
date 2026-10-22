#!/usr/bin/env bash
# 운영 액션 54 — 부하 테스트용 주문 이력 대량 생성 · 확인 · 삭제 (docs/83 §2.4)
# [JA] 運用アクション 54 — 負荷テスト用の注文履歴の大量生成 · 確認 · 削除
#
# ops/tools/generate-demo-orders.py 가 낸 SQL 을 PostgreSQL 파드의 psql 로 흘려 넣는다. 데모 사용자(ops/48)마다 끝난 주문을 쌓아,
# 주문 표가 큰 상태에서 order · contention 시나리오(ops/53)를 잴 수 있게 한다. 결제 · 배송 · 쿠폰 · 재고 · 읽기 모델에는 쓰지 않는다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/54-bulk-orders.sh estimate            # 행 수 · 용량 (기본 손님 10만 × 20 = 주문 200만)
#   ./ops/54-bulk-orders.sh load                # 확인 → 묶음별 적재(다시 실행하면 이어서) → 결과
#   ./ops/54-bulk-orders.sh status              # 생성한 주문 · 상태별 · 줄 · 표 크기
#   ./ops/54-bulk-orders.sh purge               # 생성 범위의 데모 주문만 지운다(tracking_id 'demo-')
#
#   USERS=10000 ORDERS_PER_USER=100 ./ops/54-bulk-orders.sh load   # 손님 수 · 손님당 주문(purge · status 도 같은 값으로)
#
# 환경 변수(기본값):
#   USERS=100000  ORDERS_PER_USER=20  CHUNK=1000(손님)   # USERS 는 ops/48 로 만든 데모 사용자 수 이하
#   PRODUCTS=1000000  PRODUCT_ID_OFFSET=1000000          # 줄의 상품 id 범위 — ops/44 로 넣은 범위
#   USER_ID_OFFSET=7000000000  ID_OFFSET=8000000000      # 데모 사용자 id(ops/48) · 주문 id 의 시작
#   PG_NS=postgres PG_DEPLOY=postgres PG_USER=product ORDER_DB=orderdb USER_DB=userdb
#   APP_NS=ecommerce   FORCE=1(백업 시간대 02~06 UTC 무시)   YES=1(확인을 묻지 않음)
#
# ⚠️ 실습 클러스터에서만 — 가짜 주문이 운영 DB 에 들어가면 매출 · 주문 집계 · 손님의 주문 목록을 더럽힌다. 이 클러스터의
#    APP_PUBLIC_URL 이 운영 주소면 거절한다(ops/53 과 같은 확인).
set -euo pipefail

USERS="${USERS:-100000}"
ORDERS_PER_USER="${ORDERS_PER_USER:-20}"
CHUNK="${CHUNK:-1000}"
PRODUCTS="${PRODUCTS:-1000000}"
PRODUCT_ID_OFFSET="${PRODUCT_ID_OFFSET:-1000000}"
USER_ID_OFFSET="${USER_ID_OFFSET:-7000000000}"
ID_OFFSET="${ID_OFFSET:-8000000000}"
PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_USER="${PG_USER:-product}"
ORDER_DB="${ORDER_DB:-orderdb}"
USER_DB="${USER_DB:-userdb}"
APP_NS="${APP_NS:-ecommerce}"

HERE="$(cd "$(dirname "$0")" && pwd)"
GEN="$HERE/tools/generate-demo-orders.py"

die()  { echo "ERROR: $1" >&2; exit 1; }
info() { echo ">>> $*"; }
warn() { echo "  ⚠️  $*" >&2; }
need() { command -v "$1" >/dev/null || die "$1 이 필요합니다"; }

for v in USERS ORDERS_PER_USER CHUNK PRODUCTS PRODUCT_ID_OFFSET USER_ID_OFFSET ID_OFFSET; do
    [[ "${!v}" =~ ^[0-9]+$ ]] || die "$v 는 0 이상의 정수"
done

gen() {
    python3 -I "$GEN" --users "$USERS" --orders-per-user "$ORDERS_PER_USER" --chunk "$CHUNK" \
        --user-offset "$USER_ID_OFFSET" --id-offset "$ID_OFFSET" --line-offset "$((ID_OFFSET * 4))" \
        --product-offset "$PRODUCT_ID_OFFSET" --products "$PRODUCTS" "$@"
}

psql_db() {   # psql_db <db> [psql 인자…] — SQL 은 stdin 또는 -c
    local db="$1"; shift
    kubectl -n "$PG_NS" exec -i "deploy/$PG_DEPLOY" -- psql -X -q -U "$PG_USER" -d "$db" -v ON_ERROR_STOP=1 "$@"
}

lab_cluster_or_die() {
    need kubectl
    local url
    url="$(kubectl -n "$APP_NS" get configmap ecommerce-config -o jsonpath='{.data.APP_PUBLIC_URL}' 2>/dev/null)" \
        || die "ecommerce-config 를 읽지 못했다 — kubectl 컨텍스트가 실습 클러스터인지"
    case "$url" in
        *ysyproject.com*) die "운영 클러스터다(APP_PUBLIC_URL=$url) — 가짜 주문 이력은 넣지 않는다" ;;
        "") die "APP_PUBLIC_URL 이 비어 있다 — 실습 클러스터(데모 오버레이)인지 확인할 수 없다" ;;
    esac
}

confirm() {
    [ "${YES:-0}" = "1" ] && return 0
    printf '  %s [y/N] ' "$1" >&2
    local a; read -r a
    [ "$a" = "y" ] || [ "$a" = "Y" ] || die "그만둡니다"
}

check_window() {   # 백업 CronJob(03:00 · 04:00 매일, 05:00 월요일 — k8s/85)과 겹치지 않게 — ops/44 와 같은 창
    local h; h=$((10#$(date -u +%H)))
    if [ "$h" -ge 2 ] && [ "$h" -lt 6 ]; then
        [ "${FORCE:-0}" = "1" ] || die "백업 시간대 근처다(02~06 UTC) — 그 뒤에, 또는 FORCE=1"
        warn "백업 시간대지만 FORCE=1 — 그대로 간다"
    fi
}

range_sql() { echo "order_id BETWEEN $ID_OFFSET AND $((ID_OFFSET + USERS * ORDERS_PER_USER - 1)) AND tracking_id LIKE 'demo-%'"; }

existing() {
    psql_db "$ORDER_DB" -At -c "SELECT count(*) FROM orders.orders WHERE $(range_sql)" | tr -d '[:space:]'
}

cmd_load() {
    need python3
    [ -f "$GEN" ] || die "$GEN 이 없다"
    lab_cluster_or_die
    check_window
    info "대상: $(kubectl config current-context 2>/dev/null || echo '?') · $PG_NS/$PG_DEPLOY · $ORDER_DB"
    gen --estimate | sed 's/^/    /'
    local users
    users="$(psql_db "$USER_DB" -At -c "SELECT count(*) FROM users.users WHERE user_id BETWEEN $USER_ID_OFFSET AND $((USER_ID_OFFSET + USERS - 1)) AND email LIKE '%@demo.invalid'" 2>/dev/null | tr -d '[:space:]' || true)"
    if [ "${users:-0}" != "$USERS" ]; then
        warn "데모 사용자가 ${users:-?}명 — USERS=$USERS 보다 적으면 없는 손님의 주문이 생긴다(먼저 USERS=$USERS ./ops/48-bulk-users.sh load)"
    fi
    local have; have="$(existing)" || die "orders.orders 를 읽지 못했다 — PG_NS · PG_DEPLOY · ORDER_DB 확인"
    info "이 범위에 이미 있는 데모 주문: $have 개"
    confirm "주문 $((USERS * ORDERS_PER_USER)) 개를 만든다(실습 클러스터) — 진행할까?"

    local chunks=$(( (USERS + CHUNK - 1) / CHUNK ))
    local first=$(( have / (CHUNK * ORDERS_PER_USER) ))   # 묶음은 한 트랜잭션 — 다 들어간 묶음 다음부터(다시 넣어도 같다)
    local i
    for i in $(seq "$first" $((chunks - 1))); do
        gen --chunk-index "$i" | psql_db "$ORDER_DB" >/dev/null \
            || die "묶음 $i 실패 — 같은 명령으로 다시 실행하면 이어서 넣는다"
        printf '\r    묶음 %d/%d' $((i + 1)) "$chunks"
    done
    echo
    psql_db "$ORDER_DB" -c "ANALYZE orders.orders; ANALYZE orders.order_product; ANALYZE orders.order_address;" >/dev/null
    cmd_status
}

cmd_status() {
    need kubectl
    info "데모 주문 (id $ID_OFFSET … $((ID_OFFSET + USERS * ORDERS_PER_USER - 1)), tracking_id 'demo-')"
    psql_db "$ORDER_DB" <<SQL
SELECT order_status AS "상태", count(*) FROM orders.orders WHERE $(range_sql) GROUP BY 1 ORDER BY 2 DESC;
SELECT (SELECT count(*) FROM orders.orders) AS "주문 전체",
       (SELECT count(*) FROM orders.order_product) AS "줄 전체",
       pg_size_pretty(pg_total_relation_size('orders.orders') + pg_total_relation_size('orders.order_product')
                      + pg_total_relation_size('orders.order_address')) AS "주문 · 줄 · 배송지 + 인덱스";
SQL
}

cmd_purge() {
    need python3
    lab_cluster_or_die
    info "지울 것: $(existing) 개(tracking_id 'demo-' 이고 id 가 범위 안인 주문과 그 줄 · 배송지만)"
    confirm "지울까?"
    gen --purge | psql_db "$ORDER_DB"
    cmd_status
}

case "${1:-}" in
    estimate) need python3; gen --estimate ;;
    load)     cmd_load ;;
    status)   cmd_status ;;
    purge)    cmd_purge ;;
    *)        sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
