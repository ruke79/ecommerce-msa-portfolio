# 66. gRPC 시범 적용 — 재고 예약(order → catalog)

[日本語](66-grpc-stock-reservation.md) | **한국어**

> 작성일: 2026-09-28 · 상태: ✅ 적용(배포 대기 · 전송 기본값 http)
>
> 관련: [59 결제 전 재고 예약](59-stock-reservation.ko.md) · [57 §16 서비스 간 mTLS](57-remaining-priorities.ko.md) ·
> [63 OpenTelemetry](63-opentelemetry.ko.md) · [55 무중단 전환](55-zero-downtime-migrations.ko.md)

## 0. 요약

| | 전 | 후 |
|---|---|---|
| 재고 예약 호출 | Feign(HTTP/1.1 · JSON) `/api/internal/stock/reservations` | 그대로 두고 **gRPC(HTTP/2 · protobuf)** 를 옆에 추가 |
| 고르는 법 | — | ConfigMap `STOCK_RPC_TRANSPORT` = `http`(기본) · `grpc` |
| 계약 | Java DTO(암묵적) | `common/src/main/proto/stock_reservation.proto` — 스키마가 진실 |
| 호출자 판정 | HTTP 필터(mTLS CN + 경로 규칙) | gRPC 인터셉터가 **같은 판정 객체 · 같은 규칙**을 쓴다 |
| 시간 제한 | 클라이언트 읽기 제한 5초(서버는 모른다) | 호출마다 **기한 3초 — 서버로 전파**(서버의 작업이 취소된다) |
| 추적 | Feign → Tomcat | gRPC 클라이언트 → 서버 span 이 부모-자식으로 이어진다 |
| 가드 | B-67(Feign 경로 규칙) | **B-73** — proto 서비스 ↔ 경로 규칙, 서버 포트 ↔ 매니페스트 ↔ 호출 대상, 스위치 값 |

**성능 이득은 기대하지 않았다.** 노드 하나 · 서비스별 1개 · 적은 트래픽이고, 지연의 대부분은 DB(비관적 잠금)다.
이번 시범의 가치는 ① proto 로 계약을 고정하고 ② 기한을 전파하고 ③ 운영 가능한 수준(mTLS · 판정 · 추적 · 가드 · 되돌리기)으로 한 번 제대로 해 보는 것이다.

## 1. 왜 이 호출인가

서비스 간 동기 호출(Feign 12개) 가운데 이것을 고른 이유:

- **내부 전용** — 브라우저 · 게이트웨이에 드러나지 않는다. 바꿔도 화면에 영향이 없다.
- **명령 두 개** — `Reserve` · `Release`. 계약이 작다.
- **gRPC 의 장점이 실제로 쓰이는 자리** — 결제 화면 앞의 호출이라 기한이 의미 있고, "모자람" 과 "실패" 를 구분해야 한다.
- **핵심 경로라 측정하기 좋다** — 추적(docs/63)에서 전후를 바로 비교할 수 있다.

고르지 않은 것: 브라우저 쪽(gRPC-Web 프록시가 한 겹 더 는다), 결제사 웹훅(상대가 HTTP), 결제 사가 · 재고 차감 이벤트(일부러 비동기 — Kafka 를 동기 호출로 바꾸면 장애가 번진다), 채팅(WebSocket 이 맞다).

## 2. 구조

```
order-service                                     catalog-service
┌─────────────────────────────┐                   ┌──────────────────────────────────────────┐
│ OrderStockHold              │                   │ :8081 Tomcat  InternalCallerFilter ──┐    │
│   └ StockReservationPort    │                   │                 └ InternalStockController │
│       ├ Feign   (http) ─────┼── HTTPS :8081 ──▶ │                        │               │    │
│       └ Grpc    (grpc) ─────┼── h2 mTLS :9095 ▶ │ :9095 Netty                            │    │
│           기한 3s · 추적     │                   │   Observation → InternalCallerGrpcInterceptor
└─────────────────────────────┘                   │     └ StockReservationGrpcService        │    │
                                                  │              ▼                ▼          │    │
                                                  │        StockReservationService.reserve() ◀┘    │
                                                  └──────────────────────────────────────────┘
```

두 입구가 **같은 서비스 메서드**에 닿는다 — 잠금 순서 · 전부 아니면 전무 · 연장 규칙은 하나다. 다른 것은 전송과 오류의 모양뿐이다.

| 코드 | 역할 |
|---|---|
| `common/src/main/proto/stock_reservation.proto` | 계약 |
| `common/.../grpc/StockReservationMapping` | DTO ↔ proto 변환(서버 · 클라이언트 공용) |
| `common/.../grpc/GrpcTls` | 신원 키스토어 + 내부 CA 로 SslContext |
| `common/.../security/InternalCallerGrpcInterceptor` | 호출자 판정(HTTP 필터의 판정을 재사용) |
| `catalog/.../grpc/StockGrpcServerConfig` · `StockReservationGrpcService` | 서버 조립 · 입구 |
| `order/.../stock/StockReservationPort` · `FeignStockReservationPort` · `GrpcStockReservationPort` · `StockReservationTransportConfig` | 전송 추상화 · 스위치 |

## 3. 계약 — proto 규칙

- **필드 번호는 바꾸지도, 다시 쓰지도 않는다.** 지운 필드는 `reserved` 로 남긴다. 새 필드는 새 번호로만 — 옛 클라이언트는 모르는 필드를 무시한다(Avro 의 스키마 진화와 같은 원칙, docs/38 §1.16).
- **"모자라다" 는 오류가 아니라 결과다** — `held=false` + `shortages`. 상태 코드는 호출 자체의 실패에만 쓴다. HTTP 도 모자라면 200 이다. 이 규칙이 두 전송의 **동등성**을 만든다.
- **proto3 에는 null 이 없다.** 기본값 해석을 `StockReservationMapping` 한 곳에서 정한다: id 0 → null(실제 id 는 1 이상), 사이즈 빈 문자열은 그대로(사이즈 없는 상품의 이름이자 예약 키), 만료 0 → 없음.
- 생성물은 커밋하지 않는다(`build/generated`). protoc 은 Gradle 플러그인이 플랫폼별로 받는다(x86 CI · aarch64 운영 빌드).

| 상황 | gRPC | (HTTP 에서는) |
|---|---|---|
| 모자람 | OK + `held=false` | 200 + `held=false` |
| 잘못된 요청 | `INVALID_ARGUMENT` | 400 |
| 인증서 없음(enforce) | `UNAUTHENTICATED` | 403(ANONYMOUS 로 바뀌어 거절) |
| 경로 규칙 위반(rules enforce) | `PERMISSION_DENIED` | 403 |
| 그 밖의 서버 실패 | `INTERNAL`(메시지 없음) | 500(메시지 없음) |
| 기한 초과 · 연결 실패 | `DEADLINE_EXCEEDED` · `UNAVAILABLE` | 읽기 제한 · 연결 오류 |

order 는 예외(상태 코드 무엇이든)를 HTTP 실패와 똑같이 **"catalog 에 닿지 못함" → 예약 없이 진행**으로 처리한다(결제 뒤 부족분 환불이 받는다 — docs/59).

## 4. 보안 — mTLS 와 호출자 판정

**gRPC 포트는 서블릿 필터 밖이다.** `InternalCallerFilter` 는 Tomcat 앞에서만 돈다. 인터셉터가 없으면 클러스터 안의 아무 파드나 9095 로 재고를 잡을 수 있다 — HTTP 가 막아 둔 것을 옆문이 연다.

- **판정은 복제하지 않았다.** 인터셉터는 HTTP 필터 **빈의 객체**를 받아 `callerOf(chain)` · `policyViolation(caller, path)` 를 부른다. 모드 · 내부 CA · 허용 주체 · 경로 규칙이 한 벌이다.
- **경로 규칙이 그대로 맞는다.** gRPC 메서드의 HTTP/2 경로는 `/ecommerce.stock.v1.StockReservation/Reserve` 라, ConfigMap 의 `INTERNAL_AUTH_PATH_RULES` 에 `/ecommerce.stock.v1.StockReservation/=order-service` 한 조각을 더하면 된다.
- **HTTP 와 다른 점** — 신원 헤더(User-*)를 보지 않는다(gRPC 포트는 게이트웨이 뒤에 없다). 막을 때 바로 끊는다(뒤에서 다시 판정할 인가 계층이 없다).
- **TLS** — 서비스 신원 인증서(k8s/96, CN=서비스, dnsNames 에 `catalog-service.ecommerce.svc.cluster.local`, 서버 · 클라이언트 겸용)를 그대로 쓴다. 신뢰는 **내부 CA 하나만**(JVM 기본 신뢰 저장소의 공개 CA 를 쓰지 않는다).
- **키는 PKCS#12 에서 읽는다.** cert-manager 의 `tls.key` 는 RSA 면 PKCS#1 인데 Netty 의 PEM 읽기는 PKCS#8 만 받는다(BouncyCastle 필요). 같은 Secret 의 `keystore.p12` 는 JVM 이 이미 읽는 파일이다(`JAVA_TOOL_OPTIONS` 의 `javax.net.ssl.keyStore`) — 새 환경 변수가 필요 없었다.
- 서버는 클라이언트 인증서를 **요청하되 강제하지 않는다**(OPTIONAL). TLS 층에서 끊으면 observe 단계에서 "누가 인증서 없이 부르나" 를 볼 수 없다. 판정은 인터셉터가 모드에 따라 한다.
- **enforce 인데 TLS 가 없으면 서버를 띄우지 않는다**(ERROR 로그). catalog 전체를 죽일 일은 아니다 — HTTP 입구는 그대로 돈다.
- NetworkPolicy 는 바꾸지 않았다 — 같은 네임스페이스 안은 이미 허용(`allow-same-namespace`)이고, 9095 는 Ingress · 게이트웨이로 내보내지 않는다.

## 5. 기한(deadline)

HTTP 의 읽기 제한은 **클라이언트만** 포기한다 — 서버는 끝까지 일하고 결과를 버린다. gRPC 의 기한은 요청에 실려 **서버로 전파된다**. catalog 는 기한이 지나면 `Context` 가 취소되어 그 호출을 더 붙잡지 않는다.

- 기본 3초(`STOCK_GRPC_DEADLINE_MS`) — Feign 읽기 제한(5초)보다 짧게. 결제 화면 앞에서 오래 붙잡지 않는다.
- 테스트(`GrpcStockReservationPortTest.deadlinePropagatesAndOrderProceeds`)가 "order 는 UNAVAILABLE 로 진행 + catalog 쪽 Context 취소" 를 함께 확인한다.

## 6. 전환 절차 (운영)

```bash
# 0. 새 이미지(catalog · order)를 배포한다 — 전송은 아직 http. catalog 에 gRPC 서버가 뜬다.
kubectl -n ecommerce logs deploy/catalog-service | grep "재고 예약 gRPC 서버"
#    기대: 재고 예약 gRPC 서버: port=9095 tls=true mode=enforce
#    "띄우지 않는다" 가 보이면 키스토어 · CA 경로를 먼저 고친다(전환하지 않는다)

# 1. 전환 — k8s/10-configmap.yaml 의 STOCK_RPC_TRANSPORT 를 "grpc" 로 바꾸고
./ops/28-deploy.sh <env> --config-only --restart order-service
kubectl -n ecommerce logs deploy/order-service | grep "재고 예약 전송"      # 기대: 재고 예약 전송: grpc (기한 3000ms)

# 2. 확인 — 주문 하나를 만들어 본다
kubectl -n ecommerce logs deploy/catalog-service | grep "\[mTLS caller\].*StockReservation"   # caller=order-service
kubectl -n ecommerce logs deploy/order-service | grep "재고 예약(gRPC) 실패"                   # 없어야 한다
#    Jaeger: 서비스 order-service → span "ecommerce.stock.v1.StockReservation/Reserve" 아래 catalog 의 같은 이름 span
#    Prometheus: grpc_server_seconds_count (catalog) — 레이블에 서비스 · 메서드 · 상태 코드

# 3. 되돌리기 — 같은 방법으로 "http" 로. 진행 중인 예약의 의미는 바뀌지 않는다(같은 서비스 메서드).
```

실패 로그의 상태 코드가 원인을 가른다: `UNAUTHENTICATED` · `PERMISSION_DENIED` → 인증서 · 경로 규칙, `UNAVAILABLE` → 연결(포트 · Service · DNS), `DEADLINE_EXCEEDED` → catalog 가 느림(DB 잠금 대기 등).

## 7. 관측

- **추적** — Micrometer Observation 인터셉터를 서버에서는 **가장 바깥**에 둔다(거절된 호출도 추적에 남는다). OTel 브리지가 `traceparent` 를 gRPC 메타데이터로 넘기고 받는다 → order 의 CLIENT span 과 catalog 의 SERVER span 이 한 추적의 부모-자식이다(테스트로 고정). span 이름은 gRPC 메서드 이름이다.
- **지표** — `grpc.server` · `grpc.client` 타이머(상태 코드 태그 포함)가 actuator 를 거쳐 Prometheus 로 간다. 꼬리 샘플링 · span_metrics(docs/63)는 그대로 적용된다.
- **로그** — 판정은 HTTP 와 같은 문자열(`[mTLS caller]` · `[mTLS rule]` · `[mTLS observe]`)에 `gRPC /…` 경로가 붙는다. 실패는 order 쪽 `재고 예약(gRPC) 실패 — status=…`.

## 8. 가드 B-73

gRPC 는 네 끝이 맞아야 붙는다. 하나라도 어긋나면 **테스트는 모두 초록인 채로** 운영에서만 끊긴다(단위 테스트는 in-process · 임의 포트로 돈다).

1. proto 의 서비스마다 `INTERNAL_AUTH_PATH_RULES` 에 `/패키지.서비스/` 규칙
2. catalog 의 gRPC 포트 기본값 = Deployment containerPort = Service 포트(이름 `grpc`)
3. `STOCK_GRPC_TARGET` = `dns:///catalog-service.ecommerce.svc.cluster.local:<그 포트>` — 서버 인증서의 dnsNames 와도 맞는 이름
4. `STOCK_RPC_TRANSPORT` ∈ {http, grpc} — 그 밖이면 order 가 뜨지 않는다(오타가 조용히 http 가 되지 않게 — 코드도 기동을 멈춘다)

규칙을 빼고 스위치를 오타로 바꿔 두 위반이 모두 잡히는 것을 확인했다.

## 9. 검증

| 테스트 | 무엇을 |
|---|---|
| `InternalCallerGrpcInterceptorTest`(common, 7) | **실제 Netty · TLS** 위 판정 — 허용 서비스 통과 · 다른 서비스 PERMISSION_DENIED · 인증서 없음 UNAUTHENTICATED · 다른 CA 거절 · observe · rules-observe · off |
| `StockReservationGrpcServerTest`(catalog, 6) | 운영 조립 그대로 — 같은 서비스 메서드 · 모자람은 결과 · 오류 매핑(내부 메시지 없음) · 경로 규칙 · **추적 부모-자식** · TLS 없으면 미기동 |
| `GrpcStockReservationPortTest`(order, 5) | **HTTP ↔ gRPC 동등성**(잡힘 · 모자람) · **기한 전파** · 실패 상태 코드 · 스위치 |
| 컨테이너 기동(catalog · order) | 실제 앱에서 서버가 뜨고(테스트는 포트 0), order 는 기본 http |
| B-73 | §8 |

인증서는 JDK `keytool` 로 만든다(`TestPki` — common testFixtures): 내부 CA, 그 CA 가 서명한 서비스 신원(PKCS#12, SAN=localhost), 다른 CA 의 가짜.

## 10. 한계와 다음

- **로드 밸런싱.** gRPC 는 HTTP/2 연결 하나를 오래 쓴다. ClusterIP Service 뒤에서는 연결이 **처음 붙은 파드에 고정**되어, catalog 를 여러 개로 늘려도 부하가 나뉘지 않는다. 지금은 1개라 문제가 없다. 늘릴 때는 headless Service + 클라이언트 `round_robin` 정책(또는 메시 · L7 프록시)이 필요하다.
- **프로브.** k8s 프로브는 8081(actuator)만 본다. gRPC 서버가 안 뜬 경우(§4 enforce + TLS 없음)는 로그로만 드러난다 — gRPC health 서비스(`grpc.health.v1`)나 actuator 지표를 붙일 수 있다.
- **인증서 갱신.** 서버 SslContext 는 기동 때 한 번 만든다. cert-manager 가 인증서를 갱신하면 Reloader 가 파드를 재시작하므로(k8s/41 주석) 지금은 맞다 — 재시작 없이 바꾸려면 키 매니저를 파일 감시로 바꿔야 한다.
- **측정은 아직.** 전환 뒤 Jaeger · Prometheus 로 p50/p95 를 HTTP 와 비교해 여기에 적는다.
- **다음 후보** — order → coupon 견적(`/api/coupon/internal/quote`): 금액이 걸린 내부 호출, 계약이 작다. 이번 부품(판정 · TLS · 변환 패턴 · 가드)을 그대로 쓸 수 있다.
