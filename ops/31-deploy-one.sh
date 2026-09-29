#!/usr/bin/env bash
# 운영 액션 ㉛ — 서비스 하나만 빌드해서 그 자리에 올린다 (개발 루프용)
#
# 둘 이상을 함께 올려야 하면 ops/33-deploy-some.sh — 빌드 한 번, 정해진 순서, 실패하면 멈추고 되돌리기.
#
# ── ⚠️ 이것은 ops/28 의 대체재가 아니다 ─────────────────────────────────
#
# ops/28 은 **시스템 전체를 한 버전으로** 배포한다(docs/36). 그래야 "지금 배포된 게
# 무엇인가"에 답이 하나로 나오고, 실패했을 때 되돌릴 지점이 분명하다. 이 저장소는 그
# 답이 흐려져서 배포가 일곱 시간 막힌 적이 있다(docs/39 §7).
#
# 이 스크립트는 그 모델을 **일부러 깬다.** 한 서비스만 다른 태그로 올리므로:
#
#   · 배포 저널에 기록되지 않는다 → `--rollback` 의 기준이 되지 못한다
#   · 스모크 게이트를 거치지 않는다 → **검증되지 않은 배포**다
#   · 클러스터가 여러 버전이 섞인 상태가 된다
#
# 그래서 쓰임은 하나다: **고치고 확인하기를 반복하는 동안의 빠른 루프.** 원인을 잡았으면
# 반드시 ops/28 로 전체를 한 버전에 맞춰 수렴시킨다. 이 스크립트가 끝날 때 그 명령을
# 출력한다.
#
# ⚠️ user-service 와 gateway-server 는 **서로 다른 버전으로 두지 말 것.** 게이트웨이가
#    uid 클레임 없는 토큰을 거부하므로, 게이트웨이만 새 버전이면 옛 user-service 가 발급한
#    토큰이 전부 401 이 된다(docs/27). 둘 중 하나를 올렸으면 다른 하나도 올린다.
#
# ── 왜 전체 빌드가 느렸나 ───────────────────────────────────────────────
#
# jar 가 재현 가능하지 않아서였다. Gradle 기본값이 파일 타임스탬프를 아카이브에 박기
# 때문에, 소스가 그대로여도 빌드마다 바이트가 달라지고 → 도커 레이어가 달라지고 →
# 안 바뀐 서비스 13개까지 매번 레지스트리로 전송됐다. build.gradle 의
# `preserveFileTimestamps = false` 가 그걸 없앴다(그 주석 참조). 그래서 이제는
# **전체 빌드도 훨씬 빠르다** — 이 스크립트가 필요한 경우가 줄었다는 뜻이다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/31-deploy-one.sh user-service                 # HEAD 로 빌드→푸시→교체
#   ./ops/31-deploy-one.sh frontend                     # REACT_APP_* 를 함께 넘길 것
#   ./ops/31-deploy-one.sh user-service --dry-run       # 무엇을 할지만 출력
#   TAG=abc123def456 ./ops/31-deploy-one.sh user-service   # 태그 직접 지정
#   REGISTRY=ghcr.io/owner ...                          # 기본값은 현재 배포된 이미지에서 읽는다
#
# 종료 코드: 0=성공, 1=실패
set -uo pipefail

NS="${NS:-ecommerce}"
SERVICE="${1:-}"
DRY_RUN=0
[ "${2:-}" = "--dry-run" ] && DRY_RUN=1

ok()   { echo "  ✅ $1"; }
info() { echo "  ℹ️  $1"; }
warn() { echo "  ⚠️  $1" >&2; }
die()  { echo "ERROR: $1" >&2; exit 1; }
log()  { echo; echo "▶ $1"; }

[ -n "$SERVICE" ] || die "서비스 이름을 지정하세요.
  예: $0 user-service
  대상: kubectl -n $NS get deploy -o name 으로 확인"

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"
[ -x ops/19-build-images.sh ] || die "저장소 루트에서 실행하세요"

kubectl -n "$NS" get "deploy/$SERVICE" >/dev/null 2>&1 \
    || die "$NS 에 deploy/$SERVICE 가 없습니다"

# ── 태그 ────────────────────────────────────────────────────────────────
#
# 기본은 커밋 SHA 12자다. ops/28 과 같은 규칙이라야 나중에 전체 배포로 수렴시킬 때
# 같은 태그를 쓸 수 있다.
TAG="${TAG:-$(git rev-parse --short=12 HEAD 2>/dev/null || true)}"
[ -n "$TAG" ] || die "TAG 를 알 수 없습니다. TAG=... 로 지정하세요"

# ── 레지스트리 ──────────────────────────────────────────────────────────
#
# 지금 **실제로 돌고 있는 이미지**에서 읽는다. 오버레이를 다시 해석하지 않아도 되고,
# 무엇보다 오버레이와 클러스터가 어긋나 있어도 클러스터 쪽을 따른다 — 지금 도는 것을
# 같은 자리에 갈아 끼우는 게 이 스크립트의 목적이기 때문이다.
CURRENT_IMAGE=$(kubectl -n "$NS" get "deploy/$SERVICE" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
[ -n "$CURRENT_IMAGE" ] || die "$SERVICE 의 현재 이미지를 읽지 못했습니다"

CONTAINER=$(kubectl -n "$NS" get "deploy/$SERVICE" \
    -o jsonpath='{.spec.template.spec.containers[0].name}')

IMAGE_NAME="${CURRENT_IMAGE%:*}"            # 태그를 뗀 이름
CURRENT_TAG="${CURRENT_IMAGE##*:}"
NEW_IMAGE="$IMAGE_NAME:$TAG"

# REGISTRY 는 ops/19 가 `<REGISTRY>/ecommerce-msa/<서비스>` 로 조립하므로,
# 이미지 이름에서 마지막 두 조각을 떼면 된다.
DERIVED_REGISTRY="${IMAGE_NAME%/ecommerce-msa/*}"
REGISTRY="${REGISTRY:-$DERIVED_REGISTRY}"

log "대상"
info "서비스      : $SERVICE (컨테이너 $CONTAINER)"
info "현재 이미지 : $CURRENT_IMAGE"
info "새 이미지   : $NEW_IMAGE"
info "레지스트리  : $REGISTRY"

if [ "$CURRENT_TAG" = "$TAG" ]; then
    # 같은 태그로 다시 올리면 노드가 캐시된 이미지를 그대로 쓴다(imagePullPolicy:
    # IfNotPresent). 새로 만든 이미지가 반영되지 않고, 그 사실이 아무 데도 안 드러난다.
    warn "현재 태그와 같습니다($TAG). 이미지를 새로 만들어도 노드가 캐시를 씁니다."
    warn "  커밋하고 새 SHA 로 하거나 TAG=... 로 다른 값을 주세요."
    die "중단합니다"
fi

if [ "$DRY_RUN" = "1" ]; then
    log "--dry-run — 실제로는 아무것도 하지 않습니다"
    echo "    ./ops/19-build-images.sh --registry $REGISTRY --push --only $SERVICE --tag $TAG"
    echo "    kubectl -n $NS set image deploy/$SERVICE $CONTAINER=$NEW_IMAGE"
    exit 0
fi

# ── 1. 빌드 ─────────────────────────────────────────────────────────────
#
# 빌드는 ops/19 에 맡긴다. jar 생성·포트 추출·buildx·레지스트리 로그인 확인이 전부
# 거기 있고, 여기서 다시 구현하면 두 벌이 서서히 어긋난다.
log "1/3  이미지 빌드 ($SERVICE)"
./ops/19-build-images.sh --registry "$REGISTRY" --push --only "$SERVICE" --tag "$TAG" \
    || die "빌드에 실패했습니다"

# ── 2. 교체 ─────────────────────────────────────────────────────────────
log "2/3  이미지 교체"
kubectl -n "$NS" set image "deploy/$SERVICE" "$CONTAINER=$NEW_IMAGE" \
    || die "set image 에 실패했습니다"

# 누가·언제·무엇을 바꿨는지 남긴다. ops/28 과 같은 형식이라 rollout history 에서
# 나란히 읽힌다 — 다만 "(부분)" 을 붙여 전체 배포와 구분한다.
kubectl -n "$NS" annotate "deploy/$SERVICE" \
    "kubernetes.io/change-cause=ops/31-deploy-one.sh $SERVICE $TAG (부분) @ $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --overwrite >/dev/null 2>&1 || true

# ── 3. 롤아웃 ───────────────────────────────────────────────────────────
log "3/3  롤아웃 대기"
if kubectl -n "$NS" rollout status "deploy/$SERVICE" --timeout="${ROLLOUT_TIMEOUT:-600s}"; then
    ok "$SERVICE → $TAG"
else
    kubectl -n "$NS" get pods -l "app=$SERVICE" \
        -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[0].ready,REASON:.status.containerStatuses[0].state.waiting.reason,RESTARTS:.status.containerStatuses[0].restartCount \
        2>/dev/null | sed 's/^/      /'
    die "롤아웃 실패/타임아웃 — 되돌리려면:
    kubectl -n $NS set image deploy/$SERVICE $CONTAINER=$CURRENT_IMAGE"
fi

cat <<EOF

  ⚠️ **검증되지 않은 부분 배포입니다.** 스모크 게이트를 거치지 않았고 배포 저널에도
     남지 않았습니다. 지금 클러스터는 $SERVICE 만 $TAG 이고 나머지는 다른 버전입니다.

  확인:
    GATEWAY=https://<도메인> ./ops/06-smoke-test.sh

  원인을 잡았으면 전체를 한 버전으로 수렴시키세요 — 그래야 저널에 ok 가 남고
  다음 실패 때 되돌릴 지점이 생깁니다:
    ./ops/28-deploy.sh local $TAG
EOF
