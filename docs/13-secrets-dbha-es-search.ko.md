# 시크릿 정리(G1) · PostgreSQL 복제/HA(G17) · 검색 ES 전환(G19)

[日本語](13-secrets-dbha-es-search.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 유출된 DB 비밀번호는 2026-09-25 에 "어디서도 통하지 않음" 을 확인해 종결했습니다([docs/30 §6](30-git-history-secret-exposure.ko.md)). DB → ES 는 실시간 색인입니다.

> 작성일: 2026-07-27 · 최종 점검의 마지막 🔴 덩어리 3건 적용 기록
> **실행형 스크립트**: 이 문서의 운영 절차들은 [`ops/`](../ops/README.ko.md) 에 가드된 스크립트로 준비되어 있다.

## 1. G1 — 커밋된 DB 비밀번호 제거 + 로테이션 런북

**저장소 측 조치(적용됨)**: 커넥터 JSON 4개(`*-outbox-connector-{postgresql,mysql}.json`)의 평문 `database.password` 를 Kafka Connect **파일 config provider** 참조로 교체:

```json
"database.password": "${file:/etc/kafka-connect/secrets/connect-secrets.properties:db-password}"
```

Connect 워커에 provider 활성화가 필요하다(worker properties):
```properties
config.providers=file
config.providers.file.class=org.apache.kafka.common.config.provider.FileConfigProvider
```
그리고 워커 호스트의 `/etc/kafka-connect/secrets/connect-secrets.properties` 에:

```properties
db-password=<새 비밀번호>
db-host=<DB 호스트>
```

**호스트명도 같이 뺐다**(2026-07-28). 커넥터 JSON 에는 특정 배포 환경의 공개 호스트명이 그대로
남아 있었다 — 비밀번호만큼은 아니어도, 저장소를 읽는 누구에게나 "이 주소의 5435 포트에 DB 가
있다"고 알려 주는 정보다. 정찰 비용을 공짜로 낮춰 줄 이유가 없다.

**⚠️ 이것만으로는 끝나지 않는다 — 로테이션 런북(저장소 밖 조치, 필수)**:
1. 노출된 비밀번호는 **git 이력과 GitHub에 이미 공개**되어 있다. 파일 수정은 재발 방지일 뿐, 유일한 실질 해결은 **비밀번호 변경**이다.
2. PostgreSQL: `ALTER ROLE product WITH PASSWORD '<new>';` (MySQL 병행 사용 시 동일).
3. 새 값을 배포 채널에 반영: 각 서비스의 `LOCAL_DB_PASSWORD` env / `k8s/20-secret.yaml` / Connect secrets 파일 → 서비스·커넥터 재기동.
4. 저장소에서 호스트명은 제거했지만(위 참조), **이미 공개된 이력은 되돌릴 수 없다.** 그 호스트가 공인망에서 5435 포트를 열고 있는지 방화벽/보안그룹을 반드시 점검할 것 — DB 포트가 인터넷에 열려 있으면 비밀번호 로테이션은 시간을 벌 뿐이다.
5. (선택) git 이력 파기(BFG/`git filter-repo`)는 포크·클론에 전파되지 않으므로 로테이션의 대체가 아니다 — 로테이션이 끝이고, 이력 파기는 부가 조치다.

## 2. G17 — PostgreSQL 스트리밍 복제 (SPOF 제거 1단계)

**검증 환경의 PostgreSQL 16.13으로 전 절차를 실검증한 뒤** 그대로 compose 로 인코딩했다:

검증 로그(실행 결과): ① 프라이머리 기동 → ② `pg_basebackup -R` 로 스탠바이 생성 → ③ 프라이머리 INSERT 가 스탠바이에서 조회됨(`pg_is_in_recovery()=t`) → ④ `pg_ctl promote` 승격 후 쓰기 성공(`pg_is_in_recovery()=f`).

**추가 산출물**:
| 파일 | 내용 |
|---|---|
| `docker-compose-postgres-ha.yml` | primary(5435, 기존 서비스 설정과 포트 호환) + replica(5436, hot standby read-only). `wal_level=replica`, `wal_keep_size=512MB`, healthcheck |
| `postgres-ha/init-primary.sh` | replicator 계정 생성 + pg_hba 복제 허용 |
| `postgres-ha/replica-entrypoint.sh` | PGDATA 비면 `pg_basebackup -R` 후 스탠바이 기동 |
| `k8s/80-postgres-cnpg.yaml.example` | k8s 완성형 — CloudNativePG 3노드(자동 failover, rw/ro 서비스). CRD 필요라 `.example` 로 두어 일괄 apply 에서 제외 |

**운영 노트**:
- compose 판은 **수동 failover**(`pg_ctl promote` + 서비스 접속 전환)다. 자동 failover 는 Patroni 또는 CNPG.
- 읽기 분산 시 **read-your-writes 경로는 반드시 프라이머리로**(docs/09 §5.2, F-13).
- 다음 단계(docs/09 P2 완성): 쓰기 핵심(orderdb/paydb)과 읽기 핵심(productdb) 인스턴스 분리.

## 3. G19 — 검색 Elasticsearch 전환 (docs/07 R-C1/R-C2 구현)

### 고친 버그 2건 (재색인 파이프라인 — docs/06 CAT-9/CAT-10)
1. **alias 롤오버가 항상 실패**: Writer 는 `<ts>-products` 에 색인하는데 태스크릿은 접미사 없는 이름을 확인 → "인덱스 없음" 예외로 영구 실패. 접미사 정렬 + **최초 롤오버 시 alias 부재 가드** 추가.
2. **매핑/애널라이저 사문화**: `save()` 의 동적 매핑 자동 생성으로 nori/kuromoji·Nested 선언이 전부 무시됐다. ProductWriter 가 색인 전에 `createSettings()/createMapping()` 으로 **명시적 인덱스를 먼저 생성**하도록 수정 — 한/영/일 애널라이저가 실제 적용된다.

### ES 검색 경로 (기본 OFF, 플래그 opt-in)
- **`EsProductSearchService`**(신규, `@ConditionalOnProperty(search.engine=es)`): `product-alias` 대상 multi_match(name/brand/description × 한/영/일 멀티필드) → 관련도순 productId 최대 1000건.
- **`SearchService`**: 검색어가 있고 ES 빈이 존재하면 LIKE 대신 **`productId IN (esIds)`** 로 좁힘 — "ES 로 관련도 검색, Postgres 로 상세/파셋 하이드레이션" 패턴. 응답 형태(SearchResultDto)·파셋·캐시 전부 기존 그대로.
- **폴백**: ES 예외 시 경고 로그 후 기존 LIKE 경로로 자동 폴백. ES가 "매칭 없음"이면 빈 결과.
- **기본값이 postgres 인 이유(정직한 한계)**: 당시 검증 환경엔 ES 인스턴스가 없어 통합 검증을 못 했다. 코드는 컴파일·단위 테스트(ES 사용/폴백 2케이스 포함, SearchService 6/6)까지 확인됐고, 활성화는 운영에서 아래 절차 후 `search.engine=es` 로:
  1. 재색인 실행(`POST /api/products/sync`) → 이번 수정으로 매핑 적용 + alias 롤오버가 실제로 완료됨
  2. `GET product-alias/_search` 로 색인/애널라이저 확인
  3. `search.engine=es` 배포 → 검색 품질/지연 비교 → 이상 시 플래그만 되돌리면 즉시 원복

### 남은 것 (후속)
- 관련도 순서가 Postgres 하이드레이션에서 보존되지 않음(현재 정렬은 기존 로직) — 정렬 보존 필요 시 esIds 순서 기반 재정렬 추가.
- category/subcategory 등 다른 인덱스들의 명시적 매핑, `search_after` 페이지네이션, DB↔ES 증분 동기(outbox 기반, docs/07 R-C3).
