# 25. 배송 서비스 — 요구사항과 스키마 설계 (제안)

[日本語](25-delivery-service-design.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 제목의 "(제안)" 은 작성 당시입니다. delivery-service 는 구현 · 배포됐고, §10 의 "하지 않음"(분할 배송 · 주소 변경)은 이후 구현됐습니다.

> 결제가 끝나면 주문은 `PAID` 가 되고 **거기서 멈춘다.** 그 뒤가 없다.
> 이 문서는 배송 서비스의 요구사항·도메인 모델·스키마·연동 구조를 제안한다.

## 0. 지금 무엇이 비어 있나

먼저 사실 확인부터. 배송은 "아직 안 만든 기능"이 아니라 **껍데기만 있는 기능**이다.

| 이미 있는 것 | 실제 상태 |
|---|---|
| `OrderStatus.PROCESSING / DISPATCHED / DELIVERED` | **아무도 이 값을 설정하지 않는다.** 사가는 `PAID` 에서 끝난다 |
| `Order.deliveredCreatedAt` | 항상 null |
| `Order.trackingId` | 사가 추적용 UUID 다. **택배 송장번호가 아니다** — 이름이 헷갈리게 돼 있다 |
| `OrderAddress` | 주소는 받아 두지만 배송에 쓰이지 않는다 |
| 프론트 프로필의 `DISPATCHED` 필터 | 조건에 맞는 주문이 생길 수 없어 **영원히 빈 목록** |

`Alarm` 엔티티가 저장되지 않던 것, 채팅이 전달만 되고 저장이 0건이던 것(B-24)과 같은 모양이다.
**타입은 있는데 그 타입을 채우는 코드가 없다.**

> **2026-09 갱신.** 위 표는 이제 과거다. 배송 건이 실제로 생기고(§11.1), `delivery-status`
> 를 order-service 가 소비해 PROCESSING·DISPATCHED·DELIVERED 와 `deliveredCreatedAt` 이
> 채워지며(§11.2), 관리자가 화면에서 송장을 등록한다(§11.3). 실제 택배사 없이 끝까지 돌려
> 보는 방법은 §11.6b. 배포 뒤에 겪은 두 번째 막힘과 그 복구 경로는 §11.5.

## 1. 요구사항

### 1.1 기능 요구사항

| # | 요구사항 | 비고 |
|---|---|---|
| F-1 | 결제 완료 시 배송 건이 자동 생성된다 | 사가의 연장. 주문 상태가 `PAID` → `PROCESSING` 으로 진행 |
| F-2 | 판매자/물류가 **송장번호와 택배사**를 등록한다 | 이 시점에 `DISPATCHED` |
| F-3 | 택배사에서 배송 진행 상황을 **주기적으로 가져온다** | 대부분의 한국 택배사는 웹훅을 주지 않는다 — 폴링이 현실 |
| F-4 | 고객이 배송 상태와 **이력 타임라인**을 조회한다 | 아마존식 진행 바 + 단계별 시각 |
| F-5 | 상태가 바뀌면 **알림**이 나간다 | [notification-service](24-sms-notifications.ko.md) 로 이벤트 발행 |
| F-6 | 배송 완료 시 주문이 `DELIVERED` 로 마감된다 | `deliveredCreatedAt` 이 드디어 채워진다 |
| F-7 | **택배사를 나중에 유연하게 추가**할 수 있다 | §4 — 이 요구사항이 설계를 가장 크게 좌우한다 |
| F-8 | 배송 실패·반송·분실을 표현할 수 있다 | 해피패스만 만들면 실제 운영에서 바로 막힌다 |

### 1.2 비기능 요구사항

| # | 요구사항 | 설계에 미치는 영향 |
|---|---|---|
| N-1 | **외부 API 장애가 주문에 번지지 않을 것** | 별도 서비스로 분리하는 가장 큰 이유. 택배사 API 는 자주 느리고 가끔 죽는다 |
| N-2 | API 호출 쿼터를 아낄 것 | 공공 API 는 일일 호출 한도가 있다. 상태별로 폴링 주기를 달리한다(§5) |
| N-3 | 같은 추적 이벤트를 중복 저장하지 않을 것 | 폴링은 매번 **전체 이력**을 돌려준다. 중복 제거가 필수 |
| N-4 | 레플리카 2개에서 안전할 것 | 폴링 선점에 `FOR UPDATE SKIP LOCKED`([docs/21](21-horizontal-scaling.ko.md)) |
| N-5 | 택배사 원문을 보존할 것 | 매핑이 틀렸을 때 원문이 없으면 디버깅이 불가능하다 |

### 1.3 범위

| 항목 | 상태 |
|---|---|
| **분할 배송** (한 주문 여러 상자) | ✅ 구현 — `Shipment` 단위로 추적. §3 |
| **국제 배송** (통관 단계) | ✅ 구현 — `CUSTOMS_HOLD/CLEARED` + 통관번호 |
| **배송 전 주소 변경** | ✅ 구현 — 이력 보존, 집화 후 차단 |
| **취소 후 재주문** | ✅ 구현 — 발송 전 취소, 새 배송에 `reorderedFromOrderId` 연결 |
| 실시간 기사 위치(지도 위 이동) | ❌ 한국 택배사가 **이 데이터를 제공하지 않는다**. 제공하는 것은 스캔 지점 단위 이력이다 |
| 배송비 실시간 견적 | ❌ 계약 단가가 필요하다 |
| 반품 수거(회수) 접수 | ❌ 별도 도메인. 상태 모델에 자리만 남겨 둔다 |
| 다중 택배사 자동 선택 | ❌ 계약·단가 정보가 선행돼야 한다 |

> **"위치 추적"의 실제 의미.** 요구에 "위치 추적"이 있었는데, 국내 택배 API 가 주는 것은
> GPS 좌표가 아니라 **스캔 이벤트**다: `집화완료(서울강남터미널) → 간선상차 → 간선하차(대전허브)
> → 배달출발(담당 배송기사) → 배달완료`. 지점명은 나오므로 "어느 도시까지 왔는지"는 보여줄 수
> 있지만, 지도 위 실시간 이동은 불가능하다. 프론트를 아마존식 **진행 단계 + 이력 목록**으로
> 설계한 이유가 이것이다 — 아마존도 같은 이유로 그렇게 한다.

## 2. 왜 별도 마이크로서비스인가

order-service 안에 넣지 않는 이유:

1. **장애 격리(N-1).** 택배사 API 는 우리가 통제할 수 없다. 폴링이 느려지거나 실패해도 주문
   생성·결제는 영향을 받으면 안 된다.
2. **다른 리듬으로 돈다.** 주문은 요청 기반, 배송은 **시간 기반 폴링**이다. 스케줄러와 외부
   HTTP 호출이 주문 서비스의 커넥션 풀과 스레드를 잡아먹을 이유가 없다.
3. **다른 확장 축.** 배송 건수는 주문 건수를 따라가지만, 폴링 부하는 **미완료 배송 수**에
   비례한다. 명절처럼 배송이 밀릴 때만 커진다.
4. **외부 자격증명 격리.** 택배사 API 키를 주문 서비스가 들고 있을 이유가 없다.

## 3. 도메인 모델

**분할 배송 때문에 계층이 셋이다.** 추적 정보를 주문에 직접 붙이면 한 주문이 여러 상자로
나뉘는 경우를 표현할 수 없다 — 송장번호도 택배사도 도착일도 상자마다 다르기 때문이다.

```
Delivery (주문 1건의 배송 전체 — 컨테이너)
├── order_id (UNIQUE)         분할해도 주문당 하나
├── 수취인 스냅샷              주소는 여기 하나뿐 (상자가 나뉘어도 목적지는 하나)
├── reordered_from_order_id   취소 후 재주문 연결
├── 파생 상태                  가장 뒤처진 상자를 따른다
└── Shipment[] (상자 = 추적 단위)
     ├── carrier_code + tracking_number   상자마다 다를 수 있다
     ├── status / next_poll_at            상자마다 독립적으로 진행
     ├── international + 통관번호          국제 배송
     ├── ShipmentItem[]                   이 상자에 무엇이 들었나
     └── DeliveryEvent[]                  택배사 스캔 이력
```

**전체 진행도는 가장 뒤처진 상자를 따른다.** 3개 중 2개가 도착해도 주문은 완료가 아니다 —
반대로 하면 아직 안 온 물건이 있는데 "배달완료"가 되어 사용자가 신고할 수 없다.
단, 예외 상태인 상자가 있으면 그것을 우선한다(문제는 묻히면 안 된다).

### 3.1 왜 주소를 **복사**하는가

`OrderAddress` 를 참조하지 않고 배송 시점의 주소를 **스냅샷으로 복사**한다.

사용자가 배송 중에 프로필 주소를 바꿔도 이미 출발한 택배의 목적지는 바뀌지 않는다. 참조로
두면 나중에 "이 물건이 어디로 갔는지" 조회했을 때 **현재 주소**가 나와 사실과 달라진다.
회계·분쟁 자료로서의 가치가 사라진다. 주문 금액을 주문 시점 값으로 박아 두는 것과 같은 이유다.

### 3.2 상태 모델 — 택배사 문자열을 그대로 쓰지 않는다

택배사마다 상태 표현이 다르다. 우체국은 `접수/발송/도착/배달준비/배달완료`, CJ는
`집화처리/간선상차/간선하차/배달출발/배달완료` 식이다. 이걸 그대로 저장하면:

- 화면이 택배사마다 달라진다
- 상태 기반 로직(알림 발송, 주문 마감)을 택배사 수만큼 써야 한다
- 택배사를 추가할 때마다 화면과 로직을 고쳐야 한다 → **F-7 위반**

그래서 **내부 표준 상태**로 매핑하고, 택배사 원문은 이벤트에 함께 보존한다(N-5).

**선언 순서가 곧 진행도다.** `DeliveryStatus` 의 ordinal 로 "앞으로 갔는지"를 판정하므로
(`isProgressFrom`), 값을 중간에 끼워 넣을 때는 순서를 지켜야 한다. 아래 표는 실제 선언 순서다.

| 내부 상태 | 의미 | 폴링 주기 | 주문 상태 연동 |
|---|---|---|---|
| `PENDING` | 배송 생성됨, 송장 미등록 | (폴링 안 함) | `PROCESSING` |
| `INFO_RECEIVED` | 택배사가 접수 정보만 받음 (물건은 아직 없음) | 6h | `DISPATCHED` (송장 등록 = 발송 처리, F-2) |
| `PICKED_UP` | 집화 완료 — 실물이 택배사 손에 | 3h | `DISPATCHED` |
| `IN_TRANSIT` | 간선 이동 중 | 3h | `DISPATCHED` |
| `CUSTOMS_HOLD` | 통관 대기·보류 — 서류나 관세가 걸려 있다 | 6h | `DISPATCHED` |
| `CUSTOMS_CLEARED` | 통관 완료 — 국내 배송망으로 넘어간다 | 3h | `DISPATCHED` |
| `OUT_FOR_DELIVERY` | 배달 출발 — **오늘 도착한다** | 30m | `DISPATCHED` |
| `ATTEMPT_FAILED` | 배달 시도 실패(부재 등) — 재시도된다 | 3h | `DISPATCHED` |
| `DELIVERED` | 배달 완료 (종료) | (중단) | `DELIVERED` |
| `EXCEPTION` | 파손·분실·주소불명 등 사람이 봐야 하는 상태 | 12h | (유지) |
| `RETURNED` | 반송 완료 (종료) | (중단) | (유지) |
| `CANCELLED` | 발송 전 취소 (종료) | (중단) | `PAID` 로 되돌림 — 물건이 나가지 않았으니 환불이 다시 열린다(§11.9). 이미 환불·취소된 주문이면 그대로 |

`OUT_FOR_DELIVERY` 를 별도 상태로 둔 것은 **알림 가치가 가장 높은 순간**이기 때문이다.
아마존이 "Out for delivery" 를 강조하는 이유와 같다.

통관 두 상태를 나눈 이유는 **사용자에게 할 말이 다르기** 때문이다. `CUSTOMS_HOLD` 는
사용자가 행동해야 할 수도 있는 상태(관세 납부, 개인통관고유부호 제출)라 화면에서 경고로
띄우고, `CUSTOMS_CLEARED` 는 그냥 진행 중이다. 하나로 묶으면 이 구분이 사라진다.

## 4. 택배사 연동 — 확장 가능한 구조 (F-7)

요구사항의 핵심이다. **택배사를 추가할 때 도메인 코드가 바뀌면 안 된다.**

```
                      ┌─────────────────────────┐
  DeliveryTracker ───▶│   CarrierAdapter (I/F)  │
  (폴링 스케줄러)      └───────────┬─────────────┘
                                  │ 구현체
              ┌───────────────────┼────────────────────┐
              ▼                   ▼                    ▼
      KoreaPostAdapter      SimulatedAdapter    (앞으로 추가)
      (우체국 공공 API)      (개발·테스트용)      CJ / 한진 / 롯데 …
```

**어댑터의 책임은 하나뿐이다**: `추적번호 → 표준 이벤트 목록`.
상태 판정·중복 제거·주문 연동·알림은 전부 도메인 쪽에 있다. 그래서 새 택배사는 클래스 하나만
추가하면 되고, 그 클래스는 **HTTP 호출과 응답 매핑만** 안다.

### 4.1 첫 연동으로 우체국을 고른 이유

- **공개된 공공 API 가 있다** (공공데이터포털). 사설 택배사 API 는 대부분 계약이 선행된다.
- 스펙 문서가 공개돼 있어 어댑터를 검증할 수 있다.
- 국제우편(EMS)까지 같은 계열이라 나중에 해외 배송으로 넓히기 쉽다.

⚠️ **정직하게**: 이 어댑터는 API 키 없이 실제 호출을 검증할 수 없다. 그래서
**응답 필드 매핑을 설정으로 빼고**, 매핑 로직만 기록된 샘플 응답으로 테스트한다.
실제 키를 받으면 필드 이름만 맞추면 되고, 그때 `ops/` 스크립트로 실호출을 확인한다.

### 4.2 새 택배사를 추가하는 절차

1. `CarrierAdapter` 를 구현한 클래스 하나를 만든다 (HTTP 호출 + 응답 → 표준 이벤트).
2. `carrier` 마스터 테이블에 행을 넣는다 (코드·이름·추적 URL 템플릿).
3. 끝. 스케줄러·상태 판정·알림·화면은 손대지 않는다.

## 5. 폴링 전략 — 쿼터를 태우지 않기 (N-2)

전 건을 같은 주기로 조회하면 공공 API 일일 한도를 금방 넘긴다. **상태에 따라 주기를 바꾼다.**

| 상태 | 폴링 주기 | 근거 |
|---|---|---|
| `PENDING` | 조회 안 함 | 송장번호가 없으면 조회할 대상이 없다 |
| `INFO_RECEIVED` | 6시간 | 실물이 아직 안 움직인다 |
| `PICKED_UP` · `IN_TRANSIT` | 3시간 | 하루 몇 번 스캔된다 |
| `OUT_FOR_DELIVERY` | 30분 | 오늘 도착한다 — 가장 자주 |
| `DELIVERED` · `RETURNED` | **중단** | 끝난 배송을 계속 조회하는 것이 쿼터 낭비의 주범이다 |
| `EXCEPTION` | 12시간 | 사람 개입을 기다리는 중 |

여기에 **연속 실패 백오프**를 더한다. 택배사 API 가 죽었을 때 같은 주기로 계속 때리면
복구를 방해하고 우리 쿼터만 태운다.

## 6. 스키마

```sql
-- 택배사 마스터. 코드 추가만으로 새 택배사를 인식하게 하는 것이 목적이다.
CREATE TABLE delivery.carrier (
    carrier_code      varchar(30)  PRIMARY KEY,   -- 'KOREA_POST', 'CJ', ...
    name_ko           varchar(60)  NOT NULL,
    name_en           varchar(60),
    tracking_url      varchar(300),               -- 고객이 직접 볼 수 있는 조회 페이지
    active            boolean      NOT NULL DEFAULT true,
    supports_tracking boolean      NOT NULL DEFAULT true
);

-- 주문 1건의 배송 전체. **추적 정보는 여기 없다** — 상자마다 다르기 때문이다(§3).
-- 여기 남는 것은 상자가 몇 개로 쪼개지든 하나뿐인 것들: 주문, 고객, 목적지.
CREATE TABLE delivery.delivery (
    delivery_id       bigserial    PRIMARY KEY,
    -- 분할 배송을 해도 주문당 배송 컨테이너는 하나다. 이 UNIQUE 는 유지된다.
    order_id          bigint       NOT NULL UNIQUE,
    customer_id       bigint       NOT NULL,

    -- 취소 후 재주문 연결. 새 주문이 어떤 취소 건에서 나왔는지 남긴다.
    reordered_from_order_id bigint,

    -- 수취인 스냅샷 (§3.1 — 참조가 아니라 복사). 상자가 나뉘어도 목적지는 하나다.
    recipient_name    varchar(80)  NOT NULL,
    recipient_phone   varchar(20),
    postal_code       varchar(12),
    address_line1     varchar(200) NOT NULL,
    address_line2     varchar(200),
    city              varchar(80),
    state             varchar(80),
    country           varchar(2)   NOT NULL DEFAULT 'KR',
    delivery_memo     varchar(200),                -- "부재 시 경비실"

    created_at        timestamp    NOT NULL,
    updated_at        timestamp    NOT NULL,
    version           bigint
);

CREATE INDEX idx_delivery_customer ON delivery.delivery (customer_id, created_at);
CREATE INDEX idx_delivery_order    ON delivery.delivery (order_id);

-- 상자 = **추적 단위**. 송장번호·택배사·상태·도착예정일이 전부 여기 있다.
CREATE TABLE delivery.shipment (
    shipment_id       bigserial    PRIMARY KEY,
    delivery_id       bigint       NOT NULL REFERENCES delivery.delivery(delivery_id),
    sequence_no       int          NOT NULL,       -- 화면의 "패키지 1/3"

    carrier_code      varchar(30),                 -- 상자마다 다를 수 있다
    tracking_number   varchar(60),
    status            varchar(24)  NOT NULL,
    -- 택배사가 알려준 예상 도착일. 없을 수도 있다.
    estimated_at      timestamp,
    dispatched_at     timestamp,
    delivered_at      timestamp,

    -- 국제 배송. 통관 단계가 붙고 폴링 주기도 달라진다.
    international            boolean NOT NULL DEFAULT false,
    customs_declaration_no   varchar(40),
    customs_clearance_no     varchar(40),

    -- 폴링 제어 (§5) — 상자마다 독립적으로 진행하므로 상자에 붙는다.
    next_poll_at      timestamp,
    last_synced_at    timestamp,
    poll_failures     int          NOT NULL DEFAULT 0,

    created_at        timestamp    NOT NULL,
    updated_at        timestamp    NOT NULL,
    version           bigint,

    -- 같은 택배사 안에서 송장번호는 유일하다. 잘못 입력해 남의 배송을 덮어쓰는 것을 막는다.
    CONSTRAINT uk_shipment_tracking UNIQUE (carrier_code, tracking_number)
);

-- 폴링 대상 조회용. 이게 없으면 상자가 쌓일수록 스케줄러가 전체 스캔을 한다.
CREATE INDEX idx_shipment_poll     ON delivery.shipment (next_poll_at);
CREATE INDEX idx_shipment_delivery ON delivery.shipment (delivery_id);

-- 이 상자에 무엇이 들었나. 분할 배송에서 "어느 상자에 뭐가 왔는지"를 답한다.
CREATE TABLE delivery.shipment_item (
    shipment_item_id bigserial   PRIMARY KEY,
    shipment_id      bigint      NOT NULL REFERENCES delivery.shipment(shipment_id),
    order_product_id bigint,
    product_id       bigint,
    product_name     varchar(300),
    image_url        varchar(500),
    quantity         int         NOT NULL
);

CREATE INDEX idx_shipment_item_shipment ON delivery.shipment_item (shipment_id);

-- 발송 전 주소 변경 이력. **변경 자체보다 이력이 중요하다** — 물건이 엉뚱한 곳으로 갔을 때
-- 누가 언제 어디로 바꿨는지가 없으면 책임을 가릴 수 없다.
CREATE TABLE delivery.address_change (
    address_change_id bigserial    PRIMARY KEY,
    delivery_id       bigint       NOT NULL REFERENCES delivery.delivery(delivery_id),
    previous_address  varchar(600) NOT NULL,       -- 사람이 읽는 한 줄 스냅샷
    new_address       varchar(600) NOT NULL,
    changed_by        bigint       NOT NULL,
    reason            varchar(200),
    changed_at        timestamp    NOT NULL
);

CREATE INDEX idx_address_change_delivery ON delivery.address_change (delivery_id, changed_at);

-- 택배사 스캔 이력. **상자에 붙는다** — 분할 배송이면 상자마다 별개의 타임라인이다.
CREATE TABLE delivery.delivery_event (
    event_id        bigserial    PRIMARY KEY,
    shipment_id     bigint       NOT NULL REFERENCES delivery.shipment(shipment_id),
    status          varchar(24)  NOT NULL,       -- 표준 상태
    occurred_at     timestamp    NOT NULL,       -- 택배사가 알려준 스캔 시각
    location        varchar(120),                -- '대전허브', '서울강남'
    description     varchar(300),                -- 화면에 보여줄 문구

    -- ⚠️ 택배사 원문을 그대로 보존한다(N-5). 매핑이 틀렸을 때 이게 없으면
    --    "왜 이 상태로 판정됐는지"를 재구성할 수 없다.
    raw_status      varchar(120),
    raw_payload     jsonb,

    -- 중복 제거 키(N-3). 폴링은 매번 전체 이력을 돌려주므로 이게 없으면
    -- 같은 이벤트가 조회 횟수만큼 쌓인다.
    dedup_key       varchar(120) NOT NULL,
    created_at      timestamp    NOT NULL
);

CREATE INDEX idx_delivery_event_timeline ON delivery.delivery_event (shipment_id, occurred_at);
```

> **위 SQL 은 논리 스키마다.** 실제로 배포되는 DDL 은
> `delivery-service/src/main/resources/db/migration/V2__baseline_schema.sql` 이고, 그건 손으로
> 쓴 것이 아니라 Hibernate 가 엔티티에서 만든 DDL 을 `ops/tools/hbm2flyway.py` 로 멱등 변환한
> 것이다(docs/20). 둘이 어긋나면 **V2 가 맞다** — 그쪽은 `ddl-auto: validate` 가 매 기동마다
> 엔티티와 대조하므로 거짓말을 할 수 없다.
>
> 두 군데는 의도적으로 다르다:
> - `shipment.carrier_code` 에 `carrier` 로의 FK 가 없다. 택배사 마스터는 시드 데이터이고,
>   FK 를 걸면 아직 마스터에 없는 택배사 송장이 들어올 때 배송 등록 자체가 실패한다.
>   추적을 못 하는 것보다 배송을 기록조차 못 하는 쪽이 나쁘다.
> - `delivery_event` 의 `(shipment_id, dedup_key)` UNIQUE 는 논리적으로는 맞지만 걸지 않았다.
>   중복 제거는 저장 전에 `findDedupKeys` 로 거른다(§6.1). 제약으로도 막고 싶으면 별도
>   마이그레이션으로 추가하면 되고, 그때는 폴링이 `DuplicateKeyException` 을 삼켜야 한다.

### 6.1 `dedup_key` 를 어떻게 만드나

택배사는 이벤트에 안정적인 ID 를 주지 않는 경우가 많다. 그래서
`(스캔시각 + 원문상태 + 지점명)` 의 해시를 쓴다. 같은 스캔이 반복 조회돼도 한 번만 저장된다.

> 완벽하지는 않다 — 같은 지점에서 같은 시각에 두 번 스캔되면 하나로 합쳐진다. 하지만 그
> 손실보다 **중복 이벤트가 타임라인을 뒤덮는 쪽이 훨씬 나쁘다.** 실용적 절충이다.

## 7. 이벤트 흐름

```
결제 완료 (payment 토픽)
   └─▶ delivery-service: Delivery 생성 (PENDING)
        └─▶ order-service: PAID → PROCESSING

송장 등록 (판매자 API)
   └─▶ status = INFO_RECEIVED, next_poll_at 설정
        └─▶ delivery-status 토픽 ─▶ notification-service (앱 알림)

폴링 (3시간마다)
   └─▶ CarrierAdapter → 표준 이벤트 목록
        └─▶ 새 이벤트만 저장 → 상태 전이 판정
             └─▶ 상태가 바뀌었을 때만 delivery-status 발행
                  ├─▶ notification-service: OUT_FOR_DELIVERY / DELIVERED 는 SMS
                  └─▶ order-service: DISPATCHED / DELIVERED 반영
```

**상태가 바뀔 때만 이벤트를 발행하는 것**이 중요하다. 폴링마다 발행하면 3시간마다 알림이 간다.

## 8. 프론트엔드 — 아마존식

아마존 주문 상세의 배송 영역을 그대로 참고한다.

```
┌──────────────────────────────────────────────────┐
│  화요일 도착 예정                                  │
│  ●━━━━━━━●━━━━━━━●───────○                       │
│  주문완료   발송     배송중   배달완료               │
│                                                   │
│  ▸ 배송 상세 보기                                  │
│    12/03 14:20  대전허브   간선하차                 │
│    12/03 09:15  서울강남   집화완료                 │
│                                                   │
│  [ 택배사 조회 페이지에서 보기 ]                     │
└──────────────────────────────────────────────────┘
```

설계 포인트:
- **4단계 진행 바.** 9개 내부 상태를 그대로 보여주면 사용자는 이해하지 못한다. 4단계로 접고,
  자세한 이력은 접힌 영역에 둔다 — 아마존이 하는 방식이다.
- **예상 도착일을 맨 위에 크게.** 사용자가 가장 알고 싶은 것 하나다.
- **`OUT_FOR_DELIVERY` 는 강조.** "오늘 도착합니다"가 가장 가치 있는 정보다.
- **`EXCEPTION` 은 진행 바를 멈추고 안내를 띄운다.** 진행 바만 있으면 문제 상황이 묻힌다.
- 택배사 조회 페이지 링크를 제공한다 — 우리가 못 보여주는 정보를 사용자가 직접 볼 수 있게.

## 9. 구현 순서 제안

| 단계 | 내용 | 비고 |
|---|---|---|
| 1 | delivery-service 골격 + 스키마 + 결제 이벤트 구독 | 배송 건이 생기기 시작 |
| 2 | `CarrierAdapter` + Simulated 어댑터 | 실 API 키 없이 전 경로 검증 |
| 3 | 폴링 스케줄러 + 상태 전이 + 이벤트 발행 | 핵심 |
| 4 | 조회 API + 프론트 타임라인 | 사용자에게 보이는 부분 |
| 5 | KoreaPost 어댑터 | 키를 받으면 필드 매핑만 맞추면 된다 |
| 6 | order-service 연동 (`DISPATCHED`/`DELIVERED` 반영) | 껍데기로 남아 있던 상태들이 드디어 채워진다 |

## 10. 열어 둔 질문

| 질문 | 지금의 가정 |
|---|---|
| 분할 배송(한 주문 여러 상자)을 지원할 것인가 | **하지 않는다.** `order_id` UNIQUE. 필요해지면 shipment 를 넣는다 |
| 송장번호는 누가 등록하나 | 판매자/관리자 API. 물류 시스템 연동은 나중 |
| 국제 배송 | 스키마에 `country` 는 있지만 어댑터는 국내만 |
| 배송 전 주소 변경 | 미지원. 취소 후 재주문 |

## 11. 구현 — 끊겨 있던 두 마디를 잇다 (2026-09)

§9 의 6단계 중 1~5 는 돼 있었는데 **실제로는 배송이 한 건도 생기지 않았고**, 6단계는
아예 없었다. 둘 다 "코드는 있는데 연결이 없는" 종류의 결함이라 기능 테스트로는 안 보였다.

### 11.1 🐛 배송이 한 건도 만들어지지 않았다

`PaymentCompletedListener` 는 `payment` 토픽의 이벤트 안에서 `shippingAddress` 를 찾아
배송을 만들도록 돼 있었다. 그런데 그 이벤트(`PaymentResponse.avsc`)에는 **주소도 품목도
없다** — `paymentId, orderId, customerId, couponName, amounts, paymentStatus` 뿐이다.
주소를 못 찾으면 예외를 던지도록 돼 있었으니 **모든 결제 완료가 `payment.DLT` 로 갔다.**
주문 상세의 `GET /api/deliveries/order/{id}` 404 는 "아직" 이 아니라 "영원히" 였다.

고친 방식 — **주문의 주인에게 묻는다.**

```
payment 토픽 (COMPLETED)
   └─▶ delivery-service: 이벤트에 주소가 있으면 그것을, 없으면
        GET order-service /api/order/internal/orders/{orderId}/shipping   (Feign, 내부 호출)
        └─▶ CreateDeliveryCommand → Delivery(PENDING) → delivery-status 발행
```

- order-service `OrderShippingInfoService` + `OrderController.shippingInfo` — 수취인(이름
  둘을 합침)·주소·품목만 싣는다. **금액은 없다.** 보호 규칙은 구매 집계와 같다
  (`InternalCallGuard.allowed` — 게이트웨이 신원 헤더가 있으면 관리자만).
- delivery-service `client/OrderServiceClient` — user-service 의 등급 배치와 같은 모양.
  **요청 헤더 전달 인터셉터가 없다.** 리스너 스레드라 전달할 요청이 없고, 신원 헤더를
  실으면 "외부 요청" 이 되어 403 이다. 사설 CA 는 JVM 기본 트러스트스토어
  (`JAVA_TOOL_OPTIONS`)로 신뢰한다.
- 결제가 `CANCELED`(환불·취소)로 오면 `DeliveryService.cancelByOrder` — 발송 전이면 접고
  `CANCELLED` 를 발행한다. 집화된 뒤면 손대지 않는다(반품 절차).
- 공통 DTO `common/dto/order/OrderShippingDto`.

### 11.2 order-service 가 `delivery-status` 를 듣는다

`DeliveryStatusPublisher` 는 처음부터 "order-service 가 DISPATCHED/DELIVERED 를 반영한다"
고 적고 발행하고 있었는데 듣는 쪽이 없었다. `DeliveryStatusKafkaListener`(그룹
`order-delivery-status`, JSON 문자열, `delivery-status.DLT`) → `OrderDeliveryStatusService`:

| 배송 상태 | 주문 상태 |
|---|---|
| `PENDING` (배송 건 생성) | `PROCESSING` |
| `INFO_RECEIVED` … `ATTEMPT_FAILED`, `EXCEPTION` (송장 등록 이후 전부) | `DISPATCHED` |
| `DELIVERED` | `DELIVERED` + `deliveredCreatedAt` |
| `CANCELLED` | **`PAID` 로 돌아간다**(PROCESSING·DISPATCHED 였다면). 발송 전 취소라 물건은 나가지 않았다 — 환불이 다시 열린다. §11.9 |
| `RETURNED` | 손대지 않는다 — 반품 입고가 처리한다 |
| 모르는 상태 | 함께 오는 `stage` 로 판정 |

두 규칙이 전부다. **앞으로만 간다**(PAID → PROCESSING → DISPATCHED → DELIVERED, 재전달·
순서 뒤집힘에 안전) 그리고 **결제된 주문만 움직인다** — `REFUNDED`·`CANCELED`·`COMPLETED`
는 돈의 상태가 우선이라 배송 이벤트가 늦게 와도 덮어쓰지 않는다. 바뀌면
`OrderCacheEvictor` 로 캐시를 비운다(docs/49 §10 의 사가와 같은 이유).

### 11.3 관리자 송장 등록

- `GET /api/deliveries/admin?awaiting=true|false&size=` — 관리자만(`User-Role`). 기본은
  송장을 기다리는 배송(상자 중 하나라도 송장 없이 `PENDING`), 오래된 것부터.
- 프론트 `/admin/delivery`(사이드바 Orders → Deliveries): 택배사 선택(`/carriers`) + 송장번호
  → 기존 `POST /order/{orderId}/tracking`. 등록 뒤 목록을 서버에서 다시 읽는다.

### 11.4 실제 택배사 없이 끝까지 돌려 보기

시뮬레이터 스위치는 이제 **configmap 키**다(`DELIVERY_SIMULATED_ENABLED`,
`DELIVERY_SIMULATED_STEP_MS`, `DELIVERY_POLL_INTERVAL_MS`). 예전에는 `48-delivery-service.yaml`
의 `env` 에 `"false"` 로 박혀 있어 envFrom 보다 우선했고, 오버레이 패치로는 켤 수 없었다.

```yaml
# k8s-overlays/local/patch-local-endpoints.yaml  (gitignore) 의 data:
DELIVERY_SIMULATED_ENABLED: "true"
DELIVERY_SIMULATED_STEP_MS: "60000"    # 1분마다 한 단계
DELIVERY_POLL_INTERVAL_MS: "20000"
```

반영은 **ops/28 로** 한다. `kubectl apply -k k8s-overlays/local` 을 직접 치면 그 오버레이의
`newTag` 가 전 서비스를 옛 버전으로 되돌린다(docs/33 §오버레이 직접 적용 금지):

```bash
./ops/28-deploy.sh local --config-only --restart delivery-service
```

### 11.5 🐛 두 번째 막힘 — 나라 이름 한 칸이 결제를 DLT 로 보냈다

§11.1 을 고치고 배포했는데 배송은 여전히 생기지 않았다. 로그에 이것만 있었다:

```
ERROR: value too long for type character varying(2)
```

**주소 폼은 나라를 이름으로 저장한다.** `SingularSelect` 가 `option.name` 을 값으로 쓰므로
`OrderAddress.country` 에는 `"Korea, Republic of"` 가 들어간다. 반대로 `delivery.country` 는
ISO 코드라 `varchar(2)` 다. 이름이 같은 두 값이 사실은 다른 것이었다 — 하나는 **사람이 읽는
표기**, 하나는 **코드**.

그리고 그 예외는 배송 하나가 아니라 **결제 레코드**를 재시도 끝에 DLT 로 보냈다. 주문은
PAID 인데 배송은 영영 안 생기고, 화면에는 "배송 준비 중" 만 남았다. §11.1 과 증상이 똑같아서
같은 버그가 안 고쳐진 것처럼 보였다.

| 고친 것 | 내용 |
|---|---|
| `common/i18n/CountryCodes` | 이름·alpha-2·alpha-3·한글·일본어 → alpha-2. JDK 의 CLDR 표기(`South Korea`)와 폼의 ISO 공식 표기(`Korea, Republic of`)가 달라서, **폼 목록 245개를 전부 돌려** 안 풀리는 것만 별칭으로 넣었다. `CountryCodesTest` 가 그 목록을 파일에서 읽어 전수 대조한다 |
| `Delivery` 생성자 | 나라를 코드로 바꾼다. 못 알아보면 발송국으로 본다 — **예외를 던지지 않는다** |
| `Delivery.fit()` | 모든 문자열 칸을 컬럼 폭에 맞춘다. 폼은 우편번호를 30자까지 받는데 컬럼은 12자였다 — **같은 종류가 하나 더 남아 있었다** |
| 리스너 로그 | 어느 갈래로 가든 한 줄 남긴다. 예전에는 "완료가 아닌 결제" 를 조용히 건너뛰어 로그에 아무것도 없었고, 그래서 "이벤트를 못 받았나, 받고 건너뛰었나" 를 구분할 수 없었다 |
| `POST /api/deliveries/admin/order/{orderId}` | 이미 DLT 로 간 주문의 복구. 버그를 고쳐도 그 레코드는 다시 오지 않는다 |

**왜 자르고 넘어가나.** 이 값들은 결제 이벤트를 처리하는 도중에 저장된다. 한 칸이 길어서
예외가 나면 그 레코드는 DLT 로 가고 배송은 영영 만들어지지 않는데, 화면에는 아무 단서도
없다. 잘린 주소는 관리자 화면에 보이고 발송 전에 고칠 수 있다 — **보이는 잘못이 보이지 않는
누락보다 낫다.**

> 남은 어긋남: `OrderAddress.country` 는 여전히 이름을 저장한다. 폼이 코드를 보내게 바꾸면
> 근본이 정리되지만, 이미 저장된 주소들이 이름이라 서버 쪽 변환은 어차피 필요하다.

### 11.6 🐛 세 번째 막힘 — 18자리 주문 번호가 브라우저에서 바뀌었다

배송은 만들어졌는데 송장 등록이 언제나 이랬다:

```json
{"registered":false,"message":"주문 또는 택배사를 찾을 수 없습니다"}
```

송장번호는 아무 값이나 받으므로 원인이 아니었다. 주문 번호가 **TSID 라 18자리**인 것이
원인이다. 자바에서는 `long` 이라 멀쩡하지만 JSON **숫자**로 내려보내면 브라우저의
`JSON.parse` 가 배정밀도 실수로 읽는다. 안전 정수 한계는 `2^53-1`(약 9.0e15)인데 TSID 는
8.9e17 이라 두 자리 크다:

```
889196209423729897  →  889196209423729920
```

자릿수가 같아서 화면에는 맞게 보인다. 그 번호로 요청하면 서버는 그런 주문을 모른다.

`OrderDto.id`·`OrderedProductDto.id` 가 이미 `String` 인 이유가 이것인데, 새로 만든 배송
DTO 들이 그 관례를 따르지 않았다. 같은 누락이 관리자 주문 화면에도 있었다 —
`AdminOrderSummaryDto.orderId` 는 화면에 틀린 번호를 보여줬고(그 번호를 §11.5 의 "배송이 없는
주문 잇기" 에 옮겨 적으면 실패한다), `AdminOrderItemDto.orderProductId` 는 **부분 환불이
품목을 못 찾는** 잠복 버그였다.

전부 `String` 으로 바꿨다. `DeliveryResponseIdTest` 가 실제 TSID 로 JSON 을 만들어
`"orderId":"889196209423729897"` 인지, 그리고 그 값이 `double` 로 표현되지 않는다는 전제까지
함께 고정한다.

### 11.8 🐛 화면의 시각이 9시간 어긋나 있었다

서버는 시각을 `LocalDateTime` 으로 저장하고 **시간대 표시 없이** 내려보낸다:

```
"2026-09-20T00:27:39.97"
```

값은 UTC 다(서비스들이 `LocalDateTime.now(ZoneOffset.UTC)` 로 만들고 컨테이너 기본
시간대도 UTC 다). 그런데 자바스크립트는 **시간대가 없는 날짜-시각 문자열을 현지 시각으로
읽는다** — 명세가 그렇다. 서울에서 보면 모든 시각이 9시간 이르게 찍혔다.

규칙 자체는 `Z` 한 글자를 붙이면 되는 것인데, 그 한 줄이 화면마다 복제돼 있었다.
**스무 곳 중 다섯 곳만 붙이고 있었고 나머지 열다섯 곳이 전부 틀렸다** — 주문 시각,
배송 생성 시각, 무통장입금 기한, 공지 게시일, 이벤트 기간까지.

`frontend/src/util/datetime.js` 한곳으로 모으고 전부 그것을 쓰게 했다. `datetime.test.js`
가 `util/datetime.js` 밖에서의 `new Date(값)` 을 금지한다 — `envRefs.test.js` 가
`process.env` 직접 참조를 막는 것과 같은 방식이다. 새 화면이 또 복제하면 빌드가 먼저 막힌다.

> **날짜만 있는 값은 다르게 읽는다.** `"2026-09-20"` 에 `Z` 를 붙이면 자정 UTC 가 되고,
> 시간대에 따라 달력 날짜가 하루 밀린다. 날짜만 있는 값은 현지 자정으로 읽는다.

> ⚠️ 이 파일들은 **테스트가 덮지 않는 화면이 많았다.** 고치는 중에 import 를 잘못 넣어
> 세 파일이 깨졌는데 젠킨스 격인 `npx jest` 는 전부 통과했다 — 그 화면을 부르는 테스트가
> 없었기 때문이다. 운영 빌드(`npx webpack --config webpack.config.prod.js`)를 돌려야 잡힌다.

### 11.9 🐛 배포 뒤 시험에서 드러난 세 가지

**① 송장을 등록했는데 진행 바가 "주문 완료" 에 서 있었다.** 주문은 DISPATCHED 가 되고
"발송되었습니다" 알림까지 나갔는데, 배송 추적의 진행 바는 그대로였다. `INFO_RECEIVED` 의
단계가 `ORDERED` 였기 때문이다. 송장 등록이 곧 발송 처리라는 것이 이 시스템의 정의(F-2)이므로
`SHIPPED` 로 맞췄다. 이제 `ORDERED` 단계는 `PENDING` 뿐이다.

**② 배송을 취소해도 주문은 DISPATCHED 그대로였다.** 그래서 "배송이 시작된 주문은 여기서
환불할 수 없습니다" 에 갇혔다 — 손님이 배송을 취소한 이유가 환불인데 그 길이 막혔다.
`OrderDeliveryStatusService` 가 `CANCELLED` 를 받으면 주문을 `PAID` 로 되돌린다. "앞으로만
간다" 의 유일한 예외이며, 배송 도메인이 발송 뒤 취소를 막으므로 물건이 나간 주문이 되돌아갈
일은 없다. 화면은 취소가 받아들여지면(`DeliveryTracker.onCancelled`) 주문이 DISPATCHED 를
벗어날 때까지 다시 읽는다 — 결제 확정을 기다리던 `useOrderSettling` 에 `until` 조건을 준 것.

**③ 목포 주소의 배송이 `KP` 로 찍혔다.** 변환기(§11.5)는 맞았다. 저장된 주소의 나라가
"Korea, Democratic People'S Republic of" 였다 — 목록에서 "Korea, Republic of" **바로 위**라
잘못 고르기 쉽다. 그러면 배송이 국제 배송으로 만들어져 통관 단계가 끼고, 시뮬레이터도
통관 각본을 탄다. 주소 폼의 나라 기본값을 발송국("Korea, Republic of")으로 두었고, 변환기에
사람이 손으로 쓰는 표기("Republic of Korea", "Korea, South" 등)를 더했다. 두 한국이 섞이지
않는 것은 `CountryCodesTest` 가 고정한다. **이미 저장된 주소는 손님이 직접 고쳐야 한다.**

### 11.10 🐛 시뮬레이터로는 배송 완료를 볼 수 없었다 — 폴링 주기

상태별 기본 조회 주기는 실제 택배사의 리듬이다(접수 뒤 6시간, 이동 중 3시간, 배달 출발
30분). 시뮬레이터는 1분마다 단계를 밟는데 폴러는 접수 뒤 **6시간** 뒤에나 다시 물었고,
그때 이력 전체를 받아 **곧장 배달 완료로 뛰었다.** 집화·이동·배달 출발과 그 알림은 전부
사라졌다. `DELIVERY_SIMULATED_STEP_MS` 를 아무리 줄여도 소용없었다.

택배사가 자기 갱신 주기를 안다 — `CarrierAdapter.pollIntervalCap()`. 시뮬레이터는 단계 간격을
돌려주고, 상자는 기본 주기와 그 값 중 **짧은 쪽**으로 다음 조회를 잡는다. 늘리는 데는 쓰지
않으므로 실제 택배사(우체국은 비어 있음)는 예전 그대로다. `TrackingPollerSimulatedTest` 가
접수 → 집화 → 이동 → 배달 출발 → 배달 완료를 한 단계씩 밟는 것을 고정한다.

### 11.6b 시뮬레이터로 끝까지 돌려 보기

절차: 결제 → 주문 상세에 "배송 준비 중"(PROCESSING) → `/admin/delivery` 에서 택배사
**테스트배송**(코드 `SIMULATED`), 송장 `SIM-1` 등록 → 주문 DISPATCHED, "상품이 발송되었습니다"
알림 → 폴러가 `STEP_MS` 마다 한 단계씩: 집화 → 이동 → "오늘 배송될 예정입니다" 알림 →
**배달 완료** + "배송이 완료되었습니다" 알림, 주문 DELIVERED, `deliveredCreatedAt` 채워짐.

`STEP_MS` 가 1분이면 송장 등록 뒤 **4분쯤** 배달 완료다(단계 5개, 간격 4개). 폴러의
`POLL_INTERVAL_MS` 는 `STEP_MS` 보다 짧아야 한 단계도 안 놓친다 — 위 예시(20초)면 된다.
주문 상세는 20초마다 배송을 다시 읽으므로 새로고침하지 않아도 진행이 보인다.

| 송장번호 | 각본 | 배달 완료까지(1분 단계) |
|---|---|---|
| `SIM-1` | 접수 → 집화 → 이동 → 배달 출발 → 완료 | 4분 |
| `SIMFAIL-1` | 배달 출발 → **배달 실패**(부재) → 다시 출발 → 완료 | 6분 |
| `SIMINTL-1` | 이동 → **통관 보류**(알림) → 통관 완료 → 배달 출발 → 완료 | 6분 |
| `SIMEXC-1` | 이동 → **사고**(알림)에서 멈춤 — 완료되지 않는다 | — |
| `SIMNONE-1` | 택배사가 송장을 모른다 — 실패 카운터가 올라간다 | — |

> ⚠️ **송장을 등록해도 "발송되었습니다" 알림은 나가지 않는다.** 아래 §11.7 참조 —
> 알림은 여섯 상태에서만 나간다.

### 11.7 어느 배송 상태에 알림이 나가나

두 관문을 지나야 알림이 만들어진다.

1. **발행자 쪽** — `DeliveryStatus.isWorthNotifying()` 이 참인 상태만 `worthNotifying: true`
   로 실린다. 거짓이면 notification-service 가 그 이벤트를 버린다(앱 알림도 안 남는다).
2. **종류 쪽** — `NotificationType.SHIPPING_UPDATED` 는 `smsWorthy = false` 다.
   **배송 알림은 문자로 나가지 않는다.** 앱 알림과 메일뿐이다.

| 배송 상태 | 화면에 보이는 말 | 알림 | 문구 |
|---|---|---|---|
| `PENDING` | 배송 준비 중(주문 PROCESSING) | ❌ | |
| `INFO_RECEIVED` | 송장 등록 = 발송(주문 DISPATCHED) | ✅ | "상품이 발송되었습니다" |
| `PICKED_UP` | 집화 완료 | ❌ | |
| `IN_TRANSIT` | 배송 중 | ❌ | |
| `CUSTOMS_CLEARED` | 통관 완료 | ❌ | |
| `ATTEMPT_FAILED` | 배달 실패(재시도) | ❌ | |
| `OUT_FOR_DELIVERY` | 배달 출발 | ✅ | "오늘 배송될 예정입니다" |
| `DELIVERED` | 배달 완료 | ✅ | "배송이 완료되었습니다" |
| `CUSTOMS_HOLD` | 통관 보류 | ✅ | 개인통관고유부호·관세 안내 |
| `EXCEPTION` | 배송 사고 | ✅ | "배송에 문제가 발생했습니다" |
| `RETURNED` | 반송 완료 | ✅ | "상품이 반송되었습니다" |
| `CANCELLED` | 발송 전 취소 | ✅ | "배송이 취소되었습니다" |

**고른 기준은 "받는 사람의 행동이 달라지는가" 다.** 집화·간선 이동은 알아도 할 일이 없고,
그 이력은 주문 상세의 배송 추적이 이미 보여준다.

> **발송 알림은 나중에 켰다.** 처음에는 빼 두었는데, 그 이유가 문자 요금이었다. 그런데
> 배송 알림(`SHIPPING_UPDATED`)은 애초에 `smsWorthy = false` 라 문자로 나가지 않는다 —
> 근거가 성립하지 않았다. "상품이 발송되었습니다" 는 받는 사람이 가장 먼저 기다리는
> 소식이고 대부분의 쇼핑몰이 보낸다.

**무엇이 알릴 만한지는 배송 도메인이 정한다.** notification-service 는 이벤트에 실려 온
`worthNotifying` 플래그만 본다. 그래서 발송을 대상에 더할 때 알림 쪽은 문구만 추가하면
됐고, 판정 로직은 `DeliveryStatus.isWorthNotifying()` 한곳에만 있다.

### 11.11 🐛 송장을 등록하면 배송 추적만 바뀌고 진행 띠·환불 안내는 새로고침해야 했다

배송 추적(`DeliveryTracker`)은 20초마다 스스로 읽어 "발송되었습니다" 로 바뀌었다. 그런데
바로 위의 주문 진행 띠는 "상품 준비", 옆의 환불 안내는 "주문 취소 · 환불 요청" 버튼
그대로였다 — 주문은 react-query 가 들고 있고 **아무도 다시 읽으라고 하지 않았다.** 같은
화면이 서로 다른 말을 했다.

주문 상태는 같은 배송 이벤트를 Kafka 로 받아 바뀌므로(§11.2) 배송보다 몇 초 늦다. 그래서
"배송이 바뀌었으니 주문을 한 번 다시 읽는다" 로는 부족하다 — 그 한 번이 너무 이르면 옛
값을 받는다. 배송 추적이 **상태가 바뀐 것을 본 순간** `onStatusChange(delivery)` 로 알리고
(첫 읽기와 같은 상태는 제외), 주문 화면은 `useOrderSettling` 으로 주문이 **그 단계까지
따라올 때까지** 2초마다 다시 읽는다(30초 시한). "따라왔다" 의 판정은 `orderRank` /
`rankForDeliveryStage` — 서버의 `OrderDeliveryStatusService.targetOf`·`rank()` 와 같은
대응이다. 주문이 이미 더 앞서 있으면(구매 확정 뒤 늦게 온 배송 이벤트) 바로 끝난다.

**가드**: `DeliveryTracker.test.jsx`(+2: 첫 읽기·같은 상태에는 안 부르고 바뀌면 부른다, 404 →
PENDING 도 변화다), `orderStatus.test.js`(+3).

### 11.12 🐛 결제됐는데 상세가 NOT_PROCESSED — 결제 버튼이 다시 떴다 (가끔, 배포 직후에 특히)

DB 는 PAID 인데 주문 상세는 "お支払いをお待ちしています" 와 결제 버튼을 보여 줬다. 다시
해 보면 멀쩡했다 — **경쟁 조건**이다.

사가(`OrderSaga.process`)는 상태를 PAID 로 바꾼 **트랜잭션 안에서** `OrderCacheEvictor` 로
캐시를 비웠다. 커밋은 그 뒤다 — 재고·장바구니 발행과 Kafka 트랜잭션 커밋까지 끝난 다음.
그 틈에 확정을 기다리는 화면(2초마다 상세를 읽는다)의 조회가 들어오면:

1. 캐시가 비어 있으니 DB 를 읽는다 → 아직 커밋 전이라 **NOT_PROCESSED**
2. 그 값을 `order` 캐시에 넣는다 → 방금 비운 뒤라 **그대로 30분** 남는다

배포 직후 첫 주문은 트랜잭션 프로듀서 초기화(`initTransactions`)로 그 틈이 몇 초라 거의 매번
걸렸다. `OrderCacheService` 의 `@Transactional` + `@CacheEvict` 메서드들도 같은 모양이다.

**고침**: `RedisCacheManager` 를 `transactionAware()` 로 만든다. 트랜잭션 안의 비우기·넣기는
**커밋 뒤에** 일어나고, 트랜잭션 밖은 예전처럼 즉시다. 남는 틈은 "조회가 커밋 전에 DB 를
읽고, 커밋·비우기가 끝난 뒤에 넣는" 경우뿐인데 그것은 조회 한 번의 읽기→넣기 사이(밀리초)다.

**가드**: `RedisCacheTransactionAwareTest` — 연결 팩토리를 mock 으로 두고 트랜잭션 동기화
안에서 `clear/evict/put` 이 Redis 를 건드리지 않고 커밋 뒤 작업으로 등록되는지 본다.
`transactionAware()` 를 빼면 실패하는 것을 확인했다.

> 이미 걸린 주문을 지금 바로 풀려면 `order` 캐시 키를 지우거나 30분을 기다린다. 주문
> 상태 자체(DB)는 PAID 이므로 목록(캐시 없음)에는 맞게 보인다.

## 12. 구매 확정 — `DELIVERED → COMPLETED`, 주문의 마지막 전이 (2026-09)

§0 의 표에서 PROCESSING·DISPATCHED·DELIVERED 를 채운 뒤에도 **한 칸이 더 비어 있었다.**
`OrderStatus.COMPLETED`(구매 확정)는 선언만 있고 그 값을 넣는 코드가 저장소 어디에도
없었다. 배달 완료가 끝이었고, 프로필의 "구매 확정" 필터는 영원히 비어 있었다 — 아니,
그 필터는 `COMPLETED` 를 걸러 놓고 이름은 "배송 완료" 였다(§12.4).

### 12.1 두 갈래

| 계기 | 누가 | 조건 | 남는 것 |
|---|---|---|---|
| 손님이 주문 상세에서 **구매 확정** 을 누른다 | `POST /api/order/{orderId}/confirm` | 자기 주문이고 `DELIVERED` | `completedAt`, `autoCompleted=false` |
| 배달 완료 뒤 `ORDER_AUTO_CONFIRM_DAYS`(기본 7일)가 지난다 | `OrderPurchaseConfirmationService.autoConfirmSweep`(한 시간마다) | `DELIVERED` 이고 `deliveredCreatedAt < now − days` | `completedAt`, `autoCompleted=true` |

손님이 아무것도 누르지 않는 경우가 대부분이라 **자동 확정이 실제 다수**다. 그래서
`autoCompleted` 를 따로 남긴다 — 화면이 "배송 완료 후 확정 기한이 지나 자동으로 확정되었습니다"
라고 말하고, 알림도 문구가 갈린다.

### 12.2 규칙

- **멱등.** 이미 `COMPLETED` 면 200 으로 그대로 돌려준다. 두 번 눌러도, 자동 확정과
  클릭이 겹쳐도 두 번째는 아무 일도 하지 않는다.
- **배달 완료만.** 오는 중·결제만 됨·환불·취소는 400 이다. 화면은 그 상태에 버튼을 그리지
  않는다(`canConfirmPurchase` 는 DELIVERED 뿐).
- 🔒 **남의 주문은 404.** 403 은 "그 번호의 주문이 존재한다" 를 알려 줘 번호를 훑는 것을
  막지 못한다 — `GET /{orderId}` 와 같은 규칙(`OrderNotFoundException`).
- **확정이 새로 닫는 문은 없다.** 손님의 직접 환불은 배달 완료 시점에 이미 닫혀 있고
  (`SELF_SERVICE_REFUNDABLE` = PAID·PROCESSING), 관리자 환불·반품 입고는 확정 뒤에도 열려
  있다(`SHIPPED_STATUSES` 에 COMPLETED 포함). 매출 집계(`PAID_STATUSES`)에는 배달 완료와
  똑같이 잡힌다. 즉 확정은 **"받았고 이상 없다" 는 손님의 말**이다.
- **배송 이벤트는 확정을 되돌리지 못한다.** `OrderDeliveryStatusService.MOVABLE` 밖이라
  늦게 온 배송 이벤트가 COMPLETED 를 덮어쓰지 않는다(§11.2).

### 12.3 알림 — `order-completed`

order-service 가 커밋 **뒤에** JSON 으로 발행한다(`OrderCompletedKafkaPublisher`,
`afterCommit` 동기화 — 커밋이 실패했는데 "확정됐다" 는 알림만 나가면 안 된다).

```json
{"orderId": 1234, "customerId": 7, "status": "COMPLETED", "auto": true,
 "completedAt": "2026-09-20T10:00:00", "eventId": "order-completed:1234"}
```

notification-service 의 `ORDER_COMPLETED` 는 `smsWorthy=false` — 돈이 오가지 않으니 문자는
아니다. 앱 알림과 메일뿐. `eventId` 가 주문당 하나라 재전달돼도 알림은 하나다.

> ⚠️ 새 토픽이다. `ops/02-create-dlt-topics.sh` 의 `TOPICS` 에 `order-completed` 를 더했으니
> 배포 전에 한 번 돌려 DLT 를 만들 것(B-41). notification-service 는 V9 로 두 CHECK
> (`notification.type`·`mail_message.kind`)에 값을 더한다 — 가드 B-55 가 잡았다.

### 12.4 🐛 함께 고친 것 — "배송 완료" 필터가 `COMPLETED` 를 걸렀다

프로필 사이드바와 주문 목록 탭의 "Delivered Orders / 배송 완료" 가 `filter: "COMPLETED"`
였다. 도착한 주문은 `DELIVERED` 라 이 탭은 언제나 비어 있었다. `DELIVERED` 로 바로잡고
"구매 확정(COMPLETED)" 탭을 따로 뒀다. 주문 상세의 상태 글자 색도 `order.status == "COMPLETED"`
를 보고 있었다 — DTO 의 필드는 `orderStatus` 라 언제나 거짓, 모든 주문이 빨간 글자였다.

### 12.4b 🐛 첫 배포에서 order-service 가 기동하지 못했다

```
APPLICATION FAILED TO START
Parameter 0 of constructor in TokenRequestKafkaPublisher required a single bean, but 2 were found
```

알림 발행용으로 `KafkaTemplate<String, String>`(`orderCompletedKafkaTemplate`)을 하나 더 만들었는데,
기존 `TokenRequestKafkaPublisher` 가 같은 타입을 `@RequiredArgsConstructor` 로 **이름 없이** 주입받고
있었다. lombok 은 `@Qualifier` 를 생성자로 옮겨 주지 않으므로 생성자를 직접 써서
`tokenUpdateKafkaTemplate` 을 이름으로 받는다.

발행자 단위 테스트는 목으로 만들어 **빈 배선은 한 번도 검사되지 않았다.** `KafkaPublisherWiringTest` 가
실제 `KafkaProducerConfig` 와 발행자 일곱 개를 스프링 컨텍스트로 띄운다(브로커에는 붙지 않는다). 옛 코드로
되돌리면 운영과 같은 `NoUniqueBeanDefinitionException` 으로 실패하는 것을 확인했다.

### 12.5 시뮬레이터로 끝까지

§11.6b 대로 `SIM-1` 을 배달 완료까지 보낸 뒤:

1. 주문 상세에 **구매 확정** 버튼이 뜬다. 누르면 한 번 더 묻고, 확정되면 "구매가 확정된
   주문입니다 · 확정 시각" 으로 바뀌고 알림 "구매가 확정되었습니다" 가 온다.
2. 자동 확정을 보려면 오버레이에서 `ORDER_AUTO_CONFIRM_DAYS: "0"` 으로 덮어쓰고
   order-service 를 재시작한다(`ops/28-deploy.sh local --config-only --restart order-service`).
   잡은 기동 2분 뒤 첫 실행, 그 뒤 한 시간마다다. 확정된 주문에는 "자동으로 확정되었습니다"
   가 붙고 알림 문구도 "확정 기한이 지나 자동으로" 다.
3. 프로필의 **구매 확정** 탭에 그 주문이 보인다.

설정: `ORDER_AUTO_CONFIRM_ENABLED`(기본 true) · `ORDER_AUTO_CONFIRM_DAYS`(기본 7) 는
`k8s/10-configmap.yaml` 의 키다. 레플리카가 여럿이면 잡이 파드마다 돈다 — 같은 주문을 두
파드가 같은 순간에 읽으면 둘 다 확정하고 이벤트를 두 번 낼 수 있지만, 상태는 같고 알림은
`eventId` 로 하나다. 지금은 단일 노드라 문제가 아니고, 늘릴 때 ShedLock 을 붙인다.
