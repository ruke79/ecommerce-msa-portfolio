#!/usr/bin/env bash
#
# 운영 액션 ㉟ — 네트워크 정책(k8s/95-network-policy.yaml)을 **확인하면서** 적용하고, 틀리면 스스로 되돌린다 (docs/57 §11).
#
# 왜 스크립트인가: NetworkPolicy 는 틀리면 조용히 끊는다 — 파드는 Running 인데 서비스끼리 붙지 못해 결제가 멈춘다. 그리고
# 반대로, 클러스터가 정책을 집행하지 않으면 파일을 적용해도 **아무것도 막지 않는데** 그것도 조용하다. 둘 다 두드려 봐야 안다.
#
# 사용:
#   ./ops/35-network-policy.sh check     # 지금 상태에서 허용·차단 흐름을 두드려 본다(바꾸지 않는다)
#   ./ops/35-network-policy.sh apply     # 적용 → 확인 → 허용돼야 할 흐름이 막히면 되돌린다
#   ./ops/35-network-policy.sh remove    # 정책을 지운다(되돌리기)
#
# 두드리는 방법: 잠깐 뜨는 busybox 파드에서 `nc -z` 로 TCP 연결만 해 본다(자격증명은 쓰지 않는다).
#   · 허용돼야 할 흐름 — ecommerce 네임스페이스에서: 각 서비스, Postgres·Redis·Kafka·Mongo·ES
#   · 막혀야 할 흐름   — default 네임스페이스에서: user-service·order-service 내부 API, Postgres·Redis
# 그리고 적용 뒤 ecommerce 의 모든 파드가 준비 상태를 유지하는지(kubelet 프로브가 막히지 않는지) 본다.
set -euo pipefail

cd "$(dirname "$0")/.."
MANIFEST="k8s/95-network-policy.yaml"
IMAGE="${PROBE_IMAGE:-mirror.gcr.io/library/busybox:1.36}"
TIMEOUT=3

ALLOWED_FROM_ECOMMERCE=(
  "gateway-server.ecommerce.svc.cluster.local:8000"
  "user-service.ecommerce.svc.cluster.local:8080"
  "catalog-service.ecommerce.svc.cluster.local:8081"
  "order-service.ecommerce.svc.cluster.local:8084"
  "postgres.postgres.svc.cluster.local:5432"
  "redis.redis.svc.cluster.local:6379"
  "kafka.kafka.svc.cluster.local:9092"
  "mongodb.mongodb.svc.cluster.local:27017"
  "elasticsearch.elasticsearch.svc.cluster.local:9200"
)
BLOCKED_FROM_DEFAULT=(
  "user-service.ecommerce.svc.cluster.local:8080"
  "order-service.ecommerce.svc.cluster.local:8084"
  "postgres.postgres.svc.cluster.local:5432"
  "redis.redis.svc.cluster.local:6379"
)

# 한 네임스페이스에서 대상들에 TCP 연결을 시도하고 "대상 OK|FAIL" 줄을 돌려준다. 파드는 끝나면 지운다.
probe() {
    local ns="$1"; shift
    local name="netpol-probe-$RANDOM"
    # 정책이 있으면 방금 뜬 파드는 정책 컨트롤러(kube-router)가 허용 목록에 넣기까지 몇 초간 막힌다 — 곧바로 두드리면 허용 흐름도
    # "막혔다" 로 읽혀 apply 가 되돌린다. 잠깐 기다린 뒤 두드린다(ops/37 도 같은 틈을 만났다).
    local script="sleep ${PROBE_WARMUP:-5}; "
    for t in "$@"; do
        script+="if nc -z -w $TIMEOUT ${t%:*} ${t##*:} 2>/dev/null; then echo '$t OK'; else echo '$t FAIL'; fi; "
    done
    kubectl -n "$ns" run "$name" --image="$IMAGE" --restart=Never --rm -i --quiet \
        --labels=app=netpol-probe --command -- sh -c "$script" 2>/dev/null || true
}

# 정책 **전**에 열려 있던 허용 흐름 — apply 가 채운다. 비어 있으면(check) 모든 허용 흐름이 열려 있어야 한다.
#
# 🐛 처음에는 적용 **후**에만 두드렸다. 그래서 원래 닿지 않던 대상(외부 Atlas 를 쓰느라 파드가 없는 MongoDB 등)도 "정책이
#    막았다" 로 읽고 되돌렸다. 이제 "전에는 열렸는데 후에 막힌 것" 만 정책 탓이다.
BASELINE=""

baseline() {
    echo "▶ 적용 전 — 허용돼야 할 흐름이 지금 열려 있나"
    while read -r target result; do
        [[ "$target" =~ ^[a-z0-9.-]+:[0-9]+$ && "$result" =~ ^(OK|FAIL)$ ]] || continue
        if [ "$result" = "OK" ]; then
            BASELINE="$BASELINE $target"; echo "  ✅ $target"
        else
            echo "  ℹ️  $target — 정책 전에도 닿지 않는다(쓰지 않거나 떠 있지 않다). 판정에서 뺀다"
        fi
    done < <(probe ecommerce "${ALLOWED_FROM_ECOMMERCE[@]}")
}

check() {
    local bad=0 enforced=1

    echo "▶ 허용돼야 할 흐름 (ecommerce → …)"
    while read -r target result; do
        [[ "$target" =~ ^[a-z0-9.-]+:[0-9]+$ && "$result" =~ ^(OK|FAIL)$ ]] || continue   # 결과 줄만
        if [ "$result" = "OK" ]; then
            echo "  ✅ $target"
        elif [ -n "$BASELINE" ] && [[ " $BASELINE " != *" $target "* ]]; then
            echo "  ℹ️  $target — 닿지 않지만 정책 전에도 그랬다(정책 탓이 아니다)"
        else
            echo "  🔴 $target — 막혔다"; bad=1
        fi
    done < <(probe ecommerce "${ALLOWED_FROM_ECOMMERCE[@]}")

    echo "▶ 막혀야 할 흐름 (default → …)"
    while read -r target result; do
        [[ "$target" =~ ^[a-z0-9.-]+:[0-9]+$ && "$result" =~ ^(OK|FAIL)$ ]] || continue
        if [ "$result" = "FAIL" ]; then echo "  ✅ $target — 막혔다"; else echo "  ⚠️  $target — 열려 있다"; enforced=0; fi
    done < <(probe default "${BLOCKED_FROM_DEFAULT[@]}")

    echo "▶ ecommerce 파드 준비 상태"
    local not_ready
    not_ready=$(kubectl -n ecommerce get pods --no-headers 2>/dev/null \
        | awk '$3=="Running" { split($2,a,"/"); if (a[1]!=a[2]) print $1 }')
    if [ -n "$not_ready" ]; then
        echo "  🔴 준비되지 않은 파드:"; echo "$not_ready" | sed 's/^/     /'; bad=1
    else
        echo "  ✅ Running 파드 모두 준비됨"
    fi

    CHECK_BAD=$bad
    CHECK_ENFORCED=$enforced
}

case "${1:-}" in
  check)
    check
    [ "$CHECK_ENFORCED" = 1 ] || echo "ℹ️  막혀야 할 흐름이 열려 있다 — 정책이 없거나 클러스터가 집행하지 않는다."
    [ "$CHECK_BAD" = 0 ]
    ;;
  apply)
    baseline
    [ -n "$BASELINE" ] || { echo "🔴 적용 전인데도 허용 흐름이 하나도 열려 있지 않다 — 두드리는 파드가 뜨지 않았을 수 있다. 적용하지 않는다."; exit 1; }
    kubectl apply -f "$MANIFEST"
    echo "정책이 퍼지기를 10초 기다린다…"; sleep ${NETPOL_SETTLE:-10}
    check
    if [ "$CHECK_BAD" != 0 ]; then
        echo "🔴 허용돼야 할 흐름이 막혔다 — 되돌린다."
        kubectl delete -f "$MANIFEST" --ignore-not-found
        exit 1
    fi
    if [ "$CHECK_ENFORCED" != 1 ]; then
        echo "⚠️  정책은 적용됐지만 막혀야 할 흐름이 열려 있다 — 클러스터가 NetworkPolicy 를 집행하지 않는 것 같다"
        echo "   (k3s 를 --disable-network-policy 로 올렸는지 확인). 정책은 남겨 둔다 — 해롭지는 않다."
        exit 2
    fi
    echo "✅ 적용 완료 — 허용 흐름 정상, 다른 네임스페이스에서의 연결은 막힌다."
    ;;
  remove)
    kubectl delete -f "$MANIFEST" --ignore-not-found
    ;;
  *)
    sed -n '2,20p' "$0"; exit 1 ;;
esac
