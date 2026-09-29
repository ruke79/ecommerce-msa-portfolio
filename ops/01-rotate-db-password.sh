#!/usr/bin/env bash
# 운영 액션 ① — DB 비밀번호 로테이션 (docs/13 §1 런북의 실행형)
#
# 배경: 이전 커밋에 DB 비밀번호가 평문으로 노출되어 git 이력에 영구히 남아 있다.
#       파일에서 지운 것은 재발 방지일 뿐 — 유일한 실질 해결은 이 로테이션이다.
#
# 사용:
#   PGHOST=<db호스트> PGPORT=5435 PGADMIN_USER=postgres TARGET_ROLE=product \
#     ./ops/01-rotate-db-password.sh
#   (새 비밀번호는 안전하게 프롬프트로 입력받는다. NEW_PASSWORD env로도 주입 가능)
#   DRY_RUN=1  → 실제 변경 없이 수행할 명령만 출력
set -euo pipefail

PGHOST="${PGHOST:?PGHOST 를 지정하세요 (예: localhost)}"
PGPORT="${PGPORT:-5435}"
PGADMIN_USER="${PGADMIN_USER:-postgres}"
TARGET_ROLE="${TARGET_ROLE:-product}"
DRY_RUN="${DRY_RUN:-0}"

if [ -z "${NEW_PASSWORD:-}" ]; then
    read -r -s -p "새 비밀번호($TARGET_ROLE): " NEW_PASSWORD; echo
    read -r -s -p "다시 입력: " NEW_PASSWORD2; echo
    [ "$NEW_PASSWORD" = "$NEW_PASSWORD2" ] || { echo "ERROR: 비밀번호 불일치"; exit 1; }
fi
[ ${#NEW_PASSWORD} -ge 12 ] || { echo "ERROR: 12자 이상을 권장합니다"; exit 1; }

echo ">>> 대상: role '$TARGET_ROLE' @ $PGHOST:$PGPORT"
if [ "$DRY_RUN" = "1" ]; then
    echo "[DRY_RUN] psql -h $PGHOST -p $PGPORT -U $PGADMIN_USER -c \"ALTER ROLE $TARGET_ROLE WITH PASSWORD '****'\""
else
    read -r -p "정말 로테이션할까요? 서비스 재기동 전까지 인증 실패가 발생합니다 (yes 입력): " ok
    [ "$ok" = "yes" ] || { echo "취소됨"; exit 1; }
    # :'var' 바인딩으로 셸 이스케이프/SQL 인젝션 없이 안전하게 전달.
    # ⚠️ SQL 은 stdin 으로 넘긴다 — psql 의 -v 변수 치환은 stdin/-f 로 읽는 스크립트에서만
    # 동작하고 -c 명령 문자열에서는 동작하지 않는다. (-c 로 쓰면 리터럴 ':' 로 남아
    # 'syntax error at or near ":"' 가 난다. 컨테이너 리허설에서 실제로 잡힌 버그다.)
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGADMIN_USER" -v ON_ERROR_STOP=1 \
         -v role="$TARGET_ROLE" -v pw="$NEW_PASSWORD" \
         <<< "ALTER ROLE :\"role\" WITH PASSWORD :'pw';"
    echo "OK: 비밀번호 변경 완료"
    # 새 비밀번호로 로그인 검증
    PGPASSWORD="$NEW_PASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$TARGET_ROLE" -d postgres -tAc "SELECT 1" >/dev/null \
        && echo "OK: 새 비밀번호 로그인 검증 성공"
fi

cat <<'CHECKLIST'

>>> 남은 전파 체크리스트 (순서대로):
 1. 각 서비스 환경변수 LOCAL_DB_PASSWORD 교체 (compose env / systemd / CI 시크릿)
 2. k8s 사용 시:  kubectl -n ecommerce create secret generic ecommerce-secrets \
                    --from-literal=LOCAL_DB_PASSWORD='<새값>' ... --dry-run=client -o yaml | kubectl apply -f -
                  이후 kubectl -n ecommerce rollout restart deploy
 3. Kafka Connect 워커의 /etc/kafka-connect/secrets/connect-secrets.properties 의 db-password 교체
    → 커넥터 재시작 (POST /connectors/<name>/restart)
 4. Redis 가 같은 비밀번호를 쓰고 있었다면(설정상 LOCAL_DB_PASSWORD 재사용) requirepass 도 함께 로테이션
 5. 저장소에서는 호스트명을 뺐지만(커넥터 JSON → connect-secrets 의 db-host) **이미 공개된 git
    이력은 되돌릴 수 없다.** 그 DB 호스트가 공인망에서 5435 포트를 열고 있는지 방화벽/보안그룹을
    반드시 점검할 것 — 포트가 인터넷에 열려 있으면 비밀번호 로테이션은 시간을 벌 뿐이다.
 6. (선택) MySQL 병행 환경이면 동일 계정 로테이션
CHECKLIST
