#!/usr/bin/env bash
# 운영 액션 ㊹ — 부하 테스트용 상품 대량 등록 · 확인 · 삭제 (docs/19 §5.1)
#
# ops/tools/generate-catalog-data.py 를 운영 노드에서 안전하게 돌리는 감싸개다. 손으로 하면 빠뜨리기 쉬운 것들을 대신 한다:
#
#   · 디스크 여유(표 + 인덱스 + WAL)와 백업 시간대(03:00~05:00 UTC)를 먼저 본다
#   · DB 를 port-forward 로 잠깐 열고, 끝나면(실패해도) 닫는다
#   · DB 비밀번호는 Secret 에서 읽어 생성기 프로세스의 환경 변수로만 넘긴다 — 화면 · 명령줄 · 셸 기록에 남지 않는다
#   · 끊겨도 다시 실행하면 이어서 넣는다(생성기 --resume)
#   · 적재 · 삭제 뒤 카탈로그 캐시를 비운다 — "products" 캐시는 만료가 없어서, 비우지 않으면 목록이 옛 상태로 남는다
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/44-bulk-catalog.sh estimate            # 행 수 · 용량 · 시간 추정만 (기본 200만 개)
#   ./ops/44-bulk-catalog.sh load                # 점검 → 적재(이어서) → 캐시 비우기 → 결과
#   ./ops/44-bulk-catalog.sh status              # 생성 범위에 들어간 상품 수 · 표 크기
#   ./ops/44-bulk-catalog.sh purge               # 생성 범위만 지우기 → VACUUM ANALYZE → 캐시 비우기
#   ./ops/44-bulk-catalog.sh flush-cache         # 카탈로그 캐시만 비우기
#
#   PRODUCTS=500000 ./ops/44-bulk-catalog.sh load   # 개수 바꾸기(purge 도 같은 값으로)
#   PRODUCTS=1000 PLACEHOLDER_IMAGE=/logo512.png ./ops/44-bulk-catalog.sh load
#                                                  # 이미지 저장소(S3)가 없는 데모 클러스터 — 시드 배치 잡 없이
#
# 환경 변수(기본값):
#   PRODUCTS=2000000  ID_OFFSET=1000000  TAXONOMY=dept  CHUNK=20000
#   PG_NS=postgres PG_SVC=postgres PG_DEPLOY=postgres PG_USER=product PG_DB=productdb LOCAL_PORT=15432
#   APP_NS=ecommerce SECRET=ecommerce-secrets SECRET_KEY=LOCAL_DB_PASSWORD
#   REDIS_NS=redis REDIS_DEPLOY=redis
#   STORAGE_DIR=/var/lib/rancher/k3s/storage   # local-path 가 PVC 를 두는 곳(디스크 여유를 여기서 잰다)
#   PLACEHOLDER_IMAGE=   # 비우면 시드 배치 잡이 만든 색상 · 이미지 URL 이 있어야 한다. 주면(/경로 · http(s)) 색상이 비었을 때
#                        # 시드 CSV 에서 넣고 모든 SKU 이미지를 이 주소로 — 데모(S3 없음)는 프론트가 내보내는 /logo512.png
#   FORCE=1   백업 시간대 · 디스크 경고를 무시     YES=1   purge 확인을 묻지 않음
#
# ⚠️ 2천만 개 같은 큰 값은 운영 노드에 넣지 않는다 — 디스크 60~90GB, PostgreSQL 파드 메모리 512Mi(docs/19 §5.1).
#
set -euo pipefail

PRODUCTS="${PRODUCTS:-2000000}"
ID_OFFSET="${ID_OFFSET:-1000000}"
TAXONOMY="${TAXONOMY:-dept}"
CHUNK="${CHUNK:-20000}"
PG_NS="${PG_NS:-postgres}"
PG_SVC="${PG_SVC:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_USER="${PG_USER:-product}"
PG_DB="${PG_DB:-productdb}"
LOCAL_PORT="${LOCAL_PORT:-15432}"
APP_NS="${APP_NS:-ecommerce}"
SECRET="${SECRET:-ecommerce-secrets}"
SECRET_KEY="${SECRET_KEY:-LOCAL_DB_PASSWORD}"
REDIS_NS="${REDIS_NS:-redis}"
REDIS_DEPLOY="${REDIS_DEPLOY:-redis}"
STORAGE_DIR="${STORAGE_DIR:-/var/lib/rancher/k3s/storage}"
PLACEHOLDER_IMAGE="${PLACEHOLDER_IMAGE:-}"

# 상품당 표 + 인덱스 바이트 — 생성기의 MEASURED["bytes_per_product"] 와 같은 값(로컬 50만 개 실측)
BYTES_PER_PRODUCT=2108
# 카탈로그가 쓰는 캐시 이름(@Cacheable). 키는 "<이름>::<키>" 다.
CACHES="products product products_by_category search_cache colors_cache sizes_cache details_cache"

HERE="$(cd "$(dirname "$0")" && pwd)"
GEN="$HERE/tools/generate-catalog-data.py"
PF_PID=""

die()  { echo "ERROR: $1" >&2; exit 1; }
info() { echo ">>> $*"; }
warn() { echo "  ⚠️  $*" >&2; }

cleanup() {
    if [ -n "$PF_PID" ] && kill -0 "$PF_PID" 2>/dev/null; then
        kill "$PF_PID" 2>/dev/null || true
        wait "$PF_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

need() { command -v "$1" >/dev/null || die "$1 이 필요합니다"; }

psql_in() {
    kubectl -n "$PG_NS" exec -i "deploy/$PG_DEPLOY" -- psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 "$@"
}

gen() {
    python3 "$GEN" --products "$PRODUCTS" --id-offset "$ID_OFFSET" "$@"
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# DB 를 잠깐 연다. 끝나면 trap 이 닫는다.
open_db() {
    need kubectl
    port_open "$LOCAL_PORT" && die "127.0.0.1:$LOCAL_PORT 를 이미 누가 쓰고 있다 — LOCAL_PORT 를 바꿔 다시"
    kubectl -n "$PG_NS" port-forward "svc/$PG_SVC" "$LOCAL_PORT:5432" >/dev/null 2>&1 &
    PF_PID=$!
    for _ in $(seq 1 30); do
        port_open "$LOCAL_PORT" && return 0
        kill -0 "$PF_PID" 2>/dev/null || die "port-forward 가 바로 끝났다 — kubectl -n $PG_NS get svc $PG_SVC 확인"
        sleep 0.5
    done
    die "port-forward 가 15초 안에 열리지 않았다"
}

dsn() { echo "host=127.0.0.1 port=$LOCAL_PORT dbname=$PG_DB user=$PG_USER sslmode=require"; }

# 비밀번호는 이 함수의 출력으로만 지나간다 — 변수에 담아 echo 하거나 명령줄 인자로 넘기지 않는다.
db_password() {
    kubectl -n "$APP_NS" get secret "$SECRET" -o "jsonpath={.data.$SECRET_KEY}" | base64 -d
}

run_gen_db() {
    local pw
    pw="$(db_password)" || die "Secret $APP_NS/$SECRET 의 $SECRET_KEY 를 읽지 못했다"
    [ -n "$pw" ] || die "Secret $APP_NS/$SECRET 의 $SECRET_KEY 가 비어 있다"
    PGPASSWORD="$pw" gen --copy "$(dsn)" "$@"
}

check_python() {
    need python3
    [ -f "$GEN" ] || die "$GEN 이 없다"
    python3 -c 'import psycopg2' 2>/dev/null \
        || die "psycopg2 가 없다 — Ubuntu 는 sudo apt-get install -y python3-psycopg2 (pip --user 는 24.04 에서 externally-managed 로 막힌다), 그 밖은 pip install --user psycopg2-binary"
}

check_window() {
    local h
    h=$(date -u +%H)
    # 백업 CronJob: 03:00 · 04:00 매일, 05:00 월요일(k8s/85). 적재와 겹치면 둘 다 느려지고 덤프가 반쯤 찬 표를 담는다.
    if [ "$h" -ge 2 ] && [ "$h" -lt 6 ]; then
        [ "${FORCE:-0}" = "1" ] && { warn "백업 시간대(02~06 UTC)지만 FORCE=1 — 진행"; return; }
        die "지금은 백업 시간대 근처다(02~06 UTC, 한국 11~15시). 그 뒤에 다시, 또는 FORCE=1"
    fi
}

check_disk() {
    local dir need_kb free_kb
    dir="$STORAGE_DIR"; [ -d "$dir" ] || dir=/
    # 표 + 인덱스 × 1.5(적재 중 WAL · 체크포인트 여유)
    need_kb=$(( PRODUCTS * BYTES_PER_PRODUCT * 3 / 2 / 1024 ))
    free_kb=$(df -Pk "$dir" | awk 'NR==2 {print $4}')
    echo "  디스크($dir): 남은 $(( free_kb / 1024 / 1024 )) GiB · 필요 약 $(( need_kb / 1024 / 1024 + 1 )) GiB"
    if [ "$free_kb" -lt "$need_kb" ]; then
        [ "${FORCE:-0}" = "1" ] && { warn "디스크가 모자랄 수 있지만 FORCE=1 — 진행"; return; }
        die "디스크가 모자란다 — PRODUCTS 를 줄이거나 공간을 비운 뒤 다시"
    fi
    # 다 쓰고 나서도 10% 는 남기자 — 꽉 찬 노드는 kubelet 이 파드를 쫓아낸다(디스크 압박 축출)
    local total_kb
    total_kb=$(df -Pk "$dir" | awk 'NR==2 {print $2}')
    if [ $(( free_kb - need_kb )) -lt $(( total_kb / 10 )) ]; then
        [ "${FORCE:-0}" = "1" ] || die "적재 뒤 디스크 여유가 10% 아래로 떨어진다 — 노드가 파드를 축출할 수 있다. FORCE=1 로 무시"
        warn "적재 뒤 디스크 여유가 10% 아래 — FORCE=1"
    fi
}

flush_cache() {
    info "카탈로그 캐시 비우기 ($CACHES)"
    # 비밀번호는 파드 안의 환경 변수(REDIS_PASSWORD)를 그대로 쓴다 — 밖으로 꺼내지 않는다.
    kubectl -n "$REDIS_NS" exec "deploy/$REDIS_DEPLOY" -- sh -c '
        export REDISCLI_AUTH="$REDIS_PASSWORD"
        total=0
        for c in '"$CACHES"'; do
            n=$(redis-cli --scan --pattern "$c::*" | tee /tmp/keys.$$ | wc -l)
            [ "$n" -gt 0 ] && xargs -n 500 redis-cli del < /tmp/keys.$$ >/dev/null
            total=$((total + n))
        done
        rm -f /tmp/keys.$$
        echo "  지운 캐시 키: $total"'
}

status() {
    local lo=$(( ID_OFFSET + 1 )) hi=$(( ID_OFFSET + PRODUCTS ))
    info "생성 범위 id $lo ~ $hi ($PG_DB)"
    psql_in -v lo="$lo" -v hi="$hi" <<'SQL'
SELECT count(*) AS "들어간 상품", min(product_id) AS "처음 id", max(product_id) AS "마지막 id"
  FROM product.product WHERE product_id BETWEEN :lo AND :hi;
SELECT c.relname AS "표",
       to_char(c.reltuples::bigint, 'FM999,999,999') AS "행(추정)",
       pg_size_pretty(pg_total_relation_size(c.oid)) AS "표 + 인덱스"
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'product' AND c.relname IN ('product', 'product_sku', 'product_size', 'product_subcategory')
 ORDER BY pg_total_relation_size(c.oid) DESC;
SELECT pg_size_pretty(pg_database_size(current_database())) AS "productdb 전체";
SQL
}

cmd_estimate() {
    need python3
    python3 "$GEN" --products "$PRODUCTS" --estimate
}

cmd_load() {
    info "점검 — 상품 $PRODUCTS 개, 분류 $TAXONOMY"
    check_python
    need kubectl
    check_window
    check_disk
    open_db
    info "적재 — 끊기면 같은 명령을 다시 실행하면 이어서 넣는다"
    local extra=()
    [ -n "$PLACEHOLDER_IMAGE" ] && extra+=(--placeholder-image "$PLACEHOLDER_IMAGE")
    run_gen_db --taxonomy "$TAXONOMY" --chunk "$CHUNK" --resume --yes ${extra[@]+"${extra[@]}"}
    flush_cache
    status
    echo
    echo "  다음: 부하 측정  ./ops/14-load-test.sh"
    echo "        끝나면    PRODUCTS=$PRODUCTS ./ops/44-bulk-catalog.sh purge"
}

cmd_purge() {
    local lo=$(( ID_OFFSET + 1 )) hi=$(( ID_OFFSET + PRODUCTS ))
    check_python
    need kubectl
    if [ "${YES:-0}" != "1" ]; then
        echo "상품 id $lo ~ $hi 와 그 SKU · 사이즈 · 분류 연결을 지운다(원래 상품은 남는다)."
        local answer=""
        read -r -p "계속하려면 yes 를 입력: " answer || true
        [ "$answer" = "yes" ] || die "취소했다"
    fi
    open_db
    run_gen_db --purge --yes
    info "VACUUM ANALYZE — 지운 공간을 재사용할 수 있게(디스크로 돌려받으려면 VACUUM FULL — 표를 잠근다)"
    psql_in -c "VACUUM ANALYZE product.product, product.product_sku, product.product_size, product.product_subcategory"
    flush_cache
    status
}

case "${1:-}" in
    estimate)    cmd_estimate ;;
    load)        cmd_load ;;
    status)      need kubectl; status ;;
    purge)       cmd_purge ;;
    flush-cache) need kubectl; flush_cache ;;
    *) sed -n '2,34p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
