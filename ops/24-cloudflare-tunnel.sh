#!/usr/bin/env bash
#
# Cloudflare Tunnel 을 만들고 클러스터에 연결한다 — 인바운드 포트를 하나도 열지 않고.
#
# SECRET-SCANNER-SELF-EXCLUDE — 자격증명 경로 문자열이 스캐너 패턴에 걸린다.
#
# ── 무엇을 해결하나 ────────────────────────────────────────────────────
#
# OCI Ampere 에 올린 클러스터를 외부에 노출하는 방법은 셋이다:
#
#   ① Security List 에 80/443 열기 + 공인 IP        가장 단순. 그리고 즉시 스캔당한다.
#                                                   TLS 인증서도 직접 발급·갱신해야 한다
#   ② OCI Load Balancer                             무료 티어는 10Mbps. 그래도 포트는 열린다
#   ③ Cloudflare Tunnel                             ← 이것
#
# ③ 은 cloudflared 가 **밖으로** 나가 엣지에 붙고 트래픽이 그 연결을 타고 들어온다.
# 인바운드 규칙이 필요 없고, TLS 는 Cloudflare 가 종단하며, 공인 IP 가 바뀌어도 무관하다.
#
# ── 전제 ───────────────────────────────────────────────────────────────
#
#   · Cloudflare 계정에 도메인이 등록되어 있어야 한다(네임서버를 Cloudflare 로 옮긴 상태).
#     무료 플랜으로 충분하다.
#   · cloudflared CLI. 없으면 이 스크립트가 설치 방법을 알려 준다.
#   · kubectl 이 대상 클러스터를 가리키고 있어야 한다.
#
# 사용:
#   ops/24-cloudflare-tunnel.sh --hostname shop.example.com
#   ops/24-cloudflare-tunnel.sh --hostname shop.example.com --tunnel-name ecommerce-prod
#   ops/24-cloudflare-tunnel.sh --status          # 현재 상태만 확인
#
# ⚠️ **가입만 해서는 아무것도 안 된다.** 레지스트라에서 네임서버를 Cloudflare 로 바꾸고
#    전파될 때까지 기다려야 한다(몇 분~하루). 그 전에는 tunnel login 이 도메인을
#    보여 주지도 않는다. 무엇이 남았는지 먼저 확인한다:
#
#   ops/24-cloudflare-tunnel.sh --preflight --hostname shop.example.com
#
set -euo pipefail

HOSTNAME_ARG=""
TUNNEL_NAME="${TUNNEL_NAME:-ecommerce-msa}"
NAMESPACE="${NAMESPACE:-ecommerce}"
STATUS_ONLY=0
PREFLIGHT=0

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --hostname)    HOSTNAME_ARG="$2"; shift 2 ;;
        --tunnel-name) TUNNEL_NAME="$2";  shift 2 ;;
        --namespace)   NAMESPACE="$2";    shift 2 ;;
        --status)      STATUS_ONLY=1;     shift ;;
        --preflight)   PREFLIGHT=1;       shift ;;
        -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
done

# ---------------------------------------------------------------- 사전 점검
#
# 터널을 만들기 **전에** 걸리는 것들을 본다. 여기서 가장 오래 걸리는 항목은 코드가 아니라
# **네임서버 전파**다 — 레지스트라에서 네임서버를 Cloudflare 로 바꾸고 활성화될 때까지
# 몇 분에서 하루가 걸린다. 그 전에는 `cloudflared tunnel login` 이 도메인을 보여 주지도
# 않는다. 그래서 이 점검을 클러스터보다 먼저 돌리는 편이 낫다.
#
# ⚠️ 확인할 수 있는 것만 확인한다. SSL/TLS 모드나 WebSocket 토글은 대시보드 설정이라
#    여기서 읽을 수 없다 — 읽을 수 없는 것을 통과시켰다고 말하지 않고, 목록으로만 준다.

if [ "$PREFLIGHT" = "1" ]; then
    log "Cloudflare 사전 점검"
    [ -n "$HOSTNAME_ARG" ] || die "--hostname 이 필요합니다 (예: shop.example.com)"

    FAIL=0

    # ── ① cloudflared ─────────────────────────────────────────────────
    if command -v cloudflared >/dev/null 2>&1; then
        info "cloudflared: $(cloudflared --version 2>&1 | head -1)"
    else
        warn "cloudflared 가 없습니다 — macOS: brew install cloudflared"
        FAIL=1
    fi

    # ── ② 로그인 (cert.pem) ───────────────────────────────────────────
    # 이 파일은 계정 자격증명이다. 존재 여부만 본다 — 내용은 절대 찍지 않는다.
    if [ -f "${HOME}/.cloudflared/cert.pem" ]; then
        info "로그인 완료 (~/.cloudflared/cert.pem)"
    else
        warn "로그인하지 않았습니다 — cloudflared tunnel login"
        warn "  ⚠️ 네임서버가 아직 Cloudflare 를 가리키지 않으면 이 목록에 도메인이 안 나옵니다"
        FAIL=1
    fi

    # ── ③ 네임서버 위임 — 이게 진짜 긴 항목이다 ───────────────────────
    #
    # 무료 플랜은 **존 전체 위임**만 된다. 레지스트라에서 네임서버를 Cloudflare 것으로
    # 바꿔야 한다(부분 위임 CNAME 방식은 Business 이상). 바꾼 뒤 전파를 기다려야 한다.
    if ! command -v dig >/dev/null 2>&1; then
        warn "dig 가 없어 네임서버를 확인하지 못했습니다 — 통과가 아니라 **미확인**입니다"
        warn "  macOS: 기본 포함 / Debian: apt-get install -y dnsutils / RHEL: dnf install -y bind-utils"
    else
        # 존 정점을 찾는다. 라벨을 하나씩 벗기며 NS 레코드가 나오는 첫 지점이 존이다.
        # co.kr·co.uk 같은 2단 TLD 를 목록으로 관리하지 않아도 된다.
        ZONE="$HOSTNAME_ARG"
        while [ "${ZONE#*.}" != "$ZONE" ]; do
            [ -n "$(dig +short NS "$ZONE" 2>/dev/null)" ] && break
            ZONE="${ZONE#*.}"
        done

        NS=$(dig +short NS "$ZONE" 2>/dev/null || true)
        if [ -z "$NS" ]; then
            warn "$HOSTNAME_ARG 의 네임서버를 찾지 못했습니다 — 도메인이 등록되어 있습니까?"
            FAIL=1
        elif printf '%s' "$NS" | grep -qi 'ns\.cloudflare\.com'; then
            info "네임서버가 Cloudflare 를 가리킵니다 (존: $ZONE)"
        else
            warn "존 '$ZONE' 의 네임서버가 Cloudflare 가 아닙니다:"
            printf '%s\n' "$NS" | sed 's/^/      /'
            warn "  레지스트라에서 Cloudflare 가 준 네임서버 2개로 바꾸세요."
            warn "  전파에 몇 분~하루가 걸립니다. **이걸 가장 먼저 하세요.**"
            warn "  (무료 플랜은 존 전체 위임만 됩니다 — CNAME 부분 위임은 Business 이상)"
            FAIL=1
        fi

        # 이미 다른 곳을 가리키는 레코드가 있으면 route dns 가 덮지 못하고 실패한다.
        EXISTING=$(dig +short "$HOSTNAME_ARG" 2>/dev/null | head -3 || true)
        if [ -n "$EXISTING" ]; then
            warn "$HOSTNAME_ARG 가 이미 응답합니다:"
            printf '%s\n' "$EXISTING" | sed 's/^/      /'
            warn "  기존 레코드가 있으면 터널 CNAME 을 만들지 못합니다. 대시보드에서 지우세요"
        fi
    fi

    # ── ④ 대시보드에서 사람이 봐야 하는 것 ────────────────────────────
    cat <<'EOF'

  ────────────────────────────────────────────────────────────────────────
   대시보드에서 확인할 것 (여기서는 읽을 수 없습니다)

     SSL/TLS → Overview
       암호화 모드가 **Off 가 아닐 것**. Off 면 터널로 들어와도 엣지가 TLS 를 끊습니다.
       Full 이면 충분합니다. 터널 구간은 어차피 Cloudflare 가 암호화합니다.

     SSL/TLS → Edge Certificates
       Universal SSL 이 활성일 것(존 활성화 후 15분쯤 걸립니다).
       ⚠️ 무료 인증서는 `example.com` 과 `*.example.com` **한 단계까지**만 덮습니다.
          `a.b.example.com` 을 쓰려면 유료 인증서가 필요합니다 — 호스트명을 한 단계로 두세요.
       Always Use HTTPS: 켬

     Network
       WebSockets: 켬 — chat-service 가 STOMP/WebSocket 을 씁니다. 꺼져 있으면
       채팅만 조용히 안 됩니다(다른 기능은 멀쩡해서 원인을 찾기 어렵습니다).

     Caching
       기본값을 그대로 둘 것. "Cache Everything" 페이지 규칙을 만들면 `/api/*` 응답까지
       캐시되어 **남의 장바구니가 보이는** 사고가 납니다.

   무료 플랜의 벽 (설정으로 못 넘습니다)

     · 업로드 본문 100MB — 넘으면 엣지가 413 을 돌려주고 **애플리케이션 로그에 안 남습니다**
     · 무료 인증서 서브도메인 한 단계

EOF

    if [ "$FAIL" = "0" ]; then
        info "사전 조건 통과 — ops/24 --hostname $HOSTNAME_ARG 를 돌릴 수 있습니다"
        printf '  (단 §8 배포가 먼저입니다. cloudflared-config ConfigMap 이 있어야 합니다)\n'
        exit 0
    fi
    die "위 항목을 먼저 해결하세요"
fi

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"

# ⚠️ **붙는지부터 확인한다.** 아래 단계들은 조회 실패를 `2>/dev/null` 로 삼키므로,
#    클러스터에 못 붙으면 "ConfigMap 이 없습니다" 같은 **엉뚱한 진단**이 나온다.
#    맥에서 §9 를 돌릴 때는 kubeconfig 와 SSH 터널이 둘 다 필요한데, 셸을 새로 열면
#    export 도 백그라운드 터널도 사라진다 — 실제로 그렇게 헷갈렸다.
KCTX=$(kubectl config current-context 2>/dev/null | head -1 || true)
if ! CONN_ERR=$(kubectl cluster-info 2>&1 >/dev/null); then
    printf '%s\n' "$CONN_ERR" | head -2 | sed 's/^/      /'
    die "클러스터에 붙지 못했습니다.

    현재 컨텍스트: ${KCTX:-(없음)}

    맥에서 실행 중이라면 둘 다 필요합니다(docs/33 §8):
        export KUBECONFIG=~/.kube/oci-config
        ssh -fN <호스트>            # 6443 포워딩. 셸을 닫아도 살아 있으려면 -f
        kubectl get nodes

    ⚠️ 여기서 멈추는 이유: 붙지 못한 채 진행하면 자격증명 Secret 을 **엉뚱한
       클러스터에 만들 수 있습니다.**"
fi

# ---------------------------------------------------------------- 상태 확인

if [ "$STATUS_ONLY" = "1" ]; then
    log "터널 상태"

    # 접속 확인은 위(주 경로)에서 이미 끝났다. 여기서는 **어느 클러스터를 보고 있는지**만
    # 밝힌다 — 아래 "없습니다" 가 정말 없는 것인지, 엉뚱한 클러스터를 본 것인지 구분하려면
    # 이 한 줄이 필요하다.
    info "클러스터: ${KCTX:-?}"

    kubectl -n "$NAMESPACE" get deploy cloudflared 2>/dev/null \
        || warn "cloudflared 배포가 없습니다"
    kubectl -n "$NAMESPACE" get secret cloudflared-credentials >/dev/null 2>&1 \
        && info "자격증명 Secret 있음" || warn "자격증명 Secret 없음 — 파드가 뜨지 않습니다"
    echo
    kubectl -n "$NAMESPACE" logs -l app=cloudflared --tail=15 2>/dev/null \
        | grep -iE "registered|connection|error|failed" | tail -10 || true
    exit 0
fi

[ -n "$HOSTNAME_ARG" ] || die "--hostname 이 필요합니다 (예: shop.example.com)"

if ! command -v cloudflared >/dev/null 2>&1; then
    die "cloudflared 가 필요합니다.

    macOS : brew install cloudflared
    Linux : curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-\$(dpkg --print-architecture) -o /usr/local/bin/cloudflared && chmod +x /usr/local/bin/cloudflared

    설치 후 로그인:  cloudflared tunnel login"
fi

# ---------------------------------------------------------------- 1. 로그인 확인

log "1/5  Cloudflare 인증 확인"

# `tunnel login` 이 ~/.cloudflared/cert.pem 을 만든다. 이게 없으면 아무것도 못 한다.
CERT="${HOME}/.cloudflared/cert.pem"
if [ ! -f "$CERT" ]; then
    die "Cloudflare 인증서가 없습니다: $CERT

    먼저 로그인하세요 — 브라우저가 열리고 도메인을 고르게 됩니다:
        cloudflared tunnel login"
fi
info "인증서 확인: $CERT"

# ---------------------------------------------------------------- 2. 터널

log "2/5  터널 준비"

# 이미 있으면 재사용한다. 같은 이름으로 또 만들면 커넥터가 갈라져 라우팅이 꼬인다.
if cloudflared tunnel list 2>/dev/null | awk '{print $2}' | grep -qx "$TUNNEL_NAME"; then
    TUNNEL_ID=$(cloudflared tunnel list 2>/dev/null | awk -v n="$TUNNEL_NAME" '$2==n {print $1}' | head -1)
    info "기존 터널 재사용: $TUNNEL_NAME ($TUNNEL_ID)"
else
    cloudflared tunnel create "$TUNNEL_NAME" >/dev/null || die "터널 생성 실패"
    TUNNEL_ID=$(cloudflared tunnel list 2>/dev/null | awk -v n="$TUNNEL_NAME" '$2==n {print $1}' | head -1)
    [ -n "$TUNNEL_ID" ] || die "생성한 터널의 ID 를 찾지 못했습니다"
    info "터널 생성: $TUNNEL_NAME ($TUNNEL_ID)"
fi

CREDS="${HOME}/.cloudflared/${TUNNEL_ID}.json"
[ -f "$CREDS" ] || die "터널 자격증명 파일이 없습니다: $CREDS"

# ---------------------------------------------------------------- 3. DNS

log "3/5  DNS 라우트"

# 호스트명을 터널로 향하게 한다(CNAME <uuid>.cfargotunnel.com).
#
# ⚠️ 여기서 "이미 있으면 정상"으로 넘기면 안 된다. **레코드가 있다는 것과 그게 이 터널을
#    가리킨다는 것은 다른 얘기다.** 도메인을 Cloudflare 로 옮길 때 기존 네임서버의 A 레코드가
#    통째로 임포트되는데, 그 레코드가 남아 있으면:
#
#      · `route dns` 는 "record already exists" 로 **실패**한다
#      · Cloudflare 는 그 옛 IP 로 오리진 연결을 시도한다
#      · 그 IP 는 이미 죽었으므로 **522** 가 돌아온다
#      · 그런데 터널 커넥터는 멀쩡히 붙어 있고 파드도 Ready 라, 클러스터 쪽에는
#        아무 단서가 없다
#
#    실제 배포가 여기서 막혔다(docs/33 §13). 그래서 실패를 삼키지 않고 무엇이 실패했는지
#    구분해서 알려주고, 덮어쓰기는 **사용자가 직접 결정하게** 남긴다 — 남의 레코드를
#    말없이 지우는 스크립트가 되어서는 안 된다.
if DNS_ERR=$(cloudflared tunnel route dns "$TUNNEL_NAME" "$HOSTNAME_ARG" 2>&1 >/dev/null); then
    info "$HOSTNAME_ARG → 터널 (CNAME ${TUNNEL_ID}.cfargotunnel.com)"
else
    case "$DNS_ERR" in
        *"already exists"*|*"already configured"*|*"An A, AAAA, or CNAME record"*)
            warn "$HOSTNAME_ARG 에 **이미 다른 레코드가 있어** 라우트를 만들지 못했습니다."
            warn "  그 레코드가 이 터널을 가리키지 않으면 밖에서는 522 가 돌아옵니다."
            warn "  Cloudflare 대시보드 → DNS → Records 에서 확인하세요. 이래야 정상입니다:"
            warn "    CNAME  ${HOSTNAME_ARG%%.*}  →  ${TUNNEL_ID}.cfargotunnel.com  (Proxied ✅)"
            warn "  옛 레코드를 버려도 된다면 덮어쓸 수 있습니다:"
            warn "    cloudflared tunnel route dns --overwrite-dns $TUNNEL_NAME $HOSTNAME_ARG"
            ;;
        *)
            warn "$HOSTNAME_ARG 라우트를 만들지 못했습니다:"
            printf '%s\n' "$DNS_ERR" | head -3 | sed 's/^/      /'
            ;;
    esac
fi

# ---------------------------------------------------------------- 4. 클러스터

log "4/5  클러스터에 주입"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
    || kubectl create namespace "$NAMESPACE" >/dev/null

# ⚠️ 자격증명은 Secret 으로만 들어간다. 저장소에 커밋하지 않는다.
#    --dry-run + apply 로 멱등하게: 이미 있으면 갱신된다.
kubectl -n "$NAMESPACE" create secret generic cloudflared-credentials \
    --from-file=credentials.json="$CREDS" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null \
    || die "Secret 생성 실패"
info "Secret cloudflared-credentials 주입 (파일: $(basename "$CREDS"))"

# ConfigMap 의 터널 ID 를 실제 값으로 바꾼다.
# 매니페스트를 고쳐 커밋하지 않고 클러스터에서만 치환한다 — UUID 는 환경마다 다르다.
if kubectl -n "$NAMESPACE" get configmap cloudflared-config >/dev/null 2>&1; then
    kubectl -n "$NAMESPACE" get configmap cloudflared-config -o yaml \
        | sed "s/REPLACE_ME_TUNNEL_ID/${TUNNEL_ID}/" \
        | kubectl apply -f - >/dev/null
    info "ConfigMap 의 터널 ID 치환: $TUNNEL_ID"
    # ConfigMap 이 바뀌어도 파드는 자동 재시작하지 않는다. 직접 굴린다.
    kubectl -n "$NAMESPACE" rollout restart deploy/cloudflared >/dev/null 2>&1 || true
else
    warn "cloudflared-config ConfigMap 이 없습니다. 먼저 배포하세요:"
    warn "  kubectl apply -k k8s-overlays/prod"
    warn "  그 다음 이 스크립트를 다시 실행하면 터널 ID 가 치환됩니다"
fi

# Ingress 의 host 를 실제 도메인으로 맞춘다.
CURRENT_HOST=$(kubectl -n "$NAMESPACE" get ingress ecommerce-frontend \
    -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "")
if [ -n "$CURRENT_HOST" ] && [ "$CURRENT_HOST" != "$HOSTNAME_ARG" ]; then
    fail "Ingress 의 host 가 '$CURRENT_HOST' 인데 터널은 '$HOSTNAME_ARG' 로 들어옵니다."
    warn "  traefik 이 이 요청에 매칭되는 규칙을 못 찾아 **404** 를 돌려줍니다."
    warn "  파드는 전부 Running 이고 터널도 붙어 있어서 클러스터 쪽에 단서가 없습니다."
    warn "  host 를 아예 지우는 것을 권합니다 — apex 와 www 를 한 규칙으로 받고,"
    warn "  호스트 검증은 Cloudflare 가 이미 합니다(k8s/60-ingress.yaml 의 기본값):"
    warn "    kubectl -n $NAMESPACE patch ingress ecommerce-frontend --type=json \\"
    warn "      -p='[{\"op\":\"remove\",\"path\":\"/spec/rules/0/host\"}]'"
fi

# ---------------------------------------------------------------- 5. 확인

log "5/5  연결 확인"

kubectl -n "$NAMESPACE" rollout status deploy/cloudflared --timeout=120s 2>/dev/null \
    && info "cloudflared 파드 준비됨" \
    || warn "파드가 준비되지 않았습니다: kubectl -n $NAMESPACE logs -l app=cloudflared"

# ⚠️ 파드가 Ready 인 것은 **엣지에 붙었다**는 뜻일 뿐, 사용자가 접속된다는 뜻이 아니다.
#    사이에 DNS 레코드와 Ingress host 가 있고 둘 다 조용히 어긋날 수 있다. 그래서
#    이름이 해석되는지가 아니라 **실제로 한 번 받아온다.**
info "엣지를 통해 실제로 요청해 봅니다 (전파에 최대 60초)"
HTTP_CODE=""
for _ in 1 2 3 4 5 6; do
    HTTP_CODE=$(curl -sS -o /dev/null -m 15 -w '%{http_code}' "https://$HOSTNAME_ARG/" 2>/dev/null || echo "000")
    case "$HTTP_CODE" in 200|301|302|304) break ;; esac
    sleep 10
done

case "$HTTP_CODE" in
    200|301|302|304)
        info "https://$HOSTNAME_ARG → HTTP $HTTP_CODE — 밖에서 닿습니다" ;;
    404)
        fail "HTTP 404 — 엣지·터널은 정상인데 traefik 에 매칭되는 규칙이 없습니다."
        warn "  거의 항상 Ingress 의 host 불일치입니다(위 4/5 경고를 보세요):"
        warn "    kubectl -n $NAMESPACE get ingress ecommerce-frontend -o jsonpath='{.spec.rules[0].host}'" ;;
    502|503)
        fail "HTTP $HTTP_CODE — 터널은 오리진에 닿았지만 오리진이 거절했습니다."
        warn "  traefik 뒤의 frontend 파드를 확인하세요:"
        warn "    kubectl -n $NAMESPACE get pods -l app=frontend" ;;
    522|523|524)
        fail "HTTP $HTTP_CODE — Cloudflare 가 오리진에 닿지 못했습니다."
        warn "  커넥터가 붙어 있는데 이 코드가 나오면 **DNS 레코드가 이 터널을 안 가리킵니다.**"
        warn "  대시보드 → DNS → Records 에서 '$HOSTNAME_ARG' 가 이래야 합니다:"
        warn "    CNAME → ${TUNNEL_ID}.cfargotunnel.com  (Proxied ✅)"
        warn "  A 레코드가 남아 있으면 그게 원인입니다(위 3/5 를 보세요)." ;;
    530)
        fail "HTTP 530 (Argo Tunnel error) — 이 호스트명에 연결된 커넥터가 없습니다."
        warn "  DNS 는 터널을 가리키는데 cloudflared 가 안 붙어 있는 상태입니다:"
        warn "    kubectl -n $NAMESPACE logs -l app=cloudflared --tail=20" ;;
    000)
        fail "요청 자체가 실패했습니다 — DNS 미해석이거나 TLS 실패입니다."
        warn "  네임서버가 아직 Cloudflare 로 넘어오지 않았을 수 있습니다(전파에 수 시간):"
        warn "    dig +short NS ${HOSTNAME_ARG#*.}" ;;
    *)
        warn "HTTP $HTTP_CODE — 예상 밖입니다. 응답을 직접 보세요: curl -v https://$HOSTNAME_ARG/" ;;
esac

cat <<EOF

  ────────────────────────────────────────────────────────────────────────
   확인

     curl -I https://$HOSTNAME_ARG
     kubectl -n $NAMESPACE logs -l app=cloudflared --tail=20
     ops/24-cloudflare-tunnel.sh --status

   ⚠️ 열어 둔 인바운드 포트가 있다면 이제 닫으세요. 터널을 쓰는 의미가 거기에 있습니다:
      OCI 콘솔 → Networking → VCN → Security List → 80/443 인그레스 규칙 삭제
      (22번 SSH 만 남기고, 그것도 본인 IP 로 제한하는 편이 낫습니다)

   ⚠️ 무료 플랜은 업로드 본문이 100MB 로 제한됩니다. 상품 이미지 업로드가 그보다 크면
      Cloudflare 가 413 을 돌려줍니다 — 이건 애플리케이션 로그에 남지 않습니다.

EOF
