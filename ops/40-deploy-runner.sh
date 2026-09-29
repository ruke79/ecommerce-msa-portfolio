#!/usr/bin/env bash
#
# 운영 액션 ㊵ — OCI Ampere(arm64) 인스턴스에 GitHub Actions **셀프호스티드 러너**를 설치한다 (docs/36 §6).
#
# 왜: 클러스터는 인바운드가 SSH 22번(특정 IP) 하나뿐이라 GitHub 호스티드 러너가 kube-apiserver 에 닿지 못한다. 러너는
# GitHub 쪽으로 **나가는 연결만** 쓴다 — 포트를 더 열지 않는다. 배포 워크플로(.github/workflows/deploy-oci.yml)가 이 러너에서
# `ops/28-deploy.sh` 를 실행한다(Environment `production` 승인 뒤).
#
# 러너는 **운영자와 같은 사용자**로 돈다 — 그래야 ~/.kube/config(클러스터)·~/.docker/config.json(GHCR)·배포 이력
# (~/.ecommerce-msa/deploy-journal.tsv)이 손으로 배포할 때와 같다. 별도 사용자를 만들면 이력이 둘로 갈라진다.
#
# 사용:
#   ./ops/40-deploy-runner.sh check        # 설치 전 점검 — arm64·kubectl·docker·오버레이·저장소 공개 여부
#   ./ops/40-deploy-runner.sh install      # 받기 → 등록(토큰은 입력으로) → 서비스로 시작
#   ./ops/40-deploy-runner.sh status       # 서비스 상태
#   ./ops/40-deploy-runner.sh uninstall    # 서비스 정지·해제 → 등록 해제(토큰 필요)
#
# 등록 토큰: GitHub 저장소 → Settings → Actions → Runners → New self-hosted runner 화면의 `--token` 값(1시간 유효).
# 🔒 토큰은 **명령줄 인자로 넘기지 않는다**(ps·셸 이력에 남는다) — 화면에서 입력받아 환경 변수로만 config.sh 에 넘긴다.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_DIR="$(pwd)"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner}"
REPO_URL="${REPO_URL:-https://github.com/ruke79/ecommerce-msa}"
LABELS="${LABELS:-oci-ampere}"
RUNNER_NAME="${RUNNER_NAME:-oci-ampere-$(hostname -s)}"
DEPLOY_ENV="${DEPLOY_ENV:-local}"
NS="${NAMESPACE:-ecommerce}"

ok()   { echo "  ✅ $*"; }
info() { echo "  ℹ️  $*"; }
warn() { echo "  ⚠️  $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

check() {
    step "설치 전 점검"
    local bad=0
    case "$(uname -m)" in
        aarch64|arm64) ok "arm64 — 파이프라인이 발행한 arm64 이미지를 에뮬레이션 없이 쓴다" ;;
        *) warn "arm64 가 아니다($(uname -m)) — 라벨 ARM64 와 맞지 않아 배포 잡이 이 러너를 고르지 않는다"; bad=1 ;;
    esac
    [ "$(id -u)" != "0" ] || { warn "root 로 돌리지 말 것 — 운영자 계정(ubuntu)으로"; bad=1; }
    if command -v kubectl >/dev/null && kubectl -n "$NS" get deploy >/dev/null 2>&1; then
        ok "kubectl — $NS 네임스페이스에 닿는다(~/.kube/config)"
    else
        warn "kubectl 로 $NS 에 닿지 못한다 — 이 사용자의 ~/.kube/config 를 확인"; bad=1
    fi
    if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
        ok "docker — ops/28 이 배포 전에 이미지 존재를 확인한다"
    else
        warn "docker 를 쓸 수 없다 — ops/28 이 이미지 확인을 건너뛴다(없는 태그로 배포할 수 있다). docker 그룹에 이 사용자를 넣을 것"
    fi
    if [ -f "$REPO_DIR/k8s-overlays/$DEPLOY_ENV/kustomization.yaml" ]; then
        ok "로컬 오버레이 k8s-overlays/$DEPLOY_ENV — 배포 잡이 여기를 링크한다(저장소 Variable DEPLOY_REPO_DIR=$REPO_DIR)"
    else
        warn "k8s-overlays/$DEPLOY_ENV/kustomization.yaml 이 없다 — 손으로 배포하던 그 사본에서 실행할 것"; bad=1
    fi
    info "저장소는 **비공개**여야 한다 — 공개 저장소의 셀프호스티드 러너는 포크 PR 의 코드를 실행할 수 있다(GitHub 권고)"
    info "Settings → Environments → production → Required reviewers 에 승인자를 넣을 것(승인 없이는 배포되지 않게)"
    return $bad
}

latest_version() {
    curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
        | sed -n 's/.*"tag_name": *"v\([^"]*\)".*/\1/p' | head -1
}

install() {
    check || die "점검을 먼저 통과시킬 것"
    command -v curl >/dev/null || die "curl 이 필요합니다"

    step "러너 받기 — $RUNNER_DIR"
    local ver="${RUNNER_VERSION:-$(latest_version)}"
    [ -n "$ver" ] || die "러너 최신 버전을 읽지 못했다 — RUNNER_VERSION=2.x.y 로 지정"
    mkdir -p "$RUNNER_DIR"
    if [ -x "$RUNNER_DIR/config.sh" ]; then
        info "이미 받아 두었다 — 건너뜀($RUNNER_DIR)"
    else
        local tgz="actions-runner-linux-arm64-$ver.tar.gz"
        curl -fsSL -o "$RUNNER_DIR/$tgz" "https://github.com/actions/runner/releases/download/v$ver/$tgz"
        # 릴리스가 알리는 SHA-256 과 대조한다(받은 파일이 그 파일인가).
        local want
        want=$(curl -fsSL "https://api.github.com/repos/actions/runner/releases/tags/v$ver" \
            | tr ',' '\n' | grep -A2 "\"name\": *\"$tgz\"" | sed -n 's/.*"digest": *"sha256:\([0-9a-f]*\)".*/\1/p' | head -1) || true
        if [ -n "$want" ]; then
            echo "$want  $RUNNER_DIR/$tgz" | sha256sum -c - >/dev/null || die "체크섬이 다르다 — 받은 파일을 쓰지 않는다"
            ok "v$ver 받음 — SHA-256 일치"
        else
            warn "릴리스에 체크섬이 없어 대조하지 못했다(v$ver)"
        fi
        tar -xzf "$RUNNER_DIR/$tgz" -C "$RUNNER_DIR"
        rm -f "$RUNNER_DIR/$tgz"
    fi

    step "등록 — $REPO_URL (라벨: self-hosted, linux, ARM64, $LABELS)"
    local token
    printf '  등록 토큰(Settings → Actions → Runners → New self-hosted runner, 입력은 화면에 보이지 않음): ' >&2
    read -rs token; echo >&2
    [ -n "$token" ] || die "토큰이 비었다"
    # 🔒 인자가 아니라 환경 변수로 — 러너는 ACTIONS_RUNNER_INPUT_<이름> 을 같은 이름의 인자로 읽는다.
    ( cd "$RUNNER_DIR" && ACTIONS_RUNNER_INPUT_TOKEN="$token" ./config.sh --unattended --replace \
        --url "$REPO_URL" --name "$RUNNER_NAME" --labels "$LABELS" --work _work )
    unset token
    ok "등록됨 — $RUNNER_NAME"

    step "서비스로 시작(재부팅 뒤에도) — 사용자 $(id -un)"
    ( cd "$RUNNER_DIR" && sudo ./svc.sh install "$(id -un)" && sudo ./svc.sh start ) >/dev/null
    ( cd "$RUNNER_DIR" && sudo ./svc.sh status ) | grep -E "Active:" | sed 's/^/  /' || true
    echo
    echo "✅ 끝. 저장소 Variables 에 DEPLOY_REPO_DIR=$REPO_DIR, DEPLOY_ENV=$DEPLOY_ENV(기본값과 같으면 생략 가능)."
    echo "   첫 배포는 Actions → deploy (OCI Ampere) → Run workflow 로 지금 떠 있는 버전을 한 번 돌려 보는 것을 권한다."
}

status() {
    [ -x "$RUNNER_DIR/svc.sh" ] || die "$RUNNER_DIR 에 러너가 없다 — $0 install"
    ( cd "$RUNNER_DIR" && sudo ./svc.sh status ) | sed 's/^/  /'
}

uninstall() {
    [ -x "$RUNNER_DIR/svc.sh" ] || die "$RUNNER_DIR 에 러너가 없다"
    ( cd "$RUNNER_DIR" && sudo ./svc.sh stop && sudo ./svc.sh uninstall ) >/dev/null || true
    local token
    printf '  해제 토큰(Settings → Actions → Runners → 이 러너 → Remove 화면의 토큰): ' >&2
    read -rs token; echo >&2
    ( cd "$RUNNER_DIR" && ACTIONS_RUNNER_INPUT_TOKEN="$token" ./config.sh remove --unattended )
    unset token
    ok "해제됨"
}

case "${1:-}" in
    check)     check ;;
    install)   install ;;
    status)    status ;;
    uninstall) uninstall ;;
    *) sed -n '2,20p' "$0"; exit 1 ;;
esac
