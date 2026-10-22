#!/usr/bin/env bash
# 운영 액션 56 — DLT 재처리: DLT 에 격리된 레코드를 원래 토픽으로 바이트 그대로 다시 넣는다 (docs/11 §3)
# [JA] 運用アクション 56 — DLT 再処理: DLT に隔離されたレコードを元のトピックへバイトのまま戻す
#
# 원인을 고친 뒤(이미지 배포 · 데이터 수정) 그 레코드를 다시 처리하게 하는 길이다. 2026-10-22 실습 게임데이
# (poison-message, docs/67 §9.2)에서 '문서만 보고 재처리' 가 콘솔 도구로는 안 됐다 — 값을 줄 단위 글자로 다뤄
# Avro(바이너리) 값이 깨진다. 이 스크립트는 ops/tools/DltReplay.java 를 Kafka 파드에 넣고 그 파드의 Java · kafka-clients
# 로 돌린다(cp-kafka 7.6 = Java 11, 단일 파일 실행) — 이 기계에 Java · Kafka 도구가 없어도 된다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/56-dlt-replay.sh list order.DLT                 # 파티션마다 최근 20건: 자리 · 키 · 크기 · 원래 자리 · 실패한 그룹 · 예외
#   ./ops/56-dlt-replay.sh replay order.DLT 0 5           # 0번 파티션 오프셋 5 — 무엇을 보낼지만 보인다
#   ./ops/56-dlt-replay.sh replay order.DLT 0 5-9         # 5 ~ 9
#   APPLY=1 ./ops/56-dlt-replay.sh replay order.DLT 0 5   # 실제로 보낸다
#
#   환경 변수:
#     APPLY=1            보낸다(없으면 보이기만)
#     TO=<토픽>          원래 토픽(헤더 kafka_dlt-original-topic) 대신 이 토픽으로
#     LIMIT=20           list 의 파티션마다 건수
#     KAFKA_NS=kafka · KAFKA_DEPLOY=kafka   Kafka 파드(k8s/90-dev-infra)
#     KAFKA_EXEC="…"     그 파드 안에서 명령을 돌리는 앞부분(기본 kubectl -n $KAFKA_NS exec -i deploy/$KAFKA_DEPLOY --)
#     BOOTSTRAP=localhost:9092   파드 안에서 본 브로커
#
# ── 알아 둘 것 ──────────────────────────────────────────────────────────
#
#   · 원래 토픽의 **모든 컨슈머 그룹**이 다시 받는다 — 실패한 그룹(list 의 '그룹')만이 아니다. 예: order.DLT 의 결제 요청을
#     다시 넣으면 notification · saga-projection 도 한 번 더 읽는다. 이 저장소의 컨슈머는 eventId · 처리 원장으로 거른다
#     (docs/12 §2) — 새 컨슈머를 만들면 그것부터 확인한다.
#   · 원인을 고치지 않고 넣으면 같은 자리로 다시 격리된다(list 에 '이미 한 번 다시 넣었던 것' 으로 보인다).
#   · 값은 출력하지 않는다(개인정보). DLT 레코드는 지우지 않는다 — 보존 기간(14일, docs/12)이 지나면 사라진다.
#   · 같은 레코드를 두 번 보내지 않도록 보낸 자리(→ 오프셋)를 일지에 적는다.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TOOL="$HERE/tools/DltReplay.java"
KAFKA_NS="${KAFKA_NS:-kafka}"
KAFKA_DEPLOY="${KAFKA_DEPLOY:-kafka}"
read -r -a EXEC <<< "${KAFKA_EXEC:-kubectl -n $KAFKA_NS exec -i deploy/$KAFKA_DEPLOY --}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
REMOTE=/tmp/DltReplay.java

die() { echo "✗ $*" >&2; exit 64; }

usage() { sed -n '/^# ── 사용/,/^# ── 알아/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 64; }

run_tool() {
    [ -f "$TOOL" ] || die "도구가 없습니다: $TOOL"
    "${EXEC[@]}" sh -c "cat > $REMOTE" < "$TOOL" || die "Kafka 파드에 도구를 넣지 못했습니다 — ${EXEC[*]}"
    # tools-log4j: 클라이언트 로그는 경고만 stderr 로(cp-kafka 의 도구 설정) — 표준 출력은 이 도구의 결과뿐
    "${EXEC[@]}" java -Dlog4j.configuration=file:/etc/kafka/tools-log4j.properties -cp '/usr/share/java/kafka/*' "$REMOTE" "$@"
}

ACTION="${1:-}"
case "$ACTION" in
list)
    [ -n "${2:-}" ] || usage
    run_tool list "$BOOTSTRAP" "$2" "${LIMIT:-20}" ;;
replay)
    [ -n "${2:-}" ] && [[ "${3:-}" =~ ^[0-9]+$ ]] && [[ "${4:-}" =~ ^[0-9]+(-[0-9]+)?$ ]] || usage
    case "$2" in *.DLT) ;; *) [ -n "${TO:-}" ] || die "DLT 토픽(….DLT)이 아닙니다: $2 — 정말이면 TO=<보낼 토픽>" ;; esac
    from="${4%%-*}"; to="${4##*-}"
    if [ "${APPLY:-0}" = "1" ]; then
        echo "▶ 보낸다: $2:$3 $from ~ $to → ${TO:-원래 토픽}"
    else
        echo "▶ 보이기만 한다(APPLY=1 이면 보낸다): $2:$3 $from ~ $to → ${TO:-원래 토픽}"
    fi
    run_tool replay "$BOOTSTRAP" "$2" "$3" "$from" "$to" "${APPLY:-0}" "${TO:--}"
    if [ "${APPLY:-0}" = "1" ]; then
        echo "  원래 토픽의 모든 컨슈머 그룹이 다시 받습니다 — 일지에 보낸 자리를 적으세요."
    fi ;;
*)
    usage ;;
esac
