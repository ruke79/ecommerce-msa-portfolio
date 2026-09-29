#!/usr/bin/env bash
# 프라이머리 장애 시 레플리카 승격 (수동 failover) — docs/13 §2
#
# ⚠️ 되돌릴 수 없는 조치: 승격 후 구 프라이머리가 살아나면 스플릿 브레인이 되므로
#    구 프라이머리는 반드시 정지 상태를 유지하고, 재사용하려면 새 레플리카로 재구성해야 한다.
#
# 사용: CONFIRM=yes ./ops/promote-replica.sh
set -euo pipefail

[ "${CONFIRM:-}" = "yes" ] || {
    echo "이 스크립트는 postgres-replica 를 새 프라이머리로 승격합니다."
    echo "구 프라이머리(postgres-primary)가 확실히 정지된 상태인지 확인한 뒤"
    echo "CONFIRM=yes 로 다시 실행하세요."
    exit 1
}

echo ">>> 승격 실행"
docker exec -u postgres postgres-replica pg_ctl promote -D /var/lib/postgresql/data

sleep 2
state=$(docker exec postgres-replica psql -U product -tAc "SELECT pg_is_in_recovery()")
[ "$state" = "f" ] && echo "OK: 승격 완료 (pg_is_in_recovery=f)" || { echo "ERROR: 승격 확인 실패"; exit 1; }

docker exec postgres-replica psql -U product -q -c \
    "CREATE TEMP TABLE _rw_check(x int); INSERT INTO _rw_check VALUES (1);" \
    && echo "OK: 쓰기 검증 성공"

cat <<'NEXT'

>>> 반드시 이어서:
 1. 서비스들의 POSTGRES_HOST/포트를 새 프라이머리(구 5436)로 전환 후 재기동
    (k8s: ConfigMap POSTGRES_HOST 패치 + rollout restart)
 2. 구 프라이머리는 기동 금지. 복구하려면 데이터 디렉터리를 비우고
    replica-entrypoint 로 새 레플리카로 재편입
 3. Debezium 커넥터의 database.hostname 도 새 프라이머리로 변경 후 재시작
NEXT
