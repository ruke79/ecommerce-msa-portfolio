#!/usr/bin/env bash
# 운영 액션 ② — 토픽 사전 생성: DLT 9종(docs/12 §1) + 컴팩션 상태 토픽(docs/38 §1.17)
#
# 브로커가 auto.create.topics.enable=false 인 경우 DLT 발행이 실패하므로 미리 만든다.
# (auto-create 가 켜져 있어도, 파티션/보존 정책을 명시적으로 관리하는 편이 안전하다)
#
# ── 왜 컴팩션 토픽이 여기 끼어들었나 ────────────────────────────────────
#
# saga-projection(Kafka Streams)의 출력 `order-saga-state` 는 **주문마다 최신 상태 하나**를
# 담는 토픽이다. 그러려면 `cleanup.policy=compact` 여야 하는데, Streams 는 싱크 토픽
# (`to()`)을 만들지 않는다 — 내부 토픽(변경 로그·리파티션)만 스스로 만든다. 그래서 아무도
# 안 만들면 **브로커가 자동 생성하고, 그 기본 정책은 `delete`** 다. 증상이 없다는 게 문제다:
# 토픽은 생기고 레코드도 잘 쌓이며, retention.ms(기본 7일)가 지나서야 과거 주문의 상태가
# 조용히 사라진다. 그때 프로젝션을 처음부터 다시 만들면 옛 주문이 빈다.
#
# 더 고약한 순서가 하나 있다. **앱이 먼저 뜨면 토픽은 이미 존재한다.** 그 뒤에 이 스크립트를
# `--if-not-exists` 로 돌리면 "성공"으로 끝나고 정책은 여전히 delete 다. 그래서 여기서는
# 존재 여부만 보지 않고 **cleanup.policy 를 실제로 읽어 확인하고, 다르면 kafka-configs 로
# 고친다**(FIX_CONFIGS=0 이면 경고만). 토픽 생성과 달리 이건 기존 토픽에 대한 변경이라
# 무엇을 바꿨는지 항상 출력한다.
#
# ── 실행 위치를 스스로 찾는다 ───────────────────────────────────────────
#
# 예전에는 `kafka-topics.sh` 가 PATH 에 있거나 KAFKA_BIN·KAFKA_CONTAINER(docker) 중
# 하나여야 했다. 그런데 이 프로젝트의 실제 배포 대상은 **k8s** 이고 거기서는 셋 다
# 해당되지 않는다 — 파드 안에는 docker 가 없고, 로컬 PATH 에도 카프카 CLI 가 없다.
# docs/33 을 쓰다 발견했다: 런북이 "이 스크립트를 쓰라"고 적어 놓고 실제로는 동작하지 않았다.
#
# 게다가 명령 이름이 배포판마다 다르다:
#   · Apache Kafka 배포판   kafka-topics.sh
#   · Confluent 이미지      kafka-topics      (.sh 없음) ← k8s/90-dev-infra.yaml 이 쓰는 것
#
# 이제 실행 방식을 지정하지 않으면 **kubectl → docker → 로컬 PATH 순으로 직접 찾는다.**
# 그리고 어느 쪽이든 두 이름을 모두 시도한다.
#
# 사용:
#   ./ops/02-create-dlt-topics.sh                       # 알아서 찾는다 (k8s 우선)
#   ./ops/02-create-dlt-topics.sh --check               # 무엇을 쓸지 진단만
#
#   KUBE_NAMESPACE=kafka KUBE_WORKLOAD=deploy/kafka \   # k8s 를 명시
#     ./ops/02-create-dlt-topics.sh
#   KAFKA_CONTAINER=kafka1 ./ops/02-create-dlt-topics.sh            # docker 를 명시
#   KAFKA_BIN=/opt/kafka/bin BOOTSTRAP=localhost:29092 ./ops/02-... # 로컬 CLI 를 명시
#
#   COMMAND_CONFIG=/path/client-ssl.properties          # SSL/SASL 브로커
#   PARTITIONS=3 REPLICATION=1 RETENTION_MS=1209600000(14일) EXTRA_TOPICS="foo bar"
#   STATE_TOPIC=order-saga-state STATE_PARTITIONS=<n>   # 비우면 order 토픽과 같은 수로 맞춘다
#   FIX_CONFIGS=0 → 이미 있는 토픽의 잘못된 정책을 고치지 않고 경고만
#   SKIP_STATE=1  → 상태 토픽은 건드리지 않는다 (DLT 만)
#   DRY_RUN=1 → 명령만 출력
set -euo pipefail

PARTITIONS="${PARTITIONS:-3}"
REPLICATION="${REPLICATION:-1}"
RETENTION_MS="${RETENTION_MS:-1209600000}"   # 14일 — DLT 는 재처리 유예 기간만 보존
DRY_RUN="${DRY_RUN:-0}"
FIX_CONFIGS="${FIX_CONFIGS:-1}"
SKIP_STATE="${SKIP_STATE:-0}"
CHECK_ONLY=0

# saga-projection 의 출력 토픽. 이름은 saga-projection/src/main/resources/application-local.yml
# 의 saga-projection.topics.state 와 같아야 한다 — 어긋나면 프로젝션은 자기가 만든(delete 정책)
# 토픽에 쓰고 이 스크립트는 아무도 안 쓰는 토픽을 컴팩션으로 만든다. 둘 다 조용하다.
# ConfigurationGuardrailTest(B-38)가 두 값을 대조한다.
STATE_TOPIC="${STATE_TOPIC:-order-saga-state}"
# 상태 토픽이 본뜨는 입력 토픽. 파티션 수를 여기에 맞춘다(아래 이유 참조).
STATE_SOURCE_TOPIC="${STATE_SOURCE_TOPIC:-order}"

KUBE_NAMESPACE="${KUBE_NAMESPACE:-kafka}"
KUBE_WORKLOAD="${KUBE_WORKLOAD:-deploy/kafka}"

[ "${1:-}" = "--check" ] && CHECK_ONLY=1

# 에러 핸들러가 배선된 컨슈머 토픽 전체 (docs/12 §1)
#
# ⚠️ 이 목록은 저장소의 @KafkaListener 토픽과 **정확히 일치해야** 한다. 빠진 토픽은
#    평소에 아무 증상이 없다 — DLT 는 처리 실패가 났을 때만 쓰이기 때문이다. 그러다 정말
#    poison message 가 하나 들어오면 DeadLetterPublishingRecoverer 가 없는 토픽에 쓰려다
#    실패하고, 그 파티션이 막힌다. 즉 **DLQ 를 넣은 이유였던 바로 그 장애**가 그대로 난다.
#    outgoing-mail 과 user-presence 가 실제로 그렇게 빠져 있었다(두 리스너는 이 목록보다
#    나중에 생겼다). ConfigurationGuardrailTest B-41 이 두 목록을 대조한다.
TOPICS=(order payment product-update user-created cart-empty cart-rollback coupon-use coupon-rollback chat
        outgoing-mail user-presence delivery-status coupon-issued notice-published event-started security-alert
        order-completed order-refunded product-stock user-updated user-deleted stock-shortage support-reply)
for t in ${EXTRA_TOPICS:-}; do TOPICS+=("$t"); done

info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 실행 방식 결정

MODE=""       # kube | docker | local
CLI=""        # kafka-topics | kafka-topics.sh

# 파드/컨테이너 안에 어떤 이름으로 있는지 확인한다. 배포판마다 다르다.
detect_cli() {  # detect_cli <실행 프리픽스...>
    for name in kafka-topics kafka-topics.sh; do
        if "$@" sh -c "command -v $name" >/dev/null 2>&1; then
            CLI="$name"; return 0
        fi
    done
    return 1
}

if [ -n "${KAFKA_BIN:-}" ]; then
    MODE=local
    for name in kafka-topics.sh kafka-topics; do
        [ -x "$KAFKA_BIN/$name" ] && CLI="$KAFKA_BIN/$name" && break
    done
    [ -n "$CLI" ] || die "KAFKA_BIN=$KAFKA_BIN 에 kafka-topics(.sh) 가 없습니다"

elif [ -n "${KAFKA_CONTAINER:-}" ]; then
    MODE=docker
    detect_cli docker exec "$KAFKA_CONTAINER" \
        || die "컨테이너 $KAFKA_CONTAINER 안에서 kafka-topics(.sh) 를 찾지 못했습니다"

elif command -v kubectl >/dev/null 2>&1 \
        && kubectl -n "$KUBE_NAMESPACE" get "$KUBE_WORKLOAD" >/dev/null 2>&1; then
    MODE=kube
    detect_cli kubectl -n "$KUBE_NAMESPACE" exec "$KUBE_WORKLOAD" -- \
        || die "$KUBE_NAMESPACE/$KUBE_WORKLOAD 안에서 kafka-topics(.sh) 를 찾지 못했습니다"

elif command -v kafka-topics.sh >/dev/null 2>&1 || command -v kafka-topics >/dev/null 2>&1; then
    MODE=local
    CLI=$(command -v kafka-topics.sh 2>/dev/null || command -v kafka-topics)

else
    die "Kafka CLI 를 찾지 못했습니다. 아래 중 하나가 필요합니다:

    · k8s   — kubectl 이 클러스터를 가리키고 $KUBE_NAMESPACE/$KUBE_WORKLOAD 가 있을 것
              (다르면 KUBE_NAMESPACE / KUBE_WORKLOAD 로 지정)
    · docker— KAFKA_CONTAINER=<컨테이너명>
    · 로컬  — KAFKA_BIN=/opt/kafka/bin 또는 PATH 에 kafka-topics(.sh)"
fi

# 부트스트랩 주소의 기본값은 실행 위치에 따라 다르다. 파드/컨테이너 **안**에서 도는 경우
# 브로커는 localhost 이고, 밖에서 도는 경우는 외부에 노출된 주소여야 한다.
case "$MODE" in
    kube|docker) BOOTSTRAP="${BOOTSTRAP:-localhost:9092}" ;;
    local)       BOOTSTRAP="${BOOTSTRAP:-localhost:29092}" ;;
esac

kafka_topics() {
    case "$MODE" in
        kube)   kubectl -n "$KUBE_NAMESPACE" exec "$KUBE_WORKLOAD" -- "$CLI" "$@" ;;
        docker) docker exec "$KAFKA_CONTAINER" "$CLI" "$@" ;;
        local)  "$CLI" "$@" ;;
    esac
}

# kafka-configs 는 kafka-topics 와 같은 배포판에 나란히 들어 있고 이름 규칙도 같다
# (Apache 는 .sh 가 붙고 Confluent 는 안 붙는다). 그래서 따로 탐지하지 않고 이름만 바꾼다 —
# 로컬 모드의 절대 경로(/opt/kafka/bin/kafka-topics.sh)도 그대로 맞는다.
CFG_CLI="${CLI//kafka-topics/kafka-configs}"

kafka_configs() {
    case "$MODE" in
        kube)   kubectl -n "$KUBE_NAMESPACE" exec "$KUBE_WORKLOAD" -- "$CFG_CLI" "$@" ;;
        docker) docker exec "$KAFKA_CONTAINER" "$CFG_CLI" "$@" ;;
        local)  "$CFG_CLI" "$@" ;;
    esac
}

printf '\n\033[1;36m▶ 실행 방식\033[0m\n'
case "$MODE" in
    kube)   info "kubectl exec — $KUBE_NAMESPACE/$KUBE_WORKLOAD" ;;
    docker) info "docker exec — $KAFKA_CONTAINER" ;;
    local)  info "로컬 CLI — $CLI" ;;
esac
info "명령: $CLI"
info "브로커: $BOOTSTRAP"

if [ "$CHECK_ONLY" = "1" ]; then
    printf '\n  진단만 수행했습니다 (--check). 생성하려면 --check 없이 실행하세요.\n'
    exit 0
fi

CFG_ARGS=(--bootstrap-server "$BOOTSTRAP")
[ -n "${COMMAND_CONFIG:-}" ] && CFG_ARGS+=(--command-config "$COMMAND_CONFIG")

# ---------------------------------------------------------------- 생성

printf '\n\033[1;36m▶ DLT 토픽 %d종\033[0m\n' "${#TOPICS[@]}"

for t in "${TOPICS[@]}"; do
    DLT="${t}.DLT"
    ARGS=(--create --if-not-exists --topic "$DLT"
          --partitions "$PARTITIONS" --replication-factor "$REPLICATION"
          --config "retention.ms=$RETENTION_MS")
    if [ "$DRY_RUN" = "1" ]; then
        echo "[DRY_RUN] $CLI ${CFG_ARGS[*]} ${ARGS[*]}"
    else
        kafka_topics "${CFG_ARGS[@]}" "${ARGS[@]}" >/dev/null || die "$DLT 생성 실패"
        info "$DLT"
    fi
done

if [ "$DRY_RUN" != "1" ]; then
    printf '\n\033[1;36m▶ 생성 확인\033[0m\n'
    CREATED=$(kafka_topics "${CFG_ARGS[@]}" --list | grep -c "\.DLT$" || true)
    info "$CREATED 개의 .DLT 토픽이 브로커에 있습니다"
    [ "$CREATED" -ge "${#TOPICS[@]}" ] \
        || warn "기대(${#TOPICS[@]})보다 적습니다 — 위 목록을 확인하세요"
fi

# ------------------------------------------------- 컴팩션 상태 토픽 (docs/38 §1.17)

# saga-projection 의 출력에 필요한 설정. 셋 다 이유가 있다:
#
#   cleanup.policy=compact        주문마다 최신 상태 하나. 이게 빠지면 retention.ms 가 지난
#                                 과거 주문이 조용히 사라진다.
#   segment.ms                    컴팩션은 **활성 세그먼트를 건드리지 않는다.** 저트래픽 토픽에서
#                                 기본값(segment.bytes=1GB, segment.ms=7일)이면 한 세그먼트가
#                                 몇 주씩 열려 있고 그동안 중복 키가 그대로 쌓인다 —
#                                 "컴팩션을 켰는데 왜 안 줄지"의 거의 유일한 원인이다.
#   min.cleanable.dirty.ratio     기본 0.5 는 "중복이 절반은 돼야 청소한다". 상태 토픽은
#                                 주문당 2~3회 갱신이라 그 비율에 잘 닿지 않는다.
STATE_CONFIGS=("cleanup.policy=compact" "segment.ms=86400000" "min.cleanable.dirty.ratio=0.1")
STATE_CONFIG_CSV=$(IFS=,; echo "${STATE_CONFIGS[*]}")

if [ "$SKIP_STATE" = "1" ]; then
    printf '\n  SKIP_STATE=1 — %s 는 건드리지 않습니다\n' "$STATE_TOPIC"

elif [ "$DRY_RUN" = "1" ]; then
    # 브로커를 읽지 않는다(기존 토픽 유무에 따라 실제 명령이 갈리므로 둘 다 보여 준다).
    printf '\n\033[1;36m▶ 컴팩션 상태 토픽 %s\033[0m\n' "$STATE_TOPIC"
    echo "[DRY_RUN] 없으면: $CLI ${CFG_ARGS[*]} --create --if-not-exists --topic $STATE_TOPIC --partitions ${STATE_PARTITIONS:-<$STATE_SOURCE_TOPIC 와 동일>} --replication-factor $REPLICATION --config ${STATE_CONFIGS[0]} --config ${STATE_CONFIGS[1]} --config ${STATE_CONFIGS[2]}"
    echo "[DRY_RUN] 있으면: $CFG_CLI ${CFG_ARGS[*]} --alter --entity-type topics --entity-name $STATE_TOPIC --add-config $STATE_CONFIG_CSV"

else
    printf '\n\033[1;36m▶ 컴팩션 상태 토픽 %s\033[0m\n' "$STATE_TOPIC"

    # 파티션 수는 입력 토픽에 맞춘다. 지금은 단순 싱크라 달라도 동작하지만, 이 토픽을 KTable 로
    # 다시 읽어 order/payment 와 조인하려는 순간 **공동 파티셔닝**이 요구된다(Streams 는 파티션
    # 수가 다르면 TopologyException 으로 거부한다). DLT 기본값 3 을 그대로 쓰면 입력이 1인 지금
    # 이미 어긋난다.
    DETECTED=$(kafka_topics "${CFG_ARGS[@]}" --describe --topic "$STATE_SOURCE_TOPIC" 2>/dev/null \
                | sed -n 's/.*PartitionCount: *\([0-9][0-9]*\).*/\1/p' | head -1) || DETECTED=""
    if [ -n "$DETECTED" ]; then
        STATE_PARTITIONS="${STATE_PARTITIONS:-$DETECTED}"
        info "입력 토픽 $STATE_SOURCE_TOPIC 의 파티션 수: $DETECTED"
    else
        STATE_PARTITIONS="${STATE_PARTITIONS:-1}"
        warn "$STATE_SOURCE_TOPIC 을 아직 찾지 못했습니다(앱 기동 전) — 파티션 $STATE_PARTITIONS 로 만듭니다"
    fi

    if kafka_topics "${CFG_ARGS[@]}" --list 2>/dev/null | grep -qFx "$STATE_TOPIC"; then
        # 이미 있다 — 앱이 먼저 떠서 **브로커가 자동 생성**했을 가능성이 높고, 그 기본 정책은 delete 다.
        # --if-not-exists 로 넘어가면 "성공"으로 보이면서 정책은 그대로이므로 실제 값을 읽는다.
        # --all 은 브로커 기본값까지 보여 준다(동적 설정만 보면 자동 생성 토픽은 빈 줄이 나온다).
        #
        # ⚠️ 정규식을 **줄 처음에 고정**해야 한다. 출력 한 줄은 이렇게 생겼다:
        #     cleanup.policy=compact sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:cleanup.policy=compact, DEFAULT_CONFIG:log.cleanup.policy=delete}
        #   앞에 `.*` 를 두면 탐욕적 매칭이 **synonyms 안의 마지막 값**(브로커 기본값 delete)을 잡아,
        #   이미 compact 인 토픽도 매번 "delete → compact 로 바꿨다"고 보고한다. 스텁 브로커로
        #   재실행 시나리오를 돌려 보고서야 드러났다.
        POLICY=$(kafka_configs "${CFG_ARGS[@]}" --describe --all --entity-type topics --entity-name "$STATE_TOPIC" 2>/dev/null \
                  | sed -n 's/^[[:space:]]*cleanup\.policy=\([a-z,]*\).*/\1/p' | head -1) || POLICY=""

        CURRENT_PARTS=$(kafka_topics "${CFG_ARGS[@]}" --describe --topic "$STATE_TOPIC" 2>/dev/null \
                         | sed -n 's/.*PartitionCount: *\([0-9][0-9]*\).*/\1/p' | head -1) || CURRENT_PARTS=""
        if [ -n "$CURRENT_PARTS" ] && [ -n "$DETECTED" ] && [ "$CURRENT_PARTS" != "$DETECTED" ]; then
            warn "$STATE_TOPIC 의 파티션 $CURRENT_PARTS ≠ $STATE_SOURCE_TOPIC 의 $DETECTED — 지금은 무해하지만 이 토픽을 조인에 쓰려면 맞춰야 합니다(파티션은 줄일 수 없어 재생성이 필요합니다)"
        fi

        if [ "$POLICY" = "compact" ]; then
            info "$STATE_TOPIC 이미 있고 cleanup.policy=compact — 손대지 않습니다"
        elif [ "$FIX_CONFIGS" != "1" ]; then
            warn "$STATE_TOPIC 의 cleanup.policy=${POLICY:-확인 불가} — FIX_CONFIGS=1 로 다시 실행하면 고칩니다"
        else
            kafka_configs "${CFG_ARGS[@]}" --alter --entity-type topics --entity-name "$STATE_TOPIC" \
                --add-config "$STATE_CONFIG_CSV" >/dev/null || die "$STATE_TOPIC 설정 변경 실패"
            warn "$STATE_TOPIC 의 cleanup.policy 를 ${POLICY:-확인 불가} → compact 로 **변경했습니다**(기존 토픽 변경)"
            warn "delete 정책으로 이미 삭제된 과거 상태는 돌아오지 않습니다 — 필요하면 프로젝션을 재구축하세요(saga-projection 의 SAGA_APPLICATION_ID 변경 또는 컨슈머 그룹 오프셋 리셋)"
        fi
    else
        ARGS=(--create --if-not-exists --topic "$STATE_TOPIC"
              --partitions "$STATE_PARTITIONS" --replication-factor "$REPLICATION")
        for c in "${STATE_CONFIGS[@]}"; do ARGS+=(--config "$c"); done
        kafka_topics "${CFG_ARGS[@]}" "${ARGS[@]}" >/dev/null || die "$STATE_TOPIC 생성 실패"
        info "$STATE_TOPIC 생성 — 파티션 $STATE_PARTITIONS, $STATE_CONFIG_CSV"
    fi

    printf '\n  Streams 내부 토픽(리파티션·변경 로그)은 saga-projection 이 스스로 만든다 — 손대지 마세요.\n'
    printf '  변경 로그는 이미 컴팩션이고, 이름은 <SAGA_APPLICATION_ID>-order-saga-state-store-changelog 입니다.\n'
fi

if [ "$DRY_RUN" != "1" ]; then
    printf '\n  DLT 적재 모니터링을 잊지 마세요 (docs/12 §1): 토픽 크기·컨슈머 랙 경보\n'
fi
