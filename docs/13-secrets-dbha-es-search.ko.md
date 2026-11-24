# 시크릿 정리(G1) · PostgreSQL 복제/HA(G17) · 검색 ES 전환(G19)

[日本語](13-secrets-dbha-es-search.md) | **한국어**

> 🕰️ **상태(2026-11-14) — 2026-07-27 의 기록.** 유출된 DB 비밀번호는 2026-09-25 에 "어디서도 통하지 않음" 을 확인해 종결했습니다([docs/30 §6](30-git-history-secret-exposure.ko.md)). §3 의 ES 검색은 이제 기동 설정 `search.engine=es`(ES 가 이 배포에 있는가) + 기능 플래그 `catalog.search-engine` 비율로 고릅니다([docs/74](74-feature-flags.ko.md)); DB → ES 는 상품 저장마다 실시간 색인(`ProductIndexService`)입니다. 운영(OCI)은 ES 가 없어 PostgreSQL 검색 그대로입니다.

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

> 🕰️ 지금: `search.engine=es` 는 "ES 빈 · 색인이 있다" 만 정하고, 검색마다 어느 엔진으로 갈지는 기능 플래그 `catalog.search-engine`(ConfigMap `feature-flags` — `k8s/15-feature-flags.yaml` + `ops/28 --config-only`, 재기동 없이 비율로 · `SearchEngineSelector`, docs/74)가 정한다. 되돌리기도 그 비율을 0 으로.

### ES 를 띄우는 절차 (실습 · 데모 — 운영에는 ES 가 없다)

`k8s/90-dev-infra.yaml` 에 ES 넷(Namespace · PVC 5Gi · Deployment · Service 9200)이 있다. **통째로 apply 하지 않는다** — 다른 인프라까지 저장소의 원본으로 되돌린다(docs/22 §4). 라벨로 ES 만 고른다(가드 **B-120** 이 넷에 라벨이 있는지 지킨다):

```bash
# 0) 노드 준비 — ES 는 vm.max_map_count ≥ 262144 가 없으면 기동 중에 죽는다(bootstrap check)
sudo sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-elasticsearch.conf   # 재부팅에도

# 1) ES 넷만
kubectl apply -f k8s/90-dev-infra.yaml -l ecommerce.io/infra=elasticsearch

# 2) 뜨기까지 — 처음은 플러그인(nori · kuromoji)을 받아 initContainer 가 1 ~ 2분
kubectl -n elasticsearch get pod -w
kubectl -n elasticsearch logs deploy/elasticsearch -c install-analysis-plugins
kubectl -n elasticsearch exec deploy/elasticsearch -- curl -s localhost:9200/_cluster/health
```

- 메모리: 힙 1g · 컨테이너 상한 2Gi(`ES_JAVA_OPTS` · `resources.limits`). 노드에 그만큼 여유가 있어야 한다 — 없으면 `OOMKilled` 로 되풀이한다.
- 디스크: PVC 5Gi(local-path = 노드 디스크). 플러그인도 PVC 안(`.es-plugins`)에 둔다 — 재시작에 네트워크가 필요 없다.
- `strategy: Recreate` · `replicas: 1` — 두 파드가 같은 data 경로를 열면 두 번째가 `node.lock` 에서 죽는다.
- 네트워크 정책: `k8s/95` 의 `allow-ecommerce-only`(elasticsearch 네임스페이스)가 **ecommerce 네임스페이스에서만** 받는다. ⚠️ 여러 노드인 클러스터에서 kube-router 는 `namespaceSelector` 로 다른 노드의 파드를 막는다 — catalog 와 ES 가 다른 노드면 그것부터 본다.
- catalog 는 이미 `SPRING_ELASTICSEARCH_URIS=http://elasticsearch.elasticsearch.svc.cluster.local:9200`(k8s/41)을 들고 있다. 상태(`/actuator/health`)에 ES 를 넣으려면 그 배포에 `MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=true`(기본 꺼짐 — 가드 B-116: ES 가 없는 운영에서 health 가 늘 DOWN 이었다).

⚠️ **재색인은 한 번에 하나만** — catalog 레플리카가 2개 이상이면 두 번째 요청이 다른 파드로 가 잡이 둘 돌 수 있었다(2026-11-20 실습에서 21시간, [docs/62 §23](62-engineering-casebook.ko.md)). 이제 Redis 잠금(`catalog:product-reindex:lock`)이라 파드 수와 무관하다 — 두 번째는 409, Redis 에 닿지 못하면 503. 파드가 잠금을 쥔 채 죽었으면 `redis-cli DEL catalog:product-reindex:lock`.

💡 **잡이 끝나기 전에도 검색을 세울 수 있다.** 검색이 읽는 것은 별칭 `product-alias` 하나뿐이고(다른 인덱스는 배치가 쓰지만 읽는 코드가 없다), 별칭 교체는 **마지막 단계 뒤**다. 상품 인덱스가 다 차면(부모 수 = 상품 수) 손으로 붙여도 된다:

```bash
ES="kubectl -n elasticsearch exec deploy/elasticsearch -c elasticsearch -- curl -s"
$ES "localhost:9200/_cat/indices/products-*-products?v"      # docs.count 는 부모 + 중첩이다
$ES -XPOST -H 'Content-Type: application/json' localhost:9200/_aliases \
  -d '{"actions":[{"add":{"index":"products-<타임스탬프>-products","alias":"product-alias"}}]}'
$ES localhost:9200/product-alias/_count                       # 이것은 부모만 — 상품 수와 같아야 한다
```

⚠️ `_cat/indices` 의 `docs.count` 에 놀라지 말 것 — `ProductDocument` 의 `skus` · `subcategories` 가 `Nested` 라 **중첩 객체가 Lucene 문서로 따로 셉니다**. 실습 실측: 상품 1,000만 → `docs.count` **37,003,969** = 1,000만(부모) + 17,003,969(SKU) + 1,000만(하위 분류), `_count` 는 정확히 1,000만. 중복이 아니다.

그다음 재색인 · alias · 개수 · 애널라이저 · 플래그는 `ops/03-enable-es-search.sh` 가 한 번에 한다:

```bash
# 재색인은 관리자 쓰기다 — 게이트웨이를 거쳐 관리자 토큰으로 부른다(catalog 직접 호출은 401:
# AdminWriteInterceptor + internal-auth.identity-mode=enforce — 신원 헤더는 게이트웨이가 mTLS 로 보낸 것만 신뢰)
GATEWAY=https://shop.lab ADMIN_TOKEN=<관리자 액세스 토큰> ES_URL=http://localhost:9200 INSECURE=1 \
  ./ops/03-enable-es-search.sh
```

재색인은 202 로 바로 돌아오고 배치가 뒤에서 돈다 — 스크립트는 alias `product-alias` 가 생길 때까지 기다리며 **색인 중인 문서 수**를 함께 적는다(기본 30분). 실측: 실습 데모 **상품 1,000만** 이 초당 약 5.7만 건 · 약 3분(`data-node` 6 vCPU · ES 힙 3g). ⚠️ 2026-11-23 실습(같은 1,000만 · 재색인 배치 전체 — catalog 파드 하나가 약 1 코어)에서는 **1시간 51분 35초**였다: product 34분 13초(상품 초당 약 4,900) · SKU 1,700만 28분 45초(초당 약 9,900) · size 2,951만 48분 35초(초당 약 10,100) · 나머지(category · color · details · QA · subcategory · 별칭 교체) 합 2초. 별칭은 마지막 단계에서야 붙으므로 그때까지 이름 검색은 PostgreSQL LIKE 로 폴백한다 — 측정 전에 기본 30분 기다림으로는 모자라니 `TIMEOUT` 을 7200 이상으로 하거나, 시간이 다 된 뒤 `_cat/aliases` 를 따로 본다. 시간이 다 돼도 문서 수가 늘고 있었으면 "느린 것" 이라고 말해 준다 — 그때 `sync` 를 다시 부르면 409(중복 실행 방지)이니 `TIMEOUT` 을 늘려 기다린다. 관리자 계정이 없으면 `./ops/30-admin-account.sh promote <이메일>`.

전환은 **두 단계**이고 둘 다 저장소를 고쳐 `ops/28 --config-only` 로 올린다(`kubectl patch` 금지):

1. `SEARCH_ENGINE=es`(`k8s/10-configmap.yaml` 또는 그 환경 오버레이) + `--restart catalog-service` — "ES 빈 · 색인이 있다" 만 정한다.
2. 기능 플래그 `catalog.search-engine` 의 비율(`k8s/15-feature-flags.yaml`) — 실제로 검색을 ES 로 보내는 것은 이쪽이다. 재기동 없이 1분 안팎.

되돌리기는 2 의 비율을 0 으로(가장 빠르다) → 그다음 1 을 `postgres` 로.

**지금 정해 둔 값(2026-11-20)**: 실습 · 데모는 `SEARCH_ENGINE=es` · 비율 **es 100%**(베이스 `k8s/15-feature-flags.yaml` 은 운영 기준 **0%**). 운영(OCI)은 베이스의 `postgres` 그대로이고 **비율이 바뀌어도 영향이 없다** — `SearchEngineSelector` 는 ES 빈이 없으면(`esDeployed=false`) 플래그를 **평가하지도 않는다**(추적에 'es' 로 거짓 기록이 남지 않게). 적용은 `./ops/28-deploy.sh demo --config-only --restart catalog-service`(①) · `--config-only`(②, 재기동 없음). 처음에는 10% 로 두었다가 아래 실측으로 100% 로 올렸다 — 하루 보고 `catalog_search_*` · 응답 지연 · ES 쪽 오류를 비교한 뒤 올린다.

#### 실측 — 왜 데모는 100% 인가 (2026-11-20, 상품 1,000만)

같은 검색어로 ES 에 직접 묻고, 같은 검색어를 API 로 불러 **캐시 키로 엔진을 사후에 확인했다**(`search_cache::SimpleKey [SearchParamsRequest(...), POSTGRES]`).

| 검색어 | ES 직접 | API(PostgreSQL 경로) |
|---|---|---|
| Parmesan | 5ms · 1만 건 이상 | 200 · **3.97초** |
| Sandals | 2ms · 1만 건 이상 | 200 · **3.57초** |
| Kettle | 2ms · 1만 건 이상 | 200 · **1.60초** |
| Blower | 3ms · 1만 건 이상 | 200 · **1.61초** |
| Patio | 2ms · 1만 건 이상 | 200 · **1.61초** |
| 가방 · nike | 1~11ms · **0건**(데모 이름은 영어) | **503** · 5.0~5.4초(질의 상한) |

**100% 로 올린 뒤(같은 날, 데모 `26e0ea1533ce`)** — 같은 다섯 단어가 ES 로 갔다(캐시 키에 `ES` 가 생긴 것으로 확인):

| 검색어 | PostgreSQL 경로(전) | **ES 경로(후)** | ES 질의 자체 |
|---|---|---|---|
| Parmesan | 3.97초 | **2.81초** | 5ms |
| Sandals | 3.57초 | **2.67초** | 2ms |
| Kettle | 1.60초 | **0.82초** | 2ms |
| Blower | 1.61초 | **0.76초** | 3ms |
| Patio | 1.61초 | **0.70초** | 2ms |
| 가방 · nike(0건) | **503** · 5.0 ~ 5.4초 | **200** · 0.052 · 0.063초 | 1 ~ 11ms |

**끝까지는 약 2배**다 — ES 질의는 2 ~ 5ms 인데 응답이 0.70 ~ 2.81초인 것은 나머지가 **ES 밖**에 있다는 뜻이다: 관련도로 고른 id 로 상세를 읽는 `productId IN (esIds)`(docs/07 R-C1 의 "ES 로 관련도, Postgres 로 하이드레이션") + 첫 쪽의 필터 메뉴. 다음 개선은 거기다(페이지 크기만큼만 읽기 · 메뉴를 ES 집계로). 지금의 이득은 속도보다 **5초 상한 503 이 사라진 것**이고, ES 에 0건인 검색어도 빈 결과를 빠르게 돌려준다(PostgreSQL 은 없는 것을 찾느라 끝까지 훑었다).

열다섯 검색어 중 ES 로 라우팅된 것은 하나였다(비율 10% 와 맞는다) — 그래서 **비율을 올리지 않으면 ES 가 있어도 쓰이지 않는다.**

⚠️ **엔진은 추측하지 말고 캐시 키로 확인한다.** 응답이 빨랐다고 ES 인 것이 아니다(2026-11-20: `운동화` 가 0.68초였지만 캐시 키는 `POSTGRES` 였다 — 그 조건이 PG 에서 싸게 끝난 것이다).
```bash
kubectl -n redis exec deploy/redis -- sh -c '
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "search_cache*"' \
  | grep -oE 'search=[^,]*|, (ES|POSTGRES)\]' | paste - -
```
⚠️ `grep -oE '(ES|POSTGRES)\]'` 처럼 **`]` 를 바로 뒤에 두면 `POSTGRES` 의 끝 두 글자(`ES]`)에 걸려 전부 ES 로 보인다.** 앞에 `, ` 를 붙이거나 `grep -oE 'ES|POSTGRES'`(가장 긴 쪽이 이긴다)로 센다.
⚠️ 한국어 검색어를 `curl` 로 보낼 때는 `-G --data-urlencode` — 인코딩하지 않으면 **400** 이다(브라우저는 늘 인코딩한다).



### 남은 것 (후속)
- 관련도 순서가 Postgres 하이드레이션에서 보존되지 않음(현재 정렬은 기존 로직) — 정렬 보존 필요 시 esIds 순서 기반 재정렬 추가.
- category/subcategory 등 다른 인덱스들의 명시적 매핑, `search_after` 페이지네이션, DB↔ES 증분 동기(outbox 기반, docs/07 R-C3).

> 🕰️ 지금(2026-11-14): 증분 동기는 상품 저장마다의 단건 색인(`ProductIndexService` — 실패해도 저장은 성공, 다음 sync 로 복구)까지가 기본 브랜치에 있다. 관련도 순서 보존 · `search_after` · 아웃박스 기반 색인 큐(R-C3)는 아직 기본 브랜치에 없다 — 검색 전체를 ES 로 옮기는 로드맵(R-C1 ~ R-C4)은 ES 로드맵 브랜치 에만 있다(그 브랜치의 docs/07 §5 — 이 브랜치의 [docs/07](07-catalog-search-improvements.ko.md) 은 R-C1 ~ R-C6 설계안까지, 합칠지는 판단 대기).
