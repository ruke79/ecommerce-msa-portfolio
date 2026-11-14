# 91. SLO 와 용량 측정 — 이행 단계 0

[日本語](91-slo-and-capacity.md) | **한국어**

> 📝 **상태(2026-11-14) — 도구는 적용, 숫자는 초안.** [docs/89](89-scale-out-migration-plan.ko.md) 단계 0(목표와 측정)의 레포 쪽 작업입니다: ① SLO 를 기록 규칙 · 경보로 ② 컨슈머 지연 지표 ③ 천장을 재는 `ops/53 ceiling`. **목표 숫자(가용성 99.9% · 300ms · 1초)는 docs/89 §1 의 예를 그대로 쓴 초안**이고, 확정 · 행사 피크 목표 · 실제 천장 측정(실습 클러스터)은 남아 있습니다(§6).

## 0. 요약

| | 무엇 | 어디 |
|---|---|---|
| SLO 두 개 | `catalog-read`(상품 · 검색 · 홈 GET) · `order-create`(주문 생성 POST) — 가용성 99.9% · 지연 99% 가 300ms / 1초 안 | `observability/alert-rules.yml` 의 `slo` 그룹 |
| 지연 경계 버킷 | 히스토그램에 **정확히** 0.3 · 1.0 초 경계를 더했다 — 기본 버킷에는 없어 "300ms 안의 비율" 을 셀 수 없었다 | `application-common.yml` 의 `management.metrics.distribution.slo` |
| 오류 예산 경보 | 다중 창 소진율 넷 — 빠름(1h · 5m, 14.4배, critical) · 느림(6h · 30m, 6배, warning), 요청이 적으면 울리지 않음 | 같은 `slo` 그룹 · promtool 시험 |
| 컨슈머 지연 | **지금까지 지표가 없었다** — 모든 컨슈머 팩토리에 Micrometer 리스너, 경보 `KafkaConsumerLagHigh` | common `KafkaConsumerMetricsAutoConfiguration` · `kafka-consumer` 그룹 |
| 천장 재기 | 도착률을 계단으로 올리며 SLO 가 깨지는 계단에서 멈추고, 계단마다 서비스별 파드당 요청/초 · CPU · DB 커넥션 대기 · Kafka 지연을 표로 | `./ops/53-load-k6.sh ceiling browse|order|mixed` |

---

## 1. 왜 지금

docs/89 는 "숫자 없이 다음 단계를 고르면 장비 · 관리형의 크기를 짐작으로 정하게 된다" 고 했습니다. 단계 0 을 시작해 보니 숫자를 낼 도구부터 비어 있었습니다.

- **SLO 가 레포 어디에도 없었다**(docs/86 §0). 있던 것은 서비스별 5xx 5% · p99 3초 경보뿐 — "목표를 얼마나 쓰고 있나" 가 아니라 "크게 터졌나" 만 본다.
- **p99 300ms 를 셀 수 없었다.** `percentiles-histogram` 의 기본 버킷에는 0.3 · 1.0 초 경계가 없어 `le="0.3"` 시계열이 없다(`SloRulesTest` 가 확인) — "300ms 안의 비율" 은 근삿값(`histogram_quantile`)으로만 나왔다.
- **컨슈머 지연 지표가 없었다.** Spring Boot 는 자기 자동 설정의 컨슈머 팩토리에만 Micrometer 리스너를 다는데, 이 레포의 서비스는 모두 팩토리를 직접 만든다(`KafkaConsumerConfig` · `KafkaDltConfig`). 경보 규칙 머리말도 "컨슈머 랙은 로드맵" 이었다. docs/89 단계 0 의 측정 항목("Kafka 지연을 남긴다")을 잴 수 없었다.
- **`ops/53` 은 한 번에 한 모양의 부하**를 걸었다 — 천장을 찾으려면 사람이 도착률을 바꿔 가며 여러 번 돌리고 Grafana 에서 시간대를 맞춰 숫자를 옮겨 적어야 했다.

---

## 2. SLO 초안

| SLO | SLI 대상 | 가용성 | 지연 | 왜 이 경로 |
|---|---|---|---|---|
| `catalog-read` | catalog-service 의 GET — 관리자(`admin`) · 내부(`internal`) · actuator 제외 | 99.9% (30일 약 43분) | 99% 가 **300ms** 안 | 행사 피크 읽기의 대부분 · 첫 화면 · 목록 · 검색(docs/87 §3-1) |
| `order-create` | order-service 의 `POST /api/order/create` | 99.9% | 99% 가 **1초** 안 | 결제 바로 앞 — 늦거나 실패하면 이탈 |

- **오류는 5xx 만**입니다. 4xx 는 손님 쪽(잘못된 요청 · 재고 부족 · 남의 자원)이라 예산에서 뺍니다.
- **지연은 서비스 쪽 시간**입니다(`http.server.requests`). 게이트웨이 · Cloudflare · 망 시간은 빠집니다 — 사용자 체감은 `ops/53` 의 k6 p99 로 봅니다(§4). 게이트웨이 쪽 SLI 는 남은 일입니다(§6).
- **숫자를 바꾸는 곳은 한 곳**: `alert-rules.yml` 의 `slo:objective:availability` · `slo:objective:latency` 두 줄(SLO 마다). 경보는 그 값에서 예산(1 − 목표)을 계산합니다. 지연 **경계**(300ms · 1초)를 바꾸면 세 곳을 함께 — `application-common.yml` 의 `slo` 버킷, 규칙의 `le`, `ops/53` 의 `CEILING_P99_MS` 기본값. 앞의 둘은 가드 `SloRulesTest` 가 맞춰 봅니다.
- **지금 운영(노드 한 대)이 이 목표를 지키는지**는 경보 규칙을 올린 뒤 30일 동안 `slo:sli_error:ratio_rate6h` · `slo:sli_slow:ratio_rate6h` 로 봅니다. 못 지키면 그것이 단계 1(데이터 계층을 노드 밖으로)의 근거가 됩니다.

### 2-1. 기록 규칙

SLO 마다, 창 넷(5m · 30m · 1h · 6h)마다:

| 기록 이름 | 뜻 |
|---|---|
| `slo:sli_error:ratio_rate<창>` | 5xx / 전체 — 5xx 가 하나도 없으면 0(`or vector(0)`, 비어 있으면 경보가 "모름" 이 된다) |
| `slo:sli_slow:ratio_rate<창>` | 1 − (경계 안 / 전체) — 5xx 도 센다 |
| `slo:requests:rate1h` | 1시간 평균 초당 요청 — 경보의 하한 |
| `slo:objective:availability` · `slo:objective:latency{threshold_seconds}` | 목표(상수) |

### 2-2. 오류 예산 경보

| 경보 | 조건(둘 다) | 뜻 | 등급 |
|---|---|---|---|
| `SloErrorBudgetBurnFast` · `SloLatencyBudgetBurnFast` | 1h · 5m 비율 > 14.4 × 예산 | 30일 예산의 2% 를 1시간에 — 지금도 계속되는 큰 문제 | critical |
| `SloErrorBudgetBurnSlow` · `SloLatencyBudgetBurnSlow` | 6h · 30m 비율 > 6 × 예산 | 5% 를 6시간에 — 천천히 새는 문제 | warning |

- 긴 창이 "충분히 많이 썼다", 짧은 창이 "지금도 쓰고 있다" — 끝난 사고로 한 시간씩 울리지 않습니다(Google SRE workbook 5장의 다중 창 · 다중 소진율).
- **요청이 적으면 울리지 않습니다**: `slo:requests:rate1h > 0.03`(시간당 약 100건). 지금 운영처럼 손님이 적으면 한두 건의 실패가 예산을 다 쓴 것처럼 보입니다 — 그런 실패는 기존 `High5xxRate` 가 봅니다.

### 2-3. 시험

- `observability/tests/slo-rules.test.yml`(promtool) — 5% 5xx 에서 빠름 · 느림이 울린다, 2분 안에는 아직 안 울린다, 0.05% 는 예산 안, 초당 0.01 이면 33% 실패여도 조용, 주문 절반이 1초를 넘으면 지연 빠름 · 10% 면 지연 느림만, 관리자 · actuator 경로는 SLI 에 들지 않는다, 컨슈머 지연 1,500 이 10분.
  실행: `SloRulesPromtoolTest`(컨테이너 — 운영과 같은 `prom/prometheus:v2.53.0`; Docker Hub 가 막히면 `PROMETHEUS_IMAGE=mirror.gcr.io/prom/prometheus:v2.53.0`).
- `SloRulesTest` — `application-common.yml` 을 Boot 와 같은 필터(`PropertiesMeterFilter`)로 묶어 Prometheus 출력에 `le="0.3"` · `le="1.0"` 이 있는지(기본 버킷만으로는 없다), 규칙이 읽는 `le` 가 모두 그 안에 있는지, SLO 마다 목표 · SLI · 경보가 다 있는지.
- 변이 확인: 요청 하한 제거 · 경계 `le` 바꿈 · `or vector(0)` 제거 · 관리자 경로 포함 → promtool 시험 실패, yml 경계 250ms → `SloRulesTest` 실패.

---

## 3. Kafka 컨슈머 지연

- common 의 `KafkaConsumerMetricsAutoConfiguration` 이 모든 컨슈머 팩토리에 `MicrometerConsumerListener` 를 답니다 — 팩토리 빈이면 그것에, 리스너 컨테이너 팩토리 빈이면 그 안의 팩토리에(대부분의 서비스는 `new DefaultKafkaConsumerFactory` 를 컨테이너 팩토리에 바로 넘깁니다). 이미 달려 있으면 더하지 않습니다. 끄려면 `ecommerce.kafka.consumer-metrics.enabled=false`.
- 볼 지표: `kafka_consumer_fetch_manager_records_lag_max`(클라이언트마다 파티션 중 가장 큰 지연). 토픽 · 파티션별로는 `kafka_consumer_fetch_manager_records_lag`.
- 경보 `KafkaConsumerLagHigh`: 서비스 · 토픽마다 1,000 건이 10분(warning). 부하 측정 중이면 그것이 결과입니다.
- 시험 `KafkaConsumerMetricsTest` — 컨테이너 팩토리 안 · 팩토리 빈 · 이미 달린 것(하나만) · 끄기. 변이 확인: 컨테이너 팩토리 분기 제거 · 중복 확인 제거 → 실패.

---

## 4. 천장 재기 — `ops/53 ceiling`

```bash
# 실습 클러스터(운영 주소는 거절). 유입 제한을 먼저 올린다(docs/83 §3) — 아니면 천장은 초당 20 의 429 다
./ops/53-load-k6.sh limits load
PROM=http://<obs-vm>:9090 ./ops/53-load-k6.sh ceiling browse          # catalog-read: 전체 요청의 p99 ≤ 300ms
PROM=http://<obs-vm>:9090 CEILING_RATES=5,10,20,40 ./ops/53-load-k6.sh ceiling order   # order-create: order_create 의 p99 ≤ 1초
./ops/53-load-k6.sh limits default
```

- **계단** `CEILING_RATES`(기본 10,20,50,100,200,400 — 초당 반복), 계단마다 `CEILING_RAMP`(30s) 올리고 `CEILING_HOLD`(3m) 유지. 계단마다 이 스크립트를 한 번씩 다시 부르므로 검사 · `meta.txt` 는 보통 회차와 같습니다.
- **판정**(k6 요약): p99 ≤ `CEILING_P99_MS` · 5xx 비율 ≤ `CEILING_MAX_5XX`(0.1% — 가용성 99.9% 의 예산). 깨진 계단에서 멈추고 **그 앞 계단이 천장**입니다. 버린 반복(`dropped_iterations`)이 1% 를 넘으면 부하기가 먼저 모자란 것이라 멈추고 "서버 천장은 아직 모른다" 고 적습니다 — 부하 VM 을 늘립니다(ceiling 은 `SPLIT` 과 함께 쓰지 않습니다).
- **서버 쪽**(`PROM` 이 있으면 계단 동안): 서비스별 요청/초 · 파드 수(→ **파드당 요청/초**) · CPU 평균 · DB 커넥션 대기 최대 · Kafka 지연 최대. "막힌 곳 후보" 는 CPU 80% 이상 · 대기 > 0 · 지연 > 1,000.
- 결과: `load-results/ceiling-<시나리오>-<시각>/ceiling.md`(계단 표 + 서비스별 표), 계단마다의 회차 폴더는 `steps/<계단>/`.
- 시험 `LoadK6ScriptTest`(가짜 k6 · 가짜 Prometheus): 200 에서 멈추고 400 은 돌리지 않는다 · order 는 order_create p99 · 5xx · 부하기 모자람 · 운영 주소 · STAGES · SPLIT · 줄어드는 계단 거절. 변이 확인: 멈추지 않기 · 부하기 판정 제거 → 실패.

### 4-1. 천장 표(단계 0 의 끝난 조건 — 측정하면 채운다)

| 서비스 | 파드당 요청/초(SLO 안) | 먼저 막힌 곳 | 시나리오 · 계단 | 날짜 · 커밋 · 환경 |
|---|---|---|---|---|
| catalog-service | — | — | browse | — |
| gateway-server | — | — | browse | — |
| order-service | — | — | order | — |
| cart-service | — | — | order | — |

참고로 지금까지의 실습 회차에서 catalog 파드 하나는 ~350 요청/초, 병목은 CPU(JSON 직렬화)였습니다(docs/83 · docs/89 §0 #9) — 그때는 SLO 없이 "임계값 통과" 로 판정했습니다. 실습 클러스터의 절대값은 코어를 나눠 쓴 값이라 크기 산정에는 비율 · 병목의 순서만 씁니다(docs/83).

---

## 5. 적용 · 확인

- **이미지**: 버킷 · 컨슈머 지표는 common 이라 다음 배포의 모든 백엔드 이미지에 들어갑니다(동작 변화 없음 — 지표만 늘어난다. 버킷은 `http.server.requests` 시계열마다 둘).
- **규칙**: 관측 스택에 다시 올린다 — `docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d prometheus`(두 파일).
- **확인**(배포 뒤 Prometheus):
  - `count(http_server_requests_seconds_bucket{le="0.3"})` > 0
  - `slo:sli_error:ratio_rate1h` 에 `catalog-read` · `order-create` 두 줄(손님이 없으면 지연 SLI 는 비어 있을 수 있다)
  - `count by (service) (kafka_consumer_fetch_manager_records_lag_max)` 에 Kafka 를 쓰는 서비스

---

## 6. 남은 것

1. **숫자 확정(결정)** — 가용성 · 지연 목표, 행사 피크 초당 목표(docs/87 §0 의 5,000 ~ 10,000 은 가정). 지금 값은 docs/89 §1 의 예.
2. **천장 측정** — 실습 클러스터에서 `ceiling browse` · `ceiling order` 를 돌려 §4-1 을 채운다. 그 표가 docs/89 단계 2 · 3 · 4 의 크기를 정한다.
3. **게이트웨이 SLI** — 사용자 체감에 더 가깝다. 게이트웨이의 지표를 라우트 단위로 묶어 같은 SLO 를 다는 일은 아직 하지 않았다(지금 SLI 는 서비스 쪽).
4. **대시보드** — SLO 패널(남은 예산 · 소진율)은 Grafana 에 아직 없다.
