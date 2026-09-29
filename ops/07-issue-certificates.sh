#!/usr/bin/env bash
# 운영 액션 ⑦ — 인증서 발급 + 주입 (docs/18)
#
# 배경: docs/15 §0 에서 "인증서/키스토어 = ❌ 저장소 밖 수동 관리 = 🔴" 로 남아 있던 항목.
#       서비스들은 LOCAL_KEY_STORE_PATH(PKCS12) · LOCAL_TRUST_STORE_PATH · LOCAL_CACERT_PATH ·
#       LOCAL_CRT_PATH · LOCAL_KEY_PATH · KAFKA_SSL_*_LOCATION 을 요구하는데, 이 파일들을
#       **어떻게 만드는지 아무 데도 적혀 있지 않았다**. 개발자 PC 의 E:/github/... 경로가
#       yml 에 그대로 박혀 있던 것도 같은 이유다(docs/18 §1).
#
#       이 스크립트가 그 공백을 메운다: 발급 → 자바가 읽을 수 있는 스토어로 변환 →
#       env 파일 / k8s Secret 으로 **주입** 까지. 손으로 keytool 을 두드릴 일이 없어야 한다.
#
# 모드:
#   MODE=dev          로컬 CA 를 만들고 그 CA 로 서비스 인증서를 발급 (기본값, 무료·오프라인)
#   MODE=letsencrypt  certbot 으로 실제 도메인 인증서를 무료 발급 (ACME)
#   MODE=import       이미 갖고 있는 인증서/키를 스토어로 변환만
#   MODE=verify       기존 산출물 검증만 (만료일·체인·SAN·스토어 로드)
#
# 사용 예:
#   ./ops/07-issue-certificates.sh                                   # 개발용 전체 세트
#   MODE=letsencrypt LE_DOMAIN=api.example.com LE_EMAIL=me@example.com \
#       ./ops/07-issue-certificates.sh
#   MODE=import IMPORT_CERT=fullchain.pem IMPORT_KEY=privkey.pem IMPORT_CHAIN=chain.pem \
#       ./ops/07-issue-certificates.sh
#   MODE=verify ./ops/07-issue-certificates.sh
#   DRY_RUN=1 ./ops/07-issue-certificates.sh                         # 계획만 출력
set -euo pipefail

MODE="${MODE:-dev}"
OUT_DIR="${OUT_DIR:-./certs}"
DRY_RUN="${DRY_RUN:-0}"
FORCE="${FORCE:-0}"
WARN_DAYS="${WARN_DAYS:-30}"

# 스토어 비밀번호.
#
# ⚠️ 세 값(키스토어·키·트러스트스토어)은 **같아야 한다**. 선택이 아니라 설정상의 제약이다:
#   - gateway-server/application-local.yml:54  trust-store-password: ${LOCAL_KEY_STORE_PASSWORD}
#   - */application-local.yml                  key-password: ${LOCAL_KEY_PASSWORD}
#   - RedisCacheConfig                         storePassword: ${LOCAL_KEY_PASSWORD}
# 즉 이미 여러 설정이 두 이름을 섞어 쓰고 있어, 다르게 두면 반드시 어딘가가 깨진다.
# (PKCS12 는 애초에 키별 비밀번호를 분리해도 자바 쪽 지원이 온전하지 않다.)
STORE_PASSWORD="${STORE_PASSWORD:-}"

CA_DAYS="${CA_DAYS:-3650}"
LEAF_DAYS="${LEAF_DAYS:-825}"
KEY_ALG="${KEY_ALG:-RSA}"        # RSA | EC
KEY_BITS="${KEY_BITS:-2048}"
CERT_ALIAS="${CERT_ALIAS:-ecommerce}"
ORG="${ORG:-ecommerce-msa}"

# 인증서에 들어갈 이름들(SAN). 여기 없는 이름으로 접속하면 핸드셰이크가 깨진다.
# 쉼표 구분. IPv4/IPv6 는 자동으로 IP SAN 으로, 나머지는 DNS SAN 으로 분류한다.
DEFAULT_SANS="localhost,127.0.0.1,::1,host.docker.internal"
# 컨테이너/컴포즈 네트워크의 서비스 이름
DEFAULT_SANS="$DEFAULT_SANS,eureka-server,gateway-server,config-server"
DEFAULT_SANS="$DEFAULT_SANS,user-service,catalog-service,cart-service,order-service"
DEFAULT_SANS="$DEFAULT_SANS,pay-service,coupon-service,chat-service"
DEFAULT_SANS="$DEFAULT_SANS,kafka,redis,postgres,mongodb,elasticsearch"
# k8s 클러스터 DNS (와일드카드가 <서비스>.ecommerce.svc.cluster.local 을 전부 덮는다)
DEFAULT_SANS="$DEFAULT_SANS,*.ecommerce.svc.cluster.local,*.ecommerce.svc,*.ecommerce"
SANS="${SANS:-$DEFAULT_SANS}"

# Let's Encrypt
LE_DOMAIN="${LE_DOMAIN:-}"
LE_EMAIL="${LE_EMAIL:-}"
LE_STAGING="${LE_STAGING:-0}"
LE_LIVE_DIR="${LE_LIVE_DIR:-}"
CERTBOT_ARGS="${CERTBOT_ARGS:---standalone}"

# import
IMPORT_CERT="${IMPORT_CERT:-}"
IMPORT_KEY="${IMPORT_KEY:-}"
IMPORT_CHAIN="${IMPORT_CHAIN:-}"

EMIT_K8S="${EMIT_K8S:-1}"
K8S_NAMESPACE="${K8S_NAMESPACE:-ecommerce}"

# ---------------------------------------------------------------- 유틸

log()  { printf '>>> %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
run()  {
    if [ "$DRY_RUN" = "1" ]; then
        # DRY_RUN 출력은 로그로 남거나 CI 콘솔에 찍힌다. 비밀번호를 그대로 흘리지 않는다.
        printf '[DRY_RUN] %s\n' "$*" | sed -E "s/(pass:|-storepass |-passout pass:)[^ ']*/\1****/g"
    else
        eval "$@"
    fi
}

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' 가 필요합니다. 설치 후 다시 실행하세요."; }

need openssl
need keytool

is_ip() {
    # IPv4 또는 IPv6(콜론 포함)면 참
    case "$1" in
        *:*) return 0 ;;
        [0-9]*.[0-9]*.[0-9]*.[0-9]*)
            # 각 옥텟이 숫자인지까지 확인
            printf '%s' "$1" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$' && return 0 || return 1 ;;
        *) return 1 ;;
    esac
}

# ---------------------------------------------------------------- 준비

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

CA_KEY="$OUT_DIR/ca.key"
CA_CRT="$OUT_DIR/ca.crt"
LEAF_KEY="$OUT_DIR/tls.key"
LEAF_CRT="$OUT_DIR/tls.crt"          # leaf + 중간 체인 (fullchain)
LEAF_CSR="$OUT_DIR/tls.csr"
KEYSTORE="$OUT_DIR/keystore.p12"
TRUSTSTORE_P12="$OUT_DIR/truststore.p12"
TRUSTSTORE_JKS="$OUT_DIR/truststore.jks"
KAFKA_KEYSTORE="$OUT_DIR/kafka-keystore.p12"
KAFKA_TRUSTSTORE="$OUT_DIR/kafka-truststore.jks"
ENV_FILE="$OUT_DIR/certs.env"
K8S_SECRET="$OUT_DIR/k8s-tls-keystore.secret.yaml"
RENEW_HOOK="$OUT_DIR/renew-deploy-hook.sh"

# 산출물이 실수로 커밋되지 않도록 확인한다. 개인키가 git 이력에 들어가면
# DB 비밀번호 때와 똑같은 일(ops/01)이 반복된다 — 파일을 지워도 이력엔 남는다.
if git -C "$OUT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if ! git -C "$OUT_DIR" check-ignore -q "$OUT_DIR" 2>/dev/null; then
        warn "$OUT_DIR 는 git 추적 대상입니다. .gitignore 에 추가하지 않으면 개인키가 커밋됩니다."
        warn "  echo 'certs/' >> .gitignore"
        [ "$FORCE" = "1" ] || die "중단합니다. 의도한 것이라면 FORCE=1 로 다시 실행하세요."
    fi
fi

if [ -z "$STORE_PASSWORD" ]; then
    if [ -f "$ENV_FILE" ]; then
        # 재실행/갱신 시에는 기존 비밀번호를 재사용해야 배포된 Secret 과 어긋나지 않는다.
        STORE_PASSWORD="$(grep -E '^export LOCAL_KEY_STORE_PASSWORD=' "$ENV_FILE" 2>/dev/null \
                          | head -1 | sed -E 's/^[^=]+=//; s/^"//; s/"$//')" || true
        [ -n "$STORE_PASSWORD" ] && log "기존 $ENV_FILE 의 스토어 비밀번호를 재사용합니다."
    fi
fi
if [ -z "$STORE_PASSWORD" ]; then
    STORE_PASSWORD="$(openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24)"
    log "스토어 비밀번호를 새로 생성했습니다 (certs.env 에 기록됨)."
fi

log "모드=$MODE  출력=$OUT_DIR"

# ---------------------------------------------------------------- 검증 함수

verify_artifacts() {
    local failed=0

    log "검증 ①  인증서 체인"
    if [ -f "$CA_CRT" ]; then
        if openssl verify -CAfile "$CA_CRT" "$LEAF_CRT" >/dev/null 2>&1; then
            echo "    OK  leaf 가 CA 로 검증됨"
        else
            # fullchain 은 leaf+중간 이 이어져 있어 -untrusted 가 필요할 수 있다
            if openssl verify -CAfile "$CA_CRT" -untrusted "$LEAF_CRT" "$LEAF_CRT" >/dev/null 2>&1; then
                echo "    OK  leaf 가 중간 인증서를 거쳐 CA 로 검증됨"
            else
                echo "    FAIL 체인 검증 실패"; failed=1
            fi
        fi
    else
        echo "    SKIP CA 파일 없음(공인 인증서는 시스템 신뢰 저장소를 사용)"
    fi

    log "검증 ②  키와 인증서가 같은 쌍인가"
    local c k
    c="$(openssl x509 -noout -pubkey -in "$LEAF_CRT" | openssl sha256)"
    k="$(openssl pkey -pubout -in "$LEAF_KEY" | openssl sha256)"
    if [ "$c" = "$k" ]; then echo "    OK  공개키 일치"; else echo "    FAIL 인증서와 개인키가 다른 쌍입니다"; failed=1; fi

    log "검증 ③  SAN"
    local san
    san="$(openssl x509 -noout -ext subjectAltName -in "$LEAF_CRT" 2>/dev/null | tail -n +2 | tr -d ' ')"
    if [ -n "$san" ]; then
        echo "    $san"
        case "$san" in
            *DNS:localhost*|*localhost*) echo "    OK  localhost 포함" ;;
            *) warn "    localhost 가 SAN 에 없습니다 — 로컬 기동 시 핸드셰이크가 실패합니다" ;;
        esac
    else
        echo "    FAIL SAN 이 없습니다. CN 만 있는 인증서는 자바 17 이 거부합니다"; failed=1
    fi

    log "검증 ④  만료일"
    local end days
    end="$(openssl x509 -noout -enddate -in "$LEAF_CRT" | cut -d= -f2)"
    days="$(( ( $(date -d "$end" +%s) - $(date +%s) ) / 86400 ))"
    echo "    만료: $end (${days}일 남음)"
    if [ "$days" -lt 0 ]; then
        echo "    FAIL 이미 만료되었습니다"; failed=1
    elif [ "$days" -lt "$WARN_DAYS" ]; then
        warn "    ${WARN_DAYS}일 이내에 만료됩니다 — 갱신하세요"
    fi

    log "검증 ⑤  자바가 실제로 스토어를 읽는가"
    # 서비스가 기동 중에 겪을 것과 같은 경로를 미리 밟아 본다.
    # (여기서 실패하면 'Invalid keystore format' / 'password was incorrect' 로 기동이 죽는다)
    local s
    for s in "$KEYSTORE:PKCS12" "$TRUSTSTORE_P12:PKCS12" "$TRUSTSTORE_JKS:JKS" \
             "$KAFKA_KEYSTORE:PKCS12" "$KAFKA_TRUSTSTORE:JKS"; do
        local path="${s%:*}" type="${s##*:}"
        [ -f "$path" ] || { echo "    SKIP $(basename "$path") 없음"; continue; }
        if keytool -list -keystore "$path" -storetype "$type" -storepass "$STORE_PASSWORD" >/dev/null 2>&1; then
            echo "    OK  $(basename "$path") ($type)"
        else
            echo "    FAIL $(basename "$path") ($type) 를 열 수 없습니다"; failed=1
        fi
    done

    log "검증 ⑥  개인키 파일 권한"
    local f
    for f in "$LEAF_KEY" "$CA_KEY"; do
        [ -f "$f" ] || continue
        local perm; perm="$(stat -c '%a' "$f")"
        if [ "$perm" = "600" ]; then echo "    OK  $(basename "$f") 600"
        else warn "    $(basename "$f") 권한이 $perm 입니다 (600 권장)"; fi
    done

    return $failed
}

if [ "$MODE" = "verify" ]; then
    [ -f "$LEAF_CRT" ] || die "$LEAF_CRT 가 없습니다. 먼저 발급하세요."
    verify_artifacts && { log "검증 통과"; exit 0; } || die "검증 실패 — 위 FAIL 항목을 확인하세요."
fi

# ---------------------------------------------------------------- 발급

write_san_config() {
    # openssl 확장 설정 생성. DNS/IP 를 분류해 SAN 에 넣는다.
    local cfg="$1" dns=0 ip=0 entry
    {
        echo "[req]"
        echo "distinguished_name = dn"
        echo "req_extensions = v3_req"
        echo "prompt = no"
        echo "[dn]"
        echo "CN = ${CN:-localhost}"
        echo "O = $ORG"
        echo "[v3_req]"
        echo "basicConstraints = critical, CA:FALSE"
        echo "keyUsage = critical, digitalSignature, keyEncipherment"
        # 서비스들이 서로에게 **클라이언트로도** 붙는다(Kafka mTLS, Feign, Eureka).
        # serverAuth 만 넣으면 mTLS 에서 상대가 우리 인증서를 거부한다.
        echo "extendedKeyUsage = serverAuth, clientAuth"
        echo "subjectAltName = @alt_names"
        echo "[alt_names]"
    } > "$cfg"

    local IFS=','
    for entry in $SANS; do
        entry="$(printf '%s' "$entry" | tr -d '[:space:]')"
        [ -n "$entry" ] || continue
        if is_ip "$entry"; then
            ip=$((ip + 1)); echo "IP.$ip = $entry" >> "$cfg"
        else
            dns=$((dns + 1)); echo "DNS.$dns = $entry" >> "$cfg"
        fi
    done
    log "SAN: DNS ${dns}개, IP ${ip}개"
}

issue_dev() {
    local cfg="$OUT_DIR/openssl-san.cnf"

    if [ -f "$CA_CRT" ] && [ -f "$CA_KEY" ] && [ "$FORCE" != "1" ]; then
        log "기존 로컬 CA 를 재사용합니다 ($CA_CRT). 새로 만들려면 FORCE=1."
    else
        log "로컬 CA 생성 (${CA_DAYS}일)"
        run "openssl req -x509 -newkey $KEY_ALG:$KEY_BITS -sha256 -days $CA_DAYS -nodes \
             -keyout '$CA_KEY' -out '$CA_CRT' \
             -subj '/O=$ORG/CN=$ORG local development CA' \
             -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
             -addext 'keyUsage=critical,keyCertSign,cRLSign' 2>/dev/null"
    fi

    write_san_config "$cfg"

    log "서비스 인증서 발급 (${LEAF_DAYS}일)"
    run "openssl req -new -newkey $KEY_ALG:$KEY_BITS -nodes \
         -keyout '$LEAF_KEY' -out '$LEAF_CSR' -config '$cfg' 2>/dev/null"
    run "openssl x509 -req -in '$LEAF_CSR' -CA '$CA_CRT' -CAkey '$CA_KEY' -CAcreateserial \
         -out '$LEAF_CRT' -days $LEAF_DAYS -sha256 \
         -extfile '$cfg' -extensions v3_req 2>/dev/null"
    run "rm -f '$LEAF_CSR'"
}

issue_letsencrypt() {
    need certbot
    [ -n "$LE_DOMAIN" ] || die "MODE=letsencrypt 에는 LE_DOMAIN 이 필요합니다 (예: api.example.com)"
    [ -n "$LE_EMAIL" ]  || die "MODE=letsencrypt 에는 LE_EMAIL 이 필요합니다 (만료 알림 수신용)"

    local staging=""
    [ "$LE_STAGING" = "1" ] && staging="--staging"

    # 주의: --standalone 은 80 포트를 잠깐 점유한다. 게이트웨이가 80 을 쓰고 있으면
    # CERTBOT_ARGS 를 --webroot -w <경로> 나 DNS-01 플러그인으로 바꿔야 한다.
    # 와일드카드 인증서는 DNS-01 만 가능하다.
    log "certbot 으로 $LE_DOMAIN 인증서 발급 (staging=$LE_STAGING)"
    run "certbot certonly --non-interactive --agree-tos \
         -m '$LE_EMAIL' -d '$LE_DOMAIN' $staging $CERTBOT_ARGS"

    local live="${LE_LIVE_DIR:-/etc/letsencrypt/live/$LE_DOMAIN}"
    if [ "$DRY_RUN" = "1" ]; then
        log "[DRY_RUN] $live/{fullchain,privkey,chain}.pem 을 $OUT_DIR 로 복사"
        return
    fi
    [ -d "$live" ] || die "$live 이 없습니다. certbot 출력 경로를 LE_LIVE_DIR 로 지정하세요."

    cp "$live/fullchain.pem" "$LEAF_CRT"
    cp "$live/privkey.pem"   "$LEAF_KEY"
    # 공인 인증서는 시스템 신뢰 저장소로 검증되지만, 자바 트러스트스토어를 따로 쓰는
    # 구성(Kafka mTLS)에서는 발급자 체인이 필요하다.
    [ -f "$live/chain.pem" ] && cp "$live/chain.pem" "$CA_CRT"

    write_renew_hook
}

import_existing() {
    [ -n "$IMPORT_CERT" ] || die "MODE=import 에는 IMPORT_CERT 가 필요합니다"
    [ -n "$IMPORT_KEY" ]  || die "MODE=import 에는 IMPORT_KEY 가 필요합니다"
    log "기존 인증서 가져오기"
    run "cp '$IMPORT_CERT' '$LEAF_CRT'"
    run "cp '$IMPORT_KEY'  '$LEAF_KEY'"
    if [ -n "$IMPORT_CHAIN" ]; then run "cp '$IMPORT_CHAIN' '$CA_CRT'"; fi
}

write_renew_hook() {
    # Let's Encrypt 는 90일마다 갱신된다. 갱신은 PEM 만 바꾸므로, 키스토어를 다시 만들지
    # 않으면 **서비스는 옛 인증서를 계속 쓴다**. 이 훅이 그 간극을 메운다.
    cat > "$RENEW_HOOK" <<HOOK
#!/usr/bin/env bash
# certbot 갱신 후 자동 실행되는 훅. 아래 위치에 심볼릭 링크로 걸어 두세요:
#   sudo ln -sf "$RENEW_HOOK" /etc/letsencrypt/renewal-hooks/deploy/rebuild-java-stores.sh
set -euo pipefail
MODE=import \\
IMPORT_CERT="\${RENEWED_LINEAGE:-/etc/letsencrypt/live/$LE_DOMAIN}/fullchain.pem" \\
IMPORT_KEY="\${RENEWED_LINEAGE:-/etc/letsencrypt/live/$LE_DOMAIN}/privkey.pem" \\
IMPORT_CHAIN="\${RENEWED_LINEAGE:-/etc/letsencrypt/live/$LE_DOMAIN}/chain.pem" \\
OUT_DIR="$OUT_DIR" \\
STORE_PASSWORD="\${STORE_PASSWORD:-}" \\
  "$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

# 새 스토어를 클러스터에 반영 (k8s 를 쓰는 경우)
if command -v kubectl >/dev/null 2>&1; then
    kubectl apply -f "$K8S_SECRET"
    # Secret 볼륨은 kubelet 이 갱신하지만, 자바는 기동 시 한 번만 키스토어를 읽는다.
    kubectl -n "$K8S_NAMESPACE" rollout restart deployment
fi
HOOK
    chmod +x "$RENEW_HOOK"
    log "갱신 훅 생성: $RENEW_HOOK"
}

case "$MODE" in
    dev)         issue_dev ;;
    letsencrypt) issue_letsencrypt ;;
    import)      import_existing ;;
    *)           die "알 수 없는 MODE=$MODE (dev|letsencrypt|import|verify)" ;;
esac

# ---------------------------------------------------------------- 스토어 변환

build_stores() {
    log "자바 키스토어/트러스트스토어 생성"

    local certfile=""
    [ -f "$CA_CRT" ] && certfile="-certfile '$CA_CRT'"

    run "rm -f '$KEYSTORE'"
    run "openssl pkcs12 -export -name '$CERT_ALIAS' \
         -in '$LEAF_CRT' -inkey '$LEAF_KEY' $certfile \
         -out '$KEYSTORE' -passout pass:'$STORE_PASSWORD'"

    if [ -f "$CA_CRT" ]; then
        # 트러스트스토어는 두 형식 모두 만든다. **내용이 다르다** — 쓰임이 다르기 때문이다.
        #
        #   - PKCS12: JVM 전역 트러스트스토어. 공인 CA + 내부 CA
        #   - JKS   : Kafka 클라이언트 전용. 내부 CA 만
        run "rm -f '$TRUSTSTORE_P12' '$TRUSTSTORE_JKS'"

        # ── ⚠️ PKCS12 는 반드시 JDK cacerts 를 **바탕으로** 만든다 ──────────────
        #
        # 매니페스트가 이 파일을 `-Djavax.net.ssl.trustStore` 로 준다(k8s/30·31·32 …).
        # 그 옵션은 JDK 기본 cacerts 를 **보태는 게 아니라 대체한다.** 그래서 예전처럼
        # 내부 CA 하나만 넣으면 신뢰 목록이 1개가 되고(기본 cacerts 는 151개),
        # **공인 인증서를 쓰는 모든 바깥 통신이 죽는다:**
        #
        #   · S3 이미지 업로드  → PKIX path building failed (상품 등록이 여기서 막혔다)
        #   · PayPal API       → 결제
        #   · Gmail SMTP       → 가입 인증 메일
        #   · OAuth 토큰 엔드포인트 → 소셜 로그인
        #
        # 증상이 "인증서를 못 찾는다" 라서 AWS 키 문제로 오해하기 쉽다. 키를 바꿔도
        # 그대로다 — TLS 핸드셰이크에서 막히므로 자격증명까지 가지도 못한다.
        #
        # cacerts 는 실행하는 쪽 JDK 것을 쓴다. 컨테이너의 JRE 와 완전히 같지는 않지만
        # 공인 루트는 느리게 바뀌므로 실용적으로 문제없다. 바뀌면 이 스크립트를 다시
        # 돌려 Secret 을 갱신한다.
        local cacerts
        cacerts=$(java -XshowSettings:properties -version 2>&1 \
            | sed -n 's/.*java\.home = //p' | head -1)
        cacerts="${cacerts}/lib/security/cacerts"

        if [ -f "$cacerts" ]; then
            run "keytool -importkeystore -noprompt \
                 -srckeystore '$cacerts' -srcstorepass changeit \
                 -destkeystore '$TRUSTSTORE_P12' -deststoretype PKCS12 \
                 -deststorepass '$STORE_PASSWORD' 2>/dev/null"
        else
            warn "JDK cacerts 를 찾지 못했습니다($cacerts) — 트러스트스토어에 공인 CA 가 빠집니다."
            warn "  그러면 S3·PayPal·SMTP 같은 바깥 HTTPS 가 전부 PKIX 오류로 실패합니다."
        fi

        run "keytool -importcert -noprompt -alias ca -file '$CA_CRT' \
             -keystore '$TRUSTSTORE_P12' -storetype PKCS12 -storepass '$STORE_PASSWORD' 2>/dev/null"

        # Kafka 는 내부 브로커하고만 말한다. 공인 CA 를 넣을 이유가 없으므로 넣지 않는다 —
        # 신뢰 범위는 필요한 만큼만.
        run "keytool -importcert -noprompt -alias ca -file '$CA_CRT' \
             -keystore '$TRUSTSTORE_JKS' -storetype JKS -storepass '$STORE_PASSWORD' 2>/dev/null"

        if [ "${DRY_RUN:-0}" != "1" ] && [ -f "$TRUSTSTORE_P12" ]; then
            local n
            n=$(keytool -list -keystore "$TRUSTSTORE_P12" -storepass "$STORE_PASSWORD" 2>/dev/null \
                | grep -c 'trustedCertEntry' || true)
            # 1개면 내부 CA 만 들어간 것이다 — 위 사고가 그대로 재현된다.
            if [ "${n:-0}" -le 1 ]; then
                warn "트러스트스토어 항목이 ${n}개뿐입니다 — 공인 CA 가 빠졌습니다."
                warn "  바깥 HTTPS(S3·PayPal·SMTP)가 PKIX 오류로 실패합니다."
            else
                printf '  신뢰 항목 %s개 (공인 CA + 내부 CA)\n' "$n"
            fi
        fi
    else
        warn "CA/체인 파일이 없어 트러스트스토어를 만들지 않았습니다(공인 인증서면 정상)."
    fi

    # Kafka 는 같은 신원을 쓰되 파일명을 분리해 둔다.
    # k8s/10-configmap.yaml 이 이 이름을 가리키고 있다.
    run "cp '$KEYSTORE' '$KAFKA_KEYSTORE'"
    [ -f "$TRUSTSTORE_JKS" ] && run "cp '$TRUSTSTORE_JKS' '$KAFKA_TRUSTSTORE'"

    if [ "$DRY_RUN" != "1" ]; then
        chmod 600 "$LEAF_KEY" 2>/dev/null || true
        [ -f "$CA_KEY" ] && chmod 600 "$CA_KEY"
        chmod 600 "$KEYSTORE" "$KAFKA_KEYSTORE" 2>/dev/null || true
        chmod 644 "$LEAF_CRT" 2>/dev/null || true
    fi
}

build_stores

# ---------------------------------------------------------------- 주입 ① env 파일

emit_env() {
    if [ "$DRY_RUN" = "1" ]; then log "[DRY_RUN] $ENV_FILE 생성"; return; fi
    cat > "$ENV_FILE" <<ENV
# ops/07-issue-certificates.sh 가 생성. 직접 편집하지 말 것 — 재발급 시 덮어써진다.
# 사용:  set -a; source $ENV_FILE; set +a;  ./gradlew :user-service:bootRun
#
# ⚠️ 이 파일과 같은 디렉터리의 *.key / *.p12 는 개인키다. 커밋 금지, 공유 금지.

# ---- 서비스 HTTPS (server.ssl.*) ----
export LOCAL_KEY_STORE_PATH="$KEYSTORE"
export LOCAL_KEY_STORE_PASSWORD="$STORE_PASSWORD"
export LOCAL_KEY_PASSWORD="$STORE_PASSWORD"
export LOCAL_TRUST_STORE_PATH="$TRUSTSTORE_P12"

# ---- PEM (Redis SSL, Elasticsearch 트러스트 번들) ----
export LOCAL_CACERT_PATH="$CA_CRT"
export LOCAL_CRT_PATH="$LEAF_CRT"
export LOCAL_KEY_PATH="$LEAF_KEY"

# ---- Kafka mTLS ----
# 브로커가 SSL 리스너로 떠 있을 때만 SSL 로 바꾸세요. PLAINTEXT 로컬 브로커에 SSL 로 붙으면
# 'Connection to node -1 terminated during authentication' 으로 조용히 실패합니다.
export KAFKA_SECURITY_PROTOCOL="\${KAFKA_SECURITY_PROTOCOL:-PLAINTEXT}"
export KAFKA_SSL_KEY_STORE_LOCATION="$KAFKA_KEYSTORE"
export KAFKA_SSL_KEY_STORE_PASSWORD="$STORE_PASSWORD"
export KAFKA_SSL_KEY_STORE_TYPE="PKCS12"
export KAFKA_SSL_TRUST_STORE_LOCATION="$KAFKA_TRUSTSTORE"
export KAFKA_SSL_TRUST_STORE_PASSWORD="$STORE_PASSWORD"
export KAFKA_SSL_TRUST_STORE_TYPE="JKS"
ENV
    chmod 600 "$ENV_FILE"
    log "env 파일 생성: $ENV_FILE"
}

emit_env

# ---------------------------------------------------------------- 주입 ② k8s Secret

emit_k8s_secret() {
    [ "$EMIT_K8S" = "1" ] || return 0
    if [ "$DRY_RUN" = "1" ]; then log "[DRY_RUN] $K8S_SECRET 생성"; return; fi

    b64() { base64 -w0 < "$1"; }

    {
        cat <<HEAD
# ops/07-issue-certificates.sh 가 생성. **개인키가 들어 있으므로 커밋 금지.**
# 적용:  kubectl apply -f $(basename "$K8S_SECRET")
#
# 각 Deployment 는 이 Secret 을 /app/resources 에 마운트하고
# LOCAL_KEY_STORE_PATH=/app/resources/keystore.p12 로 참조한다(k8s/31-gateway-server.yaml 등).
apiVersion: v1
kind: Secret
metadata:
  name: tls-keystore
  namespace: $K8S_NAMESPACE
type: Opaque
data:
HEAD
        printf '  keystore.p12: %s\n' "$(b64 "$KEYSTORE")"
        [ -f "$TRUSTSTORE_P12" ] && printf '  truststore.p12: %s\n' "$(b64 "$TRUSTSTORE_P12")"
        [ -f "$TRUSTSTORE_JKS" ] && printf '  truststore.jks: %s\n' "$(b64 "$TRUSTSTORE_JKS")"
        [ -f "$KAFKA_KEYSTORE" ] && printf '  kafka-keystore.p12: %s\n' "$(b64 "$KAFKA_KEYSTORE")"
        [ -f "$KAFKA_TRUSTSTORE" ] && printf '  kafka-truststore.jks: %s\n' "$(b64 "$KAFKA_TRUSTSTORE")"
        [ -f "$CA_CRT" ] && printf '  ca.crt: %s\n' "$(b64 "$CA_CRT")"
        printf '  tls.crt: %s\n' "$(b64 "$LEAF_CRT")"
        printf '  tls.key: %s\n' "$(b64 "$LEAF_KEY")"
    } > "$K8S_SECRET"

    chmod 600 "$K8S_SECRET"
    log "k8s Secret 생성: $K8S_SECRET"
}

emit_k8s_secret

# ---------------------------------------------------------------- 검증

if [ "$DRY_RUN" = "1" ]; then
    log "DRY_RUN 이므로 검증을 건너뜁니다."
    exit 0
fi

verify_artifacts || die "산출물 검증에 실패했습니다 — 위 FAIL 항목을 확인하세요."

cat <<NEXT

>>> 완료. 다음 단계:

  1) 로컬 실행
       set -a; source $ENV_FILE; set +a
       ./gradlew :gateway-server:bootRun

  2) 브라우저/curl 이 로컬 CA 를 믿게 하려면 (MODE=dev 일 때만)
       curl --cacert $CA_CRT https://localhost:8000/actuator/health
       # 시스템 신뢰 저장소에 넣으려면:
       #   sudo cp $CA_CRT /usr/local/share/ca-certificates/${ORG}-dev.crt && sudo update-ca-certificates

  3) k8s 주입
       kubectl apply -f $K8S_SECRET
       kubectl -n $K8S_NAMESPACE patch secret ecommerce-secrets --type merge -p \\
         '{"stringData":{"LOCAL_KEY_STORE_PASSWORD":"<위 비밀번호>","LOCAL_KEY_PASSWORD":"<위 비밀번호>","KAFKA_SSL_KEY_STORE_PASSWORD":"<위 비밀번호>","KAFKA_SSL_TRUST_STORE_PASSWORD":"<위 비밀번호>"}}'
       kubectl -n $K8S_NAMESPACE rollout restart deployment
     (비밀번호는 $ENV_FILE 안에 있습니다. 화면에 출력하지 않았습니다.)

  4) 만료 감시
       MODE=verify ./ops/07-issue-certificates.sh     # cron 에 걸어 두면 만료 ${WARN_DAYS}일 전에 경고

NEXT
