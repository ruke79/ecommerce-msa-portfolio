#!/usr/bin/env bash
# 운영 액션 ⑫ — 스테이징 배포 리허설 (인프라 5종 + 서비스 9종 + 스모크)
#
# docs/00 §10 의 권장 순서 마지막 단계 — "스테이징에 전체 배포 후 ops/06 스모크" — 를
# 한 머신에서 재현한다. 개별 서비스 기동(docs/14)과 Testcontainers(docs/17)가 못 보는 것,
# 즉 **전부 동시에 떠서 서로를 찾는가**(Eureka 등록 → 게이트웨이 lb:// 라우팅 → 레이트리미터)를
# 배포 전에 확인하는 것이 목적이다.
#
# 올리는 것:
#   PostgreSQL(SSL, DB 8종) · Redis(requirepass) · Kafka(KRaft) · MongoDB · Elasticsearch
#   eureka → gateway + 서비스 7종 (빌드된 bootJar 를 ops/07 인증서로 HTTPS 기동)
#   DLT 토픽 9종(ops/02) → ops/06 스모크 테스트
#
# 사용:
#   ./ops/12-staging-rehearsal.sh            # 전체: 기동 → 스모크 → 정리
#   KEEP=1 ...                               # 스모크 후 스택을 살려 둠 (조사/개발용)
#   ACTION=down ...                          # 남겨 둔 스택 정리
#   SKIP_SMOKE=1 ...                         # 기동까지만
#
# 요구: docker, 빌드된 bootJar(./gradlew bootJar), certs/(없으면 ops/07 자동 실행)
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
RUN="${RUN_DIR:-$ROOT/.staging}"
CERTS="$ROOT/certs"
KEEP="${KEEP:-0}"
ACTION="${ACTION:-up}"
PW="${STAGING_PASSWORD:-staging-rehearsal-pw}"
JWT="${JWT_SECRET_KEY:-0123456789012345678901234567890123456789012345678901234567890123}"
IMG_PREFIX="${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX:-}"
HEAP="${SERVICE_HEAP:-320m}"

INFRA="staging-pg staging-redis staging-kafka staging-mongo staging-es"

log()  { printf '\n>>> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

stop_services() {
    # 우리가 기록한 PID 만 죽인다 — pkill 패턴 매칭은 자기 자신을 잡는 사고가 있었다.
    if [ -f "$RUN/pids" ]; then
        while read -r pid name; do
            kill "$pid" 2>/dev/null && info "stop $name (pid $pid)"
        done < "$RUN/pids"
        rm -f "$RUN/pids"
    fi
}

down() {
    log "정리"
    stop_services
    for c in $INFRA; do docker rm -f "$c" >/dev/null 2>&1 && info "rm $c"; done
    rm -rf "$RUN/pg-certs"   # 리허설용 키 사본
}

[ "$ACTION" = "down" ] && { down; rm -rf "$RUN/lock"; exit 0; }

command -v docker >/dev/null || die "docker 가 필요합니다"
mkdir -p "$RUN/logs" "$RUN/bin"

# 동시 실행 방지. 컨테이너 이름과 PID 파일이 고정이라 두 리허설이 겹치면
# 늦게 끝나는 쪽의 down() 이 먼저 뜬 쪽의 스택을 쓸어버린다 — 실제로 겪었다.
if ! mkdir "$RUN/lock" 2>/dev/null; then
    die "이미 리허설이 실행 중입니다 ($RUN/lock). 강제 정리: ACTION=down $0"
fi
trap 'rm -rf "$RUN/lock"' EXIT

# ---------------------------------------------------------------- 0. 인증서

if [ ! -f "$CERTS/certs.env" ]; then
    log "0/6  인증서가 없어 ops/07 로 발급"
    ./ops/07-issue-certificates.sh >/dev/null || die "인증서 발급 실패"
fi
set -a; . "$CERTS/certs.env"; set +a
STORE_PW="$LOCAL_KEY_STORE_PASSWORD"

# ---------------------------------------------------------------- 1. 인프라

log "1/6  인프라 컨테이너 기동"
down >/dev/null 2>&1   # 이전 잔재 정리

# PostgreSQL — 서비스 yml 이 sslmode=require 이므로 SSL 을 켠 채로 띄운다.
# 인증서는 ops/07 산출물을 그대로 쓴다(전 구간이 같은 CA 를 신뢰하는지도 함께 검증된다).
#
# ⚠️ SSL 설정은 컨테이너 명령행(-c ssl=on)이 아니라 init 스크립트에서 postgresql.conf 에 쓴다.
# 명령행 인자는 엔트리포인트가 초기화용 **임시 서버**에도 그대로 넘기는데, 인증서 복사는
# init 스크립트에서야 일어나므로 'could not load server certificate' 로 초기화 자체가 죽는다.
# 개인키는 호스트에서 600(root)이라 컨테이너의 postgres(uid 70)가 못 읽는다.
# 리허설 전용 사본을 만들어 마운트한다 — down 때 삭제되며, 어차피 개발용 로컬 CA 키다.
mkdir -p "$RUN/pg-certs"
cp "$CERTS/tls.crt" "$CERTS/tls.key" "$RUN/pg-certs/"
chmod 644 "$RUN/pg-certs/tls.crt" "$RUN/pg-certs/tls.key"

mkdir -p "$RUN/pg-init"
cat > "$RUN/pg-init/00-certs-and-dbs.sh" <<EOF
#!/bin/bash
set -e
mkdir -p /var/lib/postgresql/tls
cp /certs-src/tls.crt /certs-src/tls.key /var/lib/postgresql/tls/
chown postgres:postgres /var/lib/postgresql/tls/*
chmod 600 /var/lib/postgresql/tls/tls.key
cat >> "\$PGDATA/postgresql.conf" <<'CONF'
ssl = on
ssl_cert_file = '/var/lib/postgresql/tls/tls.crt'
ssl_key_file = '/var/lib/postgresql/tls/tls.key'
CONF
psql -v ON_ERROR_STOP=1 -U postgres <<'SQL'
CREATE ROLE product LOGIN PASSWORD '$PW';
SQL
for db in userdb productdb cartdb orderdb paydb coupondb chatdb metadb; do
    psql -v ON_ERROR_STOP=1 -U postgres -c "CREATE DATABASE \$db OWNER product"
done
EOF
chmod 755 "$RUN/pg-init/00-certs-and-dbs.sh"

docker run -d --name staging-pg -p 5435:5432 \
    -e POSTGRES_PASSWORD=staging-admin \
    -v "$RUN/pg-certs:/certs-src:ro" -v "$RUN/pg-init:/docker-entrypoint-initdb.d:ro" \
    "${IMG_PREFIX}postgres:16-alpine" >/dev/null

docker run -d --name staging-redis -p 6379:6379 \
    "${IMG_PREFIX}redis:7-alpine" redis-server --requirepass "$PW" >/dev/null

# Kafka — KRaft 단일 노드, 호스트 29092 (서비스 yml 기본값과 일치)
docker run -d --name staging-kafka -p 29092:29092 \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:29092 \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
    "${IMG_PREFIX}confluentinc/cp-kafka:7.6.1" >/dev/null

docker run -d --name staging-mongo -p 27017:27017 \
    -e MONGO_INITDB_ROOT_USERNAME=admin -e MONGO_INITDB_ROOT_PASSWORD="$PW" \
    "${IMG_PREFIX}mongo:7" >/dev/null

docker run -d --name staging-es -p 9200:9200 \
    -e discovery.type=single-node -e xpack.security.enabled=false \
    -e ES_JAVA_OPTS="-Xms512m -Xmx512m" \
    "${IMG_PREFIX}elasticsearch:8.15.5" >/dev/null

info "PostgreSQL 대기"
for i in $(seq 1 60); do
    PGPASSWORD="$PW" psql -h localhost -p 5435 -U product -d productdb -tAc "SELECT 1" >/dev/null 2>&1 && break
    sleep 2
    [ "$i" = 60 ] && { docker logs --tail 20 staging-pg; die "PostgreSQL 준비 실패"; }
done
info "Kafka 대기"
for i in $(seq 1 60); do
    docker exec staging-kafka kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1 && break
    sleep 2
    [ "$i" = 60 ] && { docker logs --tail 20 staging-kafka; die "Kafka 준비 실패"; }
done
info "Elasticsearch 대기"
for i in $(seq 1 90); do
    curl -s http://localhost:9200/_cluster/health >/dev/null 2>&1 && break
    sleep 2
    [ "$i" = 90 ] && { docker logs --tail 20 staging-es; die "ES 준비 실패"; }
done
info "인프라 5종 준비 완료"

# ---------------------------------------------------------------- 2. DLT 토픽

log "2/6  DLT 토픽 9종 (ops/02)"
# cp-kafka 는 kafka-topics(.sh 없음) — ops/02 가 기대하는 이름으로 셔닝.
# ⚠️ --user root 필수: cp-kafka 는 appuser 로 돌아 /usr/local/bin 쓰기가 조용히 거부된다.
docker exec --user root staging-kafka bash -c \
    'command -v kafka-topics.sh >/dev/null 2>&1 || { printf "#!/bin/bash\nexec kafka-topics \"\$@\"\n" > /usr/local/bin/kafka-topics.sh && chmod 755 /usr/local/bin/kafka-topics.sh; }' \
    || die "kafka-topics.sh 셔닝 생성 실패"
KAFKA_CONTAINER=staging-kafka BOOTSTRAP=localhost:29092 ./ops/02-create-dlt-topics.sh >/dev/null \
    && info "DLT 토픽 생성/확인 완료" || die "DLT 토픽 생성 실패"

# 스모크 테스트의 DLT 점검이 쓸 호스트 셔닝
cat > "$RUN/bin/kafka-topics.sh" <<'EOF'
#!/bin/sh
exec docker exec staging-kafka kafka-topics.sh "$@"
EOF
chmod +x "$RUN/bin/kafka-topics.sh"

# ---------------------------------------------------------------- 3. 서비스 기동

# 서비스 JVM 공통 옵션. JVM 기본 트러스트스토어를 우리 CA 트러스트스토어로 바꾼다 —
# 서비스 간 호출(Eureka 등록, Feign)이 서로의 인증서를 신뢰하게 하는 가장 단순한 방법.
# 비밀번호는 JAVA_TOOL_OPTIONS 에 넣지 않는다 — JVM 이 기동마다 그 변수를 통째로 stderr 에 찍는다. 앱이 JAVAX_NET_SSL_TRUST_STORE_PASSWORD 를 옮긴다(B-83).
JTO="-Xms64m -Xmx$HEAP -Djavax.net.ssl.trustStore=$CERTS/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12"

boot() {
    local module="$1" name="$1"; shift
    local jar
    jar=$(ls "$ROOT/$module"/build/libs/*.jar 2>/dev/null | grep -v plain | head -1)
    [ -n "$jar" ] || die "$module bootJar 이 없습니다 — ./gradlew bootJar 먼저"
    (
        export JAVA_TOOL_OPTIONS="$JTO" JAVAX_NET_SSL_TRUST_STORE_PASSWORD="$STORE_PW"
        export LOCAL_DB_PASSWORD="$PW" JWT_SECRET_KEY="$JWT"
        export EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="https://localhost:8761/eureka"
        # 인증서 SAN 이 커버하는 이름으로 등록해야 한다. yml 기본(prefer-ip-address: true)대로면
        # 호스트 IP 로 등록되고, 게이트웨이의 TLS 검증이 'No subject alternative names matching
        # IP address ...' 로 정당하게 거부한다 — insecureTrustManager 를 끈 대가이자 목적이다.
        # 단일 호스트 리허설에서는 localhost 가 정확한 이름이다 (k8s 에서는 서비스 DNS).
        export EUREKA_INSTANCE_PREFER_IP_ADDRESS=false
        export EUREKA_INSTANCE_HOSTNAME=localhost
        export KAFKA_URL="localhost:29092" KAFKA_SECURITY_PROTOCOL="PLAINTEXT"
        export SPRING_DATA_MONGODB_CLIENT="mongodb://admin:$PW@localhost:27017/?ssl=false&authSource=admin"
        export SPRING_ELASTICSEARCH_URIS="http://localhost:9200"
        export LOCAL_ELASTICSEARCH_USERNAME=elastic LOCAL_ELASTICSEARCH_PASSWORD=unused
        export AMAZON_ACCESS_KEY=dummy AMAZON_SECRET_KEY=dummy AMAZON_BUCKET_NAME=dummy AMAZON_REGION=ap-northeast-2
        export MAIL_HOST=localhost MAIL_PORT=25 MAIL_USERNAME=dummy MAIL_PASSWORD=dummy
        # 더미 SMTP 를 헬스 인디케이터가 핑하면 종합 health 가 DOWN 이 된다 — 리허설에선 끈다
        export MANAGEMENT_HEALTH_MAIL_ENABLED=false
        export APP_PUBLIC_URL="https://localhost:8000"
        nohup java -jar "$jar" --spring.profiles.active=local \
            > "$RUN/logs/$name.log" 2>&1 &
        echo "$! $name" >> "$RUN/pids"
    )
    info "boot $name"
}

wait_ready() {
    local name="$1" port="$2" path="${3:-/actuator/health/readiness}" limit="${4:-90}"
    for i in $(seq 1 "$limit"); do
        local code
        code=$(curl -sk --max-time 3 -o /dev/null -w '%{http_code}' "https://localhost:$port$path" 2>/dev/null)
        [ "$code" = "200" ] && { info "ready $name (:$port, ${i}s)"; return 0; }
        sleep 3
    done
    printf '    ❌ %s 준비 실패 — 로그 마지막:\n' "$name"
    tail -5 "$RUN/logs/$name.log" | sed 's/^/       /'
    return 1
}

log "3/6  eureka → 서비스 7종 → gateway"
rm -f "$RUN/pids"
failed=0

boot eureka-server
wait_ready eureka-server 8761 /actuator/health 60 || die "eureka 없이는 진행 불가"

for m in user-service catalog-service cart-service order-service pay-service coupon-service chat-service; do
    boot "$m"
done
boot gateway-server

declare -A PORTS=( [user-service]=8080 [catalog-service]=8081 [cart-service]=8082
                   [order-service]=8084 [chat-service]=8086 [pay-service]=8087
                   [coupon-service]=8183 [gateway-server]=8000 )
for m in "${!PORTS[@]}"; do
    wait_ready "$m" "${PORTS[$m]}" || failed=$((failed+1))
done
[ "$failed" -gt 0 ] && { [ "$KEEP" = "1" ] || down; die "${failed}개 서비스가 뜨지 못했습니다 — $RUN/logs/ 확인"; }

# ---------------------------------------------------------------- 4. 등록 전파 대기

log "4/6  Eureka 등록 전파 대기 (게이트웨이 lb:// 라우팅의 전제)"
for i in $(seq 1 40); do
    n=$(curl -sk -H 'Accept: application/json' https://localhost:8761/eureka/apps 2>/dev/null | grep -o '"app"' | wc -l)
    [ "$n" -ge 8 ] && { info "등록 인스턴스 ${n}개"; break; }
    sleep 3
done
# ⚠️ eureka 서버에 등록이 보이는 것과 **게이트웨이가 라우팅할 수 있는 것**은 다르다.
# 게이트웨이는 자기 로컬 레지스트리 캐시(기본 30초 주기)로 라우팅하므로, 등록 직후에는
# 'Unable to find instance' 503 이 난다 — 리허설 7라운드에서 실측. 실제 라우팅이 뚫릴 때까지 기다린다.
for i in $(seq 1 40); do
    code=$(curl -sk --max-time 5 -o /dev/null -w '%{http_code}' \
        "https://localhost:8000/catalog-service/api/category/categories" 2>/dev/null)
    [ "$code" = "200" ] && { info "게이트웨이 라우팅 확인 (${i}회차, catalog 200)"; break; }
    sleep 3
    [ "$i" = 40 ] && info "⚠️ 게이트웨이 라우팅이 아직 $code — 스모크에서 실패로 드러날 것"
done

# ---------------------------------------------------------------- 5. 스모크

if [ "${SKIP_SMOKE:-0}" = "1" ]; then
    log "5/6  SKIP_SMOKE=1 — 스모크 생략"
else
    log "5/6  ops/06 스모크 테스트"
    PATH="$RUN/bin:$PATH" INSECURE=1 SKIP_SAGA=1 \
        GATEWAY=https://localhost:8000 EUREKA=https://localhost:8761 \
        KAFKA_BOOTSTRAP=localhost:29092 \
        ./ops/06-smoke-test.sh
    smoke=$?
fi

# ---------------------------------------------------------------- 6. 정리

log "6/6  마무리"
if [ "$KEEP" = "1" ]; then
    info "KEEP=1 — 스택 유지. 정리: ACTION=down $0"
    info "로그: $RUN/logs/  ·  게이트웨이: https://localhost:8000"
else
    down
fi
exit "${smoke:-0}"
