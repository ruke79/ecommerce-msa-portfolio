# catalog-service (상품 등록/검색 · Elasticsearch) 문제점 분석

[日本語](06-catalog-search-issues.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** ES 검색 · 실시간 색인 · `ddl-auto` 는 이후 고쳤습니다([07](07-catalog-search-improvements.ko.md) · [13](13-secrets-dbha-es-search.ko.md) · [16](16-saga-e2e-and-observability-verification.ko.md) · [20](20-schema-ownership.ko.md)).

> 작성일: 2026-07-24
> 대상: `catalog-service`
> 관점: 검색 정확성/성능 · DB↔ES 정합성 · 캐시 · 보안/DoS · 프로덕션 견고성
> 관련: [개선 결과](07-catalog-search-improvements.ko.md)

이 문서는 **개선 이전** 상태를 파일·라인 근거로 기록한다.

---

## 0. 요약 — 가장 중요한 발견

**"Elasticsearch 검색 서비스"인데 정작 검색이 Elasticsearch를 쓰지 않는다.**

- 실제 검색 엔드포인트 `GET /api/search` → `SearchService.searchProducts()`는 **Querydsl/JPA로 PostgreSQL을 조회**한다. ES 문서/리포지토리는 존재하지만 **검색 경로에서 한 번도 호출되지 않는다**(`SearchService`에 `ProductDocumentRepository`/`ElasticsearchOperations` 참조 0건).
- 게다가 상품명/브랜드/상세는 `likeIgnoreCase("%" + term + "%")` — **선행 와일드카드 LIKE**라 인덱스를 못 타는 **풀 스캔**(O(N)), 관련도 랭킹·형태소 분석·오타 보정 전무. ES가 존재하는 이유를 전혀 활용하지 못한다.
- ES는 **수동 `POST /api/products/sync` 배치 재색인** 때만 채워지고, `addProduct`(상품 등록)는 **ES에 쓰지 않는다** → DB와 ES가 상시 어긋난다.

| # | 문제 | 심각도 |
|---|---|---|
| CAT-1 | 검색이 ES 미사용, Postgres `LIKE '%..%'` 풀스캔 | 🔴 High |
| CAT-2 | 검색/필터 캐시 **무효화 부재** → 상품 등록해도 최대 60분 검색 미반영 | 🔴 High |
| CAT-3 | 페이지 파라미터 검증 부재 → `page=0`에 **500**, `pageSize` 무제한 **deep pagination/DoS** | 🟠 Med |
| CAT-4 | `category` 이름 검색 시 `Long.parseLong` → **NumberFormatException 500** | 🟠 Med |
| CAT-5 | DB↔ES **정합성 부재**(등록은 DB만, ES는 수동 배치) | 🔴 High |
| CAT-6 | `/products/all` 등 **무제한 findAll** + `/products/sync` **인증 없이** 재색인 트리거 | 🟠 Med |
| CAT-7 | ES 연결 설정 공백(타임아웃/복제/TLS 불명), 단일 노드 가정 | 🟡 Low |
| CAT-8 | 상품 업데이트 Kafka 컨슈머 DLQ/멱등성 부재(공통 문제) | 🟠 Med |
| CAT-9 | **ES 매핑/애널라이저가 실제로 적용되지 않음**(`createIndex=false` + 색인 전 매핑 미생성) | 🔴 High |
| CAT-10 | **별칭(alias) 롤오버 버그**로 재색인 성공해도 노출 안 됨 | 🔴 High |
| CAT-11 | `search_cache` **캐시 키 불일치**(DTO에 equals/hashCode 없음) → 적중률 0% + 무한 적재 | 🟠 Med |
| CAT-12 | 재고 갱신 경로 **자기호출(self-invocation)** 로 `@CachePut`/`@Transactional` 무효화 | 🟠 Med |
| CAT-13 | `productUpdated`가 **주문 첫 항목만** 재고 반영(루프 내 return) | 🔴 High |
| CAT-14 | `~*`(POSIX regex)에 사용자 입력 → **정규식 인젝션/ReDoS**, 쿼리 타임아웃 부재, N+1 | 🟠 Med |
| CAT-15 | `ddl-auto: create-drop`가 기본 `local` 프로필에 활성 → **재기동 시 스키마 삭제(데이터 손실)** | 🔴 High |
| CAT-16 | Kafka `JsonDeserializer.addTrustedPackages("*")` → 역직렬화 가젯(RCE류) 위험 | 🟠 Med |

---

## CAT-1. 검색이 Elasticsearch를 사용하지 않음 🔴

`src/main/java/com/project/catalog_service/service/SearchService.java`
```java
// 상품명/브랜드/상세를 모두 선행 와일드카드 LIKE로 조회 → 인덱스 불가, 풀스캔
builder.and(product.name.likeIgnoreCase("%" + params.getSearch() + "%"));
builder.and(product.brand.likeIgnoreCase("%" + params.getBrand() + "%"));
detailsBuilder.or(productDetails.value.likeIgnoreCase("%" + params.getStyle() + "%"));
```
- `SearchController.searchProducts` → `searchService.searchProducts(params)` → **전부 JPA/Querydsl(Postgres)**.
- `ProductDocument`, `ProductDocumentRepository`(ES) 및 배치 색인 잡은 존재하나 **검색에 연결되지 않음** → ES는 사실상 **사장(死藏)**.
- 결과: 상품 수가 늘수록 검색 지연이 선형 증가, 관련도 순위/형태소/동의어/오타 보정 불가, ES 도입 목적 미달성.

## CAT-2. 검색/필터 캐시 무효화 부재 → 등록 상품이 최대 60분간 검색 미반영 🔴

`SearchService`의 `searchProducts`, `getColors`, `getSizes`, `getDetails`, `findAllByCategory`는 모두 `@Cacheable`(Redis)이며, `search_cache` TTL은 **60분**(`config/redis/RedisCacheConfig.java`).

그러나 `ProductService`의 쓰기 메서드(`addProduct`, `updateRating`, `updateSoldValue`, `updateProductSize_Qty`)에는 **`@CacheEvict`가 전혀 없다**(오직 `product`/`products` 캐시에 대한 `@CachePut`만 존재). 따라서:
- **신규 상품 등록 후에도 검색 결과·필터(색상/사이즈/브랜드/상세)가 최대 60분간 갱신되지 않는다.**
- 재고/판매량/평점이 바뀌어도 검색 필터가 stale.

→ 사용자가 체감하는 "상품 등록했는데 검색에 안 떠요" 버그의 직접 원인.

## CAT-3. 페이지 파라미터 검증 부재 🟠

`SearchService.searchProducts`
```java
Pageable pageRequest = createPageRequest(params.getPage() - 1, params.getPageSize());
```
- `SearchController`가 `SearchParamsRequest`를 `@Valid` 없이 바인딩. DTO에 `@Min/@Max`도 없음.
- `page=0`(또는 미지정 기본값 0) → `PageRequest.of(-1, ...)` → **IllegalArgumentException → 500**.
- `pageSize` 상한 없음 → 매우 큰 값으로 **대량 조회/deep pagination** 유발(메모리·DB 부하, DoS).

## CAT-4. category 이름 검색 시 500 🟠

```java
Long categoryId = (params.getCategory() != null) ? Long.parseLong(params.getCategory()) : null;
```
- `category`는 코드상 **id(숫자)와 이름(문자열) 둘 다**로 쓰인다(뒤에서 `findBrandsByCategoryName(params.getCategory())` 호출). 이름(예: `"shoes"`)이 들어오면 `Long.parseLong`이 **NumberFormatException → 500**.

## CAT-5. DB↔ES 정합성 부재 🔴

- `ProductService.addProduct`(상품 등록)는 **Postgres에만 저장**하고 ES(`ProductDocumentRepository`)에 쓰지 않는다.
- ES 색인은 오직 `SearchController.syncProducts()`가 트리거하는 **전량 재색인 배치**(`indexingJob`, 새 인덱스 `products-<timestamp>` 생성)로만 갱신.
- 즉 배치를 돌리기 전까지 ES는 DB와 상시 불일치. 배치 실패/부분 실패 시 정합성 복구 수단(재시도/DLQ/리컨실)도 코드로 드러나지 않음.
- (설령 검색을 ES로 전환하더라도) **이중 쓰기 원자성**(DB 커밋 후 ES 실패 시 처리)이나 **outbox 기반 색인**이 없으면 divergence가 재발한다.

## CAT-6. 무제한/무인증 엔드포인트 🟠

`SearchController`
- `/api/products/all`, `/api/categories/all`, `/api/product-skus/all` 등 **8개** 엔드포인트가 `repository.findAll()`을 **페이지네이션 없이** 반환 → 문서 전량 덤프(메모리/네트워크 DoS).
- `POST /api/products/sync` → **전량 재색인 배치**를 트리거. 게이트웨이 catalog 라우트에는 `JwtAuthorization` 필터가 없어(상품 조회는 공개) **인증 없이 호출 가능** → 반복 호출로 ES/DB에 부하(DoS). 재색인 같은 관리 작업은 **관리자 전용 경로**여야 한다.

## CAT-7. ES 연결/운영 설정 🟡

`config/es/ElasticsearchConfig.java`는 `@EnableElasticsearchRepositories`만 있고 비어 있다 → 연결/타임아웃/자격증명은 Spring Boot 자동설정(`spring.elasticsearch.*`)에 의존. 
- 소켓/커넥션 타임아웃, 재시도, TLS, 복제/샤드 수(단일 노드 가정)가 명시되지 않음 → 운영에서 ES 지연 시 요청 hang, 단일 노드 SPOF.

## CAT-8. 상품 업데이트 Kafka 컨슈머 신뢰성 🟠

`message/consumer` + `message/config`의 상품 업데이트 리스너(주문 성공 시 판매량/재고 갱신)는 [`docs/03`](03-architecture-and-reliability.ko.md)에서 지적한 **DLQ/재시도/멱등성 부재** 문제를 공유한다. 중복 소비 시 재고/판매량 이중 반영 위험.

## CAT-9. ES 매핑/애널라이저가 실제로 적용되지 않음 🔴

`document/ProductDocument.java`는 `name/description/brand`에 한/영/일 `@MultiField` 애널라이저, `subcategories/skus`에 `@Field(type=Nested)`, `@Setting(settingPath="/es/es-settings.json")`(nori/kuromoji)를 선언한다. 그러나:
- `@Document(indexName="products", createIndex=false)` → 앱이 매핑으로 인덱스를 생성하지 않는다.
- 배치는 `elasticsearchOperations.save(...)`로 **타임스탬프 인덱스에 문서를 그냥 저장** → ES가 **동적 매핑 + 기본 `standard` 애널라이저**로 인덱스를 자동 생성한다.
- 결과: 한국어/일본어 애널라이저는 **존재하지 않고**, `@Nested`는 일반 `object`로 강등되어 nested 쿼리가 실패한다. 매핑/애널라이저 설계가 전부 사문화. (게다가 `@Setting` 경로는 `common` jar에만 존재)

## CAT-10. 별칭(alias) 롤오버 버그 — 재색인해도 노출 안 됨 🔴

`batch/config/ElasticsearchBatchConfig.java`: writer는 `newIndexName + "-products"`(예: `products-<ts>-products`)에 쓰지만, alias 롤오버 태스크릿은 `products-<ts>`(접미사 없음)의 존재를 확인·전환하려 한다 → 대상 인덱스가 없어 **항상 예외("New index ... does not exist")** → `product-alias`가 **영원히 재지정되지 않는다**. 즉 재색인이 "성공"해도 새 인덱스가 검색에 노출되지 않는다(블루-그린 의도는 좋으나 구현이 깨짐). category/color 등 다른 alias는 아예 관리되지 않는다.

## CAT-11. search_cache 적중률 0% + 무한 적재 🟠

`SearchService.searchProducts`는 `@Cacheable(value="search_cache")`에 **key 미지정** → 기본 `SimpleKeyGenerator`가 인자 `SearchParamsRequest`로 키를 만든다. 그런데 `SearchParamsRequest`(common 모듈)는 **`equals`/`hashCode`가 없다**(`@Getter/@Setter/@ToString`만). 매 요청마다 새 객체 → 키가 절대 일치하지 않아 **캐시 조회는 100% miss, 저장은 매번 발생**(60분 TTL로만 상한). 사실상 순수 오버헤드.

또한 `products` 캐시는 **TTL 없음(permanent)** + **`@CacheEvict` 부재** → `getProductsByCategory/getProductsByName/getProducts('all')` 등 목록이 **영구 stale**.

## CAT-12. 자기호출로 캐시/트랜잭션 애노테이션 무효화 🟠

`ProductService.productUpdated`가 내부에서 `this.updateSoldValue`, `this.updateProductSize_Qty`를 호출한다. Spring AOP 프록시는 **자기호출(self-invocation)을 가로채지 못하므로** 그 메서드들의 `@CachePut`/`@CacheEvict`/`@Transactional`이 **실행되지 않는다**. (`private @Transactional createOrFindProduct`도 동일 — private은 프록시 대상 아님.) → 주문 기반 재고 변경 시 캐시 갱신이 실제로 일어나지 않았다.

## CAT-13. productUpdated가 주문 첫 항목만 반영 🔴

`productUpdated`의 `return productSize;`가 **`for (orderProducts)` 루프 안**에 있어, 여러 상품을 담은 주문이라도 **첫 번째 항목만** 판매량/재고가 갱신되고 나머지는 조용히 누락됐다. 재고 정합성 직접 훼손.

## CAT-14. 정규식 인젝션/ReDoS + 쿼리 타임아웃 부재 + N+1 🟠

- `repository/jpa/data/ProductSkuRepository.findProductIDBySizeAndPriceAndColor`가 `b.size ~* :size`, `c.color ~* :color`로 **사용자 입력을 Postgres 정규식 연산자(`~*`) 우변**에 넣는다 → 파라미터 바인딩이 SQL 인젝션은 막아도 **정규식 인젝션**은 못 막는다. 악의적 패턴으로 catastrophic backtracking(ReDoS/DoS) 유발 가능, 메타문자가 매칭을 오염.
- 검색 쿼리에 `statement_timeout`/JPA 쿼리 타임아웃이 없어, 풀스캔 LIKE + 정규식 필터가 겹치면 **DB 커넥션을 무한 점유**.
- 페이지 조회 후 `ProductMapper.toDto`가 `category/subcategories/questions/skus`(→sizes/color)를 지연 로딩 참조 → 페이지 전체에 **N+1**.

## CAT-15. ddl-auto: create-drop (데이터 손실) 🔴

`application-local.yml`의 `spring.jpa.hibernate.ddl-auto: create-drop` + 기본 활성 프로필 `local` → **재기동마다 스키마 drop/재생성**. 로컬 폐기용이 아니면 재시작 시 전량 데이터 손실이며, DB 자체가 휘발적이면 DB↔ES 정합성 논의도 무의미해진다. (부수적으로 Kafka 트러스트스토어가 `file:E:/github/...`  **Windows 절대경로** 하드코딩 → 컨테이너/CI에서 깨짐.)

## CAT-16. Kafka 역직렬화 신뢰 패키지 와일드카드 🟠

`message/config/KafkaConsumerConfig.java`의 `JsonDeserializer.addTrustedPackages("*")` → 타입 헤더로 지정된 **모든 타입을 신뢰**. 토픽에 공격자가 접근 가능하면 역직렬화 가젯(RCE류) 위험. 신뢰 패키지를 명시적으로 제한해야 한다.

---

## 개선 우선순위

- **즉시(안전) 수정 — 적용 완료**([`docs/07`](07-catalog-search-improvements.ko.md)):
  - CAT-2 캐시 무효화(상품 등록/평점/재고 변경 시 검색 캐시 evict) — CAT-12 자기호출 문제를 감안해 **외부 진입점 `productUpdated`에 evict** 배치.
  - CAT-13 `productUpdated`가 주문 **모든 항목**을 처리하도록 수정(루프 내 return 제거).
  - CAT-3 페이지 파라미터 클램프(0/음수→안전, `pageSize` 상한 100).
  - CAT-4 `category` 이름 입력 시 안전 파싱(500 제거).
- **문서화 + 승인 후 진행(대규모·검증 필요, 미적용)**:
  - CAT-1·CAT-5·CAT-9·CAT-10 — 검색을 **실제 ES로 전환** + 명시적 매핑/애널라이저로 인덱스 생성 + alias 롤오버 수정 + **outbox 기반 증분 색인**. 쿼리 재작성·응답 형태·무중단 색인 전환이 필요하고 당시 검증 환경에서 빌드/검증 불가 → **설계안만 제시**.
  - CAT-14(정규식 인젝션/타임아웃), CAT-15(`create-drop`), CAT-16(신뢰 패키지), CAT-6(관리 엔드포인트 분리), CAT-7(ES 타임아웃/복제), CAT-8(컨슈머 DLQ/멱등성), CAT-11(캐시 키/`products` evict) — 동작 변경·운영 결정·재검증이 필요해 로드맵으로 명시.

> ⚠️ 적용한 수정은 catalog-service를 당시 검증 환경에서 컴파일/실행할 수 없어 **정적 검토(구문·괄호 밸런스)만** 거쳤다. 병합 전 빌드 및 상품 등록→검색 반영, 다중 항목 주문 재고 반영 시나리오의 수동 QA를 권장한다.
