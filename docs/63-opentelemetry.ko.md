# 63. OpenTelemetry — OTel 데모의 관측성 구성을 적용한다

[日本語](63-opentelemetry.md) | **한국어**

> 작성일: 2026-09-27 · 갱신: 2026-09-27(꼬리 샘플링 · 영구 저장) · 상태: ✅ 적용(배포 대기)
>
> 참고: [OpenTelemetry 데모](https://github.com/open-telemetry/opentelemetry-demo)(ruke79/kafka 의 `demo-kafka-guide/` 가
> 그 위에서 작업한 것), ruke79/kafka 의 `compose.tracing.yaml` · `test/show-trace.sh`.

## 0. 요약

| | 전 (Brave · Zipkin) | 후 (OpenTelemetry) |
|---|---|---|
| 서비스 안의 추적기 | Micrometer Tracing + **Brave** 브리지 | Micrometer Tracing + **OpenTelemetry** 브리지 |
| 내보내기 | Zipkin 형식 → `zipkin:9411` | **OTLP/HTTP** → `otel-collector:4318` |
| 수집 · 가공 | 없음(저장소로 바로) | **OTel Collector** — 메모리 상한 · 자격 증명 가림 · 배치 · 분기 |
| 저장 · UI | Zipkin(인메모리 — 재시작하면 사라짐) | **Jaeger v2**(badger 파일, **7일 보관**) — 검색 · 서비스 지도 · **Monitor 탭(RED)** |
| 샘플링 | 머리 샘플링(요청 시작에 결정) | **꼬리 샘플링**(추적이 끝난 뒤 결정) — 오류 · 느린 요청 · 주문/결제/배송은 **모두**, 나머지 10% |
| 추적 → 지표 | 없음 | **span_metrics** 커넥터 → Prometheus(`traces_span_metrics_*`) — 샘플링 **전**의 전부로 센다 |
| 지표 → 추적 | 없음 | **exemplar** — 지연 그래프의 점을 누르면 그 추적 |
| 로그 ↔ 추적 | Loki ↔ Zipkin | Loki ↔ Jaeger(같은 방식) |
| 터미널에서 보기 | 없음 | `ops/41-show-trace.sh` — 추적을 나무로 |
| 파이프라인 경보 | 없음 | `tracing-pipeline` 그룹(Collector 다운 · 내보내기 실패 · 거절 · 샘플링 대기 칸 넘침) |

코드는 벤더 중립 API(`io.micrometer.tracing.*`)만 쓰고 있었기 때문에 **서비스 코드는 한 줄도 바뀌지 않았다.**
바뀐 것은 의존성 두 줄, 공통 설정 한 블록, 매니페스트의 환경 변수 하나, 그리고 관측 스택이다.

## 1. 구성

```
 k3s 파드(11개 서비스)                     노드의 docker compose (docker-compose-observability.yml)
 ─────────────────────                     ─────────────────────────────────────────────────────────
 Micrometer Tracing (OTel 브리지)
   │ OTLP/HTTP  http://$(HOST_IP):4318/v1/traces
   ▼
                                            otel-collector ── memory_limiter → transform/redact → redaction
                                              │  (스팬 전부)                      │
                                              │                                   └─▶ span_metrics ─▶ :8889 ◀─ Prometheus
                                              ▼                                                             │ exemplar
                                            tail_sampling → batch  (오류 · 느림 · 돈 경로 전부 + 나머지 10%)     │
                                              │ OTLP/gRPC                                                   │
                                              ▼                                                             │
                                            jaeger (badger · 볼륨 jaeger-data · 7일) ◀── Monitor 탭이 Prometheus 를 읽는다
                                              ▲                                                             ▼
                                              └──────────── Grafana (Jaeger · Prometheus · Loki 데이터소스) ─┘
                                                              ▲
                                            loki ◀── alloy ◀── /var/log/pods (로그의 traceId → Jaeger)
```

OTel 데모와 같은 모양이다 — 서비스는 Collector 하나만 알고, 저장 · 파생 지표 · 가림은 Collector 가 정한다.
데모와 다른 점:

- 데모는 Prometheus 의 OTLP 수신(`--web.enable-otlp-receiver`, Prometheus 3)으로 **밀어 넣는다.** 여기는 Prometheus
  2.53 이라 Collector 의 `prometheus` 내보내기(:8889)를 **긁는다.** 결과 지표 이름은 같다.
- 데모는 서비스가 로그 · 지표도 OTLP 로 보낸다. 여기는 **추적만** OTLP 다. 지표는 이미 `/actuator/prometheus`,
  로그는 이미 Loki(Alloy)로 가고 있어 옮길 이유가 없었다(§8).
- 데모는 Jaeger · Grafana 를 프런트 프록시 아래 둔다. 여기는 Jaeger UI 를 **루프백에만** 연다(§4).

## 2. 무엇을 바꿨나

| 어디 | 바뀐 것 |
|---|---|
| `build.gradle`(루트) | `micrometer-tracing-bridge-brave` · `zipkin-reporter-brave` → `micrometer-tracing-bridge-otel` · `opentelemetry-exporter-otlp` |
| `application-common.yml` | `management.zipkin.*` → `management.otlp.tracing.endpoint`(`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`) · `transport: http`, 샘플링을 `TRACING_SAMPLING_PROBABILITY` 로, resource 속성 `deployment.environment` |
| `k8s/31,40-49` | `ZIPKIN_ENDPOINT` → `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = http://$(HOST_IP):4318/v1/traces` |
| `k8s/10-configmap.yaml` | `TRACING_SAMPLING_PROBABILITY: "1.0"` · `DEPLOYMENT_ENVIRONMENT: "production"` |
| `observability/otel-collector.yml` | 새 파일 — 수신 · 가공 · 분기(§1), 꼬리 샘플링(§5) |
| `observability/jaeger.yml` | 새 파일 — Jaeger v2 설정(badger 파일 저장 · 7일, Monitor 탭 = Prometheus) |
| `docker-compose-observability.yml` | `zipkin` → `otel-collector` · `jaeger`(볼륨 `jaeger-data` + 권한을 맞추는 `jaeger-data-init`), Prometheus 에 `--enable-feature=exemplar-storage` |
| `observability/prometheus.yml` | 작업 3개 — `otel-span-metrics`(:8889) · `otel-collector`(:8888) · `jaeger`(:8888) |
| `observability/alert-rules.yml` | `tracing-pipeline` 그룹 4개 규칙 |
| Grafana | 데이터소스 Zipkin → **Jaeger**(`deleteDatasources` 로 옛것 제거), Prometheus 에 exemplar → Jaeger, Jaeger → 로그 · 지표, 대시보드 **Tracing RED** 추가 |
| `ops/05` | Zipkin 점검 → Collector · Jaeger 점검, 추적 파이프라인 수치, 파드 → 노드 :4318 도달 점검 |
| `ops/41-show-trace.sh` | 새 스크립트 — ruke79/kafka 의 `show-trace.sh` 를 옮겼다 |
| 테스트 | 추적 테스트 4개를 운영과 같은 OTel 브리지로(`OtelTestTracing` 테스트 픽스처), `OtlpTracingExportTest`(설정 → 실제 OTLP 요청), 가드 B-59 개정 · **B-71** · **B-72** 신설 |

### 테스트가 운영과 같은 추적기를 쓰게

추적 테스트(아웃박스를 건너는 추적 · 리스너가 헤더를 이어받는지)는 Brave 추적기를 손으로 조립하고 있었다. 운영만 OTel 로
바꾸면 "테스트는 초록, 운영은 헤더가 안 이어짐" 이 될 수 있다. 그래서 common 의 테스트 픽스처 `OtelTestTracing` 이 운영과
**같은 브리지 · 같은 W3C 전파**로 추적기를 만들고, 끝난 스팬을 메모리에 모은다. 서비스의 추적 테스트는
`testImplementation(testFixtures(project(':common')))` 로 받는다.

### 설정이 실제로 OTLP 로 나가는지 — `OtlpTracingExportTest`

키 이름이 하나라도 틀리면 기동은 멀쩡하고 추적만 조용히 사라진다(Zipkin 시절 파드 안 `localhost:9411` 이 그랬다 — B-59).
이 테스트는 `application-common.yml` 을 그대로 읽어 자동 설정을 띄우고, 수집기 주소를 **매니페스트의 환경 변수 이름**
(`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`)으로 넣은 뒤, 가짜 수집기(JDK HTTP 서버)가 `/v1/traces` 로 protobuf 를 받는지,
그 안에 `service.name` 과 `deployment.environment` 가 있는지 본다. 전파 형식이 W3C `traceparent` 인지도 본다(B3 가 섞이면
서비스 사이에서 추적이 끊긴다).

## 3. 어떻게 보나

| 하고 싶은 것 | 어디서 |
|---|---|
| 로그 한 줄 → 그 요청 전체 | Grafana → Explore → Loki, 줄의 traceId → **Jaeger 에서 보기** |
| 추적의 스팬 → 그 시각의 로그 | Jaeger 데이터소스의 스팬 → **Logs for this span** |
| 느린 요청 하나를 집어 원인 보기 | 대시보드 **Tracing RED** 의 p95 그래프에서 **점(exemplar)** 클릭 → 그 추적 |
| 서비스별 호출률 · 오류율 · 지연 | Grafana **Tracing RED**, 또는 Jaeger **Monitor** 탭 |
| 서비스 사이의 호출 관계 | Jaeger **System Architecture**(서비스 지도) |
| 브라우저 없이 | `./ops/41-show-trace.sh [--longest | --errors | <traceId>]` |

`ops/41` 의 출력은 이렇게 생겼다(로컬 검증에서 실제로 나온 것):

```
trace 81f28780dc908b1e4c1a3547bb41a259  스팬 2개  서비스 gateway-server
  service          span                                                         at        took
✗ gateway-server   http get /api/auth/verify                            +       0ms       4.3ms
  gateway-server     kafka publish payment-request                      +       0ms       3.2ms
```

`at`(추적 시작부터의 시각)이 핵심이다. 주문 사가에서는 부모가 끝나고 한참 뒤에 시작하는 자식이 보이는데, 그 빈틈이
아웃박스 릴레이 · Kafka 를 건넌 시간이다(docs/56 의 지연을 추적 하나로 볼 수 있다).

### Jaeger UI 접속

Jaeger 는 **루프백(127.0.0.1:16686)에만** 열려 있다. 내 PC 에서:

```bash
ssh -L 16686:localhost:16686 <서버>     # 그리고 http://localhost:16686
```

Grafana 는 도커 망 안에서 `jaeger:16686` 으로 붙으므로 따로 할 일이 없다.

## 4. 보안 — 추적도 로그다

추적은 URL · 헤더 · 오류 메시지를 싣는다. 로그와 같은 규칙(자격 증명 · 개인정보를 남기지 않는다)을 적용해야 한다.

**URL 의 쿼리 문자열을 지운다(`transform/redact`).** 쿼리에는 OAuth 콜백의 `code` · `state`, 가입 인증 링크의 `token`,
비밀번호 재설정 링크가 실린다. Spring MVC 의 서버 스팬은 경로만 싣지만, 게이트웨이의 클라이언트 스팬 · Feign · WebClient
스팬은 쿼리까지 싣는다 — **Zipkin 시절에는 그대로 저장되고 있었다.** `http.url` · `url.full` · `http.uri` · `uri` 의 `?` 뒤를
`?[redacted]` 로 바꾸고 `url.query` 는 지운다. 경로는 남긴다(어느 API 였는지는 필요하다).

**모르는 속성까지 막는 그물(`redaction`).** 키 이름에 password · secret · token · authorization · cookie 가 들어간 속성, 값이
JWT 모양이거나 이메일 주소인 속성을 `****` 로 가린다. OTel 데모의 `transform/redact_sensitive_data` + `redaction` 과 같은
두 겹이다. `allow_all_keys: true` 가 중요하다 — 기본값(false)은 허용 목록에 없는 속성을 **전부** 지운다.

로컬에서 실제 스팬으로 확인했다:

```
http.url       https://www.ysyproject.com/api/auth/verify?token=SECRET…  →  …/api/auth/verify?[redacted]
uri            /api/auth/verify?code=OAUTHCODE&state=S                   →  /api/auth/verify?[redacted]
customer.email someone@example.com                                       →  ****
authorization  Bearer abc                                                →  ****
jwt            eyJhbGciOi….eyJzdWIi….sig                                 →  ****
```

**포트.**

| 포트 | 누가 | 바인드 | OCI 보안 목록 |
|---|---|---|---|
| 4318 (OTLP/HTTP) | k3s 파드 → 노드 IP | 모든 인터페이스(루프백이면 파드가 못 닿는다) | 🔒 **열지 않는다** |
| 16686 (Jaeger UI) | 사람 — ssh 터널 | `127.0.0.1` | 🔒 열지 않는다 |
| 13133 (Collector 헬스) | ops/05 | `127.0.0.1` | 🔒 열지 않는다 |
| 4317 · 8888 · 8889 | 도커 망 안 | 호스트로 내지 않음 | — |

가드 **B-71** 이 4318 이 모든 인터페이스에, 16686 이 루프백에만 있는지를 본다.

## 5. 운영

**메모리.** Zipkin(512m)을 빼고 Collector(256m) + Jaeger(512m)를 더했다 — 순증 256m.
Collector 는 `memory_limiter`(200MiB)에서 받기를 멈춘다(서비스는 그 배치를 버리고 계속 돈다). 로컬에서 30초 안에 추적
약 3,100개를 넣었을 때 Collector 는 57MiB, 거절 0건이었다.

### 꼬리 샘플링 — 무엇을 남길지는 추적이 끝난 뒤에 고른다

머리 샘플링(서비스가 요청 시작에 동전을 던짐)은 **결과를 모르고** 고른다. 트래픽이 늘어 0.1 로 낮추면 오류 추적도 90% 가
버려진다 — 정작 봐야 할 것이 사라진다. 그래서 서비스는 전부 보내고(`TRACING_SAMPLING_PROBABILITY=1.0`), Collector 가
추적의 스팬이 다 모일 때까지(30초) 들고 있다가 추적 단위로 고른다.

| 정책 | 남기는 것 | 이유 |
|---|---|---|
| `errors` | 스팬 하나라도 ERROR 인 추적 **전부** | 장애 조사의 출발점 |
| `slow` | 1초 넘게 걸린 추적 **전부**(`TAIL_SAMPLING_SLOW_MS`) | 지연 그래프에서 누를 가치가 있는 점(exemplar)은 대개 이쪽 |
| `money-path` | order · pay · delivery 를 거친 추적 **전부** | 돈이 오간 요청은 문의가 오면 봐야 한다. 둘러보기보다 훨씬 적다 |
| `baseline` | 나머지 중 10%(`TAIL_SAMPLING_PERCENT`) | 정상 흐름의 표본 |

정책은 OR 다(하나라도 남기면 남긴다). 로컬 실측(합성 추적):

| 종류 | 보낸 수 | Jaeger 에 남은 수 |
|---|---|---|
| 빠르고 정상(catalog) | 200 | 27 (13.5% — 10% 표본의 흔들림 범위) |
| 오류 | 20 | **20** |
| 느림(2.5초) | 10 | **10** |
| 결제(pay-service) | 10 | **10** |

**RED 지표는 샘플링 앞에서 센다.** span_metrics 를 샘플링 뒤에 두면 정상은 10% 만, 오류는 전부 남으므로 호출 수는 10% 로
줄고 **오류율은 몇 배로 부풀려진다** — 대시보드와 경보가 함께 거짓말을 한다. 그래서 파이프라인을 둘로 나눴다
(`traces` → span_metrics · `forward/sampling` → `traces/sampled` → tail_sampling → Jaeger). 위 실측에서 Prometheus 의 호출 수는
**240건 전부**(정상 210 · 오류 20 · 결제 10)였다. 가드 **B-72** 가 이 순서와 configmap 의 1.0 을 본다 — 되돌려 실패하는
것을 확인했다.

**늦게 온 스팬.** 결제 사가는 사용자가 PayPal 승인을 몇 분 뒤에 누르기도 한다. 결정한 뒤에 온 스팬이 새 결정을 받으면
추적이 반쪽만 남으므로, 결정을 기억해(`decision_cache`, 남김 · 버림 각 10만 건) 늦은 스팬도 같은 결정을 따르게 했다.

**exemplar 와의 관계.** exemplar(지연 그래프의 점)는 샘플링 전의 스팬에서 뽑힌다. 느린 쪽 · 오류는 전부 남으므로 누를 가치가
있는 점은 열리지만, **빠르고 정상인 구간의 점은 10% 만 열린다**(나머지는 Jaeger 에 없다).

`ops/05` 가 지난 1시간의 "남김 / 버림" 을 보여 준다. 대기 칸(`num_traces` 2만)이 넘쳐 결정 없이 버리면
`TailSamplingDroppedTooEarly` 경보가 울린다.

### 보관 — badger, 7일

처음에는 메모리 저장이라 **재시작하면 추적이 사라졌다** — "어젯밤 결제 실패" 문의가 오면 로그의 traceId 를 눌러도 빈 화면이었다.
이제 Jaeger 에 내장된 파일 저장소(badger)를 도커 볼륨 `jaeger-data` 에 둔다. 보관 기간은 `JAEGER_TRACE_TTL`(기본 168h)이고
지나면 badger 가 스스로 지운다. 이미지가 uid 10001 로 돌아 새 볼륨(root 소유)에 쓰지 못하므로, `jaeger-data-init`(busybox)이 한 번
소유자를 맞추고 끝난다. 로컬에서 오류 추적 하나를 `restart` · 컨테이너 재생성 뒤에 다시 조회해 둘 다 200 인 것을 확인했다.

**경보(`tracing-pipeline`).** `OtelCollectorDown`(5분) · `TraceExportToJaegerFailing`(10분) · `TracesRefusedByCollector`(10분) ·
`TailSamplingDroppedTooEarly`(5분).
어디가 끊겨도 서비스는 오류 없이 돈다 — 그래서 사람이 "추적이 안 보인다" 를 알아채기 전에 경보가 먼저 울려야 한다.
Collector 지표 이름은 버전에 따라 `_total` 이 붙기도 해서 `{__name__=~"…(_total)?"}` 로 둘 다 받는다.

**호스트 방화벽.** 파드 → 노드 IP 가 막히면 추적은 조용히 버려진다. 예전에 9411 을 허용하는 규칙을 넣었다면 **4318 로
바꿔야 한다.** `ops/05` 가 busybox 파드로 `POST /v1/traces`(빈 요청 `{}` — 스팬 0개라 저장되지 않는다)를 보내 확인한다.
GET 은 405 라 점검에 쓸 수 없다.

## 6. 버전 고정과 올리는 법

| 이미지 | 고정 | 이유 |
|---|---|---|
| `otel/opentelemetry-collector-contrib` | **0.160.0** | OTel 데모와 같은 버전. 설정의 컴포넌트 이름(`otlp_grpc` · `span_metrics`)이 이 버전의 이름이다 — 예전 이름(`otlp` · `spanmetrics`)과 다르다 |
| `jaegertracing/jaeger` | **2.20.0** | **2.21 이 v1 HTTP API 의 검색(`/api/services` · `/api/traces?service=`)을 지웠다**("UI 가 더는 부르지 않는 엔드포인트 제거"). Grafana 의 Jaeger 데이터소스와 `ops/41` 이 그것을 쓴다. 2.21 에서는 ID 조회(`/api/traces/{id}`)만 남아 로그 → 추적은 되고 검색이 404 다 — 로컬에서 실측했다 |

올리기 전에:

```bash
# Collector 설정 검증 — 컴포넌트 이름이 바뀌었으면 여기서 걸린다
docker run --rm -v $PWD/observability/otel-collector.yml:/c.yml otel/opentelemetry-collector-contrib:<새 버전> validate --config=/c.yml
# Jaeger 설정 검증
docker run --rm -v $PWD/observability/jaeger.yml:/c.yml jaegertracing/jaeger:<새 버전> validate --config=file:/c.yml
# Jaeger 를 올렸다면: Grafana → Explore → Jaeger → Search 가 되는지, ./ops/41-show-trace.sh 가 되는지
```

## 7. 검증한 것

검증 환경에서 Docker 로 스택을 실제로 띄워 확인했다.

- `otelcol-contrib validate` · `jaeger validate` 통과, `promtool check rules`(21개 규칙) · `check config` 통과.
- Spring Boot 자동 설정(공유 설정 그대로)으로 만든 실제 스팬을 Collector 로 → Jaeger 에 `gateway-server` · `order-service` 가
  나타나고, resource 에 `deployment.environment` 가 있다.
- 가림(§4) — 쿼리 · 이메일 · JWT · authorization 이 저장 전에 지워졌다.
- span_metrics → Prometheus(`traces_span_metrics_calls_total` · `_duration_milliseconds_bucket`, 라벨 `service_name` ·
  `span_name` · `span_kind` · `status_code`). 대시보드 **Tracing RED** 의 질의 3종이 데이터를 돌려준다.
- exemplar — Prometheus 에 `trace_id` 가 달린 exemplar 가 저장된다.
- Jaeger Monitor 탭 API — 20건 중 4건 오류 → 오류율 **0.2** 로 정확히 나온다.
- Grafana(11.1) → Jaeger 2.20 프록시로 서비스 목록 · ID 조회가 된다. 2.21 에서는 서비스 목록이 404(§6).
- busybox `wget --post-data '{}'` → `{"partialSuccess":{}}`(ops/05 의 파드 점검 방식).
- 꼬리 샘플링 — 오류 · 느림 · 결제 경로는 100%, 나머지 13.5%. RED 호출 수는 240건 전부(§5).
- badger — 재시작 · 컨테이너 재생성 뒤에도 추적이 남는다. 부하(추적 3,100개/30초)에서 Collector 57MiB, 거절 0.
- `ops/05` · `ops/41` 을 실제 스택에 대고 돌렸다.
- 테스트: `OtlpTracingExportTest` 2개, 추적 테스트 4개(OTel 브리지), common(B-59 · B-71 · B-72 포함), 백엔드 전체.

검증하지 못한 것: OCI 노드의 실제 방화벽 규칙, 운영 트래픽에서의 Collector 메모리 사용량.

## 8. 한계와 개선점

> 처음 적용 때의 한계 중 **꼬리 샘플링**과 **메모리 저장**은 해결했다(§5).

- **샘플링 결정 뒤에 난 오류는 못 잡는다.** 30초 안에 버리기로 한 추적에서 그 뒤에 오류 스팬이 오면, 결정 캐시가 "버림" 을
  기억하므로 그 오류도 버려진다. 돈 경로는 정책으로 전부 남기므로 실제로 걸리는 것은 둘러보기 요청의 늦은 비동기 오류뿐이다.
  오류 자체는 로그(Loki)와 RED 지표에 남는다.
- **badger 는 노드 하나의 저장소다.** 노드가 여럿이 되면(또는 보관을 몇 달로 늘리면) OpenSearch/ClickHouse 로 옮긴다.
- **꼬리 샘플링은 한 추적의 스팬이 한 Collector 로 모여야 한다.** 지금은 노드 하나라 자연히 그렇다. 노드가 여럿이 되면
  추적 id 로 Collector 를 고르는 `loadbalancing` 내보내기를 앞단에 둔다.
- **로그 · 지표는 OTLP 가 아니다.** 데모처럼 셋을 모두 OTLP 로 모으면 resource 속성(서비스 · 환경)이 한 곳에서 붙는다.
  지금은 동작하는 경로(actuator · Alloy)를 굳이 옮기지 않았다.
- **노드 하나 기준이다.** 파드는 자기 노드의 Collector 로 보낸다. 노드가 여럿이 되면 Collector 를 DaemonSet(노드마다) 또는
  클러스터 안 Deployment 로 옮긴다 — 서비스 설정은 주소만 바뀐다.
- **Grafana 가 11.1 이다.** 올리면 Jaeger 2.21+ 의 v3 API 를 쓰는 데이터소스로 갈 수 있다(데모는 Grafana 13 · Jaeger 2.21).
- **프런트엔드 추적이 없다.** 브라우저에서 시작하는 추적(OTel Web SDK)은 CORS · 공개 수집 경로가 필요해 하지 않았다.

## 9. 배포

1. 관측 스택: `docker compose -f docker-compose-observability.yml up -d --remove-orphans` — `zipkin` 이 빠지고
   `otel-collector` · `jaeger` 가 뜬다. 처음 한 번 `jaeger-data-init` 이 볼륨 권한을 맞추고 `Exited (0)` 으로 끝난다(정상).
2. 호스트 방화벽에 9411 규칙이 있었다면 4318 로 바꾼다.
3. 백엔드: 의존성과 공통 설정이 바뀌었으므로 **모든 서비스 이미지**를 다시 만들고, 매니페스트(환경 변수)가 바뀌었으므로
   `./ops/28-deploy.sh local <버전>`(또는 `--config-only` 로 매니페스트 먼저)로 올린다.
4. 확인: `./ops/05-check-observability.sh` — 파드 → :4318 OK, Jaeger 에 서비스들이 보이는지. 주문 한 건을 넣고
   `./ops/41-show-trace.sh --longest`.
