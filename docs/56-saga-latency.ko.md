# 56. 결제 사가 지연 — 폴링 간격 두 번을 커밋 직후 릴레이로

[日本語](56-saga-latency.md) | **한국어**

> 결제를 마친 사용자가 "결제 확인 중…" 을 **평균 10초, 길면 20초** 보고 있었다(실측 약 12초).
> 사가가 느린 것이 아니라, 사가가 **10초 폴러를 두 번** 건너고 있었다.

## 1. 증상

결제 확인(복귀 검증·웹훅)이 끝나면 화면은 `useOrderSettling` 으로 주문을 2초마다 다시 읽으며
`NOT_PROCESSED` 를 벗어나기를 기다린다(30초 시한). 그 기다림이 매번 10초 안팎이었고, 운이 나쁘면
20초를 넘겨 시한에 가까워졌다.

## 2. 원인 — 폴러 두 개가 직렬로

```
결제 확인 ─ 커밋 ─┐
                  │  ⏳ order PaymentOutboxScheduler  (fixedDelay 10초)
                  └─► payment-request ─► pay-service 리스너 ─ 커밋 ─┐
                                                                   │  ⏳ pay OrderOutboxScheduler (fixedDelay 10초)
                                                                   └─► payment ─► OrderSaga ─► PAID
```

- 두 스케줄러 모두 `outbox-scheduler-fixed-rate: 10000`. 행이 커밋된 순간부터 다음 폴링까지 0~10초.
- 두 번 건너므로 대기는 **0~20초, 평균 약 10초.** Kafka·리스너·DB 처리 자체는 수백 ms 다.
- 폴러는 `FOR UPDATE SKIP LOCKED` 선점 + 트랜잭션 안 동기 발행이라 레플리카가 여럿이어도 안전하다
  (docs/21 §1). 문제는 **정확성이 아니라 대기**였다.

## 3. 고르지 않은 것

| 선택지 | 버린 이유 |
|---|---|
| 간격을 1초로 | 대기는 줄지만 두 서비스 × 레플리카가 1초마다 `SKIP LOCKED` 조회를 날린다. 대부분 빈손이다. 그래도 평균 1초가 남는다 |
| 커밋 전(트랜잭션 안)에서 바로 발행 | 아웃박스를 쓰는 이유 자체를 버린다 — 발행은 됐는데 롤백되면 유령 결제 요청이 나간다 |
| Debezium CDC 로 전환 | 지연은 가장 짧지만 커넥터·슬롯 운영이 따라온다. 커넥터 설정은 있으나(`*-outbox-connector-*.json`, 규칙은 docs/38 §5) `k8s/` 에 Kafka Connect 가 없어 운영 경로가 아니다 |
| `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` | 같은 일을 하지만, 몰려오는 커밋을 합치는 장치가 없어 주문 100건이면 릴레이 100번이 겹쳐 돈다 |

## 4. 한 것 — 커밋 직후 한 번, 폴러는 안전망

`common/outbox/OutboxRelayTrigger`:

1. 헬퍼가 발행 대기(`STARTED`) 행을 쓰면 `relayTrigger.afterCommit()` 을 부른다.
   - order: `PaymentOutboxHelper.savePaymentOutbox`·`saveOrderStatusOutbox`
   - pay: `OrderOutboxHelper.saveOrderOutbox`
2. 트랜잭션 안이면 `TransactionSynchronization.afterCommit` 을 건다 — **커밋 전에는 행이 안 보이고,
   롤백되면 보낼 것이 없다.** 트랜잭션 밖이면(이미 커밋됐다) 바로.
3. 커밋 콜백은 단일 데몬 스레드에 릴레이를 넣고 곧바로 돌아온다. 요청 스레드는 Kafka 를 기다리지 않는다.
4. 그 스레드가 스케줄러의 `processOutboxMessage()` 를 **스프링 프록시로** 부른다 — 폴러와 똑같은
   `@Transactional` + `SKIP LOCKED` 선점 경로다. 스케줄러는 `ObjectProvider` 로 꺼낸다
   (스케줄러 → 헬퍼 → 트리거 → 스케줄러 순환을 피하고, 꺼낸 것이 프록시라야 잠금이 발행 끝까지 간다).

### 합치기

상태는 셋이다 — `IDLE`, `RUNNING`, `RERUN`.

- `IDLE` 에서 요청 → `RUNNING`, 실행기에 넣는다.
- `RUNNING` 중에 요청 → `RERUN` 으로 표시만 하고 돌아온다. 몇 번이 와도 표시는 하나다.
- 한 번 끝나면 `RUNNING → IDLE` 을 시도하고, 실패(= `RERUN`)면 한 번 더 돈다.

주문 50건이 한 릴레이 도중에 커밋되면 **릴레이는 두 번** 돈다(테스트로 고정). 두 번째가 배치로
가져간다.

### 안전망은 그대로

즉시 릴레이는 지름길일 뿐이고, 전달 보장은 여전히 아웃박스 + 폴러가 진다.

| 놓치는 경우 | 결과 |
|---|---|
| 커밋 직후 파드가 죽음 | 행은 `STARTED` — 다음 기동·다른 레플리카의 폴러가 가져간다 |
| 브로커가 흔들려 발행 실패 | 스케줄러의 기존 처리 그대로(행을 남기고 배치 중단). 트리거는 예외를 삼키고 로그만 — 상태가 `RUNNING` 에 걸리지 않는다 |
| 종료 중이라 실행기가 거절 | `IDLE` 로 되돌리고 넘어간다 |
| 폴러와 동시에 돎 | 같은 `SKIP LOCKED` 선점 — 레플리카 두 개가 동시에 도는 것과 같다. 한 행은 한 쪽만 |

그래서 폴링 간격(10초)은 바꾸지 않았다. 이제 그 숫자는 "사가 지연" 이 아니라 "놓친 것을 되줍는 주기" 다.

### 부르지 않는 곳

상태 갱신용 `save()`(릴레이가 `COMPLETED`/`FAILED` 를 적을 때)에서는 부르지 않는다. 거기서 부르면
릴레이가 끝날 때마다 자기 자신을 다시 예약한다 — 한 번 더 도는 것으로 끝나긴 하지만 의미 없는 조회다.
테스트가 이것을 고정한다.

## 5. 결과

| | 전 | 후 |
|---|---|---|
| 결제 확인 → PAID | 0~20초(평균 약 10초, 실측 약 12초) | Kafka 왕복 두 번 — 보통 1초 안 |
| 화면이 PAID 를 보는 시점 | 위 + 최대 2초(재조회 간격) | 사가가 끝난 뒤 첫 재조회 — 누적 0.3 / 0.8 / 1.5 / 2.5초 중 하나(§8) |
| 빈 폴링 | 서비스당 10초에 한 번 | 그대로 |

⚠️ "보통 1초 안" 은 구조에서 나온 추정이다. 당시 검증 환경에는 브로커가 없어 실측하지 못했다. 배포 뒤
Zipkin 에서 `outbox relay payment-request` 스팬과 결제 확인 요청 스팬의 간격으로 확인할 수 있다
(아웃박스를 건너도 추적이 이어진다 — docs/51 §4e).

## 6. 끄는 법

```yaml
order-service.outbox-relay-on-commit: false   # 또는 OUTBOX_RELAY_ON_COMMIT=false
pay-service.outbox-relay-on-commit: false
```

끄면 폴러만 남는다 — 예전과 같다. 즉시 릴레이가 의심될 때 코드 되돌림 없이 확인하는 스위치다.

## 7. 검증

- `common` `OutboxRelayTriggerTest`(9) — 커밋 전에는 안 돈다, 롤백이면 안 돈다, 도는 중 50건 → 두 번,
  실패 뒤에도 다음 요청이 돈다, 실행기 거절 무시, 꺼져 있으면 콜백도 안 건다, `close()`.
- order `PaymentOutboxRelayOnCommitTest`(3), pay `OrderOutboxRelayOnCommitTest`(2) — `STARTED` 행만
  예약, 상태 갱신은 예약하지 않는다.
- `PaymentOutboxClaimConcurrencyTest` 는 선점 동작만 보도록 즉시 릴레이를 끈다(배경 릴레이가 행을
  가져가면 불안정해진다 — 폴러를 1시간으로 미룬 것과 같은 이유).

## 8. 프론트 재조회 — 앞만 촘촘하게

사가가 1초 안에 끝나자, 사용자가 기다리는 시간의 대부분은 **화면의 첫 재조회까지의 2초**였다
(`useOrderSettling`, 처음부터 2초 간격).

전 구간을 촘촘히 하지 않은 이유: 즉시 릴레이가 놓치면 폴러가 되줍는 동안(최대 20초) 요청이
쏟아진다. 그래서 **앞만** 촘촘하다.

| 재조회 | 1 | 2 | 3 | 4 | 5 | 6 | 그 뒤 |
|---|---|---|---|---|---|---|---|
| 간격 | 0.3초 | 0.5 | 0.7 | 1.0 | 1.0 | 1.5 | 2초마다 |
| 누적 | 0.3초 | 0.8 | 1.5 | 2.5 | 3.5 | 5.0 | 7, 9, … |

- 첫 5초에 여섯 번, 30초 시한까지 열아홉 번쯤(예전 열다섯 번). 시한·`timedOut` 은 그대로다.
- 다음 재조회는 앞 요청이 끝난 뒤에 건다 — 응답이 늦어도 요청이 겹치지 않는다(원래 구조).
- 같은 훅을 쓰는 배송 취소 뒤 PAID 복귀·송장 등록 뒤 단계 따라잡기도 같이 빨라진다. 둘 다 Kafka
  왕복 하나라 사정이 같다.
- 테스트: 첫 재조회가 0.3초에 나간다(예전 코드면 실패), 간격이 늘다 2초에서 멈춘다, 시한까지 20번을
  넘지 않는다.

## 9. user-service 아웃박스 — 가입 직후의 빈 10초

사가 경로는 아니지만 같은 10초 폴러(`UserOutboxScheduler`)였고, 사용자가 곧바로 부딪히는 자리가 있었다.

| 이벤트 | 받는 쪽 | 폴러만 있을 때 |
|---|---|---|
| `user-created` | coupon(가입 쿠폰), chat(채팅 회원 복제본) | 가입 확인 직후 쿠폰함이 비어 있고, 채팅은 즉석 회원(원본 프로필 없음)으로 들어갔다 |
| `user-updated` | chat | 닉네임·사진을 바꾸고 채팅으로 가면 옛 값 |
| `user-deleted` | chat | 탈퇴 뒤 최대 10초 동안 복제본에 식별정보 |

`UserOutboxHelper` 의 세 기록 메서드가 행을 쓴 뒤 `relayTrigger.afterCommit()` 을 부른다. 셋 다
`Propagation.MANDATORY` 라 언제나 트랜잭션 안이다 — 인증 확정·프로필 저장·탈퇴 처리가 **커밋된 뒤에**
돌고, 롤백되면 돌지 않는다. 배선은 order·pay 와 같다(`UserOutboxRelayConfig`, `ObjectProvider` 로 프록시를 꺼낸다).

⚠️ user-service 릴레이는 발행이 실패하면 행을 **FAILED 로 남기고 자동 재시도하지 않는다**(order·pay 는
STARTED 로 남겨 다음 폴링이 다시 집는다). 즉시 릴레이도 같은 메서드를 부르므로 이 동작은 바뀌지 않는다 —
실패가 폴러 차례에 나든 커밋 직후에 나든 결과는 같다. 되줍는 것은 여전히 대사 배치의 몫이다(docs/48 ③).

- 끄기: `app.outbox.relay-on-commit: false`(`APP_OUTBOX_RELAY_ON_COMMIT`).
- 테스트: `UserOutboxRelayOnCommitTest`(2) — 가입·변경·탈퇴 모두 한 행에 한 번 예약.

## 10. 남은 것

- 더 줄이려면 재조회가 아니라 SSE/WebSocket 으로 서버가 밀어 주는 쪽이 맞다. 지금은 사가 한 번에
  요청 서너 번이라 그 비용을 들일 이유가 약하다.
- catalog 의 `StockShortagePublisher`(2초) 같은 다른 폴러는 그대로다 — 사람이 기다리는 경로가 아니다.
