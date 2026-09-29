# 65. 사이즈 단위 SKU — 재고를 따로 세는 단위에 코드를 붙인다

[日本語](65-size-level-sku.md) | **한국어**

> 작성일: 2026-09-28 · 상태: ✅ 적용(배포 대기)
>
> 관련: [59 결제 전 재고 예약](59-stock-reservation.ko.md) · [53 품절과 재입고](53-restock-and-sold-out.ko.md)

## 0. 요약

| | 전 | 후 |
|---|---|---|
| SKU 코드가 붙은 곳 | `product_sku`(= **색상 변형**) 한 줄 | `product_size`(= **사이즈 한 줄**)마다 `sku_code` |
| 검정 270 과 검정 280 | 같은 코드 | `RUN-BLK-270` · `RUN-BLK-280` |
| 규칙이 있는 곳 | Java(`generateSku`, 스타일 코드만) | DB 함수 `product.assign_size_sku_codes()` **한 곳** |
| 주문 품목 · 포장 목록 | 이름 · 사이즈 이름만 | SKU 를 주문 시점 값으로 복사해 기록 |
| 관리자 등록 상품의 사이즈 | 🐛 부모 없이 저장(어느 스타일에도 속하지 않음) | 부모를 가리킨 채 저장 |

## 1. 왜 바꿨나

SKU(Stock Keeping Unit)는 **"창고에서 따로 세야 하는 단위"** 다. 이 스키마에서 수량(`quantity`)과 가격(`price`)을
가진 것은 `product_size` 한 줄이다. 그런데 코드는 한 단계 위 `product_sku`(색상 변형)에 붙어 있었다.

- 검정 270 과 검정 280 은 재고가 따로인데 코드가 같았다.
- 그래서 주문 품목에 "무엇이 나갔나" 를 **코드 하나로** 적을 수 없었다. 창고 관리(WMS) · 바코드 연동의 전제가 없었다.

이름도 정리했다.

| 이름 | 뜻 | 위치 |
|---|---|---|
| **스타일 코드** | 색상 변형의 코드(예: `FAS-100042`). 기존 `sku` 필드 · 열 그대로 | `product_sku.sku`, `ProductSkuDto.sku` |
| **SKU** | 사이즈 한 줄의 코드(예: `FAS-100042-XL`) | `product_size.sku_code`, `ProductSizeDto.skuCode` 등 |

기존 필드 이름(`sku`)은 API 호환을 위해 바꾸지 않았고, 주석과 관리자 화면 표기를 "스타일 코드" 로 고쳤다.

## 2. 식별자는 바꾸지 않았다

장바구니 줄 · 주문 품목 · 재고 예약 · 재고 차감(Avro `product-update`) · 재입고 알림은 모두
**(상품 id, 색상 id, 사이즈 이름)** 으로 움직인다. 이번 변경은 이 키를 **건드리지 않는다.**

- SKU 는 **식별자가 아니라 기록** 이다. 주문 시점의 값을 복사해 두는 것은 상품명 · 가격 스냅샷과 같은 이유다.
- 식별 키까지 SKU 로 바꾸려면 Avro 스키마 · 예약 표 · 재입고 구독 · 장바구니 줄 조회를 모두 옮기는 여러 릴리스짜리
  확장-축소(expand-contract) 전환이 필요하다. 이득(키 하나로 통일)에 비해 위험이 커서 이번에는 하지 않았다.

## 3. 규칙 — DB 함수 한 곳 (catalog V17)

`product.assign_size_sku_codes()` 는 **비어 있는** `sku_code` 만 채우고, 채운 행 수를 돌려준다.

| # | 경우 | 코드 |
|---|---|---|
| ① | 사이즈 이름이 빔(책 · 식품 — 사이즈 없는 상품) | 스타일 코드 그대로 — 스타일 하나가 곧 재고 단위 |
| ② | 보통 | `<스타일 코드>-<사이즈>`. 대문자, 영숫자 아닌 연속 문자는 `-` 하나(`"270 mm"` → `270-MM`) |
| ③ | 영숫자가 하나도 없는 이름(`"프리"`) | 스타일 안 순번 `-S<n>` — SKU 는 ASCII 로 |
| ④ | 같은 코드가 둘 이상(같은 스타일에 같은 사이즈 이름 두 줄 — 시드에 실제로 있다) | 첫 줄만 그 코드, 나머지 `<코드>-dup-<size_id>` |
| ⑤ | 부모가 없거나 스타일 코드가 NULL | 비워 둔다 |

- **한 번 정한 코드는 바꾸지 않는다.** 과거 주문 · 반품 · 회계가 그 코드를 들고 있다. 사이즈 이름이 나중에 바뀌어도 코드는 그대로다.
- ④에서 `-2` 가 아닌 이유: 사이즈 `"2"` 의 코드와 겹친다. 소문자 `dup` 은 ②가 만들 수 없는 모양이라 재충돌하지 않는다(V7 과 같은 방식).
- 최후 방어선은 부분 UNIQUE 인덱스 `uq_product_size_sku_code` 다.

**왜 DB 함수인가:** 같은 규칙을 Java 와 SQL(기존 행 백필)에 두 번 적으면 언젠가 어긋난다. 부르는 곳은 셋이다.

| 부르는 곳 | 언제 |
|---|---|
| V17 마이그레이션 | 배포 때 기존 행 전부 |
| `ProductService.withSizeSkuCodes` | 관리자 상품 등록 직후(flush → 함수 → 채운 값을 메모리 엔티티에 옮겨 적음) |
| `BatchConfig.sizeSkuCodeStep` | CSV 일괄 등록의 마지막 단계 — ④는 전체를 봐야 정할 수 있어서 행마다가 아니라 한 번에 |

## 4. 흐름 — 장바구니 → 주문 → 포장 목록

```
catalog  product_size.sku_code (V17)
   │  /api/products/info (캐시 없음)
   ▼
cart     cart_product.sku_code (V4)   담을 때 catalog 값으로(본문 무시), 다시 담으면 지금 값으로
   │  /checkout                         SKU 가 빈 옛 줄은 catalog 에서 채운다(스타일은 색상 id 로 고름)
   ▼
order    order_product.sku_code (V24)  주문 시점 값 복사 → 주문 내역 · 관리자 주문 응답
   │  /api/order/internal/orders/{id}/shipping
   ▼
delivery shipment_item.sku_code (V4)   배송 생성 시 복사 → 배송 조회 · 관리자 배송 화면
```

- **값의 출처는 늘 catalog 다.** 장바구니 담기 본문의 `skuCode` 는 무시한다. 믿으면 고객이 쓴 코드가 주문 품목과 포장 목록에 찍힌다.
- **옛 캐시:** cart-service 의 Redis 에 SKU 가 없는 장바구니가 남아 있을 수 있다. SKU 가 빈 줄이 하나라도 있으면
  그 캐시는 쓰지 않고 다시 만든다(그때 catalog 에서 채운다).
- **없어도 주문은 된다:** 코드가 없는 상품(⑤)이나 보충 실패 시 SKU 는 NULL 로 남고 주문 · 배송은 정상 진행한다. SKU 는 기록이지 조건이 아니다.

## 5. 🐛 함께 고친 것 — 관리자 등록 상품의 사이즈가 부모 없이 저장됐다

```java
request.getSizes().forEach(size -> ProductSizeMapper.toEntity(size).setSku(skuProject));  // ① 새 객체에 부모를 걸고 버린다
skuProject.setSizes(request.getSizes().stream().map(ProductSizeMapper::toEntity).toList()); // ② 다시 만든 객체를 저장
```

`sizes` 는 `mappedBy="sku"` 다. 외래 키를 가진 쪽은 사이즈이므로 ②의 객체에 부모가 없으면 `product_size.skuproduct_id` 가
NULL 로 저장된다. **관리자가 등록한 상품은 사이즈가 없는 상품이 됐다.** 상세 화면에 가격이 없고 장바구니에 담을 수 없었다.
상세 · 질문(`mappedBy="product"`)도 같은 모양이라 함께 고쳤다(`sizesOf`, `attachDetailsAndQuestions`).

> ⚠️ 이미 운영에서 관리자 화면으로 등록한 상품이 있다면 그 사이즈 행은 `skuproduct_id` 가 NULL 이다. 어느 스타일의
> 것인지 DB 에 직접 남은 정보가 없어서 마이그레이션으로 자동 복구하지 않는다. 대신 **생성 시각**이 단서가 된다 —
> 버그가 있던 흐름은 스타일 생성 → 이미지 업로드(수 초) → 사이즈 생성 순서라, 사이즈는 자기 스타일 직후에 만들어졌고
> 그 스타일은 사이즈가 하나도 없다. 이 추정으로 짝을 제안하고 사람이 확인한 것만 반영하는 스크립트를 두었다.
>
> ```bash
> kubectl -n postgres exec deploy/postgres -- \
>   pg_dump -U product -d productdb -t product.product_size -Fc > product_size-$(date +%Y%m%d-%H%M).dump   # 먼저 백업(그 표 하나)
> ./ops/42-repair-orphan-sizes.sh status                   # 고아 사이즈 수 — 0 이면 끝
> ./ops/42-repair-orphan-sizes.sh preview                  # 짝 추정표(HIGH · CHECK · NONE) — 고치지 않는다
> ./ops/42-repair-orphan-sizes.sh apply 901:77 902:77      # 확인한 짝만 — 한 트랜잭션, 하나라도 틀리면 전부 되돌림
> # 그다음 관리자 화면 › 상품 › 그 상품 › "저장" — 캐시 · 검색 색인을 새로
> ```
>
> 되돌리기: `UPDATE product.product_size SET skuproduct_id = NULL, sku_code = NULL WHERE size_id IN (...);`
> — 스크립트가 바꾸는 것은 이 두 열뿐이다.
>
> NONE(근거 없음)은 그 상품을 다시 등록한다. 남은 고아 행은 어느 스타일에도 속하지 않아 화면에 나오지 않는다.

## 6. 화면

| 화면 | 표시 |
|---|---|
| 상품 상세 | 이름 아래 `상품 번호 RUN-BLK-280` — **고른 사이즈**의 SKU |
| 관리자 재고 | 옵션 아래 SKU(이 행이 곧 재고 단위) |
| 관리자 환불 · 반품 | 품목 요약에 `[SKU]` |
| 관리자 배송 | 포장 품목에 `[SKU]` |
| 관리자 상품 등록 | 입력칸 이름을 "스타일 코드" 로, 사이즈별 SKU 는 자동으로 만든다고 안내 |

## 7. 검증

- catalog: **실제 PostgreSQL**(Testcontainers)에서 규칙 8개 — ①~⑤, 불변, 기존 코드와의 충돌, UNIQUE 인덱스(`SizeSkuCodeMigrationTest`).
  등록 경로 회귀 1개(부모 참조 · 함수 호출 순서).
- cart 6개(catalog 값 기록 · 본문 무시 · 다시 담기 · 옛 줄 보충 · 옛 캐시 재생성), order 4개(복사 · 없어도 주문 · 배송 품목 · 주문 내역의 사이즈),
  delivery 단언 추가.
- catalog · cart · order · delivery 를 컨테이너로 기동 — Flyway 적용 + Hibernate `validate` 통과.
- 프론트 loader · 상품 정보 · 재고 화면 테스트.

## 8. 남은 것

- **바코드(GTIN)** 는 없다. 실제 유통에서는 GS1 에서 발급받는 전 세계 공통 번호를 SKU 에 매핑한다. 붙이려면 `product_size.gtin` 을 추가하면 된다.
- **식별 키는 여전히 사이즈 이름**이다(§2). 사이즈 이름을 바꾸면 장바구니 · 예약의 키가 어긋나는 문제는 그대로다.
- 관리자 화면에서 SKU 를 직접 고치는 기능은 두지 않았다 — 불변이 원칙이다.
