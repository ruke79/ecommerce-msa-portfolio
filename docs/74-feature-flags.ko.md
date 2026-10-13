# 74. 기능 플래그 — 1단계: 검색 엔진(search.engine)을 실행 중에 비율로 전환한다

[日本語](74-feature-flags.md) | **한국어**

> **상태(2026-10-06)** — 1단계 구현 완료, 운영 배포 대기. 운영(OCI)에는 Elasticsearch 가 없으므로 배포해도 동작은 그대로다
> (플래그를 평가하지 않는다, §5). 비율을 올리려면 먼저 ES 를 띄워야 한다.
> 계기: *Observability Engineering, 2nd Edition* 의 "배포(deploy)와 출시(release)를 떼어 놓고, 켠 쪽과 끈 쪽을 관측으로 비교하라".

---

## 0. 한눈에

| | 예전 | 지금 |
|---|---|---|
| 무엇이 정하나 | `search.engine` — 기동할 때 한 번 | `search.engine=es` 는 "ES 가 **있는가**", 플래그 `catalog.search-engine` 은 "있다면 **이 검색에 쓰는가**" |
| 바꾸는 법 | ConfigMap 수정 + `ops/28 --config-only --restart catalog-service` | `k8s/15-feature-flags.yaml` 의 비중 수정 → `ops/28 --config-only` (**재시작 없음**, 1분 안팎) |
| 바꾸는 범위 | 전부 한꺼번에 | 검색어의 0 ~ 100% |
| 무엇으로 돌았나 | 알 수 없음 | 서버 스팬의 `feature_flag.result.variant`, 지표 `feature_flag.evaluations`, span_metrics 의 변형별 RED |
| 플래그 정의가 없거나 틀리면 | — | 코드의 기본값 = 플래그 이전의 동작(ES 가 있으면 ES) |

---

## 1. 왜 search.engine 부터인가

[docs/13](13-secrets-dbha-es-search.ko.md) 은 ES 검색을 이렇게 켜라고 적었다: "`search.engine=es` 를 배포 → 검색 품질 · 지연을 비교 → 이상하면 플래그를 되돌린다". 그런데 그때의
"플래그" 는 기동 설정이었다.

- **비교할 대상이 없다.** 바꾸는 순간 모든 검색이 ES 로 간다. "ES 가 LIKE 보다 느린가" 를 같은 시간대의 두 무리로 볼 수 없고, 바꾸기 전 · 후의 시간대를 비교하게 된다(그 사이 트래픽이 다르다).
- **되돌리는 데 재시작이 든다.** ConfigMap 은 해시 접미사가 없어 파드가 다시 뜨지 않는다 — `--restart` 가 필요하다(ops/28 주석). 단일 노드에서 catalog 재기동은 1 ~ 2분이다.
- **위험이 크고 자주 바꾸고 싶은 스위치**다 — 결제 · 보안 스위치와 달리 검색 엔진은 잘못돼도 결과의 질이 떨어질 뿐이고, ES 장애 시 LIKE 폴백도 이미 있다. 첫 플래그로 알맞다.

---

## 2. 구성

```
k8s/15-feature-flags.yaml (ConfigMap feature-flags, flags.json)
        │  디렉터리 마운트 /app/flags  ← subPath 가 아니다(B-76)
        ▼
FileFlagProvider (common)  — 평가할 때 5초가 지났으면 파일을 다시 확인, 틀리면 직전 정의 유지
        │  OpenFeature SDK (격리된 OpenFeatureAPI, 자동 구성)
        ▼
FeatureFlagObservabilityHook — 지금 스팬에 feature_flag.* 태그, feature_flag.evaluations 지표
        │
SearchController ── SearchEngineSelector.select(params) ──▶ SearchService.searchProducts(params, engine)
                     (캐시 밖에서 평가)                        @Cacheable 키 = (params, engine)
```

### 2-1. 왜 OpenFeature + 파일 공급자인가

| 선택지 | 판단 |
|---|---|
| **OpenFeature SDK + 직접 만든 파일 공급자** (채택) | 의존성은 SDK 하나(+slf4j). 서비스 코드는 표준 API(`Client.getStringValue`)만 쓴다 — 나중에 공급자만 flagd 로 바꾸면 된다. 새 파드 · 네트워크 정책 없음 |
| flagd 공급자(`dev.openfeature.contrib.providers:flagd`) | gRPC · protobuf 를 끌고 온다. 이 저장소는 gRPC 1.84 · protobuf 3.25.9 를 맞춰 고정했다(docs/66) — 맞물림 위험에 비해 1단계에서 얻는 것이 적다 |
| Redis + 관리 API | 관리 화면 · 인가 · 감사 기록을 새로 만들어야 한다. 바꾼 기록이 git 에 남지 않는다 |

정의 파일은 **flagd 형식 그대로**다. 받는 것은 `state` · `variants` · `defaultVariant` · `metadata`, targeting 은 `fractional` 하나뿐 —
다른 규칙은 **읽을 때 파일 전체를 거부**한다. 모르는 규칙을 조용히 무시하면 "10% 에만" 이 100% 로 돈다. (비율 배정의 해시는 flagd 와 같다고 보장하지 않는다 — flagd 로 옮기면 누가 어느 쪽인지는 한 번 바뀐다.)

### 2-2. 무엇을 기준으로 나누나 — 검색어

검색 `GET /catalog-service/api/search` 는 공개 라우트라 게이트웨이가 `User-Id` 를 넣지 않는다. 그래서:

- **요청마다 무작위** — 같은 검색의 1쪽은 ES, 2쪽은 LIKE 로 갈려 무한 스크롤 목록이 어긋난다. 탈락.
- **사용자** — 비로그인이 대부분이다. 게이트웨이에 "선택적 인증" 을 더하는 것은 보안 경로를 건드린다. 1단계 범위 밖.
- **정규화한 검색어** (채택) — 소문자 · 앞뒤 공백 · 연속 공백만 맞춘다. 같은 검색어는 언제나 같은 엔진이라 페이지 · 캐시가 어긋나지 않는다.

대가: "사용자의 10%" 가 아니라 **"검색어의 10%"** 다. 인기 검색어 하나가 어느 쪽에 들어가느냐로 실제 트래픽 비율은 10% 와 다를 수 있다 — 실제 비율은 지표로 본다(§4).
검색어는 추적 · 지표 · 로그 어디에도 남기지 않는다(태그 값은 변형 이름뿐).

### 2-3. 캐시 키에 엔진을 넣었다

`searchProducts` 는 `@Cacheable("search_cache")`(60분)다. 엔진을 키에 넣지 않으면 비율을 바꾼 뒤 최대 60분 동안 **반대쪽 엔진의 결과**가 나가고, 스팬의 변형 태그와 실제 결과가 어긋난다.
그래서 엔진을 캐시 **밖**(컨트롤러)에서 정하고 인자로 넘긴다 — 키는 `(params, engine)`. 캐시가 맞아도 평가는 매 요청 일어나므로 서버 스팬에는 늘 변형이 남는다.
(배포 직후 옛 모양의 키 항목은 읽히지 않고 TTL 로 사라진다.)

### 2-4. 기본값의 규칙

| 상황 | 결과 | 이유 |
|---|---|---|
| ES 가 없는 배포(`SEARCH_ENGINE=postgres`, OCI) | POSTGRES, **평가하지 않음** | 갈 곳이 없다. 평가해서 "es" 로 기록되면 추적이 거짓말을 한다 |
| 검색어가 없음 | POSTGRES, 평가하지 않음 | 엔진이 결과에 영향이 없다 — 캐시 키도 하나로 모인다 |
| 정의 파일이 없음 · 플래그가 없음 | ES (코드의 기본값) | 플래그 이전의 동작 |
| 정의 파일이 틀림 | 직전 정의 | 오타 하나로 모든 플래그가 기본값으로 떨어지면 그것이 장애다 |
| `state: DISABLED` | 코드의 기본값(ES), 이유 DISABLED | 플래그를 끈다 = 플래그가 없던 때로 |

---

## 3. 비율 올리는 법 (ES 가 있는 환경)

1. ES 를 띄우고 `SEARCH_ENGINE=es` 로 배포한다 → `POST /api/products/sync` 로 전체 재색인(별칭 `product-alias`). 이때 비중은 `["es", 0]` 이라 검색은 아직 전부 LIKE 다. 색인만 채워진다.
2. `k8s/15-feature-flags.yaml` 에서 `["es", 5], ["postgres", 95]` 로 고쳐 커밋 → `./ops/28-deploy.sh <환경> --config-only`.
3. 1분 안팎 뒤 `feature_flag_evaluations_total{key="catalog.search-engine"}` 의 변형별 증가율로 실제 비율을 본다.
4. 변형별 지연 · 오류를 비교한다(§4). 괜찮으면 5 → 25 → 50 → 100. **앞쪽 변형(es)의 비중을 늘리는 한, 이미 es 였던 검색어는 계속 es 다**(테스트로 고정).
5. 이상하면 `["es", 0]` 으로 되돌리고 `--config-only`. 재시작 없음.
6. 100% 로 안정되면 **플래그를 지운다** — 정의 파일의 항목, `SearchEngineSelector` 를 걷어내고 기본 경로를 ES 로. 만료일(`metadata.expires`)이 지나면 가드 B-76 이 빌드를 빨갛게 만든다.

🔴 `kubectl patch` · `kubectl edit` 로 고치지 않는다. 다음 `--config-only` 가 git 의 값으로 되돌린다.

---

## 4. 어떻게 보나

**Jaeger** — 서비스 `catalog-service`, 태그 `feature_flag.result.variant=es` (또는 `postgres`). 스팬에는 `feature_flag.key` · `feature_flag.result.reason`(SPLIT · DEFAULT · ERROR …) · `feature_flag.provider.name`, 실패면 `feature_flag.error.code` 가 붙는다.
한 요청에서 플래그를 둘 이상 평가하면 시맨틱 규약 이름은 마지막 것이 이기므로 `feature_flag.variant.<키>` 도 함께 남긴다.

**Prometheus** — Collector 의 span_metrics 에 `feature_flag.result.variant` 차원을 더했다(값은 변형 이름뿐 → 시계열 상한 있음). 꼬리 샘플링 **전**에 계산하므로 표본이 아니라 전량이다.

```promql
# 변형별 p95 지연 — 검색 서버 스팬 (span_name 은 Jaeger 에서 확인한다)
histogram_quantile(0.95, sum by (le, feature_flag_result_variant) (
  rate(traces_span_metrics_duration_milliseconds_bucket{service_name="catalog-service", span_name=~".*search.*"}[5m])))

# 변형별 오류율
sum by (feature_flag_result_variant) (rate(traces_span_metrics_calls_total{service_name="catalog-service", span_name=~".*search.*", status_code="STATUS_CODE_ERROR"}[5m]))
  / sum by (feature_flag_result_variant) (rate(traces_span_metrics_calls_total{service_name="catalog-service", span_name=~".*search.*"}[5m]))

# 실제로 몇 % 가 어느 쪽으로 갔나 — 서비스 자체 지표(actuator/prometheus)
sum by (variant, reason) (rate(feature_flag_evaluations_total{key="catalog.search-engine"}[5m]))
```

ES 경로의 실패는 LIKE 로 폴백한다(오류가 되지 않는다). 그래서 "es 쪽 오류율" 만 보면 ES 장애가 안 보인다 — 폴백 경고 로그(`ES 검색 실패 — Postgres LIKE 경로로 폴백`)를 함께 본다.

---

## 5. 운영(OCI)에서는

`k8s-overlays/oci` 는 ES 를 띄우지 않고 `SEARCH_ENGINE=postgres` 다(2 OCPU · 단일 노드). 그래서 이번 배포 뒤에도:

- `SearchEngineSelector` 는 ES 빈이 없음을 보고 **평가하지 않는다** → 검색은 예전처럼 LIKE, 스팬에 플래그 태그 없음.
- ConfigMap `feature-flags` 는 생기고 catalog 에 마운트된다. 장치는 들어가 있고, ES 를 띄우는 날 §3 만 하면 된다.

즉 이번 변경의 운영 효과는 "장치 설치" 다. 비율 전환을 실제로 해 보려면 ES 를 띄울 자원(힙 1GB 이상)이 먼저다 — 노드를 하나 더 두는 것이 후보다.

---

## 6. 플래그로 만들지 않는 것

- **보안 · 결제 스위치** — `internal-auth.mode`, `auth.breached-password.enabled`, 결제 공급자 켜기 등. 실행 중에 꺼지면 안 되고, 바꾸는 사람과 시점이 기록돼야 한다. 설정(ConfigMap · 재시작)으로 둔다.
- **스키마 변경** — 플래그로 감출 수 없다. expand → contract 두 릴리스([docs/55](55-zero-downtime-migrations.ko.md), [docs/73](73-order-address-expand-contract.ko.md))는 그대로다.
- **REACT_APP_*** — 빌드 시에만 들어간다(B-51). 프론트 플래그는 다음 단계다(§8).

---

## 7. 테스트 · 가드

| 테스트 | 무엇을 막나 |
|---|---|
| `FeatureFlagTest` (common, 11) | 비율 배정이 대상마다 고정 · 비중대로 · 비중을 올려도 앞의 대상 유지 / 모르는 targeting 거부 / 파일 다시 읽기 · 틀린 파일이면 직전 정의 / 스팬 태그 · 지표, **대상 키는 남지 않음** / 자동 구성 |
| `SearchEngineSelectorTest` (catalog, 6) | 정규화한 검색어로 나뉨 / 0% · 100% / ES 없는 배포 · 검색어 없음은 평가 안 함 / 파일 없으면 ES / **캐시 키에 엔진** |
| `SearchServiceUnitTest` +1 | ES 빈이 있어도 엔진이 POSTGRES 면 ES 를 부르지 않음 |
| **B-76** (`ConfigurationGuardrailTest`) | 정의 파일이 서비스 형식대로 읽힘 · owner · 만료일(지나면 빨강) · 코드의 `…FLAG` 키 ↔ 정의 파일 양방향 · catalog 가 디렉터리 마운트(subPath 아님) · `FEATURE_FLAGS_FILE` 이 그 안 · 베이스 kustomization 에 포함 |

변이 확인(되돌리면 실패하는지) 12개: 엔진 무시 · 캐시 키에서 엔진 제거 · ES 없는 배포에서 평가 · 모르는 targeting 허용 · 스팬 태그 제거 · 틀린 파일에서 정의 비우기 · 다시 읽기 안 함 · subPath · 만료 · kustomization 누락 · 키 오타 · targeting 이름 오타 — 모두 잡혔다.

---

## 8. 다음 단계 (제안)

1. **ES 를 띄운 환경에서 실제 전환** — §3. 결과(지연 · 0건 비율 · 클릭)를 이 문서에 적는다.
2. **두 번째 플래그** — 후보: `order.stock-hold.enabled`(재고 홀드, 사용자 단위 — 주문은 로그인 경로라 `User-Id` 로 나눌 수 있다), `paypal.verification.mode`.
3. **flagd 로 옮기기** — 서비스가 여럿이 되면. 공급자만 바꾸고 정의 파일은 그대로(비율 배정은 한 번 바뀐다).
4. **프론트 플래그** — OFREP(OpenFeature Remote Evaluation Protocol)를 게이트웨이 뒤에 두는 방식. REACT_APP_* 빌드 변수로는 하지 않는다.

---

## 9. 배포

- **순서: [docs/72](72-model-refactor-deploy-runbook.ko.md)(버전 `b7c5053a45e8`)를 먼저 배포하고 안정된 뒤에 이것을 낸다.** 한 배포에 섞으면 V18(ROLLBACK-UNSAFE)로 자동 복귀가 꺼진 배포에 변경이 하나 더 얹힌다 — 문제가 생겼을 때 어느 쪽인지 가르기 어렵다. 버전은 이 문서가 들어간 커밋 이후의 파이프라인 초록 SHA.
- 이미지: **전 서비스**(common 이 바뀌었다 — 자동 구성 하나가 더 붙는다. catalog 외에는 `Client` 빈만 생기고 쓰지 않는다) — 보통의 `ops/28-deploy.sh <환경> <버전>`.
- 매니페스트: ConfigMap `feature-flags`(새 파일), catalog Deployment 에 볼륨 · 환경 변수. 파괴적 변경 없음 — 자동 복귀 가능.
- Collector: `observability/otel-collector.yml` 의 차원 추가는 Collector 재시작 때 반영된다.
- 배포 후 확인: `kubectl -n ecommerce exec deploy/catalog-service -- cat /app/flags/flags.json`, catalog 로그의 `기능 플래그 파일 /app/flags/flags.json — 플래그 1개`.
