# 20. 스키마 소유권 — `ddl-auto` 를 끄고 Flyway 에 넘기기

[日本語](20-schema-ownership.md) | **한국어**

> 이 문서는 "누가 스키마를 만드는가"를 Hibernate 에서 Flyway 로 옮긴 작업의 기록이다.
> 관련: [docs/09](09-postgresql-partitioning-sharding.ko.md)(파티셔닝) · [docs/19 §5.1](19-local-performance-tuning.ko.md)(색상 매핑 오류)

## 1. 무엇이 문제였나

전환 전, 7개 서비스 전부가 이렇게 돼 있었다:

```yaml
spring.jpa.hibernate.ddl-auto: update
```

Flyway 는 이미 도입돼 있었지만([docs/09](09-postgresql-partitioning-sharding.ko.md) P0) 파티션 테이블
같은 **특수한 것만** 만들고, 일반 테이블은 여전히 Hibernate 가 만들고 있었다. 과도기로 의도한
상태였는데, 그 과도기가 세 가지 비용을 계속 물리고 있었다.

**① 스키마 변경이 이력에 남지 않는다.** 어떤 컬럼이 언제 왜 생겼는지 알 방법이 없다. 코드의
diff 는 엔티티가 바뀐 것만 보여주지, 그게 어느 배포에서 어떤 DDL 로 반영됐는지는 말해주지
않는다. 롤백 계획을 세울 근거도 없다.

**② 운영 DB 에 검토되지 않은 DDL 이 나간다.** `update` 는 매 기동마다 그 시점의 엔티티에서
DDL 을 유추해 실행한다. 리뷰도, 승인도, 실행 계획 확인도 없다. 대형 테이블에 인덱스를 거는
문장이 배포 중에 조용히 나갈 수 있다.

**③ 제약을 지우지 않는다 — 이게 실제로 사고를 냈다.** `update` 는 **추가만** 한다. 그래서
`ProductSku.color` 가 `@OneToOne` 이던 시절 Hibernate 가 `color_id` 에 걸어 둔 UNIQUE 제약은,
매핑을 `@ManyToOne` 으로 고친 뒤에도 DB 에 그대로 남았다. 엔티티만 고쳐서는 아무것도 낫지
않았고, 결국 [V4 마이그레이션](../catalog-service/src/main/resources/db/migration/V4__relax_product_sku_color_unique.sql)
을 손으로 써야 했다. **③은 ①·②와 달리 이론적 위험이 아니라 이미 지불한 비용이다.**

## 2. 무엇으로 바꿨나

| | 전 | 후 |
|---|---|---|
| 스키마 생성 | Hibernate `ddl-auto: update` | Flyway `V*__baseline_schema.sql` |
| Hibernate 의 역할 | 생성 + 검증 | **검증만** (`validate`) |
| 스키마 변경 절차 | 엔티티 고치고 배포 | 마이그레이션 작성 → 리뷰 → 배포 |
| 어긋났을 때 | 조용히 ALTER | **기동 실패** (`Schema-validation: missing column`) |

`validate` 는 어긋남을 **시끄럽게** 만든다. 이건 부작용이 아니라 목적이다 — 조용히 고쳐지는
것보다 배포가 멈추는 편이 낫다.

설정은 `${DDL_AUTO:validate}` 로 두어 환경변수로 덮어쓸 수는 있게 했다. 위험한 것은 긴급 상황의
의도적 오버라이드가 아니라 **커밋된 기본값**이기 때문이고, 가드레일도 기본값만 본다.

## 3. 베이스라인을 손으로 쓰지 않은 이유

`validate` 로 넘어가려면 Flyway 가 **엔티티와 정확히 일치하는** 스키마를 만들 수 있어야 한다.
컬럼 하나, 타입 하나만 어긋나도 기동이 실패한다. 그걸 손으로 옮겨 적는 것은 실패가 예정된
작업이다 — catalog-service 하나만 해도 테이블 10개에 컬럼 85개다.

그래서 **Hibernate 자신에게 받아 적게 했다**:

```bash
# ① Hibernate 가 만들 DDL 을 그대로 뽑는다 (컨텍스트를 실제로 띄우면서)
./gradlew -PexportSchema -PwithContainers :catalog-service:test --tests '*ApplicationTests*'
#   → build/schema/catalog-service.sql

# ② 멱등 마이그레이션으로 기계 변환
./ops/tools/hbm2flyway.py build/schema/catalog-service.sql \
    -o catalog-service/src/main/resources/db/migration/V5__baseline_schema.sql
```

`-PexportSchema` 는 `jakarta.persistence.schema-generation.scripts.action=create` 를 켜는
개발용 스위치다(CI 는 쓰지 않는다). 엔티티를 크게 바꾼 뒤 베이스라인을 다시 뜰 때만 쓴다.

### 왜 그대로 쓸 수 없고 변환이 필요한가

DB 가 두 가지 상태로 존재하기 때문이다:

- **빈 DB** (새 환경·Testcontainers) — 마이그레이션이 전부 만들어야 한다
- **`ddl-auto` 가 이미 만들어 둔 DB** (운영·개발 서버) — 전부 이미 있다

두 번째에 `create table` 을 그냥 던지면 `relation already exists` 로 기동이 죽는다. 그래서
`hbm2flyway.py` 가 모든 문장을 **이미 있으면 넘어가는** 형태로 바꾼다:

| Hibernate 산출물 | 변환 결과 |
|---|---|
| `create schema S` | `CREATE SCHEMA IF NOT EXISTS S` |
| `create table T (...)` | `CREATE TABLE IF NOT EXISTS T (...)` |
| `create index I on T (...)` | `CREATE INDEX IF NOT EXISTS I on T (...)` |
| `alter table T add constraint N ...` | `pg_constraint` 에서 이름 존재를 확인하는 `DO` 블록 |
| (컬럼마다 추가 생성) | `ALTER TABLE T ADD COLUMN IF NOT EXISTS c ...` |

마지막 줄이 핵심이다. `create table if not exists` 는 **이미 있는 테이블을 건드리지 않으므로**,
기존 DB 가 엔티티보다 뒤처져 있으면(컬럼이 빠져 있으면) 그대로 남아 `validate` 가 실패한다.
컬럼별 `add column if not exists` 가 그 틈을 메운다.

> **"그럼 ddl-auto 랑 뭐가 다른가?"** — ddl-auto 는 매 기동마다 그때의 코드에서 DDL 을 새로
> 유추해 **검토 없이** 실행한다. 베이스라인은 커밋된 파일에 적힌 **고정된 문장 목록**이고,
> 리뷰를 거치며, Flyway 가 기록하므로 **딱 한 번만** 실행된다. 그 뒤 스키마를 또 바꾸려면
> 새 마이그레이션을 써야 한다.

컬럼은 반드시 **널 허용으로** 추가한다. 기존 행이 있는 테이블에 기본값 없는 NOT NULL 은 붙지
않기 때문이고, Hibernate 의 `validate` 는 널 허용 여부를 보지 않는다(테이블·컬럼 존재와 타입만).

### 마이그레이션 순서가 중요하다

베이스라인은 각 서비스의 **마지막** 버전이다(catalog V5, order/pay/user/cart V2, chat V3,
coupon V4). 앞선 마이그레이션이 만드는 것 — 특히 파티션 아웃박스 테이블 — 을 덮어쓰지 않기
위해서다. 빈 DB 에서 V1 이 파티션 테이블을 만들면, 베이스라인의 평범한 `create table` 은
`IF NOT EXISTS` 로 건너뛴다. 파티션 구조가 이긴다.

## 4. 어떻게 검증했나

### 빈 DB — 베이스라인이 엔티티와 정확히 일치하는가

7개 서비스 전부를 `ddl-auto: validate` 로 두고 Testcontainers 의 **빈** PostgreSQL 에 띄웠다.
어긋난 컬럼이 하나라도 있으면 컨텍스트가 뜨지 않으므로, **기동 성공 자체가 증명**이다.

```
:common:test :catalog-service:test :order-service:test :pay-service:test
:user-service:test :cart-service:test :coupon-service:test :chat-service:test
→ BUILD SUCCESSFUL
```

### 기존 DB — 베이스라인이 정말 무동작인가

이쪽이 실제 위험이다. 레거시 DB 를 재현해(예전 마이그레이션 V1~V3 + Hibernate 가 만들었을
스키마 + 그 시절의 UNIQUE 제약) 스키마를 **184개 항목**으로 스냅샷한 뒤 새 마이그레이션을
적용했다.

```
② V4 + V5 적용 → 오류 없음 (건너뛴 객체 104개)
③ 스키마 차이  → 없음 (184 → 184, V4 가 지운 UNIQUE 외 변화 없음)
④ 재적용        → 오류 없음, 스키마 불변
   외래키 9개 보존, product_sku 의 UNIQUE 제거 확인
```

> 처음 이 검증을 돌렸을 때 "차이 없음"이 나왔는데, 실은 스냅샷 쿼리가
> `text || "char"` 타입 오류로 통째로 실패해 **빈 파일 두 개를 비교**하고 있었다.
> 스냅샷이 184줄이라는 것을 확인하고 나서야 의미 있는 비교가 됐다.

### 가드레일이 실제로 막는가

`ddl-auto: update` 를 order-service 에 되돌려 넣고 빌드했다 → B-33 즉시 실패. 원복 후 통과.

## 5. 여러분의 DB 에서 해야 할 일

전환의 위험은 **여러분의 기존 DB 가 엔티티와 어긋나 있는 경우**다. 그러면 배포 즉시 기동
실패한다. 배포 전에 같은 판정을 미리 내리는 스크립트가 있다:

```bash
# 진단만 — 지금 배포하면 어떻게 되는지
PGURL='postgresql://user:pw@host:5432/productdb' SERVICE=catalog-service \
  APPLY=0 ./ops/15-schema-baseline-rehearsal.sh

# 베이스라인을 적용해 보고 무엇이 바뀌는지 확인 (운영 DB 말고 **사본**에)
PGURL=... SERVICE=catalog-service ./ops/15-schema-baseline-rehearsal.sh
```

스크립트는 ①적용 전 스냅샷 → ②베이스라인 적용 → ③변화 목록 → ④**Hibernate validate 와 같은
판정**(요구 컬럼이 전부 있는가) → ⑤ddl-auto 잔재(해시 이름의 UNIQUE 제약) 점검을 한다.
종료코드 0 이면 그 DB 에서 `validate` 로 뜰 수 있다는 뜻이다.

컬럼을 일부러 하나 지운 DB 로 시험해 보면 이렇게 나온다:

```
④ ❌ 없는 컬럼 1 개 — 지금 배포하면 기동 실패합니다:
      product.product.refundpolicy
```

적용 모드로 다시 돌리면 그 컬럼을 채우고 통과한다.

**권장 순서**: `ops/09` 로 뜬 복원본 → `ops/15` 진단 → 문제 없으면 적용 모드 → 스테이징
(`ops/12`) → 운영.

## 6. 앞으로 스키마를 바꾸려면

1. 엔티티를 고친다.
2. **마이그레이션을 쓴다** (`V<다음번호>__<설명>.sql`). 베이스라인을 고치지 않는다 — 이미
   적용된 마이그레이션을 수정하면 Flyway 체크섬이 깨진다.
3. `./gradlew :<서비스>:test -PwithContainers` — 빈 DB 에서 `validate` 가 통과하는지가 곧 검증이다.
4. 배포 전 `ops/15` 로 실제 DB 를 확인한다.

베이스라인 자체를 다시 뜰 일은 드물다(엔티티를 대규모로 재편했을 때 정도). 그때는 §3 의
두 명령을 다시 돌리고, **새 버전 번호로** 저장한다.

## 7. 남은 것

| 항목 | 상태 |
|---|---|
| 여러분의 운영 DB 에서 `ops/15` 실행 | 🔴 **운영 조치 필요** — 이 저장소에서는 확인할 수 없다 |
| `flyway.baseline-on-migrate` 를 끄기 | 🟡 모든 환경이 이력을 갖게 된 뒤에 |
| 마이그레이션 롤백(undo) 스크립트 | 🟡 Flyway Teams 기능. 지금은 복원(ops/09)이 롤백 경로다 |
