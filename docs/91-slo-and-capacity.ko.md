# 91. SLO 와 용량 측정 — 이행 단계 0

[日本語](91-slo-and-capacity.md) | **한국어**

> 📝 **상태(2026-11-14) — SLO · 예산 규칙 · 처리량 목표를 정해 적용, 실측은 남음.** [docs/89](89-scale-out-migration-plan.ko.md) 단계 0(목표와 측정)의 레포 쪽 작업입니다. SLO 넷(§2)을 기록 규칙 · 소진율 경보로, 14일 오류 예산과 그 규칙(§2-4), 단계별 처리량 목표(§2-5), 손님 쪽 길을 보는 바깥 탐침(§2-6), 컨슈머 지연 지표(§3), 천장을 재는 `ops/53 ceiling`(§4). 남은 것은 실습 클러스터에서의 천장 측정과 노드 밖 감시입니다(§6).

## 0. 요약

| | 무엇 | 어디 |
|---|---|---|
| SLO 넷 | `catalog-browse`(홈 · 상세) · `catalog-search`(목록 · 필터 · 이름 검색) · `order-create`(주문 생성) · `site-probe`(바깥 탐침) — 지금(노드 한 대)의 값과 단계 1 · 2 뒤의 목표 | `observability/alert-rules.yml` 의 `slo` 그룹 |
| 지연 경계 버킷 | 히스토그램에 **정확한** 0.3 · 1.0 초 경계 — 기본 버킷에는 없어 "300ms 안의 비율" 을 셀 수 없었다 | `application-common.yml` 의 `management.metrics.distribution.slo` |
| 소진율 경보 | 다중 창 넷 — 빠름(1h · 5m, 14.4배, critical) · 느림(6h · 30m, 6배, warning), 요청이 적으면 울리지 않음 | `slo` 그룹 · promtool 시험 |
| 14일 오류 예산 | 예산 사용률 · 절반(warning) · 소진(critical) 경보와 그때의 규칙 | `slo-budget` 그룹 |
| 처리량 목표 | 단계 2 끝 초당 1,000 · 단계 3 끝 초당 5,000 | §2-5 · `ops/53 ceiling` 의 `CEILING_TARGET_RPS` |
| 바깥 탐침 | 운영 노드의 blackbox-exporter 가 Cloudflare 를 거쳐 첫 화면 · 홈 API 를 30초마다, 경보 `SiteProbeDown` | `docker-compose-observability.oci.yml` · `observability/blackbox.yml` · `prometheus.oci.yml` |
| 컨슈머 지연 | **지금까지 지표가 없었다** — 모든 컨슈머 팩토리에 Micrometer 리스너, 경보 `KafkaConsumerLagHigh` | common `KafkaConsumerMetricsAutoConfiguration` · `kafka-consumer` 그룹 |
| 천장 재기 | 도착률 계단마다 SLO 를 보고 깨지는 계단에서 멈춘다 — 서비스별 파드당 요청/초 · CPU · DB 커넥션 대기 · Kafka 지연, 처리량 목표와 견줌 | `./ops/53-load-k6.sh ceiling browse|order|mixed` |

---

## 1. 왜 지금

docs/89 는 "숫자 없이 다음 단계를 고르면 장비 · 관리형의 크기를 짐작으로 정하게 된다" 고 했습니다. 단계 0 을 시작해 보니 숫자를 낼 도구부터 비어 있었습니다.

- **SLO 가 레포 어디에도 없었다**(docs/86 §0). 있던 것은 서비스별 5xx 5% · p99 3초 경보뿐 — "목표를 얼마나 쓰고 있나" 가 아니라 "크게 터졌나" 만 본다.
- **p99 300ms 를 셀 수 없었다.** `percentiles-histogram` 의 기본 버킷에는 0.3 · 1.0 초 경계가 없어 `le="0.3"` 시계열이 없다(`SloRulesTest` 가 확인) — "300ms 안의 비율" 은 근삿값(`histogram_quantile`)으로만 나왔다.
- **컨슈머 지연 지표가 없었다.** Spring Boot 는 자기 자동 설정의 컨슈머 팩토리에만 Micrometer 리스너를 다는데, 이 레포의 서비스는 모두 팩토리를 직접 만든다(`KafkaConsumerConfig` · `KafkaDltConfig`).
- **서비스 쪽 지표는 손님 쪽 길을 보지 않는다** — Cloudflare · 터널 · 게이트웨이 · 프론트가 막혀도 catalog 의 5xx 는 0 이다.
- **`ops/53` 은 한 번에 한 모양의 부하**를 걸었다 — 천장을 찾으려면 사람이 도착률을 바꿔 가며 여러 번 돌리고 Grafana 에서 숫자를 옮겨 적어야 했다.

---

## 2. SLO

| SLO | SLI 대상 | 가용성 지금 → 목표 | 지연 | 근거(docs/83 실측 등) |
|---|---|---|---|---|
| `catalog-browse` | catalog GET — 홈 · 상품 상세 · 배너 · 공지 · 기획전(`/api/search*` · 관리자 · 내부 · actuator 제외) | **99.5% → 99.9%** | **99% 가 300ms 안** | 실측 p99 22 ~ 25ms(1,000만 상품, 캐시 밖 상세 포함) — 운영 장비 · 캐시 밖까지 여유 |
| `catalog-search` | catalog GET `/api/search*` — 카테고리 목록 · 필터 · 이름 검색 | **99.0% → 99.5%**(ES 뒤 99.9%) | **95% 가 1초 안** | 필터 조합을 넓힌 8시간 회차 p95 1,513ms · 운영 200만의 드문 필터 조합은 5초 상한 503(docs/83 §2.8) — ES 전까지 남는 한계 |
| `order-create` | order `POST /api/order/create` | **99.5% → 99.9%** | **99% 가 1초 안** | 실측 p99 113ms(초당 5건 55분) — 재고 경합 · 쿠폰 · 장바구니 호출의 여유 |
| `site-probe` | 바깥 탐침 — 첫 화면 · 홈 API(§2-6) | **99.5% → 99.9%** | **99% 가 2초 안** | Cloudflare · 터널 · 게이트웨이 · 프론트까지 한 번에 |

- **지금 → 목표**: 지금은 PostgreSQL · Redis · Kafka 가 `replicas: 1` 이고 노드가 한 대라(docs/89 §0) 노드 재부팅 · PG 파드 재생성 하나가 곧 전체 정지입니다. 99.9%(14일에 약 20분)는 유지보수 한 번에 예산을 다 씁니다 — 처음부터 걸면 첫 달부터 "예산 없음" 으로 시작합니다. 99.5%(14일에 약 1.7시간)는 노드 한 대에서 지킬 수 있는 선입니다. docs/89 단계 1 · 2 를 마치면 목표 값으로 올립니다(규칙 주석에 적었습니다).
- **검색을 따로 둔 이유**: 카테고리 목록 화면도 `/api/search` 를 씁니다. 드문 필터 조합의 503 은 이미 알려진 한계인데, 한 SLO 에 섞으면 그것이 **상품 · 홈의 예산까지 먹습니다**. 검색의 지연 목표는 꼬리가 길어 99% 가 아니라 95% 입니다(경계 1초는 이미 있는 버킷).
- **오류는 5xx 만**입니다. 4xx 는 손님 쪽(잘못된 요청 · 재고 부족 · 남의 자원 · 유입 제한 429)이라 예산에서 뺍니다.
- **서비스 쪽 SLO 의 지연은 서비스 쪽 시간**입니다(`http.server.requests`). 게이트웨이 · Cloudflare · 망 시간은 `site-probe` 가 봅니다.
- **숫자를 바꾸는 곳은 한 곳**: `alert-rules.yml` 의 `slo:objective:availability` · `slo:objective:latency` 두 줄(SLO 마다). 규칙은 생성해서 붙였으므로 SLO 를 더하거나 선택자를 바꿀 때는 같은 모양으로 넷(창) × 둘(SLI) 을 함께 고칩니다. 지연 **경계**를 바꾸면 `application-common.yml` 의 `slo` 버킷 · 규칙의 `le` · `ops/53` 의 `CEILING_P99_MS` 기본값도 — 가드 `SloRulesTest` 가 맞춰 봅니다.

### 2-1. 기록 규칙

SLO 마다, 창 넷(5m · 30m · 1h · 6h)마다:

| 기록 이름 | 서비스 쪽 SLO | `site-probe` |
|---|---|---|
| `slo:sli_error:ratio_rate<창>` | 5xx / 전체 — 5xx 가 없으면 0(`or vector(0)`) | 1 − 탐침 성공 비율 |
| `slo:sli_slow:ratio_rate<창>` | 1 − (경계 안 / 전체) — 5xx 도 센다 | 탐침 시간 > 2초 인 비율 |
| `slo:requests:rate1h` | 1시간 평균 초당 요청 — 경보의 하한 | 초당 탐침 수(대상 둘 × 30초 = 0.067) |
| `slo:requests:increase1h` · `slo:errors:increase1h` · `slo:slow:increase1h` | 1시간 증가분 — 14일 예산이 더한다(§2-4) | 같은 뜻의 탐침 수 |
| `slo:objective:availability` · `slo:objective:latency{threshold_seconds}` | 목표(상수) | 같음 |

### 2-2. 소진율 경보

| 경보 | 조건(둘 다) | 뜻(14일 예산 기준) | 등급 |
|---|---|---|---|
| `SloErrorBudgetBurnFast` · `SloLatencyBudgetBurnFast` | 1h · 5m 비율 > 14.4 × 예산 | 14일 예산의 약 4% 를 1시간에 — 지금도 계속되는 큰 문제 | critical |
| `SloErrorBudgetBurnSlow` · `SloLatencyBudgetBurnSlow` | 6h · 30m 비율 > 6 × 예산 | 약 11% 를 6시간에 — 천천히 새는 문제 | warning |

- 긴 창이 "충분히 많이 썼다", 짧은 창이 "지금도 쓰고 있다" — 끝난 사고로 한 시간씩 울리지 않습니다(Google SRE workbook 5장의 다중 창 · 다중 소진율).
- **요청이 적으면 울리지 않습니다**: `slo:requests:rate1h > 0.03`(시간당 약 100건). 지금 운영처럼 손님이 적으면 한두 건의 실패가 예산을 다 쓴 것처럼 보입니다 — 그런 실패는 기존 `High5xxRate` 와 탐침(`SiteProbeDown`)이 봅니다.

### 2-3. 시험

- `observability/tests/slo-rules.test.yml`(promtool, 1분 간격): browse 10% 5xx 에서 빠름 · 느림 · 2분 안에는 아직 · 0.05% 는 예산 안 · 초당 0.01 이면 33% 실패여도 조용 · 주문 절반이 1초를 넘으면 지연 빠름, 10% 면 지연 느림만 · 관리자 · actuator 는 어디에도, `/api/search` 는 search 에만 · search 40% 가 1초를 넘으면 지연 느림만 · search 8% 5xx 는 search 의 느림만(browse 의 예산은 0) · 탐침 하나가 실패하면 `SiteProbeDown`(3분) 과 site-probe 빠름 · 탐침 실패 비율 값 · 탐침이 3초면 지연 빠름 · 컨슈머 지연 1,500 이 10분.
- `observability/tests/slo-budget.test.yml`(promtool, 지표 10분 간격 15일 · 평가는 그룹과 같은 5분 — 다르면 그룹 평가 순서가 흔들려 경보가 가끔 빠졌다): 0.4% 5xx 가 15일이면 예산 80% — 절반만, 0.6% 면 120% — 소진(절반도), 요청이 적으면 조용.
- 실행: `SloRulesPromtoolTest`(컨테이너 — 운영과 같은 `prom/prometheus:v2.53.0`, Docker Hub 가 막히면 `PROMETHEUS_IMAGE=mirror.gcr.io/prom/prometheus:v2.53.0`).
- `SloRulesTest` — 버킷 `le="0.3"` · `le="1.0"` 이 노출되는지(기본 버킷만으로는 없다), SLO 마다 목표의 경계를 그 SLO 의 지연 SLI 가 읽는지, SLO 넷의 목표 · SLI · 증가분 · 경보, browse 가 검색을 빼는지, 14일 창 < 운영 Prometheus 보존, 탐침 배선(판 · 포트 없음 · TLS 검증 · 운영 주소 · 모듈 · 30초).
- 변이 확인: 요청 하한 제거 · 경계 `le` · `or vector(0)` 제거 · 관리자 경로 포함 · 검색을 browse 에 · 절반 기준 0.9 · 예산 하한 제거 · 탐침 실패율 뒤집기 · `SiteProbeDown` 의 `for` 제거 → promtool 시험 실패, yml 경계 250ms · 탐침에 포트 → `SloRulesTest` 실패.

### 2-4. 14일 오류 예산과 규칙

- **창은 14일**입니다. 운영 Prometheus 보존이 15일(`--storage.tsdb.retention.time=15d`)이라 30일 예산은 셀 수 없습니다. 30일로 가려면 보존을 31일로 올려야 하는데 TSDB 가 대략 두 배가 됩니다 — 먼저 `docker system df -v` 로 prometheus 볼륨 크기와 디스크 여유(docs/63 §5 의 디스크 사고)를 보고 정합니다. 가드는 "창 < 보존" 을 지킵니다.
- 예산 사용률 `slo:budget_used:ratio14d{slo, budget="error"|"latency"}` = (14일 나쁜 요청 / 전체) / (1 − 목표). 무거운 `sum_over_time` 이라 그룹 `slo-budget` 은 5분마다 돕니다.
- **규칙**(경보가 그 규칙을 알린다):

| 예산 사용 | 경보 | 할 일 |
|---|---|---|
| 50% 이상 | `SloErrorBudgetHalfSpent`(warning, 1시간) | 큰 배포(스키마 변경 · 묶음 릴리스 — docs/85)는 원인을 고칠 때까지 미룬다. `slo:sli_*:ratio_rate6h` 로 어느 날 썼는지 보고 그때의 배포 · 사고 기록을 찾는다 |
| 100% 이상 | `SloErrorBudgetExhausted`(critical, 1시간) | 기능 배포를 멈추고 안정화 수정만. 되풀이되면 docs/89 단계 1(데이터 계층을 노드 밖으로)을 앞당기는 근거 |

- 14일 평균 요청이 초당 0.03 아래면 울리지 않습니다(위와 같은 이유).

### 2-5. 처리량 목표

| 언제 | 목표 | 근거 · 확인 |
|---|---|---|
| docs/89 단계 2 끝(앱 멀티 노드) | **평시 피크 초당 1,000 요청**을 위 SLO 안에서 | docs/87 §0 의 평시 피크 가정. 지금 실습 천장(catalog 파드 셋 ~1,100)과 거의 같아 바로 잴 수 있다 — `ceiling browse`(기본 `CEILING_TARGET_RPS=1000`) |
| docs/89 단계 3 끝(읽기를 앞단으로) | **행사 피크 초당 5,000 요청** | CDN 캐시 · ES 가 읽기를 받는다는 전제(docs/87 §3-1) |
| 그 뒤 | 초당 10,000 | 단계 3 의 측정을 보고 정한다 |

### 2-6. 바깥 탐침(`site-probe`)

- 운영 노드의 관측 덧씌우기(`docker-compose-observability.oci.yml`)에 `blackbox-exporter`(v0.25.0, 포트 공개 없음), 운영 Prometheus 의 작업 `site-probe` 가 30초마다 부른다(`prometheus.oci.yml` — 개발 PC 설정에는 없다, B-95 비교에서 뺐다).
- 대상 둘 — `https://www.ysyproject.com/`(모듈 `http_page`: 200 · TLS · 본문에 `<div id="root">`), `https://www.ysyproject.com/catalog-service/api/content/home`(모듈 `http_api`: 200 · `Content-Type: application/json` · 본문이 JSON). 상태 코드만 보면 Cloudflare 오류 · 챌린지 화면이나 프론트로 샌 API(index.html 200)도 성공이 된다. TLS 는 검증한다.
- 경보 `SiteProbeDown` — 대상 하나가 3분 연속 실패(critical). 소진율 경보는 `slo="site-probe"` 로 같은 규칙을 탄다.
- ⚠️ **한계**: 탐침은 운영 노드에서 나간다 — **노드가 통째로 죽으면 Prometheus · Alertmanager · 탐침이 함께 죽어 아무것도 울리지 않는다.** 그 경우는 노드 밖 감시(Cloudflare Health Checks · 외부 가동 감시 서비스 등)가 따로 필요하다(§6). 또 Cloudflare 의 봇 차단이 탐침을 막으면 403 · 챌린지로 실패한다 — `probe_http_status_code` 를 보고, 그러면 그 경로를 봇 규칙에서 뺀다.

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
PROM=http://<obs-vm>:9090 ./ops/53-load-k6.sh ceiling browse          # 전체 요청 p99 ≤ 300ms · 처리량 목표 1,000 과 견줌
PROM=http://<obs-vm>:9090 CEILING_RATES=5,10,20,40 ./ops/53-load-k6.sh ceiling order   # order_create p99 ≤ 1초
./ops/53-load-k6.sh limits default
```

- **계단** `CEILING_RATES`(기본 10,20,50,100,200,400 — 초당 반복), 계단마다 `CEILING_RAMP`(30s) 올리고 `CEILING_HOLD`(3m) 유지. 계단마다 이 스크립트를 한 번씩 다시 부르므로 검사 · `meta.txt` 는 보통 회차와 같습니다.
- **판정**(k6 요약): p99 ≤ `CEILING_P99_MS` · 5xx 비율 ≤ `CEILING_MAX_5XX`(0.1% — 목표 가용성 99.9% 의 예산; 천장은 단계 2 · 3 의 크기를 정하므로 목표 값으로 잰다). browse 는 목록 · 검색 요청도 300ms 로 보므로 `catalog-search`(95% 가 1초)보다 엄격합니다 — 보수적인 천장입니다. 깨진 계단에서 멈추고 **그 앞 계단이 천장**입니다. 버린 반복(`dropped_iterations`)이 1% 를 넘으면 부하기가 먼저 모자란 것이라 멈추고 "서버 천장은 아직 모른다" 고 적습니다 — 부하 VM 을 늘립니다(ceiling 은 `SPLIT` 과 함께 쓰지 않습니다).
- **처리량 목표**: `CEILING_TARGET_RPS`(browse · mixed 기본 1,000 — §2-5) — ceiling.md 머리에 "넘는다 · 못 미친다(목표의 몇 %) · 아직 모른다" 를 적습니다.
- **서버 쪽**(`PROM` 이 있으면 계단 동안): 서비스별 요청/초 · 파드 수(→ **파드당 요청/초**) · CPU 평균 · DB 커넥션 대기 최대 · Kafka 지연 최대. "막힌 곳 후보" 는 CPU 80% 이상 · 대기 > 0 · 지연 > 1,000.
- 결과: `load-results/ceiling-<시나리오>-<시각>/ceiling.md`(계단 표 + 서비스별 표), 계단마다의 회차 폴더는 `steps/<계단>/`.
- 시험 `LoadK6ScriptTest`(가짜 k6 · 가짜 Prometheus): 200 에서 멈추고 400 은 돌리지 않는다 · 처리량 목표와 견줌(못 미침 · 넘음 · order 는 없음) · order 는 order_create p99 · 5xx · 부하기 모자람 · 운영 주소 · STAGES · SPLIT · 줄어드는 계단 거절. 변이 확인: 멈추지 않기 · 부하기 판정 제거 → 실패.

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
- **관측 스택**(운영 노드, 두 파일): `docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d blackbox-exporter prometheus` — 탐침 컨테이너가 새로 뜨고 Prometheus 가 새 규칙 · 작업을 읽습니다.
- **확인**(Prometheus):
  - `probe_success{job="site-probe"}` 두 줄이 1 — 0 이면 `probe_http_status_code` 로 이유(403 이면 Cloudflare 봇 차단, §2-6)
  - 배포 뒤 `count(http_server_requests_seconds_bucket{le="0.3"})` > 0
  - `slo:sli_error:ratio_rate1h` 에 SLO 넷(손님이 없으면 서비스 쪽 지연 SLI 는 비어 있을 수 있다)
  - 15분 뒤 `slo:budget_used:ratio14d`(처음에는 데이터가 짧아 크게 흔들린다 — 14일이 쌓이기 전 절반 경보는 요청 하한이 막는다)
  - `count by (service) (kafka_consumer_fetch_manager_records_lag_max)` 에 Kafka 를 쓰는 서비스

---

## 6. 남은 것

1. **천장 측정** — 실습 클러스터에서 `ceiling browse` · `ceiling order` 를 돌려 §4-1 을 채운다. 그 표가 docs/89 단계 2 · 3 · 4 의 크기를 정한다.
2. **노드 밖 감시** — 노드가 통째로 죽으면 지금의 경보는 아무것도 울리지 않는다(§2-6). Cloudflare Health Checks 나 외부 가동 감시 서비스 하나를 두는 것은 계정 · 비용 판단이라 아직 하지 않았다.
3. **예산 창 30일** — 보존을 31일로 올릴지(디스크 확인 뒤, §2-4).
4. **목표 값으로 올리기** — docs/89 단계 1 · 2 를 마치면 `slo:objective:availability` 를 주석의 목표로.
5. **대시보드** — SLO 패널(남은 예산 · 소진율 · 탐침)은 Grafana 에 아직 없다.
