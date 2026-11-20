#!/usr/bin/env bash
#
# 운영 액션 ㊳ — 서비스마다 **자기 신원 인증서**(CN=서비스 이름)를 발급해 Secret `tls-id-<서비스>` 로 넣는다 (docs/57 §16).
#
# 왜: 지금은 모든 서비스가 인증서 하나(CN=localhost, Secret tls-keystore)를 나눠 쓴다. 그래서 서비스 간 mTLS 판정은 "내부
# 서비스인가" 까지고 "어느 서비스인가" 로 좁히지 못한다 — 그리고 게이트웨이도 같은 키를 서버 인증서로 갖고 있어, 게이트웨이가
# 뚫리면 그 키로 내부 호출을 흉내 낼 수 있다. 서비스별 신원이 있으면 판정 필터가 경로마다 부를 수 있는 서비스를 정한다
# (INTERNAL_AUTH_PATH_RULES) — 게이트웨이의 공유 키는 허용 주체가 아니게 된다.
#
# 무엇을 바꾸나: 서비스가 **다른 서비스를 부를 때 내는 클라이언트 인증서**만(JVM 기본 keystore — Feign). 서버 인증서·Redis·Kafka 는
# 공유 인증서 그대로다(같은 CA 라 바꿀 이유가 없다).
#
# Secret 모양은 cert-manager 의 Certificate(keystores.pkcs12) 가 만드는 것과 같다 — tls.crt · tls.key · ca.crt · keystore.p12.
# 그래서 나중에 발급자를 cert-manager 로 바꿔도(docs/57 §18) 매니페스트는 그대로다.
#
# 사용:
#   ./ops/38-issue-service-identities.sh                 # 없거나 만료가 가까운(RENEW_DAYS) 서비스만 발급
#   ./ops/38-issue-service-identities.sh check           # 서비스별 CN·만료일(값은 출력하지 않는다)
#   FORCE=1 ./ops/38-issue-service-identities.sh         # 전부 다시
#   ./ops/38-issue-service-identities.sh --only "user-service order-service"
#
# 필요: ops/07 이 만든 로컬 CA(CA_DIR, 기본 ./certs 의 ca.crt·ca.key), kubectl, openssl.
# 키스토어 비밀번호는 클러스터의 ecommerce-secrets(LOCAL_KEY_STORE_PASSWORD)에서 읽어 **환경 변수로만** openssl 에 넘긴다 —
# 인자·로그·파일에 남지 않는다. 개인키는 임시 디렉터리(0700)에서 만들고 Secret 에 넣은 뒤 지운다(저장소·디스크에 남기지 않는다).
#
# 발급 뒤: 매니페스트(k8s/40~49)가 /app/identity 에 마운트한다 — ./ops/28-deploy.sh <환경> <버전> 으로 반영.
# [JA] 運用アクション ㊳ — サービスごとに**自分のアイデンティティ証明書**(CN=サービス名)を発行し Secret `tls-id-<サービス>` として入れる(docs/57 §16)。
#      なぜ: 今はすべてのサービスが証明書一つ(CN=localhost、Secret tls-keystore)を共有している。だからサービス間 mTLS の判定は「内部
#      サービスか」までで「どのサービスか」には絞れない — しかもゲートウェイも同じ鍵をサーバ証明書として持っており、ゲートウェイが
#      破られるとその鍵で内部呼び出しを装える。サービス別アイデンティティがあれば判定フィルタがパスごとに呼べるサービスを決める
#      (INTERNAL_AUTH_PATH_RULES)— ゲートウェイの共有鍵は許可主体でなくなる。
#      何を変えるか: サービスが**他のサービスを呼ぶときに出すクライアント証明書**だけ(JVM 既定 keystore — Feign)。サーバ証明書・Redis・Kafka は
#      共有証明書のままだ(同じ CA なので変える理由がない)。
#      Secret の形は cert-manager の Certificate(keystores.pkcs12)が作るものと同じ — tls.crt・tls.key・ca.crt・keystore.p12。
#      だから後で発行者を cert-manager に変えても(docs/57 §18)マニフェストはそのままだ。
#      使い方は上記(既定はないか期限が近い(RENEW_DAYS)サービスだけ発行、check はサービス別 CN・有効期限(値は出力しない)、FORCE=1 は全部やり直し、--only で対象指定)。
#      必要: ops/07 が作ったローカル CA(CA_DIR、既定 ./certs の ca.crt・ca.key)、kubectl、openssl。
#      キーストアのパスワードはクラスタの ecommerce-secrets(LOCAL_KEY_STORE_PASSWORD)から読み、**環境変数でのみ** openssl に渡す —
#      引数・ログ・ファイルに残らない。秘密鍵は一時ディレクトリ(0700)で作り Secret に入れた後消す(リポジトリ・ディスクに残さない)。
#      発行後: マニフェスト(k8s/40~49)が /app/identity にマウントする — ./ops/28-deploy.sh <環境> <バージョン> で反映。
set -euo pipefail

cd "$(dirname "$0")/.."
NS="${NAMESPACE:-ecommerce}"
CA_DIR="${CA_DIR:-./certs}"
DAYS="${DAYS:-365}"
RENEW_DAYS="${RENEW_DAYS:-30}"
FORCE="${FORCE:-0}"
ORG="${ORG:-ecommerce-msa}"
# 다른 서비스를 부르거나(Feign) 앞으로 부를 수 있는 백엔드 + 게이트웨이. 게이트웨이 인증서는 내부 API 를 부르려는 것이 아니라
# **신원 헤더를 붙여 넘기는 쪽**이라는 증명이다 — 받는 쪽은 그 헤더를 이 CN 으로 온 요청에서만 믿는다(docs/57 §16).
# config-server 는 내부 API 를 부르지 않지만 Eureka 에 자기를 등록한다 — Eureka 는 등록을 그 서비스 자신의 CN 으로만 받는다
# (docs/57 §16 "Eureka 쓰기"). eureka-server 는 자기에게 등록하지 않는다 — 신원이 없다.
# [JA] 他のサービスを呼ぶ(Feign)か今後呼びうるバックエンド + ゲートウェイ。ゲートウェイ証明書は内部 API を呼ぶためではなく
#      **アイデンティティヘッダを付けて渡す側**であることの証明だ — 受ける側はそのヘッダをこの CN で来たリクエストでだけ信じる(docs/57 §16)。
#      config-server は内部 API を呼ばないが Eureka に自分を登録する — Eureka は登録をそのサービス自身の CN でだけ受け付ける
#      (docs/57 §16「Eureka 書き込み」)。eureka-server は自分には登録しない — アイデンティティがない。
SERVICES="${SERVICES:-gateway-server user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection config-server}"

MODE="issue"
while [ $# -gt 0 ]; do
    case "$1" in
        check)  MODE="check"; shift ;;
        --only) [ $# -ge 2 ] || { echo "--only 에 서비스 이름을" >&2; exit 1; }; SERVICES="$2"; shift 2 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "모르는 인자: $1" >&2; exit 1 ;;
    esac
done

ok()   { echo "  ✅ $*"; }
info() { echo "  ℹ️  $*"; }
warn() { echo "  ⚠️  $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"
command -v openssl >/dev/null || die "openssl 이 필요합니다"

# 이 Secret 의 인증서가 며칠 남았나(없으면 -1). 인증서는 공개 정보라 읽어도 된다 — 키는 읽지 않는다.
# [JA] この Secret の証明書があと何日残っているか(なければ -1)。証明書は公開情報なので読んでよい — 鍵は読まない。
days_left() {
    local crt end
    crt=$(kubectl -n "$NS" get secret "tls-id-$1" -o jsonpath='{.data.tls\.crt}' 2>/dev/null | base64 -d 2>/dev/null) || true
    [ -n "$crt" ] || { echo -1; return; }
    end=$(printf '%s' "$crt" | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2) || { echo -1; return; }
    echo $(( ( $(date -d "$end" +%s) - $(date +%s) ) / 86400 ))
}

cn_of() {
    kubectl -n "$NS" get secret "tls-id-$1" -o jsonpath='{.data.tls\.crt}' 2>/dev/null | base64 -d 2>/dev/null \
        | openssl x509 -noout -subject -nameopt RFC2253 2>/dev/null | sed -n 's/.*CN=\([^,]*\).*/\1/p'
}

if [ "$MODE" = "check" ]; then
    echo "▶ 서비스별 신원(Secret tls-id-<서비스>)"
    for s in $SERVICES; do
        d=$(days_left "$s")
        if [ "$d" -lt 0 ]; then
            warn "$s: 없다 — $0 --only $s"
        elif [ "$d" -lt "$RENEW_DAYS" ]; then
            warn "$s: CN=$(cn_of "$s") · ${d}일 남음 — 곧 만료. 다시 발급 후 재시작"
        else
            ok "$s: CN=$(cn_of "$s") · ${d}일 남음"
        fi
    done
    exit 0
fi

[ -f "$CA_DIR/ca.crt" ] && [ -f "$CA_DIR/ca.key" ] \
    || die "$CA_DIR/ca.crt·ca.key 가 없습니다 — ops/07 이 만든 CA 디렉터리를 CA_DIR=… 로 주세요(서비스 인증서와 같은 CA 여야 한다)"

# 클러스터가 믿는 CA 와 같은가 — 다른 CA 로 발급하면 판정 필터가 "다른 CA" 로 읽어 모든 내부 호출이 외부가 된다.
# 읽지 못한 까닭을 나눠 말한다 — 닿지 못함 · Secret 없음 · ca.crt 키 없음(2026-11-19 실습: 하나로 뭉친 문구로는 무엇을 할지 몰랐다).
# [JA] 読めなかった理由を分けて出す — 接続不可 · Secret なし · ca.crt キーなし。
kubectl get --raw /readyz >/dev/null 2>&1 \
    || die "클러스터에 닿지 못했습니다(컨텍스트: $(kubectl config current-context 2>/dev/null || echo 없음)) — KUBECONFIG 를 확인"
kubectl -n "$NS" get secret tls-keystore >/dev/null 2>&1 \
    || die "$NS/tls-keystore Secret 이 없습니다 — 서비스 인증서(ops/07 의 k8s-tls-keystore.secret.yaml)를 먼저 적용"
kubectl -n "$NS" get secret tls-keystore -o jsonpath='{.data.ca\.crt}' 2>/dev/null | grep -q . \
    || die "$NS/tls-keystore 에 ca.crt 키가 없습니다 — ops/07 을 EMIT_K8S=1 로 다시 만든 k8s-tls-keystore.secret.yaml 을 적용"
cluster_ca=$(kubectl -n "$NS" get secret tls-keystore -o jsonpath='{.data.ca\.crt}' 2>/dev/null | base64 -d 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 2>/dev/null) || true
local_ca=$(openssl x509 -in "$CA_DIR/ca.crt" -noout -fingerprint -sha256)
[ -n "$cluster_ca" ] || die "$NS/tls-keystore 의 ca.crt 를 읽지 못했습니다"
[ "$cluster_ca" = "$local_ca" ] || die "$CA_DIR/ca.crt 가 클러스터의 CA(tls-keystore) 와 다릅니다 — 같은 CA 로 발급해야 한다"

# 키스토어 비밀번호 — 서비스들이 이미 쓰는 값(JAVA_TOOL_OPTIONS 의 keyStorePassword). 변수로만 들고 있는다.
# [JA] キーストアのパスワード — サービスが既に使っている値(JAVA_TOOL_OPTIONS の keyStorePassword)。変数でのみ保持する。
STOREPASS=$(kubectl -n "$NS" get secret ecommerce-secrets -o jsonpath='{.data.LOCAL_KEY_STORE_PASSWORD}' | base64 -d)
[ -n "$STOREPASS" ] || die "ecommerce-secrets 의 LOCAL_KEY_STORE_PASSWORD 를 읽지 못했습니다"
export STOREPASS

WORK=$(mktemp -d)
chmod 700 "$WORK"
trap 'rm -rf "$WORK"; unset STOREPASS' EXIT

echo "▶ 서비스별 신원 발급 (${DAYS}일, 같은 CA)"
issued=""
for s in $SERVICES; do
    # cert-manager 가 맡고 있으면(ops/39) 손대지 않는다 — 둘이 번갈아 덮으면 서비스가 계속 재시작된다.
    # [JA] cert-manager が担当しているなら(ops/39)触らない — 両者が交互に上書きするとサービスが再起動し続ける。
    if kubectl -n "$NS" get certificate "id-$s" >/dev/null 2>&1; then
        info "$s: cert-manager 가 발급·갱신한다(Certificate id-$s) — 건너뜀. 지금 새로: ./ops/39-cert-manager.sh renew $s"
        continue
    fi
    if [ "$FORCE" != "1" ]; then
        d=$(days_left "$s")
        if [ "$d" -ge "$RENEW_DAYS" ]; then
            info "$s: 이미 있다(${d}일 남음) — 건너뜀. 다시 만들려면 FORCE=1"
            continue
        fi
    fi
    dir="$WORK/$s"; mkdir -p "$dir"
    cat > "$dir/ext.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = $s
O = $ORG
[v3]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth, serverAuth
subjectAltName = DNS:$s, DNS:$s.$NS.svc.cluster.local
EOF
    openssl req -new -newkey rsa:2048 -nodes -keyout "$dir/tls.key" -out "$dir/tls.csr" -config "$dir/ext.cnf" 2>/dev/null
    openssl x509 -req -in "$dir/tls.csr" -CA "$CA_DIR/ca.crt" -CAkey "$CA_DIR/ca.key" -CAcreateserial \
        -out "$dir/tls.crt" -days "$DAYS" -sha256 -extfile "$dir/ext.cnf" -extensions v3 2>/dev/null
    cp "$CA_DIR/ca.crt" "$dir/ca.crt"
    # 자바가 읽는 PKCS12 — 비밀번호는 환경 변수로(인자에 남기지 않는다). 별칭은 서비스 이름.
    # [JA] Java が読む PKCS12 — パスワードは環境変数で(引数に残さない)。エイリアスはサービス名。
    openssl pkcs12 -export -inkey "$dir/tls.key" -in "$dir/tls.crt" -certfile "$dir/ca.crt" -name "$s" \
        -passout env:STOREPASS -out "$dir/keystore.p12"
    kubectl -n "$NS" create secret generic "tls-id-$s" \
        --from-file=tls.crt="$dir/tls.crt" --from-file=tls.key="$dir/tls.key" \
        --from-file=ca.crt="$dir/ca.crt" --from-file=keystore.p12="$dir/keystore.p12" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    rm -rf "$dir"
    ok "$s: tls-id-$s (CN=$s)"
    issued="$issued $s"
done

echo
if [ -z "$issued" ]; then
    echo "✅ 새로 발급한 것이 없다."
else
    echo "✅ 발급:$issued"
    echo "   반영: 매니페스트가 /app/identity 에 마운트한다 — ./ops/28-deploy.sh <환경> <버전>"
    echo "         (이미 마운트한 뒤의 재발급이면 재시작만: kubectl -n $NS rollout restart deploy/<서비스>)"
    echo "   확인: ./ops/37-check-mtls.sh --callers  — 서비스가 자기 이름(CN)으로 부르는지"
fi
