# 73. 주문 ↔ 배송지 이중 외래 키 — expand(V25) → contract(V26) 마이그레이션 과정

[日本語](73-order-address-expand-contract.md) | **한국어**

> **상태(2026-10-04)** — R1(V25, expand)은 모델 리팩토링 2차와 함께 운영 배포 대기([docs/72](72-model-refactor-deploy-runbook.ko.md)).
> R2(V26, contract)는 별도 브랜치에 준비돼 있고 CI 컨테이너 테스트를 통과했다 — R1 이 안정된 뒤 기본 브랜치에 합친다.
> 무엇을 왜 고쳤는지는 [docs/62 §18](62-engineering-casebook.ko.md#18-컴파일되고-테스트도-통과하는데-데이터가-틀렸다--모델-계층-점검),
> expand-contract 일반론과 앞선 사례는 [docs/55](55-zero-downtime-migrations.ko.md) 에 있다.

열 두 개를 지우는 일을 **두 릴리스로 나눈 이유, 각 릴리스에서 스키마 · 코드 · 테스트를 어떻게 바꿨는지, 배포할 때 무엇을 확인하는지**를 한곳에 모은다.

---

## 0. 한눈에

| 릴리스 | 마이그레이션 | 코드 | 지우는 것 | 되돌리기 |
|---|---|---|---|---|
| **R0** (운영 중) | — | 주문 ↔ 주소가 서로를 가리킴 | — | — |
| **R1** expand | order **V25** (`0c105d5`) | 주문 → 주소 한 방향. 두 열을 매핑하지 않음 | 없음 — `order_id` NOT NULL 해제, `tax_price` 기본값 0, 주문 쪽 유니크 | 이미지만 되돌려도 됨 |
| **R2** contract | order **V26** | 없음(테스트 · e2e 시드만) | `order_address.order_id`, `orders.tax_price` | R1 이미지까지는 안전, R0 은 불가(**ROLLBACK-UNSAFE**) |

R1 과 R2 사이에는 **R1 이 다 굴러가고 안정됐다는 판단**(예: 1주일)이 있다. V26 은 R1 과 한 배포로 나가면 스스로 실패한다(§5-2).

---

## 1. 무엇이 문제였나

- **외래 키가 둘이었다.** `orders.order_address_id → order_address` 와 `order_address.order_id → orders`. 주문 하나를 만들 때 "주문 INSERT → 주소 INSERT → 주문 UPDATE(주소 id)" 세 번을 썼고, 지울 때는 한쪽을 먼저 끊어야 했다. 두 값이 어긋나도 막는 것이 없었다.
- **세금 열은 늘 0 이었다.** `Order.taxPrice` 를 채우는 곳이 없었고, 화면은 그것을 "세금 무료" 로 보였다. 가격은 세금 포함이다.

목표 모양: 주문이 주소를 가리키는 **한 방향**(`Order.shippingAddress`), 주문당 주소 하나는 `orders.order_address_id` 유니크로, 세금 열은 없음.

---

## 2. 왜 한 번에 지우지 않나

이 클러스터의 배포 성질([docs/55 §1](55-zero-downtime-migrations.ko.md))이 그대로 적용된다.

- **롤링 배포** — 한동안 옛 파드와 새 파드가 같은 DB 를 쓴다. 새 파드가 기동하면서 마이그레이션을 적용하므로, 옛 파드는 **새 스키마** 위에서 돈다.
- **Hibernate `ddl-auto: validate`** — 엔티티가 매핑하는 열이 없으면 파드가 뜨지 않는다. 이미 떠 있는 옛 파드는 그 열을 읽고 쓰다 실패한다.
- **`ops/28 --rollback` 은 이미지만 되돌린다** — 스키마는 앞으로 간 채로 남는다.

그래서 R0 코드가 쓰는 열을 R0 → R1 배포에서 지우면, 롤링 중의 옛 파드도 되돌린 옛 이미지도 죽는다. **먼저 코드가 그 열을 안 쓰게 하고(expand), 그 버전이 다 굴러간 뒤에 지운다(contract).**

---

## 3. R1 — expand (order V25)

### 3-1. 스키마

```sql
ALTER TABLE orders.order_address ALTER COLUMN order_id DROP NOT NULL;   -- 새 코드는 비워 둔다
ALTER TABLE orders.orders ALTER COLUMN tax_price SET DEFAULT 0;         -- 새 코드는 쓰지 않는다
-- 주문당 주소 하나 — 예전엔 order_address.order_id 유니크가 지켰다. 이제 주문 쪽에서.
-- 어긋난 데이터(같은 주소를 둘 이상의 주문이 가리킴)가 있으면 만들지 않고 NOTICE 만 남긴다(마이그레이션은 성공).
CREATE UNIQUE INDEX uq_orders_order_address ON orders.orders (order_address_id);
```

열을 지우지도, 바꾸지도 않으므로 B-54(파괴적 마이그레이션 선언)의 대상이 아니다.

### 3-2. 코드

- `Order.shippingAddress` 를 `@OneToOne(fetch = LAZY)` 로. `OrderAddress` 에서 `order` 필드 · `order_id` 인덱스 · 유니크 선언을 뺐다. `Order.taxPrice` 를 지웠다.
- 주문 생성: **주소를 먼저 저장하고 주문이 가리킨다** — INSERT 두 번(`OrderDataService`).
- 결제 전 주문 버리기: 주문이 가리키는 주소 id 를 읽고 → 끊고 → 주소 · 품목 · 주문 순서로 지운다(`OrderRepository`). R0 모양(주소도 주문을 가리킴)의 행도 주소를 주문보다 먼저 지우므로 처리된다.
- 프론트: 주문 상세의 세금 줄을 "포함 / 込み" 로.

### 3-3. 롤링 중 — 옛 파드와 새 파드가 함께 돌 때

| 누가 | 무엇을 | 결과 |
|---|---|---|
| R0 파드 | 주문 생성 | 지금처럼 `order_id` · `tax_price` 를 채운다 — 두 열이 남아 있으므로 정상 |
| R1 파드 | 주문 생성 | 주소의 `order_id` 는 NULL, `tax_price` 는 기본값 0 |
| R0 파드 | R1 이 만든 결제 전 주문 버리기 | `order_id` 로 주소를 찾으므로 **주소 행이 하나 남는다** — 고아 주소일 뿐 주문은 지워진다 |
| R1 파드 | R0 이 만든 주문 버리기 | 주문 쪽 주소 id 로 지우므로 정상 |

되돌리기: **이미지만 되돌려도 된다.** R0 코드는 `order_id` 를 계속 채우고, 남는 것은 위의 고아 주소 행뿐이다.

### 3-4. 테스트

- `OrderServiceApplicationTests`(Testcontainers): 새 주문의 주소는 `order_id` 가 비고 세금 열은 0. 결제 전 주문 버리기가 **새 모양과 R0 모양 모두**에서 주문 · 주소를 지운다.
- 가드 `EntityMappingGuardTest`(to-one 은 LAZY).

---

## 4. 사이 — R1 이 안정됐는지 확인

R2 를 내보내기 전에, 운영 DB 에서(읽기 전용):

```sql
-- R1 이 적용된 지 충분히 지났다
SELECT installed_on FROM flyway_schema_history WHERE version = '25';
-- 주문 쪽 유니크가 있다 — NULL 이면 V25 가 어긋난 데이터 때문에 건너뛴 것(V26 이 실패한다)
SELECT to_regclass('orders.uq_orders_order_address');
-- 건너뛰었다면 원인 찾기: 같은 주소를 가리키는 주문
SELECT order_address_id, count(*) FROM orders.orders
 WHERE order_address_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
```

그리고 R1 으로 되돌릴 일이 없다고 판단한다 — R2 가 나가면 **R0 으로는 되돌릴 수 없다.**

---

## 5. R2 — contract (order V26)

### 5-1. 스키마

```sql
ALTER TABLE orders.order_address DROP COLUMN IF EXISTS order_id;   -- 외래 키 · idx_order_address · 유니크가 함께 사라진다
ALTER TABLE orders.orders DROP COLUMN IF EXISTS tax_price;
```

열 삭제는 파괴적 변경이라 파일에 `-- ROLLBACK-UNSAFE:` 를 적었다(가드 B-54). `ops/28` 은 이 선언을 보고 그 배포의 자동 복귀를 끈다.
R0 으로 꼭 되돌려야 하면 열을 다시 만들고 채운다(파일 주석에 SQL 이 있다 — `order_id` 는 `orders.order_address_id` 로 거꾸로 채운다).

### 5-2. 스스로 멈추는 두 확인

지우기 전에 `DO` 블록이 확인하고, 아니면 `RAISE EXCEPTION` 으로 실패한다. PostgreSQL 은 DDL 도 트랜잭션이라 **아무것도 바뀌지 않고**, 새 파드가 준비되지 못해 옛 파드가 계속 돈다.

1. **시간 가드 — V25 와 한 배포가 아닐 것.** V25 가 이번 실행에서 막 적용됐으면(30분 이내, 그리고 DB 의 첫 마이그레이션보다 10분 넘게 뒤) 실패한다. 롤링 중의 R0 파드가 두 열을 쓰기 때문이다. 새 DB(모든 마이그레이션을 한 번에)는 옛 파드가 없으므로 통과한다. order V18 과 같은 방식이다([docs/55 §3](55-zero-downtime-migrations.ko.md)).
2. **데이터 가드 — 주문 쪽 유니크가 있을 것.** V25 가 `uq_orders_order_address` 를 건너뛴 상태에서 `order_id` 를 지우면, 옛 유니크까지 사라져 주문당 주소 하나를 지키는 것이 없어지고, "이 주소의 원래 주문" 이라는 단서도 잃는다. 데이터를 바로잡고 유니크를 만든 뒤 다시 배포한다.

### 5-3. 코드

엔티티는 R1 에서 이미 두 열을 매핑하지 않으므로 **바뀌는 코드가 없다.** 사라지는 열을 쓰던 테스트만 고쳤다.

- `OrderServiceApplicationTests`: 두 열이 없음을 확인. R0 모양 행을 만들던 테스트는 그 모양이 더 존재할 수 없으므로 새 모양만.
- e2e `SagaEndToEndTest` 시드에서 `tax_price` 를 뺐다.

### 5-4. 테스트 — `OrderAddressContractMigrationTest`

Spring 없이 Flyway 만, 실제 PostgreSQL(Testcontainers)에서. 테스트마다 새 데이터베이스를 만들고, `flyway_schema_history` 의 적용 시각을 옮겨 "예전부터 있던 운영 DB" 를 흉내 낸다.

| 경우 | 준비 | 기대 |
|---|---|---|
| 정상 | V24 까지 → R0 모양 주문 → V25 → R1 모양 주문 → V25 를 2일 전으로 | 두 열이 없고, 주소 · 주문 → 주소는 남고, 같은 주소를 가리키는 주문은 `uq_orders_order_address` 로 거절 |
| 한 배포 | V24 까지(30일 전으로) → V25 · V26 을 한 번에 | 실패 · 두 열과 데이터가 그대로 |
| 유니크 없음 | 같은 주소를 두 주문이 가리킴 → V25(NOTICE) → 2일 전으로 → V26 | 실패 · `order_id` 가 단서로 남음 |
| 새 DB | 모든 마이그레이션을 한 번에 | 통과 |

결과: 브랜치 CI(run 37195776469)에서 4건 통과, order-service 통합 테스트 306건 실패 0. 인수 테스트(사가 E2E)도 V26 스키마에서 통과.
가드 B-54 는 `ROLLBACK-UNSAFE:` 선언을 빼면 V26 을 잡아 실패하는 것을 확인했다(변이 확인).

### 5-5. 롤링 중 · 되돌리기

- 롤링 중의 옛 파드는 R1 이미지다 — 두 열을 매핑하지 않으므로 영향이 없다.
- R1 이미지로 되돌리는 것은 안전하다. 다만 `ops/28` 은 ROLLBACK-UNSAFE 가 있는 범위의 `--rollback` 을 막으므로, 꼭 필요하면 `FORCE_UNSAFE_ROLLBACK=1` 로 명시한다([docs/72 §6](72-model-refactor-deploy-runbook.ko.md)).

---

## 6. 배포 절차 요약

| 단계 | R1 (V25) | R2 (V26) |
|---|---|---|
| 사전 점검 | 같은 주소를 가리키는 주문 수([docs/72 §2](72-model-refactor-deploy-runbook.ko.md)) | §4 의 세 쿼리 |
| 백업 | `ops/08` · `ops/10`(같은 배포에 catalog V18 이 있어 필수) | `ops/08` — 열 삭제 |
| 배포 | `ops/28-deploy.sh prod <SHA>` — ROLLBACK-UNSAFE 경고(catalog V18) | `ops/28-deploy.sh prod <SHA>` — ROLLBACK-UNSAFE 경고(V26) |
| 배포 후 | order flyway 25 · V25 NOTICE 가 없음 · 새 주소의 `order_id` 는 NULL | order flyway 26 · 두 열이 없음(`information_schema.columns`) |
| 실패하면 | 이미지만 되돌림 | 아무것도 안 바뀜 — 원인(시간 · 데이터 가드)을 고치고 다시 배포 |

---

## 7. 장치 모음

| 장치 | 지키는 것 | 위치 |
|---|---|---|
| B-54 | 파괴적 마이그레이션은 `ROLLBACK-UNSAFE:` 를 선언한다 | `common` `ConfigurationGuardrailTest` |
| `ops/28` | ROLLBACK-UNSAFE 가 있는 배포는 자동 복귀를 끄고 `--rollback` 을 막는다 | `ops/28-deploy.sh` |
| 시간 가드 | expand 와 contract 를 한 배포로 올리지 않는다 | V26 `DO` 블록 |
| 데이터 가드 | 대신할 유니크 없이 옛 유니크를 지우지 않는다 | V26 `DO` 블록 |
| 실제 PostgreSQL 마이그레이션 테스트 | 옛 모양 데이터 → 새 버전 적용 | `OrderAddressContractMigrationTest` |

---

## 8. 남은 것

- R1 배포와 안정 확인 → contract 브랜치를 기본 브랜치 최신 위로 rebase → 기본 브랜치에 합친다 → R2 배포.
- R2 배포 후 남은 일 목록에서 "다음 릴리스(contract)" 를 지운다.
