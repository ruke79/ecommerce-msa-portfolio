# 48. 사용자 복제 — 한 이벤트, 세 소비자, 세 가지 실패

[日本語](48-user-replication-debt.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** "개선 예정" 으로 쓴 문서지만 ①~⑥ 은 적용했습니다(§6). 남은 것은 Avro 이력 정리(C3)입니다.

> 📌 docs/47 의 주문 400 을 쫓다 드러난 구조 문제를 정리한 문서다.
> **①(order 는 복제하지 않는다)·②(outbox)·③(대사 — docs/57 §14)·④(발행 시점)·⑤(변경·탈퇴 전파)는 적용했다.**
> ⑥(chat 신원을 uid 로, 이벤트에서 이메일 제거)도 적용했다.

---

## 1. 지금의 모양

`user-created` 이벤트 하나를 세 서비스가 받는다. **셋의 용도가 전부 다르다.**

```
user-service ──user-created──┬──▶ order-service   orders.customer   (행을 만든다)
 (메일 인증 시점)             ├──▶ chat-service    chat_member       (행을 만든다)
                             └──▶ coupon-service  ✗ 행 없음          (가입 쿠폰을 발급한다)
```

### 🔴 그런데 발행이 한 번도 일어나지 않았다

발행은 **메일 인증 링크를 누른 순간**에 걸려 있었다. 그런데
`app.verification.require-email-verification` 의 **기본값이 false** 다:

```java
user.setEnabled(!requireEmailVerification);   // false → 가입 즉시 활성
```

계정이 곧바로 `enabled` 로 만들어지므로 **확인 링크를 누를 일이 없고, 그 경로를 아무도
지나지 않는다.** `user-created` 토픽이 비어 있던 진짜 이유가 이것이다 — 발행이 실패한
것이 아니라 **호출된 적이 없었다.**

증상이 셋으로 나뉘어 나타났다.

| | 어떻게 보였나 |
|---|---|
| order | 주문 버튼 → 400 (docs/47) — **눈에 띈다** |
| chat | 채팅 진입 실패 — 눈에 띈다 |
| coupon | **가입 축하 쿠폰이 안 온다** — 안 왔다는 사실조차 알기 어렵다 |

세 번째가 이 문서 §3 의 요점을 그대로 보여 준다. 사용자가 "쿠폰이 안 생기는 것 같은데"
라고 묻기 전까지 아무도 몰랐다.

## 2. 무엇이 깨졌나 — dual-write  ✅ 고침(§6-②)

```java
@Transactional
public void publish(UserCreatedRequest req) {
    kafkaTemplate.send("user-created", String.valueOf(req.getUserId()), req);
}
```

**DB 트랜잭션과 카프카 발행은 같은 원자 단위가 아니다.** 계정은 커밋되고 이벤트는 안 갈 수
있다. 게다가 `send` 는 비동기라 future 를 아무도 보지 않았으므로 **실패해도 로그 한 줄
남지 않았다.**

실제 결과: 계정은 멀쩡한데 `user-created` 토픽이 **통째로 비어 있었다**(offset 0, lag 0).

> 이 저장소에는 이미 **transactional outbox** 가 있었다 — `users.outbox_event`,
> `PaymentOutboxHelper`, CDC 커넥터. 결제에는 적용했는데 `user-created` 에만 안 했고,
> `UserServiceImpl` 에 `createOutboxEvent` 가 **주석 처리된 채로** 남아 있었다.

## 3. 놓쳤을 때 세 서비스가 서로 다르게 망가진다

| 서비스 | 복제하는 것 | 실제로 쓰는 것 | 이벤트를 놓치면 | 지금 상태 |
|---|---|---|---|---|
| **order** | `orders.customer` (id·email·username·nickname) | **이메일 → id 변환, 그것뿐** | 주문 생성 실패 | 주문 시점 백필 추가 (docs/47) |
| **chat** | `chat_member` (id·email·nickname·image) | 이메일 → 회원, **닉네임을 화면에 표시** | 채팅 진입 실패 | `createMissingMember` 로 즉석 생성 — ~~닉네임을 이메일 앞부분으로 추측~~ 임시 닉네임, 가입 이벤트가 채운다(§6-⑤) |
| **coupon** | 없음 | — | **가입 쿠폰이 영영 안 나간다** | ❌ 대책 없음 |

**coupon 이 제일 나쁘다.** order·chat 은 "행이 없네" 를 읽기 시점에 알아차릴 수 있지만,
쿠폰은 **못 받았다는 사실 자체를 나중에 알 방법이 없다.** 읽기 폴백으로 고칠 수 없는
종류의 누락이다 — 대사(reconciliation) 배치 말고는 답이 없다.

## 4. 급한 것만 막았다

- order-service 가 주문 시점에 행이 없으면 user-service 에 물어 만든다.
- (그 뒤 ②를 적용해 발행 자체를 아웃박스로 옮겼다 — 아래 §6-②.)

## 5. 🔴 읽기 시점 백필은 <b>복구 밸브</b>지 설계가 아니다

세 가지 이유로 정상 경로가 되면 안 된다.

1. **동기 의존을 되살린다.** 주문 생성이 user-service 가용성에 묶인다 — 비동기 복제가
   애초에 피하려던 바로 그것이다. user-service 가 느리면 주문이 느려지고, 죽으면 신규
   고객은 주문을 못 한다.
2. **드리프트를 덮는다.** 백필이 매번 성공하면 카프카 발행이 계속 깨져 있어도 아무도
   모른다. `warn` 으로 남기지만 **로그는 알람이 아니다.**
3. **소비자마다 다시 짜야 한다.** chat 은 이미 따로 만들어 두었고(닉네임은 추측한다),
   coupon 은 애초에 불가능하다. 같은 문제를 세 번 푸는 중이다.

## 6. 개선 경로 (권장 순서)

### ✅ ① 애초에 복제하지 않는다 — 검증된 토큰이 이미 나른다 ⭐ — **적용함(2026-09)**

**order-service 에서 `orders.customer` 가 하는 일은 딱 하나다:**

```java
customerRepository.findByEmail(email).map(Customer::getId)   // 이메일 → id
```

`username`·`nickname` 컬럼은 **어디서도 읽지 않는다.** 그런데 그 id 는 이미 JWT 안에 있고
(`uid` 클레임), 게이트웨이가 **`User-Id` 헤더**로 넘겨준다. `orders.orders.customer_id` 에
**FK 도 없다**(그냥 `bigint`).

즉 이 테이블은 **order-service 가 이미 알고 있는 답을 얻기 위해** 존재한다. 카프카
이벤트도, 백필도, `customers` 캐시도 전부 그것 때문에 있다.

`User-Id` 헤더를 쓰면 주문 경로에서 이 테이블이 **통째로 빠진다.** 그 뒤에 남는 질문은
"관리자 화면·리포트에 이 테이블이 필요한가" 하나뿐이고, 필요 없으면 지운다.

chat 은 사정이 다르다 — **닉네임을 화면에 쓴다.** 그건 진짜 복제이므로 ②·③ 이 필요하다.

**적용한 것**

| | 전 | 후 |
|---|---|---|
| 요청자 신원 | 컨트롤러가 `User-Email` → 서비스가 `orders.customer` 에서 id 조회 | 컨트롤러가 **`User-Id`**(토큰의 uid, 게이트웨이가 uid 없는 토큰은 401) → 서비스가 `Long requesterId` 를 받는다. 소유권 검사 13곳 |
| 주문 생성 | 이메일로 사본 조회, 없으면 user-service 에 물어 **백필** | 주문자 id 는 헤더. 이메일은 장바구니·쿠폰 서비스 호출에만(그쪽 API 가 이메일을 키로 받는다) |
| 웹훅(Stripe·포트원) | 주문의 고객 id → 사본에서 이메일 → 받는 쪽이 다시 이메일 → id | 주문의 고객 id 를 그대로 |
| 관리자 주문 목록의 이메일 | 사본에서 `findAllById` | 트랜잭션 **밖**(`OrderService`)에서 user-service `GET /api/auth/user/emails?ids=` 한 번. 실패하면 이메일 없이 그린다. 그 엔드포인트는 내부 전용(docs/27 §9b 가드) |
| 캐시 | `customers`(이메일 → id), `order`·`orderList` 키에 이메일 | `customers` 없음, 키는 요청자 id |
| 이벤트 | `user-created` 를 구독(그룹 `user-1`) | 구독하지 않는다 — 리스너·컨슈머 팩토리·`Customer` 엔티티·저장소 삭제 |

- **테이블 `orders.customer` 는 남겨 두었다** → ✅ 전환 정리 릴리스의 V16 이 지웠다(아래 "전환 정리"). 이번 배포를 되돌리면 옛 코드가 읽는다(expand-contract). 한 번의
  릴리스가 안정되면 `-- ROLLBACK-UNSAFE` 선언을 단 마이그레이션으로 지운다(B-54).
- 트랜잭션 경계 가드(`OrderTransactionBoundaryTest`)가 처음 구현에서 `OrderDataService` 에 user-service 클라이언트를
  넣은 것을 잡았다 — 이메일 붙이기를 트랜잭션 밖으로 옮긴 이유다.
- **남은 것**: ~~cart·chat·coupon 은 여전히 이메일 → id 조회를 쓴다~~ → 셋 다 `User-Id` 로 옮겼다(§6-⑥). 요청 경로에서
  `/api/auth/user/id` 를 부르는 곳은 없다. 남은 호출은 coupon 의 관리자 이메일 발급과 전환 릴리스용 견적 대체 경로뿐이다.


### ✅ ② 복제해야 하는 것은 outbox 로 발행한다 — **적용함**

`users.outbox_event` 테이블과 엔티티는 **처음부터 있었다.** 그런데 쓰는 곳이 없었다 —
`UserServiceImpl` 에 이렇게 **주석 처리된 채로** 남아 있었다:

```java
//OutboxEvent event = createOutboxEvent(in);
//outboxEventRepository.save(event);
userCreatedProducer.publish(dto);     // ← 이것이 돌고 있었다
```

즉 아웃박스를 만들어 두고 **주석 한 줄 차이로** dual-write 를 쓰고 있었다.

**바뀐 것**

| | 전 | 후 |
|---|---|---|
| 이벤트 기록 | `registerConfirmed` 에서 카프카로 직접 — 인증 확정과 **다른 트랜잭션** | `validateAndGetUser` 가 `user.setEnabled(true)` 와 **같은 트랜잭션**에 outbox 행을 쓴다 |
| 발행 | `kafkaTemplate.send` 비동기, 결과를 보지 않음 | `UserOutboxScheduler` 폴러가 읽어서 **동기로** 보내고 상태를 확정 |
| 실패 | 흔적 없음 | 행이 `FAILED` 로 남는다 — **무엇이 안 나갔는지 셀 수 있다** |

**설계는 order-service 의 `PaymentOutboxScheduler` 와 같다.** 같은 문제를 두 번 다르게
풀 이유가 없다. 핵심 둘:

- **`FOR UPDATE SKIP LOCKED` 로 선점한다.** user-service 는 `replicas: 2` 다. 잠금 없이
  읽으면 두 레플리카가 같은 행을 발행한다(docs/21 에서 결제 아웃박스가 그랬다).
- **발행을 그 트랜잭션 안에서 동기로 기다린다.** 비동기면 상태를 바꾸는 콜백이 커밋
  뒤에 돌고, 그 사이 행은 STARTED 인데 잠금은 풀려 있다 — 그 틈에 다른 레플리카가
  집어 간다.

**`UserOutboxHelper.recordUserCreated` 는 `MANDATORY` 다.** 트랜잭션 없이 불리면 즉시
예외다 — `REQUIRED` 면 조용히 새 트랜잭션을 열어 성립해 버리고, 그게 바로 되돌아온
dual-write 다.

**멱등성**은 `validateAndGetUser` 의 "이미 활성화됨" 조기 반환이 갖는다. 확인 링크를 두
번 눌러도 행은 하나다. 아웃박스 기록이 그 조기 반환 **위로** 올라가면 깨진다.

> ⚠️ `FAILED` 는 **자동 재시도하지 않는다.** 브로커가 죽어 있으면 한 주기에 배치 전체가
> FAILED 가 되는데, 계속 되돌리면 잠금만 오래 잡는다. 복구는 ③ 대사 배치의 몫이다.
> 지금 목표는 **보이게 하는 것**까지다.

**CDC 로 옮길 여지는 남아 있다.** `event_type` 을 토픽 이름(`user-created`)으로 적어
두었으므로, 나중에 Debezium `EventRouter` 를 붙이면 `route.by.field: event_type` 이
그대로 동작한다. 폴러를 먼저 고른 이유는 **이미지 배포만으로 끝나기 때문**이다 — 커넥터는
Kafka Connect 설정·복제 슬롯·시크릿이 함께 움직여야 한다.

가드: `UserCreatedOutboxTest`(6건). 아웃박스 기록을 지우고 실패하는 것을 확인했다.

### ✅ ③ 대사(reconciliation) 배치 — **적용함(2026-09, docs/57 §14)**

`UserReplicationReconciler` — 매일, 최근 7일 사이 가입·변경·탈퇴한 사용자의 이벤트를 아웃박스로 **다시 보낸다.** 받는 쪽이
모두 멱등이라(coupon 은 같은 쿠폰이 있으면 만들지 않고, chat 은 없을 때만 만들고 더 새 값만 적용한다) "빠진 것 찾기" 대신
"최근 것 다시 보내기" 로 같은 결과가 난다. 오래된 누락은 `user.replication.reconcile.window-days` 를 한 번 넓혀 돌린다.

"user-service 에 있는데 order/chat 에 없는 사용자", "가입 쿠폰을 못 받은 사용자" 를
주기적으로 찾아 메꾼다. **드리프트는 요청마다 고치는 게 아니라 주기적으로 훑어서
고친다.** coupon 의 누락은 이 방법으로만 복구된다.

### ✅ ④ 발행 시점을 가입 시점으로 — **적용함**

②를 적용할 때 아웃박스 기록을 `validateAndGetUser`(인증 링크) 안에 두었는데, **그것만으로는
지금 설정에서는 아무 일도 일어나지 않는다.** 위 §1 의 이유로 그 경로를 아무도 지나지 않기
때문이다. 그래서 `registerNewUserAccount` 로 옮겼다 — 계정 저장과 같은 트랜잭션이다.

이제 **인증 설정과 무관하게 언제나 발행된다.** 인증을 켠 환경에서는 미인증 계정에도 하위
서비스 행이 생기는데, 그 계정은 로그인 자체가 안 되므로 해가 없다. 반대 상태 — "계정은
있는데 하위 서비스에는 없다" — 가 훨씬 나쁘다는 것을 docs/47 에서 확인했다.

> ⚠️ 하위 서비스가 인증 여부를 알아야 하면 `UserCreatedRequest` 에 `emailVerified` 를
> 더한다. 이벤트 스키마(Avro 매핑) + 소비자 셋을 함께 고쳐야 하고, 지금은 아무도 그
> 값을 필요로 하지 않아 하지 않았다.

**이미 가입한 계정은 살아나지 않는다.** 그건 ③의 몫이다.

### ✅ ⑤ 프로필 변경·탈퇴도 전파한다 — `user-updated`·`user-deleted` — **적용함(2026-09)**

`user-created` 만 있던 동안 chat 의 복제본은 **가입 순간의 값에서 멈춰 있었다.**

| 원본에서 일어난 일 | 예전 chat 복제본 | 문제 |
|---|---|---|
| 닉네임·프로필 사진 변경 | 옛 값 그대로 | 상대방 화면에 옛 이름 |
| 탈퇴 최종 처리(원본 익명화) | **이메일·이름·사진이 남음** | 🔴 개인정보 삭제 요청을 원본에서만 이행 — 복제본이 새는 곳이 된다 |

**조사하다 더 큰 것이 나왔다 — chat 은 가입 이벤트를 한 번도 받지 못하고 있었다.**
user-service 는 `user-created` 를 Avro 로 쓰는데(`459126e`, 토픽 전부 Avro 로 전환), chat 의
컨슈머만 `JsonDeserializer` 로 남아 있었다. 모든 가입 이벤트가 역직렬화에서 실패해 DLT 로 갔고,
채팅 회원은 **접속 순간 `createMissingMember` 로만** 생겼다 — 닉네임은 **이메일 앞부분**이었다.
닉네임은 상대방 화면에 보이므로, 사실상 모든 채팅 회원이 **이메일 아이디를 상대에게 노출**하고 있었다.
가드(`AvroWiringGuardrailTest`)의 목록에 chat 의 user-created 가 빠져 있어 잡지 못했다.

적용한 것:

| 위치 | 변경 |
|---|---|
| user-service `UserOutboxHelper` | `recordUserUpdated`(닉네임·이미지·`updatedAt`) · `recordUserDeleted`(userId) — **요청과 같은 트랜잭션의 아웃박스**(②와 같은 방식, `MANDATORY`) |
| user-service `UserServiceImpl` · `WithdrawalService` | 프로필·사진 변경 후, 탈퇴 최종 처리 후 기록 |
| user-service `UserLifecycleProducer` · `UserOutboxScheduler` | 릴레이가 JSON 문자열로 동기 발행, 키 = userId(같은 사용자는 한 파티션) |
| chat `UserLifecycleConsumer` | 두 토픽을 배치로 받아 레코드마다 추적을 이어받는다(docs/51 §4h). 실패는 그 인덱스만 DLT |
| chat `ChatMember` | `applyProfile(…, updatedAt)` — **옛 이벤트는 버린다** · `anonymize()` — 원본과 같은 규칙의 익명 이메일, "탈퇴한 사용자" |
| chat `V4__member_profile_sync.sql` | 닉네임 UNIQUE 제거(§7) + `profile_updated_at` |
| chat `KafkaConsumerConfig` | 🐛 user-created 를 `AvroDtoDeserializer` 로(coupon 과 같다) |
| chat `UserMessageListener.userCreated` | 즉석으로 만든 행이 먼저 있으면 **원본 프로필로 채운다** — 예전에는 id 충돌을 삼키고 끝났다 |
| chat `ChatService.createMissingMember` | 임시 닉네임에 이메일을 쓰지 않는다(`회원 <id36>`) |
| common `AvroWiringGuardrailTest` | chat 의 user-created Avro 역직렬화기를 목록에 더했다. order 는 ① 로 더 받지 않으므로 뺐다 |
| `ops/02` TOPICS | `user-updated user-deleted` (B-41) |

설계에서 고른 것과 이유:

- **이벤트는 "변경분"이 아니라 "최신 상태 전체"를 싣고 `updatedAt` 을 붙인다.** 키가 userId 라
  한 파티션에서 순서대로 읽히지만, 아웃박스 릴레이가 **실패한 행을 나중에 다시 보내면** 새 변경보다
  늦게 도착할 수 있다. 받는 쪽이 `profile_updated_at` 보다 오래된 것을 버린다. 가입 이벤트는
  시각 0 으로 취급한다 — 어떤 변경보다도 오래됐다.
- **탈퇴는 행을 지우지 않고 익명화한다.** 채팅방 참여·메시지가 member_id 를 가리킨다. 지우면
  상대방의 대화 기록이 깨진다. 식별정보(이메일·이름·사진)만 지우는 것이 원본(`WithdrawalService`)과
  같은 정책이다. 익명화 후 `profile_updated_at = MAX` 로 못 박아 **늦게 온 변경·가입 이벤트가 되살리지 못한다.**
- **`user-updated` 에 이메일을 싣지 않는다.** 받는 쪽이 쓰지 않는다(데이터 최소화). 그래서 복제본이
  없을 때 만들 수도 없다 — 건너뛴다. 없는 이유는 대개 가입 이벤트가 DLT 에 있는 것이고, 재처리하면 생긴다.
- **컨슈머는 `earliest`.** 새 그룹이 처음 뜨기 전에 쌓인 탈퇴를 놓치지 않는다. 처리는 멱등하다.
- **coupon 은 받지 않는다.** userId 만 쓰고 표시용 필드가 없다.

실무와 비교해 남은 것: **이미 DLT 에 쌓인 옛 `user-created` 는 이 수정으로 살아나지 않는다.**
배포 후 `chat` 의 user-created DLT 를 재처리하면(docs/11 §3) 즉석 생성된 회원이 원본 닉네임으로 채워진다.
그 사이 가입 이벤트가 없어도 **프로필을 한 번 바꾸면** `user-updated` 가 채운다.

가드: `UserLifecycleOutboxTest`(5) · `WithdrawalServiceTest.finalizeTellsReplicas` ·
chat `UserLifecycleConsumerTest`(12) · `AvroWiringGuardrailTest` · B-41 · B-60b.

### ✅ ⑥ chat 의 신원을 uid 로 옮기고, `user-created` 에서 이메일을 뺀다 — **적용함(2026-09)**

데이터 최소화 원칙으로는 이메일을 빼야 한다 — 복제본이 **많을수록 샐 곳이 늘어나는** 필드다. 소비자별로 확인했다.

| 소비자 | 이메일을 쓰는가(전) | 지금 |
|---|---|---|
| coupon | ❌ userId 만 쓴다 | 그대로 |
| order | — 더 받지 않는다(①) | — |
| **chat** | ✅ **신원의 키가 이메일이었다** — STOMP 세션(토큰 subject), 회원 조회 `findByEmail`, 참여자 확인 `…Member_Email`, 메시지의 발신자 필드, 프론트의 "내 메시지" 판별(`chat.email === user.email`) | **uid(member_id)** |

그래서 순서는 **chat 을 uid 로 → 이벤트에서 이메일 제거** 였다. 먼저 이메일을 빼면 새 가입자의 채팅 회원 조회가 실패한다.

#### 왜 이메일이 신원이면 안 됐나

- **방의 상대방에게 샜다.** 발신자를 이메일로 나타냈으므로 메시지마다 이메일이 실려 방의 모든 참여자에게 갔다
  (화면에 그리지는 않았지만 개발자도구로 보인다). 입장 안내는 `"<이메일> entered."` 로 화면에 찍혔다.
- **바뀌는 값이다.** 이메일을 바꾸면 옛 메시지가 남의 것이 된다. id 는 불변이다.
- **토큰이 이미 나른다.** 게이트웨이·order 는 `uid` 로 옮겼다(①). chat 만 subject(이메일)를 쓰고 있었다.

#### 바꾼 것

| 층 | 전 | 후 |
|---|---|---|
| STOMP CONNECT | 세션에 `email`(+ `userId`) | 세션에 `userId` **하나**. uid 없는 토큰은 거절 |
| 발신·입장·퇴장 | 본문의 email 을 세션 email 로 덮어씀 | 세션 uid 로 `senderId` 를 채우고 본문의 email 은 지운다 |
| 회원 조회·참여자 확인 | `findByEmail`, `…Member_Email` | `findById`, `…Member_Id` |
| REST(방 만들기·이력) | 토큰 subject | 토큰 uid(없으면 401) |
| 메시지(Kafka·몽고·화면) | `email` | `senderId`. 화면으로는 **문자열**(19자리 TSID 가 JS Number 에서 뭉개진다). DTO·몽고 모델에 `email` 필드가 **없다** — 옛 클라이언트가 보내도 무시한다 |
| Avro `ChatMessage` | v1 | **v3**(v2 에서 `senderId` 추가, v3 에서 `email` 제거). v1·v2 는 `avro-history/` 에 두고 chat·notification 디코더가 `SchemaStore` 로 읽는다 |
| 옛 대화 기록(몽고 v1) | 발신자 = 이메일 | 기동 때 `LegacySenderBackfill` 이 id 로 바꾸고 이메일을 지운다. 매핑에 없는 발신자(탈퇴 등)는 **이메일만 지운다**. 다 끝나면 매핑도 지운다 |
| 탈퇴(`user-deleted`) | 회원 행만 익명화 | 대화 기록의 닉네임 사본·옛 이메일도 지운다(입장·퇴장 문구 포함). 내용은 남긴다 — 상대방의 대화이기도 하다 |
| `chat.member.email` | 모든 회원 | **컬럼 없음**(V5). 옛 기록 백필용 매핑은 `chat.legacy_sender_email` 로 옮겼다가 백필이 끝나면 비운다 |
| `user-created` | email 포함 | **없다** — DTO·Avro(v2) 모두. v1 레코드는 이력으로 읽는다 |
| 프론트 | `chat.email === user.email` | `sameUserId(chat.senderId, user.userId)`. `user.userId` 는 토큰 **원문**에서 글자로 읽는다(`uidOf`) |

#### 설계에서 고른 것과 이유

- **uid 의 정밀도.** 프론트의 `user.userId` 는 `jwtDecode` → `JSON.parse` 를 거쳐 `Number` 였다. 19자리 TSID 는
  2^53 을 넘어 **끝자리가 뭉개진다**(…789 → …800). 서버가 보내는 `senderId`(정확한 문자열)와 비교하면 "내 메시지"가
  영영 남의 메시지가 된다 — 조용히 틀린다. 서명 검증은 서버 몫이므로 화면은 토큰 원문의 JSON 텍스트에서 숫자를
  글자로 꺼낸다. 주문 번호에서 이미 한 번 겪은 함정이다(docs/25 §11.6).
- **스키마 진화.** 레지스트리가 없어 레코드 앞의 **지문**으로 작성 스키마를 찾는다. 필드 하나만 더해도 지문이 바뀌어,
  예전 방식이면 배포 도중 옛 파드가 쓴 메시지는 전부 역직렬화 실패로 DLT 에 간다(방에 전달되지 않는다). 옛 스키마를
  클래스패스에 남기고 디코더에 등록하는 `AvroSchemaHistory` 를 만들었다 — 다음 스키마 변경도 같은 방식으로 한다.
- **옛 데이터는 한 번의 배포로 끝낸다.** 이메일 컬럼을 지우는 V5 는 기동 백필보다 **먼저** 돈다 — 컬럼만 지우면
  백필이 "이메일 → id" 를 잃는다. 그래서 V5 가 매핑을 전용 테이블로 옮긴 뒤 컬럼을 지우고, 백필은 그 테이블로 바꾼
  다음, 몽고에 이메일이 하나도 남지 않으면 테이블을 비운다. "배포해서 백필을 확인한 뒤 다음 릴리스에 지운다" 는 두 번의
  배포와 사람의 확인이 필요한데, 이 방식은 순서를 코드가 지킨다. 백필 전의 문서는 화면으로 나가지 않는다 — readiness 는
  러너가 끝난 뒤에 준비되고, 몽고 모델에 `email` 필드가 없어 읽히지도 않는다. 백필이 실패해도 기동은 막지 않는다
  (발신자만 비어 보이고, 다음 기동에 다시 돈다).
- **발신자를 모르는 옛 이메일은 지운다.** 이미 탈퇴해 매핑이 없는 발신자의 이메일을 남겨 둘 이유가 없다. 그 메시지는
  "남의 메시지"로 그려진다.
- **탈퇴의 대화 기록.** 메시지를 지우면 상대방의 대화가 사라진다. 발신자 표시(닉네임 사본·이메일)만 지운다.

#### 배포 순서

1. **notification-service** — v1·v2·v3 를 모두 읽는다(먼저 올려야 chat 이 새 스키마를 쓰기 시작해도 알림이 끊기지 않는다).
2. **chat-service** — V4·V5 마이그레이션, 기동 때 백필. 로그 `옛 발신자 백필: id 로 바꾼 문서 N건, 발신자를 모른 채
   이메일만 지운 문서 M건, 지운 매핑 K건` 이 한 번 나오고, 그 뒤 기동부터는 조용하다(할 일이 없다).
   - ⚠️ **V5 는 되돌릴 수 없다**(`ROLLBACK-UNSAFE`) — 이메일 컬럼을 지운다. 이전 이미지는 Hibernate validate 에서 기동하지
     못한다. chat 은 1 레플리카라 롤링 중 새 파드가 준비될 때까지 옛 파드와 잠깐 겹치는데, 그동안 옛 파드의 채팅 회원
     조회가 실패한다.
   - 롤링 중 옛 파드가 쓴 v1·v2 메시지는 새 파드가 읽는다. v1 은 발신자가 이메일이라 그 몇 건은 "발신자 모름"으로 저장된다.
3. **user-service + frontend** — 함께(2FA 응답 형식도 바뀌었다, docs/27 §12b). frontend 가 chat 보다 먼저 가면 그 사이
   모든 메시지가 "남의 메시지"로 보인다(기능은 멀쩡하다).

#### cart 도 사용자 id 로 (같은 배포)

cart 는 장바구니를 id 로 저장하는데 신원·캐시 키만 이메일이었다. 그래서 캐시를 비우는 모든 경로가 user-service 에
id → 이메일을 물었고, 그 호출에 쓸 **사용자 토큰을 Kafka 메시지에 실어** 날랐다. `/savecart` 는 본문의 email 을 믿어
남의 장바구니를 덮어쓸 수 있었다. 이제 신원은 `User-Id`, 캐시 키는 `carts::<id>`, user-service 호출은 없다.
cart-empty·cart-rollback·coupon-use 에서 토큰을 뺐다(스키마 v2, 이력으로 v1 도 읽는다). 상세는 docs/54 §1.1·§1.7.

배포 중(ops/28 은 서비스를 거의 동시에 교체한다) 옛·새 파드가 섞이는 몇 분 동안:

- **새 order·coupon → 옛 cart**: 전환용 호출이 `User-Id` 와 옛 `User-Email` 을 **함께** 싣는다 — 통한다.
- **옛 order·coupon → 새 cart**: `User-Id` 가 없어 401 — 그 사이의 주문 생성·쿠폰 견적이 실패할 수 있다(재시도하면 된다).
- **새 order·coupon 의 v2 메시지 → 옛 cart**: 옛 cart 는 v2 지문을 몰라 DLT 로 간다. 배포 뒤 cart-empty·cart-rollback·
  coupon-use 의 DLT 를 재처리한다(docs/11 §3 — 처리는 멱등하다).
- 새 cart 는 옛 v1 메시지(토큰 포함)를 읽는다 — 토큰은 버린다.

다음 릴리스에서 order·coupon 의 전환용 호출(`getCartDuringRollout`)을 지우고 `User-Id` 만 싣는다 → ✅ 지웠다(아래 "전환 정리").

#### coupon 도 사용자 id 로

coupon 은 쿠폰을 사용자 id 로 저장하는데, 요청마다 `User-Email` 을 받아 user-service 에 이메일 → id 를 다시 물었다
(적용·목록·기획전 수령·주문 견적). 🔴 그 Feign 호출(`findUserByEmail`)은 **액세스 토큰을 쿼리 파라미터**
(`?Authorization=Bearer …`)로 실었다 — URL 은 액세스 로그·프록시·예외 메시지에 남는다. 받는 쪽은 읽지도 않았다.

| | 전 | 후 |
|---|---|---|
| 적용·목록·기획전 수령 | `User-Email` → user-service → id | `User-Id` 로 바로 쿠폰 조회(user-service 호출 없음) |
| 고객군(기획전 대상 판정) | `/user/segments?email=` | `/user/segments?userId=` (user-service 는 전환 동안 둘 다 받는다) |
| 주문 견적 `/internal/quote` | order 가 `email` 을 보냄 | order 가 `userId` 를 보냄. coupon 은 `userId` 가 없을 때만 이메일로 찾는다 |
| `findUserByEmail` | 토큰을 URL 쿼리로 | 토큰 파라미터 삭제(인터셉터가 헤더로 넘긴다). 관리자 이메일 발급과 전환용 견적에서만 쓴다 |
| `/applycoupon` 본문 | `@Deprecated userEmail` 을 받고 무시 | 필드째 삭제, 옛 클라이언트가 보내도 `ignoreUnknown` 으로 버림 |
| 가입 쿠폰(user-created 소비) | 헤더의 토큰을 인자로 넘김 | 인자 없음 — 이벤트의 `userId` 만 쓴다 |

배포 중(user-service 와 다른 서비스가 같은 단계에서 교체된다) 섞이는 몇 분 동안:

- **새 order → 옛 coupon**: `email` 을 함께 싣고, 인터셉터가 `Authorization` 헤더를 넘긴다 — 옛 coupon 이 읽는 그대로 통한다.
- **옛 order → 새 coupon**: `userId` 없이 `email` 만 온다 — 새 coupon 이 그 이메일로 id 를 찾는다. 못 찾으면 "쿠폰 없음" 과
  같은 응답이다(500 이 아니다).
- **새 coupon → 옛 user-service 의 `/user/segments`**: 옛 쪽은 `email` 이 필수라 400 — 고객군을 못 읽으면 대상이 아니라고
  답한다(조용히 주지 않는 쪽으로 실패, 재시도하면 된다). 전체 대상 기획전은 고객군을 묻지 않아 영향이 없다.
- 옛 coupon 이 아직 도는 동안 프론트는 이미 본문에 이메일을 보내지 않는다 — 옛 coupon 도 본문 값을 읽지 않았다.

다음 릴리스에서: order 의 견적 호출에서 `email` 을, coupon 견적의 이메일 대체 경로(`legacyUserIdOf`)를,
user-service `/user/segments` 의 `email` 파라미터를 지운다 → ✅ 지웠다(아래 "전환 정리").

가드: `CouponQuoteTest`(id 로 바로 조회·user-service 미호출, 견적은 id 우선, 이메일만 오면 대체, 둘 다 없으면 거절) ·
`EventCouponClaimTest`(검증된 id 로 발급, 이메일 조회 없음) · `CouponApplyOwnershipTest`(컨트롤러가 `User-Id` 를 읽는다,
본문의 옛 `userEmail` 무시) · order `FeignContractTest`(견적은 `userId`, 어떤 클라이언트도 토큰을 URL 에 싣지 않는다) ·
`UserLookupGuardTest`(고객군을 id 로, 없는 사용자는 빈 목록).

#### ✅ 전환 정리 — 위 전환 릴리스들의 뒷정리(expand-contract 의 contract)

위의 전환 코드는 **옛 파드와 새 파드가 섞이는 몇 분**을 위한 것이었다. 전환 릴리스(커밋 `1227834` 까지)가 운영에 올라가
안정된 뒤에만 의미가 있는 정리다.

| 지운 것 | 서비스 | 대신 |
|---|---|---|
| `getCartDuringRollout`(`User-Id` + 옛 `User-Email`) | order · coupon | `getCart(User-Id)` 하나 |
| 주문 생성의 `User-Email` 헤더·`OrderRequest.email`·쓰지 않던 토큰 인자 | order · common · frontend | 주문자는 `User-Id` 하나. 옛 클라이언트가 보내는 `email` 은 Jackson 이 버린다 |
| 견적의 `email` 파라미터와 이메일 → id 대체 경로 | order · coupon | `userId` 필수. 없으면 "쿠폰 없음" 응답 |
| `/applycoupon` 의 `User-Email` 헤더 | coupon | `User-Id` 하나 |
| `/user/segments` 의 `email` 파라미터 | user | `userId` 하나 |
| `LegacyAuthorizationHeader`(`Authorization: Bearer none` 표지) | common · order · pay | 사가 메시지에 `Authorization` 헤더 자체가 없다 |
| `LegacySenderBackfill`·`LegacySenderEmails`, 탈퇴 처리의 옛 이메일 조회 | chat | 탈퇴는 `senderId` 로만 찾는다 |
| `chat.legacy_sender_email` 테이블 | chat **V6** | — |
| `orders.customer` 테이블 | order **V16** | — |

🔴 **배포 순서가 곧 조건이다 — 전환 릴리스를 건너뛰고 이것을 바로 올리면 안 된다.**

- **chat V6 는 스스로 막는다.** 매핑 테이블에 행이 남아 있으면(= 옛 발신자 백필이 끝나지 않았다) 지우지 않고
  예외로 실패한다. PostgreSQL 은 DDL 도 트랜잭션이라 아무것도 바뀌지 않고, 새 파드가 준비되지 못해 옛 파드가 계속
  돈다. 그때는 전환 릴리스를 먼저 올려 로그 `옛 발신자 백필: … 지운 매핑 N건` 을 확인한 뒤 다시 올린다. 테이블이 없는
  새 DB 에서는 그냥 지나간다(PG16 에서 세 경우 모두 확인 — 처음 쓴 `IF a AND EXISTS(...)` 는 테이블이 없으면 실패해서
  중첩 IF 로 고쳤다).
- **나머지는 스스로 막지 못한다.** 전환 릴리스 없이 옛 버전에서 곧장 올리면, 섞이는 몇 분 동안 옛 cart·coupon 이
  `User-Email` 을 못 받아 401, 옛 pay·order 가 `Authorization` 헤더 없는 사가 메시지를 DLT 로 보낸다. 데이터는 잃지
  않는다(DLT 재처리로 복구 — docs/11 §3). 그래도 순서를 지킨다.
- **되돌리기**: V6·V16 은 `ROLLBACK-UNSAFE` 다. 전환 릴리스 이미지로 되돌리면 chat 의 탈퇴 처리가 없는 테이블을 읽어
  DLT 로 간다(빈 테이블을 다시 만들면 된다 — 백필이 끝났다면 원래 비어 있었다). `orders.customer` 를 읽는 이미지는 이미
  두 릴리스 전이다.

남은 것:

- `avro-history/` 의 옛 스키마 — 그 스키마로 쓴 레코드가 토픽 보존 기간(기본 7일)을 지나 사라지면 지운다. 기간이
  기준이라 코드 배포 순서로 앞당길 수 없다.
- 이 정리와 무관한 expand-contract 하나: 결제의 옛 `paypal_order_id` 컬럼 → order·pay 모두 정리했다(docs/49 §3). 메시지의
  `paypalOrderId` 도 스키마 v4 에서 지웠다(C1·C2). C3(v1·v2 읽기 경로 삭제, B 후 14일)만 남았다.

가드: `StompHandlerSubscribeTest`(uid 없는 CONNECT 거절, 세션에 이메일 없음, 이메일만 있는 세션 거절) ·
`ChatServiceSendMessageTest`(브로드캐스트 JSON 에 `email` 없음·`senderId` 문자열, 옛 발신자 변환) ·
`UserLifecycleConsumerTest`(탈퇴가 대화 기록의 발신자 표시를 `senderId` 로 지움, 회원 모델에 이메일 필드 없음) · `MessageMapperTest.noEmailFieldAnywhere` ·
`ChatMessageSchemaHistoryTest`(chat v1·v2, user-created v1 레코드를 새 디코더가 읽는다, email 섞인 옛 JSON) ·
`UserLifecycleOutboxTest.createdCarriesNoEmail` · V5 는 PG16 에 직접 적용해 확인 ·
프론트 `ChatContentSender.test`(19자리 id, 뭉개진 id 는 내 것이 아님, 이메일로는 판별하지 않음) · `jwtUser.test`.

## 7. 스키마 지뢰 (같이 정리할 것)

- **`uk_customer_nickname UNIQUE`, `uk_customer_username UNIQUE`** (`orders.customer`).
  **동명이인 두 명이면 두 번째 사용자의 복제가 실패한다.** 복제된 표시용 필드에 유니크
  제약을 거는 것은 원본의 제약을 복제본에 흉내 내는 것인데, 원본이 바뀌면 어긋난다.
- ✅ **chat 의 닉네임 추측** — 고침(§6-⑤). `createMissingMember` 가 이메일 앞부분을 닉네임으로 썼고,
  나중에 이벤트가 도착해도 `persist` 충돌로 **덮어쓰지 않았다.** 이제 임시 닉네임은 이메일과 무관하고,
  가입 이벤트가 오면 원본 값으로 채운다.
- ✅ **`chat.member` 의 `uk_member_nickname UNIQUE`** — 없앴다(V4). order 의 것과 같은 지뢰다: 동명이인의
  가입·변경이 제약 위반으로 실패하고, 탈퇴 익명화("탈퇴한 사용자")는 여러 명이 같은 이름을 가져야 한다.
- **`user-created` 컨슈머 그룹이 서비스마다 다르다** (`user-1`, `user-2`, …). 의도된 것이지만
  토픽 retention(기본 7일)이 지나면 어느 그룹도 과거를 다시 읽을 수 없다. ③이 필요한
  이유이기도 하다.

## 8. 하지 않기로 한 것

- ~~**백필을 지우지 않는다.**~~ order 의 백필은 ① 과 함께 지웠다 — 더 복제하지 않으니 메꿀 것도 없다.
  chat 의 즉석 생성(`createMissingMember`)은 남긴다: chat 은 여전히 복제하고, DLT 에 남은 옛 가입자가 있다.
- **동기 호출로 전면 전환하지 않는다.** "복제하지 말고 그때그때 물어보자" 는 ①과 다르다.
  ①은 **물어볼 필요조차 없애는 것**이고, 전면 동기화는 서비스 간 결합을 늘린다.
