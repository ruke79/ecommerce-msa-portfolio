# catalog-service 개선 내역 (What Changed)

[日本語](07-catalog-search-improvements.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** R-C1~3 · R-C6 은 이후 적용됐습니다. R-C4(필터 정규식 · `addTrustedPackages("*")`)는 남아 있습니다.

> 대상: `catalog-service` · 관련: [문제점 분석](06-catalog-search-issues.ko.md)

원칙은 이전과 동일하다: **회귀 위험이 낮고 정적으로 검증 가능한 수정만 적용**하고, 대규모·검증 필요 항목(검색의 ES 전환, 색인 파이프라인 재설계)은 설계안으로 남긴다. 당시 검증 환경에서는 catalog-service를 빌드/실행할 수 없어, 변경 Java는 **구문·괄호 밸런스 정적 검토**만 거쳤다(병합 전 빌드+수동 QA 권장).

---

## 1. 검색/필터 캐시 무효화 — "등록해도 검색에 안 뜸" 수정 🔴 (CAT-2, CAT-12)

`src/main/java/com/project/catalog_service/service/ProductService.java`

상품이 변경되는 외부 진입 메서드에 검색·필터 캐시 무효화를 추가:

| 메서드 | 트리거 | 추가 |
|---|---|---|
| `addProduct` | 상품 등록(컨트롤러) | `@CacheEvict(search_cache, colors_cache, sizes_cache, details_cache, products_by_category, allEntries=true)` |
| `updateRating` | 평점 변경(컨트롤러) | 동일 |
| `updateProductSize_Qty`, `updateSoldValue` | (직접 호출 대비) | 동일 |
| **`productUpdated`** | **주문 성공 Kafka 소비** | 동일 — **핵심** |

- **CAT-12(자기호출) 반영**: `productUpdated`가 내부에서 `updateSoldValue`/`updateProductSize_Qty`를 `this`로 호출하므로 그 메서드의 애노테이션은 프록시를 타지 않는다. 따라서 **실제 외부 진입점인 `productUpdated`에 evict를 배치**해 주문 기반 재고 변경 시 검색 캐시가 확실히 무효화되도록 했다.
- 효과: **신규 상품 등록/평점/재고 변경이 즉시 검색·필터에 반영**(기존 최대 60분 stale 제거).

## 2. 다중 항목 주문 재고 반영 버그 수정 🔴 (CAT-13)

`ProductService.productUpdated`
```diff
- for (item : orderProducts) { ... return productSize; }   // 첫 항목만 반영
- return null;
+ ProductSize lastUpdated = null;
+ for (item : orderProducts) { ... lastUpdated = updateProductSize_Qty(...); }  // 전 항목 반영
+ return lastUpdated;
```
- `orderProducts` null 방어도 추가. 효과: 여러 상품을 담은 주문의 **모든 항목**이 판매량/재고에 반영된다.

## 3. 검색 페이지 파라미터 안전화 🟠 (CAT-3)

`src/main/java/com/project/catalog_service/service/SearchService.java`
```java
int safePage = Math.max(1, params.getPage()) - 1;         // page=0/음수 → 500 방지
int safeSize = params.getPageSize() <= 0 ? 20 : Math.min(params.getPageSize(), 100); // 상한 100
```
- 효과: `page=0`에서의 `PageRequest.of(-1,..)` **500 제거**, `pageSize` 무제한으로 인한 **deep pagination/대량 조회(DoS)** 차단.

## 4. category 이름 검색 시 500 제거 🟠 (CAT-4)

`SearchService`에 `parseCategoryId()` 헬퍼 추가 — 숫자면 id로, 이름/공백이면 `null`(이름 검색 경로로). 기존 `Long.parseLong`의 **NumberFormatException(500)** 제거.

---

## 적용하지 않고 로드맵으로 남긴 항목 (설계안)

당시 검증 환경에서 빌드/검증이 불가하거나 동작 변경·운영 결정이 필요한 항목은 적용하지 않고 방법을 남긴다.

### R-C1. 검색을 실제 Elasticsearch로 전환 (CAT-1)
- `SearchService`가 Postgres LIKE 대신 **ES를 질의**하도록 전환: `multi_match`(name/brand/description) + 필터(카테고리 keyword, 가격/색상/사이즈 nested) + 관련도 정렬 + `search_after` 기반 페이지네이션(deep pagination 회피).
- 응답 형태를 `SearchResultDto`와 호환 유지(집계로 브랜드/색상/사이즈 파셋 생성 가능).

### R-C2. 인덱스 매핑/애널라이저 실제 적용 + alias 롤오버 수정 (CAT-9, CAT-10)
- 색인 전에 **명시적 settings/mappings로 인덱스를 생성**(nori/kuromoji 애널라이저, `@Nested` 유지). `@Setting` 리소스를 catalog 모듈에 포함.
- 배치의 인덱스 이름과 alias 태스크릿의 이름 규칙을 **일치**시키고(`*-products` 접미사 통일), 모든 도메인 alias(product/category/color/…)를 원자적으로 스왑.

### R-C3. DB↔ES 정합성: outbox 기반 증분 색인 (CAT-5)
- `addProduct`/`productUpdated` 등 쓰기 트랜잭션에서 **outbox 이벤트**를 남기고, 릴레이가 ES에 증분 반영. 전량 재색인은 백업/재구축 용도로만.
- 실패 재시도·DLQ·리컨실(주기적 DB↔ES 대사) 추가.

### R-C4. 검색 견고성/보안 (CAT-14, CAT-16)
- `~*` 정규식 필터 → `=`/`IN` 또는 이스케이프된 파라미터로 교체(정규식 인젝션/ReDoS 제거).
- 검색 쿼리 `statement_timeout`/JPA 타임아웃 설정, 매퍼 N+1 제거(fetch join/DTO 프로젝션).
- Kafka `JsonDeserializer` 신뢰 패키지를 **명시적 화이트리스트**로 제한.

### R-C5. 컨슈머 신뢰성 (CAT-8)
- 상품 업데이트 컨슈머에 DLQ + 재시도 백오프 + **멱등성**(처리 이벤트 dedup 또는 조건부 업데이트)으로 중복 소비 시 재고 이중 차감 방지. (backend [`docs/03` R-2/R-3](03-architecture-and-reliability.ko.md)와 동일 패턴)

### R-C6. 관리 엔드포인트/운영 (CAT-6, CAT-7, CAT-15)
- `/products/sync`(재색인)·`/*/all`(전량 덤프)을 **관리자 전용 경로**로 분리 + 게이트웨이 인증, `/*/all`에 페이지네이션.
- ES 타임아웃 프로퍼티 키 정정(`spring.elasticsearch.connection-timeout`/`socket-timeout`) + 합리적 값, 복제계수≥1.
- `ddl-auto`를 환경별로(`validate`/`none` 운영) 분리, Kafka 트러스트스토어 경로의 하드코딩(Windows 절대경로) 제거.

---

## 검증

- 수정한 `ProductService.java`, `SearchService.java`: **중괄호/괄호 밸런스 확인**, `@CacheEvict` 4→5개소·`parseCategoryId`/클램프 반영 확인.
- ⚠️ catalog-service는 당시 검증 환경에서 컴파일 불가 → **병합 전 빌드 및 수동 QA 필수**: (1) 상품 등록 직후 검색 반영, (2) 다중 항목 주문의 전 항목 재고 반영, (3) `page=0`/`pageSize=99999`/카테고리 이름 검색의 정상 응답.
