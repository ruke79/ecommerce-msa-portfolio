#!/usr/bin/env bash
# 운영 액션 ⑪ — DB 포트 외부 노출 점검 (docs/13 §1 체크리스트 5번의 실행형)
#
# 배경: 커넥터 JSON 에 DB 호스트명이 커밋돼 있었다. 파일에서는 제거했지만(docs/18 §6.1)
#       **git 이력의 공개는 되돌릴 수 없다** — 그 주소를 아는 사람은 이미 존재한다고 가정해야 한다.
#       남은 질문은 하나뿐이다: "그 호스트의 DB 포트가 공인망에서 열려 있는가?"
#
# ⚠️ 이 스크립트는 반드시 **외부 시점**에서 실행해야 의미가 있다.
#     - 사내망/VPN 안에서 돌리면 "열려 있음"이 나와도 외부 노출이 아닐 수 있고,
#     - DB 와 같은 호스트에서 돌리면 항상 열려 있는 것으로 보인다.
#     휴대폰 테더링, 집 회선, 무료 클라우드 셸 등 인프라 밖 어디서든 실행할 것.
#
# 사용:
#   DB_HOST=<그 호스트> ./ops/11-check-db-exposure.sh
#   DB_PORTS="5432,5435,27017,6379,9200,29092" ...   # 점검할 포트 목록(기본값에 전 스토리지 포함)
#   TIMEOUT=5 ...
#
# 종료 코드: 0 = 전부 닫힘(양호), 1 = 하나라도 열림(조치 필요), 2 = 판단 불가
set -uo pipefail

DB_HOST="${DB_HOST:?DB_HOST 를 지정하세요 (git 이력에 노출됐던 그 호스트)}"
# 이 저장소가 실제로 쓰는 스토리지 포트 전부가 기본값이다.
# 노출 사고는 한 포트로 끝나지 않는다 — 같은 방화벽 실수는 보통 이웃 포트에도 있다.
DB_PORTS="${DB_PORTS:-5432,5435,27017,6379,9200,29092,8761}"
TIMEOUT="${TIMEOUT:-5}"

open=0; closed=0; unknown=0

echo ">>> 점검 대상: $DB_HOST  (외부 시점에서 실행 중인지 다시 확인하세요)"
echo ">>> 현재 위치의 공인 IP: $(curl -s --max-time 5 https://ifconfig.me 2>/dev/null || echo '확인 불가')"
echo

IFS=',' read -ra PORTS <<< "$DB_PORTS"
for port in "${PORTS[@]}"; do
    port="$(echo "$port" | tr -d '[:space:]')"
    # bash /dev/tcp 는 어디에나 있다. nc/nmap 이 없어도 돈다.
    if timeout "$TIMEOUT" bash -c "exec 3<>/dev/tcp/$DB_HOST/$port" 2>/dev/null; then
        exec 3<&- 3>&- 2>/dev/null
        echo "  🔴 $port  열려 있음 — 외부에서 TCP 연결이 성립합니다"
        open=$((open+1))
    else
        # 거부(즉시 실패)와 타임아웃(필터링/드롭)을 구분할 필요는 없다 — 둘 다 "닫힘"이다.
        echo "  ✅ $port  닫힘/필터링됨"
        closed=$((closed+1))
    fi
done

echo
if [ "$open" -gt 0 ]; then
    cat <<ACTION
결론: 🔴 ${open}개 포트가 공인망에 열려 있습니다.

지금 해야 할 일 (순서대로):
 1. 방화벽/보안그룹에서 해당 포트를 **차단하거나 허용 IP 를 화이트리스트로 제한**
    - 클라우드: 보안그룹 인바운드 규칙에서 0.0.0.0/0 제거
    - 자체 호스트: ufw deny <port> / iptables, 또는 라우터 포트포워딩 제거
 2. 차단 후 이 스크립트를 다시 돌려 닫혔는지 확인
 3. 그 포트가 열려 있던 기간의 DB 접속 로그 점검:
      PostgreSQL: log_connections=on 이었다면 pg_log 에서 낯선 IP 검색
 4. 아직 안 했다면 비밀번호 로테이션(ops/01) — 포트가 열려 있었다면 더는 선택이 아닙니다
ACTION
    exit 1
else
    echo "결론: ✅ 점검한 ${closed}개 포트 전부 외부에서 닫혀 있습니다."
    echo "      (단, 이 결과는 '지금, 이 위치에서' 의 사실입니다. 방화벽 변경 후에는 재점검하세요.)"
fi
