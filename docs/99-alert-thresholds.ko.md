# 99. 경보 기준은 어떻게 잡나 — 실무 원칙과 ERROR 로그 경보의 실측 기준

[日本語](99-alert-thresholds.md) | **한국어**

> 📝 **상태(2026-11-21)** — 신뢰성 1단계에서 Loki `ErrorLogSurge` 를 "5분 30줄" 로 처음 넣었다가, 운영 Loki 7일치로 **백테스트**해 모양을 바꿨다.
> 이 문서는 ① 실무에서 경보 기준을 잡는 원칙 ② 이 시스템의 측정 · 백테스트 결과 ③ 기준 아래에 숨어 있던 상시 ERROR ④ 다시 재는 법을 적는다.
> 관련: [51 로깅](51-logging.ko.md) · [91 SLO 와 용량](91-slo-and-capacity.ko.md) · [62 기술 사례집 §14](62-engineering-casebook.ko.md).

## 0. 한눈에

| | 결론 |
|---|---|
| 원칙 | 사용자 증상(SLO · 5xx)에는 사람을 깨우고, 원인 신호(ERROR 로그 수)는 warning. 기준은 **실데이터 분포**에서, **평소 대비 + 바닥**으로, **백테스트**로 확인 |
| 측정 | 운영 7일: ERROR 의 99% 가 eureka-server(5분당 ~100줄 — 자기 복제 403) · config-server(5분당 ~10줄 — Eureka 하트비트 실패). 나머지 서비스는 p99 ≤ 1줄 |
| 처음 기준(5분 30줄) | eureka 때문에 **7일 내내 울린 채** — 쓸모없다 |
| 지금 기준 | 5분 20줄 초과 **그리고** 직전 1시간 평균의 3배 초과, `for: 5m` — 백테스트에서 eureka 잡음은 0, 배포 시각 gateway 폭증(33 · 38줄)은 잡음 |
| 함정 | LogQL `and` 는 평소 값이 없는(조용하던) 서비스를 떨어뜨린다 → `unless` 로 쓴다 |
| 남은 일 | 상시 ERROR 둘(eureka · config-server)은 **원인을 고친다** — 경보 기준으로 덮을 일이 아니다(§4) |

## 1. 실무에서 기준을 잡는 원칙

1. **증상에는 사람을 깨우고, 원인에는 티켓을 끊는다.** Google SRE 의 기본 원칙이다. critical(호출)은 사용자가 느끼는 것 — SLO 소진율 · 5xx · 지연 — 에만 건다. ERROR 로그 수는 원인 쪽 신호라 warning · 티켓 수준이다. 이 시스템은 SLO 소진율 경보가 이미 있으므로(docs/91) `ErrorLogSurge` 는 warning 이다.
2. **기준은 실데이터 분포에서 뽑는다.** 2 ~ 4주(이 시스템은 Loki 보존이 7일) 동안 서비스마다 5분당 수를 모아 평소 최댓값 · p99 위에 여유를 둔다. 서비스마다 평소가 다르므로 하나의 숫자를 모든 서비스에 쓰지 않는다 — 쓰려면 '평소 대비' 로 쓴다.
3. **절대값 하나보다 '평소 대비' + '바닥'.** 예: 최근 5분이 직전 1시간 평균의 3배를 넘고, 동시에 바닥(예: 20줄)을 넘을 때. 바닥이 없으면 평소 0 ~ 1줄인 서비스의 2줄도 울리고, 평소 대비가 없으면 늘 시끄러운 서비스가 늘 울린다. 가능하면 줄 수보다 요청 대비 비율(ERROR ÷ 요청)이 더 안정적이다.
4. **건수보다 '처음 보는 오류' 가 더 쓸모 있을 때가 많다.** Sentry 류는 오류를 종류(예외 + 위치)로 묶어 새 종류가 처음 나올 때 알린다 — 배포 직후 새 예외 하나가 익숙한 오류 수백 줄보다 중요하다. Loki 만으로는 어렵다 — 규모가 커지면 오류 추적 도구를 따로 둔다.
5. **울려야 할 때만 울리게 다듬는다.** `for`(지속 시간)로 순간 튐을 거르고, 배포 시간에는 묵음. 경보마다 '울리면 무엇을 하나'(런북)를 적고, 지난달 울린 것 중 **실제로 조치한 비율**을 본다 — 조치 없이 넘긴 경보는 기준을 고치거나 지운다.
6. **로그 레벨 위생이 먼저다.** ERROR 는 '사람이 무언가 해야 하는 일' 에만. 예상된 실패(입력 오류 · 401 · 재시도로 풀리는 일시 오류)는 WARN 이하. 이게 안 돼 있으면 어떤 기준도 잡음이다(§4).
7. **백테스트한다.** 후보 규칙을 지난 데이터에 돌려 '몇 번 울렸을까' 를 세고 고른다(§3). Prometheus 경보는 promtool 단위 시험(`observability/tests/*.test.yml`)으로 '울릴 때만 울린다' 를 고정한다.

## 2. 측정 — 운영 Loki 7일(2026-11-14 ~ 21)

서비스마다 5분당 ERROR 줄 수(`sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [5m]))`, 5분 간격 · 비면 0):

| 서비스 | 7일 합 | 0 이 아닌 5분창 | p95 | p99 | 최대 | 30줄 넘은 창 |
|---|---|---|---|---|---|---|
| eureka-server | 165,174 | 2,018(전부) | 100 | 110 | 110 | 2,017 |
| config-server | 20,174 | 2,018(전부) | 10 | 10 | 12 | 0 |
| user-service | 119 | 24 | 0 | 1 | 20 | 0 |
| gateway-server | 73 | 3 | 0 | 0 | 38 | 2 |
| delivery-service | 37 | 37 | 0 | 1 | 1 | 0 |
| saga-projection | 4 | 4 | 0 | 0 | 1 | 0 |
| 그 밖(order · catalog · cart · pay · coupon · chat · notification) | 0 | 0 | | | | |

가장 많은 문구(숫자 · 주소 · 식별자를 가려 묶음):

| 서비스 | 문구 | 비고 |
|---|---|---|
| eureka-server | `ReplicationTaskProcessor - Batch update failure with HTTP status code 403; discarding N replication tasks` — 대상 `eureka-server.ecommerce.svc.cluster.local` | 7일 표본 2,000줄 모두. 지금도 계속(§4) |
| config-server | `DiscoveryClient … was unable to send heartbeat! … Cannot execute request on any known server` | 7일 내내. 지금도 계속(§4) |
| user-service | `JwtAuthEntryPoint - Unauthorized error: Full authentication is required …` 67 · `Application run failed … Unable to start web server` 19 · `Servlet.service() … threw exception` 29 | 401 은 정상 동작인데 ERROR. 기동 실패는 배포 시각 |
| gateway-server | `RedisRateLimiter - Error calling rate limiter lua … QueryTimeoutException` 71 | 배포 시각(11-20 15:50 ~ 16:00)에 몰림 |
| delivery-service | `TrackingPoller - 추적 영구 실패: … 택배사=KOREA_POST HTTP 400` | 약 4시간에 1줄 |
| saga-projection | `StateDirectory - Error changing permissions for the directory /app/state` | 기동 때 |

## 3. 후보 규칙 백테스트

같은 7일에 후보 셋을 Loki 질의로 돌리고(5분 간격), 두 창 연속 참(≈ `for: 5m`)을 '울림' 으로 셌다:

| 후보 | 식(요지) | eureka-server | gateway-server(배포 폭증) | 판정 |
|---|---|---|---|---|
| A | `5분 > 30` | 2,018창 참 — **한 번 울려 7일 내내 켜짐** | 2창 | ✗ 늘 켜진 경보 |
| B | `(5분 > 20) and (5분 > 3 × 1시간 평균)` | 잡음 걸러짐 | **놓침** | ✗ 조용하던 서비스를 못 잡는다 |
| C | `(5분 > 20) unless (5분 ≤ 3 × 1시간 평균)` | 잡음 걸러짐 | 2창(33 · 38줄) 잡음 | ✅ 채택 |

**B 의 함정**: 직전 1시간에 ERROR 가 0줄이던 서비스는 '평소' 시계열이 아예 없다. `and` 는 짝이 있는 시계열만 남기므로 그 서비스의 조건 전체가 사라진다 — 평소 조용하던 서비스가 갑자기 터지는, 가장 잡고 싶은 경우를 놓친다. `unless` 는 오른쪽(평소 대비 조용함)에 짝이 없으면 왼쪽을 남긴다 → 평소 값이 없으면 바닥만으로 판단한다.

(eureka 의 11-14 04:05 한 번은 질의 범위의 시작점이라 '직전 1시간' 이 비어 생긴 착시다 — 실제 운영에서는 평소 값이 늘 있다.)

채택한 규칙(`observability/loki-rules/ecommerce-log-alerts.yml` 의 `error-rate`):

```
(sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [5m])) > 20)
unless
(sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [5m]))
  <= 3 * (sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [1h] offset 5m)) / 12))
for: 5m · warning
```

⚠️ **Loki 규칙 파일은 하나라도 LogQL 오류가 있으면 통째로 빠진다**(API 는 `groups: []` 로 성공을 돌려준다 — critical `ManualActionRequired` 까지 꺼진다). `LokiRulesLoadTest`(컨테이너, Loki 3.4.2)가 파일의 그룹 이름 = 올라간 그룹 이름을 본다.

## 4. 기준 아래에 숨은 것 — 상시 ERROR

'평소 대비' 는 늘 깨져 있는 것을 **경보에서 감춘다**. 감춘 것은 따로 고친다 — 이 목록이 그 일이다.

| 무엇 | 영향 | 할 일 |
|---|---|---|
| eureka-server 가 자기 자신(`eureka-server.ecommerce.svc.cluster.local`)에 피어 복제 → **403**(Eureka 쓰기 판정 enforce) | 인스턴스 하나라 복제는 쓸모없고 버려질 뿐 — 기능 영향은 없어 보인다. ERROR 의 9할 | ✅ 원인 확정 · 고침(2026-11-21, 다음 배포): 이 파드도 ConfigMap 의 defaultZone(자기 Service 주소)을 받는데 `my-url` 은 yml 의 localhost — Eureka 는 equals 로 비교해 자기를 걸러내지 못했다(10-26 의 yml 고침은 k8s 에서 효과 없음). k8s/30 `EUREKA_SERVER_MYURL`(= defaultZone + `/`), 시험 `PeerSelfReplicationTest`. 배포 뒤 이 줄 0 |
| config-server 의 Eureka **하트비트 실패**(`Cannot execute request on any known server`) | config-server 가 Eureka 에 등록 · 갱신되지 못한다. 설정을 Eureka 로 찾는 클라이언트가 있으면 영향 | ✅ 원인 확정 · 고침(2026-11-21, 다음 배포): 실제 요청이 `http://localhost:8761`(Connection refused). yml 에 `defaultZone` 키가 없으면 env 값이 소문자 키 `defaultzone` 으로 따로 들어가 기본값이 쓰인다(Spring Map 바인딩) — config-server 는 common 에 의존하지 않아 그 키가 없었다. yml 에 키, 시험 `EurekaServiceUrlBindingTest`. 배포 뒤 처음으로 인증서를 내며 등록한다(키스토어 · 트러스트스토어는 파드에 있음 확인) |
| user-service 의 정상 401(`JwtAuthEntryPoint`)이 ERROR | 잡음 — 공격 · 만료 토큰마다 ERROR | WARN(또는 INFO)으로 내린다 |
| gateway 의 유입 제한 Redis 타임아웃 · user 기동 실패 | 배포 시각에만 | 배포 시각의 경보는 묵음 · 기동 실패는 재시작 수로 이미 본다 |
| delivery 의 우체국 추적 HTTP 400 | 그 송장의 추적이 멈춤 | 서비스키 · 송장 형식 확인(docs/25), 영구 실패면 WARN + 관리자 화면 표시 |
| saga-projection 의 상태 디렉터리 권한 | 기동 때 한 번 | 볼륨 권한(fsGroup) 확인 |

## 5. 다시 재는 법

운영 노드에서(Loki 는 노드의 compose, 보존 7일). 질의는 읽기만 한다:

```bash
# 서비스마다 5분당 ERROR 수 — 하루씩(질의 상한)
curl -s -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query=sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"}[5m]))' \
  --data-urlencode start=<시작 ns> --data-urlencode end=<끝 ns> --data-urlencode step=300

# 후보 규칙을 그대로 query_range 에 넣으면 '참인 5분창' 이 나온다 — 두 창 연속을 '울림' 으로 센다
# 문구 표본: query={job=~"ecommerce.*", level="ERROR", service="<서비스>"} · limit=2000 · direction=backward
```

- 숫자 · 주소 · 식별자는 가려서 묶는다(문서 · 대화에 원문 줄을 붙이지 않는다 — 이메일 · IP 가 섞일 수 있다).
- 상시 ERROR 를 고친 뒤 · 서비스가 늘면 다시 재고, 바닥(20)과 배수(3)를 이 문서에 적는다.

## 6. 다른 경보에도

- Prometheus 경보는 promtool 단위 시험으로 '울릴 때만 울린다' 를 고정한다(`reliability-rules.test.yml` · `slo-rules.test.yml` 등). 기준을 바꾸면 시험의 입력도 실제 분포에 맞춘다.
- 새 경보는 '울리면 무엇을 하나' 를 주석 · description 에 적는다. 한 달에 한 번 울린 경보 목록을 보고, 조치 없이 지나간 것은 기준을 고치거나 지운다.
