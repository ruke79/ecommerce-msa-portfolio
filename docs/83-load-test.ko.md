# 83. 부하 시험 — k6 시나리오(조회 · 주문 · 급증)

> **상태(2026-10-17)** — 시나리오 · 실행기(`ops/53`) · 가드는 들어갔고, 가짜 게이트웨이(https · 테스트 CA)에서 네 시나리오가 끝까지 돈다.
> **실습 클러스터에서의 측정은 아직이다** — 결과는 §5 표에 채운다.

[docs/19](19-local-performance-tuning.ko.md) 의 `ops/14` 는 curl 로 GET 경로 하나를 친다. 그것으로는 로그인 → 장바구니 → 주문 같은
쓰기 경로(재고 예약 gRPC · 주문 아웃박스 · Kafka · 장바구니 캐시)를 잴 수 없다. 여기서는 화면과 **같은 주소 · 같은 헤더**로 손님의
흐름을 k6 로 돌리고, 결과와 그때의 조건(이미지 판 · 레플리카 · 노드 · HPA · 유입 제한)을 한 폴더에 남긴다.

## 1. 어디서 재나 — 실습 클러스터

- **운영(OCI Ampere)에서는 재지 않는다.** Cloudflare 터널과 인터넷 구간의 지연이 섞여 코드가 아니라 선을 재고, 실제 손님의 사이트를
  느리게 한다. `ops/53` 은 `GATEWAY` 가 `ysyproject.com` 이면 거절한다.
- 실습 클러스터(데스크톱 한 대 위 VM — 서버 · 에이전트 · 데이터 노드)의 `https://shop.lab` 에 건다.
  부하는 `loadgen` VM 에서 낸다 — 같은 VM 에서 내면 k6 가 서버의 CPU 를 먹는다.
- **절대 수치는 이 클러스터에서만 뜻이 있다.** 결과를 옮길 때는 "실습 클러스터(데스크톱 한 대 위 VM) 기준" 을 함께 적는다. 뜻이 있는 것은
  같은 조건에서의 **전후 비교**와 **어디서 꺾이는가**다(docs/19 §0).

## 2. 시나리오 — `ops/k6/`

| 시나리오 | 한 반복 | 부하 모양(기본) | 임계값 |
|---|---|---|---|
| `browse` | 비회원: 홈 · 배너 → 카테고리 목록 1 · 2쪽(필터 포함) → 상품 상세 | 도착률 계단 25% → 50% → 100%, 최고 `RATE=100`/초, 단계마다 2분 | 실패 < 1%, p95 홈 800ms · 목록 1200ms · 상세 800ms |
| `order` | 로그인한 데모 사용자: 상품 상세 → 담기 정보 → 담기 → 체크아웃 저장 → 체크아웃 · 배송지 → 주문(Idempotency-Key) → 주문 상세 → 무통장 입금 안내 → 입금 취소 · 줄 빼기 | 30% → 100%, 최고 `RATE=min(10, USERS)`/초 | 실패 < 1%, 주문 p95 2s, 담기 p95 1s, 한 번 구매 전체 p95 6s |
| `spike` | `browse` 와 같다 | 평소(최고의 10%) 2분 → 20초 만에 최고(`RATE=200`) → 3분 유지 → 빠짐 → 3분 | 실패 < 2%, 상세 p99 3s |
| `smoke` | 조회 한 번 · 주문 한 번(계정 하나) | — | 검사 100% · 429 없음 |
| `mixed` | `browse` 그대로 + 로그인한 데모 사용자가 초당 `REVIEW_RATE`(기본 1) 번 리뷰 쓰기(화면과 같은 multipart, `review_add`) | `browse` 와 같은 계단 · 리뷰는 그동안 내내 일정하게 | `browse` 의 것 + 리뷰 p95 3s |
| `contention` | 로그인한 데모 사용자: 담기 → 체크아웃(회차 전) → **같은 순간에** 주문 → 입금 안내 → 함께 취소 | `USERS`(50)명이 재고 `STOCK` 개인 한 SKU 에 `ROUNDS`(3)번 몰린다 | 받아진 주문 ≤ 재고(회차마다) · 그 밖의 거절 · 오류 0 · 실패 < 1%(재고 부족 400 제외) |

- **도착률(arrival-rate)로 정한다** — VU 수가 아니라 손님이 오는 속도. VU 고정(닫힌 모델)은 서버가 느려지면 부하도 같이 줄어 병목을 숨긴다.
- **응답이 JSON 인지 늘 본다**(`not_json`). 데모 인그레스가 API 를 프론트로 보내던 때(2026-10-17 전)는 모든 API 가
  `index.html` 200 이라 상태 코드만 보는 검사는 전부 성공이었다. 시작 전(setup)에 홈이 JSON 이 아니면 그 이유를 말하고 멈춘다.
- **429 는 따로 센다**(`rate_limited`) — 제한이 일한 것인지 서버가 못 버틴 것인지 나눠 읽는다.
- **주문의 끝에 입금을 취소한다.** 입금 대기 주문은 손님당 3건까지(`bank-transfer.max-pending-per-customer`)이고 재고를 49시간 잡는다 —
  그대로 두면 같은 계정의 네 번째 반복부터 400 이고, 몇 번 돌리면 재고가 바닥난다. 한 번만 보려면 `KEEP_ORDERS=1`.
- **로그인은 시작 전에 천천히.** 로그인 유입 제한은 IP 당 초당 1 · 버스트 10 이고 바꿀 수 없다 — `setup()` 이 계정마다 한 번, 10명 뒤로는
  1.1초 간격으로 로그인한다(100명이면 약 2분). 429 를 받으면 기다렸다가 같은 계정을 다시.
- **계정 하나는 VU 하나만** 쓴다(`maxVUs = USERS`). 리프레시 토큰이 회전해서 두 VU 가 같은 토큰으로 갱신하면 늦은 쪽이 그 로그인을 끝낸다.
  액세스 토큰은 15분 — 13분이 지나면 그 VU 가 갱신한다.
- **SKU 가 하나인 상품만 담는다.** 담기 정보(catalog)는 SKU 를 순서가 보장되지 않는 Set 에서 `skip(style)` 로, 장바구니(cart)는
  `skus.get(style)` 로 고른다 — 둘이 같은 것을 가리키는지 확실한 것은 SKU 가 하나일 때뿐이다.

### 2.1 데이터 규모 × 도착률

트래픽(도착률)과 데이터 양은 **다른 축**이다. 상품이 늘면 인덱스 크기 · PostgreSQL 캐시 적중 · 목록의 정렬과 개수 세기 · 이름 검색
(`LIKE '%…%'`, docs/19 §6.1 의 전체 스캔)이 바뀐다. 그래서 **같은 도착률로 상품 수만 바꿔** p95 를 엔드포인트별로 비교한다.

| 상품 수 | 표 + 인덱스(상품당 2,108 B, docs/19 §5.1) | 적재(실습 VM 추정) | 볼 것 |
|---|---|---|---|
| 1만 | 약 0.02 GiB | 1분 미만 | 기준선 |
| 100만 | 약 2 GiB | 20 ~ 30분 | 메모리 안쪽 끝(`shared_buffers` 1.5 GB · 캐시 4 GB · 파드 6 Gi, 데모 인프라) |
| 1,000만 | 약 20 GiB | 3 ~ 5시간 | **메모리 밖** — 디스크 읽기가 섞인다. 깊은 쪽 · 이름 검색이 어디서 꺾이나 |
| ~~1억~~ | 약 196 GiB(WAL 여유까지 디스크 약 300 GB) | 28 ~ 46시간 | 넣을 수 없다 — data-node 디스크 97 G |

**데이터 규모 모드**(`PRODUCTS` 를 주면 — browse · spike):

- 상세는 ops/44 로 넣은 범위(`PRODUCT_ID_OFFSET`+1 ~ +`PRODUCTS`, 기본 오프셋 1000000 = ops/44 의 기본값)의 **무작위 상품 id**(`product_cold`).
  홈에 나온 수십 개만 읽으면 늘 캐시(Redis 30분 · PostgreSQL 버퍼)에서 나와 데이터 크기가 보이지 않는다.
- 목록은 1 ~ `DEEP_PAGES`(기본 50)쪽 중 무작위(`search_deep`) — OFFSET 이 커질수록 비용이 는다.
- `NAME_SEARCH`(기본 0.2) 비율로 이름 검색(`name_search`) — 검색어는 생성기 상품 이름의 명사다(결과 0건을 재지 않게, 가드).
- 시작 전에 범위의 처음 · 끝 상품이 있는지 본다 — 없으면 "느리다" 가 아니라 "없다" 를 재게 되어 멈춘다.

```bash
for n in 10000 1000000 10000000; do
  PRODUCTS=$n PLACEHOLDER_IMAGE=/logo512.png ./ops/44-bulk-catalog.sh load   # 이어서 넣는다(--resume)
  PRODUCTS=$n RATE=50 ./ops/53-load-k6.sh browse                                      # 같은 도착률
done
```

**캐시 밖을 재는 선택지 — `SPREAD=1`**(2026-10-19, `ops/k6/spread.js`). 위 모드의 검색 조합(카테고리 × 50쪽 · 이름 검색어 16개)은 유한해서
몇 분 뒤에는 `search_cache`(60분)에서 나온다 — 100만 회차(§5)에서 검색의 데이터 비용이 보인 것은 첫 1분 반뿐이었다. `SPREAD=1` 은:

- 깊은 쪽을 2 ~ 1000쪽으로(`DEEP_PAGES` 기본 1000 — 카테고리 6개면 약 6,000 조합), 이름 검색을 1 ~ `NAME_PAGES`(기본 50)쪽으로 —
  첫 쪽만 필터 메뉴를 받는다(화면과 같다). 쪽마다 개수 세기(`count`)가 함께 돈다.
- `FILTER_SEARCH`(기본 0)를 주면 그 비율로 **필터 검색**(`filter_search`): 같은 카테고리에 가격대(하한 0 ~ 200 · 폭 20 ~ 500) + 절반은 색상 ·
  30% 는 사이즈 — 값은 그 반복의 첫 쪽 응답의 필터 메뉴에서 고른다. 조합이 거의 겹치지 않는다.
  ⚠️ 2026-10-19 까지 가격 · 색상 · 사이즈 필터는 **카테고리와 상관없이** 맞는 상품 id 를 모두 앱으로 읽어 `IN (…)` 으로 보냈다(가격대는
  하한 이상인 사이즈 줄을 모두 읽어 할인가를 앱에서 계산) — 첫 회차(`104422Z`, §5)에서 catalog 가 힙 부족으로 쓰러졌고, 이제 DB 안의 EXISTS 다
  (`SearchService.skuFilter`). 그래도 기본은 끄고 `SPREAD` 회차와 따로 짧게 돌린다 — 필터 검색 하나가 경로 전체를 무너뜨린 적이 있다.
- `PRODUCTS` 없이는 시작하지 않는다. 결과 표의 상품 칸은 `1000000·넓힘` — 캐시를 잰 회차와 견주지 않는다.
- 회차마다 먼저 `ops/44 flush-cache` — 지난 회차의 조합이 60분 동안 남는다. k6 는 시작할 때 조합 수를 찍는다(요청 수보다 작으면 대부분 캐시).

```bash
PRODUCTS=1000000 RATE=50 SPREAD=1 ./ops/53-load-k6.sh browse                          # 검색의 캐시 밖 비용
PRODUCTS=1000000 RATE=10 SPREAD=1 FILTER_SEARCH=0.3 STAGES=1m:10,2m:10,30s:0 ./ops/53-load-k6.sh browse   # 필터 검색만 짧게
```

- 상품 수를 키울 때는 지우지 않고 이어서 넣는다(ops/44 는 같은 범위의 다음 id 부터). 1,000만은 디스크 여유를 먼저 본다(ops/44 가 점검). ⚠️ 큰 적재에는 `FORCE=1` 을 쓰지 않는다 — ops/44 의 `FORCE` 는 백업 시간대(02 ~ 06 UTC)뿐 아니라 **디스크 점검까지** 넘긴다. 그 시간대를 피해서 돌린다.
- **사용자 수는 이 축이 아니다.** 가입자 수(users 표의 크기)는 로그인(이메일 인덱스 한 번)의 속도를 거의 바꾸지 않는다 — 크기가
  영향을 주는 곳은 관리자 사용자 검색 · 등급 배치 · 쿠폰 일괄 발급 같은 배치다. order 의 `USERS` 는 **동시 사용자 풀**이다: 필요한
  수는 "주문 도착률 × 한 번 구매 시간"(초당 20 × 약 6초 ≈ 120) — 200 이면 초당 20, 2,000 이면 초당 100 까지. 로그인이 초당 1 이라
  2,000명은 준비만 약 37분, 2만 명은 약 6시간이다.

### 2.2 쓰기가 섞인 조회 — `mixed`

지금까지의 browse 는 **읽기만** 했다. 실제 사이트에서는 그 사이에 쓰기가 들어오고, 그중 하나가 캐시를 크게 비운다: 리뷰를 쓰거나 고치면
user-service 가 그 상품의 평균 별점을 catalog 에 보내고(`ReviewService.publishRating` → `PUT /api/product/rating`), catalog 의
`ProductService.updateRating` 은 `search_cache` 와 필터 메뉴 캐시(`colors` · `sizes` · `details` · `brands_cache`)를 **통째로** 비웠다
(`@CacheEvict(allEntries = true)`). 상품 하나의 별점이 바뀌어도 모든 카테고리 · 모든 쪽의 검색이 다음 요청에서 처음부터 계산됐다 —
100만 상품에서 그 한 번은 0.2 ~ 1.3초였다(§5). 매일 18:40 UTC 의 평점 대사(`ReviewRatingReconciler`)도 리뷰가 있는 상품마다 같은
호출을 한다. 🐛 2026-10-19 첫 회차에서 리뷰 초당 1건에 실패 **79.90%** — 이제 별점은 그 상품의 캐시만 맞추고(`19803606`) 같은 조건에서
**0.02%** 다(§5). mixed 는 이 경로가 다시 넓어지지 않는지 보는 회차다.

- 데모 계정은 받은 상품이 없어 리뷰가 403 이다(받은 상품만 — `review.require-purchase`, docs/57 §13). 측정하는 동안만
  `./ops/53-load-k6.sh reviews open`(user-service 에 `REVIEW_REQUIRE_PURCHASE=false`, 실습 클러스터만 · 재시작) → 끝나면 `reviews default`.
  시작할 때 리뷰를 하나 써 보고 403 이면 그 안내와 함께 멈춘다.
- 리뷰는 사람 · 상품마다 하나라 같은 조합을 다시 쓰면 고치기다 — 그때도 별점을 보낸다. 상품은 데이터 규모 모드면 ops/44 범위의 무작위
  상품, 아니면 홈의 상품. 본문은 `k6 리뷰` 로 시작한다(데모 DB 에 남는다 — 상품의 평균 별점도 바뀐다).
- 로그인은 order 와 같다(리뷰 VU 수 = `REVIEW_RATE × 5`, `USERS` 이하). 같은 계정을 두 VU 가 잡아 13분 뒤 갱신이 엇갈리면 다시 로그인한다.
- 비교는 **같은 조건의 browse 회차와** — 리뷰 쓰기만 다르게. `REVIEW_RATE` 를 0.1 · 1 · 5 로 바꿔 검색 p50 이 캐시 회차(~10ms)에서
  캐시 밖(§5 의 0.2 ~ 1.3초)으로 얼마나 끌려가는지 본다. 리뷰 쓰기 자체(`review_add`)의 응답 시간도 함께.

```bash
./ops/53-load-k6.sh reviews open                                   # 호스트(kubectl 이 있는 곳)에서
PRODUCTS=1000000 RATE=50 ./ops/53-load-k6.sh browse                # 기준 — 쓰기 없이(flush-cache 뒤)
PRODUCTS=1000000 RATE=50 REVIEW_RATE=1 ./ops/53-load-k6.sh mixed   # 같은 조건 + 리뷰 초당 1
./ops/53-load-k6.sh reviews default                                # 꼭 되돌린다
```

### 2.3 재고 경합 — `contention`

browse 는 읽기만, order 는 손님마다 홈의 여러 상품 중 하나를 사서 **같은 SKU 에 주문이 몰린 적이 없다**. 쇼핑몰에서 가장 위험한
정확성 문제는 초과 판매다 — 재고 20개에 주문 23개가 받아지면 손님 셋에게 돈을 받고 물건이 없다.

- **무엇을 보나**: 재고 예약(docs/59)은 SKU 행을 잠그고 "실물 − 살아 있는 예약" 을 센다. 같은 SKU 에 몰리면 잠금 대기가 길어지고,
  gRPC 기한(3초, `stock.grpc.deadline-ms`)을 넘으면 order 는 **예약 없이 진행한다**(fail-open — `OrderStockHold.UNAVAILABLE`,
  `order_stock_hold_total{result="unavailable"}`). 그 길이 부하 아래에서 열리는지 — 받아진 주문(입금 안내까지 간 것)이 회차마다
  재고 이하인가가 임계값이다. 재고 부족 거절(400 "재고가 부족해 …")은 기대한 결과라 실패로 세지 않는다.
- **한 회차**(`ROUNDS`, 기본 3): 준비(담기 정보 → 담기 → 체크아웃)는 회차 전에, 시작 시각에 모든 VU 가 함께 주문 → 주문 상세 →
  무통장 입금 안내, `HOLD_SECONDS`(20) 뒤 함께 입금 취소 · 줄 빼기, `ROUND_GAP_SECONDS`(10) 뒤 다음 회차. 해제 시각 뒤에 받아진 것은
  "늦음" — 앞 손님들이 돌려준 재고라 초과 판매가 아니다.
- **재고는 호스트에서 맞춘다**: `./ops/53-load-k6.sh stock set <수> [상품 id]` — SKU 하나인 상품(없으면 고른다)의 모든 사이즈 수량을
  `<수>` 로, 원래 값은 `~/.ecommerce-msa/stock/<id>.saved`(0700)에, 캐시를 비우고 실행 줄을 알려 준다. 실습 클러스터만.
  중간에 끊긴 회차의 입금 대기가 남았으면 남은 수가 그만큼 적다 — `RELEASE_HELD=1` 로 그 상품의 살아 있는 예약을 푼다.
- **읽는 법**: report 가 회차마다 "받아짐 N = 재고 · ← 초과 판매 k · (덜 팔림 k)" 로 적는다. 초과 판매나 덜 팔림이 있으면 그때의
  `order_stock_hold_total{result="unavailable"}` 증가 · catalog 의 잠금 대기(`재고 예약 거절` · 기한 초과 로그)를 본다.
- 가짜 서버(재고를 잠그지 않고 세는 것)로 시험했다: 잠그는 서버는 받아짐 19 · 20(재고 20, 해제 뒤 4건은 늦음), 잠그지 않는 서버는
  22 · 21 → 임계값을 넘었다(exit 99).

```bash
./ops/53-load-k6.sh stock set 20                                    # 호스트에서 — 실행 줄을 알려 준다
CONTENTION_PRODUCT=<id> STOCK=20 USERS=50 ./ops/53-load-k6.sh contention   # 부하 VM 에서
./ops/53-load-k6.sh stock restore <id>                              # 꼭 되돌린다
```

### 2.4 주문 이력이 큰 상태 — `ops/54-bulk-orders.sh`

order · contention 은 주문 표가 거의 빈 채로 쟀다. 데모 사용자(ops/48)마다 **끝난 주문**을 쌓아 주문 · 줄 · 배송지가 수백만 줄인
상태를 만든다(`ops/tools/generate-demo-orders.py` — psql 스크립트를 내고 ops/54 가 PostgreSQL 파드에 흘려 넣는다).

- 만드는 것: 주문(구매 확정 62% · 배송 완료 12% · 취소 12% · 환불 6% · 결제 완료 · 처리 중 · 발송), 배송지 사본(V25 — 주문이 가리킨다),
  줄 1 ~ 3개(상품 id 는 ops/44 의 생성 범위 — `PRODUCTS` · `PRODUCT_ID_OFFSET`). 입금 대기 · 결제 전은 만들지 않는다(입금 대기 한도
  3건 · 재고 예약에 걸린다). 결제 · 배송 · 쿠폰 · 재고 · 읽기 모델(saga-projection) · 아웃박스에는 아무것도 쓰지 않는다.
- id 는 예약 범위(주문 · 배송지 8,000,000,000 ~, 줄은 그 × 4 ~), `tracking_id` 는 `demo-<주문 id>` — purge 는 범위 **그리고** 그 접두사만 지운다.
  손님마다 주문 id 순 = 시간 순(내 주문 목록은 주문 id 내림차순). 다시 실행해도 같다(묶음마다 시드 고정 · ON CONFLICT).
- **실습 클러스터에서만** — 가짜 주문이 운영의 매출 · 주문 집계를 더럽힌다(ops/48 은 운영 데모에도 쓰지만 이건 거절한다).
- 실측(로컬 PG16 컨테이너): 주문 20만 · 줄 40만 46초, 209MB(주문당 약 1,100B). 기본(손님 10만 × 20 = 주문 200만)이면 약 2.2GB.
- 🐛 이 생성기로 처음 드러난 것: **주문 줄의 `order_id` 에 인덱스가 없었다** — 주문 상세의 줄 조회가 Parallel Seq Scan 27ms(줄 40만,
  줄 수에 비례), 주문 2만 개 purge 가 9분 20초(지운 주문마다 외래 키 검사가 줄 표 전체를 훑었다 — catalog V22 와 같은 결함).
  order V28 이 인덱스를 더하고 `ForeignKeyIndexMigrationTest`(order)가 인덱스 없는 외래 키를 막는다.

```bash
USERS=100000 ./ops/48-bulk-users.sh load                               # 데모 사용자(없으면)
USERS=100000 ORDERS_PER_USER=20 PRODUCTS=10000000 ./ops/54-bulk-orders.sh load   # 주문 200만
./ops/53-load-k6.sh order                                              # 부하 VM 에서 — 같은 조건으로 전후
USERS=100000 ORDERS_PER_USER=20 ./ops/54-bulk-orders.sh purge          # 끝나면
```

### 2.5 데이터 모양 — 쏠림 · 긴 이름(`SKEW` · `LONG_NAMES`)

생성기의 기본 분포는 가장 큰 대분류가 약 13%(1,000만이면 ~130만), 이름 40자 안팎이다. 실제 상점은 한 카테고리에 몰리거나 이름이 길 수
있다 — 그때 먼저 꺾이는 곳(그 카테고리의 개수 세기 · 필터 메뉴 · 깊은 쪽, 트라이그램 GIN · 목록 응답 크기)을 보려고 모양을 바꾼다.

- `SKEW=0.5` — 상품의 절반을 한 대분류에(기본은 가중치가 가장 큰 것 = books, `SKEW_CATEGORY=fashion` 처럼 고른다). 0.5 면 그 카테고리가
  약 56%(나머지 절반의 몫까지).
- `LONG_NAMES=1` — 이름에 수식어 8 ~ 12개를 더해 100 ~ 180자(name · slug 255 안). 앞부분(브랜드 · 형용사 · 명사 · 모델)은 그대로라
  browse 의 이름 검색어가 계속 맞는다.
- **기본값은 예전과 같은 상품이다** — 이어 넣기(`--resume`)는 묶음마다 같은 시드로 만든다. 옵션을 주지 않으면 난수를 더 쓰지 않는다
  (`CatalogGeneratorShapeTest` 가 기본 3,000개의 이름 · 카테고리 지문을 고정한다). 이어 넣을 때는 같은 값을 준다 — 다르면 앞뒤 묶음의 모양이 달라진다.
- 같은 상품 수의 기본 모양 회차와 견준다(쏠림은 다른 데이터라 기존 1,000만 위에 이어 넣지 말고 purge 뒤 새로).

```bash
PRODUCTS=10000000 ./ops/44-bulk-catalog.sh purge
PRODUCTS=10000000 SKEW=0.5 ./ops/44-bulk-catalog.sh load            # 한 카테고리에 ~560만
PRODUCTS=10000000 RATE=50 ./ops/53-load-k6.sh browse                 # 부하 VM 에서 — 기본 모양 회차와 같은 조건
```

### 2.6 손님 IP 여럿 · 부하 VM 여러 대 — `CLIENT_IPS` · `SPLIT`

게이트웨이의 유입 제한 키는 로그인 사용자 → `CF-Connecting-IP` → 소켓 주소다(`CustomUserKeyResolver`). 운영은 Cloudflare 엣지가
`CF-Connecting-IP` 를 실제 손님 IP 로 덮어쓰지만, 실습 클러스터에는 엣지가 없어 **모든 요청이 Traefik 파드 IP 하나로 보인다** — 부하 VM 을
늘려도 게이트웨이에게는 손님 하나다(그래서 지금까지 `limits load` 로 제한을 올려야 했다).

- `CLIENT_IPS=N` — VU 마다 시험용 대역(198.18.0.0/15, RFC 2544)의 주소를 `CF-Connecting-IP` 에 싣는다. 실습에서 운영과 같이 손님마다 버킷이
  생기므로 **제한을 올리지 않고** 기본 제한(IP 당 초당 20 · 버스트 40)이 손님 분포에서 어떻게 작동하는지 잰다. 이때 시작 전 제한 확인
  (`limitsRaisedOrFail`)은 건너뛴다 — 기본 제한 그대로가 목적이다. 운영에서는 엣지가 덮어써 아무 일도 없다(ops/53 은 운영 주소를 거절한다).
- `SPLIT=i/n` — 부하 VM 한 대의 CPU 가 모자랄 때. 전체 `RATE` 를 n 으로 나눠 VM 마다 `RATE/n`, 데모 계정(`ID_OFFSET + (i−1) × USERS`) ·
  손님 IP(`(i−1) × CLIENT_IPS` 부터)를 겹치지 않게, `START_AT=<UTC 시각>` 에 함께 시작한다. `STAGES` 와는 함께 쓰지 않는다.
- 합치기: 다른 VM 의 결과 폴더를 이 기계의 `OUT` 아래로 복사해 와서 `./ops/53-load-k6.sh report merge <폴더> <폴더>` — 요청 · 오류 수는 더하고
  실패율은 요청 수로 가중, **백분위는 요약에서 합칠 수 없어** VM 중 가장 나쁜 값을 적는다(정확한 합친 백분위는 `PROM_RW` 로 Prometheus 에서).

```bash
CLIENT_IPS=500 RATE=50 PRODUCTS=10000000 ./ops/53-load-k6.sh browse        # 제한은 기본값 그대로(limits default)
# 부하 VM 두 대 — 같은 시각에
SPLIT=1/2 RATE=200 START_AT=2026-10-21T01:00:00Z CLIENT_IPS=500 ./ops/53-load-k6.sh browse   # VM 1
SPLIT=2/2 RATE=200 START_AT=2026-10-21T01:00:00Z CLIENT_IPS=500 ./ops/53-load-k6.sh browse   # VM 2
./ops/53-load-k6.sh report merge browse-…(VM 1) browse-…(VM 2)
```

### 2.7 Elasticsearch 검색 — `search es`

지금까지 목록 · 검색은 모두 PostgreSQL 이었다(`SEARCH_ENGINE=postgres`). ES 는 데모 인프라에 있지만(k8s/90 · 데모 인프라의 ES 덧씌우기,
힙 3g) 엔진은 둘이 정한다 — `search.engine=es`(ES 가 이 배포에 있는가, ConfigMap)와 플래그 `catalog.search-engine`(검색어마다 ES 로 보낼 비율,
k8s/15 는 0%, docs/74).

- `./ops/53-load-k6.sh search es` — 실습 클러스터만. ES 가 떠 있는지 보고 catalog 에 `SEARCH_ENGINE=es` 를 주고 플래그 파일을 끈다
  (`FEATURE_FLAGS_FILE` 이 비면 코드의 기본값 = ES 가 있으면 ES = 100%). ConfigMap 은 건드리지 않는다. ES 파드 안에서 색인(`product-alias`)
  문서 수를 세어, 비었으면 재색인을 안내한다 — 관리자 계정으로 `POST /catalog-service/api/products/sync`(docs/13 §3, `ops/03` 의 1 ~ 4단계 —
  그 스크립트의 `RUN_K8S_PATCH` 는 `kubectl patch configmap` 이라 쓰지 않는다).
- `search default` — 되돌린다. `SEARCH_ENGINE` 은 지워 ConfigMap 값으로, `FEATURE_FLAGS_FILE` 은 **지우지 않고** 매니페스트 값으로 다시 쓴다
  (매니페스트에 직접 적힌 값이라 지우면 플래그가 영영 꺼진다 — `LoadK6ScriptTest` 가 k8s/41 의 값과 견준다).
- ES 는 **검색어가 있는 검색에만** 쓰인다(`SearchEngineSelector` — 검색어가 없으면 PostgreSQL). browse 에서는 `name_search` 만 바뀌므로
  `NAME_SEARCH` 를 올린다. 부하 VM 에는 kubectl 이 없어 `ENGINE=es` 로 회차 기록에 엔진을 적는다.
- 1,000만 상품의 색인은 무겁다(nested 포함 문서가 수천만 — docs/19 §5.1). 먼저 100만에서 PostgreSQL 회차와 견준다.

```bash
./ops/53-load-k6.sh search es                                              # 호스트에서
ENGINE=es NAME_SEARCH=0.5 PRODUCTS=1000000 RATE=50 ./ops/53-load-k6.sh browse   # 부하 VM 에서
ENGINE=postgres NAME_SEARCH=0.5 PRODUCTS=1000000 RATE=50 ./ops/53-load-k6.sh browse   # 같은 조건(search default 뒤)
./ops/53-load-k6.sh search default                                         # 꼭 되돌린다
```

### 2.8 커넥션을 몇 분 쥔 질의 — 1,000만 넓힘 · 필터 검색 (2026-10-20)

| 회차 | 조건 | 실패 | 무엇 |
|---|---|---|---|
| `120709Z` | 1,000만 · 도착률 50 · `SPREAD=1` | 10.33% | 시작 2분과 최고 단계 끝(첫 채움 9분 뒤)에 풀 15개가 다 차고 대기 60 ~ 70 · 커넥션 대기 5초(`acqMs` 5,000) |
| `132102Z` | 1,000만 · 도착률 10 · `SPREAD=1 FILTER_SEARCH=1` | 95.04% | 처음부터 끝까지 풀이 찼다 — 필터 검색(EXISTS) 질의 75 ~ 195초 |
| `133811Z` | 1,000만 · `mixed` 도착률 50 · 리뷰 초당 1 | 2.94% | 처음 1분만 풀이 찼다(캐시가 빈 첫 쪽 필터 메뉴) — 그 뒤 실패 0 |

느린 질의 로그(`log_min_duration_statement = 1s`)의 위는 **필터 검색의 EXISTS 질의 75 ~ 195초**(목록 · 개수, 13:25 ~ 13:31 — `132102Z`)와 **필터 메뉴의 사이즈 DISTINCT 76초**(240만 상품 카테고리의 SKU × 사이즈 전부를 조인)였다. 원인은 둘이다.

1. **시간 상한이 없었다.** 게이트웨이는 10초에 포기하지만 catalog 의 Tomcat 스레드와 DB 커넥션은 질의가 끝날 때까지 묶였다. 파드마다 5개뿐인 풀에서 몇 개만 이렇게 묶여도 나머지 요청이 커넥션 대기 상한(5초) 끝에 실패한다 — 그래서 home · banners 까지 같이 5xx 였다(칸막이가 없다).
2. **필터 메뉴가 요청 안에서 계산됐다.** 캐시(8 ~ 10분)가 비거나 만료되면 첫 쪽 검색이 1분 넘는 DISTINCT 를 직접 돌렸다. 메뉴는 검색 결과 캐시(`search_cache`, 60분)에도 들어가 있었다. `120709Z` 의 두 번째 붕괴(21:16)는 21:07 의 첫 채움 9분 뒤 — 그 만료다.

고친 것:
- **검색 질의 시간 상한** — `SearchService.searchProducts` 의 트랜잭션 시간 상한 `catalog.search.query-timeout-seconds`(기본 5초, 환경 변수 `CATALOG_SEARCH_QUERY_TIMEOUT_SECONDS`). 넘으면 PostgreSQL 에서 취소되고(커넥션이 바로 돌아간다) 503 이다(`SearchController.isQueryTimeout` — SQLSTATE 57014 까지 본다). 비싼 검색만 실패하고 나머지는 산다.
- **필터 메뉴는 요청 밖에서** — `SearchFilterFacets.attach` 가 결과 캐시 밖에서 메뉴를 붙인다. 캐시(`FacetEntry` — 값 + 계산 시각, 24시간)를 읽고, 없거나 10분(`catalog.facets.refresh-after`)이 지났으면 전용 실행기(`catalog.facets.threads`, 기본 1 — 파드마다 메뉴 질의는 커넥션 하나까지)에 맡긴다. 요청은 `catalog.facets.wait`(기본 1.5초)까지만 기다리고, 못 끝나면 그 메뉴만 비어서 나간다(다음 요청이 받는다). 오래된 값은 그대로 내주며 뒤에서 다시 계산한다. 메뉴 질의 자체의 상한은 `catalog.facets.query-timeout-seconds`(기본 600초 — `FacetQueries`).
- 확인(`SearchFacetsTimeoutIntegrationTest`, 실제 PostgreSQL · Redis — 표를 잠가 끝나지 않는 질의를 만든다): 검색 503 이 3초 · 잠금을 기다리는 질의 0 · 풀리면 200, 메뉴 질의는 상한에서 취소, 메뉴 계산이 15초 걸려도 첫 쪽은 1.3초에 200(그 메뉴만 빔) · 끝나면 다음 요청이 Redis 에서 받는다. 되돌리면(상한 둘 · 503 · 기다림 · 붙이기) 모두 실패한다.

고친 뒤(같은 조건, catalog 이미지만 새로 — 측정기 커밋 열은 부하 VM 의 체크아웃이라 `b2ebf3d0` 그대로):

| 회차 | 조건 | 실패 | 무엇 |
|---|---|---|---|
| `152705Z` | 1,000만 · 도착률 50 · `SPREAD=1` | **0.01%**(10.33 · 0.72%) | 커넥션 대기 내내 0 · 받는 시간 0 ~ 2ms, p95 26ms. 첫 1분 검색 p95 1.7초 = 메뉴 기다림 상한(1.5초)에 걸려 메뉴만 빈 첫 쪽, 5xx 4건은 이름 검색의 5초 상한 |
| `145932Z` · `153851Z` | 1,000만 · 도착률 10 · `SPREAD=1 FILTER_SEARCH=1` | **17.70 · 21.48%**(95.04%) | 질의가 몇 분 매달리지는 않는다(커넥션을 쥔 시간 평균 ~1초). 그래도 필터 검색(개수 + 목록 1 ~ 4초)이 풀 15개를 늘 채워 대기 30 ~ 46 · 받는 데 2 ~ 3.5초 — product_cold 최대 5,033ms(= 커넥션 대기 상한 5초), home · banners 까지 5xx |

3. **칸막이가 없었다.** 시간 상한은 질의 하나의 길이를 자를 뿐, 비싼 검색이 **몇 개나 동시에** 커넥션을 쥐는지는 막지 않는다. 그래서 **검색 격벽**(`SearchBulkhead`)을 더했다 — 파드마다 DB 를 쓰는 검색은 동시에 `catalog.search.bulkhead.max-concurrent`(기본 3), 그중 가격 · 색상 · 사이즈 필터 검색은 `…max-concurrent-filtered`(기본 2). 자리는 `…wait`(기본 200ms)까지만 기다리고, 못 얻으면 커넥션을 건드리지 않은 채 503 · `Retry-After: 2`("검색 요청이 몰려 잠시 받지 못했습니다"). 풀(5)의 나머지 둘은 home · banners · 상품 쪽 몫으로 늘 남고, 필터 없는 검색은 한 자리를 늘 쓴다. 자리는 캐시를 본 **뒤**, 트랜잭션(= 커넥션)을 열기 **전에** 얻는다 — `searchProducts` 는 `NOT_SUPPORTED` 이고 시간 상한 트랜잭션은 격벽 안의 `TransactionTemplate` 이 연다. 캐시가 맞는 검색은 자리를 쓰지 않고, 같은 검색의 동시 요청은 단일 계산(`sync = true`)이 하나로 모은 뒤라 한 자리다. 지표 `catalog_search_bulkhead_rejected_total{limit="all|filtered"}`.
- 확인: `SearchBulkheadTest`(필터 둘 · 전체 셋 · 거절은 트랜잭션을 열지 않음 · 실패해도 자리 반환), `SearchFacetsTimeoutIntegrationTest`(실제 PostgreSQL — 표를 잠그고 필터 검색 여덟을 동시에: DB 에서 기다리는 것은 둘, 여섯은 바로 503 · `Retry-After`, 그동안 다른 요청은 커넥션을 1초 안에 받는다). 격벽을 무력화하면(자리 1000) 셋 모두 실패한다(DB 에 들어간 검색 3 · 거절 없음).
- 측정에서 503 은 실패로 센다 — 이제 필터 검색이 몰리면 **필터 검색만** 503 이고 home · banners · 상품은 살아야 한다(엔드포인트별 5xx 로 본다).

남은 것: 필터 검색 자체(가격 · 사이즈 · 색상의 EXISTS + 개수)는 1,000만에서 1 ~ 4초다. 개수 세기 상한(예: "1,000개 이상" — `count(*) from (… limit 1001)`)과 질의 모양은 다음 단계다. 같은 조건으로 다시 잰다.

**8시간 오래 돌리기**(`mixed-20261020T162922Z`, 격벽 이미지 · 1,000만 · 넓힘 · 도착률 40 · 리뷰 초당 1, `STAGES=5m:40,8h:40,2m:0`): 요청 607만(초당 207.7) · 실패 **0.25%** · home p95 19ms · product_cold 37 · 리뷰 쓰기 98. 메모리는 차오르지 않았다(catalog 640Mi 안팎 / 한도 1Gi). 임계값을 넘은 것은 **검색 p95 1,513ms**(기준 1,200) 하나다.

- **검색 p95 1.5초 = 메뉴 기다림 상한** — 🐛 필터 메뉴 캐시 넷(brands · colors · sizes · details)이 `RedisCacheConfig` 의 설정 맵에 없어 **기본 10분**(흩어져 8 ~ 10분)이었다. 이 절의 '캐시 24시간'은 적혀만 있었다. 다시 계산할 때(10분)가 오기 전에 값이 Redis 에서 사라져, 카테고리마다 8 ~ 10분에 한 번 첫 쪽이 메뉴 계산을 1.5초 기다린 뒤 빈 메뉴로 나갔다. 대기열이 찼다는 로그 · 계산 실패는 0이었다 — 앞서 `152705Z` 끝의 1.7초를 '다시 계산이 대기열에 몰려서'로 본 것은 틀렸고, 같은 원인(만료)이었다. 이제 `FACETS_TTL` 24시간(흩어짐 20%) — `RedisCacheResilienceTest.facetCachesOutliveTheirRefresh`(되돌리면 실패).
- **측정기**: `deepSearchPath` 가 `includeFilters` 를 빼 서버 기본값(true)으로 깊은 쪽도 메뉴를 붙이고 기다렸다 — 화면(`browse.jsx`)은 2쪽부터 `false`. 이제 `includeFilters=false`(LoadK6ScriptTest). 이전 회차의 search_deep 응답 시간은 메뉴 기다림을 포함한다.
- **5xx 15,191 의 내역**: 격벽 거절(`limit="all"`) 4,693 · 필터 0 — 깊은 쪽(넓힘 1,000쪽의 OFFSET)과 이름 검색. 나머지 약 10,500 중 **catalog 파드 하나의 JVM 충돌**(17:47:56 UTC, `SIGSEGV` · `LinkResolver::runtime_resolve_virtual_method`, Temurin 17.0.20.1, 종료 코드 134) 동안의 몫은 많아야 4,000 쯤이다 — 파드가 빠진 것은 1분 남짓이었다(아래 타임라인). 그 밖의 약 6,000 은 아직 원인을 모른다(다음 회차에 게이트웨이 5xx 를 시간대별로 본다). 충돌 기록(`/app/hs_err_pid7.log`)은 컨테이너와 함께 사라졌다.
- **user-service 파드 하나가 CPU 한도(1000m)를 다 썼다** — 스레드 목록에서 `C2 CompilerThread0` 의 CPU 가 29,893초 / 파드 수명 31,121초(96%), 한 메서드(`FirstMatchingCompositeObservationHandler::onEvent`)를 컴파일 중이었다. JIT 컴파일러가 끝나지 않는 경우다 — 81분 뒤 두 번째 스레드 목록에서도 같은 컴파일 번호(27495)였고 그 사이 CPU 를 4,855초 더 썼다(확인). 다른 두 파드는 3m.

그래서 고친 것(2026-10-21):

- **JVM 충돌 기록이 남는다**(B-97): 이미지가 `-XX:ErrorFile=/var/log/jvm/hs_err_pid%p.log -XX:-CreateCoredumpOnCrash`, 자바 Deployment 13개는 그 경로에 `emptyDir`(64Mi) — 컨테이너가 다시 떠도 파드가 사는 동안 남는다. 로그(Loki)로 보내지 않는 이유: 기록에 레지스터 근처의 메모리 내용(토큰 · 이메일 조각)이 들어갈 수 있다. 코어 덤프(힙 전체)는 만들지 않는다. 꺼내기: `kubectl -n ecommerce exec <파드> -- ls /var/log/jvm` → `kubectl -n ecommerce cp <파드>:/var/log/jvm/hs_err_pid7.log ./hs_err.log`(파드를 지우면 사라진다 — 지우기 전에). `RuntimeImageHeapTest` 가 실제 JVM 을 SIGSEGV 로 죽여 기록이 그 디렉터리에 생기고 코어가 없는지 본다.
- **JRE 판 고정**(B-98): `ops/Dockerfile.runtime` 의 `eclipse-temurin:17.0.20.1_1-jre-jammy@sha256:b3054a20…`(멀티 아키텍처 다이제스트) 한 곳 — ops/19 · ops/13 은 그 줄을 읽고, E2E 기본값도 같다. 이 밤의 JVM 을 그대로 고정했다(측정이 이어지게). 판을 바꾸는 것(17.0.20_8 로 되돌리기 · JDK 21)은 따로 정한다.
- **게이트웨이 재시도**(B-99, `RouteRetryTest`): WebSocket 밖의 모든 라우트 마지막(회로 차단기 안쪽)에 Retry — 연결 실패(`ConnectException` · `NoRouteToHostException`)만, GET 만, 다른 인스턴스로 두 번까지. 응답 상태(5xx · 격벽 503)는 다시 보내지 않는다(Retry 기본값은 5xx 를 다시 보낸다 — `series` · `statuses` 를 비웠다). 연결 시간 상한 2초(`GATEWAY_CONNECT_TIMEOUT_MS`, 기본 45초면 사라진 파드 IP 에서 회로 차단기의 10초를 먼저 다 쓴다). 확인: 인스턴스 둘(하나는 닫힌 포트)에 GET 10번이 모두 200 · 503 은 6번 요청에 백엔드도 6번 · POST 는 닫힌 쪽 몫이 그대로 실패 — 되돌리면(재시도 빼기 · series 지우기 · POST 추가) 각각 실패.
- 충돌 시각(02:47:56 KST)의 catalog 타임라인: 파드 수 3 → 2 는 02:48:10 ~ 02:48:40, catalog 가 처리한 요청은 초당 ~200 그대로 — 실패는 catalog 에 닿기 전(게이트웨이)이었다. 다시 뜬 파드의 데움(02:49:10 ~ 02:50:00)에 커넥션 대기 5 ~ 11 · home p95 ~350ms.

**둘째 밤**(2026-10-21, 같은 mixed 8시간 · 새 이미지): 5시간 40분에 자바 파드 25개의 C2 컴파일러 CPU 는 파드 나이의 0.2 ~ 0.5%(정지 없음). 대신 **gateway 파드 하나의 JVM 이 14:20:09 KST 에 끝났다**(시작 2시간 22분 뒤) — 이번에는 기록이 남았다(`/var/log/jvm/hs_err_pid7.log`, 725KB):

- `SIGILL`(`ILL_ILLOPN`) — C2 가 컴파일한 `org.springframework.util.MimeTypeUtils.parseMimeType` 안, Netty 이벤트 루프(`reactor-http-epoll-1`, 요청 헤더 읽기). x86 에서 C2 는 '오지 않는다' 고 본 자리에 `ud2` 를 두는데, 그것을 실행하면 정확히 이 신호다 — 컴파일러가 코드를 잘못 만든 경우로 보는 것이 가장 그럴듯하다.
- **종료 코드는 1** 이다(134 아님) — `-XX:-CreateCoredumpOnCrash` 면 JVM 은 abort 대신 1 로 끝난다. 판정은 '종료 코드 1 + `/var/log/jvm` 에 `hs_err_pid*.log`'.
- 이틀 밤의 JVM 고장 셋(catalog SIGSEGV 호출 연결 · user C2 컴파일 정지 · gateway SIGILL C2 코드)은 서비스도 메서드도 다르지만 **판 하나(17.0.20.1) · JIT 영역** 이 같다. 같은 밤 JVM 이 아닌 파드(PostgreSQL · Redis · Kafka · Traefik)는 재시작 0, 호스트 커널 로그에 MCE 없음(다만 ECC 없는 메모리는 로그를 남기지 않는다).
- 운영(`b7c5053`, 10-10 빌드)도 **이미 17.0.20.1** 이다(`java -version`, 2026-10-21) — Docker Hub 의 `17-jre-jammy` 갱신 시각(10-17)은 태그를 다시 올린 때라, 판이 들어온 날로 읽은 것은 틀렸다. 그래서 B-98 의 고정은 운영과 같은 판이다(다음 배포로 판이 바뀌지 않는다).
- 다음: 실습만 바로 앞 패치 `17.0.20_8`(`JRE_IMAGE=eclipse-temurin:17.0.20_8-jre-jammy@sha256:e85989f3…` 로 빌드 — 저장소는 그대로)로 같은 조건을 하루 이틀 밤 돌려 맞댄다. 17.0.20.1 은 Oracle 안내에 JDK 17 보안 기준선(8월 18일)으로 올라 있어, 되돌리는 결정은 그 결과로 한다.
- **정정(2026-10-21 밤) — JVM 판보다 실습 호스트 메모리가 먼저다(memtest 대기)**: VM 들의 pstore(`/var/lib/systemd/pstore`)에 **커널 패닉이 닷새에 네 번, VM 세 대**에 남아 있었다 — agent2 10-17 `nft_chain_validate`(읽은 포인터 `ffffffff809fa660` 이 같은 모듈의 주소로 보이는 `ffffffffc09fa660` 과 **한 비트(30번)만 다르다**) · server-node 10-18 `xas_split` · agent1 10-19 `blk_mq_get_tag` · agent2 10-21 `rb_erase`(18:44 의 `guest-reset` · `kernel.panic=10`). 호스트의 Chrome 도 10-18 · 10-21 에 segfault. 커널 판 둘 · 관계없는 영역 넷 · 호스트 프로그램 · JVM 셋을 한꺼번에 설명하는 것은 메모리 비트 오류다. 호스트는 32GB 듀얼 랭크 네 장을 3200MT/s 로(Ryzen 5000 의 네 장 · 듀얼 랭크 공식 지원은 2666), 한 장(`DIMM_B2`)만 부품 번호가 다르다, 고장은 메모리를 거의 다 쓴 오래 돌리기에만(111 / 125Gi · 스왑 가득). 그래서 17.0.20_8 비교는 취소, 판 고정(17.0.20.1 = 운영)은 그대로. 다음: 메모리를 2666 으로 내리고 memtest86+ → 오류 0 이면 오래 돌리기를 다시(대역폭이 줄어 기준선을 새로 잡는다). 10-17 이후 회차의 충돌 · 재부팅 구간 실패는 환경 탓이다.
- ⚠️ 운영에서 `kubectl exec … java -version` 은 `Picked up JAVA_TOOL_OPTIONS` 줄에 스토어 비밀번호를 그대로 찍는다(다음 배포로 멈춘다). 그 전까지는 `… -- sh -c 'java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS'`.

**둘째 밤의 실패 4.64% — 격벽 자리가 죽은 DB 연결에 묶였다**(mixed `030018Z`, 5xx 28.2만 중 search 12.6만 · search_deep 12.7만 · name_search 2.6만, p95 205 ~ 214ms = 격벽 기다림 0.2초): 끝난 뒤 쉬는 동안에도 catalog 파드 셋 모두 격벽 안 스레드가 **정확히 셋**(자리 3/3)이었고, 스레드 덤프로 셋 다 같은 곳이었다 — 검색 개수 세기(`SearchService.search` → `fetchOne`)의 PostgreSQL 소켓 읽기(`SSLSocketInputRecord.read` ← `PGStream.receiveChar`), 상태 RUNNABLE(`Net.poll`). ES 는 0%(플래그 es 0 · postgres 100), Redis 는 명령 상한 1초라 빠진다.
- 원인: 질의 시간 상한(5초)은 PgJDBC 가 취소 요청을 **새 연결로** 보내는 것이라, 질의를 보낸 연결의 상대가 사라졌으면(VM 패닉 · 재부팅 — RST 가 오지 않는다) 답이 돌아오지 않고 읽기는 깨어나지 않는다. 데이터소스에 소켓 상한이 없었다. 그 연결이 언제 죽었는지는 남지 않았다(누수 감지 로그 0 — 몇 시간 치 로그는 kubelet 이 이미 돌려 버렸다) — 같은 밤의 agent2 패닉 · 호스트 메모리 쪽과 맞는다. 그 뒤로 DB 를 쓰는 검색은 모두 0.2초 뒤 503, 풀(5)에도 둘만 남아 home · banners · 상품 몇백 건도 5xx.
- 고침(2026-10-21): ① 검색 트랜잭션의 커넥션에 소켓 읽기 상한 = 질의 상한 + `catalog.search.network-timeout-grace`(5초 — 합 10초, `SearchBulkhead`, `Connection.setNetworkTimeout`; 풀로 돌아오면 Hikari 가 되돌린다). 넘으면 PgJDBC 가 그 연결을 버리고(08006) Hikari 가 풀에서 빼며, 검색은 503 · 로그 '검색 연결 소켓 읽기 시간 초과'. 버려진 연결의 롤백 실패가 원래 원인을 덮어 500 이 되던 것도 막았다. ② 두 데이터소스 모두 마지막 안전망 `socketTimeout` 900초(`DB_SOCKET_TIMEOUT_SECONDS` — 가장 긴 정상 질의, 필터 메뉴 600초보다 길게). 확인: `SearchDeadConnectionIntegrationTest` — 실제 PostgreSQL 앞의 TCP 중계가 질의 도중 그 연결의 바이트를 버린다(닫지 않음, 취소는 새 연결로 닿는다) → 4초에 503 · 자리 1 인데 다음 검색 200(고침을 빼면 25초 안에 끝나지 않는다).
- 실습에서 다시 보기: `kubectl logs … --tail=-1 | grep -c '검색 연결 소켓 읽기 시간 초과'` 와 쉬는 동안의 스레드 덤프(격벽 안 0).

**셋째 밤(메모리 2666 첫 밤) — 실패 0.27% · 사건 없음, 남은 덩어리는 필터 메뉴의 파드 셋 동시 갱신**(mixed `150403Z`, 8시간 처음으로 임계값 통과, p95 41ms): 재시작 · 커널 패닉 · segfault 0. 5xx 1.67만 중 9,115 는 시작 30분의 빈 캐시 검색(격벽 503 — 예상), 다음 덩어리가 15:30:45Z 의 1분 — 게이트웨이 `/fallback` 503 1,472(`catalog-service` 라우트, 한 게이트웨이 파드의 catalog 자리 25 가 0). 그 순간 catalog 파드 셋의 가장 긴 요청이 함께 6.1 ~ 6.2초였고 GC · CPU · 격벽은 평소였다.
- 원인: PostgreSQL 느린 질의 로그 — 같은 필터 메뉴 질의(브랜드 `select distinct b.brand … category_id = 3` 22초 · 색상 7초 · 사이즈 11초)가 **백엔드 셋에서 같은 매개변수로 같은 순간에** 끝났다. 메뉴 값은 Redis 에 계산 시각과 함께 있어 10분(`refresh-after`)이 지난 순간을 모든 파드가 같이 보고, 파드마다 자기 실행기로 같은 1,000만 행 훑기를 돌렸다 — 셋이 함께 도는 동안 DB 가 느려져 home 의 질의까지 6초.
- 고침(2026-10-22): 메뉴 계산은 클러스터에서 한 파드만(`FacetRefreshClaim` — Redis `SET NX PX`, 만료 = 메뉴 질의 상한 600초 + 1분, 자기 토큰만 푼다). 맡지 못한 파드는 옛 값을 그대로(없으면 메뉴 없이) 내주고, 맡은 직후 캐시가 이미 새것이면 다시 하지 않는다. Redis 에 닿지 못하면 예전처럼 그 파드에서. `SearchFilterFacetsClaimTest`(파드 둘이 캐시 · 맡기를 함께 — 오래된 · 없는 메뉴 모두 질의 한 번; 맡기를 건너뛰면 · 재확인을 빼면 · 풀지 않으면 각각 실패).
- 확인(2026-10-23, capacity-steps `001615Z` — 느린 질의를 매개변수와 함께): 10분마다 카테고리마다 한 번씩, 같은 카테고리가 겹친 적 없음. 같은 순간에 끝나는 질의 셋은 서로 다른 카테고리를 파드들이 나눠 맡은 것(§5).
- 함께 드러난 것: 관측 VM 디스크가 Jaeger(13G)로 차서 05:36 KST 부터 지표가 끊겼다(실습만 추적 보관 24시간). 오늘 고친 소켓 상한은 이번 밤에 일할 일이 없었다(0건, 쉬는 동안 격벽 안 0).

⚠️ 로그를 셀 때 `kubectl logs -l <선택자>` 는 파드마다 **마지막 10줄만** 읽는다 — `--tail=-1` 을 붙인다(2026-10-20: '검색 질의 시간 초과' 3 · 'Connection is not available' 0 은 이 때문에 믿을 수 없었다).

**운영(2026-10-25) — 필터 검색의 개수 세기가 42초, 가격 필터가 늘 503**: `/browse?category=16&price=4_` 가 "불러오지 못했습니다". 운영 DB 에 상품이 **200만**(카테고리 16 에 55,395 · SKU 340만 · 사이즈 줄 600만 — 데모 데이터)이라 "운영 상품 수에서는 1초 안" 이라는 전제가 맞지 않았다. 하한 1 · 4 모두 5.3초에 503(로그 57014 · '검색 질의 시간 초과'). 같은 모양의 `EXPLAIN ANALYZE`: 목록이 아니라 **개수**가 카테고리의 상품마다 EXISTS(sku → 사이즈 인덱스 조회)를 55,395번 돌려 42.9초(한 번 2.3ms, 임의 읽기 11만 블록). 목록 10개는 맞는 것을 찾는 즉시 멈춰 빠르다.
- 고침: SKU 조건(가격 · 색상 · 사이즈)이 있으면 개수를 끝까지 세지 않고 한 쪽 + 1 을 가져온다 — 더 있으면 `totalProducts` = 받은 수 + 1, 마지막 쪽은 정확한 수. 화면은 이 수를 보여 주지 않고 무한 스크롤의 "더 있는가"(browse.jsx `nextPageParam`: 받은 수 < total)에만 쓴다 — 화면은 그대로. 필터가 없으면 예전처럼 정확히(인덱스만 보는 개수). `SearchSkuFilterScaleTest`(7만 개 카테고리 — 1쪽 11 · 2쪽 21 · 마지막 쪽 70,000 · 넘어선 쪽 70,000, 예전처럼 세면 70,000 으로 실패).
- 한계: 맞는 것이 아주 드문 조건(카테고리 대부분보다 비싼 하한 등)은 목록도 카테고리를 끝까지 훑는다 — 상한 5초 → 503 그대로. 상품마다 화면 가격 범위를 미리 두는 열(기획전 · 상시 할인이 바뀔 때 갱신)이 다음 단계.
- 같은 날 배포 뒤: 가격 칸이 "4 ~ 0"(상한 칸의 스피너를 누르면 빈 칸이 0 이 된다)이면 하한을 1씩 올려도 늘 503 — 위 한계의 가장 나쁜 경우(맞는 것이 0)다. 하한 > 상한 · 음수 상한은 DB 에 묻지 않고 0건(`SearchService.emptyPriceRange`, `SearchServiceUnitTest`) — 화면은 "조건에 맞는 상품이 없습니다".

## 3. 순서

```bash
# 0) 데모 인그레스가 API 를 게이트웨이로 보내는지 — 200 application/json
curl -sS --cacert ~/.ecommerce-msa/demo/certs/ca.crt https://shop.lab/catalog-service/api/content/home \
     -o /dev/null -w '%{http_code} %{content_type}\n'

# 1) 데이터 — 상품 1만 · 사용자 200 (docs/79)
FORCE=1 PRODUCTS=10000 PLACEHOLDER_IMAGE=/logo512.png ./ops/44-bulk-catalog.sh load
FORCE=1 USERS=200 ./ops/48-bulk-users.sh load

# 2) 같은 CPU 를 쓰는 것을 멈춘다 — CI 러너
# 3) 점검
./ops/53-load-k6.sh smoke

# 4) 유입 제한을 잠깐 올린다(측정할 때만 — 아래)
./ops/53-load-k6.sh limits load            # 초당 2000 · 버스트 4000

# 5) 측정 — 하나씩, 사이에 몇 분 쉰다(HPA 가 줄어들고 캐시 · 커넥션이 가라앉게)
RATE=100 ./ops/53-load-k6.sh browse
USERS=200 RATE=20 ./ops/53-load-k6.sh order
RATE=200 ./ops/53-load-k6.sh spike          # 다른 창에서 kubectl -n ecommerce get hpa -w

# 6) 되돌린다 — 꼭
./ops/53-load-k6.sh limits default
```

**유입 제한을 왜 올리나.** 비로그인 요청(카탈로그 읽기)은 버킷 키가 IP 다(`CustomUserKeyResolver`). 데모에는 Cloudflare 가 없어 모든
바깥 요청이 Traefik 파드 IP 하나로 보이고, 그 버킷(초당 20 · 버스트 40)을 모든 손님이 나눠 쓴다. 그대로 재면 결과는 "초당 20 에서 429" 뿐이다.
그래서 게이트웨이 기본 제한을 환경 변수로 바꿀 수 있게 했다(`GATEWAY_RATE_LIMIT_REPLENISH` · `_BURST`, 기본값은 그대로 —
`RateLimiterPlacementTest`).

- `limits` 는 이 클러스터의 `APP_PUBLIC_URL` 이 운영 주소면 거절하고, 비어 있어도 거절한다. 로그인 제한(초당 1)은 바꿀 수 없다.
- Deployment 의 env 를 직접 바꾸는 것(`kubectl set env`)은 이 측정에만 쓰는 예외다 — 설정은 원래 ops/28 로만 바꾼다.
  매니페스트에는 이 변수를 두지 않는다(가드가 본다). 올린 채로 두면 ops/06 스모크의 "429 발생 확인" 이 실패한다 — 그것이 되돌리지 않았다는 신호다.
  🐛 **배포도 그 스모크를 돈다** — `kubectl set env` 의 값은 apply 가 지우지 않아 배포를 지나 남고, 올린 채 배포하면 멀쩡한 배포가
  스모크 실패로 자동 복귀했다(2026-10-18 `a84e4acb`). 이제 ops/28 이 적용 전에 멈추고 `limits default` 를 안내한다(`check_load_overrides`,
  `DeployLoadOverrideScriptTest`). 남은 `DB_POOL_SIZE`(`pool load`)는 알리기만 한다. 측정을 이어 가려면 배포 뒤 다시 `limits load`.
  🐛 **롤링은 측정 때의 게이트웨이 로그도 지운다** — `limits default` 가 파드를 바꿔, 캐시 후 회차의 503 을 폴백 로그로 셀 수 없었다(2026-10-18).
  이제 게이트웨이 파드를 바꾸는 명령(`limits load` · `default`, 게이트웨이가 든 `replicas`)은 바꾸기 전에 파드마다 로그 전체를
  `~/.ecommerce-msa/gateway-logs/<시각>-<이유>/`(0700, `GATEWAY_LOG_DIR`)에 남기고, 폴백 예외별 수(`summary.txt`)를 보인다. 언제든 `./ops/53-load-k6.sh logs`.
- 올린 값으로 잰 수치는 "유입 제한을 뺀 서비스의 용량" 이다. 운영의 실제 손님은 IP 마다 버킷이 따로라 이 제한에 걸리지 않는다.
- 🐛 **올리지 않고 재면 시작 전에 멈춘다**(`limitsRaisedOrFail`, ops/k6/lib.js): browse · spike · order 의 setup 이 비로그인 요청 60개를 한꺼번에 보내
  429 가 하나라도 나오면 측정하지 않는다. 2026-10-18 첫 회차가 기본값(초당 20) 그대로 돌아 결과를 모두 버렸다(§5). smoke 는 올리기 전 점검이라 건너뛰고,
  제한 자체를 재려면 `ALLOW_RATE_LIMIT=1`. 데모의 k6 는 loadgen 에서 돌아 kubectl 이 없다 — `meta.txt` 에 `gateway_limits` 가 없으면 호스트에서 `limits status` 로 확인한다.

**DB 커넥션 풀을 바꿔 다시 재기(`pool`).** 첫 유효 browse 에서 먼저 찬 것이 catalog 의 풀(5)이었다(§5). 풀만 바꿔 같은 조건으로 잰다:

```bash
./ops/53-load-k6.sh pool load 10                 # catalog-service 의 DB_POOL_SIZE 5 → 10 (서비스는 네 번째 인자)
./ops/53-load-k6.sh smoke                        # 새 파드의 데움 뒤
RATE=100 ./ops/53-load-k6.sh browse              # 같은 단계 · 같은 데이터
./ops/53-load-k6.sh pool default                 # 되돌린다 — 꼭
```

- `limits` 와 같이 운영 클러스터(APP_PUBLIC_URL)는 거절하고, `kubectl set env` 는 이 측정에만 쓰는 예외다(매니페스트에는 넣지 않는다).
- 바꾸기 전에 DB 연결 예산을 센다: PostgreSQL 의 지금 연결 수 + 데이터소스 수(그 서비스 설정의 `maximum-pool-size: ${DB_POOL_SIZE…}` 줄 수 — catalog 는 2) ×
  늘린 만큼 × HPA 최대 레플리카 ≤ `max_connections` − 20. 읽지 못하면 바꾸지 않는다.
- 바꾸면 그 서비스의 파드가 다시 뜬다 — 첫 1분은 데움이라 smoke 뒤에 잰다. `meta.txt` 의 `db_pool`(kubectl 이 있는 기계에서)과 report 가 값을 보인다.

**레플리카를 미리 늘려 다시 재기(`replicas`).** 풀 실험에서 catalog · 게이트웨이 모두 CPU 상한(1 코어)에 붙었다(§5). HPA 는 늘리지만 새 파드의 기동 · 데움이
100 단계보다 늦으니 미리 늘린다:

```bash
./ops/53-load-k6.sh pool default                 # 풀 실험을 되돌린 뒤 — 한 번에 하나만 바꾼다
./ops/53-load-k6.sh replicas load                # catalog-service · gateway-server 2 → 3 (수 · 서비스는 인자로)
./ops/53-load-k6.sh smoke
./ops/53-load-k6.sh browse
./ops/53-load-k6.sh replicas default             # HPA 최소 · 최대를 k8s/70 값으로 — 부하가 없으면 HPA 가 몇 분 안에 줄인다
```

- HPA 가 있으면 최소값을 함께 올린다(그대로 두면 부하 전의 한산한 동안 HPA 가 다시 줄인다). 수가 HPA 최대보다 크면 최대도 올린다. HPA 없는 서비스는 되돌릴 때 demo 오버레이의 수로.
- 늘리기 전에 스케줄 가능한 노드(NoSchedule · NoExecute 테인트 · cordon 제외 — 데이터 노드는 세지 않는다)의 할당 가능 − 이미 잡힌 요청(끝난 파드 제외)으로
  늘릴 파드의 CPU · 메모리 요청이 들어가는지 센다. 요청은 250m 이지만 실제로는 1 코어까지 쓰니 `kubectl top nodes` 도 함께 보인다 — 노드가 이미 바쁘면 늘려도 같은 코어를 나눠 쓴다.
- HPA 의 최소 · 최대를 바꾸는 `kubectl patch hpa` · `kubectl scale` 은 `limits` · `pool` 과 같은 측정용 예외다. 다음 데모 배포(APPLY_HPA=1)도 k8s/70 으로 되돌린다.
- `replicas max <수> [서비스…]` 는 **HPA 최대만** 올린다(최소 · 지금 레플리카 그대로) — spike 에서 HPA 가 따라가는 동안의 오류를 잴 때(`replicas load` 는 최소도 올려 미리 늘어난 채로 잰다).
  요청 여유는 최대까지 늘었을 때로 센다(그때 Pending 이면 HPA 가 아니라 노드를 잰다). HPA 가 없거나 최소보다 작은 최대는 거절, 되돌리기는 `replicas default`.

**실행기 환경 변수.** `GATEWAY`(기본 `https://shop.lab`) · `CA_FILE`(데모 CA — 검증을 끄지 않고 이것을 믿는다, `SSL_CERT_FILE`) ·
`USERS` · `ID_OFFSET`(ops/48 과 같은 번호) · `PASSWORD_FILE`(데모 비밀번호 — k6 의 환경 변수로만 넘기고 명령줄 · 결과 파일에 없다) ·
`RATE` · `STAGES`(`길이:목표,…`) · `OUT`(결과 폴더) · `PROM_RW`(k6 지표를 Prometheus 로 — obs-vm 를 `docker-compose-observability.lab.yml` 덧씌우기로 띄우면 받는다) ·
`PROM`(Prometheus 질의 주소, 없으면 `PROM_RW` 에서 — 레플리카를 시작 · 측정 중 최대 · 끝에 `count by (service) (up{environment="$PROM_ENV"} == 1)` 로 읽어 `meta.txt` 의 `replicas_start` · `replicas_max` · `replicas_end` 에, `PROM_ENV` 기본 `demo`).

**밤 회차 뒤에 이어 돌리기(`ops/57-load-queue.sh`).** 오래 돌리기(mixed 8시간)가 끝나는 아침에 사람이 없어도 다음 측정이 이어진다.
지금 k6 가 도는 기계(실습은 loadgen)에서 `nohup ./ops/57-load-queue.sh run > ~/.ecommerce-msa/load-queue.out 2>&1 &` — 그 k6 가 끝날 때까지
기다렸다가(`pgrep -x k6`, 60초마다) 회차마다 10분 쉬고 돈다. `plan` 이 순서 · 시간 합계(약 2시간 35분)를, `status` 가 지금 단계 · 로그 끝을 보인다.

| 회차 | 시나리오 | 모양 | 무엇을 보나 |
|---|---|---|---|
| order-sustained | `order` RATE=5 | 5분 올림 · **초당 5건 55분** · 5분 내림 | 주문 쓰기 경로(재고 예약 · 아웃박스 · Kafka · 입금 안내 · 취소)를 오래 — 10-17 의 order 회차는 중간에 끊겨 쓰기를 오래 잰 적이 없다. 회차 뒤 아웃박스 STARTED · FAILED 가 남지 않아야 한다 |
| capacity-steps | `browse` RATE=500 | 초당 100 → 200 → 300 → 400 → 500, 단계마다 10분 | 어느 단계에서 무엇이 먼저 막히나(게이트웨이 격벽 · 검색 격벽 · catalog 풀) · HPA 가 단계마다 따라오나(spike 의 20초 오름에는 늦었다 — §5) |

- RATE 는 가장 높은 단계와 같게 둔다 — 시나리오가 VU 수를 RATE 로 정해, STAGES 만 올리면 VU 가 모자라 부하 기계의 한계를 잰다.
- 임계값을 넘어 99 로 끝난 회차도 다음 회차로 간다(그것도 결과다). 끝나면 `report` 를 저장하고 `reviews default` · `limits default` 로 되돌린다(`CLEANUP=0` 이면 그대로).
- kubectl 이 없는 기계(loadgen)에서는 회차만 돌린다 — 유입 제한은 오래 돌리기 때 호스트에서 올린 그대로 쓰고(내려가 있으면 회차의 시작 전 확인이 429 에서 멈춘다),
  회차 사이 상태 · 되돌리기는 로그 끝의 명령을 호스트에서 친다. `stop` 은 대기열만 멈춘다 — 돌고 있는 k6 는 끝까지 간다.

## 4. 읽는 법 — 무엇을 같이 보나

k6 요약은 "얼마나 느렸나" 만 말한다. **왜** 는 같은 시간의 서버 지표에 있다(Grafana, obs-vm). 서비스 지표는 클러스터 안 에이전트가 보낸다(그 전에는 데모의 서비스 지표가 관측 VM 에 없었다). `PROM_RW` 를 주면 k6 지표도 같은 시간축에 온다.

| k6 에서 보이는 것 | 같이 볼 것 | 흔한 원인 |
|---|---|---|
| 어느 단계부터 p95 가 계단처럼 뛴다 | `hikaricp_connections_pending` · `tomcat_threads_busy_threads`(÷ `tomcat_threads_config_max_threads`) · 처리 중 요청 수 `http_server_requests_active_seconds_gcount`(히스토그램을 켠 LongTaskTimer 라 `_active_count` 가 아니다) | DB 커넥션 풀 고갈(풀 5 × 레플리카) · 스레드 포화 |
| 주문만 느리다 · 담기는 괜찮다 | order 의 gRPC 재고 예약 지연 · catalog 의 잠금 대기 | 같은 SKU 에 몰림(행 잠금) — 상품 수를 늘리면 풀리는지 |
| 주문은 빠른데 뒤가 밀린다 | Kafka 컨슈머 지연 · `payment_outbox_event` STARTED 행 수 | 아웃박스 릴레이 · 컨슈머 처리량 |
| `rate_limited` 가 는다 | 그 이름(`name` 태그) | 유입 제한 — 올리지 않았거나, 로그인한 한 손님의 버킷(초당 20) |
| 급증 직후만 5xx | HPA 의 레플리카 · 새 파드의 Ready 시각 · PostgreSQL 연결 수 | 새 파드가 준비 전에 트래픽 · 커넥션 총합이 `max_connections` 근처(docs/19 §3) |
| `http_req_failed` 가 0 인데 `checks` 가 낮다 | 실패한 검사 이름 | 400(재고 · 입금 대기 한도) — 데이터 상태 |

> 🐛 `tomcat_threads_*` 는 Tomcat 의 MBean 레지스트리가 켜져야 나온다(Spring Boot 기본은 꺼짐) — 2026-10-18 실습에서 비어 있어 공통 설정(`application-common.yml`)에서 켰다(`TomcatMetricsConfigTest`). 그 전 이미지에서는 처리 중 요청 수로 본다. 게이트웨이(WebFlux)에는 Tomcat 지표가 없다.

**CPU 가 한도에 붙었을 때 — 어디에 쓰나(`ops/58-cpu-profile.sh`).** 지표는 "CPU 1.00" 까지만 말한다. kubectl 이 있는 호스트에서 측정을 시작하기 전이든 직후든
`PROM=http://<obs-vm>:9090 ./ops/58-cpu-profile.sh` — 게이트웨이가 초당 900 요청을 넘는 순간(capacity-steps 의 200 단계)부터 20초 뒤 120초 동안 catalog · 게이트웨이의
가장 오래 뜬 파드에서 표본을 뜬다(`WHEN=rps:gateway-server:1300` 이면 300 단계 · `WHEN=now` 면 바로, 서비스는 인자로). async-profiler 3.0(linux-x64)을 한 번 받아 둔다 — 명령은 스크립트 머리말.
결과는 `~/.ecommerce-msa/profiles/<시각>/` 의 `<서비스>.txt`: **묶음**(표본을 잎에서 가장 가까운 아는 묶음에 — TLS · 암호 · JSON · Netty · Reactor · JDBC · Redis · JWT · 게이트웨이 · 로그 · 관측 · GC · JIT, JDK 프레임은 부른 쪽으로) ·
flat(잎 프레임) · inclusive(그 메서드 아래의 시간). 표본 중 파드가 다시 뜨면 결과가 없다 — 재시작 수를 meta.txt 에 남긴다.

## 5. 결과 기록

측정할 때마다 한 줄 — 같은 조건(커밋 · 레플리카 · 데이터 양)으로 고치기 전후를 나란히 둔다. 원본은 `load-results/<시나리오>-<시각>/`
(`summary.json` · `summary.txt` · `meta.txt`).

모아 보기: `./ops/53-load-k6.sh report` — 모든 회차를 시각 순으로 한 줄씩(커밋 · 도착률 · 상품 수 · 초당 요청 · 실패율 · p95/p99 · 임계값)과
가장 최근 회차의 요약(조건 · 레플리카 · 429 · 못 보낸 반복 · 엔드포인트별 avg/p50/p95/p99/max · HPA). 한 줄 표의 `레플리카` 는 catalog · 게이트웨이의 측정 중 최대(`3·3`). `report <폴더>…` 는 그 회차들, `report all` 은 전부.
엔드포인트별 값은 임계값을 건 이름만 나온다(`--summary-export` 가 그것만 남긴다).
429 가 아닌 실패는 5xx · 4xx · 응답 없음(시간 초과 · 연결 끊김)으로 따로 세고 엔드포인트별로 보인다(`errors_*`, 엔드포인트별 값은 `errorThresholds` —
언제나 통과하는 임계값으로 요약에 남긴다). 🐛 2026-10-18 전에는 실패율만 남아 `cart_add` 의 464건 · browse 의 1.06% 가 무엇인지 알 수 없었다.
loadgen 처럼 kubectl 이 없는 기계의 회차는 유입 제한이 "기록 없음" 으로 나온다(예전에는 기본값으로 잘못 읽었다) — 429 가 0 이면 올라가 있었다.
🐛 레플리카도 그랬다 — HPA(k8s/70, 최소 2 · 최대 3)가 측정 중에 늘리고 loadgen 에는 kubectl 이 없어, `a84e4acb` 회차가 2 인지 3 인지 Grafana 에서 다시 뽑아야 알았다.
이제 `PROM`(또는 `PROM_RW`)을 주면 Prometheus 에서 읽어 남긴다(`LoadK6ScriptTest`). 읽지 못하면 `replicas_note` 를 남기고 측정은 그대로 한다.

| 날짜 | 커밋 | 시나리오 | 상품 수 | 오류 < 1% 에서의 최고 도착률 | p95 / p99(엔드포인트별) | 병목(근거 지표) | 고친 것 | 고친 뒤 |
|---|---|---|---|---|---|---|---|---|
| 2026-10-18 | `704c6c53` | browse(25 → 50 → 100 반복/초) | 1만 | **초당 50 반복(요청 ~250/초)** — 5xx 0 · p95 31ms. 100(요청 ~500/초)에서 게이트웨이 5xx 최대 30/초(그 단계 ~6%), 회차 전체 1.06% | 전체 267 / 506ms · home 421 / 671 · search 234 / 451 · product 214 / 390(대부분 100 단계와 첫 1분의 데움) | **catalog 의 DB 커넥션 대기** — `hikaricp_connections_pending` 0 → 30 · Tomcat 바쁜 스레드 2 → 46 · catalog p95 31 → 390ms 가 게이트웨이 5xx 와 같은 1분(08:56:30 ~ 57:45 KST). catalog 자신은 5xx 0 — 실패는 게이트웨이가 냈다(5xx 적분 1,475건 = 0.98%, k6 1.06% 와 맞음). 풀 5 × 레플리카 2 | | |
| 2026-10-18 | `704c6c53` | browse + catalog 풀 5 → 10(`pool load 10`) | 1만 | 50 까지 같음, 100 에서 게이트웨이 503 최대 28/초 — 회차 전체 **2.17%**(3,180건, 풀 5 의 두 배) | 전체 330 / 630ms · home 493 / 786 | **CPU — catalog · 게이트웨이 모두 상한(1 코어)에 붙었다**: 100 단계에서 `process_cpu_usage` catalog 0.98 ~ 0.999 · 게이트웨이 0.89 ~ 0.97. 커넥션은 10개를 다 쓰고도 대기 10 ~ 24 — CPU 가 모자라 스레드가 커넥션을 오래 쥔 결과(원인이 아니라 증상) | 풀 5 → 10 | **더 나빠짐** — 포화된 CPU 에 동시 실행만 늘어 응답이 길어지고 벌크헤드(25)가 더 넘쳤다. 되돌린다. ⚠️ **2026-10-19 정정: 이 회차는 풀을 바꾸지 못했다** — catalog 의 데이터소스는 `hikari.*`(`DB_POOL_SIZE`)를 읽지 않아 풀은 앞뒤 모두 Hikari 기본 10 이었다(spike 파드별 지표 `hikaricp_connections_max` = 10). `pool load` 가 바꾼 것은 파드 재시작 — 차이는 막 뜬 JVM 의 몫으로 본다. 고침: 풀 빈이 `hikari.*` 를 묶는다(`CatalogDataSourcePools` · `CatalogDataSourcePoolsTest` — 최대 5 · 최소 2 · 대기 5초 · 누수 감지 20초, `DB_POOL_SIZE` 가 먹힌다) |
| 2026-10-18 | `a94070e5` | browse + catalog · 게이트웨이 레플리카 2 → 3(`replicas load`, 풀 5) | 1만 | **100 반복/초(요청 ~500/초)까지 오류 < 1% — 임계값 처음 통과**: 회차 전체 0.14%(5xx 205건 · 4xx 0 · 429 0) | 전체 194 / 378ms · home 331 / 482 · search 159 / 300 · product 144 / 267 | 남은 5xx 205건은 엔드포인트에 고르게(search 87 · home 45 · product 38 · banners 35) | 레플리카 2 → 3 | 실패 1.06% → **0.14%** · p95 267 → **194ms** · p99 506 → **378ms** · 못 보낸 반복 48 → 33 — CPU 병목에 수평 확장이 들었다 |
| 2026-10-18 | `07155983` | browse + 게이트웨이 벌크헤드 25 → 100(새 이미지, 레플리카 2 · 풀 5) | 1만 | 50 까지 같음 — 100 에서 실패 회차 전체 **1.71%**(5xx 2,572) | 전체 **814 / 1,498ms** · home 1,038 / 1,790 | 100 단계에서 벌크헤드 남은 슬롯 100 → **0**, catalog 바쁜 스레드 **128** · DB 대기 **113** · catalog p95 0.03 → **1.3초** — 상한을 올리자 CPU 가 찬 catalog 안에 줄이 섰다 | 벌크헤드 25 → 100 | **나빠짐**(실패 1.06 → 1.71% · p95 267 → 814ms) → 25 로 되돌려 값을 근거와 함께 설정에 |
| 2026-10-18 | `a84e4acb` | browse — 벌크헤드 25 로 되돌린 이미지(레플리카 catalog · 게이트웨이 3 — 배포가 다시 건 HPA, Prometheus `up` 으로 확인 · 풀 5) | 1만 | **100 반복/초(요청 ~500/초)까지 오류 < 1% — 임계값 통과**: 회차 전체 0.17%(5xx 260 · 4xx 0 · 429 0) | 전체 153 / 300ms · home 269 / 419 · search 107 / 235 · product 92 / 206 | 5xx 는 모두 게이트웨이의 벌크헤드 거절(catalog 5xx 0, 적분 216건). 정점(요청 460 ~ 500/초, 14:58:15 ~ 15:00:15 KST)에서 catalog 슬롯이 한 번 0 · 곧 11 ~ 19 로 회복. catalog DB 대기 최대 **11** · 바쁜 스레드 **21** · catalog p95 ≤ 0.2초 — 상한 100 때(113 · 128 · 1.3초)와 달리 줄이 서지 않았다. 첫 1분 p95 0.4 ~ 0.68초는 배포 직후 데움 | 벌크헤드 100 → 25 | 상한 100(1.71% · p95 814ms)보다 크게 낫고, 레플리카 3 의 `a94070e5`(0.14% · p95 194ms)와 같은 수준 — **상한 25 + 수평 확장**이 맞다 |
| 2026-10-18 | `a84e4acb` | browse `RATE=150` — 캐시 전 기준(레플리카 catalog · 게이트웨이 3 고정 `replicas load 3` · 풀 5) | 1만 | **약 100 반복/초(요청 ~500/초)에서 막힘** — 목표 150 에도 catalog 가 처리한 홈은 초당 96 ~ 99, 회차 전체 실패 **21.41%**(5xx 48,438 · 4xx 0 · 429 0 · 응답 없음 0, 경로별 요청 수에 비례) | 전체 725 / 1,433ms · home 834 / 1,701 · product 651 / 1,250 · search 724 / 1,488 | **catalog 시간의 65% 가 홈**(`a84e4acb` RATE=100 회차, `http_server_requests_seconds_sum` 경로별 적분: home 1,922초 · search 632 · product 236 · banners 169). 요청당 home **27ms**(요청 ~125/초) — search 5 · product 3 · banners 2ms 의 5 ~ 13배. 홈 한 번이 섹션 조회 + 섹션마다 카테고리 id 조회(DB) + 섹션마다 상품 목록(Redis 역직렬화)을 읽기 트랜잭션 안에서 했다. RATE=150 에서는 catalog 시간 7,484초 · home 평균 121ms(정점 246) · search 28ms(정점 93) — 넘친 몫이 모든 경로를 늦췄다 | | |
| 2026-10-18 | `342a83ab` | browse `RATE=150` — 첫 화면 구성 캐시(`HomeViewCache` 30초, 같은 조건 · 레플리카 `3·3` 을 meta.txt 로 확인) | 1만 | 목표 150 에서 catalog 가 처리한 홈 초당 **146 ~ 148**(전: 96 ~ 99). 회차 전체 실패 **4.53%**(5xx 10,281 · 4xx 0 · 429 0 · 응답 없음 0) — 아직 임계값(1%) 넘음 | 전체 **337 / 743ms** · home **371 / 801** · product 309 / 695 · search 348 / 770 | 홈 요청당 **6.5ms**(부하 낮을 때 4ms, 전: 27 ~ 121) · 캐시 적중 **99.87%**(miss 58 = 파드 3 × 30초마다) · catalog 시간 7,484 → **1,285초(−83%)** · catalog CPU 정점 0.56 ~ 0.9. **다음 병목은 게이트웨이** — 마지막 단계에서 `process_cpu_usage` 0.97 ~ 1.0(1 코어 상한), 5xx 는 catalog 에 닿기 전(catalog 가 처리한 홈 43,267 + k6 의 홈 5xx 2,030 = 홈 전체) — **게이트웨이의 벌크헤드**: 게이트웨이 503 10,720건(16:18:15 ~ 18:45 에 초당 157 · 165 · 120), catalog 벌크헤드 남은 자리 25 → 0(16:17:45 ~ 18:15), 회로 차단기는 한 번도 열리지 않았다(open · 거절 · 실패 0). catalog 응답은 8 ~ 10ms 인데 게이트웨이 CPU 가 차서 호출이 게이트웨이 안에 머물렀다 | 첫 화면 구성을 파드 메모리에 30초(트랜잭션 밖 · 만료 때 한 스레드만 다시 만듦 · 재고는 응답마다 · 관리자 섹션 변경은 그 파드에서 바로 비움, `CONTENT_HOME_CACHE_TTL`, 지표 `catalog_home_cache_total`) | 실패 21.41 → **4.53%** · p95 725 → **337ms** · p99 1,433 → **743ms** · 못 보낸 반복 296 → 187 · 최대 VU 446 → 337 |
| 2026-10-18 | `342a83ab` | browse `RATE=150` + 게이트웨이 레플리카 3 → 4(`replicas load 4 gateway-server`, catalog 3 — meta.txt `3·4`) | 1만 | 회차 전체 실패 **2.47%**(5xx 5,609 — 게이트웨이 503 적분 4,505) — 아직 임계값 넘음 | 전체 313 / 732ms · home 360 / 823 · product 281 / 664 · search 324 / 752 | 4 파드에서도 회로 차단기는 줄곧 닫힘(열림 · 거절 · 실패 집계 0)이고 503 은 **75 → 150 으로 오르는 동안과 그 뒤 1분**(17:00:15 ~ 17:01:45, 최대 70/초 · 그 동안 게이트웨이 CPU 0.97 ~ 0.995)에 몰리고, 150 에 머문 17:02 부터는 초당 1 ~ 7(요청 ~780/초의 1% 미만) — 정상 상태의 용량보다 **올라가는 순간**의 문제 | 게이트웨이 3 → 4 | 실패 4.53 → **2.47%** · p95 337 → 313ms · 못 보낸 반복 187 → 127 — 늘린 만큼 줄었지만 오르는 순간의 503 은 남았다 |
| 2026-10-18 | `342a83ab`(이미지 그대로, 측정기 `1dd742b1`) | browse `RATE=150` · 게이트웨이 4 — **같은 조건을 데워진 파드로 다시**(앞 회차를 치른 파드, 롤링 없음) · 정점 90초에 async-profiler(itimer) | 1만 | **150 반복/초(요청 ~780/초)에서 실패 0.04%**(5xx 84) — 임계값 통과 | 전체 **36 / 74ms** · home 40 / 80 · product 32 / 68 · search 38 / 77 | 게이트웨이 CPU 정점 0.78 ~ 0.93(앞 회차 0.97 ~ 0.995) · 503 최대 0.9/초. 이미지 · 레플리카 · 도착률이 같은데 2.47% → 0.04% — **앞 회차의 '오르는 순간' 503 은 막 뜬 JVM(JIT 전 · 연결 · TLS 세션 없음)이 1 코어에서 치른 값**이었다. 프로파일(표본 6,421): **유입 제한 스크립트의 SHA 확인 18.7%** — `DefaultRedisScript.getSha1` → `ResourceScriptSource.isModified` 가 요청마다 jar 안의 lua 수정 시각을 보려 클래스로더로 중첩 jar 를 뒤지고(`NestedJarFile.hasEntry` · `URL.<init>`) `FileNotFoundException` 을 만든다, 그것도 전역 잠금 안에서. 그 밖에 TLS 서버 핸드셰이크 ~9%(RSA-PSS 서명 6.8% · X25519 2% — Traefik → 게이트웨이 새 연결), 요청마다 `SSLPeerUnverifiedException`(SslInfo, 1.8%), 지표 · 추적 5.2%, logback 3.8% | 스크립트를 기동 때 한 번 읽어 글자로 고정(`RateLimiterScriptConfig` — SCG 빈의 원본만 `StaticScriptSource` 로, 내용 · SHA1 그대로) | **차가운 파드(배포 직후, `3154b3b6`)로 같은 조건**: 실패 2.47 → **0.20%**(5xx 5,609 → 446) · p95 313 → **91ms** · p99 732 → **265ms** · 못 보낸 반복 127 → 51 · 최대 VU 277 → 201 — **임계값 통과**(데워진 파드 0.04% · 36ms 와의 차이가 남은 데움 비용) |
| 2026-10-18 | `3154b3b6` | browse `RATE=150` · `3·4` — 측정 중 10초마다 커널 연결 지표(`/proc/net/snmp` 의 PassiveOpens · ActiveOpens, `/proc/net/tcp` 의 :8000) | 1만 | 실패 0.02%(5xx 47) — 임계값 통과 | 전체 45 / 89ms · home 51 / 98 | **Traefik 이 게이트웨이 파드마다 쉬는 연결을 2개만 남겼다** — 한산할 때 Traefik → :8000 ESTABLISHED 가 정확히 8(파드 4 × 2), 정점에서도 파드마다 3 ~ 19 인데 새 연결은 파드마다 초당 30 ~ 60 · Traefik 합계 초당 ~220(게이트웨이들이 받은 수의 합과 같다) — 연결 하나가 ~80ms · 요청 ~3.5개. ServersTransport 에 `maxIdleConnsPerHost` 가 없어 Go 의 기본값(2)이 걸렸고, 동시 요청이 2개를 넘을 때마다 새 TLS 연결을 열고 쓰자마자 닫았다 — 프로파일의 TLS 서버 핸드셰이크 ~9% 의 정체 | ServersTransport `maxIdleConnsPerHost: 100`(데모만 — 운영은 cloudflared → 게이트웨이, `DemoOverlayTest`) | 같은 조건(`browse-20261018T103855Z`, 연결 표본 함께): 정점의 새 연결 파드마다 초당 30 ~ 60 → **0.1 ~ 5**(단계가 오를 때 잠깐), Traefik 합계 ~220 → **~0 ~ 5** · ESTABLISHED 는 동시 요청만큼 늘어 유지(파드마다 14 ~ 62, Traefik 쪽 합계 최대 133) — 연결을 재사용한다. 실패 **0.00%**(5xx 7) · p95 **42ms** · p99 **69ms** · 못 보낸 반복 0 · 최대 VU 150 |
| (측정 전) | | browse(데이터 규모) | 1,000만 | | | | | |
| 2026-10-18 | `3b53b0d2` | order `USERS=200 RATE=20`(레플리카 기본 — HPA 가 cart · catalog · order · user 를 2 → 3) | 1만 | 20 구매/초(요청 ~80/초)에서 실패 **1.66%**(4xx 795 — `cart_add` 786, 5xx 106) — 임계값 넘음(구매 한 번 p95 6.4초 > 6초 · `cart_add` p95 1,039 > 1,000ms) | 전체 665 / 1,582ms · cart_add 1,039 / 1,868 · order_create 1,220 / 1,955 · 구매 한 번 6,422 / 11,655 | 정점(20:08:15 ~ 20:10 KST)에 **cart · order 의 CPU 0.95 ~ 1.0**(파드 3개씩), DB 커넥션 대기 cart 14 ~ 15 · order 11 ~ 21(풀 5), p95 cart 1.2초 · order 1.25초 · 게이트웨이 1.38초, 게이트웨이 벌크헤드 남은 자리 cart 3 · order 9 → 게이트웨이 503 ~0.85/초 1분(5xx 의 대부분). **요청당 CPU 가 크다** — cart ~93/초를 파드 3개가 1 코어씩 써서 요청당 ~32ms, order ~74/초 → ~40ms(홈 4ms 의 8 ~ 10배). catalog 는 cart · order 의 서비스 간 호출로 초당 ~189(바깥 요청의 2.4배). 그리고 **담기 실패 786건이 모두 400** — cart 가 어떤 예외든 400 으로 냈다. 남은 로그의 536건은 **모두 같은 `NullPointerException`**(`getSize().equals(…)`): 사이즈가 하나뿐인 상품("single" — 카메라 · 헤드폰 · 가방 …)은 사이즈 이름이 빈 값인데, 생성기의 CSV `COPY` 가 따옴표 없는 빈 칸을 **NULL** 로 넣었다(시드 배치는 `''`). 부하가 아니라 그 상품을 고른 반복마다 실패했다 | ① cart 의 사이즈 비교 셋을 `normalize`(빈 값 = null = `''`)로(`CartOneSizeTest`) ② 생성기 `COPY … FORCE_NOT_NULL (size)` — `''` 로(`DemoCatalogSeedIntegrationTest`: NULL 0 · `''` 있음) ③ 실패 응답: 고칠 수 있는 요청(`IllegalArgumentException`)만 400, 나머지 503(`CartController.failure`, `CartFailureStatusTest`). 이미 넣은 데모 데이터는 한 번 `UPDATE product.product_size SET size = '' WHERE size IS NULL` + `ops/44 flush-cache` | (측정 전 — 다음은 cart · order 의 요청당 CPU 를 프로파일로) |
| 2026-10-18 | `3154b3b6` | order `USERS=200 RATE=20`(데모 DB 의 NULL 사이즈를 `''` 로 고친 뒤 — 담기 4xx 786 → 7) · cart · order 프로파일 | 1만 | 실패 **6.33%**(5xx 2,257 — `order_create` 1,225 · 응답 없음 370 · 못 보낸 반복 623) — 임계값 넘음 | 전체 1,128 / 4,467 · cart_add 1,659 / 4,538 · order_create 4,161 / 30,220 · 구매 한 번 7,339 / 10,914 | 반복이 모두 주문까지 가자 **order 가 먼저 찼다** — 21:47:45 ~ 48:00 KST order DB 커넥션 대기 54 ~ 57(풀 5) · p95 1 → 4.1초 · CPU 1.0 · 게이트웨이 order 벌크헤드 남은 자리 0 → 503. **원인은 부하가 아니라 노드 하나** — 21:47:39 KST **server-node VM(k3s 서버 · 4 vCPU · 앱도 올라간다)이 게스트 스스로 리셋**했다(호스트 하이퍼바이저의 QMP `RESET` `guest-reset` 21:48:07, VM 안에는 종료 · 충돌 기록 없음, 21:48:13 새 부팅). 그 노드에 있던 **하나뿐인 CoreDNS** 와 order · cart · user · pay · eureka · saga 파드가 함께 사라져(`SandboxChanged` · `Unknown exit=255`) 클러스터 전체의 이름 풀이가 끊겼고 — 다른 노드의 order 는 다시 기동하다 `UnknownHostException: postgres.postgres.svc.cluster.local` 로 `exit=1` — order 의 DB 대기 · p95 폭발도 그 직후다(21:47:30 까지는 p95 0.84초). liveness · 노드 OOM 은 아니었다. 호스트는 vCPU 42 / 24 스레드 · VM 메모리 ~117 / 125 GiB 과할당, 21:48:45 ~ 50:00 order 지표 없음 · 게이트웨이 503 초당 최대 20. **cart 프로파일**(정점 90초, 표본 4,213): Feign 33% · 그중 **새 TLS 핸드셰이크 23%**(`HttpsClient.afterConnect` — 모두 `CartService` 의 catalog 조회), JIT 컴파일 37%(배포 10분 뒤라 아직 데워지는 중), Hibernate 8% · 예외 생성 6%(대부분 TLS 연결 정리) | 서비스 간 호출의 쉬는 연결: JDK `http.maxConnections` 기본 5 → 64(`HttpKeepAlive` — 실행 리스너, `HTTP_KEEPALIVE_MAX_CONNECTIONS`). 같은 모양(HTTPS · 32 스레드 · 호출 사이 지수 분포 대기 · 1,600 호출)에서 연결 234 → 32(`HttpKeepAliveTest` 가 따로 띄운 JVM 으로 잰다) — 데모 Traefik 의 쉬는 연결 2개와 같은 결함 | (측정 전) |
| 2026-10-18 | `43ea80d6` | order `USERS=200 RATE=20` — server-node 테인트 · CoreDNS 2개 · 쉬는 연결 64 · cart 503 | 1만 | 실패 **1.09%**(5xx 539 — cart 쪽 535: 담기 159 · 빼기 167 · 저장 113 · 체크아웃 96, `order_create` 4, 응답 없음 0) — 임계값 넘음(구매 한 번 p95 6.15초 > 6초 · `cart_add` p95 2,058ms) | 전체 1,135 / 2,010 · order_create 1,318 / 1,931 · 구매 한 번 6,151 / 7,303 | 노드 리셋 · DNS 장애 없음. 남은 실패는 cart(정점에서 503). **호스트가 천장** — vmstat 정점에 유휴 16 ~ 19% · VM 몫 62 ~ 66% · 대기열 20 ~ 38(24 스레드), swap 입출력 0 → 이 이상 RATE 는 호스트를 잰다. **order 프로파일**(120초, 표본 6,562): Hibernate 20% · JIT 25% · Feign 15% — 그중 **2/3 가 여전히 새 TLS 핸드셰이크**(`getCart` → cart · `getColorsInfo` → catalog), 이미지에 `HttpKeepAlive` 가 있는데도. 원인: Spring MVC 의 JSON 은 chunked 이고 **TLS 위에서** Jackson 이 끝 청크 전에 닫으면 JDK 가 연결을 캐시에 넣지 않고 버린다(평문 HTTP 는 닫을 때 남은 끝 청크를 읽어 살린다 — 그래서 HttpKeepAliveTest 의 Content-Length 응답으로는 안 보였다). 그 밖에 로그 마스킹 정규식 ~3% · Hikari 누수 감지(빌릴 때마다 예외 생성) ~2% | 응답을 끝까지 읽는 Feign Capability(`FeignResponseBody` — 자동 설정, 모든 Feign 클라이언트): 실제 OpenFeign 배선 · HTTPS · chunked JSON 50 호출에 연결 **50 → 1**(`FeignResponseBodyTest`). cart 프로파일은 이번 회차 것이 아니었다(지난 파일이 남아 있었다 — 스크립트 2판의 `kubectl cp` 는 원본이 없어도 0 을 돌려준다) | (측정 전) |
| 2026-10-18 | `8fd5bc00` | order `USERS=200 RATE=20` — Feign 응답 끝까지 읽기 · 본 측정 전 데우기(`USERS=50 STAGES=1m:5,2m:10,30s:0`, 실패 0.13%) | 1만 | 실패 **0.25%**(5xx 4 · 4xx 150 = 입금 안내 75 + 그 취소 75, 응답 없음 0) — **임계값 통과** | 전체 **77 / 281** · cart_add 85 / 298 · order_create 123 / 271 · 구매 한 번 **2,102** / 4,518 | 직전 회차(`43ea80d6`)와 같은 조건: 실패 1.09 → 0.25% · p95 1,135 → 77ms · cart_add p95 2,058 → 85 · 구매 한 번 p95 6,151 → 2,102. 정점 120초의 CPU 표본 — order 6,562 → **4,040**(−38%), cart 4,213(그 전 회차) → **1,947**(−54%). 새 TLS 연결은 order 9.7% → **0.6%** · cart 23% → **2.5%**. 호스트 vmstat 바쁜 20% 구간의 유휴 16 ~ 19% → **42%**(대기열 중앙 19). 남은 몫: Hibernate(order 24 · cart 25%) · JIT 컴파일(데웠어도 order 23 · cart 17%) · 로그 마스킹 정규식(order 3.6%) · Hikari 누수 감지(order 3.4 · cart 2.2%) · 예외 생성 4 ~ 7% | — | — (데우기의 몫이 섞여 있다 — JIT 은 줄었지만 새 연결의 감소가 표본 수 감소의 대부분) |
| 2026-10-19 | `8fd5bc00` | spike(`spike-20261018T151355Z` — 평소 20 → 20초 만에 200 반복/초 · 3분, 레플리카 기본 = HPA 최소 2 · 최대 3) | 1만 | 정점(요청 ~1,000/초)에서 막힘 — 회차 전체 실패 **21.35%**(5xx 39,728 · 4xx 0 · 429 0 · 응답 없음 0, 엔드포인트별 요청 수에 비례) · 못 보낸 반복 9,203 · 최대 VU 800 | 전체 3,554 / 6,481ms · product 3,470 / 5,882 | **게이트웨이 CPU × HPA 최대 3** — 10초 간격 시간표(Prometheus, KST): catalog 는 평소 단계에 2 → 3(00:15:15, 정점 전). 정점(00:16:25 ~ 19:45)에 게이트웨이 CPU 98 ~ 100% · 벌크헤드 남은 자리 0 ~ 6 · 503 초당 130 ~ 310(폴백 예외는 모두 `BulkheadFullException` — 시간 초과 504 0). 처음 40초는 catalog 도 찼고(CPU 84 ~ 94% · p95 ~100ms) 그 뒤 catalog 는 CPU 33 ~ 58% · p95 15 ~ 30ms 로 여유인데 503 은 그대로 — 호출이 게이트웨이 안에 머물렀다(게이트웨이 p95 0.2 → 2.9 ~ 4.2초). 게이트웨이는 정점 60초 뒤(00:17:15)에야 3 — 새 파드는 CPU 26 ~ 65%(나머지 둘은 100%) · 그 파드의 catalog 회로가 20초 열림 · 지표 수집이 한 번 빠졌다(00:18:35 ~ 55). 내리자 10초 안에 5xx 0 — 쌓이지 않고 바로 끊었다(부하 차단). 호스트(vmstat) 유휴 ≥ 32% — 호스트가 아니라 **파드당 1 코어 × 최대 3** 이 천장: 데워진 게이트웨이 4개가 ~780/초에서 CPU 0.78 ~ 0.93 이었으니(위) 1,000/초에는 5 ~ 6개. 폴백 로그가 159줄뿐인 것은 kubelet 의 로그 회전(10Mi)으로 보인다 — `kubectl logs` 는 마지막 파일만 | 측정 도구: HPA 최대만 올리는 `replicas max <수>`(최소 · 레플리카는 HPA 에 — `replicas load` 는 최소도 올려 HPA 가 따라가는 동안을 잴 수 없었다, `LoadK6ScriptTest`) · order 는 입금 안내가 성공한 주문만 취소(`8fd5bc00` 회차의 4xx 150 = 상한에 걸린 계정 하나의 안내 75 + 그 취소 75) | 아래 두 행(미리 6 · HPA 최대 6) |
| 2026-10-19 | `8fd5bc00` | spike — 게이트웨이를 **미리 6**(`spike-20261018T154226Z`, `replicas load 6 gateway-server` 직후 · catalog 는 HPA 2 ~ 3 그대로 — 정점 시작 때 2) | 1만 | 실패 **35.71%**(5xx 82,694 · 4xx 0 · 429 0) — 더 나빠짐. 못 보낸 반복 101 · 최대 VU 301 | 전체 694 / 1,637ms · product 696 / 1,508 | **병목이 catalog 로 옮겼다** — 게이트웨이 CPU 는 정점 후반 32 ~ 60%. catalog 2개가 CPU 100%(00:45:06 ~ 56 KST), 3개가 된 뒤(00:46:06)에도 90 ~ 95%. catalog 안(`VIEW=catalog`): Tomcat 바쁜 스레드 125 ~ 170 · DB 커넥션을 기다리는 스레드 90 ~ 140 · 받기까지 93 ~ 689ms · 쥔 시간 2 → 22 ~ 49ms — 처리량 ≈ 쓰는 커넥션 ÷ 쥔 시간(00:45: 20 ÷ 26ms ≈ 770 → 실측 660 ~ 700, 00:47:36: 10 ÷ 49ms ≈ 204 → 실측 193). GC 멈춤은 가장 나쁜 파드 초당 80 ~ 98ms. 게이트웨이 6 × 벌크헤드 25 = catalog 로 동시 **150**(3개일 때 75) — 벌크헤드 25 → 100 실험과 같은 일: 포화된 catalog 안에 줄이 서 응답 0.5 ~ 2.4초 → 벌크헤드가 차 503 초당 최대 800. **00:47:16 ~ 48:06(파드별 `VIEW=pods`)**: 막 뜬 세 번째 파드(00:46:10)가 CPU 100% 인 채 커넥션을 **173 → 37ms** 쥐어(데워진 파드 25ms) 바쁜 스레드 151 · 기다림 139 로 막혔고, **데워진 두 파드는 요청이 초당 13 ~ 45 · CPU 5 ~ 14% 로 놀았다** — 게이트웨이의 벌크헤드는 catalog **서비스** 하나에 25(인스턴스마다가 아니다)라, 느린 인스턴스로 간 호출(~2초)이 자리를 차지하고 빠른 인스턴스로 갈 호출(~10ms)까지 거절됐다. 합계의 '쓰는 커넥션 10 · CPU 36 ~ 52%' 는 새 파드 하나만 일한 값이다. 풀은 파드당 최대 10 — catalog 는 yml 의 `hikari.*` 를 읽지 않는다(아래) | — | — |
| 2026-10-19 | `8fd5bc00` | spike — 게이트웨이 **HPA 최대만 6**(`spike-20261018T155847Z`, `replicas max 6 gateway-server` — 최소 2 · catalog HPA 2 ~ 3) | 1만 | 실패 **20.81%**(5xx 43,511 · 4xx 0 · 429 0) — 최대 3 일 때(21.35%)와 같다. 못 보낸 반복 4,603 · 최대 VU 800 | 전체 2,807 / 5,871ms · product 2,579 / 5,576 | **HPA 는 따라갔지만 늦고, 그다음 천장은 catalog 최대 3.** 정점(01:01:00 KST) 뒤 게이트웨이 2 → 3(01:02:07) → 6(01:02:37) — 반응 ~65초 · 6 까지 ~95초, catalog 2 → 3(01:02:27). 그 전 1분은 첫 회차와 같다(게이트웨이 2개 CPU 97 ~ 100% · catalog 2개 91 ~ 99% · 503 초당 130 ~ 284). 새 게이트웨이 파드가 들어온 30초(01:02:17 ~ 47)에 처리량이 초당 ~960 → **250 ~ 320** 으로 꺼졌다 — 새 파드 CPU 24 ~ 33% · 게이트웨이 p95 1.0 ~ 3.5초 · 그 파드의 catalog 회로가 열림: 막 뜬 JVM 이 바로 제 몫을 받아 느리게 답하고 k6 의 VU 가 거기 묶였다(데움 비용 — 위 browse 의 차가운 파드 2.47% · 데워진 파드 0.04% 와 같은 것). 그 뒤 6개에서 게이트웨이는 여유(가장 낮은 파드 55 ~ 74%)인데 catalog 3개가 CPU 88 ~ 97% · p95 0.3 → 0.85초 · 503 초당 163 ~ 356 — catalog 3개의 용량(browse 에서 ~780/초 통과)이 정점 ~1,000/초보다 작다. 내리자 10초 안에 5xx 0 | — | — |
| 2026-10-19 | `4b720b13`(game-day 이미지 — improve `8f6d3632` 와 같은 코드) | browse `RATE=150` — catalog 풀이 yml 대로(최대 5 · 쉬는 연결 2) · `3·5`(게이트웨이 HPA 최대 6 이 남아 5) | 1만 | 실패 **0.01%**(5xx 21 · 4xx 0 · 429 0) — 임계값 통과 | 전체 32 / 69ms · home 36 / 74 · product 29 / 65 · search 34 / 73 | 풀 10 → 5 에도 정점(catalog ~800/초)에서 쓰는 커넥션 합 최대 7(3 파드 × 5 = 15 중) · 기다림 0 · 쥔 시간 4 ~ 5ms · catalog CPU 70 ~ 79% — 풀은 모자라지 않다. 직전 회차(`165013Z`)는 배포 직후라 setup 에서 멈췄다(exit 107 — 앞서와 같은 차가운 파드) | 풀이 yml 대로(`CatalogDataSourcePools`) | 지난 기준선(0.00 ~ 0.07%, p95 42 ~ 51ms)과 같은 수준 — 데이터 규모 측정의 기준선 |
| 2026-10-19 | `4b720b13` | browse 데이터 규모 `PRODUCTS=10000 RATE=50` — catalog · 게이트웨이 **3 고정**(`replicas load 3`) | 1만 | 실패 **0.00%**(5xx 0) — 임계값 통과 | 전체 14 / 18ms · product_cold 16 / 21 · search_deep 11 / 15 · name_search 16 / 19 · search 12 / 16 · home 13 / 17 | catalog 정점(~280/초) CPU 29% · 커넥션 대기 0 · 쥔 시간 3ms · 서버 p95 product 11 ~ 13 · search 7ms. 고정하기 전 회차(`171412Z`, 0.34% · p99 555ms)는 측정 중 게이트웨이 2 → 3 — 새 파드가 CPU 100% 인 30초에 처리량 260 → 163 · 게이트웨이 p95 302ms, 5xx 267 은 게이트웨이 지표에 거의 없어 앞의 Traefik 이 낸 것으로 본다 | 레플리카 고정 | 데이터 규모의 기준선 |
| 2026-10-19 | `4b720b13` | browse 데이터 규모 `PRODUCTS=1000000 RATE=50` · 3 고정(productdb 1,878 MB — product 660 · size 602 · sku 491 MB) | 100만 | 실패 **53.61%**(5xx 42,338 — search 10,161 · 나머지 경로마다 ~7,500) — 무너짐 | 전체 2,016 / 3,791ms · search 3,018 / 4,922 · product_cold 1,818 / 3,402 | 02:49:54 KST 부터 search 서버 p95 7 → **469ms**(CPU 20 ~ 40% 로 여유) · 02:52:24 도착률이 오르자 풀 15 가 다 차고 기다림 30 ~ 67 · 쥔 시간 150 ~ 315ms · **GC 초당 370 ~ 690ms** · home · banners 까지 p95 2 ~ 5초 → 게이트웨이 벌크헤드 503. **원인: 첫 쪽 검색의 필터 메뉴** — 색상 · 사이즈 · 상세가 카테고리의 상품 id 를 전부 읽어 `IN (:ids)` 로 펼쳤고, 상품이 65,535 를 넘는 카테고리(100만에서 fashion ~12만 · books ~13만 · electronics · home & kitchen ~8만)는 `PSQLException: PreparedStatement can have at most 65,535 parameters`(catalog 로그 96건) — 그 검색은 매번 실패했고 실패는 `search_cache` 에 남지 않아 요청마다 다시 돌았다. 브랜드는 DISTINCT 없이 상품마다 한 줄(~12만 문자열), 메뉴 메서드의 `@Cacheable` 은 같은 클래스 안 호출이라 한 번도 걸리지 않았다. 상품 1만에서는 카테고리당 수백 개라 보이지 않았다 | 필터 메뉴를 따로 된 빈 `SearchFilterFacets` 로 — id 를 앱으로 가져오지 않고 DB 안에서 조인 + DISTINCT, 카테고리마다 캐시(`brands_cache` 새로 · 10분 · sync), 개수 세기 · 목록은 상세 필터가 있을 때만 상세 표를 조인(`SearchFilterFacetsScaleTest` — 실제 PostgreSQL 에 한 카테고리 7만 개, 예전 코드는 운영과 같은 `Given query has 70,000 parameters` 로 실패 · `SearchFilterFacetsCacheTest`) | 힙 · V20 과 함께 다시(아래 `browse-20261019T082436Z`): **0.00%** · p95 28ms — 이 수정 혼자의 몫은 따로 재지 않았다 |
| 2026-10-19 | `8c716f3a`(필터 메뉴 수정 이미지) | browse 데이터 규모 `PRODUCTS=1000000 RATE=50` · 3 고정 — 배포 · `flush-cache` 직후 | 100만 | 실패 **99.01%**(5xx 78,011) — catalog 가 측정 내내 죽었다 살았다(파드 3 → 1 → 3 …, 재시작 4 ~ 6회) | p95 17 · p99 **10,004ms**(게이트웨이 시간 초과) | catalog **힙 256MB**(`MaxHeapSize` 268435456 — `JAVA_OPTS` 가 없어 JVM 기본 = 컨테이너 한도 1Gi 의 25%)가 차서 `OutOfMemoryError: Java heap space` → GC 초당 774 ~ 1,115ms → liveness 실패로 강제 종료(exit 137). 할당 프로파일(RATE=10 · 45초)은 합계 14.5MB — 요청 하나가 큰 것을 올리는 게 아니라 **동시에 처리 중인 요청들이 작은 힙을 채웠다**(배포 · 캐시 비우기 직후라 모든 검색 조합이 처음부터 계산). 실행 계획: 카테고리 개수 세기 · 브랜드 DISTINCT 가 category_id 를 맨 뒤 열로 가진 `idx_product` 를 통째로 훑었다 — books(13만) 한 번에 92,226쪽(≈ 720MB) · 93 ~ 105ms(목록은 기본 키 역순 0.9ms) | ① 런타임 이미지 `-XX:MaxRAMPercentage=50`(`JVM_MAX_RAM_PERCENTAGE`, 모든 서비스 — 768Mi 는 힙 192 → 384MB, catalog 1Gi 는 256 → 512MB, `RuntimeImageHeapTest`) ② catalog V20 `product(category_id, product_id)` 인덱스(`ProductCategoryIndexMigrationTest` — V19 → V20 의 실행 계획) | 실패 99.01 → **0.00%** · p99 10,004 → **37ms** · 재시작 0 · GC 초당 774 ~ 1,115 → **1 ~ 4ms** (다음 줄) |
| 2026-10-19 | `4b720b13`(측정기 — 이미지는 힙 50% · V20 이 든 것, catalog `MaxHeapSize` 536870912 확인) | browse 데이터 규모 `PRODUCTS=1000000 RATE=50` · 3 고정 — 배포 · `flush-cache` 뒤 | 100만 | 실패 **0.00%**(5xx 0 · 4xx 0 · 429 0 · 응답 없음 0, 요청 79,310) — **임계값 통과** | 전체 28 / 37ms · home 15 / 26 · search 14 / 22 · search_deep 14 / **121** · name_search 17 / 29 · product_cold **34 / 44** | catalog(정점 요청 ~300/초) CPU 평균 12 ~ 31% · 커넥션 대기 **0**(측정 내내) · 쥔 시간 5 ~ 6ms · GC 파드마다 초당 0 ~ 4ms · **힙 사용률 19 ~ 30%**(512MB 의 ~100 ~ 150MB — 256MB 였다면 40 ~ 60%). 서버 p95 search 경로는 첫 1분 반만 1,721 → 506 → 331 → 94ms(검색 조합을 처음 계산 — 첫 쪽 · 깊은 쪽 · 이름 검색의 `search_cache` 가 비어 있다), 17:26 부터 **7 ~ 8ms**. 1만 기준선(같은 조건)과 견주면 p95 14 → 28ms · product_cold 16 → **34ms**(무작위 100만 개 — Redis 에 없고 PostgreSQL 버퍼를 읽는다) · search_deep p99 15 → 121ms. ⚠️ **이 회차는 데이터 크기보다 캐시를 많이 쟀다**: search · search_deep · name_search 는 카테고리 ~20 × 쪽 50 · 이름 검색어 16개가 전부라 첫 계산 뒤로는 `search_cache`(60분)에서 나온다 — 100만에서의 개수 세기 · `LIKE '%…%'` 의 값은 첫 1분 반(서버 p95 0.3 ~ 1.7초)뿐이다. 두 수정(힙 · 인덱스)의 몫도 나눠 재지 않았다 | — | — (다음: 캐시 밖 비용을 따로 — 검색 조합을 넓히거나 첫 계산만 잰다, 그다음 1,000만) |
| 2026-10-19 | `9afdc415`(측정기 — 이미지는 앞 회차와 같다) | browse 데이터 규모 `PRODUCTS=1000000 RATE=50 SPREAD=1`(깊은 쪽 2 ~ 1000 · 이름 검색 1 ~ 50쪽) · catalog 3(시간표 — `PROM` 을 주지 않아 meta 에 레플리카 없음) · `flush-cache` 뒤 | 100만 | 실패 **0.00%**(5xx 0 · 4xx 0 · 요청 79,366) — 임계값 통과 | 전체 114 / 265ms · name_search **419 / 562**(p50 8) · search_deep **161 / 236**(p50 9) · product_cold 49 / 72 · search 14 / 20 · home 15 / 23 | **캐시 밖 비용이 처음으로 보였다** — 서버 search 경로 p95 가 측정 대부분(19:02 ~ 19:10 KST) 100 ~ 440ms(캐시 회차 `082436Z` 는 1분 반 뒤 7ms), 커넥션을 쥔 평균 시간 26 ~ 46ms(캐시 회차 5 ~ 6ms). 시간은 PostgreSQL 안이다 — catalog CPU 8 ~ 29% · 커넥션 대기 최대 2 · GC 초당 1 ~ 4ms. 넓힌 뒤에도 p50 이 8 ~ 9ms 라 절반 넘게는 캐시였다: 평균으로 거칠게 거슬러 세면 이름 검색 ~20% · 깊은 쪽 ~25% 가 처음 보는 조합(이름 검색 16 × 50 = 800 조합을 ~2,800번이 다 써서 19:10 부터 서버 p95 8ms). 놓친 한 번의 값: 이름 검색 **0.4 ~ 0.56초** — 실행 계획(`hoodie`, 100만): 개수 세기 **239ms**(`idx_product` 를 통째로 Index Only Scan + `lower(name) LIKE` 필터, 버퍼 565,387쪽 · Heap Fetches 100,700) + 30쪽 목록 **179ms**(Parallel Seq Scan 48,872쪽) = ~420ms. 앞 와일드카드라 인덱스로 좁히지 못한다(docs/19 §6.1). 깊은 쪽 **0.16 ~ 0.24초** — OFFSET 자체는 원인이 아니다: 가장 큰 카테고리(13만)의 900쪽(OFFSET 8,990)이 `idx_product_category` 역순으로 **7.5ms**(V20). 남은 몫은 같은 요청의 개수 세기 · 필터 메뉴 · 상품 변환으로 본다(다음 실행 계획) | — | (다음: 이름 검색 `pg_trgm` 후보 · 깊은 쪽은 개수 세기의 실행 계획) |
| 2026-10-19 | `9afdc415`(측정기 — 이미지는 앞 회차와 같다) | browse 데이터 규모 `PRODUCTS=1000000 RATE=10 SPREAD=1 FILTER_SEARCH=0.3 STAGES=1m:10,2m:10,30s:0` — 필터 검색(가격대 · 색상 · 사이즈)을 섞어 짧게 | 100만 | 실패 **93.76%**(5xx 7,187 — 모든 경로 · 4xx 7) — 무너짐 | 전체 p95 10,004ms(게이트웨이 시간 초과) · 못 보낸 반복 296 | 시작 20초(19:44:42 KST)에 GC 초당 73 → 109ms · CPU 34 → 52%, 19:44:52 에 catalog 3 → 1, 19:46 ~ 19:48 CPU 100% · 바쁜 스레드 30 · 커넥션 대기 25, 19:49 에야 3개로 — 직전 파드 로그에 **`OutOfMemoryError: Java heap space` 11건**(`PSQLException` 0 — 바인드 변수 한도에 닿기 전에 힙이 찼다). **원인: 필터 검색**(`SearchService.skuFilterIds`): 후보 SKU 를 **카테고리와 상관없이 전부** 앱으로 읽었다 — 가격대는 하한 이상인 사이즈 줄 전부(100만 상품의 수백만 줄)를 `Object[]` 로 올려 기획전 맵으로 할인가를 따졌고, 맞는 상품 id 를 `IN (…)` 으로 다시 보냈다. 초당 3번이면 512MB 힙이 20초를 못 갔다. 상품 1만에서는 수만 줄이라 보이지 않았다 | 판정을 DB 안으로 — 상관 서브쿼리 `EXISTS (SKU ⋈ 사이즈 [⋈ 색상] where 상품 = 바깥 상품 and 할인가 between …)`, 바깥 질의의 카테고리 조건과 함께 돈다. 할인가 식은 그대로(`round(정가 × (100 − least(greatest(상시, 기획전), 100)) / 100, 2)`), 기획전은 펼치기 전의 규칙(`EventPricing.rules()` — 상품 지정 · 카테고리 지정)을 높은 율부터 CASE 로(펼친 맵은 카테고리 하나면 13만 개라 실을 수 없다). 사이즈 · 색상은 정규식(`~*`) 대신 값마다 앞부분 LIKE(글자 그대로 · 대소문자 무시). 옛 두 질의는 지웠다. `SearchSkuFilterScaleTest`(실제 PostgreSQL — 7만 개 카테고리 · 상시 30% · 상품 50% · 상품 40% + 카테고리 20% 겹침 · 경계 포함 · `L` 이 `XL` 을 고르지 않음; 예전 코드는 세 시험 모두 `Given query has 70,001 parameters`) · `SearchServiceUnitTest` · `EventPricingTest` | 실패 93.76 → **0.00%**(다음 줄 `115055Z`) · catalog 재시작 0 · filter_search p95 1,086ms |
| 2026-10-19 | `9afdc415`(측정기 — 이미지는 필터를 DB 안으로 옮긴 것) | 같은 조건 다시 — `PRODUCTS=1000000 RATE=10 SPREAD=1 FILTER_SEARCH=0.3 STAGES=1m:10,2m:10,30s:0` · `flush-cache` 뒤 | 100만 | 실패 **0.00%**(요청 9,313 · 5xx 0 · 4xx 0) — 임계값 통과 | 전체 382 / 853ms · filter_search **1,086 / 1,869**(p50 227) · name_search **1,290 / 1,964**(p50 418) · search_deep 369 / 463(p50 155) · product_cold 80 / 132 · search 25 / 64 · home 29 / 109 | 메모리 문제는 사라졌다 — catalog 재시작 0 · GC 초당 6ms 이하 · CPU 11 ~ 37%. 시간은 DB 안이다: 커넥션을 쥔 평균 시간 50 ~ 138ms(정점 20:52:15 ~ 35 KST — 쓰는 중 15 · 서버 search p95 1.1 ~ 1.26초), 커넥션 대기는 최대 11ms. RATE=10 이라 거의 모든 검색이 처음 보는 조합이다 — p50 이 캐시 밖 한 번의 값. name_search p50 418 은 실행 계획의 합(개수 세기 239 + 목록 179)과 같다. 실행 계획(카테고리 3 개수 세기)은 20.8ms(Index Only Scan · Heap Fetches 13,163)인데 search_deep p50 은 155ms — ⚠️ 그 실행 계획에는 앱의 질의에 늘 붙는 `status <> 'DELETED'`(Product 의 `@SQLRestriction`)가 빠져 있었다. 조건을 넣은 실행 계획으로 확인했다: 카테고리 3 개수 세기 **102ms**(Bitmap Heap Scan — 표 46,010쪽을 다시 읽어 status 를 본다), 이름 검색 개수 세기 **178ms**(Parallel Seq Scan). 상품은 모두 ACTIVE(100만) — 조건이 거르는 줄은 없는데 비용만 든다 | catalog **V21** — 살아 있는 상품만의 부분 인덱스 둘(조건은 앱의 `status <> 'DELETED'` 와 글자 그대로): `(category_id, product_id)`(개수 세기가 Index Only Scan) · `gin (lower(name) gin_trgm_ops)`(`pg_trgm` — PostgreSQL 13 부터 신뢰된 확장이라 DB 소유자 `product` 가 만든다). db_modeling `training/ecommerce/DESIGN.md` §7 의 원칙 2 · 3(늘 붙는 조건은 부분 인덱스 · `LIKE '%…%'` 는 트라이그램)과 같은 처방. `ProductLiveIndexesMigrationTest`(슈퍼유저가 아닌 DB 소유자로 Flyway, 측정과 같은 모양 — 가장 큰 카테고리 13% · 줄 폭 ~1.4KB; V20 은 Bitmap Heap Scan · 이름은 전체 스캔 → V21 은 `Index Only Scan using idx_product_category_live` · `idx_product_name_trgm`, 값을 모르는 일반 계획에서도) | V21 확인(다음 두 줄 `132228Z` · `133825Z`): SPREAD 회차 p95 114 → **65ms** · 필터 검색 p95 1,086 → **544ms** |
| 2026-10-19 | `94fb8595`(측정기 — 이미지는 V21 이 든 것) | V21 확인 ① — `PRODUCTS=1000000 RATE=50 SPREAD=1`(앞 회차 `100143Z` 와 같은 조건) · catalog 는 2 → 3(HPA, 22:23:58 KST — 고정하지 않았다) | 100만 | 실패 **0.00%**(요청 79,335) — 임계값 통과 | 전체 **65 / 88ms**(앞 114 / 265) · name_search **106 / 153**(앞 419 / 562) · search_deep **78 / 98**(앞 161 / 236) · product_cold 37 / 53(앞 49 / 72) · search 15 / 26 · home 17 / 31 | 캐시 밖 한 번의 값이 줄었다 — 서버 search 경로 p95 가 측정 대부분 60 ~ 99ms(앞 회차 100 ~ 440ms), 커넥션을 쥔 평균 시간 7 ~ 26ms(앞 26 ~ 46ms). 이름 검색은 트라이그램 인덱스로 ~4배, 깊은 쪽은 개수 세기가 인덱스만 읽어 ~2배. 22:30:38 부터 서버 p95 ~10ms — 조합을 다 써서 캐시에서 나온다(앞 회차와 같은 모양). catalog CPU 16 ~ 58% · 커넥션 대기 최대 2ms · GC 초당 5ms 이하 | — | 앞 회차 대비: p95 114 → 65 · name_search p95 419 → 106 · search_deep p95 161 → 78 |
| 2026-10-19 | `94fb8595` | V21 확인 ② — `PRODUCTS=1000000 RATE=10 SPREAD=1 FILTER_SEARCH=0.3 STAGES=1m:10,2m:10,30s:0`(`115055Z` 와 같은 조건) · catalog **2**(앞 3) | 100만 | 실패 **0.00%**(요청 9,264) — 임계값 통과 | 전체 69 / 310ms(앞 382 / 853) · filter_search **544 / 929**(p50 185 — 앞 1,086 / 1,869 · p50 227) · name_search 12 / 18 · search_deep 13 / 73 | 필터 검색만 비교된다 — 이름 검색 · 깊은 쪽은 p50 8ms 로 ①이 15분 전에 채운 `search_cache`(60분)에서 나왔다(`flush-cache` 없이 이어 돌렸다; 필터 검색은 가격대가 거의 겹치지 않아 처음 보는 조합). 서버 search 경로 p95 92 ~ 259ms(필터 검색이 대부분), 커넥션 대기 0 · CPU 4 ~ 15%. 필터 검색의 남은 몫은 개수 세기 · 목록의 EXISTS — 카테고리의 상품마다 SKU · 사이즈 줄을 보는 세미 조인(13만 개 카테고리) | — | filter_search p95 1,086 → 544 · p99 1,869 → 929. 남은 후보: 상품마다 '보이는 가격'의 최소 · 최대를 미리 두면 SKU 줄을 보지 않고 거를 수 있다(쓰기 · 기획전 갱신 비용이 생긴다 — 재 보고 정한다) |
| 2026-10-19 | `94fb8595` | mixed 의 기준 — `PRODUCTS=1000000 RATE=50`(넓히지 않음, 쓰기 없음) · catalog 2 → **3**(HPA, 23:03:54 KST — `replicas load 3` 가 걸려 있지 않았다) | 100만 | 실패 **0.25%**(5xx 196 — home 43 · banners 41 · search 40 · search_deep 40 · product_cold 27 · name_search 5, 경로를 가리지 않는다) — 임계값 통과 | 전체 29 / 665ms · p50 9 · 경로마다 p95 15 ~ 36 · p99 529 ~ 802 · max 3.4 ~ 3.8초 | 캐시가 찬 평소 모양 — catalog 서버 p95 search 6 ~ 11ms · 상세 22 ~ 43 · 커넥션 대기 0 · 쥔 시간 5 ~ 7ms. 5xx 와 p99 꼬리는 catalog 쪽 지표에 없다(catalog 의 경로별 p95 는 내내 11ms 이하) — 새 catalog 파드가 뜬 23:03:54 ~ 23:04:24(CPU 42% · home95 7 → 11 · prod95 27 → 43)와 같은 때로 보아, 게이트웨이가 막 뜬 파드로 보낸 호출이 늦어 벌크헤드 · 시간 초과로 끊긴 것으로 본다(spike 회차 ②④ 와 같은 모양 — 게이트웨이 로그의 폴백 예외로 확인) | — | (mixed 와 비교할 기준 — 레플리카를 고정하고 다시) |
| 2026-10-19 | `94fb8595` | **mixed** — `PRODUCTS=1000000 RATE=50 REVIEW_RATE=1`(앞 줄의 기준과 같은 조건 + 리뷰 초당 1) · catalog **2**(HPA 최소 — 고정하지 않았다) | 100만 | 실패 **79.90%**(5xx 63,677 — 모든 경로에 고르게: home 12,091 · search 12,403 · search_deep 12,367 · product_cold 12,430 · banners 11,896 · name_search 2,490) — 무너짐 | 전체 p50 3 · p95 **2,332** · p99 3,938ms(기준 p95 29) · review_add p50 **1,611** · p95 3,232 | 리뷰 초당 1건이 catalog 전체를 세웠다. 시작 10초 만에(23:17:25 KST) 커넥션 대기 7 → 20 → **37 ~ 47**(풀 5 × 파드 2 = 10 이 끝까지 다 참) · 커넥션을 받기까지 평균 **1.0 ~ 1.9초** · 쥔 시간 300 ~ 450ms(기준 5 ~ 7ms) · Tomcat 바쁜 스레드 50 ~ 57, CPU 는 7 ~ 21% — 일을 하는 게 아니라 커넥션을 기다린다. 서버 p95 는 search 4 ~ 7초 · home 2.3 ~ 7초 · **banners 2.1 ~ 4.2초**(DB 를 쓰지 않던 경로까지). 게이트웨이 폴백 33,113건이 모두 `BulkheadFullException`(시간 초과 1). 원인: 리뷰마다 user-service 가 부르는 `ProductService.updateRating` 이 `@CacheEvict(allEntries = true)` 로 `products` · `search_cache` · `products_by_category` · 필터 메뉴 넷(colors · sizes · details · brands — 별점과 상관없다)을 매초 통째로 비운다 → 홈 카드 · 목록 · 검색 · 메뉴가 늘 캐시 밖 → 모든 요청이 DB 로 가 풀 10 을 놓고 줄을 선다 → 캐시를 다시 채우기 전에 다음 리뷰가 또 비운다. 리뷰 쓰기 자체도 catalog 호출(커밋 뒤 동기)을 기다려 1.6초 | 별점은 **그 상품의 캐시만**(`ProductService.updateRating`): id 상세(`product`)는 새 값으로 바꿔 넣고 slug 상세 · 이름 조회(`products` 의 `slug:` · `name:`)만 지운다. 목록 · 검색 · 카테고리 쪽 별점은 만료(30 · 60분)까지 옛 값 — "별점 N 이상" 필터도 그만큼 늦다. 필터 메뉴 넷은 별점과 상관없어 건드리지 않는다. `RatingCacheTest`(실제 Spring 캐시 프록시 — 일곱 캐시를 채운 뒤 별점: 목록 · 검색 · 메뉴 · 다른 상품은 그대로, 그 상품의 상세는 맞는다; 예전 어노테이션이면 `search_cache` 가 비어 실패) | 같은 조건(3 고정)에서 실패 79.90 · 78.91 → **0.02%** · p95 3,340 → **28ms** · review_add p50 2,216 → **52ms**(아래 `160904Z`) |
| 2026-10-19 | `94fb8595`(별점 수정 전 이미지) | mixed 의 기준 다시 — `PRODUCTS=1000000 RATE=50` · catalog · 게이트웨이 **3 고정**(`replicas load 3`) | 100만 | 실패 **0.00%**(5xx 0) — 임계값 통과 | 전체 27 / 33ms · p50 9 · home 14 / 20 · search 12 / 18 · search_deep 13 / 60 · name_search 16 / 22 · product_cold 31 / 38 | 캐시가 찬 평소 모양 — catalog 서버 p95 search 7ms · 상세 27ms · 커넥션 대기 0 · 쥔 시간 5 ~ 6ms · CPU 10 ~ 28%. 앞 기준(`135834Z`)의 5xx 196 은 측정 중 2 → 3 으로 뜬 파드 몫이었다 — 고정하면 0 | — | (mixed 의 기준) |
| 2026-10-19 | `94fb8595`(별점 수정 전 이미지) | mixed 다시 — `REVIEW_RATE=1`, 앞 줄과 같은 조건(3 고정) | 100만 | 실패 **78.91%**(5xx 62,800 — 경로마다 고르게) — 무너짐 | 전체 p95 **3,340** · p99 5,225ms · review_add p50 **2,216** · p95 4,157 | 파드를 3개로 늘려도 같다(2개일 때 79.90%) — 커넥션 15개(5 × 3)가 다 차고 대기 51 ~ 71 · 받기까지 1.6 ~ 2.7초 · 쥔 시간 400 ~ 600ms(2개일 때 300 ~ 450 — DB 를 나눠 쓰는 쪽이 늘었다) · CPU 6 ~ 12%. 파드 수가 아니라 리뷰마다 캐시를 통째로 비우는 것이 원인임을 확인 — 수정(`19803606`)은 이 이미지에 없다 | — | → 아래 두 줄(수정 이미지) |
| 2026-10-19 | 별점 수정 이미지(`19803606` — 부하 기계의 커밋 열은 `94fb8595`) | mixed 의 기준 — 배포 뒤 `PRODUCTS=1000000 RATE=50` · 3 고정 | 100만 | 실패 **0.00%**(5xx 0) — 임계값 통과 | 전체 27 / 32ms · p50 9 · home 13 / 21 · search 12 / 18 · search_deep 12 / 19 · name_search 15 / 20 · product_cold 31 / 39 | 수정 전 이미지의 기준(`145058Z`, 27 / 33ms)과 같다 — 쓰기가 없으면 이 수정은 아무것도 바꾸지 않는다. catalog 서버 p95 search 7ms · 상세 27ms · 커넥션 대기 0 · CPU 9 ~ 30%. 그 앞의 `153133Z`(exit=107) · mixed `155154Z`(exit=141)는 끝까지 가지 않은 시도라 뺀다 | — | (mixed 의 기준) |
| 2026-10-19 | 별점 수정 이미지(`19803606`) | **mixed** — `REVIEW_RATE=1`, 앞 줄과 같은 조건(3 고정 · flush-cache 뒤) | 100만 | 실패 **0.02%**(5xx 13 — home 5 · product_cold 3 · banners 2 · search · search_deep · name_search 1씩) · 못 보낸 반복 34 · 최대 VU 89 — **임계값 통과** | 전체 28 / 60ms · p50 9 · search 13 / 37 · search_deep 12 / 48 · home 14 / 37 · product_cold 32 / 75 · **review_add 52(p50) / 66 / 551** · max 7.4초 | 리뷰 초당 1건이 더 이상 catalog 를 세우지 않는다 — 수정 전 같은 조건 78.91%(p95 3,340 · 커넥션 대기 51 ~ 71)에서, 커넥션 대기 0 ~ 2 · 쥔 시간 5 ~ 6ms · 경로별 서버 p95 7 ~ 28ms 로 기준 회차와 같은 모양. 리뷰 쓰기는 52ms(catalog 호출이 상품 하나의 캐시만 고친다). **남은 것: 20초짜리 한 번** — 01:15:14 ~ 01:15:34 KST(16:15 UTC) Tomcat 바쁜 스레드 4 → 39 · 커넥션 쓰는 중 14 · 대기 **22** · 받기까지 12 → 95ms · 쥔 시간 6 → 46ms · 서버 p95 home 1.0 · search 1.5초, 그 뒤 바로 원래대로. 5xx 13 · max 7.4초 · 못 보낸 반복 34 가 이 구간 몫으로 본다. 리뷰와 상관없이 보인다(리뷰는 처음부터 초당 1). 시각이 기준 회차 시작(15:55:12 UTC)의 정확히 20분 뒤라 커넥션 수명(`max-lifetime` 20분)의 한꺼번에 은퇴를 의심했으나 **아니었다** — `hikaricp_connections_creation_seconds_count`(30초 증가, 16:13 ~ 16:18)는 0 ~ 4 로 내내 고르고 16:15 에 몰리지 않는다. postgres 의 정시 체크포인트(데모 `checkpoint_timeout=15min` — 16:00:24 · **16:15:24** · 16:30:24, 이 회차 안에서는 이것 하나)가 같은 때 시작했지만 몰림이 먼저다 — 16:15:14 의 표본에 이미 바쁜 스레드 39 · 대기 22, 체크포인트는 10초 뒤 시작해 252초에 걸쳐 버퍼 1.3% 만 썼다. 기획전 스케줄러(`EventScheduler` — 기획전 경계에서 `products` · `search_cache` 등 넷을 통째로 비운다)도 아니다 — catalog 로그(그 뒤 61,923줄, INFO)에 그 시각의 기획전 시작 · 종료 · 캐시 비우기가 없다. **원인: 같은 때 채워진 검색 캐시가 함께 만료** — 측정 뒤 Redis 의 `search_cache` 키 **48개 모두 만료가 17:15**(16:15 에 함께 다시 채워져 그 뒤 한 번도 미스가 없었다). 한 번 생긴 무리는 매시간 같은 초에 만료된다 — 처음 무리는 캐시를 통째로 비운 뒤(수정 전 mixed `150532Z` 가 끝난 15:15 의 60분 뒤). 그리고 `sync` 가 없었고, **있어도 아무것도 하지 않았다** — Spring Data Redis 3.x 의 `RedisCache.get(key, loader)` 는 잠그는 일을 작성기에 맡기는데 catalog 의 작성기는 잠그지 않는 쪽이다(실제 Redis 에서 같은 키 동시 미스 16개 → 계산 16번, `CacheStampedeRedisTest`) — 그래서 `SearchFilterFacets` 의 `sync = true` 도 이름뿐이었다 | ① 수명 흩어짐 — TTL 이 있는 모든 캐시(products 30분 · search_cache 60분 · 그 밖 10분)가 키마다 최대 20% 짧게(`RedisCacheConfig.jittered`): 한 바퀴 만에 무리가 풀린다 ② 키마다 한 번 계산 — 매니저가 내주는 캐시를 `SingleFlightCache` 로 감싸 `sync = true` 읽기를 키마다 잠근다(파드 안, 다른 키는 기다리지 않는다, 기다리는 스레드는 DB 커넥션을 쥐지 않는다) ③ `search_cache` · `products_by_category` 에 `sync = true`. 같은 키 동시 미스 16개 → 계산 1번, Redis 의 실제 만료가 200개에 6분 넘게 흩어진다(`CacheStampedeRedisTest` · `SingleFlightCacheTest` · `RedisCacheResilienceTest` — 넷 모두 되돌리면 실패) | flush-cache 직후 회차 실패 0.00% · p95 28 → 16ms · 커넥션 대기 0(아래 `082546Z`) — 60분 뒤 회차는 측정 전 |
| 2026-10-20 | 만료 흩어짐 · 키마다 한 번 계산 · V22 이미지(`b2ebf3d0`) | **mixed** — `PRODUCTS=1000000 RATE=50 REVIEW_RATE=1`, 앞 줄과 같은 조건 · purge 뒤 100만 다시 적재 · flush-cache 직후 | 100만 | 실패 **0.00%**(5xx 0 · 4xx 0) · 못 보낸 반복 0 · 최대 VU 55 — 임계값 통과 | 전체 **16 / 40ms** · p50 9 · search 13 / 19 · search_deep 13 / 41 · name_search 16 / 26 · home 14 / 22 · product_cold 19 / 26 · review_add 46(p50) / 65 / 95 · max 1.7초 | 앞 회차(`160904Z` — 28 / 60ms · 5xx 13 · 20초 몰림 한 번)보다 고르다. 몰림은 시작 30초뿐 — 캐시를 비우고 막 다시 적재한 DB 라 첫 계산이 차갑다(서버 p95 search 1.0초 · 쥔 시간 62ms, 17:26:16 KST 부터 원래대로), 그 뒤 10분 동안 커넥션 대기 0 · 쥔 시간 3ms(앞 회차 5 ~ 6) · 경로별 서버 p95 6 ~ 14ms. 그 앞의 `081821Z`(exit=107, 요청 19/초)는 끝까지 가지 않은 시도라 뺀다 | — | (60분 뒤 같은 조건 한 번 더 — 시작 무리가 60분 뒤 함께 만료되지 않는지) |
| 2026-10-20 | `b2ebf3d0` 이미지 | browse **기준** — `PRODUCTS=10000000 RATE=50`(넓히지 않음) · flush-cache 뒤 · 레플리카 3 | **1,000만** | 실패 **5.55%**(5xx 4,307 — 경로를 가리지 않는다: search 857 · search_deep 842 · banners 838 · home 823 · product_cold 772 · name_search 175, 4xx 90 — product_cold) · 못 보낸 반복 35 — 임계값 넘음 | 전체 p50 9 · p95 16 · p99 641 · max 10.0초 · home p99 6.3초 | 실패는 **시작 2분뿐**(18:47:27 ~ 18:49:07 KST, catalog 요청 초당 7 ~ 30): 커넥션 15개(5 × 3) 다 참 · 대기 24 → 80 · **쥔 시간 평균 34.5초** · 받기까지 3.8 ~ 5초 · 서버 p95 search 30초(쿼리 시간 제한) · banners 5.6초 — 캐시 밖 첫 계산이 수십 초씩 걸려 모든 경로가 줄을 섰다. 18:49:17 부터 대기 0 · 서버 p95 7 ~ 20ms(100만과 같은 모양). 의심(**확인 전**): `ops/44 load` 는 끝에 ANALYZE 만 하고 VACUUM 을 하지 않는다 — visibility map 이 비어 V20 · V21 의 개수 세기(인덱스만 읽어야 할 것)가 줄마다 표(8GB)를 다시 읽는다(가장 큰 카테고리 ~130만). 100만에서는 표가 메모리에 들어가 보이지 않았다. 그 앞의 `093132Z`(PRODUCTS=11000000, exit=107)는 넣은 범위(1,000만)와 달라 시작 전 확인이 멈췄다 | — | → 아래 줄(VACUUM 은 이미 돌았다) |
| 2026-10-20 | `b2ebf3d0` 이미지 | browse 기준 다시 — 앞 줄과 같은 조건(앞 회차 20분 뒤) | 1,000만 | 실패 **0.00%** — 임계값 통과 | 전체 15 / 22ms · p50 9 · home 13 / 18 · search 12 / 16 · search_deep 12 / 16 · name_search 15 / 20 · product_cold 21 / 25 | 시작 구간의 몰림이 없다 — 첫 줄부터 서버 p95 search 6 · home 11ms · 커넥션 대기 0 · 쥔 시간 3 ~ 4ms, 끝까지 100만과 같은 모양. 자동 VACUUM 은 앞 회차 전에 이미 돌았다(09:18 ~ 09:28 UTC, 인덱스만 읽을 수 있는 쪽 product 82% · sku 98% · size 91%) — VACUUM 가설은 아니다. 남은 둘(**확인 전**): (가) 앞 회차는 적재 직후 표 21GB 가 메모리에 없어 첫 계산마다 디스크를 읽었다(이번엔 데워져 있었다) · (나) 이번 회차는 검색 캐시(60분)가 남아 있었다 → **(가) 쪽** — 회차 전에 flush-cache 를 했고(268개) 1초 넘는 쿼리 기록(`log_min_duration_statement = 1s`)이 비었다: 캐시가 비어도 DB 페이지가 메모리에 있으면 1,000만의 첫 계산이 1초 안이다. 운영에서 같은 상황은 DB 재시작 · 노드 재부팅 직후 | — | (확정: DB 재시작 + 노드 파일 캐시 비우기 + flush-cache 뒤 다시 — 그때의 느린 쿼리로 무엇을 데울지) |
| 2026-10-22 | `915acbd5` 이미지(측정기 `c718f97f`) | browse `RATE=30`(기본 7단계 · 9분 30초) — 밤 회차 뒤 점검 · catalog · 게이트웨이 3(HPA 최소 3 · 최대 3) · 유입 제한 올림 | 홈 상품만(데이터 규모 모드 아님) | 실패 **0.03%**(5xx 13 — home 8 · product 3 · search 2, 4xx 0) · 못 보낸 반복 9 · 최대 VU 39 — 임계값 통과(요청 ~80/초) | 전체 18 / 1,518ms · home 20 / 32 · product 14 / 21 · search 23 / 1,523 · 5xx 13건은 모두 최대 10초 | 평소 모양 — p99 를 올린 것은 게이트웨이 시간 제한(`GATEWAY_TIMEOUT` 10초)에서 끊긴 몇 건이다(시점은 이 결과로는 모른다). 4분 앞의 `000813Z`(같은 조건)는 첫 화면 응답이 늦어(p95 971ms · 최대 4.6초, 61요청 중 18%) 시작 전 확인이 멈췄다(exit 107) — 10-20 의 1,000만 첫 회차와 같이 데워지기 전의 첫 요청. 결과 폴더는 호스트의 `load-results/` | — | — |
| 2026-10-22 | `915acbd5` 이미지(측정기 `5e3fc126`) | spike(`spike-20261022T005314Z` — 평소 20 → 200 반복/초 · 8분 40초) · catalog · 게이트웨이 **3 고정 · 데워진 채**(HPA 최소 3 · 최대 3, 앞 browse 를 치른 파드) | 홈 상품만 | 실패 **9.08%**(5xx 21,059 — search 8,516 · banners 4,834 · product 3,896 · home 3,813: 반복마다 search 가 두 번이라 경로마다 8 ~ 10% 로 고르다 · 4xx 0 · 429 0) · 못 보낸 반복 35 · 최대 VU 235 — 임계값(2%) 넘음 | 전체 225 / 485ms · 최대 2.9초 · product 194 / 308 | **데운 앞단 3개의 천장** — 10-19 의 spike 셋(HPA 2 ~ 3: 21.35% · 미리 6: 35.71% · 최대만 6: 20.81%)보다 낮다: 정점 전에 catalog · 게이트웨이 3개씩이 이미 데워져 있었다(10-19 의 '미리 6' 은 앞단만 늘려 병목이 catalog 로 옮겼다 — 게이트웨이 6 × 벌크헤드 25 = catalog 로 동시 150, catalog 는 정점 시작 때 2). 최대 2.9초 · 시간 초과(10초) 0 — 넘친 몫은 벌크헤드가 바로 503 으로 끊어 쌓이지 않았다. HPA 가 3 에 묶여 늘 수 없었으므로 이 회차는 '데운 3개의 용량' 이다 | — | 다음: 몇 단계에서 막히는지 — ops/57 의 capacity-steps(조회 100 → 500 반복/초, 단계마다 10분, §3) |
| 2026-10-22 | `915acbd5` 이미지(측정기 `915acbd5`) | mixed 8시간(`mixed-20261022T101928Z` — 도착률 40 · 리뷰 초당 1 · 넓힘, `STAGES=5m:40,8h:40,2m:0`) — 넷째 밤 | 1,000만 | 실패 **0.106%**(5xx 6,439 — search_deep 5,045 · name_search 1,271 · 그 밖 123, 4xx 0) · 못 보낸 반복 8 · 최대 VU 113 — 임계값 모두 통과(요청 초당 208) | 전체 25 / 173ms · home 18 / 32 · name_search 29 / 1,295 · search_deep 33 / 422 · 리뷰 쓰기 82 / 136 | 셋째 밤(0.27%)과 **같은 이미지** — 줄어든 것은 고친 효과가 아니다(`FacetRefreshClaim` 은 이 이미지에 없다). 남은 5xx 는 깊은 쪽 · 이름 검색의 격벽 503 | — | 다음 밤은 메뉴 맡기가 든 이미지로 |
| 2026-10-22 | `915acbd5` 이미지(측정기 `266ff2ec`) | order-sustained(`order-20261022T183704Z`, ops/57 — 초당 5건 55분) | 홈 상품만 | 실패 **0%**(구매 18,750 · 5xx 0 · 4xx 0) · 못 보낸 반복 0 · 최대 VU 15 | 전체 54 / 80ms · `order_create` 86 / 113 · `cart_add` 40 / 46 | 쓰기 경로를 처음으로 한 시간 — 재고 예약 · 아웃박스 · Kafka · 입금 안내 · 취소가 막히지 않았다 | — | — |
| 2026-10-22 | `915acbd5` 이미지(측정기 `266ff2ec`) | capacity-steps(`browse-20261022T195859Z`, ops/57 — 100 → 500 반복/초, 단계마다 10분) · catalog · 게이트웨이 3(HPA 최소 3 · 최대 3) | 홈 상품만 | 100 반복/초(요청 ~500/초)까지 503 0. **200 에서 catalog CPU 1.00 · 게이트웨이 503 초당 200 ~ 280**, 300 에서 600 ~ 770. 전체 실패 **35.92%**(5xx 116.7만, 4xx 0) · 못 보낸 반복 44.7만 · 최대 VU 2,000(상한) | 전체 p95 6,041 / 12,500ms · 최대 36초 | catalog CPU(200 단계) → 게이트웨이 CPU(300 단계) → **게이트웨이 liveness 실패로 재시작 연쇄**(아래) | 게이트웨이 프로브 상한(B-103) | 다음: B-103 · 메뉴 맡기가 든 이미지로 다시 — 재시작 없이 503 으로만 버티는지 |
| 2026-10-22 | `915acbd5` 이미지(측정기 `266ff2ec`) | capacity-steps 다시(`browse-20261022T222549Z`, 같은 단계) · catalog · 게이트웨이 3(HPA 최소 3 · 최대 3) | 홈 상품만 | 실패 **34.69%**(5xx 92.4만 — search 37.1만 · banners 18.6만 · product 18.4만 · home 18.3만, 4xx 0) · 못 보낸 반복 56.4만 · 최대 VU 2,000(상한) | 전체 p95 8,613 / 16,281ms · 최대 43초 | **첫 회차와 같은 무너짐** — 게이트웨이 셋 모두 liveness · readiness 실패(k8s 이벤트, 아래 ⑤), 한 파드 11번 재시작 · BackOff, 끝날 때 게이트웨이 2. 게이트웨이 폴백 예외: 격벽 5,502 · 회로 열림 4,523 · 시간 초과 64. 필터 메뉴 DISTINCT 가 10분마다 파드들에서 함께(브랜드 최대 72초 × 2 · 17초 × 3) | B-103 · `FacetRefreshClaim`(이 이미지에 없다) | 다음: 두 수정이 든 이미지로 다시 |
| 2026-10-23 | `c3ca5b65` 이미지(측정기 `266ff2ec`) — B-103 · 메뉴 맡기 | capacity-steps(`browse-20261023T001615Z`, 같은 단계) · catalog · 게이트웨이 **2 → 3**(배포가 HPA 를 2 ~ 3 으로 되돌렸다 — 앞 두 회차는 3 고정) | 홈 상품만 | 실패 **30.34%**(5xx 129.1만 — search 52.0만 · banners 26.8만 · home 25.2만 · product 25.1만, 4xx 0) · 못 보낸 반복 24.5만 · 최대 VU 2,000 · 처리량 초당 1,091(앞 두 회차 832 · 682) | 전체 p95 3,982 / 4,782ms · 최대 20.6초 | **게이트웨이 재시작 0** — liveness 실패 7(연속 6 에 닿지 않음) · readiness 실패 22(잠깐 빠졌다 돌아옴). 메뉴 DISTINCT 는 10분마다 카테고리마다 한 번 — 같은 카테고리가 겹친 적 없음(아래). 남은 실패는 용량(파드당 1 코어) | — | 다음: 3 고정 · 시각 붙인 파드 기록으로 같은 조건 비교 |

**capacity-steps(2026-10-22) 읽기 — 용량보다 먼저 무너진 것은 프로브였다.** Grafana(15초 간격, KST)와 k6 진행 줄로:
① 100 반복/초(~500 요청/초)는 503 0. 200 단계(20:16Z)에 들자마자 catalog CPU 가 1.00(파드당 1 코어 한도)에 붙고 catalog 로 가는 게이트웨이 격벽(25)이 0 이 되어 게이트웨이 503 이 초당 200 ~ 280(요청의 20 ~ 28%) — catalog 자신의 503 은 0, 넘친 몫을 앞에서 끊었다. **깨끗한 용량은 catalog 3 · 게이트웨이 3 에서 100 ~ 200 반복/초 사이**다.
② 300 단계(20:27Z)에 게이트웨이 CPU 도 1.00 — 503 이 초당 600 ~ 770 으로 늘고 게이트웨이 처리량은 ~1,500 요청/초에서 멈췄다(catalog 는 ~730 ~ 880 을 받았다).
③ 20:32:30Z 에 k6 VU 가 260 → 2,000(상한), 20:33Z 에 p95 0.2 → 3.5초 · 504 가 처음 나왔고 20:34Z 부터 게이트웨이 `up` 이 0 을 되풀이했다. 게이트웨이 파드 하나(agent2)는 8번 재시작 · 21:03Z 부터 CrashLoopBackOff. OOM · hs_err 없음, 직전 로그의 끝은 Redis(유입 제한) `EVALSHA` 500ms 시간 초과와 `BulkheadFullException` — 프로세스는 살아 있었고 **kubelet 이 죽였다**: liveness 가 `timeoutSeconds` 기본 1초 · 3번(10초 간격)이라, 트래픽과 같은 Netty 이벤트 루프 · 1 코어를 나눠 쓰는 `/actuator/health/liveness` 가 포화 중에 1초를 넘자 30초 만에 재시작 → 남은 둘에 몫이 몰려 더 포화 → 다음 재시작. 같은 순간(20:32:11Z) catalog 파드 셋이 브랜드 DISTINCT(66초)를 함께 돌렸다 — 이 이미지에는 메뉴 맡기(`FacetRefreshClaim`)가 없다.
④ 고침(B-103): 게이트웨이 liveness 는 `periodSeconds` 15 · `timeoutSeconds` 5 · `failureThreshold` 6 — 90초 동안 한 번도 5초 안에 답하지 못해야 죽인다(이벤트 루프가 정말 멎은 경우). readiness 는 상한 3초 · 3번 — 늦은 파드는 죽이지 않고 엔드포인트에서 잠깐 뺀다. 포화는 격벽 503 으로 버티고 재시작으로 키우지 않는다. 가드 `frontTierProbesTolerateSaturation`(상한 ≥ 5초 · 무응답 ≥ 60초 · readiness 상한 > 1초 — 옛 매니페스트 · 실패 횟수 3 · readiness 1초로 되돌리면 실패).
⑤ 죽인 이유의 직접 증거 — 다시 돌린 회차(`222549Z`)의 k8s 이벤트: 게이트웨이 셋 모두 `Liveness probe failed: … context deadline exceeded (Client.Timeout exceeded while awaiting headers)`, 재시작 직후 `Startup probe failed: … connection refused`(JVM 기동 중), 그다음 `Back-off restarting failed container` — 프로세스는 살아 있었고 kubelet 이 프로브로 재시작했다. kubelet journal · containerd 로그에는 프로브 실패가 남지 않는다(첫 회차에서 못 찾은 까닭). 이벤트는 1시간 뒤 사라지므로 회차가 끝나면 바로 `kubectl -n ecommerce get events --sort-by=.lastTimestamp` 를 남긴다. readiness 도 셋이 함께 실패했다 — 셋이 동시에 빠지면 엔드포인트가 비므로, B-103 뒤 재측정에서는 readiness 가 셋 모두 빠지는 순간이 있는지 본다(있으면 readiness 도 포화와 떼어 놓는다). 관측 VM 디스크는 Jaeger 로 다시 찼다(19G — 오류 폭주의 추적은 이제 초당 상한, B-102).

**capacity-steps 세 번째(2026-10-23, B-103 · 메뉴 맡기 이미지) 읽기 — 무너짐은 사라지고 용량만 남았다.** 같은 단계에서 앞 두 회차와 견주면 처리량 832 · 682 → **1,091 요청/초**, p99 12.5 · 16.3 → **4.8초**, 최대 36 · 43 → 20.6초, 못 보낸 반복 44.7만 · 56.4만 → 24.5만, 게이트웨이 재시작 8 · 11 → **0** — 시작 레플리카가 2 · 2 로 더 불리했는데도. k8s 이벤트: liveness 실패 7번(파드마다 1 ~ 3번, 연속 6번에는 닿지 않아 아무도 죽지 않았다 — 옛 1초 · 3번이었으면 재시작됐을 응답이다), readiness 실패 22번(빠졌다가 몇 초 안에 돌아왔다 — 셋이 동시에 빠졌는지는 파드 기록에 시각이 없어 확정하지 못했다), HPA 2 → 3 의 새 catalog 파드는 포화 속에서 준비까지 56초(기동 프로브 안).
메뉴 맡기(`FacetRefreshClaim`): 느린 질의 로그를 매개변수와 함께 보면 회차 내내 10분마다 카테고리(1 ~ 5)마다 브랜드 · 색상 · 사이즈가 **한 번씩** 돌았고 같은 카테고리가 겹친 적이 없다(10-22 셋째 밤은 같은 매개변수가 셋). 같은 순간에 끝나는 브랜드 질의 셋(00:48:21 · 01:09:22 · 01:19:52, 각 30 ~ 34초)은 **서로 다른 카테고리**(2 · 3 · 4)를 파드들이 나눠 맡은 것이다. 남은 것은 질의 자체의 값 — 1,000만에서 브랜드 DISTINCT 가 카테고리마다 24 ~ 46초, 10분 주기마다 메뉴 질의가 모두 합쳐 약 5분이다(5초 넘은 것만 더해)(운영 상품 수에서는 1초 안). 남은 30% 는 용량이다 — 200 반복/초 단계에서 catalog CPU 가 먼저 한도(파드당 1 코어)에 닿는다(10-22 첫 회차 ①).
단계별(Grafana 15초, 단계 안의 평균 — 처리량은 게이트웨이가 받은 요청/초 · 503 은 게이트웨이가 낸 것 · 레플리카 3 · 3):

| 단계 | 받은 요청/초 | 503/초 | 성공/초(= catalog) | 게이트웨이 p95 | catalog p95 | 게이트웨이 CPU | catalog CPU | catalog 커넥션 대기(최대) |
|---|---|---|---|---|---|---|---|---|
| 100 | 483 | 0 (0%) | 483 | 0.02초 | 0.01초 | 0.41 | 0.54 | 0 |
| 200 | 1,000 | 40 (4.0%) | 961 | 0.13 | 0.11 | 0.88 | **0.99** | 37 |
| 300 | 1,450 | 543 (37%) | 907 | 1.21 | 0.13 | **0.99** | 0.97 | 40 |
| 400 | 1,406 | 627 (45%) | 779 | 3.46 | 0.11 | 0.99 | 0.92 | 25 |
| 500 | 1,420 | 586 (41%) | 834 | 3.64 | 0.10 | 0.99 | 0.95 | 30 |

- **catalog 의 천장은 성공 900 ~ 960 요청/초(파드당 ~300)** — 200 단계에서 CPU 0.99 · 커넥션 대기가 생기고 넘친 4% 를 게이트웨이 격벽이 503 으로 끊는다. 그 위로는 받는 만큼 끊고 성공은 늘지 않는다(400 단계에서 조금 줄었다 — 과부하의 웃돈).
- **게이트웨이의 천장은 받은 요청 ~1,450/초(파드당 ~480)** — 300 단계(09:52 KST)에서 CPU 1.00 에 붙자 게이트웨이 p95 만 0.2 → 3.5초로 뛰고 catalog p95 는 0.1초 그대로다: 늦음은 게이트웨이 안의 줄(이벤트 루프)이고, 503 은 대부분 격벽의 즉시 거절이다. 400 · 500 단계는 게이트웨이가 더 받지 못해 k6 가 VU 상한(2,000)에 막혔다(못 보낸 반복). 이때 프로브가 1초를 넘었고, B-103 전에는 여기서 재시작 연쇄가 시작됐다. 게이트웨이 `up` 은 끝까지 3.
- 시작 90초(HPA 2 → 3, 막 배포된 파드)에 503 이 초당 ~95 · 격벽 0 — 데워지기 전의 첫 요청(10-22 browse 의 시작 전 확인 실패와 같은 모양).
- 다음 후보: 파드당 처리량 — catalog 는 요청당 CPU(첫 화면 · 배너 · 검색 · 상품이 한 반복에 넷, 검색 둘), 게이트웨이는 요청마다 TLS 두 번(손님 · catalog mTLS) · JWT · 유입 제한 Redis 호출. 200 단계에서 두 서비스의 CPU 프로파일을 떠 어디에 쓰는지부터 본다(`ops/58-cpu-profile.sh`, §4).

**CPU 프로파일(2026-10-23 12:22 KST 회차 — 3 · 3 고정 · capacity-steps, `ops/58-cpu-profile.sh`) 읽기 — 어디에 쓰나.** 지표로는 앞 회차와 같다: catalog 는 200 단계에서 CPU 1.00 · 성공 ~1,000/초, 게이트웨이는 3개가 처음부터 데워져 있어 300 단계(1,500/초)를 p95 0.2초로 넘기고 400 단계로 오르는 13:03 에 포화됐다(어제 2 → 3 회차는 300 단계에서) — 게이트웨이 3개의 천장 ~1,500 ~ 1,600/초. 재시작 0(B-103). 표본은 200 단계(게이트웨이 907/초)와 300 단계(1,346/초)에서 120초씩, 아래는 300 단계(catalog 표본 10,918 · 게이트웨이 10,051 — 200 단계도 묶음 비율이 거의 같다).

| 서비스 | 덩어리 | 비중 | 무엇인가 → 고친 것 |
|---|---|---|---|
| catalog | 컨트롤러별 | 검색 31% · 첫 화면 7.6% · 상품 6.4% · 배너 4.7% | 나머지는 요청마다의 기본 값(Tomcat · TLS · 지표) |
| catalog | Redis 캐시 값 풀기 | **13.6%** | 그중 5.2% 는 같은 JSON 을 두 번 읽는 몫 — Spring Data Redis 3.5 의 범용 직렬화기가 `@class` 를 찾으려고 바이트 전체를 트리로 먼저 읽는다 → **한 번에 읽는 직렬화기**(`SinglePassJsonRedisSerializer`, 저장 모양 그대로 — 캐시를 비우지 않는다) |
| catalog | 응답 JSON 쓰기 | 17.6% | 첫 화면 · 배너는 메모리 캐시에 있어도 매번 직렬화 — 다음 후보 |
| catalog | Hikari 누수 감지 | **3.9%** | 커넥션을 빌릴 때마다 스택 추적 예외 하나(`ProxyLeakTask`) → **기본 끔**(B-104, 아홉 서비스 모두 — 켜기 `DB_LEAK_DETECTION_MS=20000`) |
| catalog | Redis 입출력 · 소켓 쓰기 | ~9% · 4.5% + 2.6% | Redis(Lettuce · Netty) · 게이트웨이로의 TLS 응답 · PostgreSQL |
| 게이트웨이 | 요청마다 INFO 로그 | **4.7%** (로그 전체 7.7%) | `PreLoggingFilter` 의 `Original Request Path` — 줄마다 가림 정규식 → **DEBUG**(경로는 추적에 남는다) |
| 게이트웨이 | 요청마다 예외 하나 | **2.6%** | 라우트 필터의 `mutate()` 가 TLS 정보를 복사하며 손님의 클라이언트 인증서를 묻고, 없으니 JDK 가 `SSLPeerUnverifiedException` 을 매번 만들어 Spring 이 버린다(spring-web 6.2.19) → **묻지 않는 TLS 정보**(`NoPeerCertificateSslInfoFilter` — client-auth 가 없거나 NONE 일 때만, https 만) |
| 게이트웨이 | 소켓 send · recv · writev | ~12% | 실제 입출력 |
| 게이트웨이 | 관측(지표 · 추적) | 7.7% | 샘플링 1.0 은 B-72 — 그대로 |

세 수정을 합치면 catalog ~9% · 게이트웨이 ~7% 의 CPU 가 빠진다(같은 단계를 다시 재 확인한다). 남은 큰 몫은 catalog 의 응답 직렬화 · 캐시 역직렬화(파드 메모리 캐시를 Redis 앞에 두면 둘 다 줄지만, 상품을 고친 뒤 다른 파드에 몇 초 늦게 보인다 — 정하지 않았다).

참고 — 이 표의 덩어리를 책에서 찾을 곳(장 번호는 판에 따라 다를 수 있다 — 목차의 장 · 절 제목으로 찾을 것):

| 책 | 장 · 절 | 이 표의 어디와 이어지나 |
|---|---|---|
| *Java Performance* 2판 — Scott Oaks (O'Reilly, 2020) | 10장 Java Servers — JSON Processing | 캐시 값 풀기 · 응답 JSON 쓰기 — 매퍼 재사용, 트리로 먼저 읽고 다시 객체로 읽는 비용(이중 파싱) |
| 같은 책 | 12장 Java SE API Tips — Exceptions · Logging | 누수 감지 · TLS 인증서 조회의 예외 — 예외 비용의 대부분은 스택 추적 채우기, 스택이 깊을수록 비싸다 · 요청마다의 로그 |
| 같은 책 | 11장 Database Performance Best Practices | JDBC · 커넥션 풀(누수 감지가 빌릴 때마다 무엇을 만드는지는 HikariCP README 의 `leakDetectionThreshold` 가 정확하다) |
| 같은 책 | 3장 A Java Performance Toolbox | 프로파일러 · 표본 추출(`ops/58`, async-profiler itimer) |
| *Systems Performance* 2판 — Brendan Gregg (Addison-Wesley, 2020, 오라일리 러닝 수록) | 5장 Applications · 6장 CPUs | CPU 프로파일 · 플레임 그래프 읽기 — flat · inclusive · 묶음 |
| *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly, 2017) | 4장 Encoding and Evolution | 캐시 저장 모양을 바꾸지 않은 이유 — 롤링 배포 중 옛 · 새 파드가 서로 읽는다 |

**고친 뒤 다시(2026-10-23 19:48 KST 회차 — `c3ca5b65` 뒤 `271af0ca` 이미지 · 3 · 3 고정 · 같은 단계, `browse-20261023T104829Z`).** k6: 처리량 초당 1,160 · 실패 31.52% · p95 3,591 / p99 4,411ms · 못 보낸 반복 19.2만. 재시작 0(시각 붙인 파드 기록 — 게이트웨이 ready 가 셋 모두 빠진 순간도 없다; liveness 실패 17 · readiness 실패 44 는 연속에 닿지 않았다). 프로파일(300 단계, 같은 120초)에서 고친 넷은 사라졌다 — 게이트웨이 요청 로그 4.74 → 0.03% · TLS 인증서 조회 2.70 → 0% · catalog 이중 파싱 5.73 → 0%(캐시 풀기 전체 15.0 → 10.4%) · 누수 감지 4.17 → 0%(예외 만들기 catalog 3.9 → 0.1%).

| 단계 | 게이트웨이 CPU 전 → 후 | catalog CPU 전 → 후 | 받은 요청/초 전 → 후 | 성공/초 전 → 후 | catalog 커넥션 대기(평균) 전 → 후 |
|---|---|---|---|---|---|
| 100 | 0.46 → **0.32** | 0.61 → **0.48** | 495 → 501 | 495 → 501 | 0 → 0 |
| 200 | 0.86 → **0.68** | 0.99 → 0.99 | 1,002 → 1,000 | 953 → 956 | 9 → **19** |
| 300 | 0.98 → **0.84** | 1.00 → 1.00 | 1,501 → 1,500 | 975 → 941 | 23 → **41** |
| 400 | 0.99 → 0.99 | 0.85 → 0.95 | 1,363 → **1,597** | 755 → **881** | — |

- 게이트웨이는 같은 부하에서 CPU 21 ~ 30% 덜 쓰고 400 단계에서 17% 더 받았다.
- catalog 의 성공 천장(~950/초)은 그대로다 — CPU 가 싸지자 요청이 DB 커넥션 앞에 줄을 섰다(대기 두 배, 프로파일한 파드의 CPU 91 → 76%). 커넥션을 쓰는 곳은 응답마다의 재고 조회(검색 · 첫 화면 · 상품, 그 아래 시간 9.7 · 5.6 · 4.4%). 다음: 이 이미지 + 아래 수정으로 `pool load 10`(10-18 에는 CPU 가 먼저 차 풀을 늘리면 나빠졌다 — 지금은 조건이 다르다).
- 새로 보인 덩어리: `ExecutionTimeLogger` — 서비스 패키지의 모든 메서드 호출마다 INFO 한 줄(catalog CPU 7.9%, 일곱 서비스에 똑같이) → **기본 끔**(B-105, 켜기 `EXECUTION_TIME_LOG_ENABLED=true`). 실행 시간은 추적(B-72) · HTTP 지표에 남는다.

**실행 시간 로그를 끈 뒤(2026-10-23 21:39 KST 회차 — `60f0e5da` 이미지 · 3 · 3 · 풀 5 · 같은 단계, `browse-20261023T123921Z`).** k6: 실패 31.52 → **22.98%**(5xx 142.6만 → 102.1만), 처리량 초당 1,160 → 1,137 · p95 3,591 → 3,791 / p99 4,411 → 4,428ms — 받은 요청은 비슷하고 **성공한 요청이 310만 → 342만(+10%)**. 프로파일(300 단계)에서 catalog 의 로그는 9.1 → 0.4%, 같은 120초에 catalog 표본 9,149 → 9,800(CPU 를 더 많이 일에 썼다). 재시작 · BackOff 0, liveness 실패 8 · readiness 실패 19(연속에 닿지 않음). 다음: 같은 이미지로 `pool load 10`. 단계별(Grafana, 단계 안의 평균 — 로그 켬 19:48 → 끔 21:39):

| 단계 | 503/초 | 성공/초 | catalog CPU | catalog p95 | catalog 커넥션 대기 | 게이트웨이 CPU |
|---|---|---|---|---|---|---|
| 200 | 44 → **11** (1.1%) | 956 → **990** | 0.99 → **0.88** | 133 → **40ms** | 19 → **2.7** | 0.68 → 0.83 |
| 300 | 559 → **316** | 941 → **1,184 (+26%)** | 1.00 → 0.99 | 224 → 140ms | 41 → 25 | 0.84 → 0.95 |
| 400 | 716 → 562 | 881 → 939 | 0.95 → 0.88 | — | 12 → 7 | 0.99 → **1.00** |
| 500 | 640 → 504 | 889 → 953 | 0.95 → 0.90 | — | 9 → 4 | 1.00 → 1.00 |

catalog 는 200 단계에서 처음으로 한도 아래(0.88)였고, 300 단계에서 성공이 26% 늘었다. 그 위(400 · 500)에서는 **게이트웨이가 먼저 찬다**(CPU 1.00 · p95 3.2 ~ 3.8초) — 받은 요청은 1,500 에서 멈추고 성공은 ~950 으로 내려온다. VU 가 2,000 까지 늘며 새 연결의 TLS 핸드셰이크가 게이트웨이 CPU 를 더 쓴다(프로파일 flat 에 `ServerHandshakeContext`). 커넥션 대기가 300 단계에도 25 라 풀 10 은 여전히 볼 값이 있다.

**풀 10(2026-10-23 22:57 KST 회차 — 같은 이미지 · 3 · 3 · `pool load 10`, `browse-20261023T135745Z`) — 대기는 없앴지만 더 받지 못했다.** k6: 실패 22.98 → **24.90%**(5xx 102.1만 → 110.8만), 처리량 1,137 → 1,141 · p95 3,791 → 3,765ms. PostgreSQL 연결은 최대 ~63 / `max_connections` 300(측정 중 catalog `active` 최대 12).

| 단계 | 성공/초 풀 5 → 10 | 503/초 | catalog CPU | catalog 커넥션 대기 | catalog p95 |
|---|---|---|---|---|---|
| 100 | 491 → 485 | 0 → 3 | 0.45 → **0.54** | 0 → 0 | 10 → 15ms |
| 200 | 990 → 978 | 11 → 20 | 0.88 → **0.94** | 2.7 → 1.0 | 40 → 65ms |
| 300 | **1,184 → 1,046** | 316 → 451 | 0.99 → 1.00 | **25 → 7** | 140 → 128ms |
| 400 | 939 → 968 | 562 → 523 | 0.88 → 0.84 | 7 → 0.4 | 91 → 65ms |
| 500 | 953 → 969 | 504 → 519 | 0.90 → 0.83 | 4 → 0.1 | 81 → 60ms |

- 커넥션 대기는 거의 사라졌지만, 기다리던 요청이 동시에 DB 일을 하게 되자 catalog CPU 가 낮은 단계부터 더 높았고(100 단계 0.45 → 0.54), 300 단계의 성공은 오히려 12% 줄었다. 400 단계 위는 게이트웨이 천장이라 거의 같다. 10-18 과 같은 결론 — **풀은 5 그대로**(`pool default`). 대기는 1 코어 catalog 가 일을 줄 세우는 자리로 둔다.
- 함께 본 것: 300 ~ 400 단계에서 클러스터 안 mongodb(채팅)의 readiness 명령이 네 번 시간 초과(노드 CPU 를 나눠 쓴다 — 측정 대상 밖). events 기록의 `61m` 줄은 1시간 TTL 로 지워지는 이벤트를 `kubectl get -w` 가 다시 찍은 것이다(회차 중 일어난 일이 아니다).
- 다음: 성공 천장은 이제 게이트웨이(~1,500/초 · 400 단계에서 CPU 1.00)와 300 단계의 catalog CPU 다. 후보 — 게이트웨이 파드 수 · CPU 한도, 손님 쪽 연결 재사용(k6 VU 가 늘 때의 TLS 핸드셰이크), catalog 의 응답 JSON 쓰기(21%) · Tomcat · TLS.

**게이트웨이 4(2026-10-24 00:22 KST 회차 — 같은 이미지 · 게이트웨이 4 · catalog 3 · 풀 5, `browse-20261023T152235Z`) — 천장이 catalog 로 옮겨 갔다.** k6: 실패 22.98 → **31.97%**(5xx 102.1만 → 156.7만), 처리량 1,137 → 1,279/초 · p95 3,791 → 2,981ms. 실패율은 나빠졌지만 게이트웨이가 더 받아 보낸 요청이 늘어서다 — **성공한 요청은 342만 → 339만으로 같다**. 재시작 0, 넷째 게이트웨이 파드는 회차 3분 41초 전에 떠 차가웠다(agent1). 단계별(풀 5 · 게이트웨이 3 → 4):

| 단계 | 성공/초 | 게이트웨이가 받은 요청/초 | 503/초 | catalog CPU | catalog 커넥션 대기 | catalog p95 | 게이트웨이 CPU |
|---|---|---|---|---|---|---|---|
| 100 | 491 → 498 | 500 | 0 → 1.5 | 0.45 → 0.44 | 0 → 0.5 | 10 → 11ms | 0.29 |
| 200 | 990 → 977 | 998 | 11 → 21 | 0.88 → 0.89 | 2.7 → 4.1 | 40 → 52ms | 0.83 → 0.67 |
| 300 | **1,184 → 1,095** | 1,500 | 316 → 405 | 0.99 → 0.99 | 25 → **38** | 140 → **211ms** | 0.95 → 0.92 |
| 400 | 939 → **1,048** | 1,500 → **2,000** | 562 → 952 | 0.88 → **0.98** | 7 → **54** | 91 → 277ms | 1.00 → 0.97 |
| 500 | 953 → 952 | 1,750 | 504 → 779 | 0.90 → 0.96 | 4 → 24 | 81 → 175ms | 1.00 → 1.00(p95 **3.6초**) |

- 게이트웨이 천장은 ~1,500 → **~2,000/초**로 올랐다(파드당 ~500/초 그대로). 그런데 성공은 catalog 셋이 낼 수 있는 ~1,050 ~ 1,100/초에서 멈춘다 — catalog CPU 가 300 단계부터 끝까지 0.96 ~ 0.99.
- 300 단계 성공이 8% 줄었다. 게이트웨이의 catalog 벌크헤드(파드당 25)가 300 단계부터 바닥(남은 자리 최소 0.1)이고, 동시 호출의 상한이 75 → 100 으로 늘어 catalog 안의 줄만 길어졌다(커넥션 대기 25 → 38, p95 140 → 211ms). spike ③ 과 같은 모양 — 포화된 뒤에 더 보내면 처리량은 늘지 않고 기다림만 는다(리틀의 법칙: 1,095/초에 동시 100 이면 요청 하나가 ~90ms 머문다, 75 일 때 ~63ms).
- 500 단계의 게이트웨이 p95 3.6초는 게이트웨이 자신의 포화다(받은 요청 1,750 · CPU 1.00). 차가웠던 넷째 파드는 그 단계에서 GC 부담 12% · 긴 GC 가 늘었고 readiness 가 한 번 시간 초과(연속 아님). 429 는 500 단계에만 초당 19(유입 제한 `limits load`).
- 프로파일(catalog, 1,314/초 — 앞 회차의 수정이 모두 들어간 것을 확인: 한 번에 읽는 직렬화기 · 누수 감지 0 · 실행 시간 로그 0): JSON **22%**(응답 쓰기 17.6% · 캐시 값 풀기 11.7%), Tomcat 17%, Spring 14%, TLS 13%, JPA 19.5%(포함). 게이트웨이(1,314/초): Netty · Reactor 56%, 벌크헤드 · 회로 · 재시도 필터 ~20%(포함), 하위 연결 12.5%, TLS 7%. 게이트웨이 쪽 요청마다의 예외 · 로그는 남아 있지 않다.
- 다음: 천장은 catalog CPU 다. 같은 조건에서 catalog 를 4로(`./ops/53-load-k6.sh replicas load 4 catalog-service`) — agent3 이 이미 85%(catalog · 게이트웨이 · cart 등)라 넷째 catalog 가 그 노드에 서지 않는지 본다. 코드 쪽 후보는 catalog 의 응답 JSON 쓰기 · 캐시 값 풀기(~29%).

**응답 JSON 쓰기 · 캐시 값 풀기 줄이기(2026-10-24 — 위 프로파일, catalog 이미지).** 두 덩어리를 더 나눴다.
- 응답 쓰기(17.6%)의 41% 는 소켓 · TLS 로 내보내는 몫, 34% 는 문자열 쓰기 — 쓰는 속도보다 **보내는 양**이 비용이다. 목록(검색 · 첫 화면 · 기획전)은 상품 전체(설명 · 상세 · 문답 · 분류 · 환불 규정 · 배송비 · 사이즈 코드 …)를 실었지만 카드(`ProductCard.jsx` · `util/stock.js` · `util/pricing.js`)와 k6 가 읽는 것은 id · name · slug · status · skus(id · images · discount · color · sizes 의 size · price · quantity · onHold)뿐이다 → **목록 응답은 그 허용 목록만**(`ProductCardResponses` — 응답 타입으로 매퍼를 고른다, 상품 상세 · 캐시 값은 그대로). 로컬(검색 결과 20개, 데모 모양): 54KB → **33KB(−40%)**, 쓰기 123 → **70µs(−44%)**. 게이트웨이도 그만큼 덜 옮긴다.
- 캐시 값 풀기(11.7%)의 **절반이 `PageJacksonModule`** 이었다 — 검색 결과의 `Page` 를 트리로 읽은 뒤 상품마다 다시 객체로 읽었다(앞서 없앤 이중 읽기의 안쪽). → 파서에서 바로 읽는다(저장 모양 같음 — 캐시를 비우지 않는다). 로컬: 한 건 498 → **347µs(−30%)**.
- 따져 보고 하지 않은 것: Jackson Blackbird(접근자 생성) — 쓰기 −12 ~ 16%, 읽기는 거의 그대로라 의존성을 더할 만큼은 아니다. 파드 안 검색 캐시(Redis 앞) — 풀기를 통째로 없애지만 다른 파드의 변경이 그만큼 늦게 보인다(따로 정한다).
- 다음: 같은 조건(게이트웨이 4 · catalog 4)에서 이 이미지로 다시 잰다 — catalog CPU 의 JSON 몫과 300 · 400 단계의 성공/초.

**catalog 4(2026-10-24 01:48 KST 회차 — 이전 이미지 `60f0e5da` · 게이트웨이 4 · catalog 4 · 풀 5, `browse-20261023T164842Z`) — 넷째는 13% 만 더 냈다.** k6: 실패 31.97 → **27.30%**, 성공한 요청 339만 → **353만(+4%)**. 넷째 catalog 는 server-node(그때 35%)에 섰다. 재시작 0.

| 단계 | 성공/초 3·3 → 4·3 → 4·4 | 503/초(4·4) | catalog CPU(파드 최대) | catalog 커넥션 대기 | catalog p95 |
|---|---|---|---|---|---|
| 300 | 1,184 → 1,095 → **1,237** | 263 | 0.99 → 0.99 → 0.98 | 25 → 38 → 36 | 140 → 211 → 197ms |
| 400 | 939 → 1,048 → **1,130** | 870 | 0.88 → 0.98 → 0.96 | 7 → 54 → 40 | 91 → 277 → 241ms |
| 500 | 953 → 952 → 904 | 641 | 0.90 → 0.96 → 0.89 | 4 → 24 → 16 | 81 → 175 → 172ms |

- catalog 를 3 → 4(+33%)로 늘렸는데 300 단계 성공은 +13%, 400 단계는 +8% 뿐이다. 파드당 처리는 ~363 → ~308/초로 줄었고 가장 바쁜 파드는 여전히 한도(0.98)다. 실습 클러스터는 VM 다섯이 호스트 한 대의 코어를 나눠 쓰고 k6 도 같은 호스트에 있어, 파드를 늘려도 호스트 CPU 를 나눌 뿐이다 — **파드 수로 더 올리는 측정은 여기서 멈춘다.** 남은 길은 요청 하나의 CPU 다(위의 목록 응답 · Page 읽기 수정).
- 500 단계는 게이트웨이 포화(CPU 1.00 · p95 4.4초). 회차 직전에 늘린 넷째 게이트웨이 파드(agent1)만 두 회차 모두 그 단계에서 GC 부담 12 ~ 16% 였고 readiness 12번 · liveness 2번 실패(연속에 닿지 않아 재시작 0 — B-103). 다른 셋은 1.3 ~ 1.4%. 다음 회차에서 그 파드의 메모리 · 노드를 함께 본다.
- 프로파일(catalog, 1,354/초): 묶음 비율은 3개일 때와 같다(JSON 20% · Tomcat 18% · TLS 13%) — 같은 120초에 표본 10,748 → 9,430(파드당 짐이 줄었다).

**목록 응답 · Page 읽기 수정 뒤(2026-10-24 03:49 KST 회차 — `45a3ccd7` 이미지(game-day, 같은 수정) · 4 · 4 · 풀 5, `browse-20261023T184906Z`) — 같은 CPU 로 11% 더.** k6: 실패 27.30 → **19.39%**(5xx 131.3만 → 94.4만), 받은 요청은 같고(초당 1,247 → 1,248) **성공한 요청 353만 → 392만(+11%)**. 재시작 · liveness 실패 0, readiness 실패 13(게이트웨이 셋에 나뉨). 파드는 노드마다 catalog · 게이트웨이 하나씩. 프로파일은 이 회차에 남지 않았다(ops/58 에 `PROM` 을 주지 않았다). 단계별(Grafana 가 관측 VM 디스크 100% 로 멈춰 Prometheus 에서 직접 — 단계의 머무는 10분 평균, 이전 이미지 → 새 이미지):

| 단계 | 성공/초 | 503/초 | catalog p95 | catalog 커넥션 대기 | 벌크헤드 남은 자리 | catalog CPU(최대) | 게이트웨이 p95 |
|---|---|---|---|---|---|---|---|
| 300 | 1,237 → **1,394 (+13%)** | 263 → 106 | 197 → **117ms** | 36 → 17.5 | 1.1 → 3.2 | 0.98 → 0.99 | 0.14초 |
| 400 | 1,130 → **1,266 (+12%)** | 870 → 644 | 241 → **155ms** | 40 → 25 | 0.4 → 0.6 | 0.96 → 0.95 | 0.97초 |
| 500 | 904 → **1,157 (+28%)** | 641 → 563 | 172 → **91ms** | 16 → 6 | 0.5 → 0.2 | 0.89 → 0.91 | 3.8초 |

- catalog CPU 는 같은데(300 단계 0.99) 처리가 늘고 응답이 빨라졌다 — 요청 하나의 CPU 가 줄었다. 줄을 서는 시간이 줄어 커넥션 대기도 반으로.
- 천장: 300 · 400 단계는 여전히 catalog CPU, 500 단계는 게이트웨이(CPU 0.995 · p95 3.8초). 총 p95 가 3,224 → 3,693ms 로 오른 것은 500 단계의 게이트웨이 대기다(예전에는 그 몫이 벌크헤드에서 바로 503 이었다).
- 다시 한 게이트웨이 파드만(이번에는 server-node 의 `cfmkw` — 지난 두 회차의 `7cvjg` 처럼 **가장 늦게 뜬 파드**) 500 단계 GC 부담 17%, 다른 셋 2 ~ 4%. 세 회차째 같은 모양이라 파드 문제가 아니라 그 파드로 연결이 몰리는 쪽을 의심한다(다음: 파드별 요청/초 · 힙).
- agent3 만 노드 CPU 76 ~ 82%(다른 노드 38 ~ 58%) — 그 노드에 클러스터의 입구 traefik(손님 TLS 전부)이 있다.
- 관측 VM: Grafana 가 멈춘 원인은 디스크 100% — Jaeger 볼륨 22G(보관 24시간이지만 부하 회차가 몰린 날). 볼륨을 비우고 25%.

**같은 이미지 다시(2026-10-24 12:19 KST 회차, `browse-20261024T031926Z`) — 재현됐다.** 실패 **18.84%** · 성공한 요청 **405만**(앞 회차 392만). 단계별 성공/초 300 · 400 · 500 = 1,417 · 1,337 · 1,225(앞 회차 1,394 · 1,266 · 1,157), catalog p95 112 · 163 · 96ms. 재시작 0. 500 단계에서 GC 부담이 튄 게이트웨이 파드는 이번에는 agent3 의 `scf7v`(17%) — 앞의 셋과 달리 가장 늦게 뜬 파드가 아니다. 요청은 네 파드에 고르게 갔다(앞 회차 파드별 374 · 478 · 430/초) — 쏠림 · 나이 둘 다 아니고, 포화 단계에서 파드 하나씩 번갈아 겪는다(열린 문제, 재시작으로 이어지지 않는다 — B-103).

마지막 프로파일(catalog, 935/초 — 새 이미지): JSON 17.6% · Tomcat 17.5% · Spring 16.2% · TLS 13.1% · Netty 11.6%. 응답 쓰기의 몫은 17.2 → **11.9%**, 캐시 값 풀기의 `readTree` 는 2.3 → **0%**. 남은 가장 큰 덩어리는 **재고 표시**(`StockAvailability`, 포함 **20%**) — 목록 · 상세 응답마다 두 질의(사이즈별 지금 수량 · 살아 있는 예약 합)를 각자의 트랜잭션으로 PostgreSQL(TLS)에 보낸다(Hibernate 15% · 드라이버 10% · DB 쪽 TLS 8%, 포함). → 두 질의를 하나로 합쳤다(2026-10-24, `ProductSizeRepository#liveAndHeld` — `UNION ALL`, 값은 같다 · SQL 하나, `StockAvailabilityQueryTest`). 파드 안 1초 재고 캐시는 화면이 1초까지 늦는 결정이라 하지 않았다.

### 결론 — 실습 클러스터의 용량 측정(2026-10-18 ~ 10-24)

browse `capacity-steps`(초당 100 → 500 도착률, 단계마다 10분, 상품 1만, 응답 상한 5초)에서 성공한 요청:

| 회차 | 바꾼 것 | 성공한 요청 | 실패 | 무엇을 배웠나 |
|---|---|---|---|---|
| 10-23 00:16 | 3 · 3 · 풀 5(기준) | ~310만 | 30 ~ 35% | 게이트웨이 liveness 가 부하 중 늦은 응답으로 파드를 죽여 재시작이 번졌다 → B-103 |
| 10-23 19:48 | 요청마다의 예외 · 로그 · 누수 감지 끔(B-104) | 310만 | 31.5% | 게이트웨이 CPU −21 ~ 30% — 그러나 천장은 catalog |
| 10-23 21:39 | 메서드마다의 실행 시간 로그 끔(B-105) | **342만 (+10%)** | 23.0% | catalog 의 로그가 CPU 9.1% 였다 |
| 10-23 22:57 | 풀 10 | 339만 | 24.9% | 대기는 사라졌지만 동시에 일하는 요청이 늘어 CPU 만 더 썼다 → 풀 5 |
| 10-24 00:22 | 게이트웨이 4 | 339만 | 32.0% | 천장이 catalog 로 — 동시 호출 상한만 늘어 줄이 길어졌다 |
| 10-24 01:48 | catalog 4 | 353만 (+4%) | 27.3% | 파드당 처리가 줄었다 — 호스트 한 대의 코어를 나눌 뿐(파드 수 측정은 여기서 멈춤) |
| 10-24 03:49 · 12:19 | 목록 응답은 카드 필드만 · Page 를 한 번에 읽기 | **392만 · 405만 (+11 ~ 15%)** | 19.4 · 18.8% | 같은 CPU 로 더 — catalog p95 197 → 112 ~ 117ms(300 단계) |

- **천장**: 300 · 400 단계(초당 1,500 ~ 2,000 도착)는 catalog CPU(파드당 1 코어), 500 단계는 게이트웨이(CPU 1.00 · p95 3.6 ~ 3.8초). 성공은 초당 ~1,400 에서 멈춘다.
- **효과가 있었던 것은 모두 요청 하나의 CPU 를 줄인 것**이다 — 요청마다의 로그 · 예외 · 이중 파싱 · 실리지 않아도 될 바이트. 커넥션 풀 · 레플리카 · 동시 호출 상한은 포화된 자원 앞의 줄만 바꿨다(리틀의 법칙).
- **무너지는 방식은 고쳤다**: 넘치면 벌크헤드가 바로 503 · 재시작 0(B-103) · 관측이 먼저 죽지 않게(B-102, 관측 VM 표본 1%).
- **남은 것**: 재고 표시 질의(20%), 포화 단계에서 게이트웨이 파드 하나의 GC 부담, 사용자 서비스의 C2 컴파일 정지(§2.8). 운영(OCI 단일 노드)은 실습보다 코어가 적어 같은 순서로 막힌다 — 이 수정들은 다음 배포에 들어간다.

**spike 세 회차(2026-10-19) 읽기.** 평소의 10배를 20초 만에 받는 spike 는 이 실습 클러스터에서 HPA 만으로 흡수되지 않는다.
① HPA 의 반응(CPU 지표 → 결정 → 파드 기동)이 정점 뒤 ~65초, 최대까지 ~95초 — 3분 정점의 절반이 그 전에 지나간다.
② 새 JVM 은 준비되자마자 제 몫을 받는데 느려서, 들어오는 순간 전체 처리량을 떨어뜨린다(HPA 최대 6 회차의 30초).
③ 게이트웨이만 늘리면 catalog 로 가는 동시 호출(게이트웨이 수 × 벌크헤드 25)이 늘어 포화된 catalog 안에 줄만 선다(미리 6 회차 — 35.71%).
④ 게이트웨이의 벌크헤드는 하류 **서비스**마다 하나라, 막 뜬 느린 인스턴스로 간 호출이 자리를 차지하면 데워진 인스턴스도 논다(미리 6 회차의 00:47 — 새 catalog 파드 하나만 일했다).
⑤ 그 뒤의 천장은 catalog 3개(파드당 1 코어 · DB 풀 10 — yml 의 5 는 적용되지 않았다, 고친 이미지부터 5)다. 좋았던 것: 넘친 몫은 기다리지 않고 바로 503(벌크헤드) · 회로 열림은 새 파드에서 잠깐뿐 ·
부하가 내려가면 10초 안에 정상 — 쌓여서 무너지지 않는다. 다음 후보: 새 파드를 데운 뒤에 받기 · catalog 의 최대와 요청당 비용 · 정점이 예상되면 미리 늘리기(`replicas load`).

**2026-10-18 browse(`704c6c53`, 유입 제한 올림 — 첫 유효 회차) 읽기.** Grafana(15초 간격)로 단계마다: 25 반복/초(요청 ~125/초)와 50(~250/초)은 게이트웨이 · catalog p95 가 30ms 대로 평평했다. 100(~500/초)으로 오르자 catalog 의 HikariCP 대기가 0 → 26 ~ 30, Tomcat 바쁜 스레드 2 → 46, catalog p95 31 → 390ms 로 뛰었고, 같은 1분에 게이트웨이 5xx 가 초당 최대 30 이었다(catalog 는 5xx 0 — 게이트웨이가 낸 오류). 대기가 풀리자(08:57:45) 5xx 도 멎었다. 첫 1분의 p95 0.5 ~ 0.67s 는 데움(JIT · 캐시). → 이 데이터 · 레플리카(catalog 2)에서 browse 의 오류 < 1% 상한은 50 ~ 100 반복/초 사이, 먼저 차는 것은 catalog 의 DB 커넥션 풀(5). **고친 것 — 게이트웨이 벌크헤드를 숨은 기본값 대신 재서 정한 값으로(2026-10-18).** 처음에는 100(파드당 ~250/초 × 받아들일 응답 0.4초)으로 올렸다가 같은 조건(레플리카 2)에서 다시 재 보니 나빴다: 실패 1.06 → 1.71%, p95 267 → 814ms, p99 506 → 1,498ms. 100 단계에서 벌크헤드 남은 슬롯이 0 까지 내려갔고, catalog 의 바쁜 스레드 128 · DB 커넥션 대기 113 · catalog p95 1.3초 — CPU(1 코어)가 먼저 차는 하류에 상한을 올리면 처리량은 그대로고 하류 안에 줄만 서며, 지연이 늘수록 걸려 있는 호출이 늘어(리틀의 법칙) 더 큰 상한도 결국 넘친다. 그래서 상한은 "하류가 정상일 때의 응답 시간"으로 잡는다: 파드당 ~250/초 × 정상 응답 ~0.1초(p95 30ms 대의 몇 배) ≈ **25**(대기 0) — 결과적으로 예전 기본값과 같지만 이제는 재서 정한 값이고 설정(`resilience4j.bulkhead.configs.default`, `GATEWAY_BULKHEAD_MAX_CONCURRENT`)과 근거가 남는다. 용량이 모자라면 상한이 아니라 파드를 늘린다(레플리카 3: 0.14%). `GatewayBulkheadTest` 가 실제 컨텍스트의 회로 차단기로 동시 호출을 걸어 변수로 준 상한(40)이 그대로 걸리는지 · 넘으면 바로 막는지 · 지표(`resilience4j_bulkhead_max_allowed_concurrent_calls` · `resilience4j_bulkhead_available_concurrent_calls{name=…}`)를 보고, 기본값 25 · 대기 0 은 설정 파일에서 본다 — 설정을 지우거나 기본값을 100 으로 바꾸면 실패한다.

**레플리카 실험(2026-10-18, `replicas load` — catalog · 게이트웨이 2 → 3, 풀은 5 로 되돌림).** 같은 단계(25 → 50 → 100 반복/초) · 같은 데이터에서 실패 1.06% → 0.14%(임계값 1% 처음 통과), p95 267 → 194ms, p99 506 → 378ms. 풀을 키우면 나빠지고(2.17%) 파드를 늘리면 좋아졌다 — 병목이 커넥션이 아니라 파드당 CPU(1 코어)였다는 것과 맞는다. 바꾼 직후 smoke 한 번(10% 실패)과 browse 두 번(setup 에서 멈춤, exit 107)은 새 파드가 아직 데워지기 전이었다 — 다시 smoke 를 통과한 뒤 잰 회차가 위 수치다. 남은 것: 이 회차의 CPU(3 파드에서 1.0 아래로 내려왔는지)와 남은 5xx 205건의 시점, 게이트웨이 벌크헤드를 숨은 기본값(25) 대신 근거 있는 값으로.

**풀 실험(2026-10-18, `pool load 10`) — 가설이 틀렸다.** 풀을 10 으로 늘리자(실제로 10개를 다 썼다) 100 반복/초 단계의 503 이 1,475건 → 3,180건으로 늘었다. 같은 1분에 catalog 의 `process_cpu_usage` 가 0.98 ~ 0.999, 게이트웨이가 0.89 ~ 0.97 — 두 서비스 모두 CPU 상한(`limits.cpu` 1000m, JVM 이 보는 코어 1)에 붙어 있었다. 커넥션 대기는 풀이 작아서가 아니라 CPU 가 모자라 요청이 커넥션을 오래 쥐어서 생겼고, 풀을 키우면 같은 코어에서 동시에 도는 요청만 늘어 한 요청이 더 느려진다. → 이 실습 클러스터에서 browse 의 상한을 정하는 것은 **catalog 와 게이트웨이의 CPU(파드 2개 × 1 코어)** 다. 게이트웨이도 요청 ~250/초에 1 코어를 다 쓴다(들어오는 TLS · 나가는 TLS · 유입 제한의 Redis 호출). 다음: 레플리카(HPA 최대 3)를 미리 늘려 같은 조건으로 — HPA 는 요청(250m) 대비 70% 라 곧 늘지만 새 파드의 기동 · 데움(1분 이상)이 100 단계(2분)보다 늦다. 그 회차의 레플리카 수는 `count by (service) (up{job="spring-actuator", environment="demo"})` 로 시간축에서 본다.

**5xx 의 정체 — 게이트웨이의 벌크헤드(동시 호출 상한 25).** 상태 코드는 전부 503(1,475건, 그 밖의 4xx · 5xx 0), 게이트웨이 로그의 fallback 예외는
모두 `io.github.resilience4j.bulkhead.BulkheadFullException`(두 파드 333줄 — 로그가 503 수보다 적은 까닭은 따로 본다). Spring Cloud CircuitBreaker 3.3 은
`resilience4j-bulkhead` 가 클래스패스에 있으면 회로 차단기 이름마다 세마포어 벌크헤드를 씌우고, 설정하지 않으면 `maxConcurrentCalls` 25 · 대기 0 이다 —
우리 설정(`resilience4j.circuitbreaker` · `timelimiter`)에는 벌크헤드가 없어 그 기본값이 그대로였다. 리틀의 법칙으로 게이트웨이 파드 하나가 catalog 에 동시에 거는 호출 수 =
초당 요청(~250) × catalog 응답 시간: 31ms 일 때 ~8, DB 대기로 100 ~ 390ms 가 되자 25 를 넘어 넘친 만큼 바로 503(시간 초과 504 · 회로 열림이 아니다).
→ 벌크헤드는 catalog 가 느려질 때 기다리게 하지 않고 바로 끊는 **부하 차단**으로 일했다. 먼저 고칠 것은 catalog 가 느려진 원인(DB 커넥션 대기)이고,
벌크헤드는 숨은 기본값 대신 근거 있는 값을 설정에 적는다(파드당 목표 초당 요청 × 허용 응답 시간).

**2026-10-18 첫 회차(`2ec90f02`, 상품 1만 · 사용자 200) — 무효.** 유입 제한이 기본값(초당 20 · 버스트 40)인 채였다: Grafana 에서 catalog 가 받은
요청은 browse · spike 내내 초당 20 ~ 23 이었고 게이트웨이는 초당 433 ~ 759 를 받았다. browse 실패 138,046건 중 137,981건, spike 165,543건 중
164,696건이 429 — 서비스의 용량이 아니라 제한값을 쟀다. order 도 비로그인인 catalog 경로(product · cart_info)의 실패가 정확히 429 수(4,162)였다.
그래도 읽을 수 있었던 것(다시 잴 때 확인한다):

- 제한에 걸리지 않은 로그인 경로(order · cart — 사용자마다 버킷)는 order 도착률 초당 20 에서 `order_create` p95 106ms · `cart_add` p95 105ms ·
  구매 한 번(7단계) p95 2.0s, 5xx 0, HikariCP 대기 0.
- 429 를 내는 경로만으로도 게이트웨이 앞이 밀렸다: browse 의 최고 단계(초당 100)에서 VU 가 50 → 400(상한)으로 튀고 p99 2.1s · 최대 15s,
  spike(초당 200)는 VU 800 상한 · 못 보낸 반복 11,370 · p95 3.9s, 게이트웨이 5xx 최대 초당 4.6. 게이트웨이 CPU · Redis(유입 제한 저장소) ·
  loadgen 중 어디인지는 이 회차로는 알 수 없다 — 제한을 올린 재측정에서 게이트웨이의 처리 중 요청 수 · 5xx 와 같이 본다.
- `cart_add` 의 429 가 아닌 실패 464건(14%)은 원인 미확인 — 재측정에서 상태 코드별로 본다.

## 6. 한계 — 정직하게

- 실습 클러스터는 데스크톱 한 대 위의 VM 이다 — 노드 사이 지연은 데이터센터와 비슷하지만 디스크 · CPU 는 한 기계를 나눠 쓴다.
- 손님 한 명의 IP 가 다 같다(k6 한 대) — 운영의 IP 별 버킷 · 캐시 분포와 다르다.
- 결제는 무통장 입금(외부 호출 없음)만 잰다. 카드 · PayPal 은 제공자 샌드박스의 속도를 재게 된다.
- 상품 이미지는 자리 표시 한 장이다(데모에 이미지 저장소가 없다) — 이미지 전송량은 재지 않는다.
- 목록은 PostgreSQL 검색이다(`SEARCH_ENGINE=postgres`, k8s/10 — 데모도 바꾸지 않는다). Elasticsearch 검색은 재지 않는다.
- 데이터 규모 모드의 검색 조합(카테고리 × 쪽 `DEEP_PAGES` · 이름 검색어 16개)은 유한해서 몇 분 뒤에는 `search_cache`(60분)에서 나온다 — 상품 수의 영향이 끝까지 보이는 것은 무작위 id 의 상세(`product_cold`)뿐이고, 검색의 데이터 비용은 캐시를 비운 뒤 첫 몇 분에만 보인다(2026-10-19 100만 회차).

## 7. 가드

- `LoadK6ScriptTest` — 시나리오의 모든 API 경로를 화면(frontend/src)도 부르고 첫 조각이 게이트웨이 라우트다 · JSON 검사 · 데이터 규모 모드의 id 범위 = ops/44 · 검색어는 생성기 이름에 · 그 변수를 k6 에 넘긴다(숫자만) · 운영 주소 거절 ·
  비밀번호는 환경 변수로만 · k6 의 종료 코드(임계값 99)를 지킨다 · `limits` 는 운영 클러스터에서 거절 · contention 은 회차마다 받아진 주문 ≤ 재고를 임계값으로, 재고 부족 400(본문이 \uXXXX 여도)을 실패로 세지 않는다 · `stock` 은 실습 클러스터에서만, 처음 값을 남기고 되돌린다.
- `LoadQueueScriptTest` — 대기열은 지금 k6 가 끝난 뒤에야 시작 · 회차 순서와 RATE · 99 로 끝나도 다음 회차 · 되돌리기(`CLEANUP=0` 이면 그대로) · kubectl 없는 기계는 회차만 · 두 번째 대기열 거절.
- `CpuProfileScriptTest` — 운영 클러스터 · 빈 APP_PUBLIC_URL 은 넣기 전에 거절 · 정한 요청/초를 넘기 전에는 표본을 시작하지 않는다 · 묶음은 잎에서 가장 가까운 아는 묶음(JDK 는 부른 쪽, 자바 클래스 이름은 GC · JIT 로 세지 않는다) · 파드 안에 결과가 없으면 옛 파일 없이 실패 · 부하가 오지 않으면 표본 없이 멈춤.
- `RateLimiterPlacementTest` — 기본 제한은 환경 변수로만 바뀌고 로그인 제한은 그대로, 매니페스트에는 그 변수가 없다.
- `DemoOverlayTest` — 데모 인그레스가 모든 게이트웨이 접두사를 게이트웨이로(https · 검증).
