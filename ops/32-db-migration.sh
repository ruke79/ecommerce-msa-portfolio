#!/usr/bin/env bash
# 운영 액션 ㉜ — DB 마이그레이션 상태 조회·복구
#
# ── 이 스크립트가 필요한 이유 ────────────────────────────────────────────
#
# 마이그레이션 도구는 이미 있다. Flyway 가 서비스 9개 전부에서 스키마를 소유하고
# Hibernate 는 `ddl-auto: validate` 로 검증만 한다(docs/20). 마이그레이션은 **앱이 뜰 때**
# 적용된다.
#
# 없던 것은 그걸 **운영에서 다룰 방법**이다. 지금까지는:
#
#   · 배포가 DB 를 무엇으로 바꿀지 **미리 볼 방법이 없었다.** ops/28 은 이미지를 바꾸고
#     파드가 뜨면서 마이그레이션이 조용히 적용된다.
#   · 마이그레이션이 실패하면 flyway_schema_history 에 success=false 행이 남고, 그때부터
#     **그 서비스는 영영 못 뜬다**("Detected failed migration"). 고치려면 그 행을 지워야
#     하는데(flyway repair) 그럴 방법이 저장소에 없었다 — 운영자가 손으로 DELETE 를
#     짜야 했고, 잘못 지우면 이미 적용된 마이그레이션을 다시 돌린다.
#   · 파드가 CrashLoopBackOff 일 때 "어디까지 적용됐나"를 볼 곳이 없었다.
#
# Flyway CLI 를 새로 들이지 않는다. 필요한 정보는 전부 flyway_schema_history 테이블에
# 있고, 대기 중인 목록은 이 저장소의 db/migration/*.sql 과 대조하면 나온다.
#
# ── ⚠️ 롤백은 스키마를 되돌리지 않는다 ──────────────────────────────────
#
# `ops/28 --rollback` 은 **이미지만** 되돌린다. 스키마는 앞으로 간 채로 남는다. 그래서
# 어떤 배포가 컬럼을 지우거나 이름을 바꿨다면, 되돌린 옛 이미지는 Hibernate validate 에서
# 기동 실패한다 — 안전망이 있다고 믿는 순간에 없는 것이다.
#
# 규칙: **마이그레이션은 한 세대 뒤로 호환되게 쓴다**(expand-contract).
#   ① 더하기만 하는 변경을 먼저 배포한다(컬럼 추가, nullable 로)
#   ② 코드가 새 컬럼을 쓰게 되고, 옛 컬럼을 더 이상 안 읽는 버전이 배포되고 안정되면
#   ③ 그때 옛 것을 지우는 마이그레이션을 **다음 릴리스에** 낸다
#
# 그래야 ①·② 어느 지점에서든 한 버전 되돌리기가 동작한다. 지우는 문장을 쓸 수밖에 없다면
# 마이그레이션 파일에 `-- ROLLBACK-UNSAFE: <이유와 대비책>` 을 적는다
# (ConfigurationGuardrailTest B-54 가 그 표시를 요구한다).
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/32-db-migration.sh                      # 전 서비스 요약
#   ./ops/32-db-migration.sh info user-service    # 적용 이력 전체
#   ./ops/32-db-migration.sh pending              # 배포하면 무엇이 적용되나 (배포 전에)
#   ./ops/32-db-migration.sh drift                # 적용된 파일이 수정됐는지 (배포 전에)
#   ./ops/32-db-migration.sh repair user-service  # 실패 행 제거 (되돌릴 수 없다 — 묻는다)
#
# 종료 코드: 0=정상 · 1=실패한 마이그레이션이 있음 · 2=사용법 오류
set -uo pipefail

ACTION="${1:-summary}"
SERVICE="${2:-}"

PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
YES="${YES:-0}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ok()   { echo "  ✅ $1"; }
bad()  { echo "  ❌ $1"; }
info() { echo "  ℹ️  $1"; }
warn() { echo "  ⚠️  $1" >&2; }
die()  { echo "ERROR: $1" >&2; exit 2; }
log()  { echo; echo "▶ $1"; }

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"

# 서비스 → 데이터베이스. application-local.yml 의 jdbc URL 에서 읽는다 — 여기 또 적으면
# 두 벌이 되고 반드시 어긋난다.
db_of() {
    grep -hoP 'url:\s*jdbc:postgresql://[^/]+/\K[A-Za-z0-9_]+' \
        "$1/src/main/resources/application-local.yml" 2>/dev/null | head -1
}

SERVICES="user-service catalog-service cart-service order-service pay-service
          coupon-service chat-service notification-service delivery-service"

# 파드 안에서 psql. -A -t 로 값만, -F 로 구분자 고정.
q() {  # q <db> <sql>
    kubectl -n "$PG_NS" exec "deploy/$PG_DEPLOY" -- \
        psql -U "$PG_SUPERUSER" -d "$1" -At -F'|' -c "$2" 2>/dev/null
}

kubectl -n "$PG_NS" get "deploy/$PG_DEPLOY" >/dev/null 2>&1 \
    || die "$PG_NS/$PG_DEPLOY 를 찾을 수 없습니다 (PG_NS·PG_DEPLOY 로 지정하세요)"

# 저장소가 들고 있는 마이그레이션 버전들(정렬).
local_versions() {
    ls "$1"/src/main/resources/db/migration/V*__*.sql 2>/dev/null \
        | sed 's|.*/V\([0-9.]*\)__.*|\1|' | sort -V
}

# 저장소에는 있는데 아직 적용되지 않은 버전.
#
# ⚠️ comm 을 쓰지 않는다. comm 은 양쪽이 **같은 정렬 규칙**이어야 하는데 버전은 1,2,10 처럼
#    숫자라 `sort -V`(자연순)와 comm 이 기대하는 사전순이 어긋난다 — "file 1 is not in
#    sorted order" 로 조용히 틀린 답을 낸다. 집합 차이는 정렬 없이 구한다.
pending_versions() {  # pending_versions <서비스> <db>
    local applied; applied=$(q "$2" "select version from flyway_schema_history where version is not null")
    if [ -z "$applied" ]; then
        local_versions "$1"
    else
        local_versions "$1" | grep -vxF -f <(echo "$applied") || true
    fi
}

# ---------------------------------------------------------------- 요약

summary() {
    log "마이그레이션 상태 (DB: $PG_NS/$PG_DEPLOY)"
    printf '  %-22s %-20s %-6s %-6s %s\n' 서비스 DB 적용 저장소 상태
    printf '  %-22s %-20s %-6s %-6s %s\n' ---------------------- -------------------- ------ ------ ------
    local rc=0
    for s in $SERVICES; do
        [ -d "$s" ] || continue
        local db; db=$(db_of "$s")
        [ -n "$db" ] || { printf '  %-22s %s\n' "$s" "jdbc URL 을 읽지 못함"; continue; }

        local applied failed have state
        applied=$(q "$db" "select count(*) from flyway_schema_history where success")
        failed=$(q "$db" "select count(*) from flyway_schema_history where not success")
        have=$(local_versions "$s" | grep -c .)

        if [ -z "$applied" ]; then
            state="이력 테이블 없음(아직 한 번도 안 뜸)"
        elif [ "${failed:-0}" -gt 0 ]; then
            state="❌ 실패 ${failed}건 — repair 필요"
            rc=1
        elif [ "$applied" -lt "$have" ]; then
            state="대기 $((have - applied))건"
        else
            state="최신"
        fi
        printf '  %-22s %-20s %-6s %-6s %s\n' "$s" "$db" "${applied:-–}" "$have" "$state"
    done

    if [ "$rc" = "1" ]; then
        echo
        warn "실패한 마이그레이션이 있으면 그 서비스는 기동하지 못합니다"
        warn "  원인 확인:  $0 info <서비스>"
        warn "  복구:       $0 repair <서비스>"
    fi
    return $rc
}

# ---------------------------------------------------------------- 상세

info_one() {
    local s="$1" db; db=$(db_of "$s")
    [ -n "$db" ] || die "$s 의 DB 를 알 수 없습니다"

    log "$s ($db) 적용 이력"
    local rows
    rows=$(q "$db" "select installed_rank, version, description, success,
                           to_char(installed_on,'YYYY-MM-DD HH24:MI'), execution_time
                    from flyway_schema_history order by installed_rank")
    if [ -z "$rows" ]; then
        info "이력이 없습니다 — 이 서비스가 아직 한 번도 뜨지 않았거나 DB 가 다릅니다"
        return 0
    fi

    printf '  %-5s %-8s %-38s %-6s %-17s %s\n' '#' 버전 설명 성공 적용시각 ms
    local rc=0
    while IFS='|' read -r rank version desc success at ms; do
        [ -n "$rank" ] || continue
        local mark="✅"
        if [ "$success" != "t" ]; then mark="❌"; rc=1; fi
        printf '  %-5s %-8s %-38s %-6s %-17s %s\n' \
            "$rank" "${version:--}" "${desc:0:38}" "$mark" "$at" "$ms"
    done <<< "$rows"

    # 저장소에만 있는 것 = 다음 기동에 적용될 것.
    local pending
    pending=$(pending_versions "$s" "$db")
    if [ -n "$pending" ]; then
        echo
        info "다음 기동에 적용될 것: $(echo "$pending" | tr '\n' ' ')"
    fi
    return $rc
}

# ---------------------------------------------------------------- 대기 목록

pending_all() {
    log "배포하면 DB 에 적용될 마이그레이션"
    local any=0
    for s in $SERVICES; do
        [ -d "$s" ] || continue
        local db; db=$(db_of "$s")
        [ -n "$db" ] || continue
        local applied pending
        pending=$(pending_versions "$s" "$db")
        [ -n "$pending" ] || continue
        any=1
        echo
        echo "  $s ($db)"
        for v in $pending; do
            local f; f=$(ls "$s"/src/main/resources/db/migration/V${v}__*.sql 2>/dev/null | head -1)
            printf '    V%-6s %s\n' "$v" "$(basename "${f:-?}")"
            # 되돌리기를 깨뜨리는 문장이 있으면 배포 전에 알아야 한다.
            if [ -n "$f" ] && grep -qiE '^\s*(ALTER TABLE .*(DROP COLUMN|DROP CONSTRAINT|RENAME)|DROP TABLE)' "$f"; then
                warn "      ↑ 되돌리기를 깨뜨릴 수 있는 문장이 있습니다 — 옛 이미지가 validate 에서 죽습니다"
            fi
        done
    done
    [ "$any" = "1" ] || info "대기 중인 마이그레이션이 없습니다 — 이 배포는 스키마를 바꾸지 않습니다"
}

# ---------------------------------------------------------------- 체크섬 드리프트

# Flyway 의 마이그레이션 체크섬을 재현한다.
#
# 알고리즘: 파일을 **줄 단위로** 읽어 각 줄의 UTF-8 바이트로 CRC32 를 갱신한다. 줄바꿈은
# 포함하지 않고, 결과를 부호 있는 32비트로 본다(Flyway 의 AbstractLoadableResource 참조).
# 이 저장소의 마이그레이션 6개로 Java(java.util.zip.CRC32) 구현과 값이 일치함을 확인했다.
checksum_of() {
    python3 - "$1" <<'PYEOF'
import sys, zlib
c = 0
with open(sys.argv[1], encoding='utf-8-sig', newline=None) as f:
    for line in f:
        c = zlib.crc32(line.rstrip('\n').encode('utf-8'), c)
print(c - (1 << 32) if c >= (1 << 31) else c)
PYEOF
}

# 이미 적용된 마이그레이션 파일을 고치면, 다음 기동에서 그 서비스가 죽는다:
#
#     Migration checksum mismatch for migration version N
#
# 주석 한 줄만 더해도 그렇다 — Flyway 는 파일 **내용**으로 체크섬을 계산한다. 이 검사를
# 만들면서 실제로 그럴 뻔했다(이미 적용된 V1 넷에 설명을 달려다가).
drift_all() {
    log "적용된 마이그레이션 파일이 수정됐는지 (수정됐다면 다음 기동에서 죽는다)"

    command -v python3 >/dev/null || {
        warn "python3 가 없어 체크섬을 계산할 수 없습니다 — 이 검사는 건너뜁니다"
        return 0
    }

    local rc=0 checked=0
    for s in $SERVICES; do
        [ -d "$s" ] || continue
        local db; db=$(db_of "$s")
        [ -n "$db" ] || continue

        local rows; rows=$(q "$db" "select version, checksum from flyway_schema_history
                                    where success and version is not null and checksum is not null")
        [ -n "$rows" ] || continue

        while IFS='|' read -r version applied_sum; do
            [ -n "$version" ] || continue
            local f; f=$(ls "$s"/src/main/resources/db/migration/V${version}__*.sql 2>/dev/null | head -1)
            if [ -z "$f" ]; then
                bad "$s V$version — 적용됐는데 저장소에 파일이 없습니다(지웠거나 이름이 바뀌었습니다)"
                rc=1; continue
            fi
            checked=$((checked+1))
            local now; now=$(checksum_of "$f")
            if [ "$now" != "$applied_sum" ]; then
                bad "$s V$version $(basename "$f") — 적용 후 파일이 바뀌었습니다 (DB $applied_sum ≠ 파일 $now)"
                rc=1
            fi
        done <<< "$rows"
    done

    if [ "$rc" = "0" ]; then
        ok "적용된 마이그레이션 ${checked}개 모두 파일과 일치합니다"
    else
        echo
        warn "이 상태로 배포하면 해당 서비스가 'Migration checksum mismatch' 로 기동하지 못합니다."
        warn "  적용된 마이그레이션은 **고치지 않는다**가 원칙입니다 — 변경은 새 버전 파일로."
        warn "  되돌릴 수 없는 상황이라면(파일이 이미 머지됨) 그 환경에서 checksum 을 갱신해야"
        warn "  하고, 그건 Flyway repair 의 영역입니다. 먼저 파일을 원래대로 되돌리세요."
    fi
    return $rc
}

# ---------------------------------------------------------------- 복구

repair_one() {
    local s="$1" db; db=$(db_of "$s")
    [ -n "$db" ] || die "$s 의 DB 를 알 수 없습니다"

    log "$s ($db) 실패한 마이그레이션 정리"

    local rows
    rows=$(q "$db" "select installed_rank, version, description
                    from flyway_schema_history where not success order by installed_rank")
    if [ -z "$rows" ]; then
        ok "실패한 마이그레이션이 없습니다 — 할 일이 없습니다"
        return 0
    fi

    echo "  지울 행:"
    echo "$rows" | while IFS='|' read -r rank version desc; do
        [ -n "$rank" ] && printf '    #%-4s V%-8s %s\n' "$rank" "${version:--}" "$desc"
    done

    cat <<'EOF'

  ⚠️ 이 행들을 지우면 Flyway 는 그 마이그레이션을 **다시 시도**합니다.
     그 전에 반드시 확인하세요 — 실패한 마이그레이션이 **일부만 적용됐을 수 있습니다**:

       · PostgreSQL 은 DDL 도 트랜잭션이라 대개 통째로 롤백된다. 그러면 그냥 다시 돌려도 된다.
       · 다만 CREATE INDEX CONCURRENTLY 처럼 트랜잭션 밖에서 도는 문장은 남는다.
       · 여러 문장이 든 마이그레이션이 중간에서 죽었다면 앞 문장의 결과가 남아 있을 수 있다.

     남은 것이 있으면 손으로 정리한 뒤 repair 하세요. 그러지 않으면 재시도가
     "already exists" 로 다시 죽습니다.
EOF

    if [ "$YES" != "1" ]; then
        printf '\n  계속하려면 정확히 repair 라고 입력하세요: '
        local answer; read -r answer
        [ "$answer" = "repair" ] || { info "취소했습니다"; return 0; }
    fi

    # 실패 행만 지운다. 성공 행은 건드리지 않는다 — 지우면 이미 적용된 것을 다시 돌린다.
    local deleted
    deleted=$(q "$db" "delete from flyway_schema_history where not success")
    ok "실패 행을 제거했습니다. 파드를 재시작하면 다시 시도합니다:"
    echo "      kubectl -n ecommerce rollout restart deploy/$s"
}

# ---------------------------------------------------------------- 진입점

case "$ACTION" in
    summary) summary ;;
    info)    [ -n "$SERVICE" ] || die "서비스를 지정하세요: $0 info <서비스>"
             [ -d "$SERVICE" ] || die "그런 서비스가 없습니다: $SERVICE"
             info_one "$SERVICE" ;;
    pending) pending_all ;;
    drift)   drift_all ;;
    repair)  [ -n "$SERVICE" ] || die "서비스를 지정하세요: $0 repair <서비스>"
             [ -d "$SERVICE" ] || die "그런 서비스가 없습니다: $SERVICE"
             repair_one "$SERVICE" ;;
    -h|--help) sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//' ;;
    *)       die "알 수 없는 동작: $ACTION (summary|info|pending|drift|repair)" ;;
esac
