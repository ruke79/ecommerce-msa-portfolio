# k3s 배포 매니페스트

[日本語](README.md) | **한국어**


> ## ⚠️ 배포는 오버레이로
>
> `k8s/kustomization.yaml` 이 생기면서 **`kubectl apply -f k8s/` 는 쓸 수 없습니다**
> (kubectl 이 kustomization.yaml 을 리소스로 읽으려다 실패합니다). 대신:
>
> ```bash
> kubectl apply -f k8s/90-dev-infra.yaml     # dev 인프라 (dev 만)
> kubectl apply -k k8s-overlays/dev          # 개발 — 레플리카 1, 요청 축소
>
> kubectl apply -k k8s-overlays/prod         # 운영 — HPA 소유, Cloudflare Tunnel
> kubectl apply -f k8s/70-autoscaling.yaml   # HPA·PDB (노드가 여럿일 때만)
> kubectl apply -f k8s/85-backup-cronjobs.yaml
>
> kubectl apply -k k8s-overlays/external     # 운영 + 관리형 PG/Redis/Mongo (docs/32)
> ```
>
> 🔴 **위 `apply -k` 는 최초 배포에서만 씁니다.** 오버레이의 `images:` 에는 손으로 적어 둔
> `newTag` 가 박혀 있어서, 배포한 뒤 그 파일을 다시 apply 하면 전 서비스가 **그 옛 태그로
> 되돌아갑니다.** 두 번째부터는 `./ops/28-deploy.sh <오버레이> <버전>` 을 쓰고, 설정만
> 바꿀 때는 `./ops/28-deploy.sh <오버레이> --config-only [--restart <deploy>]` 를 씁니다.
>
> 아래 문서의 **개별 파일 적용은 그대로 동작합니다.** 환경별 차이(레플리카·리소스·이미지)를
> 오버레이가 담당할 뿐입니다. 자세한 내용은 [docs/31](../docs/31-kustomize-oci-tunnel.ko.md), 인프라를 클러스터 밖으로 빼는 `external` 오버레이는 [docs/32](../docs/32-external-managed-infra.ko.md).

이 디렉터리는 `ecommerce-msa`를 k3s(경량 Kubernetes)에 배포하기 위한 **순수 Kubernetes YAML 매니페스트**입니다.
k3s는 Kubernetes 배포판이라 `docker-compose.yml`을 직접 실행하지 못하므로, 기존 루트의
`docker-compose-core.yml` / `docker-compose-services-1.yml` / `docker-compose-services-2.yml` 이
정의하던 것과 동등한 배포를 Deployment/Service/ConfigMap/Secret/Ingress로 다시 표현했습니다.

## 범위 (참고: [`docs/`](../docs/README.ko.md))

**포함**: eureka-server, gateway-server, 7개 백엔드 서비스(user/catalog/cart/order/pay/coupon/chat), frontend.

**제외(전제)**: Kafka, PostgreSQL, Redis, MongoDB, Elasticsearch. 이미 운영 중이거나 클러스터에
별도로 구축한 **외부/기존 인프라**로 가정하고, 접속 정보만 ConfigMap/Secret으로 주입합니다.
(운영 환경에서 상태 저장소는 보통 별도의 Operator/StatefulSet이나 관리형 서비스로 다루는 것이 맞고,
이 매니페스트 세트의 관심사와 분리하는 편이 유지보수에 유리하다고 판단했습니다.)

**config-server는 `32-config-server.yaml` 로 함께 배포하지만, 설정 저장소(Git)는 연결하지 않았습니다** — 연결하려면
인증을 먼저 붙여야 합니다. 모든 서비스가 `spring.config.import`에 `optional:` 접두어를 쓰고 있어 config-server 가 없어도 정상 기동합니다.

## 왜 애플리케이션 코드를 안 건드리고 배포가 가능한가

각 서비스는 `application-local.yml` 값 대부분을 `${ENV_VAR}` 플레이스홀더로 두거나, Spring Boot의
relaxed binding(OS 환경변수가 yml 값보다 항상 우선) 덕분에 **OS 환경변수만으로 재정의 가능**합니다.
이 매니페스트는 그 사실을 이용해 이미지/코드는 그대로 두고 `ConfigMap` + `Secret` + Deployment의
`env`만으로 배포 환경을 구성합니다. (기존 docker-compose의 `environment:` 블록과 정확히 같은 메커니즘)

## 사전 준비물

1. **k3s 클러스터**와 `kubectl` 접근 권한.
2. **컨테이너 레지스트리** (Docker Hub, GHCR, 사설 레지스트리 등) — 이미지를 푸시할 곳.
3. **외부 인프라**가 이미 떠 있고 클러스터에서 네트워크로 도달 가능해야 합니다:
   - PostgreSQL 15+ — 아래 8개 데이터베이스가 필요합니다(단일 인스턴스, 계정 `product` 공용 가정):
     `userdb`, `cartdb`, `productdb`, `metadb`, `coupondb`, `orderdb`, `paydb`, `chatdb`
     (참고: `chat-service`의 `application-local.yml`은 원래 `paydb`를 가리키고 hibernate dialect도
     MySQL로 되어 있는 기존 버그가 있어, `46-chat-service.yaml`에서 `chatdb` + PostgreSQL 방언으로
     환경변수로 바로잡았습니다.)
   - Apache Kafka (SSL)
   - Redis
   - MongoDB (chat-service)
   - Elasticsearch (catalog-service) — 첫 배포에서는 아래 "알려진 이슈"에 따라 http로 접속합니다.
4. **mTLS 키스토어(PKCS12)** — 이 프로젝트는 서비스 간 mTLS를 사용합니다. 기존에 로컬/도커용으로
   만들어 둔 `keystore.p12`, `truststore.p12` (및 게이트웨이용 별도 트러스트스토어), Kafka용
   `kafka-truststore.jks` / `kafka-keystore.p12` 파일이 있어야 합니다.

## 1. 이미지 빌드 & 푸시

기존 각 서비스의 `Dockerfile`을 그대로 사용합니다(레포 루트에서 빌드 컨텍스트를 잡아야 `common` 모듈과
루트 Gradle 파일을 함께 COPY할 수 있습니다):

```bash
REGISTRY=<your-registry>/ecommerce-msa
for svc in eureka-server gateway-server user-service catalog-service cart-service \
           order-service pay-service coupon-service chat-service; do
  docker build \
    --build-arg SERVICE_DIR=$svc \
    --build-arg SPRING_PROFILES_ACTIVE=local \
    -t $REGISTRY/$svc:1.0.0 \
    -f $svc/Dockerfile .
  docker push $REGISTRY/$svc:1.0.0
done

# frontend는 이번에 새로 추가한 Dockerfile을 사용하며, 빌드 컨텍스트가 frontend/ 자체입니다.
# REACT_APP_API_URL/REACT_APP_PAYPAL_CLIENT_ID는 런타임이 아니라 빌드 시점에 번들에 박히므로
# 여기서 실제 값을 넣어야 합니다 (frontend/Dockerfile 상단 주석 참고).
docker build \
  --build-arg REACT_APP_API_URL=https://<gateway-server 외부 주소>:8000 \
  --build-arg REACT_APP_PAYPAL_CLIENT_ID=<paypal client id> \
  -t $REGISTRY/frontend:1.0.0 -f frontend/Dockerfile frontend
docker push $REGISTRY/frontend:1.0.0
```

그런 다음 이 디렉터리의 각 YAML에서 `REGISTRY_PLACEHOLDER/ecommerce-msa/<service>:1.0.0` 문자열을
실제 레지스트리 경로로 치환하세요:

```bash
find k8s -name '*.yaml' -exec sed -i "s#REGISTRY_PLACEHOLDER/ecommerce-msa#$REGISTRY#g" {} +
```

## 2. 설정 값 채우기

```bash
cp k8s/20-secret.example.yaml k8s/20-secret.yaml
# k8s/20-secret.yaml 을 열어 REPLACE_ME 값을 전부 채우세요. (.gitignore에 이미 추가되어 있습니다)
```

`k8s/10-configmap.yaml`도 여러분의 실제 인프라 호스트명(Kafka broker, Redis, MongoDB,
PostgreSQL, Elasticsearch)에 맞게 수정하세요.

mTLS 키스토어는 파일 기반 Secret으로 별도 생성합니다(먼저 네임스페이스가 있어야 합니다):

```bash
kubectl apply -f k8s/00-namespace.yaml

kubectl create secret generic tls-keystore -n ecommerce \
  --from-file=keystore.p12=/path/to/keystore.p12 \
  --from-file=truststore.p12=/path/to/truststore.p12 \
  --from-file=kafka-truststore.jks=/path/to/kafka-truststore.jks \
  --from-file=kafka-keystore.p12=/path/to/kafka-keystore.p12
```

그 파일들이 없다면 `ops/07-issue-certificates.sh` 가 만들어 줍니다([docs/18](../docs/18-certificates.ko.md)).
Secret YAML 까지 바로 뽑아 주므로 위 명령을 직접 칠 필요는 없습니다:

```bash
./ops/07-issue-certificates.sh                          # 로컬 CA (개발/사내)
# 또는  MODE=letsencrypt LE_DOMAIN=api.example.com LE_EMAIL=ops@example.com ./ops/07-...

kubectl apply -f certs/k8s-tls-keystore.secret.yaml     # tls-keystore Secret

# 스토어 비밀번호도 ecommerce-secrets 에 반영 (값은 certs/certs.env 안에 있습니다)
kubectl -n ecommerce patch secret ecommerce-secrets --type merge -p \
  '{"stringData":{"LOCAL_KEY_STORE_PASSWORD":"...","LOCAL_KEY_PASSWORD":"...","KAFKA_SSL_KEY_STORE_PASSWORD":"...","KAFKA_SSL_TRUST_STORE_PASSWORD":"..."}}'
```

## 3. 배포

> ### ⛔ `kubectl apply -f k8s/` 는 쓰지 마세요
>
> 파일명이 정렬되니 한 번에 적용해도 된다고 **예전에 이 문서가 권했습니다.** 지금은
> 그러면 안 됩니다. 이 디렉터리에는 오버레이가 일부러 빼 놓은 파일들이 같이 있어서,
> 디렉터리 통째 적용은 그것들까지 클러스터에 밀어 넣습니다:
>
> | 파일 | 디렉터리 적용의 결과 |
> |---|---|
> | `70-autoscaling.yaml` | HPA(minReplicas=2)가 되살아나 오버레이의 `replicas: 1` 을 15초 만에 되돌린다 |
> | `90-dev-infra.yaml` | Postgres·Kafka·Redis·Mongo·Elasticsearch 가 **클러스터 안에** 뜬다. 관리형을 쓰는 배포에서는 순수한 낭비이고, 단일 노드에서는 그것만으로 노드가 마른다 |
> | `85-backup-cronjobs.yaml` | 백업 CronJob 과 PVC 가 붙는다 |
> | 나머지 base 매니페스트 | 이미지가 **오버레이 이전 값**(`REGISTRY_PLACEHOLDER/...:1.0.0`)으로 돌아간다 — 방금 한 배포가 통째로 무효가 된다 |
>
> 게다가 `k8s/kustomization.yaml` 을 리소스로 읽으려다 이런 오류를 냅니다 —
> 그런데 **거기서 멈추지 않고 나머지를 전부 적용합니다.**
>
> ```
> error: resource mapping not found ... no matches for kind "Kustomization"
> ```
>
> 실제로 이 저장소에서 배포가 다섯 번 연속 실패한 원인이 이것이었습니다(docs/39 §7.3).
> **오버레이로 배포하세요**: `./ops/28-deploy.sh <환경> <버전>`.
>
> 아래 목록은 오버레이 없이 낱개로 올릴 때의 순서입니다.

```bash
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/10-configmap.yaml
kubectl apply -f k8s/20-secret.yaml
kubectl apply -f k8s/30-eureka-server.yaml
kubectl apply -f k8s/31-gateway-server.yaml
kubectl apply -f k8s/40-user-service.yaml
kubectl apply -f k8s/41-catalog-service.yaml
kubectl apply -f k8s/42-cart-service.yaml
kubectl apply -f k8s/43-order-service.yaml
kubectl apply -f k8s/44-pay-service.yaml
kubectl apply -f k8s/45-coupon-service.yaml
kubectl apply -f k8s/46-chat-service.yaml
kubectl apply -f k8s/50-frontend.yaml
kubectl apply -f k8s/60-ingress.yaml
kubectl apply -f k8s/70-autoscaling.yaml   # HPA + PDB — ⚠️ **노드가 여럿일 때만**
#   minReplicas 가 전부 2다. 단일 노드에서는 오버레이가 정한 replicas=1 을 15초 만에
#   되돌리고, 그 경합이 startupProbe 예산을 넘겨 롤아웃을 실패시킨다(docs/39 §7.3).
```

⛔ `kubectl apply -f k8s/` 로 한 번에 적용하지 마세요 — 이유는 위 상자를 보세요.


## 4. 확인

```bash
kubectl get pods -n ecommerce -w
kubectl logs -n ecommerce deploy/gateway-server
kubectl -n ecommerce port-forward svc/gateway-server 8000:8000   # 게이트웨이는 ClusterIP — 바깥에서는 터널로만 닿는다
```

## 게이트웨이 노출 방식

`gateway-server`는 자체적으로 HTTPS(mTLS)를 종료하고, Service 는 `ClusterIP` 입니다 — 바깥 트래픽은
Cloudflare 터널(`k8s-overlays/prod/cloudflared.yaml`)이 `https://gateway-server.ecommerce.svc.cluster.local:8000` 으로
보냅니다. 예전의 `type: LoadBalancer` 는 ServiceLB 를 꺼도(`--disable servicelb`, ops/23) nodePort 를 받아 모든 노드의
그 포트를 게이트웨이로 열었습니다 — 매니페스트에 LoadBalancer · NodePort 를 두지 않습니다(가드 B-85). 노트북에서
바로 붙을 때는 `kubectl port-forward` 를 씁니다.
표준 `Ingress`는 백엔드로 평문 HTTP 프록시가 기본 동작이라 게이트웨이의 자체 TLS 종료 방식과
맞지 않아 의도적으로 제외했습니다. 공인 도메인 + 공인 인증서를 앞단에 두고 싶다면 Traefik의
`IngressRoute` + `ServersTransport`(TLS passthrough)를 별도로 구성하세요.

## 알려진 이슈 / 의도적 단순화 (첫 배포 기준)

이번 작업으로 발견/보정한 사항들입니다. 근본 수정은 애플리케이션 코드 변경이 필요해
이 매니페스트 세트의 범위를 넘어가므로, env로 우회하거나 문서화만 했습니다.

| 이슈 | 조치 |
|---|---|
| `feign.catalog-url` / `feign.cart-url` / `feign.user-url` 이 어떤 yml에도 정의돼 있지 않음 (docker-compose도 마찬가지) → 없으면 cart/order/coupon/chat/user 기동 실패 | `10-configmap.yaml`에서 클러스터 내부 DNS로 채움 |
| catalog-service ES PEM 트러스트스토어 경로가 `"classpath: ${LOCAL_CRT_PATH}"`(콜론 뒤 공백)로 되어 있어 classpath 리소스로 해석 안 됨([`docs/06`](../docs/06-catalog-search-issues.ko.md)) | 첫 배포는 ES에 http(평문)로 접속하도록 우회(`41-catalog-service.yaml`). TLS로 붙이려면 소스의 오타를 고친 이미지가 필요 |
| chat-service datasource가 `paydb`를 가리키고 dialect도 MySQL로 되어 있음(복붙 오류로 보임) | env로 `chatdb` + PostgreSQL dialect로 덮어씀(`46-chat-service.yaml`) |
| Postgres `properties.ssl=true`(DefaultJavaSSLFactory, 별도 루트 인증서 필요) | 첫 배포는 `sslmode=require`(전송구간 암호화)만 쓰고 `SPRING_DATASOURCE(_*)_PROPERTIES_SSL=false`로 끔. 서버 인증서 검증까지 하려면 루트 인증서를 mTLS Secret에 추가하고 이 값을 제거하세요 |
| 게이트웨이 CORS 허용 오리진(`https://localhost:3000`)은 맵 키가 특수문자(`/**`)를 포함해 env var로 안정적으로 오버라이드 불가 | 실제 프론트 도메인을 쓰려면 `gateway-server/src/main/resources/application-local.yml`을 고쳐 이미지를 다시 빌드해야 합니다 |
| 게이트웨이 `useInsecureTrustManager: true` (mTLS 신뢰 체계 약화, [`docs/03` R-5](../docs/03-architecture-and-reliability.ko.md)) | 이번 매니페스트에서 그대로 유지. 운영 전 반드시 소스 수정 권장 |

## 리소스 사이징 / 프로브

- 요청/제한은 기존 `docker-compose-*.yml`에 추가했던 값(`mem_limit: 768m`, `cpus: 1.0`)과 동일한
  선을 따르되, k8s의 `requests`(스케줄링 기준)/`limits`(상한)로 나눴습니다.
- `livenessProbe`/`readinessProbe`는 앞선 백엔드 개선([`docs/02`](../docs/02-improvements.ko.md))에서
  전 서비스에 활성화한 `/actuator/health/liveness`, `/actuator/health/readiness`를 그대로 사용합니다.
  단 `eureka-server`는 actuator 의존성이 없어 TCP 소켓 프로브로 대체했습니다.
- 모든 Pod는 첫 기동이 느릴 수 있어(JVM+Hibernate ddl-auto+Eureka 등록) `startupProbe`로
  최대 ~150초의 기동 유예를 두고, 이후 liveness/readiness가 짧은 주기로 동작합니다.

## 향후 개선 방향 (미포함)

- ~~HorizontalPodAutoscaler, PodDisruptionBudget~~ → `70-autoscaling.yaml`로 추가됨.
  chat-service는 인메모리 STOMP 브로커라 스케일아웃 제외(docs/03 F-7), eureka는 peer-awareness 설정 선행 필요로 제외.
- ~~NetworkPolicy로 네임스페이스 간 트래픽 제한~~ → `95-network-policy.yaml`(들어오는 연결만). kustomization 밖 — `ops/35-network-policy.sh apply` 로 확인하며 적용(docs/57 §11).
- 인프라(Kafka/Postgres/Redis/Mongo/ES)까지 StatefulSet/Operator로 클러스터 내 편입
- 게이트웨이 앞단 TLS passthrough Ingress(공인 인증서 사용 시)
- Secret을 평문 대신 Sealed Secrets / External Secrets Operator로 관리
