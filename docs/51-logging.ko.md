# 51. 로깅 — 인증번호 누출을 막고, 출력 단계에서 가리고, JSON 으로 모아 추적과 잇는다

[日本語](51-logging.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** §5 첫 항목(배치 리스너 추적)은 §4h 로 해소됐고, Loki 기반 경보 일부가 들어왔습니다.

배경: 이 프로젝트의 로깅 기능이 ruke79/facebook 저장소의 로깅 기능보다 떨어지는지 평가하고,
평가에서 나온 개선 1~4번을 순서대로 적용한다.

facebook 저장소의 `backend-spring` 은 **로깅 실습 전용** 단일 서비스다. JSON 구조화 로그, 출력
단계 마스킹, MDC(traceId·userId), 비동기 스레드 전파, 접근 로그, 런타임 레벨 변경, 로그 폭주
방어, Loki 수집, 로그 캡처 테스트 53건을 갖췄다. 이 프로젝트는 서비스 간 **분산 추적**(Micrometer
Tracing + Zipkin)과 **메트릭 알림**(Prometheus + Alertmanager)은 앞서지만, 로그 자체는 평문 패턴에
마스킹이 부르는 쪽 재량이었고 수집기도 없었다. 평가 결과 순서대로 넷을 적용했다.

| 단계 | 한 것 | 핵심 파일 |
|---|---|---|
| 1 | SMS 로그 발송기에서 **인증번호가 든 본문**을 뺐다 | `notification-service/.../sms/LoggingSmsSender.java` |
| 2 | **출력 단계 마스킹**을 공통 모듈로 모든 서비스에 | `common/.../logging/*`, `common/src/main/resources/logback-spring.xml` |
| 3 | 공통 설정에 **JSON 파일·롤링·비동기**, MDC `userId`, 비동기 스레드 전파 | 같은 파일, `UserMdcFilter`, `MdcTaskDecorator` |
| 4 | **Loki + Alloy** 수집, Grafana 에서 **로그 ↔ Zipkin 추적** 왕복 | `docker-compose-observability.yml`, `observability/*` |

---

## 1. 🔒 휴대폰 인증번호가 운영 로그에 평문으로 남았다

`LoggingSmsSender` 는 실제로 보내지 않고 로그만 남기는 발송기이고 **기본값**이다
(`notification.sms.provider` 기본 `logging`, k8s 도 바꾸지 않는다). 그런데 문자 본문 전체를
`body={}` 로 INFO 에 찍었다. 인증 문자의 본문에는 인증번호가 있다.

```
[SMS:dry-run] to=+8210****5678 bytes=24 single=true body=[쇼핑몰] 인증번호 482913 ...
```

로그를 읽을 수 있는 사람이면 남의 번호로 인증을 끝낼 수 있다. `VerificationService` 는
"코드는 절대 로그에 남기지 않는다" 고 적어 두고 **자기 로그에서는** 뺐지만, 그 코드를 받아 간
발송기가 그대로 찍었다. docs/38 §1.7 이 지적했는데 남아 있었다.

**본문을 빼고 가린 번호·글자 수·한 건 여부만 남긴다.** 2단계의 전역 마스킹으로는 막을 수 없다 —
여섯 자리 숫자는 주문 번호·금액·수량과 구별이 안 된다. 인증번호는 **애초에 로그로 넘기지 않는
것**이 규칙이다.

**가드**: `LoggingSmsSenderTest` — 실제 로그를 `ListAppender` 로 캡처해 한국어·일본어 인증 문구의
코드가 어느 줄에도, **포맷 전 인자에도** 없음을 본다(JSON 인코더는 인자를 따로 직렬화할 수 있다).
옛 코드로 되돌리면 실패하는 것을 확인했다.

---

## 2. 출력 단계 마스킹 — "기억해서 부르는" 방식은 반드시 한 곳에서 샌다

예전에는 부르는 쪽이 가렸다(`EmailAddresses.mask`, `PhoneNumbers.mask`, `DeviceIdentity.maskIp`).
알림 서비스는 잘 지켰지만 cart·order·user 서비스에는 이메일을 그대로 찍는 로그가 스무 곳 가까이
있었다(`CartService` 의 `Cache miss for carts::{email}` 등).

이제 logback 이 **줄을 쓰기 직전에** 강제로 거른다.

- 텍스트(콘솔): `%mask(%msg)` · `%mask(%wEx)` — `MaskingPatternConverter`
- JSON: `MaskingJsonGeneratorDecorator` + `SensitiveValueMasker` — 메시지·MDC·인자·스택트레이스의
  **모든 문자열 값**
- 규칙은 하나: `SensitiveDataMasker`. 콘솔과 JSON 이 다르게 가리면 한쪽으로 샌다.

| 가리는 것 | 예 | 결과 |
|---|---|---|
| Bearer/Basic 토큰 | `Bearer abcdefgh…` | `Bearer ****` |
| JWT | `eyJhbGci….eyJzdWIi….c2ln…` | `eyJ****.****.****` |
| 비밀값 key=value·JSON | `"password":"hunter22"` | `"password":"****"` |
| 주민등록번호 | `900101-1234567` | `900101-1******` |
| 카드번호 16자리 | `4111-1111-1111-1111` | `4111-1111-11**-****` |
| 국제 표기 휴대폰(E.164) | `+821012345678` | `+82****5678` |
| 한국·일본 휴대폰 | `010-1234-5678` · `090-1234-5678` | `010-****-5678` |
| 이메일 | `hong.gildong@example.com` | `h****@example.com` |

**가리지 않는 것 — 일부러.** 인증번호(위 1단계), IP(Kafka·DB 주소와 구별이 안 된다 — 사용자 IP 는
저장할 때 이미 가린다), 그리고 **주문 번호(TSID 18~19자리)·UUID·금액·시각·traceId**. 오탐하면 장애 때
로그로 주문을 찾을 수 없다. `SensitiveDataMaskerTest` 의 "건드리지 않는다" 입력은 이 저장소의 실제
로그 모양이다.

facebook 에서 옮기며 바꾼 것:

- **E.164 와 일본 번호**를 더했다. 이 프로젝트는 번호를 `+8210…`·`+8190…` 으로 저장한다.
- **검사 길이 상한 16KB → 64KB.** 콘솔은 스택트레이스도 마스킹을 지나는데, 스프링의 깊은 스택은
  16KB 를 쉽게 넘어 **정작 `Caused by` 가 잘려 나갔다.**
- `Authorization: Bearer x` 는 key=value 규칙이 `Bearer` 까지 가린다(`Authorization: **** ****`).
  더 가려질 뿐 새지는 않는다 — 테스트가 그 동작을 고정한다.

### 모든 서비스에 적용되는 방식

`common` jar 의 루트에 `logback-spring.xml` 을 뒀다. common 을 쓰는 모든 서비스가 이 파일로
로그를 쓴다. 레벨(`logging.level`)과 파일 이름(`logging.file.name`)은 지금처럼 서비스가 정한다.

- 서비스 yml 의 `logging.pattern.*` 11곳을 지웠다 — 공통 설정이 패턴을 정하므로 두어도 쓰이지 않는다.
- 🐛 **eureka-server** 는 세 겹으로 어긋나 있었다.
  1. yml 의 `logging:` 이 두 칸 들여쓰여 `eureka.logging.*` 로 들어가 있어 스프링이 읽지 않았다.
  2. 대신 자기 `logback-spring.xml` 이 Netflix 로거를 **DEBUG 로 고정**했다(B-46 은 yml 만 봐서 못 잡았다).
  3. 운영용이라던 `logback-psring-prd.xml` 은 이름 오타로 아무도 읽지 않았다
     (목적지도 `logstash.yourcompany.com` 자리표시자였다).

  yml 블록을 최상위로 옮기고 두 XML 을 지웠다.
- **config-server 는 제외다.** common 을 의존하지 않는다(설정 서버에 Kafka·Avro 를 끌어올 이유가
  없다). 사용자 데이터를 다루지 않고, 스프링 기본 설정과 자기 yml 패턴으로 남는다.

**가드**

- `CommonLogbackConfigTest` — 공통 설정을 **스프링이 서비스 기동 때 여는 방식 그대로**
  (`LogbackLoggingSystem.initialize`) 열어 본다. 클래스 이름 오타 하나면 스프링이
  "Logback configuration error detected" 로 **열두 서비스의 기동을 한꺼번에 막는다** — 서비스를
  띄우기 전에 여기서 잡는다. 그리고 실제로 찍힌 콘솔·파일 줄을 읽어 가려졌는지 본다.
- **B-57** — common 을 쓰는 모듈이 자기 `logback*.xml` 이나 `logging.pattern` 을 두면 실패한다
  (두면 공통 설정의 마스킹이 빠진다). 패턴과 logback 파일을 되살려 실패하는 것을 확인했다.
- **B-57b** — 공통 설정의 모든 텍스트 패턴이 `%mask(%msg)`·`%mask(%wEx)` 를 쓰고, 모든
  `LogstashEncoder` 에 `SensitiveValueMasker` 가 달려 있어야 한다. 새 appender 를 더하면서
  `%msg` 를 그대로 쓰면 그 출력으로만 평문이 샌다.

---

## 3. JSON 파일·롤링·비동기, MDC `userId`, 비동기 스레드 전파

### 공통 설정이 내는 것

| 출력 | 형식 | 켜는 법 | 기본 |
|---|---|---|---|
| 콘솔 | 텍스트 한 줄 | `LOG_CONSOLE_FORMAT=text` | ✅ |
| 콘솔 | JSON 한 줄 | `LOG_CONSOLE_FORMAT=json` | |
| 파일 `logging.file.name`(`<모듈>/logs/<서비스>.json`) | JSON 한 줄, 비동기 기록 | `LOG_JSON_FILE=true` | ✅ (k8s 는 `false`) |

- 텍스트 줄: `2026-09-24 10:00:00.123 [http-nio-8080-exec-1] [traceId,spanId,userId] WARN  c.p.c.s.CartService - …`
- JSON 줄: `@timestamp, message, logger_name, thread_name, level, service, traceId, spanId, userId, stack_trace`
- 파일 롤링: 100MB · 14일 · **총 1GB**. 예전에는 스프링 기본값(총량 상한 없음)이었다.
- 비동기: 큐 8192, `discardingThreshold=0`(큐가 차도 INFO 를 조용히 버리지 않는다), `neverBlock=false`
  (가득 차면 잠시 기다린다 — 유실보다 지연). 콘솔은 동기로 둔다 — 파드가 죽기 직전의 마지막 줄이
  `kubectl logs` 에 남아야 한다.
- 스택트레이스(JSON): 깊이 30 · 8KB · 원인 먼저.

**왜 프로파일이 아니라 환경변수인가.** 이 저장소는 모든 환경이 `local` 프로파일로 돈다(k8s 포함,
`SPRING_PROFILES_ACTIVE: "local"`). `<springProfile>` 로 가르면 k8s 와 개발 PC 를 구별할 수 없다.
그래서 `FormatSwitchFilter` 가 `app.logging.*`(환경변수 `LOG_CONSOLE_FORMAT`·`LOG_JSON_FILE`)로 appender 를
켜고 끈다. logback 의 `<if>` 는 Janino 를 더 들여와야 해서 쓰지 않았다.

**k8s 는 텍스트 콘솔 + 파일 끔**(`k8s/10-configmap.yaml`). 컨테이너 안의 파일은 아무도 읽지 않고 파드가
지워지면 사라지며 노드 디스크만 쓴다. 클러스터에 Loki 를 붙여 stdout 을 모을 때 `LOG_CONSOLE_FORMAT=json`
으로 바꾼다.

### `userId` — "누구의 요청인가"

traceId·spanId 는 Micrometer Tracing 이 이미 MDC 에 넣는다. 빠져 있던 것은 사용자였다. "결제가 안 된다"
는 문의가 오면 이메일로 로그를 grep 하는 수밖에 없었는데, 그 이메일은 2단계부터 가려진다.

`UserMdcFilter` 가 게이트웨이가 넣은 `User-Id` 헤더를 MDC `userId` 로 넣는다.

- 게이트웨이는 클라이언트가 보낸 신원 헤더를 지우고 토큰에서 다시 넣는다. 게이트웨이를 거치지 않은
  요청은 꾸밀 수 있지만, 여기서는 **로그 표시에만** 쓰고 어떤 판단에도 쓰지 않는다.
- 로그 인젝션(줄바꿈으로 가짜 줄 만들기)은 막는다 — 영숫자와 `. _ : -` 만 남기고 64자로 자른다.
- 끝나면 `userId` **만** 지운다. `MDC.clear()` 는 추적이 넣은 traceId 까지 지운다.
- 서블릿 서비스에만 붙는다. 게이트웨이(리액티브)는 필터 체인이 달라 붙지 않는다 — 게이트웨이 로그는
  traceId 로 잇는다.

### 비동기 스레드로 넘기기

MDC 는 스레드마다 따로라, `@Async` 메일·SSE·상품 가져오기·ES 재색인 로그에서 traceId·userId 가 비었다.
`MdcTaskDecorator` 가 넘기는 순간의 MDC 를 작업 스레드에 붙이고, 끝나면 작업 스레드의 원래 MDC 로
되돌린다(되돌리지 않으면 재사용되는 풀 스레드가 남의 traceId 를 달고 다닌다).

| 붙인 곳 | 방식 |
|---|---|
| user-service `mailExecutor` · `sseExecutor` | `setTaskDecorator` |
| catalog-service 배치 `taskExecutor` | `setTaskDecorator` |
| catalog-service 상품 가져오기 · ES 재색인 스레드 | `MdcTaskDecorator.wrap(...)` |
| 스프링 부트 기본 `@Async` 실행기 | 자동 구성의 `TaskDecorator` 빈 |

### 서비스에 붙는 방식 — 자동 구성

대부분의 서비스가 `com.project.common` 을 컴포넌트 스캔하지 않는다(order·cart·chat 만 한다). 스캔에
기대면 서비스마다 붙기도 하고 안 붙기도 한다. `CommonLoggingAutoConfiguration` 을
`META-INF/spring/…AutoConfiguration.imports` 에 등록했다.

**가드**: `CommonLogbackConfigTest`(+3: JSON 파일을 파싱해 마스킹·service·traceId·userId·stack_trace 확인,
json 콘솔 모드, 파일 끄기), `UserMdcFilterTest`, `MdcTaskDecoratorTest`(풀 스레드 재사용 시 섞이지 않음),
B-57 에 `logging.file.name` 은 `.json`. eureka 의 기동 테스트가 실제 스프링 경로로 JSON 파일을 쓰는 것도
확인했다.

---

## 4. Loki + Alloy — 로그를 모으고 Zipkin 추적과 잇는다

> 🔄 추적 저장소는 지금 Jaeger 다([docs/63](63-opentelemetry.ko.md)). 아래의 "Zipkin" 은 Jaeger 로 읽는다 — 데이터소스 uid 는 `jaeger`.

`docker-compose-observability.yml` 에 둘을 더했다(기존 Prometheus·Grafana·Alertmanager·Zipkin 옆).

```
<모듈>/logs/*.json ──(Alloy, 읽기 전용 마운트)──▶ Loki ──▶ Grafana Explore
                                                          │  줄의 traceId 클릭 ▼
                                   Zipkin ◀───────────────┘
                                     │  스팬의 "Logs for this span" ▼
                                     └──────────────▶ Loki {job="ecommerce"} | json | traceId="…"
```

- **Promtail 이 아니라 Alloy.** 평가 때는 Promtail 이라고 적었지만, Grafana 가 Promtail 을 지원 종료하고
  Alloy 를 후속으로 내놓았다. 새로 붙이는 수집기를 지원이 끝난 것으로 고를 이유가 없다.
- **라벨은 `service`·`level` 두 개뿐.** traceId·userId 를 라벨로 두면 요청마다 스트림이 생겨 Loki 가
  느려진다. 줄 안의 필드로 두고 `| json | traceId="…"` 로 거른다.
- 🔒 **저장소 루트를 통째로 마운트하지 않는다.** 모듈마다 `logs` 디렉터리만 읽기 전용으로 붙인다. 루트를
  붙이면 gitignore 된 비밀 파일(`k8s/20-secret.yaml`, `kafka-secrets.properties`)까지 컨테이너에 보인다.
- Loki 보존 7일(서비스 쪽 파일은 14일·1GB).
- Grafana 데이터소스는 `uid` 를 고정했다(`zipkin`·`loki`) — 서로를 uid 로 가리킨다. derivedFields 의
  traceId 정규식은 Brave 의 형식(16·32자리 소문자 16진수)에 맞췄다.

### 써 보기

```bash
docker compose -f docker-compose-observability.yml up -d
./gradlew :order-service:bootRun            # order-service/logs/order_service.json 에 쓴다
./ops/05-check-observability.sh             # Loki·Alloy 헬스와 들어온 service 라벨까지 본다
```

Grafana(http://localhost:3001) → Explore → Loki:

```logql
{service="order-service", level=~"WARN|ERROR"}
{job="ecommerce"} | json | userId="736459283746592837"
{job="ecommerce"} | json | traceId="4bf92f3577b34da6a3ce929d0e0e4736"
```

**가드**: B-58 — common 을 쓰는 모든 모듈의 `logs` 디렉터리가 Alloy 에 읽기 전용으로 붙어 있어야 하고,
루트를 통째로 붙이면 실패한다. 서비스를 더하고 compose 를 잊으면 그 서비스의 로그만 Grafana 에 **조용히**
없다. 마운트 한 줄을 지워 실패하는 것을 확인했다.

⚠️ **당시 검증 환경에서 확인하지 못한 것.** 도커가 없어 Loki·Alloy·Grafana 를 실제로 띄워 보지 못했다. 설정 파일은
YAML 파싱과 가드로만 확인했다. 이미지 버전(`grafana/loki:3.4.2`, `grafana/alloy:v1.9.1`)은 검증 환경의
네트워크 제한으로 GitHub 를 조회할 수 없어 최신 여부를 확인하지 못했다. 처음 띄울 때 `docker compose logs loki alloy` 를
한 번 볼 것.

---

## 4c. k3s 로 배포한 서비스의 로그 — 파드 표준 출력을 읽는다

첫 구성은 각 모듈의 `logs/*.json` 만 읽었다. 그 파일은 서비스를 로컬에서 `bootRun` 으로 띄울 때만 생긴다.
k3s 에서는 로그가 파드의 **표준 출력**이고(k8s configmap 은 `LOG_JSON_FILE=false`), k3s 는 그것을 호스트의
`/var/log/pods/<네임스페이스>_<파드>_<uid>/<컨테이너>/<n>.log` 에 CRI 형식으로 쌓는다. 그대로면 **k3s 로
배포한 서비스의 로그는 Loki 에 하나도 오지 않는다.**

같은 호스트에서 compose 로 띄우는 Alloy 가 `/var/log/pods` 를 읽기 전용으로 붙여 `ecommerce_*` 파드의 로그도
읽는다(`job="ecommerce-k3s"`).

- `service` 라벨 = 경로의 컨테이너 이름(= 서비스 이름, 예: `order-service`)
- `stage.cri` 로 CRI 머리(시각·stdout·F)를 풀고 그 시각을 로그 시각으로 쓴다
- 우리 스프링 서비스에만 여러 줄 묶기(스택트레이스를 한 건으로)와 텍스트 줄의 레벨 추출을 건다.
  frontend(nginx)·cloudflared 의 줄은 날짜로 시작하지 않아, 모두에 걸면 앞 줄에 계속 붙는다.
- Alloy 는 root 로 돈다 — 파드 로그 파일이 root 소유(0640)다. 마운트는 전부 읽기 전용이다.
- Zipkin → 로그 질의는 줄 필터(`|= "<traceId>"`)라 JSON 줄과 텍스트 줄 모두에서 찾는다. Loki → Zipkin 링크는
  JSON 용과 텍스트 용(`[traceId,spanId,userId]`) 두 개다.

```bash
docker compose -f docker-compose-observability.yml up -d
docker compose -f docker-compose-observability.yml logs alloy | tail -20
./ops/05-check-observability.sh        # "Loki 에 들어온 서비스 로그" 에 서비스 이름이 나와야 한다
```

```logql
{job="ecommerce-k3s", service="order-service"}
{job="ecommerce-k3s", level="ERROR"}
{job=~"ecommerce.*"} |= "736459283746592837"      # userId
```

⚠️ k3s 의 서비스는 `ZIPKIN_ENDPOINT` 를 주입받지 않아(기본 `localhost:9411` = 파드 자신) **추적이 Zipkin 에
가지 않는다.** 로그의 traceId 로 같은 요청의 줄끼리는 이을 수 있지만, Zipkin 링크를 누르면 빈 화면이다.
k3s 에서 추적까지 잇는 일은 §5 의 "k8s 관측 스택" 과 같이 한다.

## 4d. k3s 에서도 추적이 Zipkin 에 간다 — 그리고 Kafka 를 건너도 이어진다

> 🔄 **2026-09 이후: Zipkin → OpenTelemetry([docs/63](63-opentelemetry.ko.md)).** 아래의 `ZIPKIN_ENDPOINT` · `:9411` 은
> `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` · `http://$(HOST_IP):4318/v1/traces`(노드의 OTel Collector)로, Zipkin 화면은 Jaeger 로
> 바뀌었다. 노드 IP 를 downward API 로 받는 방식 · B-59 · 방화벽 주의는 그대로다(포트만 4318).

### 파드 → 노드의 Zipkin

`management.zipkin.tracing.endpoint` 의 기본값은 `http://localhost:9411/api/v2/spans` 다. 파드 안에서
`localhost` 는 **파드 자신**이라, k3s 로 배포한 서비스의 추적은 **오류 없이 조용히 버려지고** 있었다.

클러스터에 Zipkin 을 하나 더 띄우지 않고(단일 노드는 이미 자원이 빠듯해 파드가 Pending 으로 줄을 선다),
같은 노드에서 compose 로 도는 관측 스택의 Zipkin(:9411)으로 보낸다. 노드 IP 는 매니페스트에 적지 않고
downward API 로 받는다.

```yaml
- name: HOST_IP
  valueFrom:
    fieldRef:
      fieldPath: status.hostIP
- name: ZIPKIN_ENDPOINT
  value: "http://$(HOST_IP):9411/api/v2/spans"
```

- 공통 설정을 쓰는 11개 Deployment(게이트웨이 포함)에 넣었다. eureka·config-server 는 공통 설정을 쓰지
  않고, 보내도 하트비트 소음뿐이라 뺐다.
- `$(HOST_IP)` 는 **앞에 정의된** 변수만 풀린다 — `HOST_IP` 가 먼저다.
- **가드 B-59** 가 두 항목이 있는지, 순서가 맞는지, 노드의 :9411 을 가리키는지 본다. `localhost` 로 되돌려
  실패하는 것을 확인했다.

⚠️ **호스트 방화벽.** 파드 → 노드 IP:9411 이 막히면 추적은 여전히 조용히 버려진다. `ops/05` 가 busybox 파드를
잠깐 띄워 직접 두드려 본다. 막혀 있다면 호스트에서 파드 대역(k3s 기본 `10.42.0.0/16`)의 9411 접근을 허용한다.
OCI 우분투 이미지는 iptables 기본 규칙이 엄격해 막을 수 있다 — 당시 검증 환경에서는 확인할 수 없었다.

### Kafka 를 건너도 추적이 이어진다 — 프로듀서 쪽 추적

컨슈머 쪽 추적(`ContainerProperties.setObservationEnabled(true)`)은 서비스마다 켜져 있었지만 **보내는 쪽은 한
곳도 켜져 있지 않았다.** 메시지에 추적 헤더(`traceparent`)가 실리지 않아 받는 쪽이 새 추적을 시작했다 —
"장바구니 비우기"·"배송 상태"·"구매 확정 알림"·"가입 확인 메일" 이 원인이 된 요청과 따로 떨어져 보였다.

템플릿을 만드는 곳이 서비스 여러 곳에 35군데라, 하나씩 고치지 않고 공통 자동 구성의
`KafkaTemplateObservationPostProcessor` 가 모든 `KafkaTemplate` **빈**에 켠다(끄려면
`app.tracing.kafka-producer=false`). 빈이 아닌 템플릿 — user-service 의 `SecurityAlertProducer`·
`OutgoingMailProducer` 는 생성자에서 `new` 로 만든다 — 에는 레지스트리를 직접 넣었다.

⚠️ **아웃박스는 여전히 끊긴다.** 결제 요청처럼 아웃박스 릴레이(스케줄러)가 보내는 메시지는 보내는 순간이 요청
안이 아니라 스케줄러 안이라, 추적은 릴레이에서 새로 시작한다. 주문 요청 → 결제 → 주문 확정을 한 추적으로
보려면 추적 문맥을 아웃박스 행에 저장했다가 릴레이가 되살려야 한다 — 이번 범위 밖이다.

**가드**: `KafkaTemplateObservationPostProcessorTest` — 자동 구성이 붙은 컨텍스트의 템플릿 빈은 켜지고, 끄는
설정이 먹는다.

## 4e. 아웃박스를 건너도 추적이 이어진다

§4d 로 Kafka 를 건너는 추적은 이어졌지만 **아웃박스에서 끊겼다.** 아웃박스는 "행을 쓰는 순간" 과 "보내는
순간" 이 다른 스레드다. 행은 요청(또는 리스너) 안에서 쓰이고, 보내는 것은 몇 초 뒤 스케줄러다. 추적 문맥은
스레드에 붙어 있어 스케줄러에는 없었고, Kafka 헤더에 실리는 추적은 **스케줄러가 새로 시작한 것**이었다.
Zipkin 에서 "PayPal 결제 확인" 요청은 DB 에 행을 쓰고 끝났고, 그 뒤의 사가는 따로 떨어진 추적이었다.

| 서비스 | 아웃박스 | 행을 쓰는 곳 | 릴레이 |
|---|---|---|---|
| order-service | `orders.payment_outbox_event` | 결제·환불·입금 확인 HTTP 요청 | `PaymentOutboxScheduler` |
| pay-service | `pay.order_outbox_event` | 결제 요청 Kafka 리스너 | `OrderOutboxScheduler` |
| user-service | `users.outbox_event` | 가입 확인 HTTP 요청 | `UserOutboxScheduler` |

**잇는 방법 — `common` 의 `OutboxTraceContext`**

1. 행을 쓸 때 `capture()` 가 현재 추적 문맥을 전파 헤더 모양 그대로(W3C `traceparent=00-…`) 새 컬럼
   `trace_context varchar(512)` 에 남긴다(order V14 · pay V4 · user V12).
2. 릴레이는 보내기 직전에 `resume(stored, "outbox relay <종류>", send)` 로 그 문맥에서 스팬을 되살리고,
   그 스팬을 현재 스팬으로 둔 채 보낸다. §4d 의 프로듀서 추적이 그 아래 자식이 되어 헤더에 **원래 요청의
   traceId** 가 실린다.

결과: **결제 확인 요청 → 결제 요청(아웃박스) → pay-service 리스너 → 결제 응답(아웃박스) → order-service
리스너(주문 PAID)** 가 하나의 추적이다. pay-service 는 리스너 안에서 행을 쓰므로 그 리스너의 추적 — 즉 이미
원래 요청의 추적 — 을 남긴다.

- **새 행일 때만 남긴다.** order·pay 의 헬퍼는 같은 `save()` 로 릴레이의 상태 갱신도 한다. 그때 남기면
  스케줄러의 문맥으로 덮어쓴다(`isNew()` 로 가른다).
- **추적은 발행을 막지 않는다.** 추적 빈이 없거나, 저장된 값이 없거나(옛 행) 깨져 있으면 예전처럼 그냥 보낸다.
- 추적 빈은 쓰는 순간에 찾는다 — 자동 구성 순서상 이 빈이 먼저 만들어질 수 있다.

**가드**

- `OutboxTraceContextTest` — **실제 Brave 추적기와 실제 KafkaTemplate**(MockProducer)로, 요청 스레드에서
  남긴 문맥을 다른 스레드에서 되살려 보내면 레코드 헤더에 원래 traceId 가 실리는지 본다. 대조군으로, 문맥
  없이 보내면 다른 추적이 되는 것도 본다.
- `PaymentOutboxTraceTest` — 새 행에는 남기고 상태 갱신은 덮어쓰지 않는지, 릴레이가 행의 문맥으로 `resume`
  하는지.
- **B-60** — 세 아웃박스 모두 네 끝(마이그레이션·엔티티 필드·`capture()`·`resume(`)이 있어야 한다. 릴레이
  하나에서 `resume` 을 지워 실패하는 것을 확인했다.
- 세 마이그레이션을 실제 PostgreSQL 16 에서 각 서비스의 파티션 생성(V1) 뒤에 두 번씩 적용했다 — 부모와 모든
  월별 파티션에 컬럼이 붙는다.

⚠️ pay-service 가 이미 처리한 결제를 **리스너에서 바로 다시 보내는** 경로
(`publishIfOutboxEventProcessedForPayment`)는 아웃박스를 거치지 않는다 — 리스너의 추적 그대로 나가므로
따로 할 일이 없다.

## 4f. 🐛 결제 응답부터 끊겼다 — 배치 리스너에는 컨슈머 추적이 없다

§4e 를 배포하고 보니 "결제 확인 요청 → outbox relay payment-request" 까지만 이어지고 **`outbox relay
payment-response` 와 order-service 쪽이 보이지 않았다.**

Spring Kafka 의 컨슈머 추적(`ContainerProperties.setObservationEnabled(true)`)은 **레코드 단위 리스너에만**
걸린다. 이 저장소의 컨테이너 팩토리 13개는 **전부 배치**(`setBatchListener(true)`)라, 켜 둔 설정이 어디서도
동작하지 않았다. 그래서 pay-service 가 결제 요청을 받아 결제 응답 아웃박스 행을 쓸 때 현재 추적이 없었고
(`capture()` → null), 결제 응답 릴레이는 새 추적으로 시작했다.

**고친 것** — 사가 경로의 두 배치 리스너가 레코드마다 **자기 헤더**로 추적을 이어받은 안에서 처리한다.

- pay-service `PaymentRequestKafaListener`(결제 요청) — 이 안에서 쓰는 결제 응답 행이 원래 추적을 남긴다
- order-service `PaymentResponseKafaListener`(결제 응답) — 주문 확정이 같은 추적에 붙는다

레코드별 헤더는 스프링의 기본 배치 변환기가 `KafkaHeaders.BATCH_CONVERTED_HEADERS`(레코드마다 `Map`, 값은
`byte[]`)로 넘긴다. 리스너에 선택 매개변수로 받고 `OutboxTraceContext.continueFrom(headers, name, work)` 로
감싼다. 헤더가 없으면 그냥 처리한다 — 추적은 부가 정보다.

**가드**

- `OutboxTraceContextTest` — 추적이 실린 레코드를 **스프링의 실제 `BatchMessagingMessageConverter`** 에
  통과시켜 헤더를 받고, 그것으로 이어받은 안에서 쓴 다음 아웃박스 행에 원래 traceId 가 남는지 본다.
  pay-service 에서 일어나는 순서 그대로다.
- `PaymentRequestTraceContinuationTest` — 레코드마다 그 레코드의 헤더로 이어받는지, 헤더 목록이 없어도 결제가
  처리되는지.
- B-60 에 두 리스너의 `continueFrom(` 을 더했다.

나머지 리스너는 §4h 에서 붙였다.

## 4g. 🐛 §4f 를 배포해도 그대로였다 — 운영 팩토리는 레코드 어댑터였다

§4f 뒤에도 Zipkin 의 웹훅 추적은 `outbox relay payment-request → order send` 에서 끝났다. pay-service 도,
`outbox relay payment-response` 도 없었다. 뒤엣것이 **아예** 없었던 것은 결제 응답 행의 `trace_context` 가
비어 있었다는 뜻이다(`resume(null, …)` 은 스팬 없이 그냥 보낸다) — pay-service 에서 이어받기가 일어나지 않았다.

원인: 두 리스너의 컨테이너 팩토리에 `setBatchToRecordAdapter(new DefaultBatchToRecordAdapter<>(…))` 가 있다.
리스너 메서드가 `List<…>` 를 받아도 스프링은 배치를 **레코드 하나씩** 넘기고, 그때 메시지 헤더는 그 레코드의
것이다 — `BATCH_CONVERTED_HEADERS` 는 없다. §4f 는 그 배치 헤더만 받았으니 운영에서 늘 null 이었다.
§4f 의 테스트는 변환기를 직접 부르거나 리스너 메서드를 직접 불러서 이 차이를 보지 못했다.

**고친 것** — 리스너는 `@Headers Map` 전체를 받고 `OutboxTraceContext.recordHeaders(headers, i)` 로 i 번째 레코드의
헤더를 꺼낸다. 배치 변환 헤더가 있으면 그 목록의 i 번째, 없으면 메시지 헤더 자체(레코드 어댑터)다.

**가드** — 이번에는 **스프링이 만든 리스너 어댑터**를 통과시킨다.

- `PaymentRequestListenerAdapterTraceTest`(pay) · `PaymentResponseListenerAdapterTraceTest`(order) — 운영의
  `KafkaConsumerConfig` 그대로 `@KafkaListener` 를 등록하고(브로커 없이, 자동 시작만 끈다) 레지스트리에서 꺼낸
  어댑터에 order-service/pay-service 가 보내는 모양의 레코드(Authorization 은 스프링 헤더 매퍼, traceparent 는
  바이트)를 넣는다. 처리 순간의 현재 추적이 보낸 쪽의 traceId 인지 본다. 추적기는 운영과 같은 **W3C** 전파다
  (Brave 기본값 B3 로는 traceparent 를 못 읽는다). 고치기 전 코드에서 `(no trace)` 로 실패하는 것을 확인했다.
- B-60 — 두 리스너가 `recordHeaders(` 를 쓰는지.

## 4h. 나머지 리스너도 — 모든 Kafka 소비가 보낸 쪽의 추적에 붙는다

사가 두 리스너 밖의 소비는 전부 따로 떨어진 추적이었다. 결제 뒤의 배송 생성·알림, 주문이 보낸 재고 차감·
장바구니 비우기의 **받는 쪽**, 배송 상태 → 주문 상태, 가입 → 쿠폰·채팅 회원·주문 고객 등.

**배치 리스너 21개** — cart(장바구니 비우기·롤백, 쿠폰 사용), catalog(재고), chat(가입), coupon(결제 응답,
쿠폰 롤백), delivery(결제 → 배송), notification(알림 8개·공지·기획전·메일 명령), order(가입, 배송 상태).
루프 본문은 그대로 두고 `try {` 한 줄만 스코프로 바꿨다:

```java
for (int i = 0; i < messages.size(); i++) {
    try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
            OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive cart-empty")) {
        ... 예전 본문 — continue·체크 예외 그대로 ...
    } catch (Exception e) {
        throw new BatchListenerFailedException("…", e, i);
    }
}
```

`openFrom` 은 `continueFrom` 의 스코프 모양이다(람다로 감싸면 `continue`·체크 예외가 있는 본문이 깨진다).
리스너는 `@Headers Map<String, Object> kafkaHeaders` 를 받는다 — 보통 배치 팩토리면 스프링이
`BATCH_CONVERTED_HEADERS` 로 레코드별 헤더를 넘기고 `recordHeaders` 가 i 번째를 꺼낸다. 한 배치에 서로 다른
추적의 레코드가 섞여 있어도 레코드마다 제 추적에 붙는다.

⚠️ 스팬은 `try` 가 끝날 때 닫힌다. `catch` 에서 찍는 로그(예: 낙관적 락 충돌 경고)에는 traceId 가 없다.

**레코드 단위 리스너 2개** — chat 의 채팅 메시지(`MessageConsumer`)는 팩토리 옵저베이션이 이미 켜져 있어
스프링이 이어받는다. coupon 의 가입 쿠폰(`UserCreatedConsumer`, `setBatchListener(false)`)은 그 팩토리만
옵저베이션이 빠져 있어 켰다.

**가드**

- `PaymentCompletedListenerAdapterTraceTest`(delivery) — 운영 `KafkaConsumerConfig` 로 등록한 실제 어댑터에
  **서로 다른 추적의 레코드 두 건을 한 배치로** 넣어 각자 제 추적 안에서 처리되는지 본다(보통 배치 팩토리의
  대표). 고치기 전 코드에서는 둘 다 `(no trace)`.
- B-60b — 모든 `@KafkaListener` 파일이 리스너 수만큼 `recordHeaders(` 를 쓰는지(레코드 단위 2개는 목록으로
  예외), 모든 컨테이너 팩토리가 `setObservationEnabled(true)` 인지. 새 리스너가 빠뜨리면 실패한다.

## 4i. 로그 경보 — "수동 확인 필요" 와 품절을 사람에게 알린다

돈·재고가 이미 움직였는데 뒤따를 일이 실패하면 코드는 `… — 수동 확인 필요` 를 error 로 남긴다(재고·쿠폰 복원
발행 실패, 환불액·통화 불일치, 취소된 주문에 도착한 결제 = "수동 환불 필요"). 자동 재시도가 없으니 **누가
로그를 보지 않으면 아무도 모른다.** 메트릭이 없는 일이라 Prometheus 경보로는 못 잡는다.

**Loki ruler** 를 켰다(`observability/loki-config.yml` 의 `ruler:`). 규칙은 `observability/loki-rules/
ecommerce-log-alerts.yml` — 1분마다 평가해 **기존 Alertmanager** 로 보낸다. 받는 채널은 Prometheus 경보와 같다
(severity 로 critical/warning 웹훅이 갈린다, `ops/17`).

| 경보 | 조건(최근 5분) | severity |
|---|---|---|
| `ManualActionRequired` | `{job=~"ecommerce.*"} \|~ "수동 (확인\|환불)이? 필요"` 가 한 줄이라도 | critical |
| `ProductSoldOut` | catalog-service 의 `품절 발생` (docs/53) | warning |

규칙 파일은 compose 가 `/etc/loki/rules/fake` 에 붙인다 — `auth_enabled: false` 인 Loki 의 테넌트 이름이
`fake` 라 그 아래에 있어야 읽힌다. 적용: `docker compose -f docker-compose-observability.yml up -d loki`.
확인: `ops/05` 의 "Loki 로그 경보 규칙"(두 규칙과 상태), 또는 Grafana → Alerting → Alert rules(데이터소스 규칙).

⚠️ **문구가 계약이다.** "수동 확인 필요" 를 다른 말로 바꾸면 경보가 조용히 안 온다. **B-61** 이 코드의
log.error/warn 중 "수동 … 필요" 문구가 전부 규칙의 정규식에 걸리는지, 규칙 마운트·ruler 설정이 있는지 본다.

## 4b. 🐛 첫 배포에서 드러난 것 — 모든 줄이 한 줄로 붙었다

배포하자 콘솔이 이렇게 찍혔다:

```
… 're-run your application with 'debug' enabled.%n2026-09-24 12:53:07.336 [main] [,,] ERROR …
```

줄바꿈 자리에 **`%n` 이 글자로** 나오고 다음 줄이 그대로 이어 붙었다. logback 은 복합 변환자
`%mask(...)` **바로 뒤에 붙은** `%` 를 변환어로 읽지 않는다 — `%mask(%msg)%n` 은 `hi%n` 이 된다.
빈 옵션 `{}` 로 복합 변환자를 닫으면(`%mask(%msg){}%n`) 정상이다. facebook 쪽 패턴
(`%mask(%msg)%n%mask(%ex)`)도 같은 모양이다.

`CommonLogbackConfigTest` 는 가려졌는지만(`contains`) 봤지 **줄이 나뉘는지는 보지 않았다.** 두 이벤트가
각자 한 줄인지 보는 테스트를 더했고, B-57b 가 `%mask(...)` 바로 뒤의 `%` 를 막는다.

## 5. 남은 것

- **사가 밖의 배치 리스너**는 아직 레코드 헤더로 추적을 이어받지 않는다(§4f) — 배송 생성·알림·장바구니
  비우기·쿠폰 등. 컨슈머 팩토리 13개가 전부 배치라 `setObservationEnabled(true)` 는 어디서도 효과가 없다.
- **관측 스택은 노드의 compose 다.** k3s 파드의 로그(§4c)와 추적(§4d)은 같은 노드의 compose 스택으로 간다.
  노드가 여럿이 되면 파드는 자기 노드의 Zipkin 으로 보내므로, 그때는 클러스터 안에 수집기를 두어야 한다.
- **런타임 로그 레벨 변경**(actuator `loggers`)은 열지 않았다. 인증 없이 열면 facebook 쪽처럼 누구나 레벨을
  바꿀 수 있다 — 관리 포트 분리나 인증과 함께 해야 한다.
- **로그 기반 경보**(ERROR 급증 등)와 로그 폭주 방어(초당 상한)는 이번 범위 밖이다.
- **config-server** 는 common 을 쓰지 않아 마스킹·JSON 이 적용되지 않는다. 사용자 데이터를 다루지 않는다.
