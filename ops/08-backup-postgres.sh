#!/usr/bin/env bash
# 운영 액션 ⑧ — PostgreSQL 백업 (base backup + WAL 아카이빙)
#
# 배경: docs/15 는 **설계 문서**였다. "PostgreSQL 백업 ❌ 없음, 복구 절차 ❌ 없음 = 🔴".
#       복제(docs/13 §2)는 노드 장애만 막는다. DROP TABLE·잘못된 마이그레이션·랜섬웨어는
#       복제본에 **즉시 전파**되므로, 되돌리려면 시점 복구(PITR)가 있어야 한다.
#
# 이 스크립트가 하는 일:
#   1. 서버가 PITR 가능한 상태인지 점검 (wal_level, archive_mode, archive_command)
#      → 아니면 필요한 설정을 출력하고 --setup 으로 적용
#   2. base backup (pg_basebackup, 압축·체크섬 검증)
#   3. 백업 자체 검증 (pg_verifybackup)
#   4. 보존 기간 지난 백업 정리
#   5. Prometheus textfile 지표 기록 → 백업이 **조용히 실패**하는 것을 막는다
#
# 사용:
#   PGHOST=db PGPORT=5435 PGUSER=postgres BACKUP_ROOT=/var/backups/pg ./ops/08-backup-postgres.sh
#   ACTION=setup   ...     # 아카이빙 설정을 적용 (재기동 필요할 수 있음)
#   ACTION=check   ...     # 점검만
#   DRY_RUN=1      ...     # 수행할 명령만 출력
#
# cron 예시 (일 1회 base backup, WAL 은 서버가 연속 아카이브):
#   0 3 * * *  cd /srv/ecommerce && PGHOST=db BACKUP_ROOT=/var/backups/pg ./ops/08-backup-postgres.sh
set -euo pipefail

ACTION="${ACTION:-backup}"           # backup | setup | check
PGHOST="${PGHOST:?PGHOST 를 지정하세요}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/postgresql}"
WAL_ARCHIVE="${WAL_ARCHIVE:-$BACKUP_ROOT/wal}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
DRY_RUN="${DRY_RUN:-0}"
# 아카이브 타임아웃. docs/15 §2.1 의 RPO ≈ 60초 근거가 이 값이다.
ARCHIVE_TIMEOUT="${ARCHIVE_TIMEOUT:-60}"
# node_exporter 의 textfile collector 디렉터리. 있으면 백업 지표를 남긴다.
METRICS_DIR="${METRICS_DIR:-}"

export PGHOST PGPORT PGUSER

log()  { printf '>>> %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
run()  { if [ "$DRY_RUN" = "1" ]; then printf '[DRY_RUN] %s\n' "$*"; else eval "$@"; fi; }

command -v pg_basebackup >/dev/null || die "pg_basebackup 이 필요합니다 (postgresql-client)"
command -v psql          >/dev/null || die "psql 이 필요합니다"

q() { psql -tAqX -v ON_ERROR_STOP=1 -c "$1"; }

# ---------------------------------------------------------------- 점검

check_pitr_ready() {
    local ok=0
    log "PITR 준비 상태 점검 ($PGHOST:$PGPORT)"

    local wal_level archive_mode archive_command timeout
    wal_level="$(q 'SHOW wal_level')"
    archive_mode="$(q 'SHOW archive_mode')"
    archive_command="$(q 'SHOW archive_command')"
    timeout="$(q 'SHOW archive_timeout')"

    printf '    wal_level        = %s\n' "$wal_level"
    printf '    archive_mode     = %s\n' "$archive_mode"
    printf '    archive_command  = %s\n' "$archive_command"
    printf '    archive_timeout  = %s\n' "$timeout"

    # wal_level=minimal 이면 WAL 에 복구에 필요한 정보가 아예 안 들어간다.
    # 이 상태로 base backup 을 아무리 떠 봐야 시점 복구는 불가능하다.
    [ "$wal_level" = "minimal" ] && { warn "wal_level=minimal 이면 PITR 이 불가능합니다"; ok=1; }
    [ "$archive_mode" = "off" ]  && { warn "archive_mode=off — WAL 이 보관되지 않습니다"; ok=1; }
    { [ -z "$archive_command" ] || [ "$archive_command" = "(disabled)" ]; } && {
        warn "archive_command 가 비어 있습니다"; ok=1; }

    # 아카이빙이 켜져 있다면 **실제로 성공하고 있는지** 본다.
    # archive_command 가 조용히 실패하면 pg_wal 이 차올라 결국 디스크가 터진다.
    if [ "$archive_mode" != "off" ]; then
        local stats
        stats="$(q "SELECT archived_count || '|' || failed_count || '|' || coalesce(last_failed_time::text,'-') FROM pg_stat_archiver")"
        local archived="${stats%%|*}" rest="${stats#*|}"
        local failed="${rest%%|*}" last_failed="${rest#*|}"
        printf '    아카이브 성공 %s건 / 실패 %s건 (마지막 실패: %s)\n' "$archived" "$failed" "$last_failed"
        [ "$failed" != "0" ] && { warn "아카이브 실패가 기록돼 있습니다 — pg_wal 이 무한히 쌓입니다"; ok=1; }
    fi

    if [ "$ok" != "0" ]; then
        cat <<SETUP

>>> 필요한 설정 (postgresql.conf):
    wal_level = replica
    archive_mode = on
    archive_command = 'test ! -f $WAL_ARCHIVE/%f && cp %p $WAL_ARCHIVE/%f'
    archive_timeout = ${ARCHIVE_TIMEOUT}s
    # archive_command 는 **실패 시 0 이 아닌 코드**를 반환해야 한다. 성공했다고 거짓말하면
    # PostgreSQL 이 그 WAL 을 지워 버려 복구 불가 구간이 생긴다.

    ACTION=setup 로 위 설정을 적용할 수 있습니다(ALTER SYSTEM + reload).
    단 wal_level 변경은 **재기동**이 필요합니다.
SETUP
    else
        log "PITR 준비 완료"
    fi
    return $ok
}

setup_archiving() {
    log "WAL 아카이빙 설정 적용"
    run "mkdir -p '$WAL_ARCHIVE'"
    # ALTER SYSTEM 은 postgresql.auto.conf 에 기록되므로 재기동에도 살아남는다.
    run "psql -v ON_ERROR_STOP=1 -c \"ALTER SYSTEM SET wal_level = 'replica'\""
    run "psql -v ON_ERROR_STOP=1 -c \"ALTER SYSTEM SET archive_mode = 'on'\""
    run "psql -v ON_ERROR_STOP=1 -c \"ALTER SYSTEM SET archive_command = 'test ! -f $WAL_ARCHIVE/%f && cp %p $WAL_ARCHIVE/%f'\""
    run "psql -v ON_ERROR_STOP=1 -c \"ALTER SYSTEM SET archive_timeout = '${ARCHIVE_TIMEOUT}s'\""
    run "psql -v ON_ERROR_STOP=1 -c 'SELECT pg_reload_conf()'"
    warn "wal_level / archive_mode 변경은 **서버 재기동** 후에야 적용됩니다."
    warn "재기동 후 ACTION=check 로 다시 확인하세요."
}

# ---------------------------------------------------------------- 백업

write_metric() {
    # 백업의 가장 흔한 실패 방식은 "에러가 나는 것"이 아니라 "아무도 모르게 안 도는 것"이다.
    # 마지막 성공 시각을 노출해 두면 observability/alert-rules.yml 에서
    #   time() - ecommerce_pg_backup_last_success_timestamp > 172800
    # 같은 규칙으로 잡을 수 있다.
    [ -n "$METRICS_DIR" ] || return 0
    [ "$DRY_RUN" = "1" ] && return 0
    mkdir -p "$METRICS_DIR"
    local tmp="$METRICS_DIR/.pg_backup.prom.$$"
    {
        echo "# HELP ecommerce_pg_backup_last_success_timestamp 마지막 base backup 성공 시각(유닉스 초)"
        echo "# TYPE ecommerce_pg_backup_last_success_timestamp gauge"
        echo "ecommerce_pg_backup_last_success_timestamp $1"
        echo "# HELP ecommerce_pg_backup_size_bytes 마지막 base backup 크기"
        echo "# TYPE ecommerce_pg_backup_size_bytes gauge"
        echo "ecommerce_pg_backup_size_bytes $2"
        echo "# HELP ecommerce_pg_backup_duration_seconds 마지막 base backup 소요 시간"
        echo "# TYPE ecommerce_pg_backup_duration_seconds gauge"
        echo "ecommerce_pg_backup_duration_seconds $3"
    } > "$tmp"
    mv "$tmp" "$METRICS_DIR/pg_backup.prom"   # 원자적 교체 — 반쯤 쓰인 파일을 스크랩하지 않게
}

take_base_backup() {
    local stamp target started elapsed size
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    target="$BACKUP_ROOT/base/$stamp"

    log "base backup → $target"
    run "mkdir -p '$target'"

    started="$(date +%s)"
    # -C -S: 백업 동안 필요한 WAL 이 지워지지 않도록 임시 복제 슬롯을 만든다.
    #        (슬롯 없이 뜨면 큰 DB 에서 백업 도중 WAL 이 재활용돼 복구 불가 백업이 나온다)
    # -X stream: 백업에 필요한 WAL 을 같이 받아 백업 단독으로 일관성 확보
    # --checkpoint=fast: 체크포인트를 기다리지 않고 즉시 시작
    run "pg_basebackup --pgdata='$target' --format=tar --gzip --compress=9 \
         --wal-method=stream --checkpoint=fast --progress --no-password \
         --create-slot --slot='backup_$stamp' --verbose"
    elapsed=$(( $(date +%s) - started ))

    if [ "$DRY_RUN" = "1" ]; then log "[DRY_RUN] 검증/정리 생략"; return; fi

    # 슬롯은 백업이 끝나면 지운다. 남겨 두면 그 슬롯이 요구하는 WAL 이 영원히 보존돼
    # pg_wal 디스크가 가득 찬다 — 복제 슬롯 방치는 PostgreSQL 장애의 단골이다.
    psql -v ON_ERROR_STOP=1 -c "SELECT pg_drop_replication_slot('backup_$stamp')" >/dev/null 2>&1 || \
        warn "복제 슬롯 backup_$stamp 을 지우지 못했습니다 — 수동으로 확인하세요"

    size="$(du -sb "$target" | cut -f1)"
    log "완료: $(numfmt --to=iec "$size" 2>/dev/null || echo "$size bytes"), ${elapsed}초"

    # tar 포맷은 pg_verifybackup 이 지원하지 않으므로 gzip 무결성만 확인한다.
    log "백업 무결성 확인"
    local f
    for f in "$target"/*.tar.gz; do
        [ -f "$f" ] || continue
        gzip -t "$f" || die "$f 손상 — 이 백업은 복구에 쓸 수 없습니다"
        printf '    OK %s\n' "$(basename "$f")"
    done

    echo "$stamp" > "$BACKUP_ROOT/LATEST"
    write_metric "$(date +%s)" "$size" "$elapsed"
}

prune_old() {
    [ "$DRY_RUN" = "1" ] && { log "[DRY_RUN] ${RETENTION_DAYS}일 지난 백업 정리 생략"; return; }
    log "보존 기간(${RETENTION_DAYS}일) 지난 백업 정리"

    # ⚠️ base backup 보다 오래된 WAL 만 지워야 한다. 순서를 반대로 하면
    # 남아 있는 백업이 필요로 하는 WAL 을 지워 **복구 불가능한 백업**이 된다.
    local oldest_kept
    oldest_kept="$(find "$BACKUP_ROOT/base" -maxdepth 1 -mindepth 1 -type d \
                   -mtime "-$RETENTION_DAYS" -printf '%f\n' 2>/dev/null | sort | head -1)"

    if [ -z "$oldest_kept" ]; then
        warn "보존 기간 내 백업이 없습니다 — WAL 정리를 건너뜁니다(안전 우선)"
    else
        log "  가장 오래된 보존 백업: $oldest_kept — 그보다 오래된 WAL 만 삭제"
        find "$WAL_ARCHIVE" -type f ! -newer "$BACKUP_ROOT/base/$oldest_kept" -delete 2>/dev/null || true
    fi

    find "$BACKUP_ROOT/base" -maxdepth 1 -mindepth 1 -type d -mtime "+$RETENTION_DAYS" \
        -exec rm -rf {} + 2>/dev/null || true

    local kept
    kept="$(find "$BACKUP_ROOT/base" -maxdepth 1 -mindepth 1 -type d | wc -l)"
    log "  남은 base backup: ${kept}개"
    [ "$kept" -eq 0 ] && die "백업이 하나도 남지 않았습니다 — 보존 설정을 확인하세요"
    return 0
}

case "$ACTION" in
    check)  check_pitr_ready ;;
    setup)  setup_archiving ;;
    backup)
        check_pitr_ready || warn "PITR 준비가 덜 됐습니다 — base backup 은 뜨지만 시점 복구는 불가능합니다"
        run "mkdir -p '$BACKUP_ROOT/base' '$WAL_ARCHIVE'"
        take_base_backup
        prune_old
        cat <<NEXT

>>> 백업이 실제로 **복구 가능한지**는 복원해 봐야만 안다.
    ./ops/09-restore-drill.sh    # 컨테이너에서 PITR 전 과정을 재현하고 RTO 를 측정한다
NEXT
        ;;
    *) die "알 수 없는 ACTION=$ACTION (backup|setup|check)" ;;
esac
