# 72. 모델 리팩토링 2차 배포 런북 — 되돌릴 수 없는 배포를 안전하게

[日本語](72-model-refactor-deploy-runbook.md) | **한국어**

> **상태(2026-10-06)** — 대상 버전은 **`b7c5053a45e8`** 입니다(파이프라인 초록). `d7f87c9` 이후에 더해진 것은 user 의 유예 토큰 길이 검사(a906312), 문서, 배포 수동 실행 전환(B-75)뿐입니다. 기능 플래그(docs/74, `c291eba` 이후)는 **이 배포가 안정된 뒤 따로** 나갑니다 — 그래서 Run workflow 의 버전을 비우지 말고 이 SHA 를 넣습니다(비우면 기본 브랜치 최신 = 플래그까지 함께 나간다). 무엇을 왜 고쳤는지는 [docs/62 §18](62-engineering-casebook.ko.md#18-컴파일되고-테스트도-통과하는데-데이터가-틀렸다--모델-계층-점검), 배포 스크립트 자체는 [docs/36](36-deployment-pipeline.ko.md) · [docs/33](33-deploy-runbook.ko.md) 입니다.
>
> **배포 완료(2026-10-10)** — 인스턴스에서 `ops/28-deploy.sh local b7c5053a45e8` 로 손 배포, 328초, 스모크 통과(4 통과 · 0 실패 · 5 생략). 저장소에 운영 배포 러너(`oci-ampere`)가 없어 Actions 대신 손으로 했고, 작업 사본은 먼저 대상 커밋으로 `git switch --detach` 했습니다(Actions 도 대상 커밋을 체크아웃해 같은 스크립트를 돌린다 — 다른 커밋의 매니페스트가 섞이지 않게). 사가 E2E(결제)는 수동 확인 항목입니다.
> 배포 후 확인(§5): Flyway cart 5 · chat 9 · catalog 18 · order 25 · pay 7 · notification 11 · user 16, 새 인덱스 · 제약(`uq_cart_user` · `uq_participant_room_member` · `uq_orders_order_address` · `idx_payment_tracking` · `idx_review_product` · `LOGIN_CHALLENGE` check)이 모두 있고 옛 `idx_payment` · `idx_review` 는 없음, 패스키 표 있음, 숫자로 남은 공지 · 대형 객체 0, 전 서비스 `b7c5053a45e8` READY. chat 시각 보정 1차 7건(Mongo `chat` 의 전부 — 옛 코드가 쓴 메시지뿐이었다). `productdb` 의 `public` 스키마에 남은 옛 Flyway 이력(V5)은 스키마를 `product` 로 옮기기 전의 것으로, 이번 배포와 무관합니다.
>
> **사전 점검(2026-10-09)** — 운영은 **`a813ae314f8c`**(2026-10-01 커밋, kubectl 로 전 서비스 같은 태그)입니다. §2 의 점검이 모두 예상대로였습니다 — Flyway 는 cart 4 · chat 8 · catalog 17 · order 24 · pay 6 · notification 10 · user 14, 중복 · OID · 대형 객체 · 허용 밖 메일 종류는 모두 0. 그래서 V5 · V9 는 지울 행이 없고 V11 의 check 도 통과합니다.

이번 배포는 **자동 복귀가 꺼지는 배포**입니다. catalog V18 이 되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE)이기 때문입니다. 그래서 평소 배포에 더해 **사전 점검 · 백업 · 배포 중 금지 사항 · 배포 후 확인**을 이 문서 순서대로 합니다.

1차 모델 수정(user V16 등)은 아직 운영에 나가지 않았습니다(운영 user 는 V14) — 같은 배포에 함께 나갑니다. 배포는 늘 버전 하나(커밋 SHA)로 전 서비스입니다.

## 1. 무엇이 바뀌나

| 서비스 | 변경 | 하는 일 | 되돌리기 |
|---|---|---|---|
| cart | V5 | 사용자당 중복 장바구니를 최근 것 하나로 합치고(**행 삭제**) `user_id` 유니크 | 이미지만 되돌려도 됨 |
| chat | V9 | 같은 (방, 회원) 참여 중복 삭제 + 유니크 | 이미지만 되돌려도 됨 |
| chat | 기동 시 백그라운드 | 몽고 대화 인덱스 생성, 옛 메시지 시각 +9시간(15분 뒤 2차) | 끄기: `chat.message-maintenance.enabled=false` |
| catalog | V18 | 공지 본문을 대형 객체(OID)에서 글로 옮기고 대형 객체 해제 | **불가 — ROLLBACK-UNSAFE** |
| order | V25 | `order_address.order_id` NOT NULL 해제, `tax_price` 기본값 0, 주문당 주소 유니크 | 이미지만 되돌려도 됨 |
| pay | V7 | `tracking_id` 인덱스, 유니크와 겹치던 `idx_payment` 삭제 | 이미지만 되돌려도 됨 |
| notification | V11 | 메일 종류 check 에 `LOGIN_CHALLENGE` 추가(check 를 지우고 다시 만든다 — 허용 밖 행이 있으면 실패, §2) | 이미지만 되돌려도 됨(허용 값을 늘리기만 한다) |
| user | V15 | 패스키 자격증명 표 `users.passkey_credential` 새로 만듦 | 이미지만 되돌려도 됨(옛 이미지는 쓰지 않는다) |
| user | V16 | 리뷰 본문이 든 `idx_review` 삭제(긴 한국어 리뷰가 B-tree 한도로 거절되던 것), `product_id` 인덱스 | 이미지만 되돌려도 됨 |
| frontend | — | 주문 상세 세금 줄 "포함 / 込み" | — |

서비스끼리 얽힌 계약 변경은 없습니다 — `OrderDto.taxPrice` 삭제는 JSON 필드가 빠질 뿐이고 읽는 곳이 없었으며, `CouponStatus.EXPIRED` 는 아무 행도 갖지 않았습니다. 그래서 **서비스 사이의 배포 순서는 상관없습니다**(게이트웨이 마지막은 `ops/28` 이 늘 지킵니다).

## 2. 사전 점검 — 운영 DB, 읽기 전용

마이그레이션이 무엇을 바꿀지 미리 봐 둡니다. 숫자는 배포 후 확인(§5)과 비교합니다. 개수만 봅니다 — 사용자 id · 이메일은 출력하지 않습니다.

```sql
-- 각 서비스 DB: 지금 적용된 버전 — 운영 버전(a813ae314f8c)이면 cart 4 · chat 8 · catalog 17 · order 24 · pay 6 · notification 10 · user 14.
-- 다르면 멈춘다: 이 표(§1)가 가정한 출발점이 아니다.
SELECT max(version::int) FROM flyway_schema_history WHERE success;

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

-- notification DB: V11 이 다시 만드는 check 밖의 메일 종류 — 0 이어야 한다(있으면 V11 이 실패하고 notification 이 뜨지 않는다)
SELECT count(*) FROM notification.mail_message
 WHERE kind NOT IN ('REGISTRATION_CONFIRM', 'PASSWORD_RESET', 'LOGIN_CHALLENGE', 'ORDER_PLACED',
                    'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED', 'SHIPPING_UPDATED',
                    'ORDER_COMPLETED', 'COUPON_ISSUED', 'SECURITY_ALERT', 'RESTOCK_AVAILABLE');
```

운영 클러스터 안의 PostgreSQL 에서는 이렇게 돌립니다(catalog 는 `productdb`, 스키마 `product`):

```bash
q(){ kubectl -n postgres exec -i deploy/postgres -- psql -U postgres -d "$1" -tAX -v ON_ERROR_STOP=1 -c "$2"; }
for db in cartdb chatdb productdb orderdb paydb notificationdb userdb; do
  echo "$db: $(q "$db" 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"
done
```

**2026-10-09 결과** — 버전은 위 기대값 그대로, 나머지 쿼리는 모두 0(중복 장바구니 · 중복 참여 · OID 공지 · 대형 객체 · 같은 주소 주문 · 허용 밖 메일 종류).

## 3. 백업 — 필수

V18 은 되돌릴 수 없고, V5 · V9 는 행을 지우고, 시각 보정은 몽고 문서를 바꿉니다.

`ops/08` · `ops/10` 은 DB 에 밖에서 붙는 스크립트라 이 운영 구성에는 맞지 않습니다 — PostgreSQL 은 클러스터 안 pod(`postgres` 네임스페이스)이고 기본 `pg_hba` 가 원격 replication 을 막으며, MongoDB 는 **Atlas**(chat 의 `SPRING_DATA_MONGODB_CLIENT`, 클러스터 안 `mongodb` 는 0/0)입니다. 그래서 pod 안의 도구로 뜨고 서버로 받습니다. 비밀번호는 화면 · 파일에 나오지 않습니다(PostgreSQL 은 pod 안 로컬 접속, Atlas 는 Secret 을 임시 pod 의 환경변수로만).

```bash
B=~/backups/pre-<버전>-$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$B"; df -h ~ | tail -1
kubectl -n postgres exec deploy/postgres -- pg_basebackup -U postgres -D - -Ft -X fetch --checkpoint=fast > "$B/pg-base.tar"
tar -tf "$B/pg-base.tar" | grep -c backup_label
kubectl -n postgres exec deploy/postgres -- pg_dumpall -U postgres | gzip > "$B/pg-all.sql.gz"
gzip -t "$B/pg-all.sql.gz" && zcat "$B/pg-all.sql.gz" | tail -3

kubectl -n ecommerce run mongo-backup --image=mirror.gcr.io/mongo:7 --restart=Never --overrides='{"spec":{"containers":[{"name":"mongo-backup","image":"mirror.gcr.io/mongo:7","command":["sleep","1800"],"env":[{"name":"MONGO_URI","valueFrom":{"secretKeyRef":{"name":"ecommerce-secrets","key":"SPRING_DATA_MONGODB_CLIENT"}}}]}]}}'
kubectl -n ecommerce wait --for=condition=Ready pod/mongo-backup --timeout=180s
kubectl -n ecommerce exec mongo-backup -- sh -c 'mongodump --uri="$MONGO_URI" --db=chat --archive --gzip --quiet' > "$B/mongo.archive.gz"
gzip -t "$B/mongo.archive.gz" && ls -l "$B/mongo.archive.gz"
kubectl -n ecommerce exec mongo-backup -- sh -c 'mongosh "$MONGO_URI" --quiet --eval "const d=db.getSiblingDB(\"chat\"); d.getCollectionNames().forEach(c => print(c + \": \" + d.getCollection(c).estimatedDocumentCount()))"'
kubectl -n ecommerce delete pod mongo-backup
```

- `kubectl exec` 에 `-t` 를 붙이지 않습니다 — 터미널 모드가 되면 바이너리 출력이 깨집니다.
- 정상: `backup_label` 1, 덤프 끝에 `dump complete`, Mongo 는 컬렉션별 문서 수. 물리 백업(`pg-base.tar`)은 전체를 그 시점으로, 논리 덤프(`pg-all.sql.gz`)는 표 하나(예: 공지)를 되살릴 때 씁니다.
- **2026-10-10 실측**: 전체 DB 4,767 MB → `pg-base.tar` 4.7G · `pg-all.sql.gz` 304M, Mongo `chat` 7건 · `auto_sequence` 1건(936바이트 — 작은 것이 정상). 서버 디스크 여유 30G 에서 약 25G.
- 백업은 운영 디스크에 함께 있습니다 — 배포가 안정되면 다른 기계로 옮겨 둡니다. Atlas M0 에는 자동 백업이 없습니다.

## 4. 배포

### 4-1. 배포 중 지킬 것

- 🔴 **배포가 끝날 때까지 관리자 공지 편집을 멈춥니다.** 롤링 중에는 옛 catalog 파드가 남아 있고, 그 파드로 공지를 저장하면 본문이 다시 OID 로 저장됩니다. V18 은 이미 지나가 그 행은 숫자로 남습니다(§5 의 쿼리로 찾을 수 있습니다).
- 롤링 중 잠깐 생기는 일 — 모두 예상된 것입니다.
  - 옛 catalog 파드가 공지를 읽다 실패할 수 있습니다(새 파드로 바뀌면 사라짐).
  - 옛 chat 파드가 쓴 메시지는 9시간 이른 채로 저장되지만, 15분 뒤 2차 보정이 같은 기준 시각으로 잡습니다.
  - 옛 order 파드가 결제 전 주문을 버리면 주소 행이 하나 남을 수 있습니다(주문은 지워짐).
- 인덱스 생성(cart · chat · order · pay)은 그 표의 쓰기를 잠깐 막습니다. 지금 표 크기로는 수 초입니다.

### 4-2. 실행

1. CI 가 대상 커밋의 이미지를 만들었는지 확인합니다(태그 = 커밋 SHA). `b7c5053a45e8` 은 파이프라인 #497 이 초록입니다.
2. Actions → `deploy (OCI Ampere)` → Run workflow 에 버전(커밋 SHA)을 넣어 실행합니다 — 실행이 곧 승인입니다(자동 배포는 없다, docs/36 §6). 내부적으로 `ops/28-deploy.sh <환경> <버전>` 이고, 손으로 해도 같은 명령입니다.
   - 저장소 Settings → Actions → Runners 에 **`oci-ampere` 라벨의 러너가 있어야 합니다.** 없으면 배포 잡이 러너를 기다리다 끝나지 않습니다. 그때는 둘 중 하나입니다.
     - 러너 등록(docs/36 §6.1 4번, `ops/40-deploy-runner.sh`) — 최신 러너를 받으므로 Node 24 액션에 필요한 2.327.1 이상이 됩니다.
     - 또는 인스턴스에서 손으로: 먼저 `./ops/28-deploy.sh --report` 로 지난 배포의 환경 이름을 보고, 같은 환경으로 `./ops/28-deploy.sh <환경> b7c5053a45e8`(배포 워크플로의 기본값은 `local`). 이력 파일이 같아 `--report` · `--rollback` 이 그대로 이어집니다.
3. 시작할 때 **"되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE)이 이 범위에 있습니다"** 경고와 V18 경로가 나옵니다 — 정상입니다. 이 배포는 실패해도 자동 복귀하지 않고 `--rollback` 도 막힙니다.
4. 1단계: 게이트웨이를 뺀 전 서비스. 각 서비스는 기동하면서 자기 마이그레이션을 적용합니다.
5. 2단계: 게이트웨이 → 스모크(`ops/06`).

## 5. 배포 후 확인

```sql
-- 각 서비스 DB: 적용된 버전(cart 5 · chat 9 · catalog 18 · order 25 · pay 7 · notification 11 · user 16)
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
