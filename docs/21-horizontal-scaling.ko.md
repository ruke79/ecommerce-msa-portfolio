# 21. 수평 확장 — 레플리카를 늘려도 안전한가

[日本語](21-horizontal-scaling.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** §5 의 결제 리스너 `Authorization` 헤더 요구는 없어졌습니다(사가 메시지에서 토큰을 뺐습니다). notification · delivery · saga-projection 도 `replicas: 2` 입니다.

> 배경: [docs/00 §10](00-before-after.ko.md) 이 "전 서비스 `replicas: 1` — 재시작·노드 교체마다
> 그대로 다운타임"을 운영 준비의 걸림돌로 꼽았다. 그런데 숫자를 2로 바꾸는 것이 작업의 전부가
> 아니었다. **늘리는 순간 조용히 깨지는 곳**이 있었다.

## 1. 아웃박스 폴러가 같은 이벤트를 두 번 발행하고 있었다

`order-service` 와 `pay-service` 는 아웃박스 테이블을 폴링해 Kafka 로 내보낸다. 예전 구현은
레플리카가 하나일 때만 옳았다.

```java
// 예전 — 잠금 없는 조회
Optional<List<PaymentOutboxEvent>> events =
        helper.getPaymentOutboxEventByOutboxStatus(OutboxStatus.STARTED);

events.get().forEach(outbox -> publisher.publish(outbox, this::updateOutboxStatus));
```

문제가 **두 겹**이다.

**① 잠금이 없다.** 레플리카 A 와 B 가 같은 순간에 폴링하면 같은 행을 읽는다. 둘 다 발행한다.

**② 상태 갱신이 트랜잭션 밖에서 일어난다.** `publish` 는 비동기였다 —
`kafkaTemplate.send(...).whenComplete(...)` 안에서 `COMPLETED` 로 바꿨다. 그 콜백은 Kafka
프로듀서 IO 스레드에서, **스케줄러 트랜잭션이 이미 커밋된 뒤** 실행된다. 즉 잠금을 걸었더라도
상태가 바뀌기 전에 잠금이 풀린다. ①만 고쳐서는 창이 닫히지 않는다.

결과는 **결제 요청이 두 번 발행**되는 것이다. 돈이 걸린 경로다.

> ⚠️ 이건 "레플리카를 늘리면 생길 문제"가 아니라 **이미 켜져 있던 지뢰**였다.
> `k8s/70-autoscaling.yaml` 의 HPA 가 order-service·pay-service를 `maxReplicas: 3` 으로
> 대상에 넣고 있었다. 부하가 오르면 오토스케일러가 알아서 파드를 늘리고, 그 순간 중복 발행이
> 시작된다. 아무도 replicas 를 손대지 않아도 일어난다.

### 어떻게 고쳤나

**선점(claim) + 동기 발행.** 한 트랜잭션 안에서 행을 잠그고, 발행을 기다리고, 상태를 확정한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP_LOCKED
@Query("select e from PaymentOutboxEvent e where e.outboxStatus = :status order by e.createdAt asc")
List<PaymentOutboxEvent> claimByOutboxStatus(OutboxStatus status, Pageable pageable);
```

`SKIP LOCKED` 가 핵심이다. 일반 `FOR UPDATE` 라면 B 가 A 를 **기다렸다가** A 가 커밋한 뒤 같은
행을 다시 집는다 — 중복이 그대로 난다. 건너뛰어야 서로 겹치지 않는 집합을 가져간다.

발행은 `.get(timeout)` 으로 트랜잭션 안에서 기다린다. 잠금이 상태 확정까지 유지되므로 ②의 창이
사라지고, 덤으로 "트랜잭션 없이 `save()` 가 불리던" 이상한 상태도 없어진다.

배치 크기도 제한했다(`outbox-batch-size`, 기본 100). 제한이 없으면 한 레플리카가 밀린 이벤트를
전부 잠그고 나머지는 놀게 된다.

### 덤으로 드러난 것 — 일시적 실패가 이벤트를 영구히 버렸다

예전에는 전송 실패 시 `FAILED` 로 적었다. 그런데 스케줄러는 `STARTED` 만 조회한다.
**브로커가 잠깐 흔들린 것뿐인데 그 결제 요청은 다시는 시도되지 않는다** — 조용히 사라진다.

이제 일시적 실패에는 상태를 건드리지 않는다. 다음 폴링이 자연히 재시도한다. `FAILED` 는
재시도해도 소용없는 경우(페이로드 파싱 불가, 알 수 없는 이벤트 타입)에만 쓴다.
그리고 실패가 나면 **그 배치를 중단**한다 — 계속 시도하면 타임아웃 × 배치 크기만큼
행 잠금과 DB 커넥션을 쥐고 있게 된다.

### 검증

단위 테스트로는 잡을 수 없다. 진짜 동시 트랜잭션 두 개와 진짜 PostgreSQL 이 필요하다.
`PaymentOutboxClaimConcurrencyTest` 는 두 스레드가 각자 트랜잭션에서 선점하고 **둘 다 끝날
때까지 커밋하지 않는다**(먼저 커밋하면 잠금이 풀려 아무것도 증명하지 못한다).

잠금을 빼고 돌려 보면:

```
AssertionError: 두 레플리카가 같은 아웃박스 이벤트를 집으면 결제 요청이 두 번 발행된다.
겹친 id: [5245196f-…, c813a273-…, 0cc99a79-…, a2830689-…]
```

배치 4건이 **100% 겹쳤다.** 잠금을 되돌리면 겹침 0.

> Kafka 는 여전히 at-least-once 다. 커밋 직전에 파드가 죽으면 재발행된다. 이 변경이 없애는
> 것은 **레플리카 때문에 생기는** 중복이지 전달 보장에서 오는 중복이 아니다. 소비 측 멱등성은
> 별개로 필요하다(§3).

## 2. 늘리면 안 되는 서비스 두 개

| 서비스 | 왜 안 되나 | 늘리려면 |
|---|---|---|
| **chat-service** | STOMP 브로커가 인메모리(`enableSimpleBroker`)다. 게다가 Kafka 컨슈머 그룹은 메시지를 파드 **하나에만** 준다. A 파드에 접속한 사용자는 B 파드가 소비한 메시지를 못 받는다 | 브로커 릴레이(RabbitMQ/ActiveMQ) 또는 Redis pub/sub 팬아웃 |
| **eureka-server** | standalone 설정(`register-with-eureka: false`)이다. 2개면 서로를 모르는 **독립 레지스트리 두 개**가 되어, 어느 파드에 등록됐는지에 따라 디스커버리 결과가 갈린다 | peer-aware 설정 + 안정적 DNS(StatefulSet) |

둘 다 **조용히** 깨진다. 파드는 Running 이고 로그도 깨끗한데 사용자만 메시지를 못 받는다.
그래서 주석으로 남기는 것으로는 부족하다고 보고 가드레일 **B-34** 가 빌드에서 막는다 —
레플리카가 1이 아니거나 HPA 대상에 들어가면 실패한다.

## 3. 아직 남은 것 — 소비 측 멱등성

멱등 원장(`processed_event`)은 **catalog-service 와 coupon-service 에만** 있다.
order·pay·cart·chat 은 없다. Kafka 재전달이나 아웃박스 재발행이 나면:

| 서비스 | 중복 소비 시 |
|---|---|
| pay-service | `UNIQUE(order_id, tracking_id)` 가 막아 준다 — §4 참조 |
| order-service | 낙관적 락 예외를 NO-OP 로 흡수한다(부분적) |
| cart-service | 보호 없음 |
| chat-service | 보호 없음 — 같은 메시지가 두 번 저장될 수 있다 |

pay 와 order 는 우연히 방어가 되지만 **설계된 방어는 아니다.** 로드맵에 남긴다.

## 4. 결제 중복 감지가 한 번도 동작하지 않고 있었다

`PaymentRequestKafkaListener` 는 유니크 제약 위반을 잡아 "이미 처리됨"으로 넘기려 했다.
그 코드가 **한 번도 실행되지 않았다.** 두 가지가 겹쳐 있었다:

```java
"23000" == sqlException.getSQLState()
```

- **`==` 로 String 비교.** 드라이버가 만든 문자열은 인터닝되지 않으므로 항상 false 다.
- **값도 틀렸다.** PostgreSQL 의 유니크 위반은 **23505** 이고, 23000 은 다른 DB 계열이 쓰는
  일반 무결성 위반 클래스다. `.equals()` 로 고쳤어도 매칭되지 않았다.

즉 중복 결제 메시지는 스킵되지 않고 **전부 DLT 로** 갔다. 지금은 Spring 이 23505 를 번역해 주는
`DuplicateKeyException` 을 직접 잡는다 — SQLState 문자열을 손으로 비교하지 않으므로 같은
실수가 재발할 수 없다.

## 5. Authorization 헤더가 없으면 배치가 통째로 사라졌다

두 결제 리스너 모두 이 모양이었다:

```java
if (authorizationHeader != null) {
    ... 처리 ...
} else {
    log.warn("Authorization header not found in the message batch.");   // 끝
}
```

메시지는 커밋되고, 결제는 일어나지 않는다. **소리 없는 유실**이다. order-service 쪽이면
주문이 영원히 `NOT_PROCESSED` 로 남는다(사가가 조용히 멈춘다).

처리를 거부하는 판단 자체는 옳다 — 인증 없이 결제를 만들 수는 없다. 바꾼 것은 **결과가
보이게** 한 것뿐이다: 이제 예외를 던져 DLT 에 남는다. 경보도 걸려 있다(docs/12).

## 6. 지금 상태

| 서비스 | replicas | HPA |
|---|---|---|
| gateway-server, user, catalog, cart, order, pay, coupon, frontend | **2** | min 2 / max 3 |
| chat-service | 1 (고정) | 대상 아님 |
| eureka-server | 1 (고정) | 대상 아님 |

커넥션 총합은 가드레일 B-32 가 계속 검증한다 — HPA 최대치가 3 그대로이므로 산술은 변하지 않았다.

## 7. 남은 로드맵

| 항목 | 상태 |
|---|---|
| order·pay·cart·chat 의 멱등 원장 | 🟠 §3 |
| chat 브로커 릴레이 (그 뒤 chat 확장) | 🟠 |
| peer-aware Eureka (또는 k8s 네이티브 디스커버리로 이전) | 🟡 |
| 아웃박스 발행 지연·재시도 지표 | 🟡 현재는 로그만 |
