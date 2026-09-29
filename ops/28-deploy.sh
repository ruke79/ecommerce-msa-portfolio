#!/usr/bin/env bash
#
# 배포 — **모든 환경에 같은 절차로.**
#
# 『Continuous Delivery』(Humble & Farley) 10장:
#
#     "It is essential to use the same process to deploy to every environment"
#     "On no account should you have a different process for backing out than
#      you do for deploying"
#
# 그래서 이 스크립트가 하나뿐이고, 환경은 **인자**다:
#
#   ./ops/28-deploy.sh dev  a1b2c3d4e5f6
#   ./ops/28-deploy.sh prod a1b2c3d4e5f6      # 같은 코드 경로. 오버레이만 다르다
#   ./ops/28-deploy.sh prod --rollback        # 되돌리기도 **같은 코드 경로**다
#
# 되돌리기에 별도 절차가 없다는 것이 핵심이다. 별도 절차는 정의상 거의 실행되지 않고,
# 그래서 정작 필요한 날 — 장애 한복판, 새벽 — 처음으로 실행되며, 그날 처음 버그가
# 드러난다. 여기서 롤백은 "직전에 잘 돌던 버전을 평소 쓰던 그 경로로 다시 배포한다"
# 이상도 이하도 아니다.
#
# ── 무엇을 검증하고 무엇을 거부하는가 ───────────────────────────────────
#
#   1. 레지스트리에 그 버전의 이미지가 **실제로 있는지** 먼저 확인한다.
#      없으면 클러스터를 건드리기 전에 죽는다. "이름이 해석된다"가 "거기 뭔가 있다"를
#      뜻하지 않는다는 것을 이 프로젝트에서 여러 번 겪었다(docs/33 §13).
#   2. 커밋된 오버레이 파일은 **절대 수정하지 않는다.** 태그 주입은 저장소 안의
#      임시 오버레이(.deploy/, gitignore 됨)에서 한다. 예전 절차는 운영자에게
#      `kustomize edit set image` 를 시켰는데, 그러면 작업 트리가 더러워지고
#      "지금 배포된 게 뭔지"가 커밋되지 않은 로컬 변경에 숨는다.
#   3. 배포 후 ops/06 스모크가 **게이트**다. 실패하면 직전 버전으로 자동 복귀한다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/28-deploy.sh <환경> <버전>
#   ./ops/28-deploy.sh <환경> --rollback        직전 성공 버전으로 되돌린다
#
# ⚠️ **되돌리기는 스키마를 되돌리지 않는다.** --rollback 은 이미지만 바꾼다. 마이그레이션은
#    앱이 뜰 때 적용되고 앞으로만 간다. 그래서 어떤 배포가 컬럼을 지우거나 이름을 바꿨다면
#    되돌린 옛 이미지는 Hibernate validate 에서 기동하지 못한다 — 있다고 믿는 순간에 없는
#    안전망이다. 배포 전에 무엇이 적용될지 보고, 그 뒤에 되돌릴 수 있는지 판단할 것:
#
#      ./ops/32-db-migration.sh pending     # 이 배포가 DB 를 무엇으로 바꾸나
#      ./ops/32-db-migration.sh drift       # 적용된 파일을 누가 고쳤나(고쳤으면 기동 실패)
#
#    규칙은 expand-contract 다(ops/32 주석).
#   ./ops/28-deploy.sh <환경> <버전> --dry-run  적용하지 않고 무엇이 바뀌는지만 본다
#   ./ops/28-deploy.sh --report                 배포 이력과 사이클 타임
#
#   ./ops/28-deploy.sh <환경> --config-only [--restart <deploy>]...
#       이미지는 그대로 두고 **설정만** 반영한다(ConfigMap·Secret·Ingress 등).
#
#       🔴 이럴 때 `kubectl apply -k k8s-overlays/<환경>` 을 직접 치면 안 된다.
#          그 오버레이의 `images:` 에는 손으로 적어 둔 `newTag` 가 박혀 있고, apply 는
#          **전 서비스를 그 태그로 되돌린다.** 실제로 그렇게 몇 주 전 버전으로 돌아갔다.
#          이 스크립트는 클러스터에서 지금 도는 태그를 읽어 그것으로 고정한 뒤 적용한다.
#
#       ConfigMap 은 해시 접미사가 없어서 바뀌어도 파드가 다시 뜨지 않는다. 값을 실제로
#       읽히려면 그 서비스를 재시작해야 한다 — 그것이 --restart 다:
#
#         ./ops/28-deploy.sh local --config-only --restart delivery-service
#
#   환경: k8s-overlays/ 아래 디렉터리 이름 (dev · prod · external · oci · local)
#   버전: 파이프라인이 붙인 커밋 SHA 12자. .github/workflows/build.yml 참조
#
#   옵션 환경변수:
#     GATEWAY=https://shop.example.com   스모크 대상. 미지정이면 Ingress 호스트에서
#                                        유추하고, 그것도 없으면 port-forward 한다
#     NO_SMOKE=1                         스모크 게이트 생략 (**검증되지 않은 배포**가 된다)
#     NO_ROLLBACK=1                      실패해도 자동 복귀하지 않는다 (원인 조사용)
#     DEPLOY_JOURNAL=<경로>              배포 이력 파일 (기본 ~/.ecommerce-msa/deploy-journal.tsv)
#     ROLLOUT_TIMEOUT=600s               기본값. 매니페스트의 startupProbe 예산(60×5초=300초)
#                                        보다 넉넉해야 한다 — 같으면 정상 기동이 타임아웃과
#                                        경합해서 진다(아래 설명)
#     APPLY_HPA=1                        HPA·PDB 를 강제로 적용한다. 기본은 prod 에서만
#                                        적용하고 단일 노드 환경(local·external·oci·dev)에서는
#                                        건너뛴다 — 이유는 아래 apply 블록 주석 참조
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

NS="${NS:-ecommerce}"
# ── 롤아웃 대기 시간 ────────────────────────────────────────────────────
#
# 예전 기본값은 300초였다. 그런데 모든 애플리케이션 매니페스트의 startupProbe 예산도
# **정확히 300초**다(failureThreshold 60 × periodSeconds 5). 둘이 같으면 컨테이너 시작
# 시간이 조금만 붙어도 정상 기동이 타임아웃에 진다 — 그러면 이 스크립트는 실패로 판정해
# **자동 복귀**시키고, 운영자는 "파드가 다시 Running 이네"를 보고 배포가 됐다고 오해한다.
# (실제로 겪었다: 마이그레이션 4개를 한꺼번에 도는 첫 배포에서 user-service 가 이렇게
#  롤백됐고, 구 ReplicaSet 파드가 Running 이라 성공처럼 보였다.)
#
# 그래서 startupProbe 예산의 두 배로 둔다. 진짜로 기동에 실패한 파드는 CrashLoopBackOff
# 로 먼저 드러나므로, 이 값을 늘린다고 장애를 늦게 아는 것은 아니다 — 늘어나는 것은
# "느리지만 결국 뜨는" 경우를 기다려 주는 여유뿐이다.
#
# ⚠️ 매니페스트의 failureThreshold·periodSeconds 를 올린다면 이 값도 같이 올려야 한다.
#    가드레일 B-43 이 두 값을 대조한다.
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-600s}"
JOURNAL="${DEPLOY_JOURNAL:-$HOME/.ecommerce-msa/deploy-journal.tsv}"

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
err()  { printf '  \033[0;31m✗\033[0m %s\n' "$*" >&2; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 인자

ENVIRONMENT=""
VERSION=""
DRY_RUN=0
DO_ROLLBACK=0
REPORT=0
CONFIG_ONLY=0
RESTART_DEPLOYS=""

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)  DRY_RUN=1; shift ;;
        --rollback) DO_ROLLBACK=1; shift ;;
        --report)   REPORT=1; shift ;;
        --config-only) CONFIG_ONLY=1; shift ;;
        --restart)  [ -n "${2:-}" ] || die "--restart 뒤에 Deployment 이름이 필요합니다"
                    RESTART_DEPLOYS="$RESTART_DEPLOYS $2"; shift 2 ;;
        -h|--help)  sed -n '2,60p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)         die "알 수 없는 옵션: $1" ;;
        *)
            if [ -z "$ENVIRONMENT" ]; then ENVIRONMENT="$1"
            elif [ -z "$VERSION" ];  then VERSION="$1"
            else die "인자가 너무 많습니다: $1"
            fi
            shift ;;
    esac
done

# ---------------------------------------------------------------- 배포 이력
#
# 왜 파일이 필요한가: 배포하고 나면 **직전 버전은 클러스터 어디에도 남지 않는다.**
# Deployment 의 revision history 에 남기는 하지만 이미지 태그를 되짚기 번거롭고,
# `kubectl rollout undo` 는 이 프로젝트가 원하는 것과 다르다 — undo 는 파드 스펙만
# 되돌리므로 오버레이가 그 사이에 바뀌었다면 다음 apply 에서 조용히 다시 앞으로 간다.
# 여기서는 "직전에 스모크까지 통과한 버전"을 명시적으로 적어 두고, 그것을 **정방향으로
# 다시 배포**한다.

journal_append() {   # journal_append <env> <version> <result>
    mkdir -p "$(dirname "$JOURNAL")" 2>/dev/null || return 0
    local committed
    committed=$(git show -s --format=%cI "$2" 2>/dev/null || echo "-")
    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "$3" "$committed" >> "$JOURNAL"
}

last_good() {        # last_good <env>  → 마지막으로 ok 로 끝난 버전
    [ -f "$JOURNAL" ] || return 0
    awk -F'\t' -v e="$1" '$2==e && $4=="ok" {v=$3} END {if (v) print v}' "$JOURNAL"
}

# ── --report: 사이클 타임 ────────────────────────────────────────────────
#
# 책이 유일한 전역 지표로 꼽는 것이 사이클 타임 — "커밋이 사용자에게 닿기까지".
# 그래서 저널에 커밋 시각을 함께 적어 두고 여기서 뺀다. 측정하지 않는 것은 개선되지
# 않는다. 배포 횟수가 아니라 **간격**을 보는 것이 요점이다.
to_epoch() {   # to_epoch <ISO8601>  → 초. 파싱 못 하면 빈 문자열(GNU/BSD date 모두 시도).
    date -u -d "$1" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s 2>/dev/null
}

humanize() {   # humanize <초>
    local s="$1"
    if   [ "$s" -lt 3600 ];  then printf '%d분' $(( s / 60 ))
    elif [ "$s" -lt 86400 ]; then printf '%d시간 %d분' $(( s / 3600 )) $(( (s % 3600) / 60 ))
    else printf '%d일 %d시간' $(( s / 86400 )) $(( (s % 86400) / 3600 ))
    fi
}

if [ "$REPORT" = "1" ]; then
    [ -f "$JOURNAL" ] || die "배포 이력이 없습니다: $JOURNAL
  아직 이 스크립트로 배포한 적이 없습니다."
    log "배포 이력  ($JOURNAL)"
    # printf 의 %-21s 는 **바이트**를 센다. 한글은 표시 폭 2칸에 3바이트라 헤더만
    # 폭이 어긋난다. 헤더는 그래서 손으로 맞춘 공백을 쓴다.
    echo "  배포시각             환경      버전           결과      사이클 타임(커밋→배포)"
    while IFS=$'\t' read -r at env ver result committed; do
        cycle="-"
        if [ "${committed:-}" != "-" ] && [ -n "${committed:-}" ]; then
            c=$(to_epoch "$committed"); d=$(to_epoch "$at")
            if [ -n "$c" ] && [ -n "$d" ] && [ "$d" -ge "$c" ]; then
                cycle="$(humanize $(( d - c )))"
            fi
        fi
        printf '  %-21s %-9s %-14s %-9s %s\n' "$at" "$env" "$ver" "$result" "$cycle"
    done < "$JOURNAL"
    echo
    echo "  사이클 타임은 책이 유일한 전역 지표로 꼽는 값입니다 — 커밋이 사용자에게 닿기까지."
    echo "  길다면 병목은 보통 파이프라인이 아니라 '배포를 누르기까지'에 있습니다."
    exit 0
fi

# ---------------------------------------------------------------- 환경 확인

[ -n "$ENVIRONMENT" ] || die "환경을 지정하세요. 예: $0 prod <버전>
  사용 가능: $(ls k8s-overlays 2>/dev/null | grep -v '\.example$' | tr '\n' ' ')"

OVERLAY="k8s-overlays/$ENVIRONMENT"
[ -f "$OVERLAY/kustomization.yaml" ] || die "오버레이가 없습니다: $OVERLAY/kustomization.yaml
  사용 가능: $(ls k8s-overlays 2>/dev/null | grep -v '\.example$' | tr '\n' ' ')"

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"

# 어느 클러스터에 붙어 있는지 **먼저 보여 준다.** 컨텍스트를 착각한 채 prod 를 때리는
# 사고는 이 계열에서 가장 흔하고 가장 비싸다.
CONTEXT=$(kubectl config current-context 2>/dev/null || echo "?")
kubectl cluster-info >/dev/null 2>&1 || die "클러스터에 닿지 않습니다 (context: $CONTEXT)"

# ---------------------------------------------------------------- 현재 버전 읽기
#
# 클러스터가 권위 있는 출처다. 저널은 롤백 대상을 기억하기 위한 것이고, "지금 뭐가
# 도는가"는 언제나 클러스터에 물어본다.

current_tag_of() {   # current_tag_of <deployment>
    local img
    img=$(kubectl -n "$NS" get deploy "$1" \
            -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null) || return 0
    [ -n "$img" ] || return 0
    printf '%s' "${img##*:}"
}

CURRENT_VERSION="$(current_tag_of gateway-server)"
[ -n "$CURRENT_VERSION" ] || CURRENT_VERSION="$(current_tag_of user-service)"

# 지금 도는 이미지들의 태그 — "이미지 짧은 이름<TAB>태그" 한 줄씩(Deployment 의 모든 컨테이너).
# --config-only 가 서비스마다 **자기** 태그로 고정하는 데 쓴다.
running_tags() {
    kubectl -n "$NS" get deploy \
        -o jsonpath='{range .items[*]}{range .spec.template.spec.containers[*]}{.image}{"\n"}{end}{end}' 2>/dev/null \
        | awk -F: 'NF>1 {tag=$NF; name=$0; sub(":" tag "$", "", name); n=split(name, p, "/"); print p[n] "\t" tag}' \
        | sort -u
}
RUNNING_TAGS=""

# --rollback: 버전을 저널에서 찾는다.
if [ "$DO_ROLLBACK" = "1" ]; then
    [ -z "$VERSION" ] || die "--rollback 과 버전을 함께 줄 수 없습니다 (되돌릴 버전은 이력이 정합니다)"
    VERSION="$(last_good "$ENVIRONMENT")"
    [ -n "$VERSION" ] || die "이 환경($ENVIRONMENT)에서 스모크까지 통과한 배포 이력이 없습니다: $JOURNAL
  되돌릴 버전을 직접 아는 경우 그냥 배포하세요:  $0 $ENVIRONMENT <버전>"
    if [ "$VERSION" = "$CURRENT_VERSION" ]; then
        # 마지막 성공 = 지금 도는 것. 그 앞의 성공을 찾는다.
        VERSION=$(awk -F'\t' -v e="$ENVIRONMENT" -v cur="$CURRENT_VERSION" \
            '$2==e && $4=="ok" && $3!=cur {v=$3} END {if (v) print v}' "$JOURNAL")
        [ -n "$VERSION" ] || die "지금 도는 $CURRENT_VERSION 말고는 성공한 배포 이력이 없습니다"
    fi
    warn "롤백 대상: $VERSION (현재 $CURRENT_VERSION)"
fi

# --config-only: 버전은 **클러스터가 정한다.** 사람이 태그를 적을 일이 없어야 한다 —
# 그 한 줄을 오버레이에서 읽어 오는 순간 옛 태그로 되돌리는 사고가 난다.
if [ "$CONFIG_ONLY" = "1" ]; then
    [ "$DO_ROLLBACK" = "0" ] || die "--config-only 와 --rollback 은 함께 쓸 수 없습니다"
    [ -z "$VERSION" ] || die "--config-only 에는 버전을 주지 않습니다 (지금 도는 것을 그대로 유지합니다)"
    VERSION="$CURRENT_VERSION"
    [ -n "$VERSION" ] || die "클러스터에서 현재 버전을 읽지 못했습니다 — 최초 배포라면 버전을 주고 배포하세요"
    # 🐛 예전에는 **게이트웨이의 태그 하나**로 전 서비스를 고정했다. ops/31·33 으로 몇 서비스만 새 태그로 올려 둔 상태(흔하다)
    #    에서 부르면 그 서비스들이 게이트웨이의 옛 태그로 **되돌아갔다** — 이 옵션이 막으려던 바로 그 사고다. 이제 서비스마다
    #    지금 도는 자기 태그로 고정한다(write_overlay). $VERSION 은 클러스터에 아직 없는 서비스에만 쓰인다.
    RUNNING_TAGS="$(running_tags)"
    [ -n "$RUNNING_TAGS" ] || die "클러스터에서 지금 도는 이미지를 읽지 못했습니다"
    if [ "$(printf '%s\n' "$RUNNING_TAGS" | cut -f2 | sort -u | wc -l | tr -d ' ')" -gt 1 ]; then
        info "설정만 반영 — 서비스마다 태그가 다르다(ops/31·33 으로 일부만 올린 상태). 각자 지금 도는 태그로 고정한다:"
        printf '%s\n' "$RUNNING_TAGS" | sed 's/^/      /; s/\t/ @ /'
    else
        info "설정만 반영 — 이미지는 현재 버전($VERSION)에 고정합니다"
    fi
fi

[ -n "$VERSION" ] || die "버전을 지정하세요. 파이프라인이 붙인 커밋 SHA 12자입니다.
  예: $0 $ENVIRONMENT $(git rev-parse --short=12 HEAD 2>/dev/null || echo a1b2c3d4e5f6)
  또는 직전 성공 버전으로: $0 $ENVIRONMENT --rollback"

# ---------------------------------------------------------------- 이미지 목록 해석
#
# 서비스 목록을 스크립트에 박아 두지 않는다. 오버레이를 렌더링해서 **거기 실제로 있는**
# 이미지를 읽는다. 서비스가 추가·제거되어도 이 스크립트를 고칠 일이 없고, 무엇보다
# "오버레이에는 있는데 배포 스크립트는 모르는 서비스"가 생기지 않는다.

log "오버레이 해석  ($OVERLAY)"

# kubectl 내장 kustomize 를 먼저 쓰고, 없으면 독립 kustomize 로 떨어진다.
# (kubectl 내장 버전이 구형이라 최신 문법에서 막히는 환경이 있다.)
render() {   # render <오버레이 경로>
    # 성공한 출력만 내보낸다. 곧바로 파이프로 흘리면 kubectl 이 절반쯤 찍고 죽었을 때
    # 그 절반이 폴백의 출력과 섞인다.
    local out
    if out=$(kubectl kustomize "$1" 2>/dev/null); then printf '%s\n' "$out"; return 0; fi
    if command -v kustomize >/dev/null 2>&1; then kustomize build "$1"; return $?; fi
    kubectl kustomize "$1"      # 실패 메시지를 보여 주기 위해 한 번 더
}

RENDERED="$(render "$OVERLAY" 2>&1)" \
    || {
        # 가장 흔한 실패: 같은 키를 두 번 쓴 YAML (예 — PORTONE_ENABLED: "true" 를 기존
        # "false" 줄을 지우지 않고 아래에 더함). kustomize 는 파일 이름 없이 행 번호만
        # 말하므로 어느 파일인지 여기서 찾아 준다.
        if printf '%s' "$RENDERED" | grep -q 'already defined'; then
            key=$(printf '%s' "$RENDERED" | sed -n 's/.*mapping key "\([^"]*\)" already defined.*/\1/p' | head -1)
            echo "  ↳ 같은 키('$key')가 한 파일에 두 번 있다. 값을 바꿔야지 줄을 더하면 안 된다:" >&2
            grep -rn --include='*.yaml' --include='*.yml' "^\s*$key:" k8s "$OVERLAY" 2>/dev/null \
                | sed 's/^/      /' >&2
            echo "  ↳ 로컬 값은 $OVERLAY/patch-local-endpoints.yaml 의 data 에 두면 베이스를 덮어쓴다(docs/49 §6)." >&2
        fi
        die "kustomize build 실패:
$RENDERED"
    }

# 이 저장소가 만드는 이미지만 대상이다(cloudflared·postgres 등 서드파티는 건드리지 않는다).
#
# ⚠️ `- image:` 형태도 잡아야 한다. 컨테이너 맵의 첫 키가 image 면 YAML 직렬화가
#    `      - image: ...` 로 나온다. 처음에 `^\s+image:` 만 봤더니 **frontend 하나가
#    조용히 빠졌다** — 12개여야 할 목록이 11개였고, 그대로 뒀으면 프론트엔드만 옛
#    버전으로 남아 "배포했는데 화면이 그대로"가 됐을 것이다.
IMAGES=$(printf '%s\n' "$RENDERED" \
    | grep -E '^[[:space:]]*-?[[:space:]]*image:[[:space:]]' \
    | sed -E 's/^[[:space:]]*-?[[:space:]]*image:[[:space:]]*//' | tr -d '"' \
    | grep -E '(^|/)ecommerce-msa/' | sort -u)

[ -n "$IMAGES" ] || die "오버레이에서 ecommerce-msa 이미지를 찾지 못했습니다.
  $OVERLAY 의 images: 항목이 아직 REPLACE_ME_OWNER 그대로일 수 있습니다."

# 태그를 떼서 이름만 남긴다. 이 이름들이 태그 주입의 대상이다.
IMAGE_NAMES=$(printf '%s\n' "$IMAGES" | sed 's/:[^:/]*$//' | sort -u)
info "$(printf '%s\n' "$IMAGE_NAMES" | wc -l | tr -d ' ') 개 이미지"
info "현재 배포 버전: ${CURRENT_VERSION:-(없음 — 최초 배포)}"
info "배포할 버전   : $VERSION"

# ── 되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE, B-54)이 이 범위에 있는가 ─────────────────────
#
# 🐛 2026-09-26: 85커밋을 한 번에 올리다 user-service·chat 이 기동에 실패했고, 스크립트는 **자동 복귀**했다. 그런데 그 사이
#    order(V16·V18)·pay(V6)·chat(V5)의 마이그레이션은 이미 성공해 컬럼·테이블을 지웠다 — 옛 이미지는 그 스키마에서 Hibernate
#    validate 로 기동하지 못했고("missing table orders.customer"), 복귀가 장애를 **키웠다.** 이미지는 되돌려도 스키마는 되돌리지
#    않는다. 이런 범위에서는 실패하면 **앞으로 고쳐야** 한다 — 자동 복귀를 끄고, 수동 --rollback 도 막는다.
unsafe_migrations() {   # $1=from $2=to — 그 사이에 들어온 ROLLBACK-UNSAFE 마이그레이션 파일. 커밋을 모르면 "?"
    git rev-parse -q --verify "$1^{commit}" >/dev/null 2>&1 && git rev-parse -q --verify "$2^{commit}" >/dev/null 2>&1 \
        || { echo "?"; return; }
    local lo="$1" hi="$2" f
    # 되돌리기(새 → 옛)도 같은 범위다 — 옛 쪽이 조상이 되게 맞춘다.
    git merge-base --is-ancestor "$hi" "$lo" 2>/dev/null && { lo="$2"; hi="$1"; }
    git diff --name-only --diff-filter=A "$lo" "$hi" -- '*/src/main/resources/db/migration/*.sql' 2>/dev/null \
        | while read -r f; do git show "$hi:$f" 2>/dev/null | grep -q "ROLLBACK-UNSAFE" && echo "$f"; done
}
UNSAFE=""
if [ -n "$CURRENT_VERSION" ] && [ "$CURRENT_VERSION" != "$VERSION" ]; then
    UNSAFE="$(unsafe_migrations "$CURRENT_VERSION" "$VERSION")"
    if [ "$UNSAFE" = "?" ]; then
        warn "두 버전 중 하나를 이 저장소에서 찾지 못해 되돌릴 수 없는 마이그레이션을 확인하지 못했습니다(git fetch?)."
        UNSAFE=""
    elif [ -n "$UNSAFE" ]; then
        warn "되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE)이 이 범위에 있습니다 — 실패해도 **자동 복귀하지 않습니다**:"
        printf '%s\n' "$UNSAFE" | sed 's/^/        /' >&2
        warn "  실패하면 앞으로 고칩니다(고친 이미지를 다시 배포). 여러 단계로 나눠 배포하도록 만든 전환이 있으면 그 순서를 지킵니다."
        if [ "$DO_ROLLBACK" = "1" ] && [ "${FORCE_UNSAFE_ROLLBACK:-0}" != "1" ]; then
            die "--rollback 은 이 범위를 지나 되돌립니다 — 옛 이미지가 지금 스키마에서 기동하지 못할 수 있습니다.
  각 파일의 ROLLBACK-UNSAFE 설명대로 스키마를 먼저 되돌린 뒤라면: FORCE_UNSAFE_ROLLBACK=1 $0 $ENVIRONMENT --rollback"
        fi
    fi
fi
info "클러스터      : $CONTEXT / ns=$NS"

# ---------------------------------------------------------------- 산출물이 실제로 있는가
#
# 클러스터를 건드리기 **전에** 확인한다. 없는 태그로 apply 하면 파드가 ImagePullBackOff
# 로 멈추고, 그때는 이미 옛 파드가 종료되기 시작한 뒤다 — 즉 장애가 난 다음에야 안다.

verify_images() {
    local prober="" missing=0 name
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        prober=docker
    elif command -v skopeo >/dev/null 2>&1; then
        prober=skopeo
    elif command -v crane >/dev/null 2>&1; then
        prober=crane
    else
        warn "docker·skopeo·crane 중 아무것도 없어 이미지 존재 확인을 건너뜁니다."
        warn "  태그가 잘못되면 ImagePullBackOff 로 **배포 중에** 드러납니다."
        return 0
    fi

    log "레지스트리에 $VERSION 이 있는지 확인 ($prober)"
    while IFS= read -r name; do
        [ -n "$name" ] || continue
        case "$prober" in
            docker) docker manifest inspect "$name:$VERSION" >/dev/null 2>&1 ;;
            skopeo) skopeo inspect "docker://$name:$VERSION" >/dev/null 2>&1 ;;
            crane)  crane manifest "$name:$VERSION" >/dev/null 2>&1 ;;
        esac
        if [ $? -eq 0 ]; then
            info "${name##*/}"
        else
            err "${name##*/}:$VERSION 없음"
            missing=$((missing+1))
        fi
    done <<EOF
$IMAGE_NAMES
EOF

    if [ "$missing" -gt 0 ]; then
        die "이미지 $missing 개를 레지스트리에서 찾지 못했습니다.

  · 파이프라인이 아직 안 끝났을 수 있습니다 — 릴리스 후보 승격까지 기다리세요
  · 비공개 패키지라면 로그인이 필요합니다:
        echo \"\$GHCR_PAT\" | docker login ghcr.io -u <USER> --password-stdin
  · 버전을 손으로 만들려면:  ./ops/19-build-images.sh --registry <레지스트리> --tag $VERSION --push"
    fi
}

# ---------------------------------------------------------------- 임시 오버레이 생성
#
# 커밋된 오버레이는 건드리지 않는다. 이 디렉터리는 .gitignore 되어 있고 매번 새로 쓴다.

WORK="$ROOT/.deploy/$ENVIRONMENT"

write_overlay() {   # write_overlay <디렉터리> <버전> [게이트웨이-고정-버전]
    local dir="$1" ver="$2" gw_pin="${3:-}" name short
    rm -rf "$dir"; mkdir -p "$dir"
    {
        echo "# ops/28-deploy.sh 가 생성한 파일입니다. 직접 고치지 마세요 — 매 배포마다 덮어씁니다."
        echo "apiVersion: kustomize.config.k8s.io/v1beta1"
        echo "kind: Kustomization"
        echo "resources:"
        # $dir 은 언제나 <저장소>/.deploy/<환경> 이므로 두 단계 위가 저장소 루트다.
        echo "  - ../../$OVERLAY"
        echo "images:"
        while IFS= read -r name; do
            [ -n "$name" ] || continue
            short="${name##*/}"
            echo "  - name: $name"
            local running=""
            [ -z "$RUNNING_TAGS" ] || running=$(printf '%s\n' "$RUNNING_TAGS" | awk -F'\t' -v n="$short" '$1==n {print $2; exit}')
            if [ -n "$gw_pin" ] && [ "$short" = "gateway-server" ]; then
                echo "    newTag: \"$gw_pin\""
            elif [ -n "$running" ]; then
                echo "    newTag: \"$running\""      # --config-only — 지금 도는 그 태그 그대로
            else
                echo "    newTag: \"$ver\""
            fi
        done <<EOF
$IMAGE_NAMES
EOF
    } > "$dir/kustomization.yaml"
}

# ---------------------------------------------------------------- 롤아웃 대기

# ⚠️ `kubectl apply -k` 는 Deployment 를 **한꺼번에** 바꾼다. 그러니 롤아웃은 동시에
#    진행되고, 이 함수가 순서대로 기다려도 전체 시간은 합이 아니라 **가장 늦는 하나**가
#    정한다. 아래 표의 초는 '이 단계가 시작된 뒤 몇 초에 끝났는가'다 — 각각이 얼마나
#    걸렸는가가 아니다. 꼬리가 누구인지 보는 것이 목적이다.
#
# 그리고 파드가 **스케줄되지 못하고 있는지**를 같이 본다. 이 클러스터는 단일 노드인데
# 앱 Deployment 에 strategy 가 없어서 기본값(replicas 1 → maxSurge 1)이 걸린다. 즉
# 롤아웃 중에는 서비스마다 파드가 둘이고, 13개면 요청 자원이 순간 두 배가 된다.
# 노드가 그걸 못 받으면 surge 파드가 Pending 으로 줄을 서고 롤아웃이 **직렬화된다** —
# 실패하지 않고 그냥 느려지기만 해서, 로그만 봐서는 영영 알 수 없다.
# 가장 새 파드의 기동 오류를 보여 준다 — 롤아웃 실패 직후, 자동 복귀로 그 파드가 사라지기 전에.
# 전체 로그가 아니라 실패를 말하는 줄만: Spring 의 APPLICATION FAILED TO START 블록,
# 예외의 Caused by, ERROR. DEBUG 폭포(LOG_LEVEL_SQL=debug 등) 속에서도 원인이 보이게.
show_startup_error() {   # show_startup_error <deployment>
    local d="$1" pod
    pod=$(kubectl -n "$NS" get pods -l "app=$d" --sort-by=.metadata.creationTimestamp \
        -o jsonpath='{.items[-1:].metadata.name}' 2>/dev/null)
    [ -n "$pod" ] || return 0
    printf '      ── %s 의 기동 오류 (새 파드 %s) ──\n' "$d" "$pod"
    {
        kubectl -n "$NS" logs "$pod" --tail=400 2>/dev/null
        kubectl -n "$NS" logs "$pod" --tail=400 --previous 2>/dev/null
    } | grep -E "APPLICATION FAILED TO START|Caused by:|\bERROR\b|Description:|Action:" \
      | grep -vE "DEBUG|^\s*$" | tail -12 | sed 's/^/      /'
    printf '      전체:  kubectl -n %s logs %s --previous | grep -B2 -A12 "APPLICATION FAILED"\n' "$NS" "$pod"
    printf '      (복귀 뒤 `kubectl logs deploy/%s` 는 **옛 파드** 라 이 오류가 보이지 않습니다)\n' "$d"
}

wait_rollouts() {   # wait_rollouts <deployment...>
    local d rc=0
    local phase_start pending_log sampler
    phase_start=$(date +%s)
    pending_log="$(mktemp)"

    # 대기하는 동안 Pending 파드를 표본으로 모은다. 백그라운드로 도는 이유: 앞선
    # deployment 를 기다리는 사이에 뒤엣것이 Pending 이어도 알아채야 하기 때문이다.
    (
        while :; do
            kubectl -n "$NS" get pods --field-selector=status.phase=Pending \
                -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null \
                >> "$pending_log"
            sleep 10
        done
    ) & sampler=$!
    # shellcheck disable=SC2064
    trap "kill $sampler 2>/dev/null" RETURN

    local timings=""
    for d in "$@"; do
        kubectl -n "$NS" get deploy "$d" >/dev/null 2>&1 || continue
        if kubectl -n "$NS" rollout status "deploy/$d" --timeout="$ROLLOUT_TIMEOUT" >/dev/null 2>&1; then
            timings="${timings}$(( $(date +%s) - phase_start ))|$d
"
            info "$d 롤아웃 완료 ($(( $(date +%s) - phase_start ))s)"
        else
            err "$d 롤아웃 실패/타임아웃"
            # 왜 못 떴는지를 여기서 바로 보여 준다. 이걸 안 찍으면 운영자가
            # describe/logs 를 손으로 찾아 헤매게 된다.
            kubectl -n "$NS" get pods -l "app=$d" \
                -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[0].ready,REASON:.status.containerStatuses[0].state.waiting.reason,RESTARTS:.status.containerStatuses[0].restartCount \
                2>/dev/null | sed 's/^/      /'
            # 🐛 기동 실패의 원인은 **새 파드**의 로그에 있다. 그런데 자동 복귀 뒤에
            #    `kubectl logs deploy/<이름>` 을 치면 옛 파드(멀쩡한 쪽)의 로그가 나온다 —
            #    운영자는 스케줄러가 도는 DEBUG 줄만 한참 보다가 "로그에 아무것도 없다" 고
            #    한다(실제로 겪었다: order-service 가 Stripe 빈 부재로 기동 실패했는데 옛
            #    파드의 아웃박스 폴링 로그만 보였다). 그래서 여기서, 되돌리기 전에, 가장 새
            #    파드의 기동 오류를 뽑아 둔다. 재시작 중이면 --previous 가 직전 시도다.
            show_startup_error "$d"
            rc=1
        fi
    done

    kill $sampler 2>/dev/null

    # 꼬리부터 본다 — 이 단계의 길이를 정한 것이 그것이다.
    if [ -n "$timings" ]; then
        printf '\n  이 단계에서 늦게 끝난 순서 (단계 시작 기준 초):\n'
        printf '%s' "$timings" | sort -rn | head -5 | while IFS='|' read -r secs name; do
            printf '    %5ss  %s\n' "$secs" "$name"
        done
    fi

    # Pending 이 관측됐다면 그게 느린 이유다. 단일 노드 + maxSurge 조합을 의심할 것.
    local pending
    pending=$(sort -u "$pending_log" 2>/dev/null | grep -c . || true)
    if [ "${pending:-0}" -gt 0 ]; then
        printf '\n  ⚠️ 대기 중 Pending 이던 파드 %s개 — 스케줄을 못 받아 롤아웃이 직렬화됐습니다:\n' "$pending"
        sort -u "$pending_log" | sed 's/^/      /' | head -10
        printf '      원인 확인:  kubectl -n %s describe pod <이름> | tail -15\n' "$NS"
        printf '      단일 노드라면 strategy 에 maxSurge: 0 / maxUnavailable: 1 을 고려하세요\n'
        printf '      (서비스마다 파드가 둘이 되지 않습니다. 대신 그 서비스는 재시작 동안 끊깁니다.)\n'
    fi
    rm -f "$pending_log"
    return $rc
}

# 기다릴 대상은 **우리가 버전을 바꾸는 Deployment 뿐이다.**
#
# 오버레이에는 우리 것이 아닌 Deployment 도 있다(cloudflared). 그것까지 기다리면,
# 이미 아파 있던 cloudflared 때문에 멀쩡한 배포가 실패로 판정되고 아무 도움도 안 되는
# 롤백이 돌아간다. 배포의 성패는 배포한 것으로만 판정해야 한다.
OUR_NAMES=$(printf '%s\n' "$IMAGE_NAMES" | sed 's|.*/||' | sort -u)
ALL_DEPLOYS=$(printf '%s\n' "$RENDERED" \
    | awk '/^kind: Deployment$/{d=1} d && /^  name: /{print $2; d=0}' | sort -u)
MANAGED_DEPLOYS=$(comm -12 <(printf '%s\n' "$ALL_DEPLOYS") <(printf '%s\n' "$OUR_NAMES"))
OTHER_DEPLOYS=$(printf '%s\n' "$MANAGED_DEPLOYS" | grep -vx -e user-service -e gateway-server || true)

# ---------------------------------------------------------------- 리비전 주석
#
# `kubectl rollout history` 는 ReplicaSet 의 `kubernetes.io/change-cause` 를 읽는다.
# 아무도 적어 주지 않으면 전부 `<none>` 이고, "리비전 37 이 어느 커밋인가"를 알려면
# ReplicaSet 을 전부 꺼내 이미지 태그를 눈으로 대조해야 한다. 실제로 그렇게 됐다 —
# 리비전 38개가 전부 `<none>` 이라, 무엇이 배포돼 있는지 확인하는 데만 한참 걸렸다.
#
# Deployment 의 **metadata 주석**을 달면 컨트롤러가 동기화 때 그 시점의 ReplicaSet 으로
# 복사한다. 사라진 `kubectl --record` 가 하던 일이 정확히 이것이다. 주석을 다는 행위
# 자체가 동기화를 깨우므로 apply 직후에 달아도 새 ReplicaSet 에 실린다.
#
# ⚠️ 파드 템플릿(spec.template)에는 **절대** 넣지 않는다. 거기 넣으면 주석 한 줄이
#    템플릿 해시를 바꿔, 내용이 완전히 같아도 배포할 때마다 새 롤아웃이 돌아간다.
stamp_change_cause() {   # stamp_change_cause <버전> <deployment...>
    local ver="$1"; shift
    local cause d
    cause="ops/28-deploy.sh $ENVIRONMENT $ver @ $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    for d in "$@"; do
        kubectl -n "$NS" get deploy "$d" >/dev/null 2>&1 || continue
        # 실패해도 배포를 멈추지 않는다 — 기록이 없는 것이 배포가 안 되는 것보다 낫다.
        kubectl -n "$NS" annotate deploy "$d" \
            "kubernetes.io/change-cause=$cause" --overwrite >/dev/null 2>&1 || true
    done
}

# ---------------------------------------------------------------- 스모크 게이트
#
# 배포는 "파드가 Running" 이 아니라 "요청이 정상 응답한다" 로 끝난다. 이 프로젝트는
# 파드가 전부 Running 인데 사이트가 522 를 뱉는 상태를 실제로 겪었다(docs/33 §2).

SMOKE_PF_PID=""
cleanup_pf() { [ -n "$SMOKE_PF_PID" ] && kill "$SMOKE_PF_PID" 2>/dev/null; SMOKE_PF_PID=""; }
trap cleanup_pf EXIT

# ⚠️ 결과를 echo 로 돌려주지 않고 전역 SMOKE_TARGET 에 담는다.
#    $(...) 로 부르면 port-forward 가 **서브셸에서** 뜨고 그 PID 를 부모가 알 수 없다.
#    그러면 스크립트가 끝나도 port-forward 가 남는다 — trap 이 죽일 대상을 못 찾는다.
SMOKE_TARGET=""
# 스모크에 넘길 부가 설정. resolve_gateway 가 대상에 맞게 정한다.
SMOKE_INSECURE=""
SMOKE_SKIP_DIRECT=""
resolve_gateway() {
    if [ -n "${GATEWAY:-}" ]; then SMOKE_TARGET="$GATEWAY"; return 0; fi

    # Ingress 의 호스트를 쓴다. 없으면(호스트 미지정 Ingress 가 이 저장소의 기본값이다 —
    # k8s/60-ingress.yaml 참조) port-forward 로 게이트웨이에 직접 붙는다.
    # 대상을 못 찾았다고 게이트를 조용히 건너뛰지는 않는다.
    local host
    host=$(printf '%s\n' "$RENDERED" | awk '/^kind: Ingress$/{i=1} i && /^ *- host: /{print $3; exit}')
    if [ -n "$host" ]; then SMOKE_TARGET="https://$host"; return 0; fi

    kubectl -n "$NS" port-forward svc/gateway-server 18000:8000 >/dev/null 2>&1 &
    SMOKE_PF_PID=$!
    # 포트가 열릴 때까지 최대 10초. 고정 sleep 보다 빠르고, 안 열리면 확실히 안다.
    local i
    for i in $(seq 1 20); do
        if (exec 3<>/dev/tcp/127.0.0.1/18000) 2>/dev/null; then exec 3<&- 3>&-; break; fi
        sleep 0.5
    done

    # ⚠️ **https 다.** 게이트웨이는 스스로 TLS 를 종단한다(profile local 의 server.ssl,
    #    포트 8000). 예전에는 여기가 `http://` 였고, 그래서 스모크의 모든 요청이 톰캣에서
    #    400 "This combination of host and port requires TLS" 로 튕겼다. 즉 **스모크 게이트가
    #    구조적으로 통과할 수 없었다** — 무엇을 배포하든 실패 판정을 받고 자동 복귀했다.
    #    저널에 ok 가 한 줄도 없던 이유다. (docs/39 §7.7)
    SMOKE_TARGET="https://localhost:18000"
    # 게이트웨이 인증서는 ops/07 이 만든 자체 서명이다. 이 구간은 로컬 포트포워드라
    # 중간자가 낄 자리가 없으므로 검증을 끈다.
    SMOKE_INSECURE=1
    # ⚠️ 개별 서비스 직접 점검을 끈다. ops/06 은 $GATEWAY 가 localhost 면 "로컬 실행"으로
    #    보고 localhost:8080 등을 직접 찌르는데, 여기서 localhost 인 것은 포트포워드 때문이고
    #    서비스 포트는 클러스터 밖으로 나오지 않는다. 그대로 두면 가짜 실패 7건이 나고
    #    (마지막 코드 000) 진짜 문제가 그 사이에 묻힌다. k8s 프로브가 이미 같은 일을 한다.
    SMOKE_SKIP_DIRECT=1
}

run_smoke() {
    if [ "${NO_SMOKE:-0}" = "1" ]; then
        warn "NO_SMOKE=1 — 스모크 게이트를 건너뜁니다. **검증되지 않은 배포**입니다."
        return 0
    fi
    resolve_gateway
    log "스모크 테스트  ($SMOKE_TARGET)"
    # SKIP_DIRECT 는 빈 값이면 ops/06 이 스스로 판단한다 — 그 자동 판단을 덮어써야 하는
    # 경우(포트포워드)에만 값이 들어 있다.
    GATEWAY="$SMOKE_TARGET" \
        INSECURE="${SMOKE_INSECURE:-${INSECURE:-0}}" \
        SKIP_DIRECT="${SMOKE_SKIP_DIRECT:-${SKIP_DIRECT:-}}" \
        ./ops/06-smoke-test.sh
    local rc=$?
    cleanup_pf
    return $rc
}

# ---------------------------------------------------------------- 배포 본체
#
# ⚠️ user-service 를 gateway-server 보다 먼저 올린다.
#    게이트웨이가 uid 클레임 없는 토큰을 거부하므로, 게이트웨이가 먼저 새 버전이 되면
#    아직 옛 user-service 가 발급한 토큰이 전부 401 이 된다(docs/27).
#    그래서 1단계에서는 게이트웨이만 **현재 버전에 고정**해 두고 나머지를 올린 뒤,
#    2단계에서 게이트웨이를 올린다. 최초 배포(현재 버전 없음)에서는 단계를 나누지 않는다.

deploy_version() {   # deploy_version <버전>
    local ver="$1"

    if [ -n "$CURRENT_VERSION" ] && [ "$CURRENT_VERSION" != "$ver" ]; then
        log "1/2  게이트웨이를 제외한 서비스 → $ver"
        write_overlay "$WORK" "$ver" "$CURRENT_VERSION"
        kubectl apply -k "$WORK" || return 1
        # 게이트웨이는 아직 $CURRENT_VERSION 에 고정돼 있으므로 여기서 찍지 않는다 —
        # 실제로 배포된 것과 다른 버전이 이력에 남으면 없느니만 못하다.
        # shellcheck disable=SC2086
        stamp_change_cause "$ver" user-service $OTHER_DEPLOYS
        # shellcheck disable=SC2086
        wait_rollouts user-service $OTHER_DEPLOYS || return 1

        log "2/2  게이트웨이 → $ver"
        write_overlay "$WORK" "$ver"
        kubectl apply -k "$WORK" || return 1
        stamp_change_cause "$ver" gateway-server
        wait_rollouts gateway-server || return 1
    else
        log "전체 서비스 → $ver"
        write_overlay "$WORK" "$ver"
        kubectl apply -k "$WORK" || return 1
        # shellcheck disable=SC2086
        stamp_change_cause "$ver" user-service $OTHER_DEPLOYS gateway-server
        # shellcheck disable=SC2086
        wait_rollouts user-service $OTHER_DEPLOYS gateway-server || return 1
    fi

    # HPA·PDB 는 오버레이 밖에 있다(k8s-overlays/prod 주석 참조 — kustomize 는 오버레이
    # 디렉터리 밖의 낱개 파일을 참조하지 못한다). 버전과 무관하지만 배포마다 같이 맞춰 둔다.
    #
    # ⚠️ **prod 에서만 적용한다.** 예전에는 환경과 무관하게 매번 적용했는데, 저장소의 다른
    #    문서들은 정반대를 말하고 있었다:
    #      · k8s-overlays/external  "어느 쪽이든 k8s/70-autoscaling.yaml 은 적용하지 않는다"
    #      · ops/23-oci-ampere      "어느 쪽이든 ... 적용하지 마세요 (HPA min=2 → 5,300m)"
    #      · k8s-overlays/local.example "단일 노드라면 HPA 를 지울 것"
    #
    #    그래서 운영자가 `kubectl delete hpa --all` 로 지워도 **다음 배포가 되살렸다.**
    #    그 결과가 단일 노드에서 연쇄로 나타났다: 서비스마다 파드가 최소 2개 → 배포 때
    #    새 파드 2개가 동시에 기동 → startupProbe 예산(300초)을 넘김 → 롤아웃 타임아웃 →
    #    자동 복귀. "배포했는데 그 서비스만 옛 버전"의 정체였다(실측: gateway-server).
    #
    #    prod 는 replicas 를 Deployment 에서 아예 지우고 HPA 가 소유하는 모델이라 필요하다.
    #    단일 노드 환경(local·external·oci·dev)에서는 필요하지도, 들어가지도 않는다.
    #    그래도 켜고 싶으면 APPLY_HPA=1 로 강제한다.
    if [ "${APPLY_HPA:-auto}" = "1" ] \
            || { [ "${APPLY_HPA:-auto}" = "auto" ] && [ "$ENVIRONMENT" = "prod" ]; }; then
        kubectl apply -f k8s/70-autoscaling.yaml >/dev/null 2>&1 \
            && info "HPA·PDB 적용" || warn "HPA·PDB 적용 실패 (무시하고 계속합니다)"
    else
        info "HPA·PDB 건너뜀 (환경=$ENVIRONMENT — 단일 노드에서는 적용하지 않는다. 강제하려면 APPLY_HPA=1)"

        # 적용하지 않는 것과 **없는 것**은 다르다.
        #
        # 이 스크립트가 안 만들어도 HPA 는 다른 경로로 들어올 수 있다 — 손으로 친
        # `kubectl apply -f k8s/70-autoscaling.yaml`(ops/23 과 k8s/README 의 안내문에
        # 그대로 적혀 있다), 예전 버전의 이 스크립트, 다른 창에서 돌린 무언가.
        # 그러면 minReplicas=2 가 여기서 정한 replicas=1 을 15초 안에 되돌리고,
        # 단일 노드에서는 그것이 곧 기동 경합 → startupProbe 초과 → 롤아웃 실패다.
        #
        # 지우지는 않는다. 운영자가 일부러 켰을 수도 있고, 배포 스크립트가 남의 리소스를
        # 조용히 삭제하는 것은 그 자체로 놀라운 동작이다. 대신 **보이게** 한다 —
        # 이 경고가 없으면 증상이 "배포하면 파드가 자꾸 두 개가 된다"로만 나타난다.
        local hpas
        hpas=$(kubectl -n "$NS" get hpa -o name 2>/dev/null | wc -l | tr -d ' ')
        if [ "${hpas:-0}" -gt 0 ]; then
            warn "이 환경($ENVIRONMENT)에 HPA 가 $hpas 개 남아 있습니다 — 이 스크립트가 만든 것이 아닙니다"
            warn "  minReplicas=2 가 오버레이의 replicas 를 되돌립니다. 단일 노드라면 지우세요:"
            warn "    kubectl -n $NS delete hpa --all"
            warn "  누가 만들었는지는 이렇게 확인합니다:"
            warn "    kubectl -n $NS get hpa -o yaml --show-managed-fields | grep -E 'manager:|time:'"
        fi
    fi

    return 0
}

# ---------------------------------------------------------------- 서비스별 신원 Secret 이 있는가
#
# 백엔드 매니페스트(k8s/40~49)는 자기 신원 Secret(tls-id-<서비스>)을 마운트한다(docs/57 §16). 없으면 새 파드가
# ContainerCreating 에서 멈추고 롤아웃이 시간 초과로 끝난다 — 적용하기 **전에** 막는다.
check_identity_secrets() {
    local missing="" name
    for name in $(grep -ho 'secretName: tls-id-[a-z-]*' "$ROOT"/k8s/*.yaml 2>/dev/null | awk '{print $2}' | sort -u); do
        kubectl -n "$NS" get secret "$name" >/dev/null 2>&1 || missing="$missing $name"
    done
    [ -z "$missing" ] || die "서비스별 신원 Secret 이 없습니다:$missing
  먼저 발급하세요: ./ops/38-issue-service-identities.sh   (ops/07 의 CA 로, 값은 출력하지 않는다)"
}

# ---------------------------------------------------------------- 실행

# --config-only 는 지금 도는 이미지를 그대로 쓰므로 레지스트리 확인이 필요 없다
# (태그가 레지스트리에서 지워졌어도 노드에는 남아 있다).
[ "$CONFIG_ONLY" = "1" ] || verify_images
check_identity_secrets

if [ "$CONFIG_ONLY" = "1" ]; then
    log "설정 반영 — $ENVIRONMENT (이미지 $VERSION 유지)"
    write_overlay "$WORK" "$VERSION"
    if [ "$DRY_RUN" = "1" ]; then
        kubectl apply -k "$WORK" --dry-run=server || die "적용(dry-run) 실패"
        exit 0
    fi
    kubectl apply -k "$WORK" || die "적용 실패"
    for d in $RESTART_DEPLOYS; do
        kubectl -n "$NS" get deploy "$d" >/dev/null 2>&1 \
            || die "그런 Deployment 가 없습니다: $d"
        kubectl -n "$NS" rollout restart "deploy/$d" >/dev/null || die "$d 재시작 실패"
        info "$d 재시작"
    done
    # shellcheck disable=SC2086
    [ -z "$RESTART_DEPLOYS" ] || wait_rollouts $RESTART_DEPLOYS || die "재시작한 서비스가 뜨지 않았습니다"
    if [ -z "$RESTART_DEPLOYS" ]; then
        warn "ConfigMap 은 바뀌어도 이미 떠 있는 파드가 읽지 않습니다 — 값을 반영하려면:"
        warn "  $0 $ENVIRONMENT --config-only --restart <deploy>"
    fi
    log "설정 반영 완료 — $ENVIRONMENT @ $VERSION"
    exit 0
fi

if [ "$DRY_RUN" = "1" ]; then
    log "DRY RUN — 적용하지 않습니다"
    write_overlay "$WORK" "$VERSION"
    printf '%s\n' "$IMAGE_NAMES" | sed "s|^|  |; s|$|:$VERSION|"
    echo
    if [ -n "$CURRENT_VERSION" ]; then
        echo "  현재 $CURRENT_VERSION → $VERSION"
        if [ "$CURRENT_VERSION" = "$VERSION" ]; then
            warn "같은 버전입니다 — 파드는 재시작되지 않습니다"
        fi
    fi
    echo
    echo "  적용하려면 --dry-run 을 빼고 다시 실행하세요."
    exit 0
fi

START_EPOCH=$(date -u +%s)

if deploy_version "$VERSION" && run_smoke; then
    journal_append "$ENVIRONMENT" "$VERSION" ok
    ELAPSED=$(( $(date -u +%s) - START_EPOCH ))
    log "배포 완료 — $ENVIRONMENT @ $VERSION  (${ELAPSED}초)"
    info "되돌리려면:  $0 $ENVIRONMENT --rollback   (→ ${CURRENT_VERSION:-이력 참조})"
    info "이력·사이클 타임:  $0 --report"
    exit 0
fi

# ── 여기부터는 실패 경로 ────────────────────────────────────────────────

journal_append "$ENVIRONMENT" "$VERSION" fail
err "배포 또는 스모크가 실패했습니다: $ENVIRONMENT @ $VERSION"

if [ "${NO_ROLLBACK:-0}" = "1" ]; then
    warn "NO_ROLLBACK=1 — 되돌리지 않고 현재 상태를 유지합니다(원인 조사용)."
    warn "  조사가 끝나면:  $0 $ENVIRONMENT --rollback"
    exit 1
fi

if [ -z "$CURRENT_VERSION" ] || [ "$CURRENT_VERSION" = "$VERSION" ]; then
    warn "되돌릴 직전 버전이 없습니다(최초 배포이거나 같은 버전). 수동 조치가 필요합니다."
    exit 1
fi

if [ -n "$UNSAFE" ]; then
    err "자동 복귀하지 않습니다 — 되돌릴 수 없는 마이그레이션이 이미 적용됐을 수 있습니다(위 목록)."
    err "  옛 이미지는 그 스키마에서 기동하지 못할 수 있어, 복귀가 장애를 키웁니다. **앞으로 고칩니다:**"
    err "    ① 위의 서비스별 기동 오류를 고친 이미지를 만들고  ② $0 $ENVIRONMENT <새 버전>"
    err "  지금 새 버전으로 뜬 서비스는 그대로 돌고, 실패한 서비스는 옛 파드가 계속 돕니다(롤링이 멈춘 상태)."
    exit 1
fi

# ⭐ 여기가 책의 처방이 그대로 드러나는 곳이다. 롤백 전용 경로를 타지 않는다 —
#    **평소 쓰는 배포 함수를 직전 버전으로 다시 호출할 뿐이다.** 그래서 이 경로는
#    한 번도 따로 테스트할 필요가 없다. 매 배포가 곧 이 경로의 테스트다.
log "자동 복귀 — 직전 버전 $CURRENT_VERSION 을 다시 배포합니다"

ROLLBACK_TO="$CURRENT_VERSION"

# 복귀는 **한 번에 전부** 되돌린다(단계를 나누지 않는다).
#
# 2단계 순서(user-service 먼저, gateway 나중)는 앞으로 갈 때만 성립하는 규칙이다.
# 뒤로 갈 때 같은 순서를 쓰면 오히려 옛 user-service + 새 gateway 조합이 생겨
# 문제를 키운다. 반대 순서를 새로 만들면 그것이야말로 "배포와 다른 별도 절차" —
# 거의 실행되지 않아 필요한 날 처음 버그가 드러나는 그 경로다.
#
# 그래서 복귀는 책이 말하는 그대로 한다: **직전에 잘 돌던 구성 전체를 그대로
# 다시 배포한다**("redeploy the previous good version from scratch"). 부분적으로
# 되감지 않으니 조합의 수가 늘지 않는다.
#
# 근본 해법은 순서가 아니라 호환성이다 — 게이트웨이가 두 형태의 토큰을 모두 받으면
# 순서 자체가 사라진다(docs/36 §5 의 남은 과제).
CURRENT_VERSION=""

if deploy_version "$ROLLBACK_TO"; then
    journal_append "$ENVIRONMENT" "$ROLLBACK_TO" rollback
    if NO_SMOKE=0 run_smoke; then
        info "복귀 완료 — $ROLLBACK_TO 로 서비스가 정상입니다"
    else
        err "복귀했지만 스모크가 여전히 실패합니다 — 원인이 이번 버전이 아닐 수 있습니다"
        err "  인프라(DB·Kafka·터널) 쪽을 보세요: docs/33 §13 의 '층이 여럿인 경로'"
    fi
else
    err "복귀조차 실패했습니다. 수동 개입이 필요합니다:"
    err "    kubectl -n $NS get pods"
    err "    kubectl -n $NS describe deploy/gateway-server"
fi

exit 1
