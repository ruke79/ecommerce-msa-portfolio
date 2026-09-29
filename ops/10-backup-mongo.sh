#!/usr/bin/env bash
# 운영 액션 ⑩ — MongoDB 백업 + 복원 검증 (docs/15 §3, C3 등급)
#
# 대상: chat-service 의 대화 기록. 손실 허용(RPO) 24시간, 복구 목표(RTO) 8시간 — C1(금전)보다
#       완만하지만 **재생성이 불가능한** 데이터다. Elasticsearch 색인처럼 다시 만들 수 없다.
#
# 이 스크립트는 백업만 하지 않는다. 뜬 덤프를 **빈 인스턴스에 실제로 복원해 문서 수를 대조**한다.
# 복원해 보지 않은 덤프는 백업이 아니다(ops/09 와 같은 원칙).
#
# 사용:
#   MONGO_URI='mongodb://admin:pw@localhost:27017/?authSource=admin' \
#     BACKUP_ROOT=/var/backups/mongo ./ops/10-backup-mongo.sh
#   ACTION=restore RESTORE_FROM=/var/backups/mongo/20260728T031500Z TARGET_URI=... ./ops/10-backup-mongo.sh
#   VERIFY=0 ...        # 복원 검증 생략 (빠르지만 권장하지 않음)
#   DRY_RUN=1 ...
set -euo pipefail

ACTION="${ACTION:-backup}"          # backup | restore
MONGO_URI="${MONGO_URI:?MONGO_URI 를 지정하세요}"
DB_NAME="${DB_NAME:-chat}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/mongodb}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
VERIFY="${VERIFY:-1}"
DRY_RUN="${DRY_RUN:-0}"
METRICS_DIR="${METRICS_DIR:-}"
# 검증용 임시 인스턴스. docker 가 없으면 VERIFY=0 으로 두고 별도 환경에서 확인해야 한다.
MONGO_IMAGE="${MONGO_IMAGE:-${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX:-}mongo:7}"

RESTORE_FROM="${RESTORE_FROM:-}"
TARGET_URI="${TARGET_URI:-}"

log()  { printf '\n>>> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
run()  { if [ "$DRY_RUN" = "1" ]; then printf '[DRY_RUN] %s\n' "$*"; else eval "$@"; fi; }

command -v mongodump  >/dev/null || die "mongodump 이 필요합니다 (mongodb-database-tools)"
command -v mongorestore >/dev/null || die "mongorestore 가 필요합니다"

mongo_count() {
    # mongosh 가 없는 환경도 있어 --eval 결과를 최소한으로 파싱한다.
    local uri="$1" db="$2"
    mongosh "$uri" --quiet --eval \
        "db.getSiblingDB('$db').getCollectionNames().reduce((n,c)=>n+db.getSiblingDB('$db')[c].countDocuments({}),0)" \
        2>/dev/null | tr -d '[:space:]'
}

write_metric() {
    [ -n "$METRICS_DIR" ] || return 0
    [ "$DRY_RUN" = "1" ] && return 0
    mkdir -p "$METRICS_DIR"
    local tmp="$METRICS_DIR/.mongo_backup.prom.$$"
    {
        echo "# HELP ecommerce_mongo_backup_last_success_timestamp 마지막 mongodump 성공 시각"
        echo "# TYPE ecommerce_mongo_backup_last_success_timestamp gauge"
        echo "ecommerce_mongo_backup_last_success_timestamp $1"
        echo "# HELP ecommerce_mongo_backup_documents 백업된 문서 수"
        echo "# TYPE ecommerce_mongo_backup_documents gauge"
        echo "ecommerce_mongo_backup_documents $2"
    } > "$tmp"
    mv "$tmp" "$METRICS_DIR/mongo_backup.prom"
}

do_restore() {
    [ -n "$RESTORE_FROM" ] || die "ACTION=restore 에는 RESTORE_FROM 이 필요합니다"
    [ -n "$TARGET_URI" ]   || die "ACTION=restore 에는 TARGET_URI 가 필요합니다"
    [ -d "$RESTORE_FROM" ] || die "$RESTORE_FROM 이 없습니다"

    # 복원은 되돌릴 수 없다. 운영 URI 로 실수 실행하는 사고를 막는다.
    if [ "$DRY_RUN" != "1" ]; then
        printf '대상: %s\n' "$(printf '%s' "$TARGET_URI" | sed -E 's#://[^@]*@#://***@#')"
        read -r -p "이 인스턴스의 '$DB_NAME' 데이터를 덮어씁니다. 계속할까요? (yes 입력): " ok
        [ "$ok" = "yes" ] || die "취소됨"
    fi
    run "mongorestore --uri='$TARGET_URI' --drop --gzip --dir='$RESTORE_FROM'"
    log "복원 완료"
}

if [ "$ACTION" = "restore" ]; then do_restore; exit 0; fi

# ---------------------------------------------------------------- 백업

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$BACKUP_ROOT/$stamp"

log "1/4  mongodump → $target"
run "mkdir -p '$target'"
# --oplog: 덤프 도중의 변경을 oplog 로 함께 받아 **시점 일관성**을 확보한다.
#          없으면 컬렉션마다 다른 시각의 스냅샷이 섞여, 복원된 대화가 앞뒤가 안 맞을 수 있다.
#          (단독 인스턴스에는 oplog 가 없으므로 실패하면 --oplog 없이 재시도한다)
if ! run "mongodump --uri='$MONGO_URI' --db='$DB_NAME' --gzip --oplog --out='$target' 2>/dev/null"; then
    info "oplog 를 쓸 수 없습니다(레플리카셋이 아님) — 시점 일관성 없이 덤프합니다"
    info "  → 운영에서는 레플리카셋 구성을 권장합니다(docs/15 §3)"
    run "mongodump --uri='$MONGO_URI' --db='$DB_NAME' --gzip --out='$target'"
fi

if [ "$DRY_RUN" = "1" ]; then log "[DRY_RUN] 검증/정리 생략"; exit 0; fi

log "2/4  덤프 내용 확인"
files="$(find "$target" -name '*.bson.gz' | wc -l)"
[ "$files" -gt 0 ] || die "bson 파일이 하나도 없습니다 — 덤프가 비었습니다"
info "컬렉션 ${files}개, 크기 $(du -sh "$target" | cut -f1)"
# gzip 무결성. 손상된 덤프를 '성공'으로 기록하면 정작 필요할 때 못 쓴다.
find "$target" -name '*.gz' -exec gzip -t {} \; || die "덤프가 손상되었습니다"
info "gzip 무결성 OK"

SOURCE_DOCS="$(mongo_count "$MONGO_URI" "$DB_NAME")"
info "원본 문서 수: ${SOURCE_DOCS:-확인불가}"

# ---------------------------------------------------------------- 복원 검증

if [ "$VERIFY" = "1" ] && command -v docker >/dev/null 2>&1; then
    log "3/4  빈 인스턴스에 실제로 복원해 대조"
    probe="mongo-restore-probe-$$"
    trap 'docker rm -f "$probe" >/dev/null 2>&1 || true' EXIT
    docker run -d --name "$probe" -P "$MONGO_IMAGE" >/dev/null
    port="$(docker port "$probe" 27017/tcp | head -1 | sed 's/.*://')"
    for i in $(seq 1 60); do
        docker exec "$probe" mongosh --quiet --eval 'db.runCommand({ping:1})' >/dev/null 2>&1 && break
        sleep 1
    done

    mongorestore --uri="mongodb://localhost:$port" --gzip --dir="$target" --quiet
    RESTORED_DOCS="$(mongo_count "mongodb://localhost:$port" "$DB_NAME")"
    info "복원된 문서 수: ${RESTORED_DOCS:-확인불가}"

    if [ -n "$SOURCE_DOCS" ] && [ -n "$RESTORED_DOCS" ]; then
        [ "$SOURCE_DOCS" = "$RESTORED_DOCS" ] \
            && info "OK  문서 수 일치 — 이 덤프는 복원 가능합니다" \
            || die "문서 수 불일치 ($SOURCE_DOCS ≠ $RESTORED_DOCS) — 이 백업은 신뢰할 수 없습니다"
    else
        info "mongosh 가 없어 개수 대조를 건너뜁니다 (복원 자체는 성공)"
    fi
    docker rm -f "$probe" >/dev/null 2>&1 || true
    trap - EXIT
else
    log "3/4  복원 검증 생략 (VERIFY=$VERIFY, docker 필요)"
fi

# ---------------------------------------------------------------- 정리

log "4/4  보존 기간(${RETENTION_DAYS}일) 정리"
find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d -mtime "+$RETENTION_DAYS" -exec rm -rf {} + 2>/dev/null || true
kept="$(find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d | wc -l)"
info "남은 백업 ${kept}개"
[ "$kept" -eq 0 ] && die "백업이 하나도 남지 않았습니다 — 보존 설정을 확인하세요"

echo "$stamp" > "$BACKUP_ROOT/LATEST"
write_metric "$(date +%s)" "${SOURCE_DOCS:-0}"
log "완료: $target"
