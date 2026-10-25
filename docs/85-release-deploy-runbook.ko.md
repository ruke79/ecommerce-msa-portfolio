# 85. 묶음 릴리스 배포 런북 — 10-10 이후 쌓인 수정을 운영에 한 번에

[日本語](85-release-deploy-runbook.md) | **한국어**

> **상태(2026-10-24)** — **배포 끝: 운영은 `4415e498565c`** 입니다(1차 시도는 §7-1). 같은 날 뒤이은 배포(`6501d3fd8e60`)와 2026-10-25 의 두 번째 후속 배포로 지금 운영은 **`dc25b0f8a920`** 입니다(§0 "후속 배포"). 남은 것은 §8. 이전 운영은 `b7c5053a45e8`(2026-10-10, [docs/72](72-model-refactor-deploy-runbook.ko.md))였습니다. 그 뒤 improve 에 커밋 260여 개가 쌓였습니다. 결제 · 쿠폰 · 아웃박스 결함 수정, 보안(신원 헤더 · Eureka 쓰기 · 보안 헤더), 주문 진행 타임라인(docs/76), 기능 플래그(docs/74), 부하 측정의 CPU 수정(docs/83), 장바구니 화면 오류 수정이 들어 있습니다.
> **대상 버전**은 이 문서가 들어간 커밋 이후의 improve 최신 커밋입니다(파이프라인 초록, 12자리 SHA). 배포 전에 §0 의 표에 적습니다.
> 되돌릴 수 없는 마이그레이션(ROLLBACK-UNSAFE)은 없습니다. 그래서 **자동 복귀가 켜진 배포**입니다. 다만 user V18 은 중복 행을 지우므로 백업은 필수입니다(§3).

## 0. 기록

| 항목 | 값 |
|---|---|
| 대상 버전(12자리) | `4415e498565c`(1차 `eeecf0344023` 은 복귀 — §7-1) |
| 리허설 날짜 · 결과 | 2026-10-24 06:42 UTC · 운영 백업 사본 03:00 UTC(783MB) · 마이그레이션 `c22cd7bb5990` — **아홉 서비스 통과**(아래) |
| 사전 점검(§2) 날짜 | 2026-10-24 운영 — 출발 버전 그대로, 장바구니 · 배너 · 기획전 0, `pg_trgm` 없음(V21 이 만든다) |
| 백업 폴더(§3) | `~/backups/pre-eeecf0344023-20261024T070609Z` — 5.0G(물리 · 논리 · Mongo `chat` 7 · `migration_marker` 1 · `auto_sequence` 1) |
| 배포 시작 · 끝 · 걸린 시간 | 1차 2026-10-24 `eeecf0344023` — 스모크 3번(429 미발생)으로 자동 복귀(§7-1) · 2차 ~08:25 UTC `4415e498565c` — 끝(자동 복귀 없음) |
| 스모크 | 2차 통과 — 게이트웨이 유입 제한 데우기 941ms |
| 배포 직후 · 확인(§5 · §6) | 아웃박스 FAILED 0 · coupon-rollback · cart-empty DLT 0 · Redis 정리. 스키마 cart 7 · coupon 9 · order 28 · user 18 · catalog 22, 파드 재시작 0 · 메모리 4 ~ 501Mi, 게이트웨이 ClusterIP 8000, 보안 헤더 · font-awesome 0 · 목록 응답에 description 0 · LazyInitializationException 0, ops/05 모두 통과. payment.DLT · order.DLT 의 notification 그룹 6쌍은 배포 전(10-16 ~ 10-22) — 다시 보내지 않는다 |
| 후속 배포(2026-10-24 UTC 저녁) | `6501d3fd8e60` — 알림 NPE(§7-2) · NFKC(catalog V23, [docs/60 §14](60-i18n-audit.ko.md)) · 포트원 일본결제(리디렉션 복귀 · 구매자 이름 · 상점 정보 · 문구, [docs/49 §6](49-payment-providers.ko.md)) · 상품 목록 필터. frontend 이미지 · 번들의 채널 키 · 상점 정보 확인, `/browse` 정상, payment.DLT · order.DLT 8건 그대로(최근 10:32 UTC — 배포 전) · coupon-rollback · cart-empty 0. 배포 뒤 Stripe 새 주문 → "주문 접수" 메일 도착 · payment.DLT 8건 그대로(알림 NPE 수정 확인). 포트원 카드 결제는 결제창이 이니시스 테스트 서버(`devcbt.inicis.com/cbtauth`)로 넘어간 뒤 연결 시간 초과 — PC · 운영 인스턴스 모두 `curl` 15초 초과라 이니시스 쪽(포트원에 문의) |
| 후속 배포 2(2026-10-25 UTC 아침) | `dc25b0f8a920` — 포트원 `customData` 두 번 감쌈(order · frontend) · 체크아웃에서 포트원 숨김(`HIDDEN_METHODS`, 이니시스 회신까지) · 색상을 바꿔도 사이즈 유지 · 포트원 전화번호 숫자만([docs/49 §6](49-payment-providers.ko.md)). 마이그레이션 없음, 253초. 스모크 통과(429 11회), order-service · frontend 이미지 `dc25b0f8a920`, 체크아웃에 포트원 없음 · 색상을 바꿔도 사이즈 그대로, payment.DLT · order.DLT 8건 그대로(최근 2026-10-24 10:32 UTC — 배포 전). 되돌리기 대상 `6501d3fd8e60` |

## 1. 무엇이 바뀌나

### 1-1. 스키마 — 모두 더하기만 한다(열 · 표 · 인덱스)

| 서비스 · DB | 마이그레이션 | 하는 일 | 되돌리기 |
|---|---|---|---|
| cart · `cartdb` | V6 · V7 | 처리 원장 `cart.processed_event`(장바구니 비우기 멱등) · `processed_at` 인덱스 | 이미지만 |
| catalog · `productdb` | V19 ~ V22 | `processed_at` 인덱스, `product(category_id, product_id)`, **`pg_trgm` 확장** + 살아 있는 상품 부분 인덱스 둘, 외래 키 열 인덱스 넷 | 이미지만 |
| coupon · `coupondb` | V6 ~ V9 | `used_order_id` · `payment_order_id` · `payment_until`(널 허용 열), `(user_id, status)` 인덱스 · 겹치던 `idx_coupon` 삭제, `processed_at` 인덱스 | 이미지만 |
| order · `orderdb` | V26 ~ V28 | `create_key`(멱등 키) + 부분 유니크, `payment_attempt` · `payment_attempt_until`, `order_product(order_id)` 인덱스 | 이미지만 |
| user · `userdb` | V17 · V18 | `scheduler_lock` 표, **리뷰 · 찜 중복 정리(행 삭제)** + 유니크 | 이미지만(지운 행은 백업에서) |

chat · pay · notification · delivery 의 스키마는 그대로입니다. 마지막 마이그레이션 커밋은 2026-10-20 이고, 2026-10-21 리허설(운영 사본)에서 아홉 서비스 모두 통과했습니다. 그때 catalog V18 → V22 가 19초, user V18 의 중복은 0 이었습니다.

### 1-2. 서비스 사이의 계약 — 순서가 있지만 ops/28 은 보장하지 않는다

`ops/28` 은 1단계에서 게이트웨이를 뺀 **모든 서비스를 한꺼번에** 올리고, 2단계에서 게이트웨이를 올립니다. 그래서 "coupon · cart 를 order 보다 먼저" 는 지켜지지 않습니다. 단일 노드라 롤링 창은 몇 분이고, 그 사이 생길 수 있는 일은 아래와 같습니다. **배포 직후(§5) 확인으로 대신합니다.**

| 바뀐 것 | 창 안에서 생길 수 있는 일 | 배포 직후 할 일 |
|---|---|---|
| `coupon-rollback` Avro v2(orderId) | 옛 coupon 파드가 v2 를 못 읽어 `coupon-rollback.DLT` 로 | DLT 를 원래 토픽으로 다시 보낸다(§5-2) |
| `cart-empty` Avro v3(orderId) | 옛 cart 파드가 못 읽어 `cart-empty.DLT` 로 | 같다 |
| order 아웃박스의 새 행 종류 `product-update` · `cart-empty` · `order-status` | 옛 order 파드의 릴레이가 모르는 종류라 FAILED | `STARTED` 로 되돌린다(§5-1) |
| 쿠폰 결제 잡기 `/api/coupon/internal/payment-claim` | 옛 coupon 이 404 → order 는 예전처럼 통과 | 없음 |
| 쿠폰 견적 본문(장바구니) | 옛 coupon 은 본문을 무시하고 예전처럼 읽는다 | 없음 |
| 쿠폰 `EXPIRED` 결과 | 옛 order 가 몰라 "쿠폰을 확인하지 못해" 로 막는다(잠깐) | 없음 |
| config-server 의 Bus 제거 + 매니페스트의 버스 끄기 env 삭제 | 옛 이미지에 새 매니페스트면 health DOWN — 이미지와 매니페스트가 같은 배포라 생기지 않는다 | 없음 |

### 1-3. 동작이 바뀌는 것(사용자 · 운영자가 알아챌 수 있는 것)

- **장바구니**: 장바구니가 없는 사용자의 오류 화면이 사라집니다.
- **로그인 · 계정**
  - 관리자 잠금 · 만료가 비밀번호와 소셜 로그인도 막습니다.
  - 2FA 켜기는 최근 로그인 10분 안에만 됩니다.
  - 로그인 실패 잠금은 Redis 로 옮겼습니다. 지금 잠긴 계정은 배포로 풀립니다.
  - 알림 설정 변경이 처음으로 DB 에 저장됩니다. 예전에 바꾼 줄 알던 설정은 기본값으로 보입니다.
- **가입 확인 메일 링크 · 소셜 로그인 시작**: 지금은 404 이고, 배포로 고쳐집니다.
- **주문 · 결제**
  - 이미 결제된 주문의 두 번째 결제를 거절합니다.
  - 주문당 결제창은 하나만 열립니다(다른 탭은 10분 동안 거절).
  - 무통장 입금 대기는 고객당 3건까지입니다.
  - 체크아웃 금액은 catalog 의 지금 값으로 다시 맞춥니다.
- **상품 목록 응답**: 카드 필드만 실립니다(설명 등은 상세에만). 첫 화면 구성은 파드 메모리에 30초 머뭅니다. 목록 · 검색 카드의 별점은 캐시 만료(30 · 60분)까지 옛 값입니다.
- **프론트엔드**: 보안 헤더가 붙습니다(CSP 는 Report-Only). Font Awesome CDN 을 뺐습니다.
- **로그**
  - 서비스 메서드마다의 `executed in N ms` 줄과 게이트웨이의 요청 경로 INFO 줄이 사라집니다.
  - JVM 기동 때 스토어 비밀번호가 로그에 찍히지 않습니다(`JAVA_TOOL_OPTIONS` 에서 뺐다).
- **메모리**: JVM 최대 힙이 컨테이너 한도의 25% → 50% 입니다.

## 2. 사전 점검 — 운영 DB, 읽기 전용

개수만 봅니다. 사용자 id · 이메일은 출력하지 않습니다.

```bash
# 운영 인스턴스에서 실행한다 — 실습 클러스터가 아니다(`hostname` · `kubectl config current-context` 로 먼저 본다).
# `!` 를 쓰지 않는다 — 대화형 bash 는 큰따옴표 안의 `!~` 를 이전 명령으로 바꾼다(history expansion).
q(){ kubectl -n postgres exec -i deploy/postgres -c postgres -- psql -U postgres -d "$1" -tAX -v ON_ERROR_STOP=1 -c "$2"; }
# 지금 적용된 버전 — 출발점(cart 5 · catalog 18 · coupon 5 · order 25 · user 16)이 아니면 멈춘다.
# catalog 의 이력은 product 스키마에 있다(public 의 V5 는 옛 기록).
for db in cartdb coupondb orderdb userdb chatdb paydb notificationdb deliverydb; do
  echo "$db: $(q "$db" 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"
done
echo "productdb: $(q productdb 'SELECT max(version::int) FROM product.flyway_schema_history WHERE success')"

# cart: 주문이 막힐 장바구니 줄(수량 1 ~ 1000 밖) — 0 이어야 한다(2026-10-21 운영 0)
q cartdb "select count(*) from cart.cart_product where cart_id is not null and (qty < 1 or qty > 1000)"

# catalog: 배너 · 기획전 링크가 http(s) · '/…' 밖이면 화면이 막는다 — 0 이어야 한다
q productdb "select count(*) from content.banner where link_url is not null and link_url <> '' and not (link_url ~ '^(https?://|/)')"
q productdb "select count(*) from content.event  where link_url is not null and link_url <> '' and not (link_url ~ '^(https?://|/)')"

# catalog: pg_trgm 을 DB 소유자(product)가 만들 수 있는가 — 이미 있으면 바로 끝난다
q productdb "select extname from pg_extension where extname = 'pg_trgm'"
```

**리허설(권장)**: 그 사이 운영 데이터가 늘었으니, 가장 최근 운영 백업 사본으로 한 번 더 돌립니다.
— 그 사본을 올린 PostgreSQL 에 대상 버전의 Flyway 를 돌려 아홉 서비스 통과 · ROLLBACK-UNSAFE 없음 · user V18 중복 수를 봅니다.

**리허설 기록(2026-10-24, 운영 백업 03:00 UTC)** — 그 뒤 마이그레이션 파일은 바뀌지 않았으므로 대상 버전이 `c22cd7bb5990` 보다 뒤여도 이 결과가 그대로 맞습니다.

| 서비스 · DB | 버전 | 걸린 시간 | 건수가 바뀐 표 |
|---|---|---|---|
| cart · `cartdb` | V5 → V7 | 6.2초 | `cart.processed_event` 새 표(0) |
| catalog · `productdb` | V18 → V22 | 23.9초(Flyway 17.8초 — 인덱스) | 없음(이력 표만) |
| coupon · `coupondb` | V5 → V9 | 6.2초 | 없음 |
| order · `orderdb` | V25 → V28 | 6.2초 | 없음 |
| user · `userdb` | V16 → V18 | 6.2초 | `users.scheduler_lock` 새 표(0) — **V18 이 지운 중복 0줄** |
| chat · pay · notification · delivery | 그대로 | 각 6초 | 없음 |

- ROLLBACK-UNSAFE 없음, 실패한 이력 0. 6초대는 Flyway 컨테이너 기동이 대부분입니다.
- 장바구니 없는 줄 14개(수량 0, 2026-10-21 과 같다)는 아무도 읽지 않습니다 — 지우기는 선택.
- productdb 의 `public.flyway_schema_history`(V5)는 옛 기록입니다 — Flyway 는 `product` 의 것만 봅니다.

## 3. 배포 전 준비

### 3-1. 백업 — 필수

user V18 이 중복 리뷰 · 찜 행을 지웁니다. 매일 백업(03:00 UTC)과 별도로, 배포 직전에 하나 더 뜹니다. 명령은 [docs/72 §3](72-model-refactor-deploy-runbook.ko.md#3-백업--필수) 그대로입니다(물리 · 논리 · Mongo). 폴더는 `~/backups/pre-<대상 버전>-<시각>` 으로 하고, §0 에 적습니다.

### 3-2. 토픽 — `ops/02`

```bash
./ops/02-create-dlt-topics.sh
```
- `order-timeline` 을 **컴팩션**으로 만듭니다(docs/76 §3-1). 안 만들면 프로젝션이 delete 정책으로 자동 생성해, 보존 기간 뒤 타임라인이 사라집니다.
- saga-projection 의 입력 토픽(`order` · `payment` · `order-status`)이 없으면 만듭니다.
- 비밀이 실리는 `outgoing-mail` 에는 짧은 보존을 겁니다.

### 3-3. 서비스 신원 — `ops/38`

```bash
./ops/38-issue-service-identities.sh --only gateway-server   # 신원 헤더는 게이트웨이 CN 에서만(docs/57 §16)
./ops/38-issue-service-identities.sh --only config-server    # config-server 신원이 새로 생겼다 — 없으면 ops/28 이 멈춘다
```
(cert-manager 를 쓰면 ops/39.)

### 3-4. 지킬 것

- **대상 버전의 이미지가 GHCR 에 있어야 합니다.** CI 가 그 커밋의 파이프라인을 초록으로 끝냈는지 봅니다 — `ops/28` 도 첫 단계에서 이미지가 있는지 확인하고, 없으면 클러스터를 건드리기 전에 멈춥니다.
- 배포 중에는 관리자 작업(상품 · 쿠폰 · 공지 편집)을 멈춥니다. 옛 파드와 새 파드가 섞여 있습니다.
- 디스크 여유를 확인합니다. 전 서비스의 새 이미지를 받습니다: `df -h /`

## 4. 배포

인스턴스에서 손으로 합니다(운영 배포 러너 `oci-ampere` 는 아직 없습니다 — docs/72 §4-2).

```bash
cd ~/ecommerce-msa
git fetch origin main
git switch --detach <대상 버전>          # 매니페스트도 그 커밋의 것으로
./ops/28-deploy.sh --report              # 지난 배포의 환경 이름(local)과 직전 버전(b7c5053a45e8) 확인
./ops/28-deploy.sh local <대상 버전> --dry-run
./ops/28-deploy.sh local <대상 버전>
```

- 1단계에서 게이트웨이를 뺀 서비스가 다 뜨는 동안, 각 서비스가 자기 마이그레이션을 적용합니다. catalog 의 인덱스 생성은 운영 상품 수에서 수 초입니다.
- 2단계에서 게이트웨이를 올리고, 스모크(`ops/06`)를 돌립니다. 스모크가 실패하면 `b7c5053a45e8` 로 **자동 복귀**합니다(§7).
- 게이트웨이 Service 가 LoadBalancer → ClusterIP 로 바뀝니다(B-85). 바깥은 터널로만 들어옵니다.

## 5. 배포 직후 — 10분 안에

### 5-1. 아웃박스 — 옛 릴레이가 FAILED 로 찍은 새 종류의 행

(`q` 는 §2 의 함수입니다.)

```bash
q orderdb "SELECT event_type, count(*) FROM orders.payment_outbox_event WHERE outbox_status = 'FAILED' GROUP BY 1"
q orderdb "UPDATE orders.payment_outbox_event SET outbox_status = 'STARTED'
            WHERE event_type IN ('product-update', 'cart-empty', 'order-status') AND outbox_status = 'FAILED'"
```
다시 보내도 안전합니다. 받는 쪽(catalog · cart · saga-projection)이 eventId 로 중복을 거릅니다. 위 세 종류 밖의 FAILED 는 이번 배포와 무관합니다 — 그 행은 `OrderOutboxRowFailed` 경보로 따로 봅니다.

### 5-2. DLT — 롤링 창에 옛 파드가 못 읽은 메시지

```bash
for t in coupon-rollback.DLT cart-empty.DLT payment.DLT order.DLT; do ./ops/56-dlt-replay.sh list "$t"; done
# 파티션마다 최근 건 — 자리 · 키 · 원래 자리 · 실패한 그룹 · 예외. 이번 배포 시각의 것만 고른다.
./ops/56-dlt-replay.sh replay coupon-rollback.DLT <파티션> <오프셋|시작-끝>           # 무엇을 보낼지 보이기만
APPLY=1 ./ops/56-dlt-replay.sh replay coupon-rollback.DLT <파티션> <오프셋|시작-끝>   # 보낸다 — 바이트 그대로 · 원래 파티션
```
콘솔 도구로 다시 보내지 않습니다(Avro 가 깨진다). 원래 토픽의 **모든 컨슈머 그룹**이 다시 받습니다. 그래서 다시 보내는 것은 받는 쪽이 멱등인 `coupon-rollback` · `cart-empty`(처리 원장 · eventId)만입니다. `payment.DLT` · `order.DLT` 는 다시 보내지 않고 아래처럼 원인별로 처리합니다(결제 응답을 다시 보내면 다른 그룹이 한 번 더 처리할 수 있다 — ops/56 주석).
- `payment.DLT` 에 delivery 그룹 레코드가 있으면, 그 주문은 배송이 만들어지지 않은 것입니다. 관리자 배송 복구로 채웁니다(PAID · PROCESSING 주문만).
- `order.DLT`(결제 요청)에 레코드가 있으면, 그 주문은 결제가 시작되지 않았거나 결제됐는데 주문이 결제 전입니다. 경보 `OrderPaymentUnconfirmed` · order 로그 "결제 대사" 로 주문을 찾아 결제 제공자 콘솔과 맞춥니다.

### 5-3. Redis — 옛 영구 상품 캐시

products 캐시는 이제 30분 만료 · 키 접두사입니다. 옛 영구 키를 한 번 지웁니다(다음 조회가 다시 채운다).
```bash
kubectl -n redis exec deploy/redis -- sh -c 'export REDISCLI_AUTH="$REDIS_PASSWORD";
  redis-cli --scan --pattern "products::*" | xargs -r -n 200 redis-cli del >/dev/null; echo done'
```

## 6. 배포 후 확인

### 6-1. 스키마
```bash
for db in cartdb coupondb orderdb userdb; do echo "$db: $(q "$db" 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"; done
echo "productdb: $(q productdb 'SELECT max(version::int) FROM product.flyway_schema_history WHERE success')"
# 기대: cart 7 · coupon 9 · order 28 · user 18 · catalog 22 (chat · pay · notification · delivery 는 그대로)
```

### 6-2. 화면 — 한 바퀴

| 확인 | 기대 |
|---|---|
| 장바구니가 없는 계정으로 장바구니 열기 → 상품 담기 → 마지막 줄 빼기 | 오류 화면 없이 "비어 있음" |
| `https://www.ysyproject.com/user-service/registrationConfirm?token=x` | 실패 화면으로 리다이렉트(404 아님) |
| 소셜 로그인 시작 | 제공자 화면으로 간다 |
| 주문 → 카드(테스트) · PayPal(샌드박스) · 무통장 | 결제 완료, 주문 상세에 "진행 기록"(docs/76) |
| 위 결제 동안 브라우저 개발자 도구 콘솔 | CSP 위반 보고가 없다(있으면 그 출처를 정책에 더한다 — §8) |
| 홈 · 검색 · 기획전 카드 | 사진 · 가격 · 할인 · 재고 배지 · 색 견본이 예전처럼 |

### 6-3. 명령으로

```bash
kubectl -n ecommerce get pods                                         # 재시작 · OOMKilled 없음
kubectl -n ecommerce top pods                                         # 힙 50% — 한도에 닿는 서비스는 JVM_MAX_RAM_PERCENTAGE(예: 45)
kubectl -n ecommerce get svc gateway-server                           # ClusterIP · 포트 8000 만
curl -sI https://www.ysyproject.com/ | grep -iE 'x-frame-options|content-security-policy-report-only|strict-transport'
curl -s https://www.ysyproject.com/ | grep -c font-awesome            # 0
curl -s 'https://www.ysyproject.com/catalog-service/api/search?page=1&pageSize=1' | grep -c '"description"'   # 0 — 목록은 카드 필드만
for d in order-service cart-service; do kubectl -n ecommerce logs deploy/$d --since=30m | grep -c LazyInitializationException; done   # 0
kubectl -n ecommerce logs deploy/catalog-service --since=30m --tail=-1 | grep -cE '검색 질의 시간 초과|검색 연결 소켓 읽기 시간 초과'    # 0
./ops/05-check-observability.sh                                       # 서비스 지표 11개 up · node-textfile up
```

## 7. 실패하면

- **스모크 실패**: `ops/28` 이 `b7c5053a45e8` 로 자동 복귀합니다. 마이그레이션은 모두 더하기만 해서 옛 이미지가 그대로 뜹니다(리허설로 확인). 복귀 뒤에는 새 종류의 아웃박스 행(§5-1)이 옛 릴레이에 의해 FAILED 가 됩니다 — 다시 올릴 때 같은 UPDATE 를 합니다.
- **한 서비스만 뜨지 않음**(롤링이 멈춤): 옛 파드가 계속 돕니다. `ops/28` 이 출력한 기동 오류를 보고, 고친 이미지로 다시 배포합니다.
- **user V18 로 지운 행이 필요함**: §3-1 의 논리 덤프에서 그 표만 되살립니다.

### 7-1. 1차 시도(2026-10-24) — 스모크 3번 "429 미발생" 으로 자동 복귀

- **무엇**: 이미지 · 마이그레이션 · 2단계 게이트웨이까지 뜬 뒤, 스모크 3번(같은 손님의 동시 70발 → 429 가 하나라도)이 429 를 받지 못했다. 나머지(게이트웨이 health · 폴백 503 · 카탈로그 200)는 통과.
- **왜**: 이번 릴리스에서 게이트웨이의 Redis 명령 타임아웃이 60초(Lettuce 기본) → 500ms 가 됐다(B-84). 넘긴 유입 제한 명령은 **통과**(fail-open)다. 막 뜬 게이트웨이의 첫 명령들은 연결 · AUTH · 스크립트 적재(EVALSHA → NOSCRIPT → EVAL)를 함께 치르고, 전 서비스가 한꺼번에 뜬 단일 노드에서 그것이 상한을 넘었다. 예전에는 60초를 기다려 429 가 났다. 재현(실제 게이트웨이 + 실제 Redis, 개발 기계): 데우기 없이는 기동 직후 첫 70발 중 **57발이 통과**(버스트 40).
- **고친 것**: 게이트웨이가 readiness 전에 유입 제한 스크립트를 한 번 실행한다(`RateLimiterWarmup` — 손님과 겹치지 않는 키, Redis 에 닿지 못하면 경고만 하고 뜬다). 같은 재현에서 첫 70발 중 정확히 40발 통과 · 30발 429. 스모크 3번은 429 가 없으면 3초 뒤 한 번만 다시 쏘고, 결과마다 코드별 수를 남긴다(제한이 빠진 게이트웨이는 다시 쏴도 429 가 없다). 테스트 `RateLimiterLiveTest`(컨테이너 — 데우기를 빼면 실패).
- **같은 일은 손님에게도**: 배포 · 재시작 직후 몇 초는 유입 제한이 없었다. 운영 b7c5053 은 반대로 Redis 가 잠깐 끊기면 모든 요청이 최대 60초 멈춘다.
- **다시 배포할 때**: 대상 버전은 이 수정이 들어간 커밋(CI 초록). §2 · §3 은 다시 하지 않아도 된다(마이그레이션 파일 그대로 · 백업은 오늘) — §4 부터. 복귀가 남긴 아웃박스 FAILED 행은 §5-1 의 UPDATE 가 함께 처리한다.

### 7-2. 배포 뒤 발견(2026-10-24) — 주문 · 결제 안내 알림이 만들어지지 않았다

- **무엇**: `order.DLT` · `payment.DLT` 에 notification 그룹(`notification-order` · `notification-payment`)이 쌍으로 쌓였다 — 10-16 ~ 10-22 에 6쌍(b7c5053), 배포 뒤 Stripe 주문 2건으로 8쌍. `ops/56 list` 의 원인 사슬: `BatchListenerFailedException → NullPointerException`.
- **왜**: 알림 메일 제목을 지역 언어로 바꾸는 줄이 `Locale.forLanguageTag(locale)` 였다. 지역(`notification_preference.region`)은 **전화번호를 인증해야** 채워져서, 인증하지 않은 사용자는 `locale` 이 null → NPE. 그 줄이 메일 실패를 막는 try 밖이라 **알림 생성 전체**가 실패했다(메일 수신을 켠 사용자의 주문 · 결제 · 배송 · 쿠폰 · 재입고 안내 모두).
- **고친 것**: 지역을 모르면 제목은 그대로 · 메일 언어는 기본값, 메일 준비 전부를 try 안으로(알림은 메일과 상관없이 만든다), 경고 로그에 예외 메시지 대신 클래스 이름(`NotificationMailLocaleTest` — 되돌리면 실패). 다음 배포에 들어간다.
- **쌓인 DLT**: 다시 보내지 않는다(원래 토픽의 모든 그룹이 다시 받는다). 놓친 안내는 주문 8건 — 필요하면 관리자 공지로.

## 8. 며칠 뒤 — 관찰을 거친 다음 단계

| 언제 | 할 일 | 근거 |
|---|---|---|
| 배포 다음 날 | `SINCE=24h ./ops/37-check-mtls.sh` 의 ①-3 이 0 이면 `INTERNAL_AUTH_IDENTITY_MODE=enforce` → `ops/28 --config-only` + 백엔드 재시작 → ②-2 가 403 | docs/57 §16 "신원 헤더" |
| 배포 다음 날 | `kubectl -n ecommerce logs deploy/eureka-server --since=24h \| grep -c '\[eureka write\]'` 가 0 이면 `EUREKA_WRITE_AUTH_MODE=enforce` → `--config-only --restart eureka-server` | docs/57 §16 "Eureka 쓰기" |
| 결제 한 바퀴 뒤 | CSP 위반이 없으면 frontend nginx 의 `Content-Security-Policy-Report-Only` → `Content-Security-Policy`(다음 frontend 이미지) — ✅ 2026-10-25 콘솔 위반 0(카드 · PayPal · 무통장), 코드 반영 · 다음 frontend 배포에 | docs/57 §19 |
| 배포가 안정된 뒤 | `LOCAL_KEY_STORE_PASSWORD` 교체 — `./ops/59-rotate-store-password.sh` check → apply(스토어 비밀번호만 · 키 그대로 · 하나씩 재시작) → 하루 뒤 cleanup. 옛 비밀번호가 지난 로그에 남아 있다 | docs/57 §16 "스토어 비밀번호" |
| 1주일쯤 뒤 | contract 릴리스: order `order_address.order_id` · `orders.tax_price`, user `lock_expires_at` · `failed_login_attempts` 열 삭제(ROLLBACK-UNSAFE 선언) | docs/72 §7 |
| 배포 뒤 한 번 | 운영 b7c5053 기간에 경보 없이 지나갔을 수 있는 "파드마다 첫 건" — 로그의 '이중 결제' · DLT 토픽을 훑어본다 | B-101 |

배포가 끝나면 남은 일 목록의 "다음 배포에 함께" 항목들을 "적용 끝" 으로 옮기고, 이 문서 맨 위 상태에 결과를 적습니다.
