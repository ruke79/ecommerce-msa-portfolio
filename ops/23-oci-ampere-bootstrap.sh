#!/usr/bin/env bash
#
# OCI Ampere A1(arm64) 인스턴스에 k3s 를 올린다.
#
# 대상: Oracle Cloud Always Free 의 Ampere A1 (arm64).
#
# ⚠️ **한도가 테넌시마다 다르다.** 2026-06-15 부로 Always Free Ampere 한도가
#    4 OCPU/24GB → 2 OCPU/12GB 로 축소됐다고 공지 없이 문서만 바뀌었지만, 실제로는
#    4 OCPU/24GB 가 그대로 잡히는 계정이 있다. 이 스크립트가 `nproc` 으로 확인해 준다.
#
#    CPU 요청 합계 (docs/32 §2):
#
#                                                                  4 OCPU  2 OCPU
#      prod(HPA min=2) + 클러스터 내 인프라 5종     5,950m/10,048Mi   🔴      🔴
#      **k8s-overlays/external** 전부 1레플리카     3,550m/ 6,592Mi   ✅      🔴
#      **k8s-overlays/oci** + Kafka·Redis(ES 제외)  1,120m/ 4,032Mi   ✅      ✅
#
#    (2026-09: saga-projection 과 config-server 가 더해져 external 은 각 +100m/384Mi,
#     oci 는 각 +60m/256Mi. saga-projection 은 external 오버레이가 1레플리카로 고정한다 —
#     매니페스트 기본값은 2다. config-server 는 매니페스트 자체가 1이다.)
#
#    받은 크기에 따라 오버레이가 갈린다. 4 OCPU 면 external, 2 OCPU 면 oci 다
#    (oci 는 external 을 상속해 더 조인 것이라 4 OCPU 에서 써도 된다. 반대는 안 된다).
#
#    **어느 쪽이든 k8s/70-autoscaling.yaml 은 적용하지 않는다** — prod 의 HPA 는 min=2 라
#    11개 서비스가 5,300m 이 되어 4 OCPU 도 넘긴다(replicas 필드가 없으면 기본값 1).
#    ⚠️ 레플리카 1은 이 저장소가 레플리카 2에서 잡아 온 동시성 버그를 재현하지 못한다
#       (docs/31 §3). 4 OCPU 라면 여유로 핵심 두어 개만 손으로 2레플리카로 올려 볼 수 있다.
#       2 OCPU 라면 ops/12-staging-rehearsal.sh 로 리눅스에서 따로 확인할 것.
#
# ⚠️ **인스턴스 위에서 직접 실행한다.** 로컬에서 원격으로 도는 스크립트가 아니다.
#
#   scp ops/23-oci-ampere-bootstrap.sh <사용자>@<공인IP>:~/
#   ssh <사용자>@<공인IP> 'sudo bash 23-oci-ampere-bootstrap.sh'
#
#   기본 사용자는 이미지마다 다르다: Ubuntu 는 `ubuntu`, **Oracle Linux 는 `opc`**.
#   root 로는 직접 못 들어간다(두 이미지 다 `PermitRootLogin` 이 막혀 있다).
#
# ── 어떤 이미지를 고를 것인가 ──────────────────────────────────────────
#
#   **Oracle Linux 9 (aarch64)** 를 권한다. 셋 다 되지만 OCI 안에서만 차이가 난다:
#
#     Oracle Linux 9  플랫폼 이미지(콘솔에 항상 있음), oci-utils·cloud-init 내장, 2032 까지
#     Rocky Linux 9   기술적으로 동등하나 OCI 에서는 마켓플레이스 이미지 — OCID 를 따로 찾아야
#     CentOS          **선택지가 아니다.** CentOS Linux 는 2024-06 EOL, 남은 Stream 은 롤링
#     Ubuntu          잘 되지만 도쿄 리전에서 A1 이미지 목록에 안 잡히는 경우가 보고된다
#
#   ⚠️ 이미지를 바꿔도 `Out of host capacity` 는 해결되지 않는다 — 용량은 이미지와 무관하다
#      (ops/26 참조). 이미지 선택은 용량 문제와 별개다.
#
#   ⚠️ OL8 이 아니라 **OL9** 를 쓸 것. OL8 은 cgroup v1 이 기본이라 k3s 가 v2 를 못 쓴다.
#
#   이 스크립트는 Ubuntu(iptables + netfilter-persistent)와 RHEL 계열(firewalld + SELinux)을
#   모두 다룬다.
#
# ══════════════════════════════════════════════════════════════════════════
#  ⚠️ OCI 에서 k3s 가 깨지는 가장 흔한 이유 — 이 스크립트가 다루는 것
# ══════════════════════════════════════════════════════════════════════════
#
# OCI 의 Ubuntu·Oracle Linux 이미지는 **인스턴스 안에 iptables REJECT 규칙을 미리 넣어
# 둔다.** 22번을 제외한 거의 모든 입력을 막는데, 이게 파드 네트워크(CNI)까지 막는다.
# 증상은 엉뚱하게 나타난다 — 파드는 Running 인데 서로 통신이 안 되고, CoreDNS 가
# CrashLoopBackOff 를 돌고, `kubectl exec` 이 타임아웃된다. "k3s 가 이상하다"로 보이지만
# 원인은 방화벽이다.
#
# 두 번째는 **Security List / NSG** 다. 이건 인스턴스 밖(콘솔)이라 스크립트가 손댈 수 없다.
# 다만 이 프로젝트는 Cloudflare Tunnel 로 들어오므로(ops/24) **인바운드를 열 필요가 없다.**
# 22번(SSH)만 있으면 된다. 그게 터널을 쓰는 가장 큰 이유다.
#
# ══════════════════════════════════════════════════════════════════════════
#
# 사용:
#   sudo bash ops/23-oci-ampere-bootstrap.sh
#   sudo bash ops/23-oci-ampere-bootstrap.sh --check     # 진단만, 변경 없음
#
set -euo pipefail

CHECK_ONLY=0
K3S_VERSION="${K3S_VERSION:-v1.30.4+k3s1}"

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK_ONLY=1; shift ;;
        -h|--help) sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
done

[ "$(id -u)" = "0" ] || die "root 로 실행하세요: sudo bash $0"

# ---------------------------------------------------------------- 1. 환경 확인

log "1/6  환경 확인"

ARCH="$(uname -m)"
case "$ARCH" in
    aarch64|arm64) info "아키텍처: $ARCH (Ampere A1)" ;;
    x86_64) warn "아키텍처가 $ARCH 입니다 — Ampere 가 아닙니다. 계속 진행은 됩니다" ;;
    *) die "지원하지 않는 아키텍처: $ARCH" ;;
esac

CPUS=$(nproc)
MEM_GB=$(( $(grep MemTotal /proc/meminfo | awk '{print $2}') / 1024 / 1024 ))
info "CPU ${CPUS}코어 / 메모리 ${MEM_GB}GB"

# 배포판을 판별한다. 아래 방화벽·SELinux 처리가 계열마다 완전히 다르기 때문이다.
#   Ubuntu/Debian : iptables 규칙 파일 + netfilter-persistent, SELinux 없음
#   RHEL 계열     : firewalld + SELinux enforcing (Oracle Linux·Rocky·AlmaLinux)
OS_ID=""; OS_VER=""; OS_NAME="unknown"
if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    OS_ID="${ID:-}"; OS_VER="${VERSION_ID:-}"; OS_NAME="${PRETTY_NAME:-$OS_ID}"
fi
case "$OS_ID" in
    ol|rocky|almalinux|rhel|centos|fedora) OS_FAMILY="rhel" ;;
    ubuntu|debian)                         OS_FAMILY="debian" ;;
    *)                                     OS_FAMILY="unknown" ;;
esac
info "배포판: $OS_NAME (계열: $OS_FAMILY)"
[ "$OS_FAMILY" = "unknown" ] && \
    warn "배포판을 알아내지 못했습니다 — 방화벽·SELinux 처리를 건너뛸 수 있습니다"

# OL8/Rocky8 은 cgroup v1 이 기본이다. k3s 는 v2 를 기대한다.
if [ ! -f /sys/fs/cgroup/cgroup.controllers ]; then
    warn "cgroup v2 통합 계층이 보이지 않습니다 — k3s 의 메모리·CPU 제한이 헐거워집니다"
    case "$OS_FAMILY" in
        rhel) warn "  OL8 / Rocky 8 이면 기본이 v1 입니다. **9 로 다시 만드는 것**이 답입니다" ;;
        *)    warn "  컨테이너 안에서 이 스크립트를 돌리면 여기서 오탐이 납니다(실제 VM 에서 확인하세요)" ;;
    esac
fi

# ── 이 박스에 맞는 오버레이를 골라 준다 ────────────────────────────────
#
# 한도가 테넌시마다 달라서(4 OCPU/24GB 또는 2 OCPU/12GB) 문서만 보고 고를 수 없다.
# 실제 코어 수로 판정한다. 요청 합계는 docs/32 §2:
#
#   external  3,550m / 6,592Mi     oci  1,120m / 4,032Mi
#
# oci 는 external 을 상속해 더 조인 것이라 4 OCPU 에서 써도 된다. 반대는 안 된다.
if [ "$CPUS" -ge 4 ]; then
    OVERLAY="k8s-overlays/external"
    info "권장 오버레이: $OVERLAY  (요청 3,550m — ${CPUS}코어에 들어갑니다)"
    info "  더 조이고 싶으면 k8s-overlays/oci 를 써도 됩니다(1,120m)"
elif [ "$CPUS" -ge 2 ]; then
    OVERLAY="k8s-overlays/oci"
    info "권장 오버레이: $OVERLAY  (요청 1,120m)"
    warn "  external(3,550m)은 ${CPUS}코어에 들어가지 않습니다"
else
    OVERLAY="k8s-overlays/oci"
    warn "CPU ${CPUS}코어 — 가장 조인 $OVERLAY 도 요청 합계가 1,120m 입니다"
fi
warn "어느 쪽이든 k8s/70-autoscaling.yaml 은 적용하지 마세요 (HPA min=2 → 5,300m)"

# 실제 사용량(자바 서비스 13개 + Kafka)은 8GB 안팎이다. 요청 합계보다 이쪽이 진짜 제약이다.
if [ "$MEM_GB" -lt 10 ]; then
    warn "메모리 ${MEM_GB}GB — 자바 서비스 13개 + Kafka 의 실사용이 8GB 안팎이라 빠듯합니다"
fi

# ---------------------------------------------------------------- 2. 방화벽

log "2/6  iptables 규칙 점검 (OCI 의 대표적 함정)"

# OCI 이미지는 INPUT 체인 끝에 REJECT 를 둔다. 이걸 그대로 두면 CNI 트래픽이 막힌다.
#
# ⚠️ `iptables` 명령이 없으면 이 검사는 "규칙 0개"를 돌려준다 — **없는 것과 못 본 것은 다르다.**
#    최소 설치된 Oracle Linux 이미지가 바로 이 경우라, 규칙이 멀쩡히 있는데도 통과했다고
#    보고할 수 있다. 그래서 명령 유무를 먼저 가른다.
IPTABLES_SEEN=1
if ! command -v iptables >/dev/null 2>&1; then
    IPTABLES_SEEN=0
    REJECT_RULES=0
    warn "iptables 명령이 없어 규칙을 확인하지 못했습니다 — 통과가 아니라 **미확인**입니다"
    case "$OS_FAMILY" in
        rhel)   warn "  확인하려면: dnf install -y iptables-nft 후 다시 실행" ;;
        debian) warn "  확인하려면: apt-get install -y iptables 후 다시 실행" ;;
    esac
    warn "  아래 firewalld 점검은 그대로 진행됩니다(RHEL 계열은 보통 이쪽이 원인입니다)"
else
REJECT_RULES=$(iptables -L INPUT -n --line-numbers 2>/dev/null | grep -c "REJECT" || true)
fi
if [ "$REJECT_RULES" -gt 0 ]; then
    warn "INPUT 체인에 REJECT 규칙 ${REJECT_RULES}개 — k3s 파드 네트워크를 막습니다"
    if [ "$CHECK_ONLY" = "0" ]; then
        # 규칙을 통째로 지우지 않는다. **k3s 가 쓰는 대역만 명시적으로 허용**하고
        # 나머지는 그대로 둔다 — REJECT 를 다 걷어내면 인스턴스가 그대로 노출된다.
        #
        # 10.42.0.0/16  파드 CIDR (k3s 기본)
        # 10.43.0.0/16  서비스 CIDR (k3s 기본)
        for cidr in 10.42.0.0/16 10.43.0.0/16; do
            iptables -C INPUT -s "$cidr" -j ACCEPT 2>/dev/null \
                || iptables -I INPUT 1 -s "$cidr" -j ACCEPT
            info "허용: $cidr"
        done
        # cni0/flannel 인터페이스에서 오는 것도 허용.
        iptables -C INPUT -i cni0 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -i cni0 -j ACCEPT 2>/dev/null || true
        iptables -C FORWARD -s 10.42.0.0/16 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -s 10.42.0.0/16 -j ACCEPT
        iptables -C FORWARD -d 10.42.0.0/16 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -d 10.42.0.0/16 -j ACCEPT
        info "FORWARD 체인에 파드 CIDR 허용"

        # 재부팅 후에도 남게 저장한다. 이걸 빼먹으면 "어제는 됐는데" 가 된다.
        if command -v netfilter-persistent >/dev/null 2>&1; then
            netfilter-persistent save >/dev/null 2>&1 && info "netfilter-persistent 로 저장"
        elif command -v iptables-save >/dev/null 2>&1 && [ -d /etc/iptables ]; then
            iptables-save > /etc/iptables/rules.v4 && info "/etc/iptables/rules.v4 에 저장"
        else
            warn "규칙을 영구 저장하지 못했습니다 — 재부팅하면 사라집니다."
            case "$OS_FAMILY" in
                debian) warn "  apt-get install -y iptables-persistent 후 다시 실행하세요" ;;
                rhel)   warn "  RHEL 계열은 보통 firewalld 가 방화벽을 소유합니다(아래 참조)."
                        warn "  굳이 raw iptables 를 남기려면:"
                        warn "    dnf install -y iptables-services && systemctl enable --now iptables"
                        warn "    service iptables save" ;;
                *)      warn "  iptables-save 결과를 부팅 시 복원하도록 직접 설정하세요" ;;
            esac
        fi
    fi
elif [ "$IPTABLES_SEEN" = "1" ]; then
    info "차단 규칙 없음"
fi

# Oracle Linux 는 firewalld 를 쓴다. 켜져 있으면 같은 문제가 생긴다.
if systemctl is-active --quiet firewalld 2>/dev/null; then
    warn "firewalld 가 켜져 있습니다 — k3s 와 충돌합니다"
    if [ "$CHECK_ONLY" = "0" ]; then
        # 끄는 대신 파드 대역을 trusted 존에 넣는다.
        firewall-cmd --permanent --zone=trusted --add-source=10.42.0.0/16 >/dev/null
        firewall-cmd --permanent --zone=trusted --add-source=10.43.0.0/16 >/dev/null
        firewall-cmd --reload >/dev/null
        info "firewalld trusted 존에 파드·서비스 CIDR 추가"
    fi
fi

# ---------------------------------------------------------------- 2b. SELinux
#
# RHEL 계열(Oracle Linux·Rocky·AlmaLinux)은 SELinux 가 **enforcing** 으로 나온다.
# 정책 없이 k3s 를 올리면 설치는 성공하는데 컨테이너 생성이 거부되고, 로그에는
# `permission denied` 만 남아 방화벽 문제처럼 보인다. 원인은 방화벽이 아니라 정책이다.
#
# 여기서 하는 일은 **끄는 게 아니라 정책을 넣는 것**이다. `setenforce 0` 은 되긴 하지만
# 재부팅하면 돌아오고, 무엇보다 배포판이 주는 격리를 통째로 버리는 선택이다.
#
#   container-selinux  컨테이너 런타임용 기본 정책 (배포판 저장소에 있다)
#   k3s-selinux        k3s 전용 정책 (get.k3s.io 가 rancher 저장소를 추가해 설치한다)

SELINUX_MODE="$(getenforce 2>/dev/null || echo "N/A")"
[ "$SELINUX_MODE" = "N/A" ] || log "2b/6  SELinux 정책 (RHEL 계열)"
if [ "$SELINUX_MODE" = "Enforcing" ]; then
    warn "SELinux 가 enforcing 입니다 — 정책 없이는 k3s 컨테이너 생성이 거부됩니다"
    if [ "$CHECK_ONLY" = "0" ]; then
        if rpm -q container-selinux >/dev/null 2>&1; then
            info "container-selinux 이미 설치됨"
        elif command -v dnf >/dev/null 2>&1 && dnf install -y container-selinux >/dev/null 2>&1; then
            info "container-selinux 설치"
        else
            warn "container-selinux 설치 실패 — k3s 설치가 정책을 못 넣을 수 있습니다"
        fi
        # k3s-selinux 는 아래 get.k3s.io 가 알아서 설치한다. 설치 후 4/6 에서 확인한다.
    fi
elif [ "$SELINUX_MODE" != "N/A" ]; then
    info "SELinux: $SELINUX_MODE (정책 처리 불필요)"
fi

if [ "$CHECK_ONLY" = "1" ]; then
    log "--check — 진단만 하고 끝냅니다"
    exit 0
fi

# ---------------------------------------------------------------- 3. 커널·시스템

log "3/6  커널 파라미터"

# Elasticsearch 가 요구한다. 없으면 부트스트랩 체크에서 즉시 죽는다
# ("max virtual memory areas vm.max_map_count [65530] is too low").
cat > /etc/sysctl.d/99-k3s-ecommerce.conf <<'EOF'
# Elasticsearch 부트스트랩 체크 통과용
vm.max_map_count = 262144
# 커넥션이 많은 서비스(게이트웨이·Kafka)에서 포트 고갈을 막는다
net.ipv4.ip_local_port_range = 10240 65535
# 컨테이너가 많아 기본값으로는 inotify 감시자가 모자란다
fs.inotify.max_user_watches = 524288
fs.inotify.max_user_instances = 512
EOF
sysctl -p /etc/sysctl.d/99-k3s-ecommerce.conf >/dev/null
info "vm.max_map_count=262144 (ES 필수), inotify 상향"

# ---------------------------------------------------------------- 4. k3s

log "4/6  k3s 설치"

if command -v k3s >/dev/null 2>&1; then
    info "이미 설치됨: $(k3s --version | head -1)"
else
    # --write-kubeconfig-mode 644 : sudo 없이 kubectl 을 쓰기 위해
    # traefik 은 **끄지 않는다** — 60-ingress.yaml 과 Cloudflare Tunnel 이 이걸 쓴다
    # --disable servicelb : 터널로 들어오므로 LoadBalancer 타입이 필요 없다.
    #                       켜 두면 klipper-lb 파드가 80/443 을 잡으려다 실패한다
    #
    # metrics-server 는 **끄지 않는다** — prod 오버레이의 HPA 가 이걸 쓴다. k3s 는 기본으로
    # 포함하므로 아무것도 안 하면 켜져 있다(`--disable metrics-server=false` 같은 문법은 없다).
    #
    # 이미지 GC 임계값을 낮춘 이유: 프리티어 부트볼륨이 작아(기본 50GB) 이미지가 몇 번
    # 쌓이면 디스크가 찬다. 디스크가 차면 kubelet 이 파드를 evict 하기 시작한다.
    curl -sfL https://get.k3s.io | \
        INSTALL_K3S_VERSION="$K3S_VERSION" \
        INSTALL_K3S_EXEC="server \
            --write-kubeconfig-mode 644 \
            --disable servicelb \
            --kubelet-arg=image-gc-high-threshold=70 \
            --kubelet-arg=image-gc-low-threshold=50" \
        sh - || die "k3s 설치 실패"
    info "k3s $K3S_VERSION 설치"
fi

# 설치 스크립트가 정책을 실제로 넣었는지 확인한다. "설치가 성공했다"와 "정책이 들어갔다"는
# 다른 말이고, 어긋나면 증상이 파드 단계에서야 나타난다.
if [ "$SELINUX_MODE" = "Enforcing" ]; then
    if rpm -q k3s-selinux >/dev/null 2>&1; then
        info "k3s-selinux 정책 확인"
    else
        warn "SELinux 가 enforcing 인데 k3s-selinux 정책이 없습니다."
        warn "  파드가 Running 인데 컨테이너가 permission denied 로 죽으면 이게 원인입니다."
        warn "  둘 중 하나를 고르세요:"
        warn "    ① 정책을 넣는다(권장): dnf install -y k3s-selinux && systemctl restart k3s"
        warn "    ② 끈다(권장하지 않음): setenforce 0 && /etc/selinux/config 에서 permissive"
    fi
fi

# 준비될 때까지 기다린다. 바로 kubectl 을 때리면 아직 API 가 안 떠 있다.
for i in $(seq 1 60); do
    k3s kubectl get nodes >/dev/null 2>&1 && break
    sleep 2
done
k3s kubectl get nodes >/dev/null 2>&1 || die "k3s API 가 뜨지 않았습니다: journalctl -u k3s -n 50"
info "노드: $(k3s kubectl get nodes --no-headers | awk '{print $1, $2}')"

# ---------------------------------------------------------------- 5. 검증

log "5/6  파드 네트워크 검증 (방화벽이 제대로 열렸는지)"

# CoreDNS 가 뜨는지가 가장 빠른 판정이다. 방화벽이 막고 있으면 여기서 CrashLoopBackOff 다.
for i in $(seq 1 60); do
    ready=$(k3s kubectl -n kube-system get deploy coredns -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)
    [ "${ready:-0}" -ge 1 ] && break
    sleep 5
done
if [ "${ready:-0}" -ge 1 ]; then
    info "CoreDNS 정상 — 파드 네트워크가 열려 있습니다"
else
    warn "CoreDNS 가 준비되지 않았습니다. 순서대로 확인하세요:"
    warn "  k3s kubectl -n kube-system get pods"
    warn "  iptables -L INPUT -n --line-numbers"
    if [ "$OS_FAMILY" = "rhel" ]; then
        warn "  firewall-cmd --zone=trusted --list-sources        # 파드·서비스 CIDR 이 있는지"
        warn "  getenforce && rpm -q k3s-selinux                  # enforcing 인데 정책이 없으면 그게 원인"
        warn "  ausearch -m avc -ts recent                        # SELinux 거부 로그"
    fi
fi

# ---------------------------------------------------------------- 6. 마무리

log "6/6  완료"

cat <<EOF

  kubeconfig : /etc/rancher/k3s/k3s.yaml
  로컬에서 쓰려면:

    scp <사용자>@<공인IP>:/etc/rancher/k3s/k3s.yaml ~/.kube/oci-config
    sed -i '' 's/127.0.0.1/<공인IP>/' ~/.kube/oci-config    # macOS
    export KUBECONFIG=~/.kube/oci-config

  ⚠️ 그러려면 6443 을 Security List 에 열어야 합니다. 열기 싫으면 SSH 터널을 쓰세요:

    ssh -L 6443:127.0.0.1:6443 <사용자>@<공인IP>    # Ubuntu 는 ubuntu, Oracle Linux 는 opc

  다음 단계:

    1. 이미지 빌드 — **이 인스턴스 위에서** 돌리면 arm64 네이티브라 에뮬레이션이 없습니다
         ./ops/19-build-images.sh --platform linux/arm64 --registry <레지스트리> --push

    2. 시크릿 주입 (docs/13)
         kubectl apply -f k8s/20-secret.yaml

    3. 배포 — 이 박스(${CPUS} OCPU / ${MEM_GB}GB)에 맞는 오버레이
         kubectl apply -k $OVERLAY

         ⚠️ k8s/70-autoscaling.yaml 은 **적용하지 마세요** (HPA min=2 → 5,300m).
            예전에는 이 자리에 경고 바로 밑에 그 명령이 적혀 있었습니다. 3번 항목을
            통째로 복사하면 경고까지 같이 복사되지만 실행되는 것은 명령뿐이라,
            "지워도 배포하면 되살아나는 HPA" 로 나타났습니다. 그래서 뺐습니다.
            여러 노드 클러스터에서만 k8s/README 를 보고 따로 적용하세요.

    4. 외부 노출 — 인바운드 포트를 열지 않고
         ./ops/24-cloudflare-tunnel.sh --hostname shop.example.com

EOF
