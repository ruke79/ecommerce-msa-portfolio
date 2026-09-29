#!/usr/bin/env bash
#
# OCI Always Free Ampere A1 인스턴스를 **잡힐 때까지** 재시도한다.
#
# ── 무엇을 해결하나 ────────────────────────────────────────────────────
#
# 도쿄·서울 같은 인기 리전에서 A1 을 만들려 하면 거의 항상 이걸 만난다:
#
#     Out of host capacity.
#
# 이건 설정 오류가 아니라 **그 순간 호스트에 A1 코어가 비어 있지 않다**는 뜻이다.
# 그래서 다음 셋은 전부 소용없다:
#
#   · 이미지를 Ubuntu 에서 Oracle Linux 로 바꾸기   — 용량은 이미지와 무관하다
#   · shape 를 A1 이 아닌 것으로 바꾸기            — 그러면 Always Free 가 아니다
#   · 다른 리전 고르기                             — ⚠️ Always Free 는 **홈 리전에서만** 된다
#   · 다른 AD/FD 고르기                            — ⚠️ 도쿄·오사카·서울은 **AD 가 하나뿐**이다.
#                                                    FD 를 지정하면 오히려 범위만 좁아진다
#
# 실제로 통하는 것은 둘뿐이다: **계속 시도하거나**, Pay As You Go 로 업그레이드하거나
# (Always Free 리소스는 그래도 $0 이고 할당 성공률이 올라간다는 보고가 많다).
# 이 스크립트는 앞쪽을 자동화한다.
#
# ── 무엇을 만드나 ──────────────────────────────────────────────────────
#
# docs/33 §4 가 요구하는 사양 그대로:
#   VM.Standard.A1.Flex · 2 OCPU / 12GB · 부트볼륨 100GB (기본값 — 아래 참조)
#
# ⚠️ **한도가 테넌시마다 다르다.** 2026-06-15 부로 무료 한도가 4 OCPU/24GB → 2 OCPU/12GB
#    로 축소됐다고 문서만 바뀌었지만, **4 OCPU/24GB 가 그대로 잡히는 계정이 있다.**
#    기본값은 안전하게 2/12 로 두었다. 본인 계정이 4/24 를 준다면 이렇게 넘긴다:
#
#      OCI_OCPUS=4 OCI_MEMORY_GB=24 ops/26-oci-launch-retry.sh
#
#    한도를 넘으면 LimitExceeded 가 나는데, 이 스크립트는 그걸 **재시도하지 않고 즉시
#    멈춘다** — 영원히 안 될 요청이기 때문이다. 그러니 4/24 를 시도해 보는 비용은 낮다.
#    (용량 부족과 한도 초과는 다른 실패다. 아래 분류를 보라.)
#
# ⚠️ 이 값은 Always Free A1 **한도 전부**를 쓴다는 뜻이다. 이미 A1 인스턴스가 있으면
#    한도를 넘어 LimitExceeded 가 나고, 그건 재시도해도 영원히 안 된다 — 아래에서 구분한다.
#
# ── 사용 ───────────────────────────────────────────────────────────────
#
#   # 0) 필요한 OCID 를 찾는다 (이 스크립트가 도와준다)
#   ops/26-oci-launch-retry.sh --list-ads
#   ops/26-oci-launch-retry.sh --list-images
#   ops/26-oci-launch-retry.sh --list-subnets
#
#   # 1) 값을 넣고 점검
#   export OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..xxxx
#   export OCI_SUBNET_OCID=ocid1.subnet.oc1.ap-tokyo-1.xxxx
#   export OCI_IMAGE_OCID=ocid1.image.oc1.ap-tokyo-1.xxxx
#   ops/26-oci-launch-retry.sh --check-network   # SSH 가 들어갈 수 있는 경로인가
#   ops/26-oci-launch-retry.sh --check
#
#   # 2) 잡힐 때까지 돌린다 (tmux/screen 안에서 며칠 두어도 된다)
#   ops/26-oci-launch-retry.sh
#
# 요구: oci CLI (`oci setup config` 로 인증 완료), jq
#
set -euo pipefail

# ---------------------------------------------------------------- 기본값

SHAPE="${OCI_SHAPE:-VM.Standard.A1.Flex}"
OCPUS="${OCI_OCPUS:-2}"
MEMORY_GB="${OCI_MEMORY_GB:-12}"
BOOT_GB="${OCI_BOOT_GB:-100}"
DISPLAY_NAME="${OCI_DISPLAY_NAME:-ecommerce-msa}"
SSH_KEY="${OCI_SSH_PUBLIC_KEY:-$HOME/.ssh/id_rsa.pub}"

COMPARTMENT="${OCI_COMPARTMENT_OCID:-}"
SUBNET="${OCI_SUBNET_OCID:-}"
IMAGE="${OCI_IMAGE_OCID:-}"

INTERVAL="${OCI_RETRY_INTERVAL:-60}"    # 초. 너무 짧으면 OCI 가 429 로 막는다
MAX_ATTEMPTS="${OCI_MAX_ATTEMPTS:-0}"   # 0 = 무제한

ACTION="launch"

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --check)        ACTION="check";        shift ;;
        --list-ads)     ACTION="list-ads";     shift ;;
        --list-images)  ACTION="list-images";  shift ;;
        --list-subnets) ACTION="list-subnets"; shift ;;
        --check-network) ACTION="check-network"; shift ;;
        --dry-run)      ACTION="dry-run";      shift ;;
        --name)         DISPLAY_NAME="$2";     shift 2 ;;
        --interval)     INTERVAL="$2";         shift 2 ;;
        -h|--help) sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
done

command -v oci >/dev/null || die "oci CLI 가 필요합니다.
    설치: bash -c \"\$(curl -L https://raw.githubusercontent.com/oracle/oci-cli/master/scripts/install/install.sh)\"
    인증: oci setup config"
command -v jq >/dev/null || die "jq 가 필요합니다"

# 테넌시는 CLI 설정에서 읽는다. 대부분의 개인 계정은 compartment == tenancy 다.
TENANCY="${OCI_TENANCY_OCID:-$(grep -m1 '^tenancy' ~/.oci/config 2>/dev/null | cut -d= -f2 | tr -d ' ' || true)}"

# ---------------------------------------------------------------- 조회 도우미

if [ "$ACTION" = "list-ads" ]; then
    log "가용성 도메인 (AD)"
    [ -n "$TENANCY" ] || die "테넌시 OCID 를 찾지 못했습니다 — OCI_TENANCY_OCID 를 지정하세요"
    oci iam availability-domain list --compartment-id "$TENANCY" \
        | jq -r '.data[].name' | sed 's/^/  /'
    exit 0
fi

if [ "$ACTION" = "list-images" ]; then
    log "Ubuntu 24.04 aarch64 이미지 (이 shape 에서 쓸 수 있는 것만)"
    [ -n "$COMPARTMENT" ] || die "OCI_COMPARTMENT_OCID 가 필요합니다"
    oci compute image list --compartment-id "$COMPARTMENT" \
        --operating-system "Canonical Ubuntu" --shape "$SHAPE" \
        --sort-by TIMECREATED --sort-order DESC \
        | jq -r '.data[] | "  \(.["display-name"])\n    \(.id)"' | head -20
    printf '\n  ⚠️ aarch64(ARM) 이미지인지 이름으로 확인하세요. x86 이미지는 A1 에 붙지 않습니다.\n'
    exit 0
fi

if [ "$ACTION" = "list-subnets" ]; then
    log "서브넷"
    [ -n "$COMPARTMENT" ] || die "OCI_COMPARTMENT_OCID 가 필요합니다"
    oci network subnet list --compartment-id "$COMPARTMENT" \
        | jq -r '.data[] | "  \(.["display-name"])  (public=\(.["prohibit-public-ip-on-vnic"] | not))\n    \(.id)"'
    printf '\n  ⚠️ 공인 IP 가 붙는 서브넷이어야 합니다(public=true). SSH 로 들어가야 하니까요.\n'
    printf '  경로 전체를 보려면: %s --check-network\n' "$0"
    exit 0
fi

# ---------------------------------------------------------------- 네트워크 점검
#
# "보안 리스트에 포트를 열어 둬야 하지 않나?" — **22번은 기본 보안 리스트에 이미 있다.**
# OCI 가 VCN 을 만들 때 `0.0.0.0/0 → TCP 22` 인그레스를 넣어 준다. 마법사로 만들었다면
# 손댈 것이 없다. 그리고 이 프로젝트는 Cloudflare Tunnel 로 들어오므로 80·443·6443 은
# **열지 않는다**(kubectl 은 SSH 터널로 붙인다 — docs/33 §8).
#
# 그래서 SSH 가 안 될 때 범인은 보통 보안 리스트가 아니다. 셋 중 하나다:
#
#   ① 프라이빗 서브넷을 골랐다        공인 IP 자체가 안 붙는다
#   ② 라우트 테이블에 IGW 가 없다     공인 IP 는 있는데 밖에서 들어올 길이 없다
#   ③ 인스턴스 **안**의 방화벽        OCI 밖의 문제다. ops/23 이 다룬다
#
# ①②(OCI 쪽)와 ③(호스트 쪽)은 증상으로 가를 수 있다:
#
#   timeout             패킷이 조용히 버려졌다  → ①②·보안 리스트
#   Connection refused  패킷은 닿았다           → ③ 또는 sshd 가 안 떠 있다
#
# 이 점검은 ①②와 보안 리스트만 본다. ③ 은 `ops/23 --check` 가 인스턴스 위에서 본다.
#
# ⚠️ NSG 는 서브넷이 아니라 VNIC 에 붙으므로 여기서 보이지 않는다. OCI 는 보안 리스트와
#    NSG 를 **합집합**으로 적용하니 보안 리스트에 22가 있으면 통과다. 다만 NSG 로만
#    허용해 둔 구성이라면 이 점검이 "22 없음"이라고 말할 수 있다 — 그때는 오탐이다.

if [ "$ACTION" = "check-network" ]; then
    log "네트워크 경로 점검 — SSH 가 들어갈 수 있는가"
    [ -n "$SUBNET" ] || die "OCI_SUBNET_OCID 가 필요합니다 — 먼저 $0 --list-subnets"

    if ! RAW=$(oci network subnet get --subnet-id "$SUBNET" 2>&1); then
        printf '%s\n' "$RAW" | head -3
        die "서브넷을 읽지 못했습니다: $SUBNET"
    fi
    SUBNET_JSON=$(printf '%s' "$RAW" | jq '.data')

    SUBNET_NAME=$(printf '%s' "$SUBNET_JSON" | jq -r '.["display-name"]')
    info "서브넷: $SUBNET_NAME"

    FAIL=0

    # ── ① 공인 IP 가 붙는가 ────────────────────────────────────────────
    if [ "$(printf '%s' "$SUBNET_JSON" | jq -r '.["prohibit-public-ip-on-vnic"]')" = "true" ]; then
        warn "프라이빗 서브넷입니다 — 공인 IP 가 붙지 않아 SSH 로 들어갈 수 없습니다"
        warn "  VCN 마법사로 만들면 퍼블릭·프라이빗 서브넷이 **둘 다** 생깁니다. 퍼블릭 쪽을 고르세요"
        FAIL=1
    else
        info "퍼블릭 서브넷 — 공인 IP 가 붙습니다"
    fi

    # ── ② 라우트 테이블에 인터넷 게이트웨이가 있는가 ───────────────────
    RT_ID=$(printf '%s' "$SUBNET_JSON" | jq -r '.["route-table-id"]')
    if ! RAW=$(oci network route-table get --rt-id "$RT_ID" 2>&1); then
        printf '%s\n' "$RAW" | head -3
        warn "라우트 테이블을 읽지 못했습니다 — 직접 확인하세요"
    else
        IGW=$(printf '%s' "$RAW" | jq -r '
            [.data["route-rules"][]
             | select(.destination == "0.0.0.0/0")
             | select(.["network-entity-id"] | test("internetgateway"))] | length')
        if [ "$IGW" -gt 0 ]; then
            info "라우트 0.0.0.0/0 → 인터넷 게이트웨이"
        else
            warn "0.0.0.0/0 → 인터넷 게이트웨이 라우트가 없습니다"
            warn "  공인 IP 가 붙어도 밖에서 들어올 길이 없습니다. 증상은 **timeout** 입니다"
            warn "  NAT 게이트웨이는 나가는 것만 됩니다 — 들어오려면 IGW 여야 합니다"
            FAIL=1
        fi
    fi

    # ── ③ 보안 리스트: 22 는 열려 있는가, 그 밖에 열린 것은 없는가 ─────
    #
    # protocol "6"=TCP, "all"=전부. tcp-options 가 없으면 모든 포트다.
    PORT_FILTER='
      def allows($p):
        (.protocol == "all" or .protocol == "6")
        and (.["tcp-options"]["destination-port-range"] as $r
             | $r == null or ($r.min <= $p and $r.max >= $p));
    '
    SSH_OK=0
    for SL in $(printf '%s' "$SUBNET_JSON" | jq -r '.["security-list-ids"][]'); do
        if ! RAW=$(oci network security-list get --security-list-id "$SL" 2>&1); then
            printf '%s\n' "$RAW" | head -3
            warn "보안 리스트를 읽지 못했습니다: $SL"
            continue
        fi
        SL_NAME=$(printf '%s' "$RAW" | jq -r '.data["display-name"]')

        # 22 를 허용하는 규칙이 하나라도 있는가
        N=$(printf '%s' "$RAW" | jq "$PORT_FILTER"'
            [.data["ingress-security-rules"][] | select(allows(22))] | length')
        [ "$N" -gt 0 ] && SSH_OK=1

        # 0.0.0.0/0 으로 열린 규칙을 나열한다. 22 말고 다른 게 있으면 그게 노출이다.
        #
        # ICMP(1)·ICMPv6(58)는 제외한다. 기본 보안 리스트의 `type 3 code 4` 는 Path MTU
        # Discovery 용이라 **있어야 하는** 규칙이고, 이걸 경고하면 경고 자체가 소음이 된다.
        OPEN=$(printf '%s' "$RAW" | jq -r '
            .data["ingress-security-rules"][]
            | select(.source == "0.0.0.0/0")
            | select(.protocol != "1" and .protocol != "58")
            | if (.["tcp-options"] // null) == null or ((.["tcp-options"]["destination-port-range"]) // null) == null
              then "\(.protocol):전체포트"
              else "\(.protocol):\(.["tcp-options"]["destination-port-range"].min)-\(.["tcp-options"]["destination-port-range"].max)"
              end')
        if [ -n "$OPEN" ]; then
            printf '  \033[0;33m·\033[0m %s — 0.0.0.0/0 으로 열린 규칙: %s\n' \
                "$SL_NAME" "$(printf '%s' "$OPEN" | tr '\n' ' ')"
        fi
        # 22(=6:22-22) 외에 열린 것이 있으면 경고한다. 터널을 쓰므로 있을 이유가 없다.
        EXTRA=$(printf '%s' "$OPEN" | grep -v '^6:22-22$' || true)
        if [ -n "$EXTRA" ]; then
            warn "$SL_NAME: 22번 외에 인터넷으로 열린 규칙이 있습니다"
            warn "  이 프로젝트는 Cloudflare Tunnel 로 들어옵니다 — 80/443/6443 을 열 이유가 없습니다"
            warn "  특히 6443 이 열려 있으면 **k8s API 가 인터넷에 노출**된 것입니다. 지우세요"
        fi
    done

    if [ "$SSH_OK" = "1" ]; then
        info "보안 리스트가 TCP 22 를 허용합니다"
    else
        warn "TCP 22 를 허용하는 인그레스 규칙을 찾지 못했습니다"
        warn "  기본 보안 리스트에는 원래 들어 있습니다 — 지웠다면 다시 넣으세요:"
        warn "    소스 <본인IP>/32 · IP 프로토콜 TCP · 목적지 포트 22"
        warn "  (NSG 로만 허용해 둔 구성이면 이 경고는 오탐입니다)"
        FAIL=1
    fi

    printf '\n'
    if [ "$FAIL" = "0" ]; then
        info "OCI 쪽 경로는 열려 있습니다."
        printf '  그래도 SSH 가 안 되면 인스턴스 **안**의 방화벽입니다 — ops/23 --check 를 돌리세요.\n'
        printf '  증상으로 가릅니다: timeout = OCI 쪽 / Connection refused = 호스트 쪽.\n'
    else
        die "위 항목을 고치기 전에는 SSH 로 들어갈 수 없습니다"
    fi
    exit 0
fi

# ---------------------------------------------------------------- 점검

[ -n "$COMPARTMENT" ] || die "OCI_COMPARTMENT_OCID 가 필요합니다 (--list-subnets 도움말 참고)"
[ -n "$SUBNET" ]      || die "OCI_SUBNET_OCID 가 필요합니다 — ops/26-oci-launch-retry.sh --list-subnets"
[ -n "$IMAGE" ]       || die "OCI_IMAGE_OCID 가 필요합니다 — ops/26-oci-launch-retry.sh --list-images"
[ -f "$SSH_KEY" ]     || die "SSH 공개키가 없습니다: $SSH_KEY
    만들려면: ssh-keygen -t ed25519 -f ~/.ssh/oci -C oci  →  OCI_SSH_PUBLIC_KEY=~/.ssh/oci.pub"

# AD 목록을 받아 둔다. AD 는 **생성 요청에서 고르는 값**이라(이미 만든 인스턴스는 옮길 수
# 없다) 시도마다 다른 AD 를 쓸 수 있고, AD 가 여럿이면 용량도 AD 마다 다르다.
#
# ⚠️ 다만 **대부분의 리전은 AD 가 하나뿐이다.** 3개를 가진 곳은 애슈번·피닉스·프랑크푸르트·
#    런던 정도이고 도쿄·오사카·서울은 전부 단일 AD 다. 그런 리전에서는 아래 순회가
#    원소 1개짜리 목록을 도는 **no-op** 이다 — 해롭진 않지만 이걸 "해법"으로 기대하면 안 된다.
mapfile -t ADS < <(oci iam availability-domain list --compartment-id "${TENANCY:-$COMPARTMENT}" \
                     | jq -r '.data[].name')
[ "${#ADS[@]}" -gt 0 ] || die "가용성 도메인을 조회하지 못했습니다 — oci CLI 인증을 확인하세요"

# 폴트 도메인(FD)은 **일부러 지정하지 않는다.** 지정하면 OCI 가 찾을 범위를 좁힐 뿐이라
# 용량 확보에 불리하다. 비워 두면 OCI 가 여유 있는 FD 를 알아서 고른다.

log "설정"
info "shape      : $SHAPE  ($OCPUS OCPU / ${MEMORY_GB}GB)"
info "부트볼륨   : ${BOOT_GB}GB"
info "이름       : $DISPLAY_NAME"
info "SSH 공개키 : $SSH_KEY"
info "AD ${#ADS[@]}개: ${ADS[*]}"
if [ "${#ADS[@]}" -eq 1 ]; then
    warn "이 리전은 AD 가 하나뿐입니다 — AD 순회는 효과가 없습니다(도쿄·오사카·서울 등)."
    warn "  여기서 통하는 것은 **시간**뿐입니다. 용량이 열릴 때까지 계속 두드리거나,"
    warn "  Pay As You Go 로 업그레이드하는 두 가지입니다."
fi
info "재시도 간격: ${INTERVAL}초"

if [ "$ACTION" = "check" ]; then
    printf '\n  설정만 확인했습니다 (--check). 실제로 만들려면 옵션 없이 실행하세요.\n'
    printf '  ⚠️ %s OCPU / %sGB 를 요청합니다. Always Free A1 한도는 계정 전체 합계이고\n' "$OCPUS" "$MEMORY_GB"
    printf '     **테넌시마다 2 OCPU/12GB 또는 4 OCPU/24GB** 입니다(축소 이후에도 4/24 인 계정이 있습니다).\n'
    printf '     기존 A1 인스턴스가 있으면 LimitExceeded 가 나고, 재시도로 해결되지 않습니다.\n'
    printf '     4/24 를 아직 안 써 봤다면: OCI_OCPUS=4 OCI_MEMORY_GB=24 %s\n\n' "$0"
    exit 0
fi

# ---------------------------------------------------------------- 발사

launch_cmd() {  # launch_cmd <AD>
    oci compute instance launch \
        --compartment-id "$COMPARTMENT" \
        --availability-domain "$1" \
        --shape "$SHAPE" \
        --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY_GB}" \
        --image-id "$IMAGE" \
        --subnet-id "$SUBNET" \
        --assign-public-ip true \
        --boot-volume-size-in-gbs "$BOOT_GB" \
        --display-name "$DISPLAY_NAME" \
        --ssh-authorized-keys-file "$SSH_KEY" \
        --wait-for-state RUNNING
}

if [ "$ACTION" = "dry-run" ]; then
    log "실행될 명령 (첫 AD 기준)"
    printf '  oci compute instance launch \\\n    --compartment-id %s \\\n    --availability-domain %s \\\n' \
        "$COMPARTMENT" "${ADS[0]}"
    printf '    --shape %s --shape-config {"ocpus":%s,"memoryInGBs":%s} \\\n' "$SHAPE" "$OCPUS" "$MEMORY_GB"
    printf '    --image-id %s --subnet-id %s \\\n' "$IMAGE" "$SUBNET"
    printf '    --assign-public-ip true --boot-volume-size-in-gbs %s \\\n' "$BOOT_GB"
    printf '    --display-name %s --ssh-authorized-keys-file %s\n\n' "$DISPLAY_NAME" "$SSH_KEY"
    exit 0
fi

log "발사 — 잡힐 때까지 재시도합니다 (Ctrl-C 로 중단)"
printf '  tmux/screen 안에서 돌리면 SSH 가 끊겨도 계속됩니다.\n'

START=$(date +%s)
attempt=0

while :; do
    attempt=$((attempt + 1))
    AD="${ADS[$(( (attempt - 1) % ${#ADS[@]} ))]}"

    ERR=$(launch_cmd "$AD" 2>&1 >/tmp/oci-launch-$$.json) && {
        ELAPSED=$(( $(date +%s) - START ))
        log "성공 — ${attempt}회 시도, ${ELAPSED}초 경과"
        IP=$(oci compute instance list-vnics --instance-id \
                "$(jq -r '.data.id' /tmp/oci-launch-$$.json)" 2>/dev/null \
             | jq -r '.data[0]["public-ip"]' || echo "?")
        info "AD    : $AD"
        info "공인IP: $IP"
        rm -f /tmp/oci-launch-$$.json
        cat <<EOF

  ────────────────────────────────────────────────────────────────────────
   다음 단계 (docs/33 §5)

     ssh ubuntu@$IP
     scp ops/23-oci-ampere-bootstrap.sh ubuntu@$IP:~/
     ssh ubuntu@$IP 'sudo bash 23-oci-ampere-bootstrap.sh --check'

   ⚠️ Security List 인그레스는 **22번만** 열어 두세요. 80/443 은 필요 없습니다 —
      Cloudflare Tunnel 로 들어옵니다(docs/31).

EOF
        exit 0
    }

    # ── 실패 분류 — 재시도할 것과 하지 말아야 할 것을 가른다 ──────────
    #
    # 여기서 구분하지 않으면 "영원히 안 될 요청"을 며칠씩 두드리게 된다.
    case "$ERR" in
        *"Out of host capacity"*|*OutOfCapacity*|*"out of capacity"*)
            printf '\r  [%3d회] %s  용량 없음 — %s 후 재시도   ' \
                "$attempt" "$(date +%H:%M:%S)" "${INTERVAL}s"
            ;;
        *TooManyRequests*|*"429"*)
            warn "요청이 너무 잦습니다 — 간격을 2배로 늘립니다 (${INTERVAL} → $((INTERVAL * 2))초)"
            INTERVAL=$((INTERVAL * 2))
            ;;
        *LimitExceeded*|*QuotaExceeded*|*"exceed"*)
            rm -f /tmp/oci-launch-$$.json
            die "한도 초과입니다 — **재시도해도 되지 않습니다.**

    $(printf '%s' "$ERR" | head -3)

    Always Free A1 한도는 계정 전체 합계이고, **테넌시마다 2 OCPU/12GB 또는
    4 OCPU/24GB** 입니다(2026-06-15 축소 이후에도 4/24 인 계정이 있습니다).
    지금 요청: ${OCPUS} OCPU / ${MEMORY_GB}GB

    이미 A1 인스턴스가 있다면 지우거나, 이번 요청을 줄이세요:
        OCI_OCPUS=1 OCI_MEMORY_GB=6 $0

    반대로 4/24 를 아직 안 써 봤다면 한 번 시도해 볼 만합니다 — 안 되면 여기서
    바로 멈추므로 비용이 없습니다:
        OCI_OCPUS=4 OCI_MEMORY_GB=24 $0"
            ;;
        *NotAuthenticated*|*NotAuthorized*|*"401"*|*"403"*)
            rm -f /tmp/oci-launch-$$.json
            die "인증/권한 오류입니다 — 재시도로 해결되지 않습니다.

    $(printf '%s' "$ERR" | head -3)

    oci setup config 를 다시 확인하세요."
            ;;
        *)
            warn "예상치 못한 오류 (${attempt}회차):"
            printf '%s\n' "$ERR" | head -5 | sed 's/^/      /'
            warn "  재시도는 계속합니다. 같은 오류가 반복되면 Ctrl-C 로 멈추세요."
            ;;
    esac

    if [ "$MAX_ATTEMPTS" -gt 0 ] && [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
        rm -f /tmp/oci-launch-$$.json
        die "$MAX_ATTEMPTS 회 시도했지만 실패했습니다."
    fi

    # 같은 초에 몰리지 않도록 약간의 지터를 준다.
    sleep $(( INTERVAL + RANDOM % 15 ))
done
