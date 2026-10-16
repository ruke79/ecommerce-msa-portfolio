# 관측성 스택 · 분산 추적 · Kafka DLQ 적용

[日本語](11-observability-and-kafka-reliability.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 대시보드 · Alertmanager · 프로듀서 쪽 추적 · DLQ 확산은 이후 끝났습니다([12](12-dlq-rollout-idempotency-alerts.ko.md) · [22](22-alerting-and-backup-automation.ko.md) · [51](51-logging.ko.md)).

> 작성일: 2026-07-27 · 해소 대상: 최종 점검의 G3(관측성 수집 스택 부재) · G4(분산 추적 부재) · G7(Kafka DLQ 부재)
> 관련: [docs/03](03-architecture-and-reliability.ko.md) R-2/R-4, F-2 · [docs/10](10-quality-hardening.ko.md)

## 1. 관측성 스택 (G3)

지금까지는 `/actuator/prometheus`와 traceId를 **노출만** 하고 수집기가 없어, `docs/03`의 장애 대응 런북이 전제하는 "탐지"가 불가능했다.

**추가**: `docker-compose-observability.yml` + `observability/`
| 컴포넌트 | 포트 | 역할 |
|---|---|---|
| Prometheus v2.53 | 9090 | 8개 서비스 `/actuator/prometheus` 스크레이프(HTTPS, 15s), 15일 보존 |
| Grafana 11.1 | **3001** (프론트 3000과 충돌 방지) | Prometheus/Zipkin 데이터소스 **자동 프로비저닝** |
| Zipkin 3.4 | 9411 | 분산 추적 수집/조회 (데모: 인메모리 스토리지) |

```bash
docker compose -f docker-compose-observability.yml up -d
```

주의(정직한 한계):
- 서비스가 자가서명 TLS로 뜨므로 스크레이프는 `insecure_skip_verify` — 운영에서는 사설 CA를 `ca_file`로 신뢰시킬 것.
- Grafana **대시보드는 미포함**(데이터소스만 자동 연결). JVM(Micrometer) 공식 대시보드 import 권장(ID 4701 등).
- 경보 규칙(Alertmanager)은 다음 단계. `docs/03` 부록의 지표 체크리스트가 규칙의 출발점이다.
- k8s에서는 이 compose 대신 kube-prometheus-stack(Helm)을 권장(`k8s/README.md`).

## 2. 분산 추적 (G4)

**추가**:
- 루트 `build.gradle`(subprojects 공통): `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` → **전 서비스**(gateway 포함)에 적용. 버전은 Boot BOM 관리.
- `application-common.yml`: `management.tracing.sampling.probability: 1.0`(운영에선 낮출 것), `management.zipkin.tracing.endpoint`(기본 `localhost:9411`, `ZIPKIN_ENDPOINT`로 오버라이드 — k8s ConfigMap에서 주입 가능).
- **전 서비스 로그 패턴에 `[%X{traceId:-},%X{spanId:-}]` 삽입** — 서비스들이 커스텀 패턴으로 Boot 기본 상관관계 출력을 덮고 있었으므로 명시적으로 추가(10개 yml).
- Kafka 경로 전파: 이번에 수정한 catalog/order 컨슈머 팩토리에 `setObservationEnabled(true)` 적용.

효과: HTTP(게이트웨이→서비스, Feign)와 catalog/order Kafka 소비까지 **traceId 하나로 로그 연결 + Zipkin 타임라인 조회**. 남은 것: 나머지 서비스의 Kafka 컨슈머/프로듀서 observation 활성화(동일 한 줄 패턴), 커스텀 KafkaTemplate 프로듀서 측 전파.

## 3. Kafka DLQ — 레퍼런스 구현 (G7)

이전에 "batch listener라 위험"으로 미뤘던 항목을, **컴파일·테스트가 가능해진 지금** catalog(상품 업데이트)와 order(사가 핵심)에 실제 구현했다.

**패턴** (`KafkaDltConfig` — catalog/order 각각):
1. `DeadLetterPublishingRecoverer` — 값 타입별 템플릿 2개(정상 페이로드→JSON 재직렬화, **역직렬화 실패→원본 byte[] 보존**), 대상 `<topic>.DLT`(파티션 -1: DLT 파티션 수가 달라도 안전).
2. `DefaultErrorHandler` = recoverer + **지수 백오프 3회**(1s×2), 역직렬화/변환 예외는 **재시도 없이 즉시 DLT**.
3. 컨슈머 팩토리: 값 역직렬라이저를 **`ErrorHandlingDeserializer`로 래핑** — 이것이 없으면 poison 페이로드가 리스너에 도달하기 전 컨슈머 스레드에서 터져 **무한 재폴링**(F-2의 실제 메커니즘)된다.
4. 리스너: `forEach` → **인덱스 루프 + `BatchListenerFailedException(cause, index)`** — 성공분 오프셋은 커밋되고, 실패 레코드만 재시도/격리 후 나머지가 재전달된다. (통짜 실패 시 성공분까지 중복 재처리되는 문제 방지)

**적용 파일**:
| 서비스 | 파일 |
|---|---|
| catalog | `message/config/KafkaDltConfig.java`(신규), `KafkaConsumerConfig`, `ProductUpdateKafkaListener` |
| order | `config/KafkaDltConfig.java`(신규), `KafkaConsumerConfig`(양쪽 팩토리), `CustomerKafkaListener`, `PaymentResponseKafkaListener`(기존 NO-OP 예외 스킵은 유지) |

**테스트**: `ProductUpdateKafkaListenerTest` — 배치 중간 실패 시 "앞은 처리·실패 인덱스 보고·뒤는 미처리" 시맨틱 고정.

**운영 반영 체크리스트**:
- DLT 토픽(`product-update.DLT`, `payment.DLT`, `user-created.DLT`) 자동 생성이 꺼져 있으면 사전 생성.
- DLT 적재 모니터링(컨슈머 랙/토픽 크기) + 재처리 절차(내용 수정 후 원토픽 재발행) 런북화.
- **나머지 4개 서비스(cart/coupon/pay/user)에 동일 패턴 확산** — 이 두 구현이 복붙 가능한 템플릿이다.

## 4. 여전히 남은 것

- **G8 멱등성**: `ProductUpdateRequest` 등 이벤트 페이로드에 `eventId`가 없어 컨슈머 dedupe 불가. **이벤트 계약에 eventId 추가**(JSON 하위호환) 후 Redis SETNX/유니크 제약 dedupe — 계약 변경이라 별도 진행 필요.
- Alertmanager 경보 규칙, Grafana 대시보드 코드화, 나머지 서비스 DLQ/observation 확산, 로그 수집(Loki/ELK).
  > **2026-09 갱신.** 로그 수집은 Loki + Alloy 로 붙었다 — docs/51 §4. Kafka **프로듀서** 쪽 추적 전파는 아직이다.
