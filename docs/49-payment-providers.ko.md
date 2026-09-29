# 49. 결제수단을 늘릴 수 있게 — 포트, 그리고 Stripe · 포트원

[日本語](49-payment-providers.md) | **한국어**

한국에서 PayPal 라이브 결제가 막혀 결제 이후 과정을 확인할 수 없다는 데서 시작했다.
결제수단을 하나 더 붙이려고 보니 **붙일 자리가 없었다.**

---

## 1. 왜 리팩터링이 먼저였나

결제 확인이 `OrderDataService.persisitPaypalPayment` 한 메서드였고, 거기 **두 종류가
섞여** 있었다.

| | |
|---|---|
| **PayPal 에만 해당** | PayPal REST 로 주문을 조회하고 status 가 `COMPLETED` 인지 본다 |
| **모든 결제에 해당** | 확인된 금액을 **DB 의 주문 총액**과 대조 · 통화 대조 · 그 결제 참조가 이미 쓰였는지 차단 · 결제 아웃박스에 기록 (docs/35 의 네 규칙) |

섞여 있으면 **결제수단을 추가할 때마다 아래 규칙이 복사된다.** 그리고 복사본 하나에서
금액 대조를 빠뜨리면 **그 수단으로만** 결제 없이 주문이 완료된다 — docs/35 의 원래 결함이
정확히 그 모양이었다.

## 2. 포트 하나만 뽑았다

```java
public interface PaymentGateway {
    PaymentProvider provider();
    VerifiedPayment verify(String providerPaymentId);   // 제공자에게 직접 물어본다
}
```

아래 규칙은 `recordPayment` **하나가** 갖는다. **새 제공자는 그것을 자동으로 상속한다.**

결제수단을 추가하는 일은 이제 이것뿐이다:

1. `PaymentProvider` 에 값 하나
2. `PaymentGateway` 구현 빈 하나

레지스트리(`PaymentGateways`)는 스프링이 찾은 빈을 모아 색인하므로 **고칠 필요가 없다.**
가드는 `PaymentGatewaysTest` — "게이트웨이를 꽂는 것만으로 등록된다" 를 고정한다.

### 포트가 둘인 이유

제공자마다 **시작 방식이 다르다.**

| | 결제창을 누가 여나 | 포트 |
|---|---|---|
| PayPal | 브라우저 SDK | `PaymentGateway` |
| 포트원 | 브라우저 SDK (`IMP.request_pay`) | `PaymentGateway` |
| Stripe Checkout | **서버가 세션을 만들고** 브라우저가 이동 | `HostedCheckoutGateway` |

시작 단계가 없는 제공자에게 빈 메서드를 강요하지 않으려고 나눴다. **확인 규칙은 그대로
하나다** — 다른 것은 시작뿐이다.

> ⚠️ order-service 전체를 ports/adapters 로 재배치하지는 **않았다.** 결제 경계 하나가
> 레버리지가 있는 지점이고, 나머지는 회귀 위험만 크다. PayPal 구현은 <b>어댑터로 감싸서</b>
> 손대지 않았다(`PaypalPaymentGateway`) — 잘 돌던 결제 경로를 건드리지 않기 위해서다.

## 3. 스키마는 expand-contract

`paypal_order_id` 를 rename 하면 되돌리기가 깨진다(B-54). 유니크 인덱스가 걸린 컬럼이라
더 그렇다. 그래서 넓히기만 했다 — `payment_provider` · `provider_payment_id` 를 추가하고
백필하고, **옛 컬럼은 다음 릴리스까지 남긴다**(V4).

### 🔴 테스트가 롤링 배포 위험을 하나 잡았다

옛 이미지가 반영한 결제는 `paypal_order_id` 에만 값이 있다. 새 코드가 새 컬럼만 보면
그 결제를 "아직 안 붙었다" 로 판단해 **결제 이벤트를 한 번 더 발행**한다 — 같은 행이라
유니크 제약도 막지 못한다. 멱등 판정이 **옛 컬럼도 보게** 했다.

롤링 배포 중 짧은 창에서만 일어나는 종류라, 테스트가 없으면 재현조차 어렵다.

### 옛 컬럼 정리 (contract) — 두 릴리스로 나눈다

> 이 절의 전환들을 배포 열차(어느 커밋에서 끊어 올리나)와 교훈까지 묶어 정리한 것은 [docs/55](55-zero-downtime-migrations.ko.md) 다.

옛 컬럼을 "다음 릴리스에 지운다" 를 한 번에 하면 **같은 창이 반대 방향으로 열린다.** 롤링 중에는 옛 파드(엔티티가
`paypal_order_id` 를 매핑한다)가 잠시 함께 돌고, 새 파드의 마이그레이션이 컬럼을 지우는 순간 그 파드의 **주문 조회가
전부 실패한다** — Hibernate 는 매핑한 컬럼을 SELECT 목록에 넣는다. 그래서 두 단계다.

| 단계 | 릴리스 | 하는 일 | 되돌리기 |
|---|---|---|---|
| 1 ✅ | 커밋 `8f4a131` | 엔티티가 옛 컬럼을 매핑하지 않는다. 멱등 검사·결제된 주문 취소 거절·환불 알림이 새 컬럼만 본다. **V17** 이 옛 컬럼에만 남은 값을 새 컬럼으로 옮긴다(멱등) | 안전 — 값을 복사할 뿐이고, 옛 이미지는 두 컬럼을 다 본다 |
| 2 ✅ | 1단계가 안정된 **다음** 릴리스 | **V18** 이 컬럼과 인덱스(`uk_order_paypal_order_id`)를 지운다. 1단계를 건너뛴 배포면 스스로 멈춘다(아래 "건너뛰기 방지") | `ROLLBACK-UNSAFE` — 1단계 이전 이미지는 기동하지 못한다 |

재사용 차단은 처음부터 끝까지 그대로다 — `(payment_provider, provider_payment_id)` 부분 유니크(V4)가 막는다.

### pay-service 도 제공자 중립으로

`pay.payment` 는 PayPal 참조만 `paypal_order_id` 에 적었다 — Stripe·포트원·무통장 결제는 "어느 결제인가" 가 남지
않았다. 🐛 그리고 **전해지지도 않았다.** 결제 요청 DTO 에는 `paymentProvider`·`providerPaymentId` 가 있었지만 Avro
스키마(v2)에 그 필드가 없어, 릴레이 경로에서는 매핑이 조용히 버렸다(CDC 의 JSON 경로에만 실렸다). 재발 방지로 모든
매핑의 DTO ↔ 스키마 필드를 이름과 왕복으로 대조하는 테스트를 두었다(`AvroMappingContract`, docs/55 §8).

| 단계 | 릴리스 | order | pay | 되돌리기 |
|---|---|---|---|---|
| A ✅ | 커밋 `6b8cf08` | 결제 요청을 **v2 작성 스키마로** 쓴다 | 모든 소비자(pay·saga-projection·문자열 소비자)가 v3 를 읽는다. **V5** — 새 컬럼 둘 + `payment_type` 에서 제공자, `paypal_order_id` 에서 참조를 옮긴다. 엔티티는 새 컬럼만 매핑한다. v2 레코드면 결제수단·PayPal 참조에서 정한다 | 안전 — 컬럼 추가·복사뿐, 옛 이미지는 `paypal_order_id` 로 판단하지 않는다(저장만) |
| B ✅ | A 가 안정된 다음(order 2단계와 같은 릴리스) | **v3 로 쓴다** → Stripe·포트원·무통장 참조도 pay 에 도착. 메시지의 `paypalOrderId` 는 더 채우지 않는다 | **V6** — A 의 롤링 중 옛 파드가 쓴 행을 한 번 더 옮기고 컬럼을 지운 뒤 `idx_payment` 를 옛 컬럼 없이 다시 만든다. A 를 건너뛴 배포면 스스로 멈춘다 | `ROLLBACK-UNSAFE` |
| C1 ✅ | B 다음(커밋 `0836bb9`) | v3 로 계속 쓴다. **다음 스키마 v4**(`paypalOrderId` 제거)를 `avro-next/` 에 두고 모든 소비자의 디코더가 미리 안다. **V19** 는 릴리스 표지(아래) | 변화 없음(디코더만) | 안전 |
| C2 ✅ | C1 이 다 굴러간 뒤 | `PaymentRequest.avsc` 를 v4 로 바꾸고(v3 는 `avro-history/` 로, `avro-next/` 파일 삭제) v4 로 쓴다. DTO 의 `paypalOrderId` 삭제. **V20**(가드 전용)이 V19 를 보고 C1 을 건너뛴 배포를 막는다(V18 과 같은 방식) | 참조는 `providerPaymentId` 하나만 읽는다. v2 레코드(A 이전·A 의 order)의 PayPal 참조는 v4 에서 빠진 필드에 있어 **비어 있게 된다** — DLT 재처리로만 올 수 있고, 원본 참조는 order 의 `provider_payment_id` 에 있다 | 안전(옛 소비자는 C1 이라 v4 를 안다) |
| C3 | B 배포 후 **14일** 뒤(DLT 보존 기간) | — | v2 대체 경로(`PaymentService.providerOf` 의 `paymentType` → 제공자)와 `avro-history/PaymentRequest-v1·v2` 를 지운다. pay 마이그레이션이 V6 의 적용 시각을 보고 14일 전이면 막는다. v3 이력은 C2 배포 후 14일 뒤에 같은 방식으로 | — |

**C 는 왜 방향이 반대인가.** 필드를 **더할** 때(A·B)는 "새 생성 클래스의 레코드를 옛 작성 스키마로 쓴다" 로 한 릴리스를
버텼다 — 새 필드를 끝에 붙였으니 옛 필드의 위치가 같았다. 필드를 **뺄** 때는 그게 안 된다. `paypalOrderId` 는 가운데(4번째)
필드라, 빼면 뒤 필드의 위치가 모두 밀리고 v4 클래스를 v3 스키마로 쓰면 값이 엉뚱한 칸에 들어간다. 그래서 반대로 간다 —
C1 은 **생성 클래스를 v3 로 둔 채** 다음 스키마 v4 를 디코더 저장소에 미리 넣는다. 저장소는 지문으로 작성 스키마를 찾을
뿐이라 "옛" 과 "다음" 을 구분하지 않고, 읽을 때는 Avro 의 스키마 해석(v4 → v3)이 빠진 필드를 기본값(null)으로 채운다.
C2 에서 v4 로 쓰기 시작해도 롤링 중 남은 C1 파드가 읽는다. v4 파일은 손으로 두므로 지금 스키마와 어긋나지 않게 테스트가
대조한다(정규형 비교).

**C 를 왜 셋으로 나누나.** C2(쓰기 전환)는 **릴리스 순서**가 조건이고, C3(v1·v2 읽기 경로 삭제)는 **시간**이 조건이다 —
앞 릴리스의 order 가 쓴 v2 레코드가 토픽(7일)과 DLT(14일, `ops/13`)에서 사라져야 한다. 한 커밋에 묶으면 시간 조건
가드가 14일 동안 배포 전체를 막는다. 서로 다른 조건은 서로 다른 릴리스에 둔다.

**릴리스 표지(V19).** C1 은 DB 를 바꾸지 않는데, C2 의 가드는 "C1 이 이미 배포됐는가" 를 알아야 한다. 그래서 아무 일도 하지
않는 마이그레이션(`SELECT 1`)을 둔다 — Flyway 이력의 `installed_on` 이 곧 C1 의 배포 시각이다. 지우면 C2 가드가 C1 을
건너뛴 배포로 볼 수 있으므로 지우면 안 된다.

**왜 order 가 한 릴리스 늦게 v3 로 썼나.** 단일 객체 인코딩은 작성 스키마의 지문을 싣고, 읽는 쪽은 아는 지문만 읽는다.
ops/28 은 서비스를 거의 동시에 교체하므로 "읽는 쪽 먼저" 를 배포 순서로 지킬 수 없다 — 새 order 가 곧바로 v3 로 썼다면
롤링 중 옛 pay 는 결제 요청을 DLT 로 보내고(결제가 반영되지 않는다), 옛 saga-projection 은 역직렬화 실패로 스트림이 멈췄다.
그래서 A 는 **읽기는 v3, 쓰기는 v2** 였다. 새 필드를 스키마 끝에 붙였으므로 v2 필드의 위치가 같고, v3 레코드를 v2
스키마로 그대로 쓸 수 있다(새 필드만 빠진다). B 의 롤링 중 옛 파드는 A 라 이미 v3 를 안다. 레지스트리가 있다면 이 일을
호환성 규칙이 대신한다.

### 🔴 건너뛰기 방지 — 2단계 마이그레이션이 1단계 배포를 확인한다

2단계(order V18·pay V6)는 **1단계가 운영에 다 굴러간 뒤**에만 안전하다. 두 릴리스를 한 번에 올리면 롤링 중 1단계 이전
파드가 지워진 컬럼을 SELECT 하고(주문·결제 조회 전부 실패), order 의 v3 레코드를 v2 만 아는 pay·saga-projection 이 읽지
못한다. 사람이 순서를 기억하는 대신 마이그레이션이 확인한다:

- Flyway 이력에서 1단계 마이그레이션(order V17 · pay V5)의 `installed_on` 을 본다. **30분 안에** 적용됐고 DB 의 첫
  마이그레이션보다 10분 넘게 뒤라면 = 이미 운영 중이던 DB 에 1단계와 2단계가 **한 배포로** 올라왔다 → 예외로 실패한다.
- PostgreSQL 은 DDL 도 트랜잭션이라 2단계 파일은 아무것도 바꾸지 않는다(1단계는 이미 커밋됐다 — 그것은 안전하다). 새 order
  파드가 준비되지 못하니 **v3 레코드도 나가지 않는다** — 스키마 쪽 위험까지 같은 장치가 막는다. 30분 뒤 다시 배포하면 통과한다.
- 새 DB(모든 마이그레이션이 한 번에 도는 경우)는 옛 파드가 없으므로 통과한다. 이력 테이블이 없어도 통과한다(fail-open).
- PG16 에서 확인: 건너뜀 → 거부·컬럼 남음 / 1단계 2시간 뒤 → 삭제·재이관·인덱스 재생성 / 새 DB → 통과 / 이력 없음 → 통과.

개발 DB 에 두 커밋을 한꺼번에 받아 기동하면 이 가드에 걸린다 — 30분 뒤 다시 기동하면 된다.

가드(테스트): `PaymentRequestSchemaEvolutionTest`(order 는 v4 로 쓴다, 🔴 C1 의 소비자(v3 + 다음 스키마 v4)가 C2 의
레코드를 읽는다, v3 만 아는 소비자는 v4 를 못 읽는다, v3·v2 레코드를 읽는다, v3 는 끝에만 더했다, v4 는 v3 에서
`paypalOrderId` 만 뺀 것이다) · `PaymentProviderRecordTest`(v3 는 그대로, v2 는 PayPal 참조·결제수단에서,
CASH → BANK_TRANSFER, 엔티티가 옛 컬럼을 매핑하지 않음) · `PaypalPaymentVerificationTest`(새 컬럼에 기록, 메시지는 제공자 중립
필드로, `paypalOrderId` 는 비어 있음, 이미 반영된 결제는 재발행 없음, 엔티티가 옛 컬럼을 매핑하지 않음).

## 4. Stripe — Checkout 리디렉션 + 웹훅

**카드 정보가 우리 페이지를 전혀 거치지 않고, 프론트에 Stripe 키가 필요 없다.** 서버가
세션을 만들기 때문이다 — PayPal 에서 겪은 "빌드 시점에 박히는 client id" 문제가 구조적으로
생기지 않는다.

확정 경로가 **둘이고 둘 다 필요하다.**

| | 무엇을 위한 것인가 | 없으면 |
|---|---|---|
| **복귀 검증** | 사용자에게 즉시 결과를 보여 준다 | 결제 직후 화면이 "확인 중" 에 머문다 |
| **웹훅** | 권위 있는 경로 | 결제 직후 탭을 닫으면 **돈은 빠졌는데 주문은 미결제** |

둘이 겹치는 것은 정상이고 자주 일어난다. `recordPayment` 가 멱등하므로 어느 쪽이 먼저
와도 결과가 같다.

### 웹훅 서명 — 여기만 직접 구현했다

웹훅 엔드포인트는 **인증 없이 공개**돼야 한다(Stripe 가 우리 토큰을 가질 수 없다).
그래서 "정말 Stripe 가 보냈는가" 는 **오직 서명이 답한다.** 틀리면 그 URL 에 JSON 을
POST 하는 것만으로 결제가 조작된다.

SDK 없이 구현한 대가로 경계 조건을 전부 고정했다 — 다른 비밀키 · 본문 변조 · 시각 만료 ·
미래 시각 · 헤더 없음 · 형식 깨짐 · `v1` 다중 · `v0` 단독 · 16진수 아님(13건).

> 재전송 방어가 핵심이다. **서명 자체는 영원히 유효하다** — 시각을 보지 않으면 한 번
> 가로챈 요청을 몇 달 뒤에도 그대로 다시 보낼 수 있다.

**게이트웨이 라우트를 order-service 라우트보다 위에 뒀다.** 순서가 바뀌면 웹훅도
`JwtAuthorization` 을 타고 401 이 되고, Stripe 는 재시도하다 포기한다.

## 5. 포트원(구 아임포트) — 국내 결제

브라우저가 결제창을 연다(`IMP.request_pay`). 서버는 `imp_uid` 를 **포트원에 다시 물어**
확인한다.

### 🔒 포트원 V1 웹훅에는 서명이 없다 — 그래도 안전한 이유

Stripe 와 달리 발신자를 증명할 서명이 없다. 누구나 호출할 수 있다. 그럼에도 결제를
조작할 수 없는 것은 **본문을 단서로만 쓰기** 때문이다:

1. 본문에서 취하는 것은 `imp_uid` **하나뿐** — 금액·상태·주문 참조는 **전부 버린다**
2. 그 `imp_uid` 를 **포트원 API 에 다시 조회** — 상태·금액·`merchant_uid` 는 그 응답에서만
3. `merchant_uid` 는 **우리가 만든 trackingId** — 남의 결제를 보내도 우리 주문을 가리키지 않으면 끝
4. 금액을 DB 의 주문 총액과 대조
5. 같은 결제를 두 주문에 붙이는 것은 유니크 제약이 막는다

즉 공격자가 할 수 있는 최대치는 **이미 존재하는 정상 결제를 한 번 더 확인하게 만드는
것**이고, 반영이 멱등하므로 아무 일도 일어나지 않는다. **본문을 그대로 믿는 순간 이 논리가
전부 무너진다** — 그래서 1번이 핵심이다.

서명이 필요하면 포트원 V2 로 올려야 한다(V2 는 Standard Webhooks 서명을 쓴다).

### 브라우저가 금액을 정해도 되는 이유

`IMP.request_pay` 에는 금액이 들어간다. 즉 **브라우저가 결제 금액을 정한다.** 그래도 되는
것은 확인 단계에서 **포트원이 알려 준 실제 결제액을 DB 의 주문 총액과 대조**하기
때문이다. 1원으로 바꿔 결제하면 결제는 성공하지만 **주문에는 반영되지 않는다.**

> ⚠️ 이 대조를 빼면 즉시 무료 주문이 가능해진다. 포트를 만든 이유가 이 규칙을 제공자마다
> 다시 쓰지 않기 위해서다.

## 6. 설정

| | Stripe | 포트원 |
|---|---|---|
| 켜기 | `STRIPE_ENABLED` | `PORTONE_ENABLED` |
| 서버 자격증명 | `STRIPE_SECRET_KEY` · `STRIPE_WEBHOOK_SECRET` | V1 `PORTONE_API_KEY/SECRET` · V2 `PORTONE_V2_API_SECRET`+`_WEBHOOK_SECRET` |
| 프론트 빌드 인자 | **없음** | V1 `REACT_APP_PORTONE_IMP_CODE` · V2 `REACT_APP_PORTONE_STORE_ID`+`_CHANNEL_KEY` |
| test/live 구분 | **키가 말한다** (`sk_test_`/`sk_live_`) | **키로는 알 수 없다** — 상점 설정이 정한다 |

### Stripe 붙이는 순서 (테스트 모드)

맞춰야 하는 값이 **셋**이고, 각각 나오는 곳이 다르다.

| 값 | 어디서 나오나 | 어디에 넣나 |
|---|---|---|
| `STRIPE_SECRET_KEY` (`sk_test_…`) | 대시보드 → Developers → **API keys** → Secret key | `k8s/20-secret.yaml` |
| `STRIPE_WEBHOOK_SECRET` (`whsec_…`) | 대시보드 → Developers → **Webhooks** → 엔드포인트를 **만들 때** 한 번 보여 준다 | `k8s/20-secret.yaml` |
| `STRIPE_ENABLED` | — | `k8s/10-configmap.yaml` (`"true"`) |

1. **대시보드 오른쪽 위 "Test mode" 를 켠 상태**에서 키를 받는다. 키 접두사가
   `sk_test_` 인지 확인한다 — order-service 기동 로그가 `Stripe 테스트 모드 (sk_test_…)`
   로 다시 말해 준다. `sk_live_` 면 경고가 찍히고 **실제 돈이 움직인다.**
2. 웹훅 엔드포인트를 만든다:
   - URL: `https://<사이트>/order-service/api/order/payment/stripe/webhook`
   - 이벤트: **`checkout.session.completed`** 와 **`checkout.session.async_payment_succeeded`**
     — 코드가 보는 것은 이 둘뿐이고, 나머지는 받아도 무시한다
   - 만들자마자 나오는 `whsec_…` 를 그 자리에서 복사한다. **나중에 다시 볼 수 없다**
     (Reveal 로 볼 수는 있지만 다른 값을 발급받게 되는 경우가 있다 — 그러면 시크릿도
     갈아 끼워야 한다)
3. 두 값을 `k8s/20-secret.yaml` 에 넣고, configmap 의 `STRIPE_ENABLED` 를 `"true"` 로:
   ```bash
   kubectl apply -f k8s/20-secret.yaml
   # 🔴 `kubectl apply -k k8s-overlays/local` 을 직접 치지 말 것 — 그 오버레이의 newTag 가
   #    전 서비스를 옛 버전으로 되돌린다. --config-only 는 지금 도는 이미지를 유지한다.
   ./ops/28-deploy.sh local --config-only --restart order-service
   kubectl -n ecommerce logs deploy/order-service | grep -i stripe
   #   "Stripe 테스트 모드 (sk_test_…)"  +  "Stripe 결제 활성화 (endpoint=https://api.stripe.com)"
   ```
   **켰는데 키가 비어 있으면 기동하지 않는다.** 로그에 어느 값이 비었는지 찍힌다.
4. 결제 화면에서 **Stripe** 를 고르고 테스트 카드로 결제한다: 번호 `4242 4242 4242 4242`,
   만료일은 미래 아무 날, CVC 아무 세 자리. 실패를 보려면 `4000 0000 0000 0002`(거절).
5. 확인은 두 곳에서:
   - 브라우저가 `/order/<id>?stripe=success&session_id=…` 로 돌아와 "결제 완료" 를 그린다
   - 대시보드 → Webhooks → 그 엔드포인트 → 최근 전송이 **200** 인지. 4xx 면 서명
     불일치(시크릿이 틀림)나 게이트웨이 401(라우트 순서, §4)이다

> 🔴 복귀 주소는 `APP_PUBLIC_URL` 에서 만든다(`frontend.url`). 예전에는 `FRONTEND_URL`
> 만 봤는데 **어떤 매니페스트도 그 변수를 설정하지 않아** 클러스터에서 조용히
> `https://localhost:3000` 이 됐다 — 결제 뒤 브라우저가 사용자 자기 PC 의 3000 포트로
> 갔을 것이다. 지금은 configmap 의 `APP_PUBLIC_URL` 이 사이트 주소면 된다.

> 통화는 `STRIPE_CURRENCY` 가 정한다(아래 "통화" 절). 일본 결제를 시험하려면 `JPY` 로 두고
> `FX_RATE_JPY` 를 채운다 — Stripe 대시보드의 계정 통화 설정과 무관하게 Checkout 은 JPY
> 세션을 만들 수 있다. 최소 단위(JPY 는 ×1)는 코드가 통화에서 유도한다.

**로컬에서 웹훅을 받으려면** 공개 URL 이 없으므로 Stripe CLI 가 대신 전달한다:
```bash
stripe listen --forward-to localhost:8084/api/order/payment/stripe/webhook
#   ▶ Ready! Your webhook signing secret is whsec_…   ← 이 값을 STRIPE_WEBHOOK_SECRET 으로
stripe trigger checkout.session.completed
```
CLI 가 주는 `whsec_` 는 대시보드 것과 **다르다** — 로컬은 CLI 값, 클러스터는 대시보드 값이다.

### 🔴 먼저 — 내 포트원 계정은 V1 인가 V2 인가

콘솔 → 결제연동 화면을 본다.

| 보이는 것 | 계정 | 서버 | 프론트 빌드 인자 |
|---|---|---|---|
| **가맹점 식별코드 `imp…`** | V1 (아임포트) | `PORTONE_VERSION=v1` · `PORTONE_API_KEY/SECRET` | `REACT_APP_PORTONE_IMP_CODE` · `_PG` |
| **Store ID `store-…`** 만 | V2 | `PORTONE_VERSION=v2` · `PORTONE_V2_API_SECRET` · `PORTONE_V2_WEBHOOK_SECRET` | `REACT_APP_PORTONE_STORE_ID` · `_CHANNEL_KEY` |

**2024 년 이후 새로 만든 계정은 V2 뿐이다.** `imp…` 가 없으면 아래 "V1" 절은 건너뛰고
"V2" 절로 간다. 두 버전은 API·인증·웹훅이 전부 다르고, 어댑터가 각각 있다
(`PortOnePaymentGateway` · `PortOneV2PaymentGateway`). 같은 제공자에 빈이 둘이면 기동을
막으므로 하나만 켜진다.

### 🔴 통화 — 결제수단마다 다르다

주문에는 금액이 **둘** 있다.

| | 통화 | 어디에 | 무엇의 기준인가 |
|---|---|---|---|
| `Order.total` | **상점 통화** (`STORE_CURRENCY`, 기본 USD) | 카탈로그 가격 · 매출 집계 · pay-service · 구매 이력 | 회계 |
| `Order.chargedAmount` + `currency` | **청구 통화** (`<PROVIDER>_CURRENCY`) | 그 제공자에게 실제로 요청한 결제 | 결제 확인 · 환불 |

국내 PG(포트원)는 KRW 로만 결제되고, 일본 결제를 시험하려면 Stripe·PayPal 을 JPY 로
돌려야 한다. 그래서 **청구 통화는 결제수단마다 정한다:**

```yaml
# k8s/10-configmap.yaml
STORE_CURRENCY:   "USD"     # 상점 통화 — 바꾸지 않는다
PAYPAL_CURRENCY:  "USD"
STRIPE_CURRENCY:  "JPY"     # 일본 테스트
PORTONE_CURRENCY: "KRW"
FX_RATE_KRW: "1350"         # 상점 통화 1 단위 = 청구 통화 얼마
FX_RATE_JPY: "150"
```

**주문을 만들 때** 그 수단의 통화가 상점 통화와 다르면 환율로 청구액을 정해 주문에
저장한다(V6: `currency · charged_amount · fx_rate`). 결제 확인과 환불은 **그 저장값**과
대조한다 — 그 사이 환율이 바뀌어도 "그때 청구한 금액" 이 기준이다. 재계산하면 환율이
바뀐 뒤의 확인·환불이 전부 거부된다.

세 가지가 통화를 따라간다:

- **소수 자릿수** — KRW·JPY 는 0, USD 는 2. `java.util.Currency` 가 안다. 여기서 틀리면
  Stripe 에 100 배(JPY 에 ×100)를 보내거나 PayPal 이 `DECIMALS_NOT_SUPPORTED`("1500.00")
  로 거절한다. `PaymentCurrencyPolicy.toMinorUnits / plain` 이 한 곳이다.
- **프론트** — 결제 화면은 `order.currency · chargedAmount` 를 제공자에게 넘긴다
  (`util/money.js`). PayPal 은 SDK 에 통화가 박히므로 청구 통화가 다르면 SDK 를 그
  통화로 **다시 로드**한다(`resetOptions`). 청구 통화가 상점 통화와 다르면 화면에
  "결제 금액 ¥8,880 (주문 총액 $59 기준)" 을 보여 준다. 상점 통화는 빌드 인자
  `REACT_APP_STORE_CURRENCY`(ops/19 기본 USD)로 번들에 박힌다(B-51).

  🐛 **그 한 줄이 운영의 첫 화면을 죽였다.** webpack 설정이 `EnvironmentPlugin([이름 넷])`
  이라 목록에 없는 `process.env.REACT_APP_STORE_CURRENCY` 는 치환되지 않은 채 번들에
  남았고, 브라우저에는 `process` 가 없어 `Uncaught ReferenceError: process is not defined`
  로 앱 전체가 뜨지 않았다(`util/money.js` 는 모듈 최상단에서 읽는다). 포트원 키
  넷도 같은 처지였다 — 결제 버튼을 누르는 순간 같은 오류가 났을 것이다. 테스트는 Node
  라 `process` 가 있어 아무것도 잡지 못했다. 이제 `webpack.env.js` 가 빌드 환경의
  `REACT_APP_*` 를 **전부** `process.env` 객체로 정의하고(CRA 방식 — 목록을 손으로
  관리하지 않는다), `util/envRefs.test.js` 가 소스의 `process` 참조를
  `process.env.<REACT_APP_*|NODE_ENV|PUBLIC_URL>` 꼴로만 허용한다(맨몸의 `process.env`
  기본 인자, 다른 이름은 실패).
- **하류** — 아웃박스의 `amounts` 는 **상점 통화의 total** 이다. 청구액을 보내면
  KRW 80,000 과 USD 59 가 pay-service 의 한 컬럼에 섞인다.

#### 환율 — 고정값이 기본, 실시간은 선택

`FX_RATE_<통화>` 는 **고정 환율**이다. 청구 통화의 환율이 없으면 그 수단으로는 **주문
생성이 실패**한다. 설정을 빠뜨린 결과가 "환율 1 로 조용히 청구"(59 USD → 59 JPY)여서는
안 된다.

`FX_LIVE_ENABLED=true` 면 **Frankfurter**(ECB 참조 환율, 키 없는 공개 API)에서 받은 값을
먼저 쓴다(`FrankfurterFxRateSource`). 그래도 고정값은 지우지 않는다 — 두 역할이 남는다:

| | |
|---|---|
| **대비책** | API 가 죽었거나 그 통화가 없으면 고정값. 환율 API 장애가 주문 생성 장애가 되지 않는다 (`FxRateSource` 계약: 실패 = 비어 있음, 예외 아님) |
| **상한선** | 실시간 값이 고정값과 `FX_LIVE_MAX_DEVIATION_PERCENT`(기본 30) 넘게 다르면 고정값을 쓰고 경고한다. 통화 코드 혼동·데이터 사고로 청구액이 열 배가 되는 것을 막는 마지막 줄 |

어느 쪽을 썼든 환율은 **주문에 고정**된다(`fx_rate`). "실시간" 은 주문 생성 시점의 값이지
결제 시점마다 다시 계산한다는 뜻이 아니다 — 재계산하면 위에서 말한 대로 확인·환불이
전부 거부된다.

- 캐시: 상점 통화 기준의 환율 표 하나를 `FX_LIVE_TTL_MINUTES`(60) 동안 든다. 주문마다
  API 를 부르지 않는다. ECB 는 **평일 하루 한 번** 갱신하므로 TTL 을 줄여도 값이 더 자주
  바뀌지 않는다. 갱신에 실패하면 옛 표를 계속 쓰고, 실패 뒤 1분은 다시 부르지 않는다.
- ⚠️ order-service 파드에서 `api.frankfurter.app` 으로 나가는 HTTPS 가 열려 있어야 한다.
  막혀 있으면 기동은 되지만 매 갱신이 경고 로그와 함께 실패하고 고정값만 쓴다 — "켰는데
  값이 안 바뀐다" 면 그 로그를 먼저 본다.
- ECB 참조 환율은 **은행 매매 환율이 아니다.** 실결제의 청구액을 정하기에는 거친 값이지만,
  정합성(확인·환불)은 주문에 고정된 값으로 보장되므로 정밀도는 정산 손익의 문제다.
  실결제로 가면 PG 의 정산 환율에 맞춘 출처로 `FxRateSource` 구현을 바꾸는 것이 맞다.

가드: `PaymentCurrencyPolicyTest`(실시간 우선 · 편차 상한 · 비어 있으면 고정 · 예외도 고정 ·
둘 다 없으면 여전히 실패) · `FrankfurterFxRateSourceTest`(응답 모양 · TTL · 실패 = 비어 있음 ·
장애 중 재시도 억제 · 옛 표 유지).

#### 🐛 `Order.total` 은 이제 `numeric(19,2)` 다 (V7)

예전에는 `int` 라 상점 통화의 소수가 잘렸다($59.98 → 59, docs/35 · docs/40 #10). 프론트가
제공자에 보내는 금액도 같은 59 라 검증은 통과했지만 **실제로 98센트를 덜 받았고**, 청구액은
그 잘린 값에서 환산됐으므로 같은 오차를 물려받았다 — 통화 문제가 아니라 그 이전의 문제였다.

V7 이 `total · total_before_discount · shipping_price · tax_price` 넷을 함께 옮긴다(한 식으로
묶여 있어 하나만 옮기면 화면의 세 줄이 맞지 않는다). `OrderPricing` 은 이제 버림이 아니라
상점 통화의 자릿수로 **반올림**한다 — 카탈로그 가격이 이미 둘째 자리까지라 반올림이 실제로
일어나는 것은 쿠폰 할인율(59.98 × 10% = 5.998)뿐이고, 화면도 같은 값을 서버에서 받는다.
`OrderDto`·`AdminOrderSummaryDto` 의 금액도 `BigDecimal` 이고, 프론트는 `{total}$` 대신
`formatMoney(total, STORE_CURRENCY)`(`util/money`) 로 찍는다.

> ⚠️ **되돌리기가 막히는 마이그레이션이다**(`ROLLBACK-UNSAFE`). integer → numeric 으로
> 바꾼 뒤 옛 이미지(int 필드)는 Hibernate validate 에서 기동하지 못한다. B-54 가드가
> `ALTER COLUMN … TYPE` 도 잡도록 넓혔다. 옛 주문의 잘린 센트는 복구하지 않는다 — 얼마가
> 잘렸는지 어디에도 남아 있지 않다.
>
> 등급 집계(`aggregatePurchases`)는 합계를 `Long` 으로 캐스팅해 `PurchaseStatDto.recentAmount`
> 를 그대로 둔다 — 등급 구간이 달러 단위라 센트는 의미가 없다. 이 JPQL 은 Testcontainers
> 없이는 부팅 검증이 안 되므로(§8) 배포 후 `order-service` 기동 로그를 본다.

> ⚠️ **같은 키를 두 번 쓰면 배포가 멈춘다.** `PORTONE_ENABLED: "true"` 를 기존
> `PORTONE_ENABLED: "false"` 줄을 지우지 않고 아래에 **더하면** YAML 이 무효가 되고
> `ops/28` 이 `mapping key "PORTONE_ENABLED" already defined` 로 죽는다. 값을 **바꾸거나**,
> 베이스 파일은 그대로 두고 `k8s-overlays/local/patch-local-endpoints.yaml` 의 `data` 에
> 그 키를 적는다 — 패치는 같은 키를 **덮어쓰므로** 베이스와 겹쳐도 된다. 커밋되지 않는
> 파일이라 `git pull` 과 충돌하지도 않는다.

### 포트원 V2 붙이는 순서 (신규 계정)

| 값 | 어디서 나오나 | 어디에 넣나 |
|---|---|---|
| `PORTONE_V2_API_SECRET` | 콘솔 → 결제연동 → **API Keys** → V2 API Secret 발급 (한 번만 보여 준다) | `k8s/20-secret.yaml` |
| `PORTONE_V2_WEBHOOK_SECRET` (`whsec_…`) | 콘솔 → 결제연동 → **웹훅** → 엔드포인트 추가 시 | `k8s/20-secret.yaml` |
| `PORTONE_ENABLED` · `PORTONE_VERSION` | — | 기존 줄의 **값을 바꾼다**(`"true"` · `"v2"`) — 줄을 더하지 말 것. 로컬 값은 `k8s-overlays/local/patch-local-endpoints.yaml` 에 두는 편이 낫다(아래 ⚠️) |
| `REACT_APP_PORTONE_STORE_ID` (`store-…`) | 결제연동 화면 맨 위 — 공개 값 | `ops/19` 빌드 인자 |
| `REACT_APP_PORTONE_CHANNEL_KEY` (`channel-key-…`) | **결제 채널** → 채널 상세 → 채널 키 — 공개 값 | `ops/19` 빌드 인자 |

1. **결제연동 → 결제 채널 → 채널 추가**: **테스트 결제**, PG 는 처음이면 KG이니시스.
   PG 상점 정보 칸(MID · 사인키 · INIAPI key/iv)에는 이니시스 **테스트 공개값**을 넣는다 —
   콘솔이 채워 주거나 안내에 보인다. ⚠️ **"결제 취소" 칸(INIAPI key/iv)이 비면 결제는
   되는데 환불(§10)이 `code != 0` 로 실패한다.** 채널이 생기면 **채널 키**를 복사한다.
2. **API Keys** 에서 V2 API Secret 을 발급한다. 한 번만 보여 준다.
3. **웹훅** 에 엔드포인트를 추가한다: `https://<사이트>/order-service/api/order/payment/portone/webhook`,
   버전 **V2**. 만들면 나오는 `whsec_…` 를 복사한다 — Standard Webhooks 서명 키다.
   코드는 `Transaction.Paid` 만 처리하고 나머지 종류는 200 으로 무시한다.
4. 서버:
   ```bash
   # 20-secret.yaml: PORTONE_V2_API_SECRET, PORTONE_V2_WEBHOOK_SECRET
   # k8s-overlays/local/patch-local-endpoints.yaml 의 data 에 두 줄을 넣는다(베이스 값을 덮는다):
   #   PORTONE_ENABLED: "true"
   #   PORTONE_VERSION: "v2"          (PORTONE_CURRENCY 는 기본 KRW)
   kubectl apply -f k8s/20-secret.yaml
   ./ops/28-deploy.sh local --config-only --restart order-service   # apply -k 를 직접 치면 옛 태그로 되돌아간다
   kubectl -n ecommerce logs deploy/order-service | grep -i 포트원
   #   "포트원 V2 결제 활성화 (endpoint=https://api.portone.io apiSecret=NN자)"
   ```
   V2 인데 두 시크릿 중 하나라도 없으면 **기동하지 않는다.**
5. 프론트 — **다시 빌드**:
   ```bash
   REACT_APP_PORTONE_STORE_ID=store-… REACT_APP_PORTONE_CHANNEL_KEY=channel-key-… \
   REACT_APP_API_URL=… REACT_APP_PAYPAL_CLIENT_ID=… ops/19-build-images.sh …
   ```
   Store ID 가 있으면 화면은 V2 로 동작한다(V1 식별코드가 같이 있어도 V2 가 우선).
6. 결제수단에서 **포트원** → 결제창. V2 는 결제 id(`payment-<uuid>`)를 **우리가** 만들고
   주문 참조는 `customData` 로 실어 보낸다 — 서버가 포트원에 조회해서 거기서 읽는다.
   확인: 브라우저가 `/payment/portone/confirm` 뒤 "결제 완료", 콘솔 → 결제내역에 `PAID`,
   웹훅 로그에 200.

> V1 과 달리 웹훅에 **서명이 있다**(`PortOneV2WebhookVerifier`, Standard Webhooks —
> `webhook-id` · `webhook-timestamp` · `webhook-signature`, 키는 `whsec_` 뒤 base64 를
> **디코딩한 바이트**). 서명이 틀리면 본문을 읽지도 않는다. 그래도 §5 의 "본문은 단서로만"
> 규칙은 그대로다 — 서명은 "포트원이 보냈다" 를 말할 뿐, 어느 주문의 얼마짜리 결제인지는
> 여전히 다시 조회한다.

### 포트원 V1 붙이는 순서 (imp… 식별코드가 있는 계정)

Stripe 와 달리 값이 **넷**이고, 그중 둘은 **프론트 빌드에 박힌다**(B-51). 서버만 바꾸고
프론트를 다시 빌드하지 않으면 결제창이 안 뜬다.

| 값 | 어디서 나오나 | 어디에 넣나 |
|---|---|---|
| `PORTONE_API_KEY` · `PORTONE_API_SECRET` | 관리자콘솔 → 결제연동 → **식별코드·API Keys** (REST API 키 / 시크릿) | `k8s/20-secret.yaml` |
| `PORTONE_ENABLED` | — | 기존 줄의 값을 `"true"` 로 **바꾼다**(줄을 더하지 말 것), 또는 오버레이 패치에 둔다 |
| `REACT_APP_PORTONE_IMP_CODE` (`imp…`) | 같은 화면의 **가맹점 식별코드** — 공개 값이다 | `ops/19` 빌드 인자 |
| `REACT_APP_PORTONE_PG` | 아래 2번에서 고른 PG 의 코드 | `ops/19` 빌드 인자 (기본 `html5_inicis`) |

1. **관리자콘솔 → 결제연동 → 식별코드·API Keys** 에서 셋을 받는다. ⚠️ **API Secret 은
   발급 때 한 번만 보여 준다.** 그 자리에서 복사한다.
2. **결제연동 → 결제 채널 관리 → 채널 추가** 에서 **테스트 결제** 로 PG 를 하나 고른다.
   처음이면 **KG이니시스** 가 무난하다(가입 없이 테스트 채널이 열린다). 이때 고른 PG 의
   코드가 `REACT_APP_PORTONE_PG` 다 — 이니시스 `html5_inicis`, 토스페이먼츠 `tosspayments`,
   나이스 `nice`, KCP `kcp`. **채널의 PG 와 이 값이 다르면 결제창이 "등록되지 않은 PG"
   로 뜬다.** 브라우저의 `IMP.request_pay({pg})` 가 그 값을 그대로 쓴다.
3. **결제연동 → 웹훅 관리** 에 URL 을 넣는다:
   `https://<사이트>/order-service/api/order/payment/portone/webhook`. 서명은 없다 —
   본문에서 `imp_uid` 만 취해 API 로 다시 조회하는 것이 안전장치다(§5).
4. 서버:
   ```bash
   # k8s/20-secret.yaml 에 PORTONE_API_KEY / PORTONE_API_SECRET, configmap 에 PORTONE_ENABLED: "true"
   kubectl apply -f k8s/20-secret.yaml
   ./ops/28-deploy.sh local --config-only --restart order-service   # apply -k 를 직접 치면 옛 태그로 되돌아간다
   kubectl -n ecommerce logs deploy/order-service | grep -i 포트원
   #   "포트원 결제 활성화 (endpoint=https://api.iamport.kr apiKey=1234…)"
   ```
5. 프론트 — **다시 빌드해야 한다.** 식별코드가 번들에 박히기 때문이다:
   ```bash
   REACT_APP_PORTONE_IMP_CODE=imp12345678 REACT_APP_PORTONE_PG=html5_inicis \
   REACT_APP_API_URL=… REACT_APP_PAYPAL_CLIENT_ID=… ops/19-build-images.sh …
   ```
   `ops/19` 가 식별코드 앞 5자와 PG 를 로그에 찍는다(값 전체는 찍지 않는다). 비어 있으면
   결제수단에서 포트원을 눌렀을 때 "설정되지 않았다" 안내가 뜨고 나머지는 그대로다.
6. 결제 화면에서 **포트원** 을 고르면 결제창이 뜬다. 테스트 채널은 실제 카드 번호를 넣어도
   **승인 직후 자동 취소**된다(PG 가 테스트 거래를 밤에 일괄 취소한다). 확인은 두 곳:
   브라우저가 `/payment/portone/confirm` 뒤 "결제 완료" 를 그리는지, 콘솔 → 결제내역에
   그 거래가 `paid` 로 있는지.

> ⚠️ 포트원은 **키만으로 테스트/실결제를 구분할 수 없다** — 채널 설정이 정한다. 기동
> 로그가 그 사실을 경고로 남긴다. 실결제 채널로 바꿀 때는 사업자등록·통신판매업 신고와
> PG 계약이 먼저다(docs/49 첫머리).

**둘 다 기본이 꺼짐이다.** PayPal 검증은 반대로 기본이 켜짐인데(`mode=http`), 방향이 다른
이유가 있다: PayPal 은 이미 쓰는 수단이라 "설정을 깜빡하면 검증 없이 통과" 가 되면 안
되고, Stripe·포트원은 새 수단이라 설정하지 않은 환경에서 기동이 실패하면 기존 배포가
멈춘다. **켠 뒤에 자격증명이 없으면 기동을 막는다** — 반쯤 켜진 상태(목록에는 뜨는데
누르면 500)가 제일 나쁘다.

## 7. 결제수단 선택이 실제로 동작한다

주문 화면이 **결제수단과 무관하게 PayPal 버튼만** 그렸다. 분기 조건이 주석 처리된 채였다:

```jsx
{/* {order.paymentMethod === "paypal" && ( */}
<PayPalButtons ... />
{/* { order.paymentMethod === "credit_card" && } */}
```

"무통장입금"을 골라도 PayPal 버튼이 떴다. 이제 분기하고, 아직 못 쓰는 수단은 버튼 대신
**왜 못 쓰는지**를 말한다. 이름의 `"Creadit cart"` 오타도 고쳤다.

## 8. ⚠️ 검증하지 못한 것

> ✅ **Stripe — 운영에서 결제·환불 모두 성공**(2026-09-26, 사용자 확인). 아래 필드 매핑이 실제 응답과 맞다는 뜻이다.
> PayPal 은 아직 실제 결제로 확인하지 못했다. 포트원은 그대로 미검증.

**Stripe·포트원 연동은 실제 API 로 검증하지 못했다.** 이 작업 환경에서 두 API 에 나갈 수
없고 테스트 자격증명도 없다. 문서 규격대로 작성했고, 응답 필드 매핑을 한 곳에 모아 두었다:

- Stripe — `StripeCheckoutGateway.verify` (`payment_status` · `amount_total` · `currency`)
- 포트원 — `PortOnePaymentGateway.toVerified` (`status` · `amount` · `currency`)

실제 응답과 다르면 **거기만** 고치면 된다. 검증 실패 로그가 무엇을 받았는지 남긴다.

서명 검증(`StripeWebhookVerifier`)과 포트/레지스트리는 <b>순수 로직이라 테스트로 검증됐다</b> —
외부 호출이 없기 때문이다.

### 🐛 꺼 둔 환경에서 order-service 가 기동하지 못했다 (수정됨)

`StripeConfig`·`PortOneConfig` 는 `*.enabled=true` 일 때만 게이트웨이 빈을 만든다. 그런데
그 게이트웨이를 생성자로 받는 `StripePaymentService`·`PortOnePaymentService` 에는 **조건이
없었다.** 클러스터 기본값(둘 다 `"false"`)에서 order-service 는

```
No qualifying bean of type 'StripeCheckoutGateway' available
```

로 기동조차 못 했다. 컨트롤러는 `@Autowired(required = false)` 로 "빈이 없으면 null" 을
기다리고 있었으니 빠진 것은 서비스 클래스의 `@ConditionalOnProperty` 한 줄이다. 두 서비스에
같은 조건을 붙였다.

**왜 늦게 발견됐나.** 단위 테스트 189개는 전부 통과했다 — 이 종류의 버그는 **컨텍스트를
띄워야만** 드러난다. Testcontainers 기동 테스트(`OrderServiceApplicationTests`)가 그 일을
하는데, Docker 가 없는 환경에서는 돌지 않는다. 배포에서는 롤아웃 타임아웃 → 자동 복귀 →
옛 파드가 Running 이라 `kubectl logs deploy/order-service` 에는 **멀쩡한 옛 파드의 로그**
(아웃박스 폴링 DEBUG)만 보였다. `ops/28` 이 이제 롤아웃 실패 직후 **새 파드**의 기동 오류를
뽑아 보여 준다.

**가드.** `OptionalPaymentServicesWiringTest` 가 `ApplicationContextRunner` 로 서비스 둘만
올려 조건을 고정한다 — 둘 다 꺼진 기본값에서 컨텍스트가 뜨고, 켜면 생기고, 켰는데
게이트웨이가 없으면 막힌다. 배포 전 진짜 확인은 여전히 컨테이너 기동 테스트다:

```bash
./gradlew :order-service:test -PwithContainers     # Docker 필요
```

이번에는 로컬 PostgreSQL 16 + Redis 를 직접 띄워 같은 검증을 했다: Flyway V1–V11 적용,
Hibernate `validate` 통과, `결제 게이트웨이: [PAYPAL, STRIPE, PORTONE, BANK_TRANSFER]`
(더미 자격증명으로 켠 경우)와 `[PAYPAL, BANK_TRANSFER]`(기본값) 둘 다 기동.

### 🐛 Stripe 결제가 영원히 NOT_PROCESSED 였다 — CHECK 제약이 enum 보다 낡았다 (수정됨)

첫 실결제에서 드러났다. 결제 확인은 200, 아웃박스도 COMPLETED, 그런데 주문은 확정되지 않았다.
pay-service 로그에 원인이 있었다:

```
ERROR: new row for relation "payment" violates check constraint "payment_payment_type_check"
  Detail: Failing row contains (..., COMPLETED, STRIPE, ...)
```

베이스라인(V2)은 hbm2ddl 산출물이라 `@Enumerated(STRING)` 컬럼마다 **그 시점의 enum 값**으로
`check (payment_type in ('PAYPAL','CREDIT_CARD','CASH'))` 가 박혀 있다. 그 뒤 enum 에
STRIPE·PORTONE 을 더했는데 제약은 그대로였다. Hibernate `validate` 는 CHECK 를 보지 않으므로
기동은 멀쩡했고, 컨테이너 기동 테스트도 못 잡는다 — **그 값이 처음 저장되는 순간**에만
드러난다. 응답이 order-service 로 돌아가지 않으니 사가가 주문을 PAID 로 못 바꿨다.

같은 결함이 두 곳 더 있었다: `orders.order_status` 에 REFUNDED 가 없었고(첫 전액 환불에서
터질 참이었다), `notification.type` 에 나중에 더한 세 종류가 없었다.

| 서비스 | 마이그레이션 | 넓힌 것 |
|---|---|---|
| pay-service | V3 | `payment_type` + STRIPE, PORTONE |
| order-service | V12 | `order_status` + REFUNDED |
| notification-service | V6 | `type` + NOTICE_PUBLISHED, ANNOUNCEMENT, EVENT_STARTED |

세 마이그레이션 모두 그 컬럼의 CHECK 를 찾아 지우고 같은 이름으로 다시 건다. 되돌리기는
안전하다 — 허용 범위를 넓히기만 한다.

**가드 B-55**(`ConfigurationGuardrailTest`): 엔티티의 문자열 enum 컬럼마다, 마이그레이션이
마지막으로 정의한 CHECK 목록이 자바 enum 의 모든 상수를 담고 있는지 소스를 대조한다.
enum 에 값을 더하고 마이그레이션을 빠뜨리면 빌드가 막는다. 일부러 좁게 건 것(쿠폰 대상의
`ALL`)은 이유와 함께 예외 목록에 적는다.

**이미 결제된 주문**은 마이그레이션 뒤에도 저절로 확정되지 않는다 — 실패한 결제 응답은
DLT 로 갔거나 버려졌다. `pay.payment` 에 행이 없고 `orders.orders.provider_payment_id` 는
있는 주문이 그것이다. 수가 적으면 관리자가 확인한 뒤 수동으로 상태를 바꾸는 것이 가장 안전하다.

## 10. 환불 — 결제 확인의 거울상

환불을 붙이면서 한 일은 결제 확인 때와 같다: **포트 하나, 규칙 한 곳.**

```java
public interface RefundingGateway extends PaymentGateway {
    RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason);
}
```

`HostedCheckoutGateway` 와 같은 이유로 별도 인터페이스다 — 환불 API 가 없는 제공자
(무통장입금 같은 상태 기계)에게 빈 메서드를 강요하지 않는다. 지금은 셋 다 구현한다.

| | 무엇을 환불하나 | 호출 |
|---|---|---|
| PayPal | 주문이 아니라 **캡처** — 주문을 조회해 캡처 id 를 찾아간다 | `POST /v2/payments/captures/{id}/refund` |
| Stripe | 세션이 아니라 **PaymentIntent** — 세션을 조회해 찾아간다 | `POST /v1/refunds` |
| 포트원 | `imp_uid` 그대로 | `POST /payments/cancel` |

세 곳 모두 **금액을 명시한다.** 비워 보내면 "남은 전액" 인데, 그러면 환불액을 제공자가
정하는 셈이다. 환불액은 DB 의 주문 총액이고, 응답의 금액을 그것과 다시 대조한다.

### 규칙 — `OrderDataService.refundOrder` 하나

1. 주문이 실재하는가, **요청자의 것인가** (관리자는 예외 — `User-Role`)
2. 이미 환불됐는가 → 멱등. **제공자를 다시 부르지 않는다** (두 번 환불되면 안 된다)
3. 결제된 주문인가 — 결제 전 주문은 취소의 영역이다
4. 배송 전인가 — 사용자 스스로는 `PAID`·`PROCESSING` 까지. 발송된 뒤에는 물건이 돌아와야
   하므로 관리자가 판단한다
5. 제공자가 받아들였는가, 환불액·통화가 주문 총액과 같은가 — **부분 환불은 없다**

**순서가 중요하다.** 제공자를 부르기 전에 거부할 수 있는 것은 전부 먼저 거부한다. 제공자가
돈을 돌려준 뒤 우리가 거부하면 "돈은 나갔는데 주문은 결제 상태" 가 된다. 그래서 제공자
호출 뒤에 남는 검사는 응답 대조뿐이고, 그것이 실패하면 **예외에 환불 참조를 실어**
운영자가 손으로 맞출 수 있게 한다.

### 상태와 하류

- `OrderStatus.REFUNDED` 가 생겼다. `CANCELED`(결제 전에 접음)와 다르다. 매출 집합
  `PAID_STATUSES` 에는 **없다** — `PaidStatusesTest` 가 9번째 상태를 사람이 분류하게 막았고,
  프론트의 `orderStatus.js` 도 같은 방향이다.
- pay-service 에는 결제 확인과 **같은 아웃박스**로 `paymentStatus=CANCELED` 를 보낸다.
  🐛 그쪽 `persistPayment` 는 **기존 행을 절대 바꾸지 않았다** — 같은 trackingId 로 무엇이
  오든 저장된 상태를 되돌려 보냈다. 재전달에는 맞지만 환불에는 틀리다. `COMPLETED →
  CANCELED` 한 방향만 열었다.
- 쿠폰은 `OrderSaga.compensateRefund` 가 기존 `CouponRollbackRequest` 로 돌려준다.
- **재고는 물건이 어디 있느냐가 정한다** — 아래 "반품 입고와 재고 복원".

### 반품 입고와 재고 복원

환불이 곧 반품은 아니다. 발송 전 환불은 물건이 창고에 그대로라 재고가 **바로** 돌아와야
하고, 발송된 뒤의 환불은 물건이 밖에 있으니 **돌아온 때** 돌아와야 한다. 그 사이에 재고를
늘리면 없는 물건을 판다.

| 환불 시점 | `Order.awaitingReturn` | 재고 복원 |
|---|---|---|
| 발송 전(`PAID`·`PROCESSING`) | false | 환불 직후 — `OrderSaga.compensateRefund` |
| 발송 뒤(`DISPATCHED`·`DELIVERED`·`COMPLETED`, 관리자만) | **true** | 관리자가 **반품 입고**를 기록할 때 — `OrderSaga.restock` |
| 물건이 먼저 돌아오고 환불이 나중 | false (입고가 이미 있다) | 입고 때 이미 돌아갔다 |

**복원 이벤트는 차감의 역이다.** 새 토픽·새 리스너를 만들지 않았다 — 결제 때 보내는
`ProductUpdateRequest`(토픽 `product-update`)에 `restock=true` 를 실어 보내고, catalog 의
`productUpdated` 가 같은 항목을 **부호만 바꿔** 적용한다(사이즈 수량 +, 판매량 −, 0 아래로는
안 간다). 경로가 둘이면 한쪽만 고쳐지는 날이 온다(CAT-13 이 그런 결함이었다). Avro 는 필드
추가(기본 `false`)라 옛 소비자는 차감으로 읽는다.

**한 번만.** 멱등 키는 `restock-<orderId>` — 차감(`order-<orderId>`)과 다른 키라 둘 다 한
번씩이다. order 쪽은 `stockRestoredAt` 이 한 번만 찍히고(조건부 UPDATE 한 문장), catalog 쪽은
`processed_event` 가 두 번째를 버린다. 순서는 **보내고 나서 표시**다 — 반대면 발행이 실패했는데
DB 는 "돌려줬다" 가 된다.

**반품 입고** — `POST /api/order/admin/return-received` `{trackingId, restock}` (관리자만,
재고를 늘리는 경로다). 수량은 본문에 없다 — DB 의 주문 품목 그대로이고, 부분 반품은 부분
환불이 없는 것과 같은 이유로 없다. `restock=false`(파손·오염)면 입고만 기록되고 재고는
그대로다. 규칙(`OrderDataService.receiveReturn`): 이미 입고됐으면 멱등, 물건이 나간
주문(반품 대기, 또는 아직 환불되지 않은 발송·배송·확정)이어야 한다. 발송 전 환불에 입고를
또 기록하면 재고가 두 번 늘므로 거절한다.

관리자 화면(`/admin/refund`)에 **반품 대기** 필터(`status=AWAITING_RETURN`, 상태가 아니라
플래그다)와 행마다 **반품 입고** 버튼(재고 복원 체크박스)이 붙었다. 고객 상세는 "반품 접수
중 / 반품 입고 완료" 를 보여 준다.

가드: `OrderSagaRestockTest`(언제 · 무엇을 · 한 번만 · 실패 시 표시 없음),
`OrderReturnTest`(입고 규칙), `OrderRefundTest`(발송 전/후의 `awaitingReturn`),
catalog `ProductServiceUnitTest`(부호 반전 · 판매량 0 에서 멈춤), `AvroWireTest`(restock 왕복).

### 부분 환불 · 부분 반품 (V9)

단위는 **주문 품목의 수량**이다. 요청 본문은 `items: [{orderProductId, qty}]` 뿐이고 비우면
"남은 전부" 다 — 그래서 옛 호출(전액)은 그대로 동작한다. 금액은 여기에도 없다.

**금액은 `RefundPricing` 이 정한다** — 서버가, 세 규칙으로:

1. **마지막 환불은 남은 전부다.** 요청이 아직 환불되지 않은 수량을 전부 덮으면 금액은
   `total − 지금까지 환불액`. 배송비와 반올림 찌꺼기가 여기서 정리되므로 부분 환불을 몇 번
   하든 **합은 정확히 `total`** 이다(`RefundPricingTest.partsSumToTheTotal`).
2. **그 전의 환불은 할인을 나눠 뺀 단가다.** 쿠폰이 10% 였으면 품목도 10% 깎인 값으로 —
   정가로 돌려주면 손님이 낸 것보다 더 받는다. 할인은 상품 금액(배송비 뺀 값) 대비 품목
   금액의 비율로 나눈다. **배송비는 부분 환불에서 돌려주지 않는다** — 나머지 물건은 여전히
   배송됐다.
3. 어느 경우에도 남은 환불 가능액을 넘지 않는다. 청구 통화의 환불액은 주문에 고정된
   환율로 바꾸되, 마지막 환불은 **청구 잔액 전부**라 환율 반올림 찌꺼기도 정리된다.

품목마다 네 수량을 센다(`OrderProduct`): `refundedQty` 돈이 돌아간 수 · `returnedQty` 물건이
돌아온 수(**발송 전에 환불된 수량도 든다** — 창고를 떠나지 않았다) · `restockedQty` 재고로
되돌린 수 · `discardedQty` 파손 등으로 재고에 넣지 않은 수. 아직 재고로 갈 수 있는 수는
`returned − restocked − discarded` 이고 `OrderSaga.restock` 은 정확히 그만큼만 보낸다.
반품 대기(`awaitingReturn`)는 이제 `Σrefunded > Σreturned` 다.

| | 부분 환불 | 전액 완료(남은 수량 0) |
|---|---|---|
| 주문 상태 | 그대로(결제됨) — 돈이 일부 남아 있다 | `REFUNDED` |
| 매출 집계 | `total − refundedAmount` (`aggregatePurchases`) | 집합에서 빠진다 |
| pay-service | 보내지 않는다 — 결제가 살아 있고 그쪽 상태는 둘뿐이다 | `CANCELED` |
| 쿠폰 | 돌려주지 않는다 — 여전히 그 쿠폰으로 산 주문이다 | 복원 |
| 재고 | 발송 전이면 환불 수량만큼 바로, 발송 뒤면 입고 때 | 같다 |

환불 한 건 한 건은 `order_refund` 에 남는다(금액 둘 · 통화 · 품목 JSON · 제공자 참조).
`Order.refundId` 는 "마지막 환불 참조" 다. **제공자 멱등 키**가 중요해졌다 — 같은 캡처에
환불이 여럿이라 `PayPal-Request-Id: refund-<captureId>` 만으로는 두 번째가 첫 번째의
응답으로 대체된다. `RefundingGateway.refund` 에 `refundKey`("같은 상태에서 같은 품목·수량"
의 해시)가 들어가 PayPal 은 요청 id 에, Stripe 는 `Idempotency-Key` 에 싣는다. 포트원은
멱등 헤더가 없다.

재고 복원의 멱등 키는 `restock-<orderId>-u<누적 복원 수>` — 같은 상태에서 다시 불리면 같은
키(재시도가 접힌다), 다음 입고 뒤에는 다른 키. ⚠️ 발행은 됐는데 품목 표시가 실패하고 **그
사이 새 입고**가 들어오면 다음 키가 달라져 이전 수량이 한 번 더 실린다 — error 로그로
남기고 운영자가 맞춘다(다른 보상과 같은 등급).

화면: 고객 상세와 관리자 화면 모두 줄마다 `0…남은 수량` 을 고른다(`ItemQtyPicker`). 기본은
남은 전부라 손대지 않으면 옛 동작(전액·전량)이고, 그때는 `items` 를 보내지 않는다. 부분
환불의 예상 금액은 **그리지 않는다** — 할인 배분을 화면이 흉내 내면 서버 값과 어긋나
"왜 적게 돌아왔냐" 가 된다. 문구로만 말한다.

옛 주문(V9 이전, 품목 카운터가 0)은 백필한다: `REFUNDED` 면 전 수량 환불, `stock_restored_at`
이 있으면 전 수량 복원, `return_received_at` 만 있으면 전 수량 폐기. 그래야 "남은 수량" 이
0 이 되어 환불·입고를 또 하지 못한다.

가드: `RefundPricingTest` 9, `OrderRefundTest`(부분 · 마지막이 완료 · 초과 거절 · 키가 다름 ·
발송 뒤 부분은 반품 대기), `OrderReturnTest`(부분 입고 · 폐기 · 초과 거절),
`OrderSagaRestockTest`(pending 만 · 누적 키 · 부분 환불엔 쿠폰 없음), 프론트
`ItemQtyPicker.test.jsx` · `Refunds.test.jsx` · `RefundButton.test.jsx`.

### 🐛 겸사겸사 잡힌 것

- **목록은 PAID, 상세는 NOT_PROCESSED.** 주문 상세는 Redis 캐시(60분)에서 나오는데, 결제
  응답으로 상태를 PAID 로 바꾸는 사가가 캐시를 비우지 않았다. 확정을 기다리는 화면이 같은
  상세를 14번 다시 읽어도 늘 옛 값이라 시한을 넘겨 "확정이 늦어진다" 고 했고, 미결제로 보이는
  상세에서 결제 버튼을 누르면 "이미 결제된 주문입니다" 였다. 상태를 바꾸는 곳은 반드시
  `OrderCacheEvictor` 를 부른다(사가의 PAID·CANCELED·재고 복원 표시).
- **환불했더니 환불과 무관한 물건까지 사라졌다.** 범인은 환불이 아니라 그 전 결제의
  `cart-empty` 였다 — "이 사용자의 장바구니를 통째로 지워라" 가 결제 응답 뒤 비동기로
  도착하니, 확정이 늦어지는 사이 새로 담은 물건까지 지웠다. 이제 사가가 주문의 품목
  (상품·사이즈·색상·수량)을 싣고(`CartEmptyRequest.items`, Avro 기본값 `[]` 로 호환)
  cart-service 는 그 줄만 뺀다. 품목이 비어 오면(옛 발행자) 예전처럼 전부 비운다
  (`CartEmptyLinesTest`).
- 환불·취소된 주문에도 "배송 준비 중" 과 "결제가 확인됐습니다" 가 남아 있었다 — 새로고침
  대신 주문만 다시 읽게 되면서 드러났다. 둘 다 확정·취소·환불 뒤에는 그리지 않는다.

- **환불이 pay-service 에 한 번도 반영되지 않았다.** 요청 중복 판정이 주문 id 만 봤다 —
  "완료된 응답 이벤트가 있으면 처리한 것". 환불 요청(CANCELED)은 같은 주문으로 오므로 결제
  완료 응답이 있는 한 언제나 "중복" 이었고, 옛 COMPLETED 응답이 다시 발행됐다. 결제 행은
  영원히 COMPLETED, order-service 사가는 그 응답을 무시(주문이 결제 대기가 아니다), 쿠폰·재고
  복원과 환불 알림 전부 없었다. 주문 상태만 REFUNDED 라(order-service 가 동기로 바꾼다) 화면은
  환불된 것처럼 보였다. 중복 판정에 **요청한 상태**를 넣었다(`PaymentIdempotencyTest`). 알림은
  `PAYMENT_REFUNDED`(환불 완료)로 갈라진다 — 예전엔 CANCELED 도 "결제 완료" 였고 중복 키가
  겹쳐 아무것도 안 만들어졌다(notification-service V7, `PaymentNotificationTest`).

`PaymentRequest.avsc` 의 `PaymentType` 심볼이 `["PAYPAL"]` 뿐이었다. DTO 에는 STRIPE·
PORTONE 이 있으므로 Stripe 로 결제하면 아웃박스 → Avro 변환에서 `valueOf("STRIPE")` 가
터져 **결제 이벤트가 pay-service 에 도착하지 못했을 것이다.** 심볼을 채웠다(enum default
가 있어 옛 소비자도 깨지지 않는다). §8 의 "실제 API 로 검증하지 못했다" 가 이런 것을
못 잡는다는 뜻이다 — E2E 한 번이 여전히 필요하다.

### 화면

주문 상세에서 상태에 따라 셋 중 하나를 그린다: 발송 전이면 **버튼**, 발송 뒤면 **고객센터
안내**(서버가 어차피 거절하지만 눌러 보고 거절당하게 두지 않는다), 환불됐으면 **완료 +
참조**. 보내는 것은 `trackingId` 와 사유뿐이다 — 금액도 결제 참조도 본문에 없다.

**관리자 화면** — `/admin/refund`. 상태로 거르거나 추적번호 하나를 찾고, 결제된 주문에는
전부 환불 버튼이 있다(관리자는 발송된 주문도 환불한다 — 반품 회수는 위 "반품 입고"). 같은
`POST /api/order/refund` 를 쓰고, 서버가 `User-Role` 로 두 규칙(소유권·발송 전)을 푼다.
목록은 `GET /api/order/admin/orders` — 소유권 검사가 없는 유일한 목록이라 역할 판정이
곧 접근 통제이고, **캐시하지 않는다**(환불 직후 옛 상태를 보여 주면 같은 주문을 또
환불하려 든다).

### 🐛 환불해도 재고가 돌아가지 않았다 — "No transaction is in process"

운영 로그: `재고 복원 발행 실패 — 수동 확인 필요 … IllegalStateException: No transaction is
in process`. 재고 복원·쿠폰 롤백·장바구니 롤백·장바구니 비우기 템플릿은 `transactional.id`
를 가진 **트랜잭션 프로듀서**다(docs/38 §1.17). 결제 응답 리스너 안에서는 DB 트랜잭션에
Kafka 트랜잭션이 붙어 보내지지만, 환불·반품 입고·무통장 취소는 **HTTP 요청에서 DB
트랜잭션이 끝난 뒤** 보낸다 — 트랜잭션이 없어 매번 실패했다. 환불은 끝났는데 재고도, 전액
환불의 쿠폰도 돌아가지 않았다.

`common/kafka/TransactionalSends.run(template, ops -> ops.send(...))` 가 가른다: 트랜잭션
프로듀서인데 진행 중인 트랜잭션이 없으면 그 한 번의 발송만 감싼 트랜잭션
(`executeInTransaction`)으로, 있으면 거기 붙여, 트랜잭션 프로듀서가 아니면 그냥 보낸다.
order-service 의 네 발행자(`ProductUpdate`·`CouponRollback`·`CartRollback`·`CartEmpty`)가
이것을 거친다. **가드**: `TransactionalSendsTest` — 대조군(그냥 보내면 같은 예외), 한 번짜리
트랜잭션의 커밋, 바깥 트랜잭션에 붙기, 비트랜잭션 템플릿.

> 이미 실패한 주문은 자동으로 다시 보내지 않는다. 로그의 `orderId` 로 재고를 손으로 맞춘다.

### 🐛 부분 환불에는 알림이 없었다

"환불 완료" 알림은 pay-service 의 결제 응답(CANCELED)에서 나온다. 그런데 결제 취소는 **전액이 다
돌아갔을 때만** 보낸다(부분 환불은 결제가 아직 살아 있다). 품목 일부를 환불하면 돈은 돌아가는데 앱·메일
어디에도 알림이 없었다.

order-service 가 환불 **한 건마다** `order-refunded` 를 보낸다(`OrderRefundedKafkaPublisher`, 커밋 뒤,
비트랜잭션 템플릿). 내용은 가장 최근 환불의 **청구** 금액·통화, `complete`(전액이 다 돌아갔는가), 키
`order-refunded:<orderId>:<n 번째 환불>` — 환불 참조는 제공자에 따라 결제 참조로 대신 채워져 겹칠 수 있어
순번을 쓴다. notification-service 는 **부분 환불만** "부분 환불 완료"(`PAYMENT_REFUNDED`, 금액 포함)로
만든다 — 전액은 결제 응답 쪽이 이미 알린다. 토픽은 ops/02 목록에 더했다(DLT).

**가드**: `OrderRefundedKafkaPublisherTest`(최근 환불·청구 금액·순번 키·기록 없으면 안 보냄),
`OrderRefundedNotificationTest`(부분만 알림·전액 건너뜀·두 번째 부분 환불은 다른 키·금액 표기),
`KafkaPublisherWiringTest`(자기 템플릿).

## 11. 무통장입금 — 결제 API 가 아니라 주문 상태 기계

```
NOT_PROCESSED ──(입금 안내 받기: 고객)──▶ UNPAID ──(입금 확인: 관리자)──▶ PAID
                                            │
                                            └──(기한 경과 · 손님이 접음)──▶ CANCELED
```

물어볼 제공자가 없다. "확인해 줄 상대" 는 은행 내역을 본 **관리자**다. 그래서 새 게이트웨이를
만들되(`BankTransferGateway`, 제공자 `BANK_TRANSFER`, 결제수단 id 는 `cash`) 그 `verify` 는
**부르면 안 되는** 메서드다 — 불렸다는 것은 누군가 무통장입금을 "브라우저가 결제했다고
말하는" 경로로 넣었다는 뜻이라 거부한다.

| 전이 | 누가 | 무엇이 바뀌나 |
|---|---|---|
| 입금 안내 받기 `POST /api/order/payment/bank-transfer/start` | 고객(주문자만) | `UNPAID`, 입금자명, 기한(`BANK_TRANSFER_DEADLINE_HOURS`, 기본 48h). 돈이 안 왔으니 하류에 알릴 것이 없다 |
| 입금 확인 `POST /api/order/admin/deposit-confirmed` | 🔒 관리자만 | 결제 참조(`deposit-<orderId>[-참조]`)를 붙이고 **카드와 같은 아웃박스**로 `COMPLETED` 를 보낸다. 그 뒤는 같다: pay-service → 응답 → 사가가 `PAID` 로 바꾸고 재고 차감·장바구니 비움 |
| 입금 전 취소 `POST /api/order/payment/bank-transfer/cancel` | 고객·관리자 | `CANCELED` + 쿠폰 복원. 재고는 차감된 적이 없다 |
| 기한 만료 (`BankTransferService.expire`, 매시) | 잡 | 위와 같다. **확인이 끝난 주문(결제 참조가 붙은)은 건드리지 않는다** — 사가가 아직 `PAID` 로 못 바꾼 찰나일 수 있다 |

세 가지가 카드와 다르다:

- **`UNPAID` 가 뜻을 얻었다.** 이 상태는 enum 에 있었지만 아무도 넣지 않았다. 이제 "입금
  안내를 받았고 기한 안에 돈이 와야 한다" 다. `NOT_PROCESSED`(결제 절차를 시작하지 않음)와
  다르고, 매출이 아니다(`PAID_STATUSES` 에 없다). 사가의 결제 응답 처리는 이제 두 상태에서
  받는다(`OrderSaga.AWAITING_PAYMENT`). 고객의 "Unpaid Orders" 필터가 이 주문을 보여 준다.
- **입금 확인은 금액을 받지 않는다.** 주문의 청구액이 왔다는 관리자의 확인이다. "얼마가
  왔다" 를 받기 시작하면 그 값을 믿게 된다 — 다르게 왔으면 확인하지 말고 고객에게
  연락하는 것이 맞다. 확인 패널이 청구액을 보여 주고 그렇게 묻는다.
- **환불은 관리자만.** 게이트웨이의 `refund` 는 관리자가 계좌로 송금한 **기록**이라 언제나
  "받아들였다" 고 답한다. 고객이 스스로 부르면 돈이 가지 않았는데 주문만 REFUNDED 가 되므로
  `refundOrder` 가 이 제공자에 한해 고객을 거절한다(화면도 고객센터 안내를 그린다).

계좌는 설정이다(`BANK_TRANSFER_BANK · ACCOUNT · HOLDER`, configmap). 손님에게 보여 주는
값이라 비밀이 아니지만 **비어 있으면 안내를 시작할 수 없다** — 계좌 없는 안내는 입금할
곳이 없다(500 이 아니라 설정 문제라고 말한다). 청구 통화는 `BANK_TRANSFER_CURRENCY`(국내
계좌면 KRW) — 손님이 입금할 금액이 그 통화로 안내되고, `OrderSaga` 가 보내는 아웃박스
금액은 여전히 상점 통화다.

화면: 주문 상세의 결제 자리에 `BankTransfer` 가 그려진다 — 결제 전이면 입금자명 + "입금
안내 받기", 입금 대기면 계좌·금액·기한·입금자명 + "입금 전 주문 취소". 관리자 화면에는
"입금 대기 (무통장)" 필터와 행마다 "입금 확인" 버튼(은행 거래 참조 입력, 선택).

가드: `BankTransferTest`(안내 · 멱등 · 소유권 · 확인은 같은 아웃박스 · 확인 뒤 취소 불가 ·
만료는 미확인만 · 환불은 관리자만 · `cash → BANK_TRANSFER`), `OrderSagaProcessTest`(UNPAID
도 PAID 가 된다), `PaymentGatewaysTest`(제공자 넷), 프론트 `BankTransfer.test.jsx` 5 ·
`Refunds.test.jsx` +1 · `paymentMethods.test.js`.

### 포트원 가상계좌 — "입금 안내" 를 PG 가 만들고 "입금 확인" 을 웹훅이 한다

같은 상태 기계에 두 자리만 바뀐다.

| 전이 | 무통장입금 | 포트원 가상계좌 |
|---|---|---|
| 입금 안내 | 고객이 입금자명을 적고 요청 → 상점 계좌 | 결제창이 계좌를 **발급** → 발급 웹훅(`Transaction.VirtualAccountIssued`) 또는 브라우저 복귀가 `registerVirtualAccount` 로 주문에 붙인다(UNPAID, 기한은 PG 의 기한) |
| 입금 확인 | 관리자 | **포트원 결제 완료 웹훅** → `recordPayment` → 아웃박스 → 사가 → PAID. 관리자는 확인할 수 없다 |
| 입금 전 취소 | 접고 쿠폰 복원 | **PG 의 계좌를 먼저 닫고** 접는다. 닫지 못하면 접지 않는다 |
| 기한 만료 | 잡이 접는다 | 같다(기한 = PG 의 기한, 지나면 PG 도 계좌를 닫는다) |

어느 알림인지는 **본문이 아니라 포트원 조회**가 말한다(`PortOnePaymentService`). 발급 웹훅과
완료 웹훅은 결제 참조만 꺼내고, `verify` 가 완료라 하면 결제를 붙이고, 아니면
`virtualAccountOf` 가 발급된 계좌를 주면 그것을 붙이고, 둘 다 아니면(취소·실패) 무시한다.
본문이 "발급됐다" 고 해도 포트원이 아니라고 하면 아무것도 붙지 않는다 — V1 웹훅에 서명이
없어도 안전한 이유와 같다(§5).

브라우저 복귀(`/payment/portone/confirm`)도 같은 갈래를 탄다. 예전에는 결제가 완료되지 않은
것을 전부 거절했는데, 가상계좌는 발급이 곧 정상 결과다 — 응답의 `orderStatus` 가 `UNPAID`
면 화면이 새로고침해 계좌·금액·기한을 보여 준다(`BankTransfer`, PG 계좌가 상점 계좌 대신).
가상계좌의 금액·통화는 붙일 때 청구액과 대조한다 — 1원짜리 계좌를 붙여 두면 결제 확인에서
어차피 거절되지만 손님에게 틀린 안내를 보여 주지 않기 위해서다.

🔴 **취소된 주문에 결제가 도착하면 `recordPayment` 가 거절하고 error 로 남긴다.** 기한이
지나 우리가 접은 뒤 입금됐거나, 취소된 주문에 뒤늦게 캡처가 붙은 경우다. 조용히 기록하면
사가가 상태를 못 바꿔 "돈은 왔는데 주문은 취소" 가 아무 데도 안 보인다. 수동 환불이 필요한
상황이고, 그래서 가상계좌 취소는 PG 의 계좌를 먼저 닫는다.

프론트: 포트원을 고르면 "카드·간편결제 / 가상계좌" 를 고른다. V1 은 `pay_method: "vbank"`,
V2 는 `payMethod: "VIRTUAL_ACCOUNT"` + `virtualAccount.accountExpiry.validHours`. 발급된 뒤
장바구니는 비우지 않는다 — 입금 전에 비우면 기한이 지나 접힌 주문의 장바구니가 없다.

응답 모양(V2 `method.type=PaymentMethodVirtualAccount` 의 `bank`·`accountNumber`·
`remitteeName`·`expiredAt`, V1 `vbank_num`·`vbank_name`·`vbank_holder`·`vbank_date`)은 실제 API
로 검증하지 못했다(§8). 다르면 `virtualAccountOf` 와 `PortOneV2PaymentGatewayTest` 의 본문을
함께 고친다. V2 의 `bank` 는 은행 **코드**라 화면에 코드가 그대로 보인다 — 이름 표를 붙이는
것은 남은 일이다.

가드: `PortOnePaymentServiceTest`(발급 웹훅 → 계좌 · 완료 웹훅 → 결제 · 본문은 단서 · 복귀의
두 결과 · 남의 주문 거절), `PortOneV2PaymentGatewayTest`(발급 응답 파싱 · PAID 면 발급 아님 ·
발급 웹훅 참조), `BankTransferTest`(UNPAID 전이 · 멱등 · 금액 대조 · 소유권 · 관리자 확인 불가 ·
취소된 주문의 결제 거절), 프론트 `BankTransfer.test.jsx` · `Refunds.test.jsx`.

## 12. 남은 것

- **일본 결제수단** — Stripe 가 コンビニ決済·銀行振込 을 지원한다. 새 연동이 아니라
  Checkout 세션의 `payment_method_types` 를 늘리는 일이다 — 지금은 코드가 `card` 로
  **고정**한다(대시보드 설정이 코드와 어긋나지 않게). 늘릴 때는 `async_payment_failed`
  웹훅 처리도 같이 붙여야 한다.
- **부분 환불의 배송비** — 지금은 마지막 환불에만 실린다. "배송비만 환불" 이나 "부분
  환불에도 배송비 일부" 가 필요하면 `RefundPricing` 규칙 하나를 바꾸면 된다 — 테스트가
  합계 불변을 지킨다.
- `PaypalPaymentRequest` 이름이 제공자 중립이 아니다. 다음 정리 때 바꾼다.
