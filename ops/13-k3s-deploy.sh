#!/usr/bin/env bash
# 운영 액션 ⑬ — k3s 실클러스터 배포 검증
#
# ops/12 가 "전부 동시에 뜨는가"를 호스트에서 확인했다면, 이 스크립트는 남아 있던 마지막 질문 —
# **"k8s/ 매니페스트가 진짜 클러스터에서 동작하는가"** — 에 답한다. Docker 만 있으면 된다:
# k3s 를 컨테이너로 띄우고, 이미지를 빌드·임포트하고, 90-dev-infra + 서비스 매니페스트 전체를
# 적용한 뒤, NodePort 로 노출된 게이트웨이에 ops/06 스모크를 돌린다.
#
# 사용:
#   ./ops/13-k3s-deploy.sh                # 전체: 클러스터 → 이미지 → 배포 → 스모크 → (KEEP=1 아니면) 정리
#   KEEP=1 ...                            # 클러스터 유지 (kubectl: docker exec k3s-ecommerce kubectl ...)
#   ACTION=down ...                       # 정리
#   SKIP_BUILD=1 ...                      # bootJar/이미지 빌드 생략(재실행 시)
#
# 요구: docker · 리눅스(privileged 컨테이너) · ops/07 인증서(없으면 자동 발급)
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
CERTS="$ROOT/certs"
CLUSTER="k3s-ecommerce"
K3S_IMAGE="${K3S_IMAGE:-${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX:-}rancher/k3s:v1.31.4-k3s1}"
REGISTRY="k3s.local"                       # 매니페스트의 REGISTRY_PLACEHOLDER 치환값
PW="${STAGING_PASSWORD:-staging-rehearsal-pw}"
JWT="${JWT_SECRET_KEY:-0123456789012345678901234567890123456789012345678901234567890123}"
JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:17-jre-jammy}"
KEEP="${KEEP:-0}"
ACTION="${ACTION:-up}"
IMG="${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX:-}"

# ⚠️ 새 서비스를 추가하면 여기에도 넣어야 한다. 빠뜨리면 매니페스트(k8s/4x-*.yaml)는
# 이미지를 참조하는데 그 이미지를 아무도 만들지 않아 ImagePullBackOff 로 끝난다.
# notification-service(8188)·delivery-service(8189)가 실제로 그렇게 빠져 있었다.
SERVICES="eureka-server gateway-server config-server user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection"
INFRA_IMAGES="${IMG}postgres:16-alpine ${IMG}redis:7-alpine ${IMG}confluentinc/cp-kafka:7.6.1 ${IMG}mongo:7 ${IMG}elasticsearch:8.15.5"
# k3s 가 모든 파드의 샌드박스로 쓰는 pause 이미지. 이것까지 임포트하지 않으면 레지스트리가
# 막힌 환경에서 **모든 파드가 FailedCreatePodSandBox** 로 멈춘다(4라운드에서 실측).
PAUSE_IMAGE="rancher/mirrored-pause:3.6"
# k3s 가 스스로 받아 오는 kube-system 이미지들. pause 와 같은 이유로 미리 넣어야 한다.
# 특히 **CoreDNS 가 없으면 클러스터 DNS 자체가 없어** 모든 파드가 DB·Kafka·서로를
# 찾지 못한다 — 증상은 애플리케이션 오류(Flyway "connection attempt failed")로 나타나
# 원인을 엉뚱한 곳에서 찾게 된다(k3s 검증에서 실측).
SYSTEM_IMAGES="rancher/mirrored-coredns-coredns:1.12.0"

log()  { printf '\n>>> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
K()    { docker exec -i "$CLUSTER" kubectl "$@"; }

[ "$ACTION" = "down" ] && { docker rm -f "$CLUSTER" >/dev/null 2>&1; echo "정리 완료"; exit 0; }
command -v docker >/dev/null || die "docker 가 필요합니다"

# ---------------------------------------------------------------- 0. 인증서

if [ ! -f "$CERTS/certs.env" ]; then
    log "0/7  인증서 발급 (ops/07)"
    ./ops/07-issue-certificates.sh >/dev/null || die "인증서 발급 실패"
fi
set -a; . "$CERTS/certs.env"; set +a
STORE_PW="$LOCAL_KEY_STORE_PASSWORD"

# Elasticsearch 파드의 부트스트랩 요구치 (k3s 는 호스트 커널을 공유한다)
if [ "$(sysctl -n vm.max_map_count)" -lt 262144 ]; then
    sysctl -w vm.max_map_count=262144 >/dev/null || die "vm.max_map_count 를 올릴 수 없습니다(root 필요)"
fi

# ---------------------------------------------------------------- 1. 이미지 빌드

if [ "${SKIP_BUILD:-0}" != "1" ]; then
    log "1/7  bootJar 빌드"
    sh gradlew bootJar -q 2>/dev/null || ./gradlew bootJar -q || die "gradle 빌드 실패"
    sh gradlew --stop >/dev/null 2>&1 || true   # 데몬 메모리 반환 — 파드 9개와 공존 못 한다

    log "     서비스 이미지 ${SERVICES// /, } 빌드"
    # 저장소의 서비스 Dockerfile 은 빌더 스테이지에서 gradle 풀빌드를 다시 한다 — 로컬 검증에서는
    # 방금 빌드한 jar 를 같은 런타임(JRE + 동일 기동 인자)으로 감싸는 편이 수 배 빠르다.
    # 기동 인자(--spring.profiles.active, optional configserver import)는 원본 이미지와 동일하게 유지한다.
    for m in $SERVICES; do
        jar=$(ls "$m"/build/libs/*.jar | grep -v plain | head -1)
        [ -n "$jar" ] || die "$m jar 없음"
        docker build -q -t "$REGISTRY/ecommerce-msa/$m:1.0.0" -f - "$(dirname "$jar")" <<EOF >/dev/null || die "$m 이미지 빌드 실패"
FROM $JRE_IMAGE
COPY $(basename "$jar") /app.jar
ENV SPRING_PROFILES_ACTIVE=local
# 9개 JVM 이 한 노드에 동시에 뜬다. 힙을 묶지 않으면 파드 limit(768Mi)×9 까지 부풀어
# 단일 노드 검증 환경에서 k3s 컨테이너째 OOM 으로 죽는다(11라운드에서 실측).
ENV JAVA_TOOL_OPTIONS="-Xms48m -Xmx280m"
# 매니페스트의 runAsNonRoot 는 **숫자 UID** 여야 검증된다 — 원본 Dockerfile 처럼 이름(spring)만
# 쓰면 kubelet 이 'image will run as root' 로 거부한다(10라운드에서 실측).
RUN useradd --system --uid 1000 spring
USER 1000
ENTRYPOINT ["sh","-c","java -jar /app.jar --spring.profiles.active=\${SPRING_PROFILES_ACTIVE} \${CONFIG_SERVER_URL:+--spring.config.import=optional:configserver:\${CONFIG_SERVER_URL}}"]
EOF
        info "built $m"
    done
fi

# ---------------------------------------------------------------- 2. k3s 클러스터

log "2/7  k3s 클러스터 기동"
if [ "${REUSE_CLUSTER:-0}" = "1" ] && K get nodes 2>/dev/null | grep -q " Ready"; then
    info "기존 클러스터 재사용 (REUSE_CLUSTER=1)"
else
docker rm -f "$CLUSTER" >/dev/null 2>&1
# NodePort 를 호스트로 퍼블리시해 두면 스모크를 호스트에서 그대로 돌릴 수 있다.
# eviction 임계값 완화: 기본값(가용 <10%)은 쿼터가 큰 CI/샌드박스 디스크에서 퍼센트만 보고
# disk-pressure taint 를 걸어 **모든 파드가 Pending** 이 된다(3라운드에서 실측).
# 검증용 일회성 클러스터이므로 절대량 기준으로 낮춘다.
docker run -d --name "$CLUSTER" --privileged \
    -p 8000:30800 -p 8761:30761 \
    "$K3S_IMAGE" server --disable=traefik \
    --kubelet-arg=eviction-hard=imagefs.available\<1Gi,nodefs.available\<1Gi \
    --kubelet-arg=image-gc-high-threshold=100 \
    --kubelet-arg=eviction-pressure-transition-period=30s >/dev/null || die "k3s 기동 실패"
for i in $(seq 1 60); do
    K get nodes 2>/dev/null | grep -q " Ready" && break
    sleep 2
    [ "$i" = 60 ] && { docker logs --tail 20 "$CLUSTER"; die "k3s 노드가 Ready 가 되지 않습니다"; }
done
info "node Ready"
fi

# 일부 샌드박스형 실행 환경(user namespace)에서는 oom_score_adj 를 **낮추는** 쓰기가 거부된다.
# CRI 는 모든 파드 샌드박스에 oomScoreAdj=-998 을 강제하므로, 그런 호스트에서는 ctr 은 되는데
# 파드만 전부 'runc create failed: can't get final child's PID from pipe: EOF' 로 죽는다 —
# 진짜 원인은 bundle 의 log.json 에만 남는다: 'failed to update /proc/self/oom_score_adj:
# Permission denied' (6라운드에서 실측). runc 래퍼로 해당 필드를 중화한다.
# 일반 호스트에서는 쓰기가 허용되므로 이 래퍼는 무해하다.
# 멱등 가드는 셔뱅으로 판별한다 — 진짜 runc 바이너리에도 "oomScoreAdj" 문자열이
# 들어 있어 grep 가드는 설치를 건너뛰어 버린다(7라운드에서 실측).
docker exec "$CLUSTER" sh -c '
if [ "$(head -c 2 /bin/runc)" != "#!" ]; then
    cp /bin/runc /bin/runc.real
    cat > /bin/runc <<"WRAP"
#!/bin/sh
bundle=""; prev=""
for a in "$@"; do
  { [ "$prev" = "--bundle" ] || [ "$prev" = "-b" ]; } && bundle="$a"
  prev="$a"
done
if [ -n "$bundle" ] && [ -f "$bundle/config.json" ]; then
  sed -i "s/\"oomScoreAdj\":-\{0,1\}[0-9]*/\"oomScoreAdj\":0/g" "$bundle/config.json"
fi
exec /bin/runc.real "$@"
WRAP
    chmod +x /bin/runc
fi' || die "runc 래퍼 설치 실패"
info "runc oomScoreAdj 래퍼 설치"

# ---------------------------------------------------------------- 3. 이미지 임포트

log "3/7  이미지 임포트 (클러스터는 외부 레지스트리에 접근하지 않는다)"
# 함정 둘 (실측):
#   · -n k8s.io 필수 — k3s 의 kubelet 은 k8s.io 네임스페이스만 본다. 기본 네임스페이스로
#     임포트하면 성공한 것처럼 보이지만 파드는 영원히 ImagePullBackOff 다.
#   · --platform linux/amd64 필수 — docker v29 의 save 는 멀티플랫폼 매니페스트를 쓰는데,
#     tar 에 없는 변형까지 참조해 ctr 이 'content digest not found' 로 죽는다.
if ! docker image inspect "$PAUSE_IMAGE" >/dev/null 2>&1; then
    if docker pull "${IMG}$PAUSE_IMAGE" >/dev/null 2>&1; then
        docker tag "${IMG}$PAUSE_IMAGE" "$PAUSE_IMAGE"
    else
        # 레지스트리가 아예 안 되는 환경의 최후 수단: pause 는 "떠 있기만 하면 되는"
        # 샌드박스 홀더라, 이미 가진 아무 이미지 위에 sleep 만 얹어도 동등하게 동작한다.
        info "pause 원본을 받을 수 없어 로컬 대체 이미지를 빌드합니다"
        docker build -q -t "$PAUSE_IMAGE" - <<EOF >/dev/null || die "pause 대체 이미지 빌드 실패"
FROM ${IMG}redis:7-alpine
ENTRYPOINT ["sleep","infinity"]
EOF
    fi
fi
for img in $PAUSE_IMAGE $SYSTEM_IMAGES $INFRA_IMAGES $(for m in $SERVICES; do echo "$REGISTRY/ecommerce-msa/$m:1.0.0"; done); do
    if ! docker image inspect "$img" >/dev/null 2>&1; then
        docker pull "$img" >/dev/null 2>&1 || docker pull "${IMG}$img" >/dev/null 2>&1 \
            && docker tag "${IMG}$img" "$img" 2>/dev/null || die "$img 를 받을 수 없습니다"
    fi
    docker save --platform linux/amd64 "$img" 2>/dev/null \
        | docker exec -i "$CLUSTER" ctr -n k8s.io images import - >/dev/null \
        || die "$img 임포트 실패"
    info "imported $img"
done

# ---------------------------------------------------------------- 4. 시크릿 + 매니페스트

log "4/7  네임스페이스 · 시크릿 · 인프라"
K apply -f - < k8s/00-namespace.yaml >/dev/null

# 인프라 자격증명 (90-dev-infra 가 참조)
# ⚠️ K() 는 컨테이너 안의 kubectl 이므로 파일은 반드시 stdin 으로 넘긴다 —
# -f <경로> 는 컨테이너 안에서 해석돼 조용히 실패한다(2라운드에서 실측).
# 네임스페이스를 먼저 — 전체 파일을 선적용하면 시크릿보다 디플로이먼트가 먼저 생겨
# 파드가 FailedMount 로 몇 분을 허비한다.
for ns in postgres redis kafka mongodb elasticsearch; do
    K create namespace "$ns" --dry-run=client -o yaml | K apply -f - >/dev/null
done
K -n postgres create secret generic pg-credentials --from-literal=password="$PW" \
    --dry-run=client -o yaml | K apply -f - >/dev/null
docker cp "$CERTS/tls.crt" "$CLUSTER:/tmp/tls.crt" && docker cp "$CERTS/tls.key" "$CLUSTER:/tmp/tls.key"
K -n postgres create secret generic pg-tls \
    --from-file=tls.crt=/tmp/tls.crt --from-file=tls.key=/tmp/tls.key \
    --dry-run=client -o yaml | K apply -f - >/dev/null
K -n redis create secret generic redis-auth --from-literal=password="$PW" \
    --dry-run=client -o yaml | K apply -f - >/dev/null
K -n mongodb create secret generic mongo-auth \
    --from-literal=username=admin --from-literal=password="$PW" \
    --dry-run=client -o yaml | K apply -f - >/dev/null
K apply -f - < k8s/90-dev-infra.yaml >/dev/null || die "90-dev-infra 적용 실패"

# 애플리케이션 시크릿 (20-secret.example.yaml 의 실값 버전)
for f in keystore.p12 truststore.p12 truststore.jks kafka-keystore.p12 kafka-truststore.jks ca.crt tls.crt tls.key; do
    docker cp "$CERTS/$f" "$CLUSTER:/tmp/$f"
done
K -n ecommerce create secret generic tls-keystore \
    --from-file=keystore.p12=/tmp/keystore.p12 --from-file=truststore.p12=/tmp/truststore.p12 \
    --from-file=truststore.jks=/tmp/truststore.jks --from-file=kafka-keystore.p12=/tmp/kafka-keystore.p12 \
    --from-file=kafka-truststore.jks=/tmp/kafka-truststore.jks --from-file=ca.crt=/tmp/ca.crt \
    --from-file=tls.crt=/tmp/tls.crt --from-file=tls.key=/tmp/tls.key \
    --dry-run=client -o yaml | K apply -f - >/dev/null
K -n ecommerce create secret generic ecommerce-secrets \
    --from-literal=JWT_SECRET_KEY="$JWT" \
    --from-literal=LOCAL_DB_PASSWORD="$PW" \
    --from-literal=LOCAL_KEY_STORE_PASSWORD="$STORE_PW" \
    --from-literal=LOCAL_KEY_PASSWORD="$STORE_PW" \
    --from-literal=KAFKA_SSL_TRUST_STORE_PASSWORD="$STORE_PW" \
    --from-literal=KAFKA_SSL_KEY_STORE_PASSWORD="$STORE_PW" \
    --from-literal=AMAZON_ACCESS_KEY=dummy --from-literal=AMAZON_SECRET_KEY=dummy \
    --from-literal=AMAZON_BUCKET_NAME=dummy --from-literal=AMAZON_REGION=ap-northeast-2 \
    --from-literal=LOCAL_ELASTICSEARCH_USERNAME=elastic \
    --from-literal=LOCAL_ELASTICSEARCH_PASSWORD=unused \
    --from-literal=SPRING_DATA_MONGODB_CLIENT="mongodb://admin:$PW@mongodb.mongodb.svc.cluster.local:27017/chat?ssl=false&authSource=admin" \
    --from-literal=SPRING_DATA_MONGODB_PASSWORD="$PW" \
    --from-literal=MAIL_HOST=localhost --from-literal=MAIL_PORT=25 \
    --from-literal=MAIL_USERNAME=dummy --from-literal=MAIL_PASSWORD=dummy \
    --from-literal=REACT_APP_PAYPAL_CLIENT_ID=dummy \
    --dry-run=client -o yaml | K apply -f - >/dev/null
info "secrets 적용"

# ConfigMap — dev 인프라에 맞는 오버라이드:
#   · Kafka 는 90-dev-infra 가 PLAINTEXT 리스너이므로 SSL→PLAINTEXT
#   · 더미 SMTP 가 종합 health 를 DOWN 으로 만들지 않도록 mail 헬스 비활성
sed -e 's/KAFKA_SECURITY_PROTOCOL: "SSL"/KAFKA_SECURITY_PROTOCOL: "PLAINTEXT"/' \
    -e 's|APP_PUBLIC_URL: .*|APP_PUBLIC_URL: "https://localhost:8000"|' \
    -e '/^data:/a\  MANAGEMENT_HEALTH_MAIL_ENABLED: "false"' \
    k8s/10-configmap.yaml | K apply -f - >/dev/null
info "configmap 적용 (Kafka PLAINTEXT 오버라이드)"

log "     인프라 rollout 대기"
for d in postgres/postgres redis/redis kafka/kafka mongodb/mongodb elasticsearch/elasticsearch; do
    ns="${d%%/*}"; name="${d##*/}"
    K -n "$ns" rollout status deploy/"$name" --timeout=300s >/dev/null \
        && info "ready $ns/$name" \
        || { K -n "$ns" describe pod -l app="$name" | tail -15; die "$d 기동 실패"; }
done

# ---------------------------------------------------------------- 5. 서비스 배포

RUN_STATUS="$(mktemp -d)"
trap 'rm -rf "$RUN_STATUS"' EXIT
log "5/7  서비스 9종 배포"
for f in k8s/30-eureka-server.yaml k8s/31-gateway-server.yaml k8s/32-config-server.yaml k8s/4*.yaml; do
    sed "s|REGISTRY_PLACEHOLDER|$REGISTRY|" "$f" | K apply -f - >/dev/null
    info "applied $(basename "$f")"
done
# frontend(빌드 무거움)와 ingress(traefik 비활성) 는 이 검증의 범위 밖 — 60/50 은 적용하지 않는다.

# 단일 노드 검증에서는 롤링 서지를 끈다. maxSurge=1 이면 재배포 때 파드가 18개가 되어
# 새 파드가 자원을 못 얻고, 구 파드는 새 파드가 Ready 가 될 때까지 남아 **교착**한다(실측).
for m in $SERVICES; do
    K -n ecommerce patch deploy "$m" -p \
      '{"spec":{"strategy":{"rollingUpdate":{"maxSurge":0,"maxUnavailable":1}}}}' >/dev/null 2>&1
    # 매니페스트의 JAVA_TOOL_OPTIONS(트러스트스토어)가 이미지 ENV 의 힙 상한을 덮어쓴다.
    # 단일 노드에서 9개 JVM 이 공존하려면 둘 다 필요하므로 이어붙인다.
    K -n ecommerce set env deploy/"$m" \
      JAVA_TOOL_OPTIONS="-Xms48m -Xmx280m -Djavax.net.ssl.trustStore=/app/resources/truststore.p12 -Djavax.net.ssl.trustStorePassword=$STORE_PW -Djavax.net.ssl.trustStoreType=PKCS12" >/dev/null 2>&1
done

# 게이트웨이/유레카를 NodePort 로 노출 (docker run 의 -p 와 맞물린다)
K -n ecommerce patch svc gateway-server -p \
    '{"spec":{"type":"NodePort","ports":[{"port":8000,"targetPort":8000,"nodePort":30800}]}}' >/dev/null
K -n ecommerce patch svc eureka-server -p \
    '{"spec":{"type":"NodePort","ports":[{"port":8761,"targetPort":8761,"nodePort":30761}]}}' >/dev/null

log "     서비스 rollout 대기 (startupProbe 여유 포함 최대 5분/개)"
# ⚠️ 순차 대기는 불공정하다. 9개 파드는 **동시에** 기동하는데 대기는 순서대로 하므로,
# 목록 앞쪽 서비스는 노드가 가장 붐빌 때 자기 예산을 다 쓰고, 뒤쪽은 앞의 대기 시간만큼
# 덤을 받는다. 실제로 17라운드에서 앞선 user/catalog 만 시간 초과로 실패하고
# 나머지 7개는 통과했다 — 서비스 문제가 아니라 계측 문제였다. 전부 병렬로 기다린다.
failed=0
pids=""
for m in $SERVICES; do
    ( K -n ecommerce rollout status deploy/"$m" --timeout=600s >/dev/null 2>&1 \
        && echo "ready $m" > "$RUN_STATUS/$m" \
        || echo "FAILED" > "$RUN_STATUS/$m" ) &
    pids="$pids $!"
done
for p in $pids; do wait "$p"; done
for m in $SERVICES; do
    if grep -q "^ready" "$RUN_STATUS/$m" 2>/dev/null; then
        info "ready $m"
    else
        failed=$((failed+1)); printf '    ❌ %s\n' "$m"
        K -n ecommerce logs deploy/"$m" --tail=5 2>/dev/null | sed 's/^/       /'
    fi
done
[ "$failed" -gt 0 ] && { [ "$KEEP" = "1" ] || docker rm -f "$CLUSTER" >/dev/null; die "${failed}개 서비스 rollout 실패"; }

# ---------------------------------------------------------------- 6. DLT 토픽

log "6/7  DLT 토픽 9종"
for t in order payment product-update user-created cart-empty cart-rollback coupon-use coupon-rollback chat; do
    K -n kafka exec deploy/kafka -- kafka-topics --bootstrap-server localhost:9092 \
        --create --if-not-exists --topic "$t.DLT" --partitions 3 --replication-factor 1 \
        --config retention.ms=1209600000 >/dev/null 2>&1 || die "$t.DLT 생성 실패"
done
info "DLT 9종 생성/확인"

# ---------------------------------------------------------------- 7. 스모크

log "7/7  스모크 (게이트웨이 NodePort 경유)"
# 게이트웨이 로컬 레지스트리 캐시(30초 주기)가 채워질 때까지 — ops/12 와 같은 이유
for i in $(seq 1 40); do
    code=$(curl -sk --max-time 5 -o /dev/null -w '%{http_code}' \
        "https://localhost:8000/catalog-service/api/category/categories" 2>/dev/null)
    [ "$code" = "200" ] && { info "게이트웨이 라우팅 확인 (${i}회차)"; break; }
    sleep 3
done

# 개별 서비스 직접 접근(§1)은 클러스터 밖에서 불가능하지만, 같은 검증을 k8s readiness 프로브가
# 이미 수행했다(rollout 통과 = 전원 readiness). SKIP_DIRECT=1 로 §1 만 생략한다.
SKIP_DIRECT=1 INSECURE=1 SKIP_SAGA=1 \
    GATEWAY=https://localhost:8000 EUREKA=https://localhost:8761 \
    ./ops/06-smoke-test.sh
smoke=$?

if [ "$KEEP" = "1" ]; then
    info "KEEP=1 — 클러스터 유지. 사용: docker exec $CLUSTER kubectl get pods -A"
else
    docker rm -f "$CLUSTER" >/dev/null
    info "클러스터 정리 완료"
fi
exit "$smoke"
