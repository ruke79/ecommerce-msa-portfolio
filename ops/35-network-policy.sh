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
#   나가는 연결(egress) 1 · 2단계 — k8s/97-network-policy-egress.yaml (docs/57 §23 · §26):
#   ./ops/35-network-policy.sh egress-render   # 노드 IP 를 펼친 매니페스트를 출력만 한다
#   ./ops/35-network-policy.sh egress-check    # 그룹마다(internal · public · mail · mongo · metrics · cloudflared · backup) 허용 · 차단 흐름을 두드린다
#   ./ops/35-network-policy.sh egress-apply    # 사전 점검 → 적용 전 두드리기 → 적용 → 두드리기 → 막히면 되돌린다
#   ./ops/35-network-policy.sh egress-remove   # egress 정책만 지운다(되돌리기)
#   [JA] 運用アクション ㉟ — ネットワークポリシー(k8s/95-network-policy.yaml)を確認しながら適用し、間違っていれば自分で戻す(docs/57 §11)。
#        なぜスクリプトか: NetworkPolicy は間違うと静かに切る — Pod は Running なのにサービス同士が繋がらず決済が止まる。逆に
#        クラスターがポリシーを執行しなければ、ファイルを適用しても何も遮断しないが、それも静かである。どちらも叩いてみないと分からない。
#        使い方: check = 今の状態で許可・遮断の流れを叩いてみる(変えない)、apply = 適用 → 確認 → 許可されるべき流れが塞がれたら戻す、
#        remove = ポリシーを消す(戻す)。
#        外向き接続(egress)第 1 段階 — k8s/97-network-policy-egress.yaml(docs/57 §23): egress-render = ノード IP を展開した
#        マニフェストを出力するだけ、egress-check = 二つのグループの許可・遮断の流れを叩いてみる(変えない)、egress-apply = 事前点検 →
#        適用前の叩き → 適用 → 叩き → 塞がれたら戻す、egress-remove = egress ポリシーだけを消す(戻す)。
#
#   [JA] egress 第 1 段階 — render(出力のみ) · check · apply(事前点検 → 適用 → 確認 → 塞がれば戻す) · remove。
#
#   [JA] egress 第 1 段階 — render(出力のみ) · check · apply(事前点検 → 適用 → 確認 → 塞がれば戻す) · remove。
#
# 두드리는 방법: 잠깐 뜨는 busybox 파드에서 `nc -z` 로 TCP 연결만 해 본다(자격증명은 쓰지 않는다).
#   · 허용돼야 할 흐름 — ecommerce 네임스페이스에서: 각 서비스, Postgres·Redis·Kafka·Mongo·ES
#   · 막혀야 할 흐름   — default 네임스페이스에서: user-service·order-service 내부 API, Postgres·Redis
#   · 관리 포트(9180, B-119) — netpol-probe-metrics(metrics-agent 와 같은 정책) 파드는 닿고, 같은 네임스페이스의 다른 파드는 막힌다
#     (파드 IP 로 — Service 는 그 포트를 내지 않는다)
# 그리고 적용 뒤 ecommerce 의 모든 파드가 준비 상태를 유지하는지(kubelet 프로브가 막히지 않는지) 본다.
set -euo pipefail

cd "$(dirname "$0")/.."
MANIFEST="k8s/95-network-policy.yaml"
EGRESS_MANIFEST="k8s/97-network-policy-egress.yaml"
# k8s/97 의 정책 이름 · 두드리는 그룹(두드리기 파드 라벨 netpol-probe-<그룹>). 정책을 더하면 둘 다.
# [JA] k8s/97 のポリシー名 · 叩くグループ(叩き用 Pod のラベル netpol-probe-<グループ>)。ポリシーを足すなら両方とも。
EGRESS_POLICIES="egress-cluster-only egress-public-no-private egress-smtp egress-mongo-atlas egress-metrics-agent egress-cloudflared egress-backup-postgres"
EGRESS_GROUPS="internal public mail mongo metrics cloudflared backup"
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
# [JA] 一つの名前空間で対象に TCP 接続を試み、「対象 OK|FAIL」の行を返す。Pod は終わったら消す。
probe() {
    local ns="$1"; shift
    # 라벨이 곧 받는 정책이다 — egress 두드리기는 k8s/97 의 netpol-probe-internal · netpol-probe-public 으로 뜬다.
    # [JA] ラベルがそのまま受けるポリシーだ — egress の叩き確認は k8s/97 の netpol-probe-internal・netpol-probe-public で起動する。
    local label="${PROBE_LABEL:-netpol-probe}"
    local name="${label}-$RANDOM"
    # 정책이 있으면 방금 뜬 파드는 정책 컨트롤러(kube-router)가 허용 목록에 넣기까지 몇 초간 막힌다 — 곧바로 두드리면 허용 흐름도
    # "막혔다" 로 읽혀 apply 가 되돌린다. 잠깐 기다린 뒤 두드린다(ops/37 도 같은 틈을 만났다).
    # [JA] ポリシーがあると、起動したばかりの Pod はポリシーコントローラ(kube-router)が許可リストに入れるまで数秒間ブロックされる — すぐ叩くと許可フローも
    #      「ブロックされた」と読まれ apply が元に戻してしまう。少し待ってから叩く(ops/37 も同じ隙間に遭遇した)。
    local script="sleep ${PROBE_WARMUP:-5}; "
    for t in "$@"; do
        if [[ "$t" == dns:* ]]; then
            # 이름 풀이만 본다 — egress 정책이 kube-dns(53)를 막으면 모든 연결이 이름에서 멈춘다.
            # [JA] 名前解決だけを見る — egress ポリシーが kube-dns(53)を塞ぐとすべての接続が名前の段階で止まる。
            script+="if nslookup ${t#dns:} >/dev/null 2>&1; then echo '$t OK'; else echo '$t FAIL'; fi; "
        else
            script+="if nc -z -w $TIMEOUT ${t%:*} ${t##*:} 2>/dev/null; then echo '$t OK'; else echo '$t FAIL'; fi; "
        fi
    done
    kubectl -n "$ns" run "$name" --image="$IMAGE" --restart=Never --rm -i --quiet \
        --labels=app="$label" --command -- sh -c "$script" 2>/dev/null || true
}

# 정책 **전**에 열려 있던 허용 흐름 — apply 가 채운다. 비어 있으면(check) 모든 허용 흐름이 열려 있어야 한다.
#
# 🐛 처음에는 적용 **후**에만 두드렸다. 그래서 원래 닿지 않던 대상(외부 Atlas 를 쓰느라 파드가 없는 MongoDB 등)도 "정책이
#    막았다" 로 읽고 되돌렸다. 이제 "전에는 열렸는데 후에 막힌 것" 만 정책 탓이다.
# [JA] ポリシー**前**に開いていた許可フロー — apply が埋める。空なら(check)すべての許可フローが開いていなければならない。
#      🐛 最初は適用**後**にだけ叩いていた。そのため元々届かなかった対象(外部 Atlas を使うため Pod のない MongoDB など)も「ポリシーが
#      塞いだ」と読んで元に戻していた。今は「前は開いていたのに後で塞がれたもの」だけをポリシーのせいとする。
BASELINE=""
MGMT_BASELINE=""

# 관리 포트(9180, actuator — B-119)를 두드릴 파드 IP — 관리 포트를 내는 user-service 파드 하나. Service 는 관리 포트를 내지 않는다.
# 시험은 NETPOL_MGMT_IP 로 준다. 없으면(옛 매니페스트 · 파드 없음) 관리 포트 점검을 건너뛴다.
# [JA] 管理ポート(9180、actuator — B-119)を叩く Pod IP — 管理ポートを出す user-service の Pod 一つ。Service は管理ポートを出さない。
#      テストは NETPOL_MGMT_IP で与える。なければ(古いマニフェスト · Pod なし)管理ポートの点検を飛ばす。
mgmt_ip() {
    if [ -n "${NETPOL_MGMT_IP+x}" ]; then echo "$NETPOL_MGMT_IP"; return 0; fi
    kubectl -n ecommerce get pods -l app=user-service \
        -o jsonpath='{range .items[*]}{.status.podIP}{" "}{.spec.containers[0].ports[*].name}{"\n"}{end}' 2>/dev/null \
        | awk '/management/ && $1 != "" { print $1; exit }' || true   # 못 찾으면 빈 값(set -e 로 멈추지 않게) ／ [JA] 見つからなければ空(set -e で止まらないように)
}

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
    local mip; mip=$(mgmt_ip)
    if [ -n "$mip" ]; then
        if [[ "$(PROBE_LABEL=netpol-probe-metrics probe ecommerce "$mip:9180")" == *"$mip:9180 OK"* ]]; then
            MGMT_BASELINE=open; echo "  ✅ metrics-agent → $mip:9180 (관리 포트)"
        else
            echo "  ℹ️  metrics-agent → $mip:9180 — 정책 전에도 닿지 않는다. 판정에서 뺀다"
        fi
    fi
}

check() {
    local bad=0 enforced=1

    echo "▶ 허용돼야 할 흐름 (ecommerce → …)"
    while read -r target result; do
        [[ "$target" =~ ^[a-z0-9.-]+:[0-9]+$ && "$result" =~ ^(OK|FAIL)$ ]] || continue   # 결과 줄만 ／ [JA] 結果行だけ
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

    # 관리 포트(B-119) — 지표 에이전트는 닿고, 같은 네임스페이스의 다른 파드는 막힌다. [JA] 管理ポート — エージェントだけ届く。
    local mip; mip=$(mgmt_ip)
    if [ -z "$mip" ]; then
        echo "▶ 관리 포트 — 관리 포트를 내는 user-service 파드가 없다(옛 매니페스트?) · 건너뛴다"
    else
        echo "▶ 관리 포트 $mip:9180 (actuator)"
        if [[ "$(PROBE_LABEL=netpol-probe-metrics probe ecommerce "$mip:9180")" == *"$mip:9180 OK"* ]]; then
            echo "  ✅ metrics-agent → 관리 포트"
        elif [ -n "$BASELINE" ] && [ "$MGMT_BASELINE" != open ]; then
            echo "  ℹ️  metrics-agent → 관리 포트 — 닿지 않지만 정책 전에도 그랬다"
        else
            echo "  🔴 metrics-agent → 관리 포트 — 막혔다(지표가 끊긴다)"; bad=1
        fi
        if [[ "$(probe ecommerce "$mip:9180")" == *"$mip:9180 FAIL"* ]]; then
            echo "  ✅ 다른 파드(ecommerce) → 관리 포트 — 막혔다"
        else
            echo "  ⚠️  다른 파드(ecommerce) → 관리 포트 — 열려 있다"; enforced=0
        fi
    fi

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


# ══ 나가는 연결(egress) 1단계 — k8s/97 (docs/57 §23) ═══════════════════════════════════════════
# [JA] 外向き接続(egress)第 1 段階 — k8s/97(docs/57 §23)。
# [JA] 外向き接続の第 1 段階。
# [JA] 外向き接続の第 1 段階。

# 노드 InternalIP — 파드의 HOST_IP(추적을 보내는 OTLP 수집기 주소)와 같은 값. 시험은 NETPOL_NODE_IPS 로 준다.
# [JA] ノード InternalIP — Pod の HOST_IP(トレースを送る OTLP コレクタのアドレス)と同じ値。テストは NETPOL_NODE_IPS で与える。
node_ips() {
    if [ -n "${NETPOL_NODE_IPS:-}" ]; then
        tr ' ' '\n' <<<"$NETPOL_NODE_IPS" | grep -v '^$'
    else
        kubectl get nodes -o jsonpath='{range .items[*]}{.status.addresses[?(@.type=="InternalIP")].address}{"\n"}{end}' \
            | grep -v '^$'
    fi
}

# `__NODE_IP__` 줄을 노드마다 한 줄로 펼친다. 노드가 없거나 IPv4 가 아니면 멈춘다(빈 목록이면 수집기로 가는 추적이 끊긴다).
# [JA] `__NODE_IP__` の行をノードごとに 1 行へ展開する。ノードがないか IPv4 でなければ止まる(空リストだとコレクタへのトレースが途切れる)。
egress_render() {
    local ips
    ips=$(node_ips)
    [ -n "$ips" ] || { echo "🔴 노드 IP 를 읽지 못했다 — 렌더하지 않는다" >&2; return 1; }
    while read -r ip; do
        [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "🔴 IPv4 가 아닌 노드 주소: $ip" >&2; return 1; }
    done <<<"$ips"
    # 쉼표로 넘긴다 — mawk(Ubuntu 기본 awk)는 -v 값 안의 줄바꿈을 받지 않는다.
    # [JA] カンマで渡す — mawk(Ubuntu 既定の awk)は -v の値の中の改行を受け付けない。
    awk -v ips="$(tr '\n' ',' <<<"$ips" | sed 's/,$//')" '
        /__NODE_IP__/ { n = split(ips, a, ","); for (i = 1; i <= n; i++) { line = $0; sub(/__NODE_IP__/, a[i], line); print line } next }
        { print }' "$EGRESS_MANIFEST"
}

# "바깥이 필요 없는" 그룹은 클러스터 밖으로 못 나간다 — 그 그룹이 쓰는 PostgreSQL · Redis · Kafka 가 클러스터 밖이면(external
# 오버레이의 Neon · Upstash · Confluent) 적용하면 끊긴다. ConfigMap 의 주소가 모두 *.svc.cluster.local 일 때만 진행한다.
# 시험은 NETPOL_BACKENDS 로 준다.
# [JA] 「外部が不要な」グループはクラスタ外へ出られない — そのグループが使う PostgreSQL・Redis・Kafka がクラスタ外なら(external
#      オーバーレイの Neon・Upstash・Confluent)適用すると切れる。ConfigMap のアドレスがすべて *.svc.cluster.local のときだけ進める。
#      テストは NETPOL_BACKENDS で与える。
egress_precheck() {
    local backends="${NETPOL_BACKENDS:-}"
    if [ -z "$backends" ]; then
        # 🐛 2026-11-19 실습: registry-vm(이미지 빌드 VM)에서 돌려 "읽지 못했다" 한 줄로 멈췄다 — 왜인지 몰랐다. 까닭을 나눠 말한다:
        #    클러스터에 닿지 못함(이 셸의 kubectl 이 그 클러스터를 가리키지 않는다) · ConfigMap 없음 · 키가 비어 있음.
        # [JA] 読めなかった理由を分ける — クラスタに届かない(この kubectl の向き先) · ConfigMap なし · キーが空。
        local err
        if ! err=$(kubectl -n ecommerce get configmap ecommerce-config -o name 2>&1 >/dev/null); then
            if kubectl get namespace ecommerce -o name >/dev/null 2>&1; then
                echo "🔴 ecommerce 네임스페이스에 ConfigMap ecommerce-config 가 없다 — 배포(ops/28)가 끝난 클러스터인지 본다"
            else
                local ctx
                ctx=$(kubectl config current-context 2>/dev/null || echo "(없음)")
                echo "🔴 kubectl 이 클러스터에 닿지 못한다 — 지금 컨텍스트: $ctx"
                echo "   ${err%%$'\n'*}"
                echo "   그 클러스터의 kubeconfig 가 있는 곳(노드 · kubectl 을 쓰는 PC)에서 돌리거나 KUBECONFIG · --context 를 맞춘다"
            fi
            return 1
        fi
        local key v
        for key in POSTGRES_HOST SPRING_DATA_REDIS_HOST REDIS_HOST KAFKA_URL; do
            v=$(kubectl -n ecommerce get configmap ecommerce-config -o "jsonpath={.data.$key}" 2>/dev/null || true)
            [ -n "$v" ] && backends="$backends $v"
        done
    fi
    [ -n "${backends// /}" ] || { echo "🔴 ConfigMap ecommerce-config 에 POSTGRES_HOST · SPRING_DATA_REDIS_HOST · REDIS_HOST · KAFKA_URL 이 모두 비어 있다 — 적용하지 않는다(직접 주려면 NETPOL_BACKENDS)"; return 1; }
    local bad=0 b host
    for b in ${backends//,/ }; do
        host="${b%%:*}"
        if [[ "$host" == *.svc.cluster.local || "$host" == *.svc ]]; then
            echo "  ✅ $host — 클러스터 안"
        else
            echo "  🔴 $host — 클러스터 밖이다. '바깥이 필요 없는' 그룹(cart · coupon · pay · gateway …)이 이것을 쓰면 끊긴다"; bad=1
        fi
    done
    egress_precheck_stage2 || bad=1
    return $bad
}

# 2단계의 전제(docs/57 §26) — 좁힌 포트 밖을 쓰는 설정이면 적용하지 않는다. 값은 출력하지 않는다(맞는지만).
#   · 메일 포트(Secret MAIL_PORT)는 465 · 587 — 25 · 2525 같은 포트면 egress-smtp 가 막는다
#   · 설정 저장소(config-server 의 CONFIG_GIT_URI)는 https — ssh(22) 는 public 의 443 밖
#   · PostgreSQL 백업(backup-config 의 PGHOST)은 클러스터 안 — 관리형 DB 면 egress-backup-postgres 가 막는다
# 시험은 NETPOL_MAIL_PORT · NETPOL_GIT_URI · NETPOL_BACKUP_PGHOST 로 준다(빈 값 = 쓰지 않음).
# [JA] 第 2 段階の前提 — 狭めたポートの外を使う設定なら適用しない(値は出さない)。
egress_precheck_stage2() {
    local bad=0 v
    # 🐛 2026-11-20 실습: 실습 Secret 은 MAIL_HOST=localhost · MAIL_PORT=25(메일을 보내지 않는 자리) 인데 포트만 보고 거절했다.
    #    파드 안 루프백은 NetworkPolicy 가 고르지 않는다 — 호스트가 localhost · 127.x · ::1 이면 '쓰지 않는다' 로 본다.
    # [JA] 実習の Secret は localhost:25(メールを送らない設定)なのに拒否した。ループバックは NetworkPolicy の対象外。
    local mhost
    if [ -n "${NETPOL_MAIL_HOST+x}" ]; then mhost="$NETPOL_MAIL_HOST"
    else mhost=$(kubectl -n ecommerce get secret ecommerce-secrets -o 'jsonpath={.data.MAIL_HOST}' 2>/dev/null | base64 -d 2>/dev/null || true); fi
    if [ -n "${NETPOL_MAIL_PORT+x}" ]; then v="$NETPOL_MAIL_PORT"
    else v=$(kubectl -n ecommerce get secret ecommerce-secrets -o 'jsonpath={.data.MAIL_PORT}' 2>/dev/null | base64 -d 2>/dev/null || true); fi
    case "$mhost" in localhost|127.*|::1|"[::1]") v="" ;; esac
    case "$v" in
        ""|465|587) echo "  ✅ 메일 포트 — 465 · 587 이거나 쓰지 않는다(호스트가 localhost 면 쓰지 않는 것으로 본다)" ;;
        *) echo "  🔴 메일 포트(Secret MAIL_PORT)가 465 · 587 이 아니다 — egress-smtp 가 막는다(k8s/97 의 그 정책에 포트를 더하고 다시)"; bad=1 ;;
    esac
    if [ -n "${NETPOL_GIT_URI+x}" ]; then v="$NETPOL_GIT_URI"
    else v=$(kubectl -n ecommerce get deploy config-server \
        -o 'jsonpath={.spec.template.spec.containers[0].env[?(@.name=="CONFIG_GIT_URI")].value}' 2>/dev/null || true); fi
    case "$v" in
        ssh://*|git@*|git://*) echo "  🔴 설정 저장소 주소가 ssh/git 프로토콜이다 — public 그룹은 https(443) 만 나간다. https 주소로 바꾸고 다시"; bad=1 ;;
        *) echo "  ✅ 설정 저장소 — https 이거나 쓰지 않는다" ;;
    esac
    if [ -n "${NETPOL_BACKUP_PGHOST+x}" ]; then v="$NETPOL_BACKUP_PGHOST"
    else v=$(kubectl -n ecommerce get configmap backup-config -o 'jsonpath={.data.PGHOST}' 2>/dev/null || true); fi
    if [ -z "$v" ] || [[ "$v" == *.svc.cluster.local || "$v" == *.svc ]]; then echo "  ✅ 백업 PGHOST — 클러스터 안이거나 백업 설정 없음"
    else echo "  🔴 백업 PGHOST($v)가 클러스터 밖이다 — egress-backup-postgres 는 postgres 네임스페이스만 연다"; bad=1; fi
    return $bad
}

# 지표 에이전트가 실제로 가는 곳 — 여러 노드 클러스터(실습)에서는 API 서버가 한 노드에만 있고 remote write 는 노드 밖(관측 VM)이다.
# 🐛 2026-11-20 실습: 첫 노드 IP 의 6443 · 9090 을 두드려 '정책 전에도 닫힘' 으로만 나와 그 두 경로를 판정하지 못했다.
#    API 서버 = kubernetes 엔드포인트, remote write = metrics-agent ConfigMap 의 url(`__HOST_IP__` 면 노드). 못 읽으면 예전처럼 노드.
# [JA] 指標エージェントの実際の行き先 — API サーバーは kubernetes のエンドポイント、remote write は ConfigMap の url。
api_server_target() {   # $1 = 대신 쓸 노드 IP ／ [JA] 代わりに使うノード IP
    if [ -n "${NETPOL_API_TARGET:-}" ]; then echo "$NETPOL_API_TARGET"; return 0; fi
    local ip port
    ip=$(kubectl -n default get endpoints kubernetes -o 'jsonpath={.subsets[0].addresses[0].ip}' 2>/dev/null || true)
    port=$(kubectl -n default get endpoints kubernetes -o 'jsonpath={.subsets[0].ports[0].port}' 2>/dev/null || true)
    if [[ "$ip" =~ ^[0-9.]+$ && "$port" =~ ^[0-9]+$ ]]; then echo "$ip:$port"; else echo "$1:6443"; fi
}
remote_write_target() {   # $1 = 대신 쓸 노드 IP ／ [JA] 代わりに使うノード IP
    if [ -n "${NETPOL_RW_TARGET:-}" ]; then echo "$NETPOL_RW_TARGET"; return 0; fi
    local hp
    hp=$(kubectl -n ecommerce get configmap metrics-agent-config -o yaml 2>/dev/null \
        | grep -oE 'url: *https?://[^/ ]+' | head -1 | sed -E 's#.*://##' || true)
    case "$hp" in
        ""|__HOST_IP__*) echo "$1:9090" ;;
        *:*) echo "$hp" ;;
        *) echo "$hp:80" ;;
    esac
}

EGRESS_NODE=""
egress_targets() {   # $1 = internal | public
    local node="${EGRESS_NODE:-$(node_ips | head -1)}"
    ALLOW=( "dns:kafka.kafka.svc.cluster.local"
            "gateway-server.ecommerce.svc.cluster.local:8000"
            "user-service.ecommerce.svc.cluster.local:8080"
            "postgres.postgres.svc.cluster.local:5432"
            "redis.redis.svc.cluster.local:6379"
            "kafka.kafka.svc.cluster.local:9092"
            "$node:4318" )
    # 막혀야 할 것: 클라우드 메타데이터, 노드의 kubelet(10250 — 늘 떠 있다)
    # [JA] 塞がれるべきもの: クラウドメタデータ、ノードの kubelet(10250 — 常に起動している)
    BLOCK=( "169.254.169.254:80" "$node:10250" )
    case "$1" in
        public)
            ALLOW+=( "dns:api.stripe.com" "api.stripe.com:443" "1.1.1.1:443" )
            BLOCK+=( "1.1.1.1:80" ) ;;   # 2단계: 인터넷은 443 만 ／ [JA] 第 2 段階: インターネットは 443 のみ
        internal)
            BLOCK+=( "1.1.1.1:443" "api.stripe.com:443" ) ;;
        mail)   # notification — public + SMTP(587 · 465) ／ [JA] notification — public + SMTP(587 · 465)
            ALLOW+=( "1.1.1.1:443" "smtp.gmail.com:587" )
            BLOCK+=( "1.1.1.1:80" ) ;;
        # 아래는 1 · 2단계의 두 그룹 밖 — 각자 정책 하나만 받는다
        # [JA] 以下は第 1 · 2 段階の二つのグループの外 — それぞれポリシーを一つだけ受ける
        mongo)  # mongo-backup — DNS · Atlas(27017) · 클러스터 안 mongodb ／ [JA] mongo-backup — DNS · Atlas(27017) · クラスター内の mongodb
            ALLOW=( "dns:kafka.kafka.svc.cluster.local" "mongodb.mongodb.svc.cluster.local:27017" )
            BLOCK=( "169.254.169.254:80" "$node:10250" "1.1.1.1:443" "postgres.postgres.svc.cluster.local:5432"
                    "gateway-server.ecommerce.svc.cluster.local:8000" ) ;;
        metrics)   # metrics-agent — 관리 포트 · API 서버 · remote write ／ [JA] metrics-agent — 管理ポート · API サーバー · remote write
            local mip; mip=$(mgmt_ip)
            ALLOW=( "dns:kafka.kafka.svc.cluster.local" "$(api_server_target "$node")" "$(remote_write_target "$node")" )
            [ -z "$mip" ] || ALLOW+=( "$mip:9180" )
            BLOCK=( "169.254.169.254:80" "$node:10250" "1.1.1.1:443" "postgres.postgres.svc.cluster.local:5432"
                    "user-service.ecommerce.svc.cluster.local:8080" ) ;;
        cloudflared)   # 터널 — Cloudflare · gateway · traefik ／ [JA] トンネル — Cloudflare · gateway · traefik
            ALLOW=( "dns:region1.v2.argotunnel.com" "gateway-server.ecommerce.svc.cluster.local:8000"
                    "traefik.kube-system.svc.cluster.local:80" "1.1.1.1:443" )
            BLOCK=( "169.254.169.254:80" "$node:10250" "1.1.1.1:80" "postgres.postgres.svc.cluster.local:5432"
                    "user-service.ecommerce.svc.cluster.local:8080" ) ;;
        backup)   # postgres-backup · restore-drill — postgres 만 ／ [JA] postgres-backup · restore-drill — postgres のみ
            ALLOW=( "dns:postgres.postgres.svc.cluster.local" "postgres.postgres.svc.cluster.local:5432" )
            BLOCK=( "169.254.169.254:80" "$node:10250" "1.1.1.1:443" "redis.redis.svc.cluster.local:6379"
                    "gateway-server.ecommerce.svc.cluster.local:8000" ) ;;
    esac
}

# 한 그룹을 두드린다. 결과는 "그룹 대상 OK|FAIL" 줄 — EGRESS_BEFORE 에 적용 전 결과를 담아 두면 "전에도 막혔던 것" 을 판정에서 뺀다.
# [JA] 一つのグループを叩く。結果は「グループ 対象 OK|FAIL」の行 — EGRESS_BEFORE に適用前の結果を入れておけば「前も塞がれていたもの」を判定から外す。
egress_probe() {   # $1 = internal | public
    egress_targets "$1"
    PROBE_LABEL="netpol-probe-$1" probe ecommerce "${ALLOW[@]}" "${BLOCK[@]}" | sed "s/^/$1 /"
}

EGRESS_BEFORE=""
egress_check() {
    local bad=0 blocked=0 leaked=0 group target result
    local results=""
    for group in $EGRESS_GROUPS; do
        results+=$(egress_probe "$group")$'\n'
    done
    for group in $EGRESS_GROUPS; do
        egress_targets "$group"
        echo "▶ $group 그룹 — 허용돼야 할 흐름"
        for target in "${ALLOW[@]}"; do
            result=$(awk -v g="$group" -v t="$target" '$1==g && $2==t {print $3}' <<<"$results" | tail -1)
            local before
            before=$(awk -v g="$group" -v t="$target" '$1==g && $2==t {print $3}' <<<"$EGRESS_BEFORE" | tail -1)
            if [ "$result" = OK ]; then echo "  ✅ $target"
            elif [ -n "$EGRESS_BEFORE" ] && [ "$before" != OK ]; then echo "  ℹ️  $target — 닿지 않지만 정책 전에도 그랬다"
            else echo "  🔴 $target — 막혔다"; bad=1; fi
        done
        echo "▶ $group 그룹 — 막혀야 할 흐름"
        for target in "${BLOCK[@]}"; do
            result=$(awk -v g="$group" -v t="$target" '$1==g && $2==t {print $3}' <<<"$results" | tail -1)
            local before
            before=$(awk -v g="$group" -v t="$target" '$1==g && $2==t {print $3}' <<<"$EGRESS_BEFORE" | tail -1)
            if [ "$result" = FAIL ]; then
                if [ -n "$EGRESS_BEFORE" ] && [ "$before" != OK ]; then echo "  ℹ️  $target — 막혔지만 정책 전에도 그랬다(판정에서 뺀다)"
                else echo "  ✅ $target — 막혔다"; blocked=1; fi
            else
                echo "  ⚠️  $target — 열려 있다"; leaked=1
            fi
        done
    done
    echo "▶ ecommerce 파드 준비 상태"
    local not_ready
    not_ready=$(kubectl -n ecommerce get pods --no-headers 2>/dev/null \
        | awk '$3=="Running" { split($2,a,"/"); if (a[1]!=a[2]) print $1 }')
    if [ -n "$not_ready" ]; then echo "  🔴 준비되지 않은 파드:"; echo "$not_ready" | sed 's/^/     /'; bad=1
    else echo "  ✅ Running 파드 모두 준비됨"; fi
    CHECK_BAD=$bad
    # 막혀야 할 것이 모두 막혔을 때만 "집행됨" — 일부만 막히면 정책 · 컨트롤러가 기대와 다르다(되돌리지는 않는다, 더 열리지는 않는다).
    # [JA] 塞がれるべきものがすべて塞がれたときだけ「施行済み」 — 一部だけ塞がれるならポリシー・コントローラが期待と違う(元には戻さない、これ以上開きはしない)。
    if [ "$blocked" = 1 ] && [ "$leaked" = 0 ]; then CHECK_ENFORCED=1; else CHECK_ENFORCED=0; fi
}

egress_apply() {
    echo "▶ 사전 점검 — '바깥이 필요 없는' 그룹의 백엔드가 클러스터 안인가"
    egress_precheck || { echo "🔴 적용하지 않는다 — 이 환경은 k8s/97 1번 정책의 전제와 다르다(docs/57 §23)"; exit 1; }
    local rendered
    rendered=$(egress_render) || exit 1
    EGRESS_NODE=$(node_ips | head -1)
    echo "▶ 적용 전 — 그룹마다의 흐름을 기록한다"
    EGRESS_BEFORE=""
    local group
    for group in $EGRESS_GROUPS; do EGRESS_BEFORE+=$(egress_probe "$group")$'\n'; done
    grep -q ' OK$' <<<"$EGRESS_BEFORE" || { echo "🔴 적용 전인데도 열린 흐름이 하나도 없다 — 두드리는 파드가 뜨지 않았을 수 있다. 적용하지 않는다."; exit 1; }
    kubectl apply -f - <<<"$rendered"
    echo "정책이 퍼지기를 ${NETPOL_SETTLE:-10}초 기다린다…"; sleep "${NETPOL_SETTLE:-10}"
    egress_check
    if [ "$CHECK_BAD" != 0 ]; then
        echo "🔴 허용돼야 할 흐름이 막혔다 — 되돌린다."
        # shellcheck disable=SC2086
        kubectl -n ecommerce delete networkpolicy $EGRESS_POLICIES --ignore-not-found
        exit 1
    fi
    if [ "$CHECK_ENFORCED" != 1 ]; then
        echo "⚠️  정책은 적용됐지만 막혀야 할 흐름이 열려 있다(⚠️ 줄) — 클러스터가 egress 정책을 집행하지 않거나 정책이 기대와 다르다. 남겨 둔다(해롭지 않다)."
        exit 2
    fi
    echo "✅ egress 1 · 2단계 적용 — 바깥이 필요 없는 서비스는 클러스터 밖으로, 바깥이 필요한 서비스는 사설 대역 · 메타데이터 · 443 밖으로,"
    echo "   지표 에이전트 · cloudflared · 백업은 제 할 일 밖으로 나가지 못한다."
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
  egress-render)
    egress_render
    ;;
  egress-precheck)
    egress_precheck
    ;;
  egress-check)
    egress_check
    [ "$CHECK_BAD" = 0 ]
    ;;
  egress-apply)
    egress_apply
    ;;
  egress-remove)
    # shellcheck disable=SC2086
    kubectl -n ecommerce delete networkpolicy $EGRESS_POLICIES --ignore-not-found
    ;;
  *)
    sed -n '2,27p' "$0"; exit 1 ;;
esac
