# 로컬 성능 튜닝 — 무엇을 측정할 수 있고 무엇은 못 하는가

[日本語](19-local-performance-tuning.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 수치만 낡음.** PostgreSQL 데이터소스는 지금 10개입니다(notification · delivery 추가). 풀 총합은 B-32 가 다시 셉니다.

> 작성일: 2026-07-28
> 관련: [테스트 전략](17-testing.ko.md) · [파티셔닝](09-postgresql-partitioning-sharding.ko.md) · [ops/14](../ops/14-load-test.sh)

## 0. 원칙 — 구조적 사실과 환경적 수치를 구분한다

"성능 테스트는 운영에서만 의미 있다"는 말은 절반만 맞다. 성능 문제에는 두 종류가 있고,
**한쪽은 노트북에서 정확히 잡힌다.**

| | 구조적 사실 | 환경적 수치 |
|---|---|---|
| 예 | SQL 쿼리 **개수**, 커넥션 풀 **총합**, 캐시 적중 **여부** | 지연시간 ms, 처리량 RPS, GC 일시정지 |
| 하드웨어 의존 | ❌ 없음 | ✅ 절대적 |
| 로컬 측정 | **정확함** | 참고용(상대 비교만) |
| CI 고정 | **가능** | 불가능(불안정) |

이 문서는 **구조적 사실만 빌드로 고정**하고, 환경적 수치는 측정 도구만 제공한다.

## 1. 로컬에서 잡히는 것 / 안 잡히는 것

**✅ 로컬로 충분한 것**
- **N+1 쿼리** — 상품 20건에 SQL 이 1번인지 81번인지는 어디서 재도 같다
- **커넥션 풀 산정** — 산술이다. `데이터소스 × 풀 × 레플리카 ≤ max_connections`
- **캐시 키·TTL·무효화** — 로직 문제
- **인덱스 사용 여부** — `EXPLAIN` 이 Seq Scan 인지 Index Scan 인지
- **Kafka 컨슈머 처리 구조** — `max.poll.records`, concurrency, 배치 커밋
- **오류의 변곡점** — 동시성 N 에서 5xx 가 시작되면 그건 구조적 한계다

**❌ 로컬 수치를 믿으면 안 되는 것**
- 절대 지연/처리량 (CPU·디스크·네트워크가 다름)
- 서비스 간 네트워크 지연 (로컬은 사실상 0 → 타임아웃 값 검증 불가)
- ~~실제 데이터 카디널리티~~ → **§5 에서 해결.** 시드 CSV 를 증식하면 분포를 보존한 채
  규모를 키울 수 있다. 다만 **실제 트래픽의 쿼리 분포**(어떤 카테고리를 얼마나 자주 보는가)는
  여전히 로컬로 알 수 없다
- 지속 부하에서의 GC 행태

## 2. 발견 ① — 상품 목록의 N+1 (실측)

`Product` 는 LAZY 컬렉션을 넷 갖는다: `subcategories`·`details`·`questions`·`skus`.
그리고 `ProductMapper.toDto` 가 **넷을 모두 순회**한다. 배치 페치가 없으면

```
상품 N건 조회 = 1(목록) + 4N(컬렉션) 쿼리     → 20건이면 81번
```

코드에는 이런 흔적이 남아 있었다:

```java
//@BatchSize(size = 10) // 10개의 Product에 대한 details를 한 번에 조회
```

**주석 처리된 채로.** 문제를 알아챈 사람이 있었지만 켜지지는 않았다.
`ProductSku` 만 이번 작업 초반에 `MultipleBagFetchException` 을 고치며 우연히 붙었을 뿐이다.

### 검증 방법 — 시간이 아니라 개수를 잰다

`ProductQueryCountTest` 가 Hibernate `Statistics.getPrepareStatementCount()` 로 실제 발행된
SQL 수를 센다. 세 가지를 본다:

| 검사 | 잡는 것 |
|---|---|
| 상품 조회 SQL 수 < 상품 수 | "상품당 최소 한 번"이라는 N+1 의 정의 위반 |
| 네 컬렉션에 `@BatchSize` 선언 존재 | 데이터가 적어 우연히 통과하는 경우 |
| 반복 호출 시 쿼리 수 비증가 | 캐시·페치 전략이 의도대로 동작하는지 |

두 번째 검사가 중요하다 — 첫 번째만 있으면 **테스트 DB 에 상품이 적을 때 조용히 통과**한다.

### 결과

```
수정 전:  FAIL  Product.subcategories 에 @BatchSize 없음
수정 후:  PASS  3/3
```

네 컬렉션 모두 `@BatchSize(size = 20)` — 목록 페이지 크기와 맞췄다. 이제 컬렉션당 1~2회의
`IN (...)` 쿼리로 묶인다.

## 3. 발견 ② — 커넥션 풀 총합이 서버 한도를 초과 (산술)

풀 설정이 **어느 서비스에도 없었다.** 즉 전부 HikariCP 기본값 10 이다. 총합을 세어 보면:

| | 계산 | 결과 |
|---|---|---|
| PostgreSQL 데이터소스 | user 1 + catalog 2 + cart 1 + order 1 + pay 1 + coupon 1 + chat 1 | **8개** |
| 레플리카 1일 때 | 8 × 10 | 80 |
| HPA `maxReplicas: 3` 이면 | 8 × 10 × 3 | **240** |
| PostgreSQL 기본 `max_connections` | | **100** |

**레플리카가 2개만 돼도 160 > 100 으로 초과한다.** 그때 나타나는 증상은 "느려짐"이 아니라
늦게 뜬 파드의 `FATAL: sorry, too many clients already` — 기동 실패다. HPA 가 부하에 반응해
스케일아웃하는 바로 그 순간에 터진다는 점이 특히 나쁘다.

### 조치

```yaml
hikari:
  maximum-pool-size: ${DB_POOL_SIZE:5}
  minimum-idle: ${DB_POOL_MIN_IDLE:2}
  connection-timeout: 5000        # 풀 고갈을 30초 기다리지 않고 빠르게 실패
  leak-detection-threshold: 20000 # 반납 안 된 커넥션 로그
  max-lifetime: 1200000
```

`8 × 5 × 3 = 120`. 서버의 `max_connections` 를 **200 으로 올리는 것이 전제**다
(기본 100 으로는 부족). `postgresql.conf`:

```
max_connections = 200
```

> 풀은 크다고 빠르지 않다. PostgreSQL 은 커넥션당 프로세스를 하나 띄우므로, 풀을 키우면
> DB 쪽 메모리와 컨텍스트 스위칭이 함께 늘어난다. 코어 수의 2~4배가 통상 권장선이고,
> 그 이상은 PgBouncer 같은 커넥션 풀러로 다중화하는 편이 맞다.

`connection-timeout: 5000` 도 의도적이다. 기본 30초는 풀이 마르면 **요청 스레드를 30초씩
묶어** 톰캣 스레드까지 고갈시킨다 — 한 서비스의 DB 지연이 전체 정지로 번지는 경로다.

### 가드레일

`ConfigurationGuardrailTest` 의 B-32 가 `데이터소스 × 풀 × 레플리카 ≤ 200` 을 빌드마다 검사한다.
데이터소스를 추가하거나 풀을 키우면 총합이 자동으로 다시 계산된다.

## 4. 부하 하네스 — `ops/14-load-test.sh`

동시성을 계단식으로 올리며 응답 코드 분포와 백분위를 기록한다. curl 기반이라 k6/JMeter
설치를 전제하지 않는다.

```bash
GATEWAY=https://localhost:8000 ./ops/14-load-test.sh
CONCURRENCY="1 5 10 20 50" DURATION=15 ./ops/14-load-test.sh
```

### 읽는 법

- **429 는 정상이다** — 레이트리미터가 일하고 있다는 뜻(ops/06 이 검증하는 그 동작).
- **5xx/무응답이 특정 동시성에서 갑자기** 나타나면 구조적 한계다. 순서대로 의심할 것:
  1. DB 커넥션 풀 고갈 — 로그의 `Connection is not available, request timed out`
  2. 서킷브레이커 개방 — `/actuator/metrics/resilience4j.circuitbreaker.state`
  3. 톰캣 스레드 포화 — `/actuator/metrics/tomcat.threads.busy`
- **p99 가 동시성에 선형**이면 큐잉, **계단식으로 뛰면** 타임아웃/재시도다.

절대 수치는 운영에 이식되지 않는다. **같은 머신에서 코드 변경 전후를 비교**할 때만 쓸 것.

> 로그인 → 장바구니 → 주문 같은 쓰기 경로 · 도착률 계단 · 급증(HPA)은 k6 시나리오로 잰다 — `ops/53`, [docs/83](83-load-test.ko.md).


## 5. 대량 데이터 생성 — `ops/tools/generate-catalog-data.py`

§1 에서 "실제 데이터 카디널리티는 로컬로 못 만든다"고 썼는데, **부분적으로 틀렸다.**
저장소에는 이미 실제 시드 CSV 가 있다(상품 250 · SKU 509 · 사이즈 1,290). 그것을
**템플릿으로 증식**하면 값의 분포는 보존한 채 규모만 키울 수 있다.

```bash
# 5만 상품(55만 행)을 25초에 적재
./ops/tools/generate-catalog-data.py --products 50000 \
    --copy "postgresql://product:pw@host:5435/productdb"

# 배치 잡으로 적재 경로까지 검증하려면 CSV 로
./ops/tools/generate-catalog-data.py --products 50000 --out /tmp/bigcsv
```

값을 지어내지 않는 것이 핵심이다. `product-00001` 같은 합성 문자열은 인덱스 선택도와
직렬화 비용을 왜곡한다. 이 생성기는 시드의 브랜드명·설명문·이미지 경로를 재사용하고,
SKU/사이즈 비율도 시드에서 **실측해** 따른다(상품당 SKU 2, SKU당 사이즈 3).

`created_at` 은 730일에 고르게 뿌린다 — 한 날짜에 몰리면 파티션 프루닝 효과를
측정할 수 없기 때문이다([docs/09](09-postgresql-partitioning-sharding.ko.md)).

### 만들면서 드러난 스키마 사실 두 가지

**① `ProductSku.color` 가 `@OneToOne` 이었다 — 설계가 아니라 매핑 오류.**
JPA 규약상 `@OneToOne` 소유측 FK 에는 **UNIQUE** 가 붙으므로, Hibernate 가 `color_id` 에
전역 UNIQUE 를 건다. 한 색상 행을 오직 한 SKU 만 참조할 수 있다는 뜻이다. 시드가
SKU 509 = 색상 509 로 제약을 **정확히 포화**시키고 있어서, 상품을 하나만 더 넣어도
`duplicate key value violates unique constraint` 로 막혔다.

이게 의도된 설계인지 실수인지는 저장소의 나머지 코드가 말해 준다 — 전부 색상을
**공유 어휘**로 다루고 있었다:

| 근거 | 공유를 전제한다는 뜻 |
|---|---|
| `ProductSkuRepository.findColorsByProductId` → `select DISTINCT b.color` | DISTINCT 는 색상이 반복될 때만 의미가 있다 |
| `ProductColorRepository.findByColor(String)` → `Optional<ProductColor>` | 색상명이 행을 식별한다 = 마스터 어휘 |
| `frontend/.../ColorsFilter.jsx` | 색상 필터 패싯 — 색상 1개당 상품 1개면 무의미 |
| `ProductColor` 안의 주석 처리된 `@OneToMany(mappedBy="color")` | 원저자도 "여럿이 하나를 참조"하는 모델을 의도했던 흔적 |

그래서 `@ManyToOne` 으로 바로잡고, 기존 DB 의 제약은
[`V4__relax_product_sku_color_unique.sql`](../catalog-service/src/main/resources/db/migration/V4__relax_product_sku_color_unique.sql)
로 해제한다. 기존 데이터는 색상값이 전부 고유하므로 **푸는 방향**은 어떤 행도 위반하지 않는다.

이 마이그레이션에서 두 가지를 더 배웠다:

- **제약 이름을 하드코딩할 수 없다.** Hibernate 가 만든 이름은
  `ukf0mvdc9aqf8t23p9di0hn9lpy` 같은 스키마 파생 해시라 환경마다 다르다. 이름이 아니라
  **정의**(`product_sku` 의 `color_id` 단일 컬럼 UNIQUE)로 `pg_constraint` 에서 찾아 지운다.
- **Flyway 는 Hibernate 보다 먼저 돈다.** 이 테이블은 마이그레이션이 아니라 `ddl-auto` 가
  만들기 때문에, 빈 DB(Testcontainers·신규 환경)에서는 마이그레이션 시점에 테이블이 아직
  없다. 가드 없이 실행했더니 `relation "product.product_sku" does not exist` 로 컨텍스트
  로딩이 통째로 깨졌다 — 테스트 4건 전부 실패. `to_regclass(...) IS NULL` 이면 무동작하게
  하고, 신규 스키마의 `color_id` 인덱스는 엔티티의 `@Table(indexes=...)` 가 만들도록 옮겼다.
  (UNIQUE 제약이 인덱스를 겸하고 있었으므로, 제약만 지우면 색상 조인이 Seq Scan 이 된다.)

회귀 테스트(`ProductQueryCountTest.색상은 여러 SKU 가 공유할 수 있어야 한다`)는 애노테이션
선언과 **DB 에 UNIQUE 가 남아 있지 않은지** 양쪽을 본다 — `ddl-auto: update` 는 이미 있는
제약을 지우지 않으므로 엔티티만 고쳐서는 실환경이 낫지 않기 때문이다.

**② `product_sku.images` 는 `text[]` 다.** CSV 에는 `"a.jpg,b.jpg"` 로 들어 있어
`COPY` 가 배열 리터럴로 파싱하려다 실패한다. 배치 잡은 DTO 에서 split 해 넘기므로
이 차이가 드러나지 않는다. 생성기가 배열 리터럴로 변환한다.

둘 다 데이터를 **대량으로 넣어 보기 전에는** 만날 일이 없는 사실이다.

### 5.1 수천만 개 — 백화점식 분류 · 묶음 적재 · 용량 실측 (2026-10-01)

2천만 개로 부하 테스트를 하려다 생성기의 한계 네 가지가 드러났다. 고친 뒤 로컬 PostgreSQL 16 에 **50만 개를 실제로 넣고** 재서
2천만 개를 외삽했다.

| 문제 | 영향 | 고친 것 |
|---|---|---|
| 모든 행을 메모리에 올린 뒤 한 번에 COPY | 2천만 개면 행 1억 3천만 개 — 메모리에서 멈춘다 | 2만 개 묶음마다 만들고 COPY · 커밋. 메모리는 묶음 하나만큼. 끊기면 `--resume` 이 이어 넣는다 |
| 시드 분류(소분류 15개)에 증식 | 소분류 하나에 130만 개 — 현실과 다른 병목을 잰다 | 대형 쇼핑몰식 분류를 내장(대분류 20 · 소분류 99). 시드와 이름이 같은 대분류는 그 id 를 쓰고 소분류를 더한다. 옵션(의류 사이즈 · 신발 치수 · 저장 용량 · 중량 · 묶음 · 책 형태)과 가격대가 소분류마다 다르다 |
| `--copy` 가 이미지에 클래스패스 경로(`images\food\…`)를 넣었다 | 적재한 상품의 이미지가 화면에서 전부 깨진다 | 시드 배치가 S3 에 올려 둔 URL 을 대분류별로 재사용 |
| `--copy` 가 V17 의 `sku_code` 를 비워 두었다 | 장바구니 · 주문이 사이즈별 SKU 코드 없이 기록된다 | V17 함수와 같은 규칙으로 생성기가 채운다(DB 함수를 6천만 행에 돌리지 않는다) |

그 밖에: 판매량은 긴 꼬리(파레토), 가격은 로그 균등, 브랜드는 대분류마다 400개. 적재 뒤 신원 시퀀스를 넣은 id 뒤로 옮긴다 — 안 그러면
관리자 등록이 언젠가 같은 id 에 부딪힌다. `--purge` 로 생성 범위만 지운다.

**실측 — 50만 개(인덱스가 있는 채로 적재, 운영과 같은 조건)**

| 표 | 상품당 행 | 표 + 인덱스 |
|---|---|---|
| product | 1 | 330 MiB |
| product_sku | 1.70 | 318 MiB |
| product_size | 3.02 | 301 MiB |
| product_subcategory | 1 | 56 MiB |
| **합계** | | **1.0 GiB = 상품당 약 2.1KB** |

인덱스 14개가 전체의 약 45% 다. 가장 큰 것은 `uq_product_size_sku_code`(90 MiB)와 `idx_product`(이름 · 브랜드 · slug · 카테고리 복합,
84 MiB)다. 적재 속도는 4코어 x86 · `synchronous_commit=off` 에서 초당 약 3,000개.

**2천만 개 외삽** — `--estimate` 가 같은 계산을 한다.

| 항목 | 값 |
|---|---|
| 행 수 | 상품 2,000만 · SKU 3,400만 · 사이즈 6,040만 · 연결 2,000만 |
| PostgreSQL | **약 39 GiB**(적재 중 WAL 까지 약 59 GiB 여유 필요) |
| Elasticsearch | 약 19~30 GiB(추정 — 재지 않았다) |
| 적재 시간 | 4코어 x86 에서 약 2시간, 운영 노드(Ampere · 기본 설정)에서는 그 3~5배로 잡는다 |

**⚠️ 운영 노드에 그대로 넣으면 안 되는 이유**

- 노드 메모리는 24GB(2026-10-01 `free -h`: 사용 9.7Gi · 가용 13Gi), 부트 볼륨은 무료 한도 합계 200GB 다. DB + 색인만 60~90GB 다.
- PostgreSQL 파드의 메모리 상한은 512Mi, Elasticsearch 힙은 512MB 다(k8s/90). 39 GiB 표를 그 메모리로 읽으면 거의 모든 조회가
  디스크로 간다 — 재는 것이 앱이 아니라 디스크가 된다. 512MB 힙으로 2천만 개(nested 문서 포함 약 7천만 Lucene 문서)를 색인하면
  메모리 부족으로 멈출 가능성이 크다.
- 적재하는 몇 시간 동안 운영 트래픽과 디스크를 다툰다. 백업 CronJob 도 그만큼 커진다.

운영 노드에서는 `./ops/44-bulk-catalog.sh load`(기본 200만 개)가 위 점검 · 적재 · 캐시 비우기를 한 번에 하고, `purge` 가 지운다.

권장: 2천만 개는 **별도 부하 테스트 환경**(PostgreSQL 메모리 4GB 이상, ES 힙 2~4GB)에서. 운영 노드에서 재려면 **200만 개**(약 4 GiB +
색인 2~3 GiB)가 현실적인 상한이다. 끝나면 `--purge` 후 `VACUUM`.

> **사용자도 많이** — 로그인되는 데모 사용자 대량 생성은 [docs/79](79-demo-users.ko.md)(`ops/48`). 상품(`ops/44`)과 합쳐 "상품도 사용자도 많은" 운영 데모가 된다.

### 5.2 Elasticsearch 로 바꿔 재기 — 막혀 있던 세 곳 (2026-10-01)

검색을 `SEARCH_ENGINE=es` 로 바꿔 같은 데이터로 비교하려 하자, 재색인이 대량 데이터에서 돌 수 없는 이유 세 가지가 나왔다.

| # | 문제 | 고친 것 | 확인 |
|---|---|---|---|
| ① | `es-settings.json` 이 nori · kuromoji 토크나이저를 쓰는데 클러스터의 ES(기본 이미지)에 플러그인이 없다 — 재색인이 **색인 생성 단계에서** 실패한다(docs/16 · docs/17 에 "nori 미검증"으로 남아 있던 것) | `k8s/90` ES 에 initContainer — `elasticsearch-plugin install analysis-nori analysis-kuromoji`. 플러그인은 PVC 안(`.es-plugins`)에 두어 한 번 받으면 재시작에 네트워크가 필요 없다. ES 버전이 바뀌면 어긋난 것만 다시 받는다 | 같은 이미지로 "이미 있음 → 건너뜀", "버전 다름 → 지우고 설치" 를 돌렸다. ES 가 데이터 경로 안의 `.es-plugins` 와 함께 green 으로 뜨는 것도 확인. **실제 내려받기는 검증 환경의 네트워크 제한(artifacts.elastic.co 차단)으로 못 했다 — 운영에서 첫 기동 로그로 확인할 것.** 가드: 설정이 쓰는 플러그인 토크나이저마다 ES 매니페스트가 설치하는가(빼면 실패 확인) |
| ② | 상품 리더가 컬렉션 넷을 JOIN FETCH 한 쿼리에 OFFSET 페이징 — Hibernate 가 **매 페이지마다 결과 전체를 메모리에 올린다**(HHH90003004). ORDER BY 도 없다. SKU · 사이즈 리더는 OFFSET 이라 600만 행에서 끝나지 않는다. 멀티 스레드 스텝의 프로세서가 리더의 EntityManager 로 LAZY 를 동시에 읽었다 | `KeysetItemReader` — id 만 `WHERE id > :last ORDER BY id LIMIT n`, 그 id 들의 엔티티를 `IN` 으로, 매퍼가 쓰는 연관(카테고리 · SKU · 색상 · 소분류 · 사이즈)을 같은 페이지 안에서 채우고 EntityManager 를 닫아 분리된 엔티티를 넘긴다 | `KeysetProductReaderTest`(PostgreSQL 컨테이너): 첫 read 가 한 페이지만 읽는다 · 모든 상품을 id 순으로 한 번씩 · DELETED 제외 · 세션 없이 매퍼가 돈다 · 4 스레드 동시 읽기에 중복 없음. 페이지 제한이나 연관 채우기를 빼면 해당 테스트가 실패한다 |
| ③ | ES 힙 512MB | 힙 1GB, 컨테이너 상한 2Gi | 노드 가용 메모리 13Gi(실측) 안에서 |

**절차** — 상품을 넣은 뒤:

```bash
./ops/28-deploy.sh local <버전>          # ① ② 가 들어간 이미지 · 매니페스트
kubectl -n elasticsearch logs deploy/elasticsearch -c install-analysis-plugins   # "설치: analysis-nori analysis-kuromoji" 또는 "이미 있다"
./ops/44-bulk-catalog.sh load            # 상품 200만
./ops/03-enable-es-search.sh             # 재색인 → 별칭 → 문서 수 → 분석기 확인
# k8s/10-configmap.yaml 의 SEARCH_ENGINE 을 "es" 로 → ./ops/28-deploy.sh local --config-only → catalog-service 재시작
```

비교할 때는 `SEARCH_ENGINE` 만 바꿔 같은 데이터로 잰다. 생성기는 DB 에만 넣으므로 ES 는 **넣은 뒤 재색인해야** 보인다.

## 6. 실측 — 5만 상품에서의 실행계획

적재 후 `EXPLAIN (ANALYZE)` 로 잰 결과. 이제 **인덱스 선택도**를 실제로 볼 수 있다.

| 쿼리 | 결과 | 판정 |
|---|---|---|
| 커서 페이지네이션 (`product_id < N ORDER BY DESC LIMIT 21`) | `Index Scan Backward`, 0.13ms, 13 buffers | ✅ 설계대로 |
| **이름 검색 `LIKE '%Olive%'`** | **`Seq Scan` 8ms, 48,800행 버림** | 🔴 §6.1 |
| 카테고리 조인 | 21건 반환에 120행 스캔(읽기 증폭 5.7배) | 🟠 §6.2 |

### 6.1 이름 검색이 전체 스캔이다 (CAT-1 의 실제 비용)

```
Seq Scan on product (actual time=0.021..7.960 rows=1200)
  Filter: ((name)::text ~~ '%Olive%'::text)
  Rows Removed by Filter: 48800
```

[docs/06 CAT-1](06-catalog-search-issues.ko.md) 이 "검색이 Elasticsearch 를 쓰지 않는다"고
지적한 그 경로다. 5만 건에서 8ms 는 견딜 만해 보이지만 **선형으로 늘어난다** — 50만 건이면
80ms, 그리고 이건 캐시가 비었을 때가 아니라 **모든 요청마다**다. 선행 와일드카드(`%...`)라
B-tree 인덱스로는 해결되지 않는다. 선택지는 셋:

1. **ES 로 전환** — 이미 준비돼 있다(`SEARCH_ENGINE=es`, [ops/03](../ops/03-enable-es-search.sh)). 정석.
2. `pg_trgm` GIN 인덱스 — ES 없이 부분일치를 인덱싱. 중간 규모까지는 충분하다.
3. 접두어 검색으로 제한(`LIKE 'Olive%'`) — 그러면 일반 B-tree 로 해결되지만 UX 가 바뀐다.

**이 측정 없이는 "언젠가 느려질 것"이라는 추측이었다.** 이제 숫자가 있다.

### 6.2 카테고리 조인의 읽기 증폭

커서 페이지네이션이 `product_id` 로 먼저 정렬한 뒤 카테고리로 거르므로, 21건을 돌려주려고
120행을 읽는다(5.7배). 카테고리가 5개라 평균 5배인 셈 — 산술과 맞는다. 카테고리가 늘거나
희소한 카테고리를 조회하면 증폭이 커진다. `(category_id, product_id DESC)` 복합 인덱스가
정석 해법이나, 실제 트래픽의 카테고리 분포를 보고 결정할 일이라 여기서는 **측정만** 해 둔다.

## 7. 아직 측정하지 않은 것 (정직하게)

| 항목 | 왜 안 했나 |
|---|---|
| ~~인덱스 선택도~~ | ✅ §6 에서 측정 — 시드 CSV 증식으로 해결. 단 **실제 트래픽의 쿼리 분포**는 여전히 미지 |
| GC 튜닝 | 지속 부하가 필요. 현재 힙 상한만 지정 |
| Kafka 컨슈머 처리량 | 구조는 검증했으나 배치 크기 최적값은 실메시지 크기에 달렸다 |
| ES 쿼리 성능 | nori 미적용 상태라 한국어 검색 품질·성능 모두 미측정 |
| 프론트엔드 번들 크기 | CI 가 빌드는 하지만 크기 예산은 없다 |

## 8. 요약

로컬 튜닝으로 **두 개의 실제 결함**을 잡았고, 둘 다 운영에서는 부하가 걸린 순간에야
드러났을 종류다:

| 결함 | 증상이 나타났을 시점 | 지금 |
|---|---|---|
| 상품 목록 N+1 (1+4N) | 카탈로그가 커질수록 목록 API 가 느려짐 | `@BatchSize` + 쿼리 수 테스트 |
| 커넥션 풀 총합 240 > 100 | **HPA 가 스케일아웃하는 순간** 파드 기동 실패 | 풀 5 + 총합 가드레일 |
| 이름 검색 전체 스캔 | 카탈로그 규모에 선형으로 느려짐 | 측정 완료(§6.1) — ES 전환 또는 `pg_trgm` |
| SKU 가 색상 수(509)에 묶임 | 상품을 늘리는 순간 적재 실패 | 제약을 문서화, 생성기가 색상도 함께 생성 |

앞의 둘은 시간이 아니라 **개수를 세어** 찾았기에 CI 에 고정했다.
뒤의 둘은 **규모를 키워 보고서야** 드러났다 — 시드 250건에서는 둘 다 보이지 않는다.

> 여기서 얻은 교훈: "로컬에서는 성능을 알 수 없다"는 통념이 실제로는 **세 겹**이다.
> 구조적 사실은 지금 바로 잴 수 있고(쿼리 수·풀 산술), 데이터 의존적 사실은
> **시드를 증식하면** 잴 수 있으며(인덱스 선택도), 트래픽 의존적 사실만이
> 진짜로 운영을 필요로 한다(쿼리 분포·GC·실지연). 앞의 두 겹을 로컬에서 걷어내면
> 운영에서 마주할 놀라움이 그만큼 줄어든다.
