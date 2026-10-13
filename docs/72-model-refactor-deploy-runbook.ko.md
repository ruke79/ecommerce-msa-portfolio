# 72. 모델 리팩토링 2차 배포 런북 — 되돌릴 수 없는 배포를 안전하게

[日本語](72-model-refactor-deploy-runbook.md) | **한국어**

> **상태(2026-10-06)** — 대상 버전은 **`b7c5053a45e8`** 입니다(파이프라인 초록). `d7f87c9` 이후에 더해진 것은 user 의 유예 토큰 길이 검사(a906312), 문서, 배포 수동 실행 전환(B-75)뿐입니다. 기능 플래그(docs/74, `c291eba` 이후)는 **이 배포가 안정된 뒤 따로** 나갑니다 — 그래서 Run workflow 의 버전을 비우지 말고 이 SHA 를 넣습니다(비우면 기본 브랜치 최신 = 플래그까지 함께 나간다). 무엇을 왜 고쳤는지는 [docs/62 §18](62-engineering-casebook.ko.md#18-컴파일되고-테스트도-통과하는데-데이터가-틀렸다--모델-계층-점검), 배포 스크립트 자체는 [docs/36](36-deployment-pipeline.ko.md) · [docs/33](33-deploy-runbook.ko.md) 입니다.

이번 배포는 **자동 복귀가 꺼지는 배포**입니다. catalog V18 이 되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE)이기 때문입니다. 그래서 평소 배포에 더해 **사전 점검 · 백업 · 배포 중 금지 사항 · 배포 후 확인**을 이 문서 순서대로 합니다.

1차 모델 수정(user V16 등)을 아직 배포하지 않았다면 같은 배포에 함께 나갑니다 — 배포는 늘 버전 하나(커밋 SHA)로 전 서비스입니다.

## 1. 무엇이 바뀌나

| 서비스 | 변경 | 하는 일 | 되돌리기 |
|---|---|---|---|
| cart | V5 | 사용자당 중복 장바구니를 최근 것 하나로 합치고(**행 삭제**) `user_id` 유니크 | 이미지만 되돌려도 됨 |
| chat | V9 | 같은 (방, 회원) 참여 중복 삭제 + 유니크 | 이미지만 되돌려도 됨 |
| chat | 기동 시 백그라운드 | 몽고 대화 인덱스 생성, 옛 메시지 시각 +9시간(15분 뒤 2차) | 끄기: `chat.message-maintenance.enabled=false` |
| catalog | V18 | 공지 본문을 대형 객체(OID)에서 글로 옮기고 대형 객체 해제 | **불가 — ROLLBACK-UNSAFE** |
| order | V25 | `order_address.order_id` NOT NULL 해제, `tax_price` 기본값 0, 주문당 주소 유니크 | 이미지만 되돌려도 됨 |
| pay | V7 | `tracking_id` 인덱스, 유니크와 겹치던 `idx_payment` 삭제 | 이미지만 되돌려도 됨 |
| frontend | — | 주문 상세 세금 줄 "포함 / 込み" | — |

서비스끼리 얽힌 계약 변경은 없습니다 — `OrderDto.taxPrice` 삭제는 JSON 필드가 빠질 뿐이고 읽는 곳이 없었으며, `CouponStatus.EXPIRED` 는 아무 행도 갖지 않았습니다. 그래서 **서비스 사이의 배포 순서는 상관없습니다**(게이트웨이 마지막은 `ops/28` 이 늘 지킵니다).

## 2. 사전 점검 — 운영 DB, 읽기 전용

마이그레이션이 무엇을 바꿀지 미리 봐 둡니다. 숫자는 배포 후 확인(§5)과 비교합니다.

```sql
-- cart DB: 합쳐질 중복 장바구니
SELECT user_id, count(*) FROM cart.cart GROUP BY 1 HAVING count(*) > 1;

-- chat DB: 지워질 중복 참여
SELECT room_id, member_id, count(*) FROM participant_chat_room GROUP BY 1, 2 HAVING count(*) > 1;

-- catalog DB: OID 로 저장된 공지 수 · 대형 객체 수
SELECT count(*) FROM content.notice WHERE body ~ '^[0-9]+$';
SELECT count(*) FROM pg_largeobject_metadata;

-- order DB: 같은 주소를 가리키는 주문 — 있으면 V25 가 유니크를 만들지 않고 NOTICE 만 남긴다
SELECT order_address_id, count(*) FROM orders.orders
 WHERE order_address_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
```

## 3. 백업 — 필수

V18 은 되돌릴 수 없고, V5 · V9 는 행을 지우고, 시각 보정은 몽고 문서를 바꿉니다.

```bash
./ops/08-backup-postgres.sh     # PostgreSQL base backup + WAL (PITR)
./ops/10-backup-mongo.sh        # MongoDB 덤프 + 빈 인스턴스 복원 검증
```

## 4. 배포

### 4-1. 배포 중 지킬 것

- 🔴 **배포가 끝날 때까지 관리자 공지 편집을 멈춥니다.** 롤링 중에는 옛 catalog 파드가 남아 있고, 그 파드로 공지를 저장하면 본문이 다시 OID 로 저장됩니다. V18 은 이미 지나가 그 행은 숫자로 남습니다(§5 의 쿼리로 찾을 수 있습니다).
- 롤링 중 잠깐 생기는 일 — 모두 예상된 것입니다.
  - 옛 catalog 파드가 공지를 읽다 실패할 수 있습니다(새 파드로 바뀌면 사라짐).
  - 옛 chat 파드가 쓴 메시지는 9시간 이른 채로 저장되지만, 15분 뒤 2차 보정이 같은 기준 시각으로 잡습니다.
  - 옛 order 파드가 결제 전 주문을 버리면 주소 행이 하나 남을 수 있습니다(주문은 지워짐).
- 인덱스 생성(cart · chat · order · pay)은 그 표의 쓰기를 잠깐 막습니다. 지금 표 크기로는 수 초입니다.

### 4-2. 실행

1. CI 가 대상 커밋의 이미지를 만들었는지 확인합니다(태그 = 커밋 SHA).
2. Actions → `deploy (OCI Ampere)` → Run workflow 에 버전(커밋 SHA)을 넣어 실행합니다 — 실행이 곧 승인입니다(자동 배포는 없다, docs/36 §6). 내부적으로 `ops/28-deploy.sh <환경> <버전>` 이고, 손으로 해도 같은 명령입니다.
3. 시작할 때 **"되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE)이 이 범위에 있습니다"** 경고와 V18 경로가 나옵니다 — 정상입니다. 이 배포는 실패해도 자동 복귀하지 않고 `--rollback` 도 막힙니다.
4. 1단계: 게이트웨이를 뺀 전 서비스. 각 서비스는 기동하면서 자기 마이그레이션을 적용합니다.
5. 2단계: 게이트웨이 → 스모크(`ops/06`).

## 5. 배포 후 확인

```sql
-- 각 서비스 DB: 적용된 버전(cart 5 · chat 9 · catalog 18 · order 25 · pay 7)
SELECT max(version::int) FROM flyway_schema_history WHERE success;

-- catalog: 숫자로 남은 공지가 없고 대형 객체가 0
SELECT notice_id FROM content.notice WHERE body ~ '^[0-9]+$';
SELECT count(*) FROM pg_largeobject_metadata;

-- cart · chat: 중복이 사라졌다(§2 의 쿼리가 0행)
```

- **chat 로그**: `옛 메시지 시각 보정: N건을 +9시간 옮겼다` — 15분 뒤 2차 실행도 봅니다. 실패하면 `대화 기록 정비(…) 실패` 경고가 남고, 다음 기동에 다시 돕니다.
- **order 로그**: V25 의 `NOTICE: 같은 배송지를 가리키는 주문이 있어 …` 가 없어야 합니다. 있으면 주소 유니크가 만들어지지 않은 것입니다 — 데이터를 확인한 뒤 손으로 만듭니다.
- **화면**: 주문을 하나 만들어 주문 상세에 "세금 포함" 이 보이는지. DB 에서 새 주소의 `order_id` 는 NULL, 주문의 `tax_price` 는 0.
- **선택**: Redis 의 옛 방 해시는 읽는 곳이 없습니다 — `DEL CHAT_ROOM` 으로 지워도 됩니다.

## 6. 실패하면 — 앞으로 고친다

- 실패한 서비스는 옛 파드가 계속 돌고(롤링이 멈춘 상태), 성공한 서비스는 새 버전으로 남습니다. `ops/28` 이 새 파드의 기동 오류를 출력합니다 — 고친 이미지로 다시 배포합니다.
- 꼭 되돌려야 하면 **스키마를 먼저** 되돌린 뒤에만: `FORCE_UNSAFE_ROLLBACK=1 ./ops/28-deploy.sh prod --rollback`. V18 의 경우 공지 본문을 다시 대형 객체로 넣어야 합니다(§3 의 백업에서 복원하는 편이 간단합니다). 공지는 운영자만 쓰므로, 되돌리지 않고 공지 화면만 잠시 막히는 쪽이 대개 낫습니다.

## 7. 다음 릴리스 — contract

이번 버전이 안정되고 되돌릴 일이 없다고 판단되면(예: 1주일), 새 마이그레이션으로 지웁니다.

```sql
-- ROLLBACK-UNSAFE: 열을 지운다 — 이 버전 이전 이미지는 order_address.order_id · orders.tax_price 를 쓴다.
ALTER TABLE orders.order_address DROP COLUMN IF EXISTS order_id;   -- 외래 키 · idx_order_address · 유니크가 함께 사라진다
ALTER TABLE orders.orders DROP COLUMN IF EXISTS tax_price;
```

열 삭제는 파괴적 변경이라 `-- ROLLBACK-UNSAFE:` 선언이 필요합니다(가드 B-54). 엔티티는 이미 두 열을 쓰지 않으므로 코드 변경은 없습니다.
