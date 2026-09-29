#!/usr/bin/env bash
#
# 운영 액션 ㊴ — 서비스별 신원 인증서의 발급·갱신을 cert-manager 에 맡긴다 (docs/57 §18).
#
# 왜: ops/38 로 만든 신원은 1년짜리이고, 갱신은 사람이 다시 돌리고 재시작해야 한다 — 잊으면 만료되는 날 서비스 간 호출이 전부
# 끊긴다(enforce). cert-manager 가 90일짜리를 만료 30일 전에 새로 발급하고, Reloader 가 그 서비스만 롤링 재시작한다.
# Secret 이름·키는 ops/38 과 같다(tls-id-<서비스>) — 매니페스트는 그대로, 발급자만 바뀐다.
#
# 사용:
#   ./ops/39-cert-manager.sh              # 전부 — install → ca → apply → check (각 단계는 이미 돼 있으면 건너뛴다)
#   ./ops/39-cert-manager.sh install      # cert-manager·Reloader 설치(고정 버전)
#   ./ops/39-cert-manager.sh ca           # ops/07 의 CA 를 ClusterIssuer 로 등록(cert-manager 네임스페이스에만)
#   ./ops/39-cert-manager.sh apply        # k8s/96 — Certificate 10개
#   ./ops/39-cert-manager.sh check        # Certificate Ready·만료·다음 갱신
#   ./ops/39-cert-manager.sh renew <서비스>  # 지금 새로 발급(연습·사고 대응) — Reloader 가 그 서비스를 재시작한다
#
# 비밀은 출력하지 않는다 — CA 키는 파일에서 kubectl 로 바로 넣고(인자·로그 없음), 확인은 지문(fingerprint)만 비교한다.
set -euo pipefail

cd "$(dirname "$0")/.."
NS="${NAMESPACE:-ecommerce}"
CM_NS="cert-manager"
CA_DIR="${CA_DIR:-./certs}"
CERT_MANAGER_VERSION="${CERT_MANAGER_VERSION:-v1.16.2}"
CERT_MANAGER_URL="${CERT_MANAGER_URL:-https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml}"
RELOADER_VERSION="${RELOADER_VERSION:-v1.1.0}"
RELOADER_URL="${RELOADER_URL:-https://raw.githubusercontent.com/stakater/Reloader/${RELOADER_VERSION}/deployments/kubernetes/reloader.yaml}"
MANIFEST="k8s/96-service-identities.yaml"
SERVICES="user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection"

ok()   { echo "  ✅ $*"; }
info() { echo "  ℹ️  $*"; }
warn() { echo "  ⚠️  $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"

install() {
    step "cert-manager ${CERT_MANAGER_VERSION}"
    if kubectl get crd certificates.cert-manager.io >/dev/null 2>&1 \
            && kubectl -n "$CM_NS" rollout status deploy/cert-manager-webhook --timeout=5s >/dev/null 2>&1; then
        ok "이미 있다 — 건너뜀"
    else
        kubectl apply -f "$CERT_MANAGER_URL" >/dev/null || die "cert-manager 설치 실패 — $CERT_MANAGER_URL 에 닿는가"
        for d in cert-manager cert-manager-cainjector cert-manager-webhook; do
            kubectl -n "$CM_NS" rollout status "deploy/$d" --timeout=300s >/dev/null || die "$d 가 뜨지 않았다"
        done
        ok "설치 — cert-manager·cainjector·webhook 준비됨"
    fi

    step "Reloader ${RELOADER_VERSION} — Secret 이 바뀌면 그 Deployment 를 롤링 재시작"
    if kubectl get deploy -A -l app=reloader-reloader -o name 2>/dev/null | grep -q .; then
        ok "이미 있다 — 건너뜀"
    else
        kubectl apply -f "$RELOADER_URL" >/dev/null || die "Reloader 설치 실패 — $RELOADER_URL 에 닿는가"
        kubectl rollout status deploy/reloader-reloader -n default --timeout=300s >/dev/null 2>&1 \
            || kubectl get deploy -A -l app=reloader-reloader -o name | grep -q . || die "Reloader 가 뜨지 않았다"
        ok "설치"
    fi
}

ca() {
    step "CA 를 ClusterIssuer 로 — Secret $CM_NS/internal-ca"
    command -v openssl >/dev/null || die "openssl 이 필요합니다"
    [ -f "$CA_DIR/ca.crt" ] && [ -f "$CA_DIR/ca.key" ] || die "$CA_DIR/ca.crt·ca.key 가 없습니다 — ops/07 의 CA 디렉터리를 CA_DIR=… 로"
    local cluster local_fp
    cluster=$(kubectl -n "$NS" get secret tls-keystore -o jsonpath='{.data.ca\.crt}' 2>/dev/null | base64 -d 2>/dev/null \
        | openssl x509 -noout -fingerprint -sha256 2>/dev/null) || true
    local_fp=$(openssl x509 -in "$CA_DIR/ca.crt" -noout -fingerprint -sha256)
    [ "$cluster" = "$local_fp" ] || die "$CA_DIR/ca.crt 가 클러스터가 믿는 CA(tls-keystore) 와 다르다 — 같은 CA 여야 한다"
    kubectl -n "$CM_NS" create secret tls internal-ca --cert="$CA_DIR/ca.crt" --key="$CA_DIR/ca.key" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    local fp="${local_fp#*=}"
    ok "internal-ca — 클러스터가 믿는 CA 와 지문 일치(${fp:0:11}…)"
}

apply_manifest() {
    step "Certificate — $MANIFEST"
    kubectl get secret -n "$CM_NS" internal-ca >/dev/null 2>&1 || die "$CM_NS/internal-ca 가 없다 — 먼저: $0 ca"
    kubectl get secret -n "$NS" ecommerce-secrets >/dev/null 2>&1 || die "$NS/ecommerce-secrets 가 없다(keystore 비밀번호)"
    kubectl apply -f "$MANIFEST" >/dev/null || die "적용 실패"
    kubectl wait --for=condition=Ready clusterissuer/internal-ca --timeout=60s >/dev/null || die "ClusterIssuer 가 Ready 가 아니다"
    local s
    for s in $SERVICES; do
        kubectl -n "$NS" wait --for=condition=Ready "certificate/id-$s" --timeout=120s >/dev/null \
            || die "id-$s 가 Ready 가 아니다 — kubectl -n $NS describe certificate id-$s"
    done
    ok "Certificate ${SERVICES// /·} Ready"
    info "ops/38 이 만든 Secret 은 cert-manager 가 새로 발급해 덮는다 — Reloader 가 서비스를 한 번씩 롤링 재시작한다"
}

check() {
    step "서비스별 신원 — cert-manager"
    local s ready after renew bad=0
    for s in $SERVICES; do
        ready=$(kubectl -n "$NS" get certificate "id-$s" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
        after=$(kubectl -n "$NS" get certificate "id-$s" -o jsonpath='{.status.notAfter}' 2>/dev/null)
        renew=$(kubectl -n "$NS" get certificate "id-$s" -o jsonpath='{.status.renewalTime}' 2>/dev/null)
        if [ "$ready" = "True" ]; then
            ok "$s: 만료 ${after:-?} · 다음 갱신 ${renew:-?}"
        else
            warn "$s: Ready=${ready:-없음} — kubectl -n $NS describe certificate id-$s"; bad=1
        fi
    done
    if kubectl get deploy -A -l app=reloader-reloader -o name 2>/dev/null | grep -q .; then
        ok "Reloader 가 떠 있다 — 갱신되면 그 서비스만 롤링 재시작"
    else
        warn "Reloader 가 없다 — 갱신돼도 파드는 옛 인증서를 쥐고 있다(만료되는 날 끊긴다). $0 install"; bad=1
    fi
    return $bad
}

renew() {
    local s="${1:-}"
    [ -n "$s" ] || die "renew 에 서비스 이름을"
    command -v cmctl >/dev/null && { cmctl renew -n "$NS" "id-$s"; return; }
    # cmctl 이 없으면 — Secret 을 지우면 cert-manager 가 곧바로 다시 발급한다(키도 새로). 떠 있는 파드는 마운트된 옛 파일을 계속
    # 쓰고, 새 Secret 이 생기면 Reloader 가 재시작한다. 그 몇 초 사이에 새로 뜨는 파드는 기다린다(ContainerCreating).
    kubectl -n "$NS" delete secret "tls-id-$s" >/dev/null
    kubectl -n "$NS" wait --for=condition=Ready "certificate/id-$s" --timeout=120s >/dev/null || die "다시 발급되지 않았다"
    ok "$s: 새로 발급 — Reloader 가 재시작한다(kubectl -n $NS rollout status deploy/$s)"
}

case "${1:-all}" in
    install) install ;;
    ca)      ca ;;
    apply)   apply_manifest ;;
    check)   check ;;
    renew)   renew "${2:-}" ;;
    all)     install; ca; apply_manifest; check
             echo
             echo "✅ 끝. 서비스들이 새 인증서로 한 번씩 재시작된다 — 다 뜬 뒤 ./ops/37-check-mtls.sh" ;;
    -h|--help) sed -n '2,20p' "$0" ;;
    *) die "모르는 명령: $1 (install·ca·apply·check·renew·all)" ;;
esac
