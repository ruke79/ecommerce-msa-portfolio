#!/usr/bin/env bash
#
# 운영 액션 ㊱ — 클러스터 Redis 의 비밀번호를 **DB 비밀번호와 분리**한다(처음), 또는 다시 바꾼다(그 뒤). 값은 출력하지 않는다.
#
# 왜: 클러스터 Redis 는 DB 와 같은 비밀번호를 쓴다 — 서비스 설정이 spring.data.redis.password 를 ${LOCAL_DB_PASSWORD} 로
# 잡고, redis-auth Secret 도 같은 값이다(docs/00 §11.1). 하나가 새면 둘 다 샌다. 그리고 DB 비밀번호를 바꿀 때마다 Redis 를
# 함께 맞춰야 해서, 빠뜨리면 다음 재시작에 Redis 를 쓰는 서비스가 한꺼번에 죽는다(ops/27 §2).
#
# 어떻게 — 끊김 없이:
#   ① Redis 의 default 사용자에 **새 비밀번호를 더한다**(ACL SETUSER default >새값). 옛 값도 계속 통한다.
#   ② ecommerce-secrets 에 SPRING_DATA_REDIS_PASSWORD(새 값)를 넣고 redis-auth 도 새 값으로.
#      환경 변수 SPRING_DATA_REDIS_PASSWORD 는 서비스 설정 파일의 ${LOCAL_DB_PASSWORD} 를 덮는다(스프링 우선순위).
#   ③ ecommerce 의 Deployment 를 차례로 재시작한다 — 새 파드는 새 값으로 붙고, 옛 파드는 옛 값으로 계속 돈다.
#   ④ Redis 를 재시작한다 — 새 프로세스는 redis-auth(새 값)만 받는다. **이 순간 수 초 끊긴다**(Recreate). 서비스는 다시 붙는다.
#      `--keep-old` 면 건너뛴다 — 옛 값(=DB 비밀번호)은 다음 Redis 재시작 때까지 통한다.
#
# 값은 인자·로그·파일에 남지 않는다 — 생성은 셸 변수, 전달은 stdin(kubectl patch --patch-file /dev/stdin, redis-cli 표준 입력).
#
# 사용:
#   ./ops/36-rotate-redis-password.sh              # 분리(또는 교체) 후 Redis 재시작
#   ./ops/36-rotate-redis-password.sh --keep-old   # Redis 는 재시작하지 않는다
#   ./ops/36-rotate-redis-password.sh --dry-run    # 무엇을 할지만
#   ./ops/36-rotate-redis-password.sh --finish chat-service   # ③에서 멈췄을 때 — 원인을 고친 뒤 **남은 것만** 마친다:
#        Secret 의 새 값이 Redis 에 통하는지 확인 → 준 Deployment 만 재시작 → ecommerce 가 전부 준비됐으면 ④(Redis 재시작).
#        새 비밀번호를 다시 만들지 않는다(처음부터 다시 돌리면 전부를 한 번 더 재시작한다).
#
# 끝나면 ./ops/27-check-secret-drift.sh --only redis 로 확인한다(이제 SPRING_DATA_REDIS_PASSWORD 로 시험한다).
set -euo pipefail

NS="${NAMESPACE:-ecommerce}"
SECRET="${SECRET:-ecommerce-secrets}"
KEEP_OLD=0; DRY=0; FINISH=0; FINISH_DEPLOYS=""
for a in "$@"; do
    case "$a" in
        --keep-old) KEEP_OLD=1 ;;
        --dry-run)  DRY=1 ;;
        --finish)   FINISH=1 ;;
        -*) sed -n '2,30p' "$0"; exit 1 ;;
        *)  [ "$FINISH" = 1 ] || { sed -n '2,30p' "$0"; exit 1; }
            FINISH_DEPLOYS="$FINISH_DEPLOYS $a" ;;
    esac
done

step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die()  { printf '\033[0;31m❌ %s\033[0m\n' "$*" >&2; exit 1; }

kubectl -n redis get deploy redis >/dev/null 2>&1 || die "redis/redis Deployment 가 없습니다 — 관리형 Redis 면 그쪽 콘솔에서 바꾸고 SPRING_DATA_REDIS_PASSWORD 만 넣으세요."
kubectl -n "$NS" get secret "$SECRET" >/dev/null 2>&1 || die "$NS/$SECRET Secret 이 없습니다."
command -v openssl >/dev/null || die "openssl 이 없습니다."

redis_cli() {   # stdin 으로 명령을 받는다. 인증은 파드 안의 현재 비밀번호 — 값은 파드 밖으로 나오지 않는다.
    kubectl -n redis exec -i deploy/redis -- sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli'
}

# Redis 를 재시작해도 되는가 — ecommerce 의 Deployment 가 전부 준비됐는가. 하나라도 옛 파드가 남아 있으면 그 파드는 옛 값을
# 들고 있을 수 있다(재시작 전 환경 변수). 그 상태로 Redis 를 재시작하면 그 파드가 Redis 를 잃는다.
all_ready() {
    local d bad=""
    for d in $(kubectl -n "$NS" get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
        kubectl -n "$NS" rollout status "deploy/$d" --timeout=5s >/dev/null 2>&1 || bad="$bad $d"
    done
    NOT_READY="${bad# }"
    [ -z "$bad" ]
}

restart_redis() {
    if [ "$KEEP_OLD" = 1 ]; then
        echo
        echo "⚠️  --keep-old — 옛 비밀번호(DB 와 같은 값)는 다음 Redis 재시작 때까지 통한다:"
        echo "    kubectl -n redis rollout restart deploy/redis"
        return
    fi
    step "④ Redis 재시작 — 새 프로세스는 새 비밀번호만 받는다(수 초 끊김)"
    kubectl -n redis rollout restart deploy/redis >/dev/null
    kubectl -n redis rollout status deploy/redis --timeout=300s
}

if [ "$FINISH" = 1 ]; then
    [ -n "$(kubectl -n "$NS" get secret "$SECRET" -o jsonpath='{.data.SPRING_DATA_REDIS_PASSWORD}')" ] \
        || die "--finish 인데 $SECRET 에 SPRING_DATA_REDIS_PASSWORD 가 없다 — 처음부터 돌린다(옵션 없이)."
    step "새 비밀번호(Secret 의 값)가 Redis 에 통하는가"
    # 값은 파이프로만 — 화면·인자에 나오지 않는다.
    pong=$(kubectl -n "$NS" get secret "$SECRET" -o jsonpath='{.data.SPRING_DATA_REDIS_PASSWORD}' | base64 -d \
        | kubectl -n redis exec -i deploy/redis -- sh -c 'REDISCLI_AUTH="$(cat)" redis-cli ping' 2>&1)
    [ "$pong" = "PONG" ] || die "Secret 의 새 값이 Redis 에 통하지 않는다 — Redis 가 그새 재시작됐을 수 있다. 처음부터 다시 돌린다(옵션 없이)."
    echo "  ✅ PING"
    for d in $FINISH_DEPLOYS; do
        step "재시작 — $d"
        kubectl -n "$NS" rollout restart "deploy/$d" >/dev/null || die "deploy/$d 가 없다"
        kubectl -n "$NS" rollout status "deploy/$d" --timeout=300s || die "$d 롤아웃이 끝나지 않았다 — Redis 는 두 값을 다 받고 있어 멈추지 않는다. 원인을 본 뒤 다시 --finish"
    done
    step "ecommerce 가 전부 준비됐는가"
    all_ready || die "아직 준비되지 않았다: $NOT_READY — Redis 재시작은 하지 않는다. 고친 뒤: $0 --finish $NOT_READY"
    echo "  ✅ 전부 준비됨"
    restart_redis
    echo
    echo "✅ 끝. 확인: ./ops/27-check-secret-drift.sh --only redis"
    exit 0
fi

if [ -n "$(kubectl -n "$NS" get secret "$SECRET" -o jsonpath='{.data.SPRING_DATA_REDIS_PASSWORD}')" ]; then
    echo "이미 분리돼 있다 — 새 값으로 교체한다."
else
    echo "처음 분리한다 — 지금은 DB 비밀번호(LOCAL_DB_PASSWORD)를 함께 쓰고 있다."
fi

DEPLOYS=$(kubectl -n "$NS" get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
if [ "$DRY" = 1 ]; then
    echo "① Redis default 사용자에 새 비밀번호 추가"
    echo "② $NS/$SECRET 의 SPRING_DATA_REDIS_PASSWORD · redis/redis-auth 의 password 교체"
    echo "③ 재시작: $(echo $DEPLOYS)"
    [ "$KEEP_OLD" = 1 ] && echo "④ (건너뜀) Redis 재시작" || echo "④ Redis 재시작 — 수 초 끊김"
    exit 0
fi

NEW="$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40)"
[ "${#NEW}" -ge 32 ] || die "비밀번호 생성 실패"
B64="$(printf '%s' "$NEW" | base64 | tr -d '\n')"

step "① Redis 에 새 비밀번호를 더한다(옛 값도 계속 통한다)"
out=$(printf 'ACL SETUSER default >%s\n' "$NEW" | redis_cli 2>&1)
[ "$out" = "OK" ] || die "ACL SETUSER 실패: $(echo "$out" | head -1)"
pong=$(printf '%s' "$NEW" | kubectl -n redis exec -i deploy/redis -- sh -c 'REDISCLI_AUTH="$(cat)" redis-cli ping' 2>&1)
[ "$pong" = "PONG" ] || die "새 비밀번호로 인증되지 않습니다 — 아무것도 바꾸지 않았습니다(Secret 그대로)."
echo "  ✅ 새 비밀번호로 PING"

step "② Secret 교체 — $NS/$SECRET(SPRING_DATA_REDIS_PASSWORD), redis/redis-auth(password)"
printf '{"data":{"SPRING_DATA_REDIS_PASSWORD":"%s"}}' "$B64" \
    | kubectl -n "$NS" patch secret "$SECRET" --type merge --patch-file /dev/stdin >/dev/null
printf '{"data":{"password":"%s"}}' "$B64" \
    | kubectl -n redis patch secret redis-auth --type merge --patch-file /dev/stdin >/dev/null
unset NEW B64
echo "  ✅ 두 Secret 갱신"

step "③ 서비스 재시작 — 새 파드가 새 비밀번호로 붙는다"
failed=""
for d in $DEPLOYS; do
    kubectl -n "$NS" rollout restart "deploy/$d" >/dev/null
    if kubectl -n "$NS" rollout status "deploy/$d" --timeout=300s >/dev/null 2>&1; then
        echo "  ✅ $d"
    else
        echo "  🔴 $d — 롤아웃이 끝나지 않았다"; failed+=" $d"
    fi
done
if [ -n "$failed" ]; then
    echo
    echo "🔴 롤아웃 실패:$failed"
    echo "   Redis 는 옛·새 비밀번호를 **둘 다** 받고 있어 지금은 멈추지 않는다. Redis 재시작은 하지 않고 멈춘다."
    echo "   원인을 본 뒤(예: kubectl -n $NS get pods; kubectl -n $NS logs deploy/<이름> --tail=80),"
    echo "   처음부터가 아니라 남은 것만: $0 --finish$failed"
    exit 1
fi

restart_redis
echo
echo "✅ 끝. 확인: ./ops/27-check-secret-drift.sh --only redis"
echo "   이제 DB 비밀번호를 바꿀 때 Redis 를 함께 맞출 필요가 없다(ops/01·ops/25)."
