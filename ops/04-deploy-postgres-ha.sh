#!/usr/bin/env bash
# 운영 액션 ④ — PostgreSQL 프라이머리-레플리카 배포 + 검증 (docs/13 §2)
#
# 사용:
#   POSTGRES_PASSWORD=... REPLICATION_PASSWORD=... ./ops/04-deploy-postgres-ha.sh
#   DRY_RUN=1 → 명령만 출력
# 승격(failover)은 별도의 ops/promote-replica.sh 사용.
set -euo pipefail

: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD 필요}"
: "${REPLICATION_PASSWORD:?REPLICATION_PASSWORD 필요}"
DRY_RUN="${DRY_RUN:-0}"
COMPOSE=(docker compose -f docker-compose-postgres-ha.yml)
DBS=(userdb cartdb productdb metadb coupondb orderdb paydb chatdb)

run() { if [ "$DRY_RUN" = "1" ]; then echo "[DRY_RUN] $*"; else "$@"; fi }

echo ">>> 1) 기동"
run "${COMPOSE[@]}" up -d

if [ "$DRY_RUN" != "1" ]; then
    echo ">>> 2) 프라이머리 healthy 대기"
    for _ in $(seq 1 30); do
        docker exec postgres-primary pg_isready -U product >/dev/null 2>&1 && break
        sleep 2
    done
    docker exec postgres-primary pg_isready -U product

    echo ">>> 3) 서비스별 데이터베이스 생성(멱등)"
    for db in "${DBS[@]}"; do
        docker exec postgres-primary psql -U product -tAc \
            "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 || \
            docker exec postgres-primary psql -U product -c "CREATE DATABASE $db OWNER product"
        echo "  OK: $db"
    done

    echo ">>> 4) 복제 상태 검증"
    echo "  - 프라이머리 pg_stat_replication (state=streaming 이어야 함):"
    docker exec postgres-primary psql -U product -c \
        "SELECT application_name, state, sync_state FROM pg_stat_replication"
    echo "  - 레플리카 recovery 모드 (t 여야 함):"
    docker exec postgres-replica psql -U product -tAc "SELECT pg_is_in_recovery()"

    echo ">>> 5) 왕복 검증: 프라이머리 쓰기 → 레플리카 읽기"
    docker exec postgres-primary psql -U product -q -c \
        "CREATE TABLE IF NOT EXISTS _ha_check(ts timestamptz); INSERT INTO _ha_check VALUES (now());"
    sleep 2
    docker exec postgres-replica psql -U product -tAc "SELECT count(*) FROM _ha_check" \
        && echo "OK: 스트리밍 복제 동작 확인"
    docker exec postgres-primary psql -U product -q -c "DROP TABLE _ha_check;"

    cat <<'NEXT'

>>> 다음 단계:
 - 서비스 접속: 쓰기/일반 → 5435(프라이머리), 읽기 전용 분산 → 5436(레플리카)
   ⚠️ 방금 쓴 데이터를 읽는 경로(read-your-writes)는 반드시 5435 로 (docs/09 §5.2)
 - 복제 지연 모니터링: SELECT now()-pg_last_xact_replay_timestamp() (레플리카에서)
 - 장애 시 승격: ./ops/promote-replica.sh
NEXT
fi
