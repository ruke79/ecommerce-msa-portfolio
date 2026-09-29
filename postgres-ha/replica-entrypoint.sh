#!/bin/bash
# 레플리카 엔트리포인트: 데이터 디렉터리가 비어 있으면 프라이머리에서 pg_basebackup 으로
# 초기 복제본을 만들고(-R: standby.signal + primary_conninfo 자동 기록) 스탠바이로 기동한다.
# 이 절차는 로컬 PostgreSQL 16.13 에서 복제/승격까지 실검증되었다 (docs/13 §2).
set -e

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "[replica] empty PGDATA — running pg_basebackup from ${PRIMARY_HOST}..."
    until pg_isready -h "${PRIMARY_HOST}" -p 5432 -U "${POSTGRES_USER}"; do
        echo "[replica] waiting for primary..."
        sleep 2
    done
    PGPASSWORD="${REPLICATION_PASSWORD}" pg_basebackup \
        -h "${PRIMARY_HOST}" -p 5432 -U replicator \
        -D "$PGDATA" -R -X stream
    chmod 0700 "$PGDATA"
fi

# 이후는 공식 이미지 기본 엔트리포인트로 위임 (스탠바이 모드는 standby.signal 이 결정)
exec docker-entrypoint.sh postgres
