#!/usr/bin/env bash
# 운영 액션 ㉝ — 서비스 **여러 개**를 한 태그로 빌드해서 그 자리에 올린다 (개발 루프용)
#
# ops/31 의 여러 서비스판이다. 기능 하나가 서비스 둘 이상에 걸치는 일이 잦다 — 재고 예약만 해도 catalog·order·frontend
# 셋이 함께 바뀐다(docs/59). ops/31 을 세 번 부르면 빌드가 세 번(gradle·buildx 기동 세 번) 돌고, 셋 중 하나가 실패해도
# 앞의 둘은 이미 올라가 있다.
#
# ── ⚠️ 이것도 ops/28 의 대체재가 아니다 ─────────────────────────────────
#
# ops/31 과 같은 이유로(그 머리말 참조): 배포 저널에 남지 않고(`--rollback` 기준이 못 된다), 스모크 게이트를 거치지 않으며,
# 클러스터가 여러 버전이 섞인 상태가 된다. 원인을 잡았으면 ops/28 로 전체를 한 버전에 수렴시킨다 — 끝날 때 그 명령을 출력한다.
#
# ── ops/31 에 없는 것 ───────────────────────────────────────────────────
#
#   · 빌드는 **한 번** — ops/19 에 `--only "a b c"` 로 한꺼번에 넘긴다(bootJar 도 그 모듈들만).
#   · 올리는 **순서**가 정해져 있다 — 기반(eureka·config) → 백엔드 → 게이트웨이 → 프론트. user-service 가 게이트웨이보다
#     먼저다: 새 게이트웨이가 옛 user-service 의 토큰을 거부할 수 있다(docs/27).
#   · 기본은 **하나씩** 올리고 롤아웃이 끝나야 다음으로 간다. 하나가 실패하면 **거기서 멈추고**, 이미 올린 것들의 되돌리기
#     명령을 역순으로 출력한다. `--rollback-on-failure` 면 그것을 직접 실행한다. `--parallel` 은 전부 바꾼 뒤 한꺼번에 기다린다.
#   · user-service 와 gateway-server 는 **짝**이다 — 하나만 주면 다른 하나를 자동으로 더한다(ops/31 은 주석으로 경고만 했다).
#     `--allow-split` 으로 끈다.
#   · `--changed <git ref>` — 그 ref 이후 바뀐 파일로 대상을 고른다. common·루트 gradle·런타임 Dockerfile 이 바뀌면 자바
#     서비스 전부다. 클러스터에 배포돼 있지 않은 서비스는 건너뛴다.
#   · `--changed deployed` — ⭐ 기준을 고를 필요가 없다. 서비스마다 **지금 떠 있는 이미지의 태그(= 커밋)** 이후 그 서비스가
#     바뀌었는지 본다. ⚠️ `--changed <지금 브랜치>` 는 HEAD 와 HEAD 를 비교하니 언제나 "없음" 이다 — 기준은 "배포된 것" 이어야 한다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/33-deploy-some.sh catalog-service order-service frontend     # HEAD 로 빌드→푸시→차례로 교체
#   ./ops/33-deploy-some.sh catalog-service order-service --dry-run    # 무엇을 할지만 출력
#   ./ops/33-deploy-some.sh --changed deployed                         # ⭐ 떠 있는 이미지 이후 바뀐 서비스만
#   ./ops/33-deploy-some.sh --changed 8314abc                          # 8314abc 이후 바뀐 서비스만
#   ./ops/33-deploy-some.sh --changed origin/main --dry-run
#   ./ops/33-deploy-some.sh a b --parallel --rollback-on-failure
#   TAG=abc123def456 ./ops/33-deploy-some.sh a b                       # 태그 직접 지정
#   REGISTRY=ghcr.io/owner ...                                         # 기본값은 현재 배포된 이미지에서 읽는다
#
#   frontend 를 넣으면 REACT_APP_* 를 ops/19 에 넘길 때와 같이 환경에 둔다(빌드 시점 값이다 — B-51).
#   더 쉬운 길: ops/frontend.env(gitignore, ops/frontend.env.example 참조)에 적어 두면 ops/19 가 알아서 읽는다 — export 를 잊을 일이 없다.
#
# 종료 코드: 0=성공, 1=실패(되돌렸든 아니든)
set -uo pipefail

NS="${NS:-ecommerce}"

ok()   { echo "  ✅ $1"; }
info() { echo "  ℹ️  $1"; }
warn() { echo "  ⚠️  $1" >&2; }
die()  { echo "ERROR: $1" >&2; exit 1; }
log()  { echo; echo "▶ $1"; }

# 올리는 순서. ops/19 의 SERVICES 와 같은 이름이고, frontend 가 마지막이다.
ORDER="eureka-server config-server user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection gateway-server frontend"
JAVA_SERVICES="eureka-server config-server gateway-server user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection"

known() { case " $ORDER " in *" $1 "*) return 0 ;; esac; return 1; }
has()   { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

# ── 인자 ────────────────────────────────────────────────────────────────
REQUESTED=""
EXPLICIT=""     # 인자로 직접 이름을 준 서비스 — 짝·--changed 로 들어온 것과 가른다
CHANGED_REF=""
DRY_RUN=0
PARALLEL=0
ROLLBACK_ON_FAILURE=0
ALLOW_SPLIT=0

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)             DRY_RUN=1; shift ;;
        --parallel)            PARALLEL=1; shift ;;
        --rollback-on-failure) ROLLBACK_ON_FAILURE=1; shift ;;
        --allow-split)         ALLOW_SPLIT=1; shift ;;
        --changed)             [ $# -ge 2 ] || die "--changed 에 git ref 를 주세요 (예: --changed origin/main)"
                               CHANGED_REF="$2"; shift 2 ;;
        -h|--help)             sed -n '2,40p' "$0"; exit 0 ;;
        -*)                    die "모르는 옵션: $1" ;;
        *)                     known "$1" || die "모르는 서비스: $1
  대상: $ORDER"
                               has "$1" "$REQUESTED" || REQUESTED="$REQUESTED $1"
                               EXPLICIT="$EXPLICIT $1"; shift ;;
    esac
done

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"
[ -x ops/19-build-images.sh ] || die "저장소 루트에서 실행하세요"

# ── --changed: 바뀐 파일 → 서비스 ───────────────────────────────────────
#
# 모듈 디렉터리 이름이 곧 서비스(=배포) 이름이다. 모든 자바 서비스가 함께 쓰는 것(common, 루트 gradle 설정, 자바 런타임
# 이미지)이 바뀌면 자바 서비스 전부다 — 그 jar 들은 common 을 안에 담고 있다.
# ref 이후 바뀐 파일 → 서비스 목록(공백 구분)
services_changed_since() {
    local ref="$1" files out="" f s top
    files=$(git diff --name-only "$ref" HEAD) || return 1
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        case "$f" in
            common/*|build.gradle|settings.gradle|gradle.properties|gradle/*|ops/Dockerfile.runtime)
                for s in $JAVA_SERVICES; do has "$s" "$out" || out="$out $s"; done ;;
            ops/Dockerfile.frontend-runtime)
                has frontend "$out" || out="$out frontend" ;;
            */*)
                top="${f%%/*}"
                if known "$top" && ! has "$top" "$out"; then out="$out $top"; fi ;;
        esac
    done <<< "$files"
    echo "$out"
}

if [ "$CHANGED_REF" = "deployed" ]; then
    # 서비스마다 떠 있는 이미지의 태그가 기준이다(ops/19·28·31·33 은 태그를 커밋 SHA 로 붙인다).
    log "떠 있는 이미지 이후 바뀐 서비스"
    FROM_CHANGES=""
    for s in $ORDER; do
        img=$(kubectl -n "$NS" get "deploy/$s" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null) || continue
        [ -n "$img" ] || continue
        tag="${img##*:}"
        if ! git rev-parse --verify --quiet "$tag^{commit}" >/dev/null; then
            # 커밋으로 읽을 수 없는 태그(latest·옛 버전 번호·아직 fetch 하지 않은 커밋) — 모르면 올린다.
            info "$s: 태그 $tag 를 커밋으로 읽을 수 없어 넣습니다(git fetch 가 필요할 수 있습니다)"
            FROM_CHANGES="$FROM_CHANGES $s"
            continue
        fi
        if has "$s" "$(services_changed_since "$tag")"; then
            info "$s: $tag 이후 바뀜"
            FROM_CHANGES="$FROM_CHANGES $s"
        fi
    done
    [ -n "$FROM_CHANGES" ] || info "(없음 — 떠 있는 이미지가 모두 HEAD 와 같은 코드입니다)"
    for s in $FROM_CHANGES; do has "$s" "$REQUESTED" || REQUESTED="$REQUESTED $s"; done
elif [ -n "$CHANGED_REF" ]; then
    git rev-parse --verify --quiet "$CHANGED_REF^{commit}" >/dev/null \
        || die "git ref 를 찾을 수 없습니다: $CHANGED_REF (git fetch 가 필요할 수 있습니다)"
    FROM_CHANGES=$(services_changed_since "$CHANGED_REF") || die "git diff 에 실패했습니다"

    log "$CHANGED_REF 이후 바뀐 서비스"
    if [ -z "${FROM_CHANGES// /}" ]; then
        info "(없음 — 서비스 코드가 바뀌지 않았습니다)"
        if [ "$(git rev-parse "$CHANGED_REF^{commit}")" = "$(git rev-parse HEAD)" ]; then
            info "⚠️  $CHANGED_REF 는 HEAD 와 같은 커밋입니다 — 자기 자신과 비교했습니다. 떠 있는 이미지와 비교하려면: $0 --changed deployed"
        fi
    else
        info "$FROM_CHANGES"
    fi
    for s in $FROM_CHANGES; do has "$s" "$REQUESTED" || REQUESTED="$REQUESTED $s"; done
fi

[ -n "${REQUESTED// /}" ] || die "올릴 서비스가 없습니다.
  예: $0 catalog-service order-service
      $0 --changed deployed        # 떠 있는 이미지 이후 바뀐 것
      $0 --changed origin/main"

# ── 짝: user-service ↔ gateway-server ───────────────────────────────────
if [ "$ALLOW_SPLIT" = "0" ]; then
    if has user-service "$REQUESTED" && ! has gateway-server "$REQUESTED"; then
        REQUESTED="$REQUESTED gateway-server"
        info "user-service 와 짝인 gateway-server 를 더합니다(docs/27 — 끄려면 --allow-split)"
    elif has gateway-server "$REQUESTED" && ! has user-service "$REQUESTED"; then
        REQUESTED="$REQUESTED user-service"
        info "gateway-server 와 짝인 user-service 를 더합니다(docs/27 — 끄려면 --allow-split)"
    fi
fi

# ── 클러스터에 있는가 — 순서대로 정렬하며 거른다 ───────────────────────
#
# 직접 이름을 준 서비스가 없으면 오타이거나 잘못된 클러스터다 — 멈춘다. --changed 로만 고른 서비스가 없으면(이 환경에
# 배포하지 않는 모듈) 건너뛴다.
TARGETS=""
for s in $ORDER; do
    has "$s" "$REQUESTED" || continue
    if kubectl -n "$NS" get "deploy/$s" >/dev/null 2>&1; then
        TARGETS="$TARGETS $s"
    elif [ -n "$CHANGED_REF" ] && has "$s" "${FROM_CHANGES:-}"; then
        info "$s: $NS 에 배포돼 있지 않아 건너뜁니다"
    else
        die "$NS 에 deploy/$s 가 없습니다"
    fi
done
TARGETS="${TARGETS# }"
[ -n "$TARGETS" ] || die "올릴 서비스가 없습니다(대상이 모두 이 클러스터에 없습니다)"

# ── 태그 ────────────────────────────────────────────────────────────────
TAG="${TAG:-$(git rev-parse --short=12 HEAD 2>/dev/null || true)}"
[ -n "$TAG" ] || die "TAG 를 알 수 없습니다. TAG=... 로 지정하세요"

if [ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]; then
    # 커밋하지 않은 변경은 이미지에 들어가지만 태그(=커밋 SHA)에는 없다 — 나중에 이 태그가 무엇인지 답할 수 없다.
    warn "커밋하지 않은 변경이 있습니다. 이미지는 작업 트리로 만들어지지만 태그 $TAG 는 그것을 말해 주지 않습니다."
fi

# ── 서비스별 현재 이미지 ────────────────────────────────────────────────
#
# 지금 도는 이미지에서 이름·컨테이너·레지스트리를 읽는다(ops/31 과 같다). 여러 서비스의 레지스트리가 다르면 한 번의
# ops/19 호출로 만들 수 없다 — 멈춘다.
declare -A CONTAINER CURRENT NEW
REG_SEEN=""
SAME_TAG=""
KEPT=""
for s in $TARGETS; do
    img=$(kubectl -n "$NS" get "deploy/$s" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
    [ -n "$img" ] || die "$s 의 현재 이미지를 읽지 못했습니다"
    # 짝(user-service ↔ gateway-server)이나 --changed 로 들어왔는데 이미 이 태그로 떠 있으면 할 일이 없다 — 건너뛴다.
    # 🐛 예전에는 이것도 "같은 태그" 로 멈췄다: gateway-server 를 올리려는데 user-service 가 이미 같은 커밋이면 배포 전체가 중단.
    #    직접 이름을 준 서비스만 멈춘다 — 같은 태그로 다시 빌드하면 노드가 캐시된 옛 이미지를 쓰기 때문이다.
    if [ "${img##*:}" = "$TAG" ] && ! has "$s" "$EXPLICIT"; then
        info "$s: 이미 $TAG 로 떠 있어 건너뜁니다"
        continue
    fi
    KEPT="$KEPT $s"
    CONTAINER[$s]=$(kubectl -n "$NS" get "deploy/$s" -o jsonpath='{.spec.template.spec.containers[0].name}')
    CURRENT[$s]="$img"
    name="${img%:*}"
    NEW[$s]="$name:$TAG"
    [ "${img##*:}" = "$TAG" ] && SAME_TAG="$SAME_TAG $s"
    reg="${name%/ecommerce-msa/*}"
    if [ -z "$REG_SEEN" ]; then
        REG_SEEN="$reg"
    elif [ "$reg" != "$REG_SEEN" ] && [ -z "${REGISTRY:-}" ]; then
        die "서비스마다 레지스트리가 다릅니다($REG_SEEN, $reg — $s). REGISTRY=... 로 하나를 지정하세요"
    fi
done
REGISTRY="${REGISTRY:-$REG_SEEN}"
TARGETS="${KEPT# }"
[ -n "$TARGETS" ] || { info "올릴 것이 없습니다 — 대상이 모두 이미 $TAG 입니다"; exit 0; }

log "대상 (올리는 순서)"
i=0
for s in $TARGETS; do
    i=$((i + 1))
    info "$i. $s  ${CURRENT[$s]##*:} → $TAG"
done
info "레지스트리 : $REGISTRY"
info "방식       : $([ "$PARALLEL" = 1 ] && echo '한꺼번에 바꾸고 기다림(--parallel)' || echo '하나씩 — 실패하면 거기서 멈춤')"
info "실패하면   : $([ "$ROLLBACK_ON_FAILURE" = 1 ] && echo '이미 바꾼 것을 되돌림' || echo '되돌리기 명령을 출력')"

if [ -n "$SAME_TAG" ]; then
    # 같은 태그로 다시 올리면 노드가 캐시된 이미지를 그대로 쓴다(imagePullPolicy: IfNotPresent).
    warn "현재 태그와 같은 서비스가 있습니다:$SAME_TAG ($TAG)"
    warn "  이미지를 새로 만들어도 노드가 캐시를 씁니다. 커밋하고 새 SHA 로 하거나 TAG=... 로 다른 값을 주세요."
    die "중단합니다"
fi

rollback_cmds() {   # $@ = 되돌릴 서비스(바꾼 순서) → 역순으로 출력
    local list=("$@") k
    for ((k = ${#list[@]} - 1; k >= 0; k--)); do
        local s="${list[$k]}"
        echo "    kubectl -n $NS set image deploy/$s ${CONTAINER[$s]}=${CURRENT[$s]}"
    done
}

if [ "$DRY_RUN" = "1" ]; then
    log "--dry-run — 실제로는 아무것도 하지 않습니다"
    echo "    ./ops/19-build-images.sh --registry $REGISTRY --push --only \"$TARGETS\" --tag $TAG"
    for s in $TARGETS; do
        echo "    kubectl -n $NS set image deploy/$s ${CONTAINER[$s]}=${NEW[$s]}"
        [ "$PARALLEL" = 1 ] || echo "    kubectl -n $NS rollout status deploy/$s"
    done
    echo
    echo "  실패했을 때의 되돌리기(역순):"
    # shellcheck disable=SC2086
    rollback_cmds $TARGETS
    exit 0
fi

# ── 1. 빌드 — 한 번에 ───────────────────────────────────────────────────
log "1/3  이미지 빌드 ($TARGETS)"
./ops/19-build-images.sh --registry "$REGISTRY" --push --only "$TARGETS" --tag "$TAG" \
    || die "빌드에 실패했습니다 — 아무것도 바꾸지 않았습니다"

# ── 2·3. 교체와 롤아웃 ──────────────────────────────────────────────────
APPLIED=()
FAILED=""

set_image() {
    local s="$1"
    kubectl -n "$NS" set image "deploy/$s" "${CONTAINER[$s]}=${NEW[$s]}" >/dev/null || return 1
    APPLIED+=("$s")
    # ops/31 과 같은 형식 — rollout history 에서 전체 배포(ops/28)와 "(부분)" 으로 구분된다.
    kubectl -n "$NS" annotate "deploy/$s" \
        "kubernetes.io/change-cause=ops/33-deploy-some.sh $s $TAG (부분: $TARGETS) @ $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --overwrite >/dev/null 2>&1 || true
}

wait_rollout() {
    local s="$1"
    if kubectl -n "$NS" rollout status "deploy/$s" --timeout="${ROLLOUT_TIMEOUT:-600s}"; then
        ok "$s → $TAG"
        return 0
    fi
    kubectl -n "$NS" get pods -l "app=$s" \
        -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[0].ready,REASON:.status.containerStatuses[0].state.waiting.reason,RESTARTS:.status.containerStatuses[0].restartCount \
        2>/dev/null | sed 's/^/      /'
    return 1
}

if [ "$PARALLEL" = "1" ]; then
    log "2/3  이미지 교체 (한꺼번에)"
    for s in $TARGETS; do
        set_image "$s" || { FAILED="$s (set image)"; break; }
        info "$s → ${NEW[$s]}"
    done
    if [ -z "$FAILED" ]; then
        log "3/3  롤아웃 대기"
        for s in $TARGETS; do
            wait_rollout "$s" || FAILED="$FAILED $s"
        done
    fi
else
    log "2/3·3/3  하나씩 교체하고 롤아웃을 기다린다"
    for s in $TARGETS; do
        echo "  ── $s"
        set_image "$s" || { FAILED="$s (set image)"; break; }
        wait_rollout "$s" || { FAILED="$s"; break; }
    done
fi

if [ -n "$FAILED" ]; then
    warn "실패:${FAILED# }"
    if [ ${#APPLIED[@]} -eq 0 ]; then
        die "아무것도 바꾸지 못했습니다"
    fi
    if [ "$ROLLBACK_ON_FAILURE" = "1" ]; then
        log "되돌리기 (바꾼 것 ${#APPLIED[@]}개, 역순)"
        for ((k = ${#APPLIED[@]} - 1; k >= 0; k--)); do
            s="${APPLIED[$k]}"
            if ! kubectl -n "$NS" set image "deploy/$s" "${CONTAINER[$s]}=${CURRENT[$s]}" >/dev/null; then
                warn "$s 를 되돌리지 못했습니다 — 직접: kubectl -n $NS set image deploy/$s ${CONTAINER[$s]}=${CURRENT[$s]}"
            elif kubectl -n "$NS" rollout status "deploy/$s" --timeout="${ROLLOUT_TIMEOUT:-600s}" >/dev/null; then
                ok "$s → ${CURRENT[$s]##*:} (되돌림)"
            else
                # 이미지는 옛것으로 돌렸다. 옛 버전의 롤아웃이 안 끝나는 것은 이번 배포와 다른 원인(노드·DB 등)일 수 있다.
                warn "$s 는 옛 이미지로 돌렸지만 롤아웃이 끝나지 않았습니다 — kubectl -n $NS get pods -l app=$s"
            fi
        done
        die "배포를 되돌렸습니다. 원인을 확인하세요(kubectl -n $NS logs deploy/<서비스>)"
    fi
    echo
    echo "  이미 바꾼 서비스를 되돌리려면(역순):"
    rollback_cmds "${APPLIED[@]}"
    die "배포가 중간에 멈췄습니다 — 지금 클러스터에는 위 서비스들만 $TAG 입니다"
fi

cat <<EOF

  ⚠️ **검증되지 않은 부분 배포입니다.** 스모크 게이트를 거치지 않았고 배포 저널에도
     남지 않았습니다. 지금 클러스터는 [$TARGETS] 만 $TAG 이고 나머지는 다른 버전입니다.

  확인:
    GATEWAY=https://<도메인> ./ops/06-smoke-test.sh

  되돌리려면(역순):
$(rollback_cmds $TARGETS)

  원인을 잡았으면 전체를 한 버전으로 수렴시키세요 — 그래야 저널에 ok 가 남고
  다음 실패 때 되돌릴 지점이 생깁니다:
    ./ops/28-deploy.sh local $TAG
EOF

# ── 매니페스트(k8s/) 변경은 이 스크립트가 반영하지 않는다 ───────────────────────────────────
# 🐛 이미지만 바꾼다(kubectl set image). 서비스별 mTLS 신원(마운트·JAVA_TOOL_OPTIONS, docs/57 §16)을 매니페스트에 넣은 뒤
#    이것으로만 올렸더니, 파드는 새 이미지인데 옛 명세 그대로라 공유 인증서(CN=localhost)로 부르고 있었다 — ops/37 --callers 가
#    전부 localhost 였다. 올린 서비스들의 옛 태그 이후 k8s/ 가 바뀌었으면 알린다.
k8s_changed=""
for s in $TARGETS; do
    old="${CURRENT[$s]##*:}"
    git cat-file -e "${old}^{commit}" 2>/dev/null || continue
    k8s_changed+="$(git diff --name-only "$old" HEAD -- k8s/ 2>/dev/null)"$'\n'
done
k8s_changed=$(printf '%s' "$k8s_changed" | sed '/^$/d' | sort -u)
if [ -n "$k8s_changed" ]; then
    warn "매니페스트(k8s/)가 바뀌었는데 이 스크립트는 반영하지 않습니다(이미지만 바꿈):"
    printf '%s\n' "$k8s_changed" | sed 's/^/      /' >&2
    warn "  반영 — 서비스마다 지금 도는 태그를 그대로 두고 명세만 적용: ./ops/28-deploy.sh local --config-only"
fi
