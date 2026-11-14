# 관측성 스택 · 분산 추적 · Kafka DLQ 적용

[日本語](11-observability-and-kafka-reliability.md) | **한국어**

> 🕰️ **상태(2026-11-14) — 2026-07-27 의 기록. 여기 적힌 "남은 것" 은 모두 끝났고, 추적은 Zipkin 에서 OpenTelemetry + Jaeger 로 바뀌었습니다.** 본문(§1 ~ §4)은 그때의 글로 두고, 절마다 🕰️ 로 지금을 적었습니다. 지금의 구성은 [docs/63](63-opentelemetry.ko.md)(추적) · [docs/51](51-logging.ko.md)(로그) · [docs/12](12-dlq-rollout-idempotency-alerts.ko.md)(DLQ · 멱등 · 경보) · [docs/22](22-alerting-and-backup-automation.ko.md)(경보 전달) · [docs/91](91-slo-and-capacity.ko.md)(SLO · 컨슈머 지연)을 봅니다.

| 2026-07 의 이 문서 | 지금(2026-11-14) | 어디 |
|---|---|---|
| 추적 저장소 Zipkin 3.4(인메모리) | **OTel Collector 0.160 → Jaeger 2.20**(badger, 7일). Collector 가 개인정보 가림 · 꼬리 샘플링(B-102) · 스팬 자르기(B-94)를 맡는다. Grafana 의 옛 Zipkin 데이터소스는 지우게 되어 있다 | docs/63 |
| 서비스 쪽 `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` | `micrometer-tracing-bridge-otel` + OTLP(HTTP) — 서비스는 Collector 에만 보낸다 | 루트 `build.gradle` |
| 샘플링 1.0 은 "운영에선 낮출 것" | **1.0 고정**(B-72) — 무엇을 남길지는 추적이 끝난 뒤 Collector 가 고른다 | `application-common.yml` |
| Kafka 추적은 catalog · order 컨슈머만, 프로듀서는 아직 | 컨슈머는 Kafka 를 받는 8개 서비스 모두, 프로듀서는 모든 `KafkaTemplate`(공통 후처리기), 아웃박스 행에도 추적 문맥 | docs/51 §4d · `KafkaTemplateObservationPostProcessor` · `OutboxTraceContext` |
| Grafana 대시보드 없음 | 대시보드 둘 자동 등록(`ecommerce-overview` · `tracing-red`), 지표 ↔ 추적 ↔ 로그 왕복 | `observability/grafana/provisioning/` |
| 경보는 다음 단계 | Alertmanager 0.28.1 + 경보 규칙(서비스 · 요청 · JVM · 사가 · 아웃박스 · 백업 · 추적 · **SLO · 컨슈머 지연**), Discord 전달(B-96) | `observability/alert-rules.yml` · docs/22 · docs/91 |
| 스크레이프는 `insecure_skip_verify` | 로컬 개발(`prometheus.yml`)만. 운영은 클러스터 안 에이전트가 CA 로 검증해 노드 Prometheus 로 보낸다(B-95) | `k8s-overlays/external/metrics-agent.yaml` |
| DLQ 는 catalog · order 만, 백오프 3회 | Kafka 를 받는 8개 서비스(+ user 의 DLT 설정), 공통 정책 `DltRetryPolicy` — 일시 오류는 1 · 2 · 4 … 30초로 10번(약 3분), DLT 계수기 · 경보 `KafkaDeadLetterPublished`(기동 때 0 — B-101) · 재처리 `ops/56` | docs/12 |
| G8 멱등성(eventId 없음) | eventId + 처리 원장(catalog · coupon · cart · order), 30일 정리(`ProcessedEventPurger`) | docs/12 §2 |
| 로그 수집 없음 | Loki + Alloy, 로그 줄의 traceId → Jaeger | docs/51 §4 |
| 컨슈머 랙 모니터링 | 2026-11-14 부터 지표 `kafka_consumer_fetch_manager_records_lag_max` · 경보 `KafkaConsumerLagHigh` | docs/91 §3 |

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

> 🕰️ 지금: 위 네 가지 한계는 모두 풀렸다 — 운영 스크레이프는 CA 검증(B-95), 대시보드 둘 자동 등록, Alertmanager · 경보 규칙(docs/12 §3 · docs/22), 운영은 kube-prometheus-stack 대신 노드의 compose(두 파일 — `docker-compose-observability.oci.yml`) + 클러스터 안 `metrics-agent`(docs/63 §5). Zipkin 은 Jaeger 로 바뀌었다(docs/63). 서비스는 이제 11개.

## 2. 분산 추적 (G4)

**추가**:
- 루트 `build.gradle`(subprojects 공통): `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` → **전 서비스**(gateway 포함)에 적용. 버전은 Boot BOM 관리.
- `application-common.yml`: `management.tracing.sampling.probability: 1.0`(운영에선 낮출 것), `management.zipkin.tracing.endpoint`(기본 `localhost:9411`, `ZIPKIN_ENDPOINT`로 오버라이드 — k8s ConfigMap에서 주입 가능).
- **전 서비스 로그 패턴에 `[%X{traceId:-},%X{spanId:-}]` 삽입** — 서비스들이 커스텀 패턴으로 Boot 기본 상관관계 출력을 덮고 있었으므로 명시적으로 추가(10개 yml).
- Kafka 경로 전파: 이번에 수정한 catalog/order 컨슈머 팩토리에 `setObservationEnabled(true)` 적용.

효과: HTTP(게이트웨이→서비스, Feign)와 catalog/order Kafka 소비까지 **traceId 하나로 로그 연결 + Zipkin 타임라인 조회**. 남은 것: 나머지 서비스의 Kafka 컨슈머/프로듀서 observation 활성화(동일 한 줄 패턴), 커스텀 KafkaTemplate 프로듀서 측 전파.

> 🕰️ 지금: Brave · Zipkin 은 `micrometer-tracing-bridge-otel` + OTLP 로 바뀌었다(Collector 주소는 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` → `management.otlp.tracing.endpoint` — `ZIPKIN_ENDPOINT` 는 없다, docs/63 §2). 샘플링은 "낮출 것" 이 아니라 **1.0 고정**(B-72 — 꼬리 샘플링이 결과를 보고 고른다). 로그 패턴은 공통 `logback-spring.xml` 하나(`[traceId,spanId,userId]`). 남은 것 둘은 끝났다 — 컨슈머 observation 은 Kafka 를 받는 8개 서비스 모두, 프로듀서는 모든 `KafkaTemplate` 빈에 공통 후처리기(`KafkaTemplateObservationPostProcessor`, docs/51 §4d), 아웃박스 릴레이처럼 요청 밖에서 보내는 것은 행에 저장한 추적 문맥으로 잇는다(`OutboxTraceContext`).

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
  - **재처리 도구(2026-10-22): `ops/56-dlt-replay.sh`**. `list <토픽>.DLT` 는 자리 · 키 · 크기 · 원래 자리 · 실패한 그룹 · 예외를 보여 준다(값은 출력하지 않는다 — 개인정보). `replay <토픽>.DLT <파티션> <오프셋>[-<끝>]` 은 보내지 않고 보여 주기만 하고, `APPLY=1` 이면 원래 토픽 · **원래 파티션**으로 값 · 키 · 헤더를 바이트 그대로 보낸다(DLT 헤더만 빼고 `dlt-replayed-from` 을 더한다).
  - Kafka 파드 안에서 그 파드의 Java · kafka-clients 로 돈다(`ops/tools/DltReplay.java`, 단일 파일).
  - 콘솔 도구로는 안 된다 — 값을 줄 단위 글자로 다뤄 Avro 값이 깨진다(실습 게임데이 poison-message).
  - ⚠️ 원래 토픽의 **모든** 컨슈머 그룹이 다시 받는다 — 각 컨슈머가 eventId · 처리 원장으로 거르는지 먼저 본다(docs/12 §2). 테스트는 `DltReplayScriptTest`(cp-kafka 7.6.1).
- **나머지 4개 서비스(cart/coupon/pay/user)에 동일 패턴 확산** — 이 두 구현이 복붙 가능한 템플릿이다.

> 🕰️ 지금: 확산은 끝났다 — Kafka 를 받는 8개 서비스(cart · catalog · chat · coupon · delivery · notification · order · pay)가 모두 배치 리스너 + 인덱스 보고 + `KafkaDltConfig`(user 도 DLT 설정은 있다 — 받는 리스너는 없다). 재시도는 "3회(1s×2)" 가 아니라 공통 `DltRetryPolicy`: 일시 오류(DB 일시 실패 · 잠금 등)는 1 · 2 · 4 · 8 · 16 · 30초 … 10번(약 3분), 그 밖은 바로 DLT. DLT 로 간 레코드는 계수기 `kafka_dlt_records_total{topic}`(기동 때 0 — B-101)와 경보 `KafkaDeadLetterPublished` 로 보인다. ⚠️ 2026-10-15 전에는 pay · order 팩토리의 레코드 어댑터가 예외를 삼켜 이 재시도 · DLT 가 돌지 않았다(B-88 — 이제 금지).

## 4. 여전히 남은 것

> 🕰️ **2026-11-14 — 아래 목록은 모두 끝났다.** 지금 남은 관측 일은 docs/91 §6(SLO 숫자 확정 · 천장 측정 · 게이트웨이 SLI · SLO 대시보드)에 있다.

- ~~**G8 멱등성**: `ProductUpdateRequest` 등 이벤트 페이로드에 `eventId`가 없어 컨슈머 dedupe 불가.~~ → ✅ eventId + 처리 원장(catalog · coupon · cart · order 배송 상태), 30일 정리 `ProcessedEventPurger`(docs/12 §2).
- ~~Alertmanager 경보 규칙~~ → ✅ docs/12 §3 · docs/22(Discord, B-96) · docs/91(SLO 소진율 · 컨슈머 지연).
- ~~Grafana 대시보드 코드화~~ → ✅ `observability/grafana/provisioning/dashboards/`.
- ~~나머지 서비스 DLQ/observation 확산~~ → ✅ Kafka 를 받는 8개 서비스 모두(§3 의 🕰️), 프로듀서 쪽 추적도(§2 의 🕰️ — 2026-09 갱신에 "아직" 이라 적었던 것).
- ~~로그 수집(Loki/ELK)~~ → ✅ Loki + Alloy(docs/51 §4).
