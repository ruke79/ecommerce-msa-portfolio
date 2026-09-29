#!/usr/bin/env bash
# 운영 액션 ⑨ — PITR 복원 훈련 (docs/15 §4)
#
# 왜 필요한가: **검증하지 않은 백업은 백업이 아니다.** 백업 스크립트가 매일 조용히 돌아도,
# 정작 필요한 날 복구가 안 되는 경우가 대부분이다 — WAL 이 중간에 끊겼거나, archive_command 가
# 실패를 성공으로 보고했거나, 보존 정책이 필요한 WAL 을 지웠거나.
#
# 이 스크립트는 사고 하나를 **실제로 재현하고 되돌린다**:
#
#   1. 아카이빙이 켜진 PostgreSQL 을 띄운다
#   2. 주문 데이터를 넣는다                      ← 지키고 싶은 것
#   3. base backup 을 뜬다
#   4. 주문을 더 넣는다 → 시각 T 를 기록          ← 여기까지는 복구되어야 한다
#   5. DROP TABLE 로 사고를 낸다                 ← 이건 되돌려져야 한다
#   6. base backup + WAL 로 시각 T 로 복구한다
#   7. 검증: 테이블이 살아 있고, 행 수가 T 시점과 정확히 같고, 사고 이후 데이터는 없다
#   8. RTO(복구 소요 시간)를 실측해 출력한다
#
# 복제(docs/13 §2)로는 5번을 되돌릴 수 없다 — DROP TABLE 은 복제본에도 즉시 전파된다.
# 그래서 PITR 이 필요하고, 그래서 이 훈련이 필요하다.
#
# 사용:
#   ./ops/09-restore-drill.sh                  # 컨테이너로 전 과정 재현 (기본)
#   PG_IMAGE=postgres:16-alpine ./ops/09-restore-drill.sh
#   KEEP=1 ./ops/09-restore-drill.sh           # 실패 원인 조사용으로 컨테이너 보존
#
# 실패하면 exit 1 — CI 에 넣어 정기 훈련을 자동화할 수 있다(docs/15 §5 "분기 정기 훈련").
set -euo pipefail

PG_IMAGE="${PG_IMAGE:-${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX:-}postgres:16-alpine}"
PGPASSWORD_DRILL="${PGPASSWORD_DRILL:-drillpw}"
WORK="${WORK:-$(mktemp -d -t pitr-drill-XXXXXX)}"
KEEP="${KEEP:-0}"
PRIMARY="pitr-drill-primary-$$"
RESTORED="pitr-drill-restored-$$"
ROWS_BEFORE_BACKUP="${ROWS_BEFORE_BACKUP:-500}"
ROWS_AFTER_BACKUP="${ROWS_AFTER_BACKUP:-300}"

log()  { printf '\n>>> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker 가 필요합니다"

cleanup() {
    if [ "$KEEP" = "1" ]; then
        printf '\nKEEP=1 — 컨테이너와 %s 를 남겨 둡니다.\n' "$WORK"
        return
    fi
    docker rm -f "$PRIMARY" "$RESTORED" >/dev/null 2>&1 || true
    # 컨테이너가 postgres uid 로 만든 파일이 있어 host 권한으로는 못 지울 수 있다.
    docker run --rm -v "$WORK:/w" "$PG_IMAGE" sh -c 'rm -rf /w/*' >/dev/null 2>&1 || true
    rm -rf "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$WORK/wal" "$WORK/base" "$WORK/restore"
# 컨테이너의 postgres 사용자(uid 70)가 써야 한다.
chmod 777 "$WORK" "$WORK/wal" "$WORK/base" "$WORK/restore"

psql_primary() { docker exec -e PGPASSWORD="$PGPASSWORD_DRILL" "$PRIMARY" psql -U postgres -tAqX -v ON_ERROR_STOP=1 "$@"; }
psql_restored() { docker exec -e PGPASSWORD="$PGPASSWORD_DRILL" "$RESTORED" psql -U postgres -tAqX -v ON_ERROR_STOP=1 "$@"; }

wait_ready() {
    # ⚠️ pg_isready 만으로는 부족하다. 공식 이미지는 초기화용 임시 서버를 먼저 띄웠다가
    # 껐다 켜므로, 그 임시 서버에 붙으면 곧바로 "the database system is shutting down" 을
    # 맞는다(-c 옵션도 아직 안 먹은 상태다). "초기화 완료" 로그를 본 뒤에 판단한다.
    local name="$1" limit="${2:-90}" i
    for i in $(seq 1 "$limit"); do
        if docker logs "$name" 2>&1 | grep -q "init process complete"; then
            break   # 초기화 단계가 있는 컨테이너 — 이제 진짜 서버를 기다린다
        fi
        if docker exec "$name" test -f /var/lib/postgresql/data/recovery.signal 2>/dev/null \
           || docker logs "$name" 2>&1 | grep -q "database system is ready to accept connections"; then
            break   # 복원 컨테이너처럼 초기화 단계가 없는 경우
        fi
        sleep 1
    done
    for i in $(seq 1 "$limit"); do
        if docker exec "$name" pg_isready -U postgres -q 2>/dev/null \
           && docker exec -e PGPASSWORD="$PGPASSWORD_DRILL" "$name" \
                psql -U postgres -tAqX -c 'SELECT 1' >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    printf '\n--- %s 로그 ---\n' "$name" >&2
    docker logs --tail 40 "$name" >&2 || true
    die "$name 가 ${limit}초 안에 준비되지 않았습니다"
}

# ---------------------------------------------------------------- 1. 아카이빙 켜고 기동

log "1/7  아카이빙이 켜진 PostgreSQL 기동"
docker rm -f "$PRIMARY" >/dev/null 2>&1 || true
docker run -d --name "$PRIMARY" \
    -e POSTGRES_PASSWORD="$PGPASSWORD_DRILL" \
    -v "$WORK/wal:/wal" -v "$WORK/base:/base" \
    "$PG_IMAGE" \
    -c wal_level=replica \
    -c archive_mode=on \
    -c "archive_command=test ! -f /wal/%f && cp %p /wal/%f" \
    -c archive_timeout=10 \
    -c max_wal_senders=4 \
    -c wal_keep_size=64MB >/dev/null
wait_ready "$PRIMARY"
info "archive_command = $(psql_primary -c 'SHOW archive_command')"

# ---------------------------------------------------------------- 2. 지켜야 할 데이터

log "2/7  주문 데이터 적재 (${ROWS_BEFORE_BACKUP}건)"
psql_primary -c "CREATE DATABASE orderdb" >/dev/null
psql_primary -d orderdb -c "
    CREATE TABLE orders (
        id          bigserial PRIMARY KEY,
        order_no    text NOT NULL,
        amount      numeric(12,2) NOT NULL,
        created_at  timestamptz NOT NULL DEFAULT now()
    );
    INSERT INTO orders (order_no, amount)
    SELECT 'ORD-' || lpad(g::text, 8, '0'), (g * 1000)::numeric
    FROM generate_series(1, $ROWS_BEFORE_BACKUP) g;" >/dev/null
info "적재 완료: $(psql_primary -d orderdb -c 'SELECT count(*) FROM orders')건"

# ---------------------------------------------------------------- 3. base backup

log "3/7  base backup"
backup_started="$(date +%s)"
docker exec -e PGPASSWORD="$PGPASSWORD_DRILL" "$PRIMARY" \
    pg_basebackup --pgdata=/base --format=plain --wal-method=stream \
                  --checkpoint=fast -U postgres --no-password
info "소요 $(( $(date +%s) - backup_started ))초, 크기 $(du -sh "$WORK/base" 2>/dev/null | cut -f1)"

# ---------------------------------------------------------------- 4. 백업 이후 데이터 + 목표 시각

log "4/7  백업 이후 주문 ${ROWS_AFTER_BACKUP}건 추가 → 이 시점까지 복구되어야 한다"
psql_primary -d orderdb -c "
    INSERT INTO orders (order_no, amount)
    SELECT 'ORD-' || lpad((g + $ROWS_BEFORE_BACKUP)::text, 8, '0'), (g * 1000)::numeric
    FROM generate_series(1, $ROWS_AFTER_BACKUP) g;" >/dev/null

EXPECTED_ROWS="$(psql_primary -d orderdb -c 'SELECT count(*) FROM orders')"
EXPECTED_SUM="$(psql_primary -d orderdb -c 'SELECT coalesce(sum(amount),0)::text FROM orders')"
# 목표 시각은 **서버 시계**로 잡는다. 호스트와 컨테이너의 시계/타임존이 어긋나면
# 복구가 엉뚱한 지점에서 멈춘다 — 실제 복구 실패의 흔한 원인이다.
TARGET_TIME="$(psql_primary -c 'SELECT now()')"
info "목표 시각 T = $TARGET_TIME"
info "T 시점 상태: ${EXPECTED_ROWS}건, 합계 $EXPECTED_SUM"

sleep 2   # T 와 사고 사이에 확실한 간격을 둔다

# ---------------------------------------------------------------- 5. 사고

log "5/7  사고 재현 — DROP TABLE orders"
psql_primary -d orderdb -c "DROP TABLE orders" >/dev/null
psql_primary -d orderdb -c "CREATE TABLE orders (id bigint); INSERT INTO orders VALUES (-1)" >/dev/null
info "사고 후 상태: $(psql_primary -d orderdb -c 'SELECT count(*) FROM orders')건 (쓰레기 데이터)"

# 사고 시점까지의 WAL 을 아카이브로 밀어낸다. 실무에서는 archive_timeout 이 알아서 하지만
# 훈련에서는 기다릴 이유가 없다.
psql_primary -c "SELECT pg_switch_wal()" >/dev/null
psql_primary -c "CHECKPOINT" >/dev/null
sleep 3
info "아카이브된 WAL: $(find "$WORK/wal" -type f | wc -l)개"

log "     프라이머리 정지 (사고 난 서버는 더 이상 진실의 원본이 아니다)"
docker stop "$PRIMARY" >/dev/null

# ---------------------------------------------------------------- 6. 복구

log "6/7  base backup + WAL 로 T 시점 복구"
rto_started="$(date +%s)"

# base backup 을 복원 대상 디렉터리로 옮긴다. 원본 백업은 건드리지 않는다 —
# 복구가 실패했을 때 다시 시도할 수 있어야 한다.
docker run --rm -v "$WORK:/w" "$PG_IMAGE" sh -c 'cp -a /w/base/. /w/restore/ && chmod 700 /w/restore'

# 복구 설정. PostgreSQL 12+ 부터 recovery.conf 는 없어졌고 postgresql.auto.conf 에 쓴다.
#   recovery_target_action=promote : 목표 시각에 도달하면 읽기전용에 머무르지 않고 승격
#   restore_command                : 아카이브에서 WAL 을 가져오는 방법
docker run --rm -v "$WORK:/w" "$PG_IMAGE" sh -c "cat >> /w/restore/postgresql.auto.conf <<'CONF'
restore_command = 'cp /wal/%f %p'
recovery_target_time = '$TARGET_TIME'
recovery_target_action = 'promote'
CONF
touch /w/restore/recovery.signal"

info "recovery_target_time = $TARGET_TIME"

docker rm -f "$RESTORED" >/dev/null 2>&1 || true
docker run -d --name "$RESTORED" \
    -e POSTGRES_PASSWORD="$PGPASSWORD_DRILL" \
    -v "$WORK/restore:/var/lib/postgresql/data" \
    -v "$WORK/wal:/wal" \
    "$PG_IMAGE" >/dev/null
wait_ready "$RESTORED" 120

# 승격이 끝날 때까지 기다린다. 복구 중에는 읽기전용이라 검증 결과가 달라질 수 있다.
for i in $(seq 1 60); do
    [ "$(psql_restored -c 'SELECT pg_is_in_recovery()')" = "f" ] && break
    sleep 1
done
RTO=$(( $(date +%s) - rto_started ))
info "복구 완료 — RTO ${RTO}초"

# ---------------------------------------------------------------- 7. 검증

log "7/7  검증"
failed=0

actual_rows="$(psql_restored -d orderdb -c 'SELECT count(*) FROM orders' 2>/dev/null || echo 'ERROR')"
actual_sum="$(psql_restored -d orderdb -c 'SELECT coalesce(sum(amount),0)::text FROM orders' 2>/dev/null || echo 'ERROR')"

if [ "$actual_rows" = "$EXPECTED_ROWS" ]; then
    info "OK   행 수 $actual_rows = T 시점과 일치"
else
    printf '    FAIL 행 수 %s (기대 %s)\n' "$actual_rows" "$EXPECTED_ROWS"; failed=1
fi

if [ "$actual_sum" = "$EXPECTED_SUM" ]; then
    info "OK   합계 $actual_sum = T 시점과 일치"
else
    printf '    FAIL 합계 %s (기대 %s)\n' "$actual_sum" "$EXPECTED_SUM"; failed=1
fi

# 사고(DROP + 쓰레기 삽입)가 되돌려졌는가. 행 수만 봐서는 부족하다 —
# 쓰레기 테이블도 1건이므로, 원래 스키마가 살아 있는지 직접 확인한다.
if [ "$(psql_restored -d orderdb -c "SELECT count(*) FROM information_schema.columns WHERE table_name='orders'")" -ge 4 ]; then
    info "OK   원래 스키마(4개 컬럼) 복원 — DROP TABLE 이 되돌려졌다"
else
    printf '    FAIL orders 테이블이 사고 이후 형태입니다 — 복구가 T 를 지나쳤습니다\n'; failed=1
fi

if [ "$(psql_restored -d orderdb -c 'SELECT count(*) FROM orders WHERE order_no IS NULL')" = "0" ]; then
    info "OK   사고 이후 쓰레기 데이터 없음"
else
    printf '    FAIL 사고 이후 데이터가 남아 있습니다\n'; failed=1
fi

cat <<SUMMARY

────────────────────────────────────────────────
  복원 훈련 결과
    목표 시각(T)     : $TARGET_TIME
    복구된 행 수     : $actual_rows / $EXPECTED_ROWS
    RTO(실측)        : ${RTO}초
    사용한 WAL       : $(find "$WORK/wal" -type f | wc -l)개
────────────────────────────────────────────────
SUMMARY

if [ "$failed" != "0" ]; then
    die "복원 훈련 실패 — 위 FAIL 항목을 확인하세요. 이 상태면 실제 사고 때도 복구되지 않습니다."
fi
log "복원 훈련 통과 — 백업이 실제로 복구 가능함을 확인했습니다."
