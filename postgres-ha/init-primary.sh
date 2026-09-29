#!/bin/bash
# 프라이머리 초기화(공식 postgres 이미지의 docker-entrypoint-initdb.d 에서 1회 실행):
# 스트리밍 복제용 계정 생성 + 복제 접속 허용.
set -e

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" <<-SQL
    CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '${REPLICATION_PASSWORD}';
SQL

# 복제 프로토콜 접속 허용 (운영에서는 all 대신 레플리카 서브넷/호스트로 좁힐 것)
echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"
