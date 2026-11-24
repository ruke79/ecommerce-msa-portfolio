# 91. SLO 와 용량 측정 — 이행 단계 0

[日本語](91-slo-and-capacity.md) | **한국어**

> 📝 **상태(2026-11-14) — SLO · 예산 규칙 · 처리량 목표를 정해 적용, 실측은 남음.** [docs/89](89-scale-out-migration-plan.ko.md) 단계 0(목표와 측정)의 레포 쪽 작업입니다. SLO 넷(§2)을 기록 규칙 · 소진율 경보로, 14일 오류 예산과 그 규칙(§2-4), 단계별 처리량 목표(§2-5), 손님 쪽 길을 보는 바깥 탐침(§2-6), 컨슈머 지연 지표(§3), 천장을 재는 `ops/53 ceiling`(§4). 남은 것은 실습 클러스터에서의 천장 측정과 노드 밖 감시입니다(§6).
>
> 📝 **2026-11-15 추가 — 다섯 번째 SLO `gateway`(§2-7)와 Grafana SLO 대시보드(§2-8).** 게이트웨이의 라우트 타이머(`spring.cloud.gateway.requests`)는 Spring Cloud Gateway 4.3 에서 기본 꺼짐이라 지금까지 없었습니다 — 회로 차단 폴백 503 · 연결 실패 · 다운스트림 시간 초과는 어느 지표에도 세어지지 않았습니다. 다음 배포(gateway 이미지 + common)부터 나옵니다.

## 0. 요약

| | 무엇 | 어디 |
|---|---|---|
| SLO 다섯 | `catalog-browse`(홈 · 상세) · `catalog-search`(목록 · 필터 · 이름 검색) · `order-create`(주문 생성) · `gateway`(게이트웨이를 지난 모든 라우트) · `site-probe`(바깥 탐침) — 지금(노드 한 대)의 값과 단계 1 · 2 뒤의 목표 | `observability/alert-rules.yml` 의 `slo` 그룹 |
| 게이트웨이 라우트 타이머 | `spring.cloud.gateway.requests`(routeId · httpStatusCode) — **4.3 은 기본 꺼짐이라 없었다**, 경계 버킷 0.3 · 1 · 2초만 | gateway `application-local.yml` 의 `spring.cloud.gateway.server.webflux.metrics` · `application-common.yml` |
| 지연 경계 버킷 | 히스토그램에 **정확한** 0.3 · 1.0 초 경계 — 기본 버킷에는 없어 "300ms 안의 비율" 을 셀 수 없었다 | `application-common.yml` 의 `management.metrics.distribution.slo` |
| 소진율 경보 | 다중 창 넷 — 빠름(1h · 5m, 14.4배, critical) · 느림(6h · 30m, 6배, warning), 요청이 적으면 울리지 않음 | `slo` 그룹 · promtool 시험 |
| 14일 오류 예산 | 예산 사용률 · 절반(warning) · 소진(critical) 경보와 그때의 규칙 | `slo-budget` 그룹 |
| 처리량 목표 | 단계 2 끝 초당 1,000 · 단계 3 끝 초당 5,000 | §2-5 · `ops/53 ceiling` 의 `CEILING_TARGET_RPS` |
| 바깥 탐침 | 운영 노드의 blackbox-exporter 가 Cloudflare 를 거쳐 첫 화면 · 홈 API 를 30초마다, 경보 `SiteProbeDown` | `docker-compose-observability.oci.yml` · `observability/blackbox.yml` · `prometheus.oci.yml` |
| 컨슈머 지연 | **지금까지 지표가 없었다** — 모든 컨슈머 팩토리에 Micrometer 리스너, 경보 `KafkaConsumerLagHigh` | common `KafkaConsumerMetricsAutoConfiguration` · `kafka-consumer` 그룹 |
| SLO 대시보드 | Grafana `ecommerce-msa SLO` — 남은 예산 · 소진율 · 게이트웨이 라우트 · 탐침 · 컨슈머 지연 | `observability/grafana/provisioning/dashboards/slo.json`(§2-8) |
| 천장 재기 | 도착률 계단마다 SLO 를 보고 깨지는 계단에서 멈춘다 — 서비스별 파드당 요청/초 · CPU · DB 커넥션 대기 · Kafka 지연, 처리량 목표와 견줌 | `./ops/53-load-k6.sh ceiling browse|order|mixed` |

---

## 1. 왜 지금

docs/89 는 "숫자 없이 다음 단계를 고르면 장비 · 관리형의 크기를 짐작으로 정하게 된다" 고 했습니다. 단계 0 을 시작해 보니 숫자를 낼 도구부터 비어 있었습니다.

- **SLO 가 레포 어디에도 없었다**(docs/86 §0). 있던 것은 서비스별 5xx 5% · p99 3초 경보뿐 — "목표를 얼마나 쓰고 있나" 가 아니라 "크게 터졌나" 만 본다.
- **p99 300ms 를 셀 수 없었다.** `percentiles-histogram` 의 기본 버킷에는 0.3 · 1.0 초 경계가 없어 `le="0.3"` 시계열이 없다(`SloRulesTest` 가 확인) — "300ms 안의 비율" 은 근삿값(`histogram_quantile`)으로만 나왔다.
- **컨슈머 지연 지표가 없었다.** Spring Boot 는 자기 자동 설정의 컨슈머 팩토리에만 Micrometer 리스너를 다는데, 이 레포의 서비스는 모두 팩토리를 직접 만든다(`KafkaConsumerConfig` · `KafkaDltConfig`).
- **서비스 쪽 지표는 손님 쪽 길을 보지 않는다** — Cloudflare · 터널 · 게이트웨이 · 프론트가 막혀도 catalog 의 5xx 는 0 이다.
- **게이트웨이는 라우트를 몰랐다**(2026-11-15 발견) — Spring Cloud Gateway 4.3 은 라우트 타이머가 기본 꺼짐이고, 게이트웨이의 `http.server.requests` 는 라우트 요청의 `uri` 가 모두 `UNKNOWN` 이다. 회로 차단 폴백(`/fallback` 503) · 연결 실패 · 다운스트림 시간 초과는 백엔드에 닿지 않으므로 서비스 쪽 지표에도 없다.
- **`ops/53` 은 한 번에 한 모양의 부하**를 걸었다 — 천장을 찾으려면 사람이 도착률을 바꿔 가며 여러 번 돌리고 Grafana 에서 숫자를 옮겨 적어야 했다.

---

## 2. SLO

| SLO | SLI 대상 | 가용성 지금 → 목표 | 지연 | 근거(docs/83 실측 등) |
|---|---|---|---|---|
| `catalog-browse` | catalog GET — 홈 · 상품 상세 · 배너 · 공지 · 기획전(`/api/search*` · 관리자 · 내부 · actuator 제외) | **99.5% → 99.9%** | **99% 가 300ms 안** | 실측 p99 22 ~ 25ms(1,000만 상품, 캐시 밖 상세 포함) — 운영 장비 · 캐시 밖까지 여유 |
| `catalog-search` | catalog GET `/api/search*` — 카테고리 목록 · 필터 · 이름 검색 | **99.0% → 99.5%**(ES 뒤 99.9%) | **95% 가 1초 안** | 필터 조합을 넓힌 8시간 회차 p95 1,513ms · 운영 200만의 드문 필터 조합은 5초 상한 503(docs/83 §2.8) — ES 전까지 남는 한계 |
| `order-create` | order `POST /api/order/create` | **99.5% → 99.9%** | **99% 가 1초 안** | 실측 p99 113ms(초당 5건 55분) — 재고 경합 · 쿠폰 · 장바구니 호출의 여유 |
| `gateway` | 게이트웨이를 지난 모든 라우트(`chat-service-ws` 제외, §2-7) | **99.5% → 99.9%** | **99% 가 2초 안** | 서비스 쪽이 못 보는 폴백 503 · 연결 실패 · 504 · 게이트웨이 자신의 시간(JWT · 유입 제한). 경계 2초는 모든 라우트를 한 SLO 로 보기 때문(검색은 5% 가 1초를 넘어도 된다) |
| `site-probe` | 바깥 탐침 — 첫 화면 · 홈 API(§2-6) | **99.5% → 99.9%** | **99% 가 2초 안** | Cloudflare · 터널 · 게이트웨이 · 프론트까지 한 번에 |

- **지금 → 목표**: 지금은 PostgreSQL · Redis · Kafka 가 `replicas: 1` 이고 노드가 한 대라(docs/89 §0) 노드 재부팅 · PG 파드 재생성 하나가 곧 전체 정지입니다. 99.9%(14일에 약 20분)는 유지보수 한 번에 예산을 다 씁니다 — 처음부터 걸면 첫 달부터 "예산 없음" 으로 시작합니다. 99.5%(14일에 약 1.7시간)는 노드 한 대에서 지킬 수 있는 선입니다. docs/89 단계 1 · 2 를 마치면 목표 값으로 올립니다(규칙 주석에 적었습니다).
- **검색을 따로 둔 이유**: 카테고리 목록 화면도 `/api/search` 를 씁니다. 드문 필터 조합의 503 은 이미 알려진 한계인데, 한 SLO 에 섞으면 그것이 **상품 · 홈의 예산까지 먹습니다**. 검색의 지연 목표는 꼬리가 길어 99% 가 아니라 95% 입니다(경계 1초는 이미 있는 버킷).
- **오류는 5xx 만**입니다. 4xx 는 손님 쪽(잘못된 요청 · 재고 부족 · 남의 자원 · 유입 제한 429)이라 예산에서 뺍니다.
- **서비스 쪽 SLO 의 지연은 서비스 쪽 시간**입니다(`http.server.requests`). 게이트웨이의 시간 · 폴백은 `gateway` 가, Cloudflare · 터널 · 망 시간은 `site-probe` 가 봅니다.
- **숫자를 바꾸는 곳은 한 곳**: `alert-rules.yml` 의 `slo:objective:availability` · `slo:objective:latency` 두 줄(SLO 마다). 규칙은 생성해서 붙였으므로 SLO 를 더하거나 선택자를 바꿀 때는 같은 모양으로 넷(창) × 둘(SLI) 을 함께 고칩니다. 지연 **경계**를 바꾸면 `application-common.yml` 의 `slo` 버킷 · 규칙의 `le` · `ops/53` 의 `CEILING_P99_MS` 기본값도 — 가드 `SloRulesTest` 가 맞춰 봅니다.

### 2-1. 기록 규칙

SLO 마다, 창 넷(5m · 30m · 1h · 6h)마다:

| 기록 이름 | 서비스 쪽 SLO · `gateway` | `site-probe` |
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

- `observability/tests/slo-rules.test.yml`(promtool, 1분 간격): browse 10% 5xx 에서 빠름 · 느림 · 2분 안에는 아직 · 0.05% 는 예산 안 · 초당 0.01 이면 33% 실패여도 조용 · 주문 절반이 1초를 넘으면 지연 빠름, 10% 면 지연 느림만 · 관리자 · actuator 는 어디에도, `/api/search` 는 search 에만 · search 40% 가 1초를 넘으면 지연 느림만 · search 8% 5xx 는 search 의 느림만(browse 의 예산은 0) · 탐침 하나가 실패하면 `SiteProbeDown`(3분) 과 site-probe 빠름 · 탐침 실패 비율 값 · 탐침이 3초면 지연 빠름 · 컨슈머 지연 1,500 이 10분 · gateway: 폴백 503 10% 면 gateway 의 오류 빠름 · `chat-service-ws` 가 2초를 넘어도 지연 예산은 0 · 429 · 401 이 절반이어도 조용 · 10% 가 2초를 넘으면 지연 느림만.
- `observability/tests/slo-budget.test.yml`(promtool, 지표 10분 간격 15일 · 평가는 그룹과 같은 5분 — 다르면 그룹 평가 순서가 흔들려 경보가 가끔 빠졌다): 0.4% 5xx 가 15일이면 예산 80% — 절반만, 0.6% 면 120% — 소진(절반도), 요청이 적으면 조용.
- 실행: `SloRulesPromtoolTest`(컨테이너 — 운영과 같은 `prom/prometheus:v2.53.0`, Docker Hub 가 막히면 `PROMETHEUS_IMAGE=mirror.gcr.io/prom/prometheus:v2.53.0`).
- `SloRulesTest` — 버킷 `le="0.3"` · `le="1.0"` 이 노출되는지(기본 버킷만으로는 없다), SLO 마다 목표의 경계를 그 SLO 의 지연 SLI 가 읽는지, SLO 넷의 목표 · SLI · 증가분 · 경보, browse 가 검색을 빼는지, 14일 창 < 운영 Prometheus 보존, 탐침 배선(판 · 포트 없음 · TLS 검증 · 운영 주소 · 모듈 · 30초), gateway(라우트 타이머의 `le="2.0"` 버킷 · 경계 버킷만 · 게이트웨이 설정의 `metrics.enabled` · 규칙이 빼는 라우트가 게이트웨이에 있는 SockJS · WebSocket 라우트뿐인지), 대시보드(읽는 기록 규칙 · 경보 이름이 alert-rules.yml 에 있는지).
- gateway `GatewayRouteMetricsTest` — 실제 게이트웨이 컨텍스트로 가짜 catalog 의 200 · 503 을 지나 보내고, `spring.cloud.gateway.requests{routeId, httpStatusCode}` 와 2초 버킷이 생기는지.
- 변이 확인: 요청 하한 제거 · 경계 `le` · `or vector(0)` 제거 · 관리자 경로 포함 · 검색을 browse 에 · 절반 기준 0.9 · 예산 하한 제거 · 탐침 실패율 뒤집기 · `SiteProbeDown` 의 `for` 제거 → promtool 시험 실패, yml 경계 250ms · 탐침에 포트 → `SloRulesTest` 실패. gateway(2026-11-15): 빼는 라우트 제거 · 4xx 를 오류로 → promtool 실패, `metrics.enabled: false` · 게이트웨이 버킷 줄 삭제 · 빼는 라우트 이름 틀림 · 규칙 `le` 1.5 → `SloRulesTest` 실패, `metrics.enabled: false` · 버킷 줄 삭제 → `GatewayRouteMetricsTest` 실패.

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
- 대상 둘 — `https://www.ysyproject.com/`(모듈 `http_page`: 200 · TLS · 본문에 `<div id="root">`), `https://www.ysyproject.com/catalog-service/api/content/home?probe=site-probe`(모듈 `http_api`: 200 · `Content-Type: application/json` · 본문이 JSON). 상태 코드만 보면 Cloudflare 오류 · 챌린지 화면이나 프론트로 샌 API(index.html 200)도 성공이 된다. TLS 는 검증한다.
- 경보 `SiteProbeDown` — 대상 하나가 3분 연속 실패(critical). 소진율 경보는 `slo="site-probe"` 로 같은 규칙을 탄다.
- ⚠️ **한계**: 탐침은 운영 노드에서 나간다 — **노드가 통째로 죽으면 Prometheus · Alertmanager · 탐침이 함께 죽어 아무것도 울리지 않는다.** 그 경우는 노드 밖 감시(Cloudflare Health Checks · 외부 가동 감시 서비스 등)가 따로 필요하다(§6). 또 Cloudflare 의 봇 차단이 탐침을 막으면 403 · 챌린지로 실패한다 — `probe_http_status_code` 를 보고, 그러면 그 경로를 봇 규칙에서 뺀다.

### 2-7. 게이트웨이(`gateway`)

- **SLI**: `spring_cloud_gateway_requests_seconds{service="gateway-server", routeId!="chat-service-ws"}` — 게이트웨이의 `GatewayMetricsFilter` 가 라우트를 지난 요청마다 남긴다(태그 `routeId` · `routeUri` · `httpStatusCode` · `httpMethod` · `outcome`). 오류는 `httpStatusCode=~"5.."`, 지연은 `le="2.0"` 을 넘은 비율.
- **켜기**: `spring.cloud.gateway.server.webflux.metrics.enabled: true`(gateway `application-local.yml` — 4.3 은 기본 false). path 태그는 켜지 않는다(요청 경로마다 시계열). 버킷은 `application-common.yml` 의 `slo` 에 `spring.cloud.gateway.requests: 300ms,1s,2s` — **percentiles-histogram 은 켜지 않는다**(라우트 × 상태 코드 × 메서드마다 버킷 70여 개가 된다). 그래서 이 타이머로는 p99 를 근사할 수 없다 — 라우트별 p99 는 서비스 쪽 `http_server_requests` 와 Jaeger 로 본다.
- **보는 것**: 서비스 쪽 SLO 가 못 보는 것 — 회로 차단 폴백(`/fallback` 503, 격벽 · 회로 열림 · 10초 시간 상한), 연결 실패(Retry 를 다 쓴 뒤), 다운스트림 시간 초과, 게이트웨이 자신의 시간(JWT 검사 · 유입 제한의 Redis). 범위는 모든 라우트 — 장바구니 · 쿠폰 · 사용자 · 결제 웹훅까지 처음으로 SLO 안에 들어온다.
- **뺀 것**: `chat-service-ws`(`/chat-service/chat/**` — SockJS 의 xhr-streaming · WebSocket 은 연결이 끝날 때까지 한 요청이라 지연 예산을 다 쓴다). 가드 `SloRulesTest` 가 빼는 라우트가 게이트웨이에 있고 그 경로인지 본다 — 라우트 이름을 바꾸면 조용히 SLO 에 들어오지 않게. 4xx(유입 제한 429 · 인증 401 · 403)는 다른 SLO 처럼 예산 밖.
- **경계 2초 · 99%**: 모든 라우트를 한 SLO 로 보므로 가장 느슨한 서비스 SLO 에 맞췄다 — `catalog-search` 는 5% 가 1초를 넘어도 되므로 1초 경계면 검색이 많은 날 게이트웨이 예산을 먹는다. 가용성은 다른 SLO 와 같이 지금 99.5% → 목표 99.9%.
- **겹침**: 백엔드의 5xx 는 서비스 쪽 SLO 와 `gateway` 에 함께 센다(같은 사고에 경보 둘). 경보 묶음(`slo` 라벨)이 달라 둘 다 오지만, `gateway` 에만 오면 원인이 게이트웨이 쪽(폴백 · 연결 · 시간 상한)이다.

### 2-8. 대시보드(Grafana `ecommerce-msa SLO`)

- 파일 `observability/grafana/provisioning/dashboards/slo.json`(uid `slo`, 폴더 `ecommerce-msa`) — 관측 스택의 Grafana 가 프로비저닝으로 읽는다. 변수 `slo`(`label_values(slo:objective:availability, slo)`, 여럿 · 전체).
- 패널: 14일 오류 · 지연 예산 남음(stat — 50% 노랑 · 0 빨강, 규칙 §2-4), 목표 · 1시간 평균 요청(경보 하한 0.03), 오류 · 지연 소진율(1h · 5m 를 예산으로 나눈 값 — 14.4 · 6 선), 6시간 오류 · 느림 비율(예산을 어느 날 썼나), 울리는 SLO · 탐침 · 컨슈머 경보 표, 게이트웨이 라우트별 요청 · 5xx 비율 · 2초 넘은 비율 · 2xx 밖 상태 코드, 탐침 성공 · 시간 · 상태 코드, 서비스 · 토픽별 컨슈머 지연.
- 패널은 기록 규칙(`slo:*`)을 읽는다 — 규칙 이름을 바꾸면 패널이 빈다. 가드 `SloRulesTest` 가 대시보드가 읽는 `slo:*` 이름 · 경보 이름이 alert-rules.yml 에 있는지 본다. 2026-11-15 에 Prometheus v2.53.0 · Grafana 11.1.0 으로 쿼리 20개가 모두 오류 없이 도는 것과 프로비저닝을 확인했다(가짜 지표).

### 2-9. 앞단 캐시(CDN) — 서버가 표시하고 Cloudflare 는 따르기만(2026-11-15)

- **왜 여기서**: 처리량 목표(§2-5)의 단계 3(초당 5,000)은 읽기의 대부분을 앞단이 받는다는 전제다(docs/89 §4). 사이트는 이미 Cloudflare 뒤라 CDN 을 따로 붙일 필요는 없다 — 막고 있던 것은 **원본이 아무 표시도 하지 않는다**는 것이었다(정적 파일 `/static/` 만 1년 `immutable`).
- **표시**(catalog `EdgeCacheHeaders`): catalog 의 모든 API 응답은 기본 `Cache-Control: no-store`, 아래 공개 GET 이 **200** 으로 답할 때만 `public, max-age=0, s-maxage=30, stale-while-revalidate=30`.
  - 대상 열넷(게이트웨이 재작성 뒤 경로): `/api/content/{home, banners, notices, notices/{id}, events, events/{slug}}` · `/api/category/{categories, product/allsubcategories, product/subcategories}` · `/api/product/{frontpage, products, {slug}}` · `/api/search` · `/api/search/category`. 모두 `User-Id` · 쿠키 · 언어를 보지 않는다(가드가 컨트롤러 시그니처로 본다).
  - 빼는 것: 서비스 사이 조회(`/api/{id}` · `products/info` · `cart/*` · `stock` · `color*`), 관리자(`/api/content/admin/**`), 내부(`/api/internal/**`), 기획전 쿠폰 제안, POST, 오류(드문 필터 503 을 30초 동안 모두에게 주지 않게), `probe` 쿼리가 붙은 요청.
  - `max-age=0` — 브라우저는 지금처럼 매번 받는다(관리자가 고친 것 · 재고가 새로고침에 바로 보인다). `s-maxage=30` — 앞단만 30초(첫 화면 파드 메모리 캐시 `HomeViewCache` 와 같은 크기). 재고 표시는 그만큼 늦을 수 있다 — 주문 · 결제는 서버가 재고를 다시 본다.
  - 끄기: `catalog.edge-cache.enabled=false`(모두 no-store), 수명은 `catalog.edge-cache.s-maxage` · `stale-while-revalidate`.
- **Cloudflare 규칙**(배포 뒤 · 사용자 작업 — docs/85 §9-4): Cache Rules 하나 — 호스트 `www.ysyproject.com` 이고 경로가 `/catalog-service/api/` 로 시작하면 "Eligible for cache", Edge TTL 은 "원본의 Cache-Control 을 따르고 없으면 우회", Browser TTL 은 원본을 따름. 규칙을 끄면 바로 원래대로.
- **SLO 에 주는 영향**
  - 캐시에서 나간 응답은 서비스 쪽 SLI(`http_server_requests`)와 `gateway` SLI 에 남지 않는다 — 규칙을 켠 뒤 그 SLI 는 "캐시를 지나친 요청" 만 본다(요청 수가 줄어 요청 하한 0.03 아래로 갈 수 있다).
  - 바깥 탐침의 API 대상은 `?probe=site-probe` 로 캐시를 지나친다 — 쿼리가 다르면 앞단의 캐시 키도 다르고, 원본은 그 요청에 no-store 를 단다. 빼면 원본이 죽어도 캐시의 200 으로 탐침이 성공한다(가드가 대상의 `probe=` 를 본다).
  - `stale-while-revalidate` — 만료 직후에는 옛 응답을 주며 뒤에서 새로 받는다. 원본이 잠깐 느려도 손님은 기다리지 않는다.
- **시험**: catalog `EdgeCacheHeadersTest`(7) — 목록 GET 200 은 public 한 줄, 목록 밖 · POST · 오류 · 탐침 · 본문 없는 응답은 no-store, 끄면 no-store, 수명 설정, **목록의 경로가 실제 GET 이고 그 핸들러가 헤더 · 쿠키 · 요청 객체 · Principal · Locale 을 읽지 않음**, 관리자 · 내부 · 재고 · 장바구니 · 서비스 조회는 목록 밖, `WebMvcConfig` 등록. `SloRulesTest` — API 탐침 대상의 `probe=`. 변이 확인: 재고를 목록에 · 오류도 public · 탐침도 public · 기본 no-store 제거 · 등록 제거 · POST 도 public → `EdgeCacheHeadersTest` 실패, 탐침 대상의 `?probe=` 제거 → `SloRulesTest` 실패.

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
- **사전 점검 — ES 색인**(browse · mixed, 2026-11-23): 이름 검색이 ES 로 가는 구성(`SEARCH_ENGINE=es` 이고 플래그 `catalog.search-engine` 의 es 비율 > 0, 또는 `ops/53 search es` 로 플래그 파일을 비운 배포)인데 별칭 `product-alias` 가 비었으면 k6 를 부르기 전에 멈춥니다. 색인이 없으면 catalog 는 경고 한 줄과 함께 PostgreSQL `LIKE` 로 폴백하고, 그 지연이 천장이 됩니다 — 실습(상품 1,000만)에서 재색인을 빠뜨린 채 재니 catalog CPU 20% 인 첫 계단(10)이 이름 검색 p99 2.4초 때문에 p99 490ms 로 깨졌습니다(다른 요청은 모두 p99 60ms 아래). 이름 검색 비율은 browse.js 와 같이 `NAME_SEARCH`, 없으면 데이터 규모 모드(`PRODUCTS`)에서 0.2. 색인 문서가 `PRODUCTS` 보다 적으면 경고만. 재색인은 `ops/03-enable-es-search.sh`(docs/13 §3), 이름 검색 없이 재려면 `NAME_SEARCH=0`, 건너뛰려면 `CEILING_ES_CHECK=0`. 플래그를 읽지 못하면 확인하는 쪽으로 갑니다. 시험 `LoadK6ScriptTest` +1(빈 색인이면 k6 전 거절 · mixed 도 · 배포 env 가 ConfigMap 보다 앞섬 · 플래그 파일을 비우면 ES · 색인이 있으면 통과 · 상품 수보다 적으면 경고 · `NAME_SEARCH=0` · postgres · es 0% · 건너뛰기 · 규모 모드가 아니면 확인 없음 · 플래그를 못 읽으면 확인) — 변이 7가지(호출 제거 · mixed 빼기 · 플래그 파일 분기 · 배포 env 무시 · 못 읽으면 건너뜀 · 규모 모드 기본값 · 엔진 확인 제거) 모두 실패.
- **처리량 목표**: `CEILING_TARGET_RPS`(browse · mixed 기본 1,000 — §2-5) — ceiling.md 머리에 "넘는다 · 못 미친다(목표의 몇 %) · 아직 모른다" 를 적습니다.
- **서버 쪽**(`PROM` 이 있으면 계단 동안): 서비스별 요청/초 · 파드 수(→ **파드당 요청/초**) · CPU 평균 · DB 커넥션 대기 최대 · Kafka 지연 최대. "막힌 곳 후보" 는 CPU 80% 이상 · 대기 > 0 · 지연 > 1,000.
- 결과: `load-results/ceiling-<시나리오>-<시각>/ceiling.md`(계단 표 + 서비스별 표), 계단마다의 회차 폴더는 `steps/<계단>/`.
- 시험 `LoadK6ScriptTest`(가짜 k6 · 가짜 Prometheus): 200 에서 멈추고 400 은 돌리지 않는다 · 처리량 목표와 견줌(못 미침 · 넘음 · order 는 없음) · order 는 order_create p99 · 5xx · 부하기 모자람 · 운영 주소 · STAGES · SPLIT · 줄어드는 계단 거절. 변이 확인: 멈추지 않기 · 부하기 판정 제거 → 실패.

### 4-1. 천장 표(단계 0 의 끝난 조건 — 측정하면 채운다)

| 서비스 | 파드당 요청/초(SLO 안) | 먼저 막힌 곳 | 시나리오 · 계단 | 날짜 · 커밋 · 환경 |
|---|---|---|---|---|
| catalog-service | **174.6**(파드 1, 계단 40 — CPU 35% · p99 16ms) | 아니다 — 계단 80(369 요청/초)에서도 CPU 67% · DB 풀 대기 0 · 오류 0 | browse · 5,10,20,40,80 | 2026-11-23 06:31 UTC · `f5c04e958cb7` · 운영(OCI Ampere 1 노드 + 원격 1 — frontend 만) |
| gateway-server | **174.6**(파드 1, 계단 40 — CPU 32%) | **catalog 격벽**(`catalogServiceCircuitBreaker` 동시 25 · 대기 0) — 계단 80 에서 가득 참 460건 → 5xx 0.813%. GC 아님(docs/95 §5-2) | browse · 5,10,20,40,80 | 2026-11-23 06:31 UTC · `f5c04e958cb7` · 운영(OCI Ampere) |
| order-service | — | — | order | — |
| cart-service | — | — | order | — |

참고로 지금까지의 실습 회차에서 catalog 파드 하나는 ~350 요청/초, 병목은 CPU(JSON 직렬화)였습니다(docs/83 · docs/89 §0 #9) — 그때는 SLO 없이 "임계값 통과" 로 판정했습니다. 실습 클러스터의 절대값은 코어를 나눠 쓴 값이라 크기 산정에는 비율 · 병목의 순서만 씁니다(docs/83).

### 4-2. 운영 읽기 천장(2026-11-15 — 준비 끝, 실행은 운영 노드에서)

실습 클러스터의 수치는 코어를 나눠 쓴 값이라 비율로만 쓴다. **지금 운영이 SLO 안에서 초당 몇 요청을 받나**는 운영 노드에서만 나온다 — 그래서 읽기(비회원 browse)만, 클러스터 안에서, 상한을 두고 잰다.

| 무엇 | 왜 |
|---|---|
| **browse 만** | order · mixed · contention 은 주문 · 재고 · 쿠폰 · 리뷰를 운영 DB 에 남긴다. 대량 사용자(`ops/48`)도 필요 없다 |
| **클러스터 안에서 게이트웨이로 바로**(`https://gateway-server.ecommerce.svc.cluster.local:8000`) | Cloudflare 를 거치면 엣지가 `CF-Connecting-IP` 를 덮어써 한 IP 의 유입 제한(초당 20)만 잰다. 클러스터 안에서는 게이트웨이가 그 헤더를 믿으므로 **손님 IP 흉내(`CLIENT_IPS`)로 버킷이 나뉜다** — 운영 제한 설정을 바꾸지 않는다 |
| **k6 를 Job 으로**(`K6=ops/tools/k6-job.sh`) | 노드에서는 클러스터 주소가 풀리지 않는다. Job 은 `grafana/k6:0.54.0`(arm64 있음) · CPU 1 · 1Gi, 시나리오는 ConfigMap, TLS 는 `tls-keystore` 의 공개 CA(`ca.crt`)만으로 검증. 같은 네임스페이스라 NetworkPolicy(k8s/95)도 연다 |
| **상한** | 도착률 ≤ `PROD_MAX_RATE`(기본 200 반복/초), SLO 가 깨지는 첫 계단에서 멈춘다(ceiling) |
| **시간** | 02:45 ~ 05:15 UTC(백업 03:00 · Mongo 04:00 · 원장 정리 04:40 ~)는 거절. 손님이 가장 적은 시간에 |
| **경보** | 측정 동안 Alertmanager 묵음(SLO · 탐침 · 5xx · p99 · 지연 · 회로 차단기)을 스스로 걸고 끝나면 푼다. 못 걸면 멈춘다(손으로 걸었으면 `SILENCED=1`) |
| **서버 지표** | 운영 Prometheus(노드 `localhost:9090`)에는 `environment` 라벨이 없다 — 선택자는 `job="spring-actuator"`(`PROM_MATCH` 로 바꿀 수 있다) |

```bash
# 운영 노드에서 — 저장소를 측정할 커밋으로 맞춘 뒤(git switch --detach <SHA>), kubectl 컨텍스트는 운영
PROD_READONLY=1 K6=ops/tools/k6-job.sh \
GATEWAY=https://gateway-server.ecommerce.svc.cluster.local:8000 CLIENT_IPS=2000 \
PROM=http://localhost:9090 ALERTMANAGER=http://localhost:9093 \
CEILING_RATES=5,10,20,40,80,160 CEILING_HOLD=2m \
  ./ops/53-load-k6.sh ceiling browse
./ops/53-load-k6.sh report        # 결과: load-results/ceiling-browse-<시각>/ceiling.md
```

- 운영 클러스터(ConfigMap 의 `APP_PUBLIC_URL` 이 운영 주소)에서는 **이 모드로만** 돈다 — `PROD_READONLY=1` 이 없거나 위 조건 하나라도 어기면 k6 를 부르기 전에 멈춘다. 보통 회차(`RATE=` 하나)도 같은 조건이고 묵음을 건다.
- 데이터 규모 모드로 캐시 밖까지 보려면 `PRODUCTS=<운영 상품 수> PRODUCT_ID_OFFSET=<첫 id>`(운영은 데모 생성기의 200만 — id 범위를 먼저 확인) · `SPREAD=1`. 없으면 홈에 나온 상품만 읽어 대부분 캐시에서 나온다 — 그것도 "운영의 읽기 천장" 의 한 면이다.
- 해석: k6 Job 은 서버와 같은 노드의 CPU 1 개를 쓴다 — 천장은 그만큼 보수적이다(ceiling.md 의 서비스별 CPU 로 본다). 터널 · Cloudflare 시간은 빠진다(그것은 `site-probe` 가 본다).
- 시험: `LoadK6ScriptTest` — 운영에서 거절 8가지 · 묵음을 한 번 걸고 푼다 · 지표 선택자 · 묵음 실패 · `k6-job.sh`(판 고정 · 공개 CA 만 · 비루트 · 종료 코드 · 정리 · 비밀 거절). 변이 확인 5가지.

### 4-3. GC — 측정 배선(2026-11-15)

- **왜**: 문서에 GC 측정 계획이 없었다(docs/19 의 "GC 튜닝 — 지속 부하가 필요" 한 줄). 실습 포화 단계에서 게이트웨이 파드 하나가 GC 부담 12 ~ 17% 였다는 관측(docs/83)은 Grafana 에서 손으로 본 것이고, `ops/53` 에는 GC 지표가 하나도 없었다.
- **파드가 쓰는 GC**: 매니페스트의 CPU 한도는 1 코어 · 메모리 768Mi(catalog 1Gi)다. **JDK 17 은 코어가 2 미만이면 G1 대신 SerialGC 를 고른다**(같은 조건으로 확인 — 1 코어 · 768Mi → SerialGC · 힙 384MB, 1Gi → SerialGC · 512MB, 2 코어 → G1). `k8s-overlays/oci`(2 OCPU 용)는 `-XX:+UseSerialGC` 를 명시한다. SerialGC 는 한 스레드가 전부 멈추고 치우므로 GC 정지가 그대로 꼬리 지연에 더해진다. 운영이 어느 쪽인지는 배포 뒤 `count by (service, gc) (jvm_gc_pause_seconds_count)` 로 본다 — `gc` 가 `Copy` · `MarkSweepCompact` 면 Serial, `G1 …` 이면 G1.
- **지표**(Micrometer 가 이미 낸다 — 코드 변경 없음): `jvm_gc_overhead`(지난 5분 중 GC 정지 비율, 0 ~ 1) · `jvm_gc_pause_seconds_{count,sum,max}`(라벨 `gc` · `action` · `cause`) · `jvm_gc_memory_allocated_bytes_total`(할당) · `jvm_memory_usage_after_gc{area="heap",pool="long-lived"}`(GC 뒤 오래 사는 영역의 사용 비율).
- **`ops/53 ceiling`**: 서비스별 표에 **GC 종류 · GC 부담(최대) · GC 정지(최대 ms) · 파드당 할당 MB/s · GC 뒤 힙(최대)** 열, 막힌 곳 후보에 **GC 부담 ≥ `CEILING_GC_OVERHEAD`(0.10)** · **GC 뒤 힙 ≥ 90%**. GC 부담은 지난 5분 창이라 계단(유지 3분)이 짧으면 앞 계단의 몫이 섞인다 — 경계 계단은 `CEILING_HOLD=5m` 로 다시 잰다.
- **경보**(`jvm-health` 그룹): `JvmGcOverheadHigh`(서비스의 GC 부담 > 10% 가 10분) · `JvmHeapAfterGcHigh`(GC 뒤 힙 > 90% 가 15분 — 쓰는 중의 힙을 보는 `JvmHeapNearLimit` 와 달리 GC 직전의 쓰레기를 세지 않는다).
- **대시보드**: `ecommerce-msa SLO` 의 "JVM · GC" 줄 — GC 종류 표 · GC 부담 · GC 뒤 힙 · 정지 최대 · 파드당 할당 속도. 경보 표에 두 경보도.
- **GC 로그**(켜지 않는다 — 필요할 때 그 서비스만): `JAVA_OPTS` 에 `-Xlog:gc*:file=/var/log/jvm/gc.log:time,uptime,level,tags:filecount=3,filesize=10m` — `/var/log/jvm` 은 이미 emptyDir(B-97)이라 컨테이너가 다시 떠도 남는다. 꺼내기는 `kubectl -n ecommerce cp <파드>:/var/log/jvm/gc.log ./gc.log`.
- **시험**: `LoadK6ScriptTest` +2(GC 열 · 후보 — Copy · MarkSweepCompact 를 Serial 로, 할당 ÷ 파드 · 문턱 설정과 검사), promtool `observability/tests/jvm-rules.test.yml`(GC 부담 10분 전은 조용 · 지나면 울림 · 잠깐 튄 GC 는 조용 · GC 뒤 힙 15분 · 기존 `JvmHeapNearLimit` 도), `SloRulesTest` 가 `jvm-health` 의 모든 경보가 promtool 시험에 있는지 본다. 변이 확인: GC 후보 · 종류 매핑 · 문턱 검사 · 문턱 무시 · GC 뒤 힙 후보 제거 → `LoadK6ScriptTest` 실패, 경보 문턱 0.2 · `for` 1분 · GC 뒤 힙 0.99 → promtool 실패, 경보 이름 바꿈 → `SloRulesTest` 실패.
- **다음**(로드맵): ① 배포 뒤 운영의 GC 종류 · 힙 · 평시 GC 부담을 이 절에 적는다 → ② 실습 천장(§4-1)에서 계단마다 GC 열로 "CPU 냐 GC 냐" 를 가른다 → ③ 천장의 80% 고정 부하로 게이트웨이 · catalog 의 Serial(1 코어) · Parallel(1 코어) · G1(2 코어) · 힙 50/60/70% 를 견준다(p99 · 파드 CPU 당 처리량 · GC 부담 · 정지 최대, 할당이 많은 곳은 `ops/58` 의 할당 모드) — 게이트웨이 한 파드만 튀던 GC 부담(docs/83)도 여기서 → ④ JDK 21 의 Generational ZGC 는 jdk21-build 브랜치를 합칠 때.
- **도구**(2026-11-17): 로드맵 ① 은 `ops/60-jvm-facts.sh`(파드마다 JVM 이 고른 GC · 힙 · 스레드), ③ 의 설정 변경은 `./ops/53-load-k6.sh jvm load <서비스…> gc=… heap=… cpu=… gclog=1` → `jvm default`(원래 값을 Deployment 주석에 두고 되돌린다 — 손으로 `kubectl set env` 하지 않는다). 시나리오 · 판정은 [docs/92](92-jvm-measurement-scenarios.ko.md) §4-2 X-2 ~ X-5.
- **운영 GC 종류 — 실측 끝(2026-11-19, `64b7c7a38cb2` 배포 뒤)**: `count by (service, gc) (jvm_gc_pause_seconds_count)` 가
  모든 서비스에서 **`Copy`** 입니다 — 즉 **SerialGC**(`Copy` + `MarkSweepCompact` 가 Serial 의 두 수집기 이름). 1 코어 ·
  768Mi 파드에서 JVM 이 Serial 을 고른다는 §4-3 의 전제가 운영에서 확인됐습니다. 디스크는 `/` 75%(25G 여유) — 예산 창을
  30일로 늘리는 판단(§2-4)의 근거 값입니다. 다음은 `ops/61-gc-baseline.sh` 로 며칠 평균 · 최대를 받아 이 표를 채우는 것.
- **운영 기준값 — 로드맵 ①(2026-11-17, 도구 준비 · 값은 운영 노드에서 실행한 뒤)**: 한 번 찍은 `jvm_gc_overhead` 는 "지난 5분" 이라 평시가 아니다 — 며칠의 평균 · 최대를 본다. `ops/61-gc-baseline.sh` 는 Prometheus 를 **읽기만** 하는 도구(파드 · 클러스터에 아무것도 하지 않는다)라 운영 노드에서 그대로 돈다:

  ```bash
  PROM=http://localhost:9090 ./ops/61-gc-baseline.sh
  ```

  창은 기본 7일(`WINDOW=24h` 등, 보존 15일이라 14d 까지), 선택자는 `MATCH`(기본 `job="spring-actuator"` — ops/53 의 `PROM_MATCH` 와 같다). 결과 `~/.ecommerce-msa/gc-baseline/<시각>/gc-baseline.md`(표) · `raw/<열>.json`(응답 그대로). 열: GC 종류(파드마다 다르면 '섞임') · 최대 힙 · GC 부담 평균 / 최대 · GC 정지 최대 · 파드당 GC 횟수/분 · 파드당 할당 MB/s · 요청당 할당 KB · GC 뒤 힙 최대, '눈여겨볼 것' 에 GC 부담 최대 ≥ 10%(경보 문턱) · GC 뒤 힙 ≥ 90% · 정지 ≥ 500ms. GC 종류는 `ALLOW_PROD=1 ./ops/60-jvm-facts.sh`(docs/92 F-4)의 사실 표와 같아야 한다. 시험 `GcBaselineScriptTest`(+4 — 표 계산 · 섞임 · 창과 선택자 · 입력 검사 · 실패 종료; 변이 확인: 파드 나눗셈 · Serial 매핑 · 창 상한 · 선택자 검사 · 전부 실패 종료 · 문턱).

  | 날짜 · 창 · 운영 커밋 | 서비스 | 파드 | GC | 최대 힙 | GC 부담 평균 / 최대 | 정지 최대 ms | 파드당 할당 MB/s | 요청당 할당 KB | GC 뒤 힙 최대 | 메모 |
  |---|---|---:|---|---:|---:|---:|---:|---:|---:|---|
  | — | (실행하면 `gc-baseline.md` 의 줄을 옮긴다) | | | | | | | | | |

---

## 5. 적용 · 확인

- **이미지**: 버킷 · 컨슈머 지표는 common 이라 다음 배포의 모든 백엔드 이미지에 들어갑니다(동작 변화 없음 — 지표만 늘어난다. 버킷은 `http.server.requests` 시계열마다 둘). 게이트웨이 라우트 타이머(§2-7)는 gateway 이미지 — 라우트 × 상태 코드 × 메서드마다 시계열 여섯(버킷 넷 · count · sum).
- **대시보드**(§2-8): Grafana 의 파일 프로비저닝은 폴더를 주기적으로 다시 읽으므로(기본 10초) 이 커밋 이후로 switch 하면 나타난다 — 재시작은 필요 없다. 보이지 않으면 `docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml restart grafana`.
- **관측 스택**(운영 노드, 두 파일): `docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d blackbox-exporter prometheus` — 탐침 컨테이너가 새로 뜨고 Prometheus 가 새 규칙 · 작업을 읽습니다.
- **확인**(Prometheus):
  - `probe_success{job="site-probe"}` 두 줄이 1 — 0 이면 `probe_http_status_code` 로 이유(403 이면 Cloudflare 봇 차단, §2-6)
  - 배포 뒤 `count(http_server_requests_seconds_bucket{le="0.3"})` > 0
  - `slo:sli_error:ratio_rate1h` 에 SLO 넷(손님이 없으면 서비스 쪽 지연 SLI 는 비어 있을 수 있다)
  - 15분 뒤 `slo:budget_used:ratio14d`(처음에는 데이터가 짧아 크게 흔들린다 — 14일이 쌓이기 전 절반 경보는 요청 하한이 막는다)
  - `count by (service) (kafka_consumer_fetch_manager_records_lag_max)` 에 Kafka 를 쓰는 서비스
  - 배포 뒤 `count by (routeId) (spring_cloud_gateway_requests_seconds_bucket{le="2.0"})` 에 라우트들 · `slo:sli_error:ratio_rate1h{slo="gateway"}`
  - GC(§4-3): `count by (service, gc) (jvm_gc_pause_seconds_count)`(Serial 인지 G1 인지) · `max by (service) (jvm_memory_max_bytes{area="heap"})` · `max by (service) (jvm_gc_overhead)` 를 §4-3 에 적는다

---

## 6. 남은 것

1. **천장 측정** — 실습 클러스터에서 `ceiling browse` · `ceiling order` 를 돌려 §4-1 을 채운다. 운영의 읽기 천장은 §4-2(준비 끝 — 운영 노드에서 실행). 그 표가 docs/89 단계 2 · 3 · 4 의 크기를 정한다.
2. **노드 밖 감시** — 노드가 통째로 죽으면 지금의 경보는 아무것도 울리지 않는다(§2-6). Cloudflare Health Checks 나 외부 가동 감시 서비스 하나를 두는 것은 계정 · 비용 판단이라 아직 하지 않았다.
3. **예산 창 30일** — 보존을 31일로 올릴지(디스크 확인 뒤, §2-4).
4. **목표 값으로 올리기** — docs/89 단계 1 · 2 를 마치면 `slo:objective:availability` 를 주석의 목표로.
5. ~~**대시보드**~~ — 끝(2026-11-15, §2-8). 게이트웨이 SLI 도(§2-7).
6. **앞단 캐시 규칙 켜기** — 서버 표시는 끝(§2-9). 배포 뒤 Cloudflare 규칙을 켜고 `cf-cache-status: HIT` 를 본다(docs/85 §9-4).
7. **GC** — 측정 배선은 끝(§4-3). 운영의 GC 종류 기록(`ops/61` 준비 끝 — 운영 노드에서 실행) → 천장 표의 GC 열 → GC 비교 실험(§4-3 '다음').
