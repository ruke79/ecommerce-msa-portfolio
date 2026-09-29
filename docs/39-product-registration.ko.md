# 39. 상품 등록 절차 — 첫 화면에 상품이 나오기까지

[日本語](39-product-registration.md) | **한국어**

> 배경: 프론트엔드 홈 화면에 상품이 표시되지 않았다. 상품을 프로덕션 수준으로 등록하는
> 절차도 필요했다.
>
> 이 문서는 **절차서이자 수정 기록이다.** §1 이 "왜 안 보였나", §2~§4 가 절차,
> §5 가 이번에 고친 것이다.

---

## 0. 한 줄 요약

상품이 안 보이는 원인은 세 층에 흩어져 있었고, 셋 다 **조용히** 실패했다 —
이미지 업로드가 실패해도 임포트 잡은 성공으로 끝났고, 이미지가 없는 상품이 하나라도
있으면 홈이 통째로 백지가 됐고, 그 백지와 "상품이 없음"이 화면에서 구분되지 않았다.

---

## 1. 왜 첫 화면에 상품이 안 나왔나

### 1.1 이미지 업로드 실패가 삼켜졌다

`ProductSkuItemProcessor`·`ProductColorItemProcessor` 는 CSV 가 가리키는 이미지를
S3 로 올린다. 그런데 실패하면 이렇게 처리했다.

```java
} catch (Exception e) {
    System.err.println("Failed to upload image from path: " + path);
    e.printStackTrace();
    return null;          // ← 그리고 filter(Objects::nonNull)
}
```

결과: **자격 증명이 폐기된 상태에서도 임포트 잡은 COMPLETED 로 끝난다.** 대신
`images` 가 빈 SKU 가 DB 에 남는다. 로그를 보지 않는 한 아무도 모른다.

이 저장소는 실제로 AWS 키를 폐기한 이력이 있다(docs/30). 그 뒤에 임포트를 돌렸다면
정확히 이 상태가 된다.

### 1.2 이미지가 없는 상품 하나가 홈 전체를 백지로 만들었다

프론트 곳곳이 `product.skus[0].images[0]` 을 그대로 읽었다. `skus` 가 비었거나
`images` 가 비면 그 자리에서 TypeError 가 나고, React 는 **렌더 트리를 통째로 버린다.**
한 상품이 아니라 첫 화면 전체가 사라진다.

`ProductCard` 도 같은 모양이었다 — `product.skus[active].sizes.map(...)` 과
`style.color` 를 전제했다. 색상 하나에 사이즈가 없으면 같은 증상이다.

### 1.3 "장애"와 "데이터 없음"이 구분되지 않았다

홈 로더는 실패를 `console.error` 로 삼키고 null 을 돌려줬고, 화면은 `products &&` 로
전체를 감췄다. 그래서 API 가 죽었을 때와 상품이 0개일 때의 화면이 **완전히 같았다.**

### 1.4 (해당하는 경우) 섹션이 가리키는 카테고리가 없다

홈 구성은 이제 `content.home_section` 테이블이 정한다(docs 없이 V10 마이그레이션 참조).
섹션이 가리키는 카테고리에 상품이 없으면 그 섹션은 조용히 빠진다 — 제목만 있고 아래가
빈 줄은 고장처럼 보이기 때문이다. 전부 그렇게 빠지면 첫 화면이 비어 보인다.

어드민 `/admin/home` 목록의 **노출**·**카테고리** 두 칸이 그 답을 준다.

---

## 2. 지금 상태 확인 — 순서대로

```bash
# ① 상품이 DB 에 있는가
kubectl -n postgres exec deploy/postgres -- \
  psql -U product -d productdb -c "select count(*) from product.product;"

# ② 이미지가 붙어 있는가 (0 이 아니어야 한다)
kubectl -n postgres exec deploy/postgres -- psql -U product -d productdb -c "
  select count(*) filter (where images is null or cardinality(images) = 0) as 이미지없음,
         count(*) as 전체
  from product.product_sku;"

# ③ 홈 섹션이 무엇을 가리키는가
kubectl -n postgres exec deploy/postgres -- psql -U product -d productdb -c "
  select section_id, title, category_name, layout, published, starts_at, ends_at
  from content.home_section order by sort_order;"

# ④ 서버가 실제로 무엇을 내려보내는가
curl -s https://www.ysyproject.com/catalog-service/api/content/home | head -c 400
```

읽는 법:

| 결과 | 원인 | 다음 |
|---|---|---|
| ① 이 0 | 상품이 아예 없다 | §3 또는 §4 |
| ② 의 "이미지없음" 이 0 이 아니다 | 업로드가 실패한 채 등록됐다 | §3.1 로 키부터 고치고 재등록 |
| ③ 에 published=false 나 기간 밖 | 섹션이 꺼져 있다 | `/admin/home` 에서 켠다 |
| ④ 의 `sections` 가 비었다 | 위 셋 중 하나 | 위로 |
| ④ 는 정상인데 화면이 비었다 | 프론트 캐시/빌드 | 프론트 이미지 태그 확인 |

---

## 3. 절차 A — 상품 하나씩 등록 (상시 운영 경로)

### 3.1 먼저 이미지 저장소를 확인한다

`/admin/product` 를 열면 상단에 **이미지 저장소 배너**가 뜬다. 정상이면 아무것도
그리지 않고, 쓸 수 없으면 버킷 이름과 SDK 오류 메시지를 그대로 보여 준다.

이 점검은 읽기가 아니라 **작은 객체를 실제로 쓰고 지운다**(`ImageService.probe`).
읽기만 확인하면 의미가 없다 — 등록이 필요로 하는 것은 put 권한이고, 키를 폐기하거나
버킷 정책이 바뀌면 정확히 그 권한만 사라진다.

배너가 떴다면 여기서 멈춘다. `AWS_ACCESS_KEY`/`AWS_SECRET_KEY` 를 교체하고
catalog-service 를 다시 띄운 뒤에 진행한다.

### 3.2 등록

1. `/admin/category` 에서 카테고리·서브카테고리를 먼저 만든다.
   (상품 등록 폼이 그 목록에서 고르게 되어 있다.)
2. `/admin/product` 에서 상품을 등록한다. 이미지 3장과 색상 견본 이미지가 **필수**다 —
   서버가 둘 다 없으면 500 을 낸다(`ProductController.addProduct` 주석 참조).
3. 같은 상품의 다른 색상은 등록 직후 뜨는 배너에서 "변형 추가"로 이어 간다.

### 3.3 첫 화면에 올린다

`/admin/home` 에서 섹션을 만든다.

| 칸 | 뜻 |
|---|---|
| 기준 | 카테고리 전체인가, 특정 서브카테고리인가 |
| 배치 | GRID = 캐러셀 위로 겹치는 상단 격자, SWIPER = 그 아래 가로 스크롤 |
| 상품 수 | 1~50. 첫 화면이라 상한을 둔다 — 실수로 큰 값을 넣으면 첫 로딩이 통째로 늦는다 |
| 정렬 순서 | 작을수록 위 |
| 시작/종료 | 비우면 제한 없음. 기획전 기간 |
| 공개 | 꺼 두면 작업 중 섹션이 노출되지 않는다 |

없는 카테고리를 가리키면 **저장 시점에 400 으로 막는다.** 저장을 허용하면 증상이
"홈에 그 섹션만 안 나온다"로 나타나고, 어디가 틀렸는지 화면에서는 알 수 없다.

---

## 4. 절차 B — CSV 일괄 등록 (초기 데이터 투입)

`/admin/import`. 번들된 CSV 9개를 순서대로 넣고 이미지 60장을 함께 올린다.

> **멱등하지 않다.** 두 번 돌리면 상품이 두 벌 생긴다. 빈 DB 에 한 번 넣는 용도다.

동작:

1. 시작 전에 저장소 쓰기를 확인한다. 안 되면 **시작하지 않고** 503 을 낸다 —
   업로드 실패가 이제 스텝을 실패시키므로, 그냥 시작하면 카테고리·상품까지 들어간 뒤
   SKU 스텝에서 죽어 절반만 채워진 DB 가 남는다.
2. 즉시 202 로 돌아온다. 잡은 뒤에서 돈다.
3. 진행 중에는 두 번째 시작을 409 로 막는다.
4. 화면이 3초마다 상태를 묻고, 스텝별 읽음/기록/건너뜀과 실패 원인을 보여 준다.

명령줄에서:

```bash
# 시작 (관리자 쿠키 필요)
curl -X POST https://www.ysyproject.com/catalog-service/api/content/admin/product-import \
     -b "ACCESS=<관리자 토큰>"

# 진행 상황
curl -s https://www.ysyproject.com/catalog-service/api/content/admin/product-import \
     -b "ACCESS=<관리자 토큰>"
```

예전 경로 `POST /catalog-service/api/products/load` 도 같은 동작을 한다(기존 호출자용).

---

## 5. 이번에 고친 것

| # | 고친 것 | 왜 |
|---|---|---|
| 1 | 임포트 프로세서가 업로드 실패를 **던진다** | 삼키면 잡이 성공으로 끝나고 이미지 없는 상품이 남는다. "절반만 들어간 데이터"보다 "실패했다고 말하는 잡"이 낫다 |
| 2 | `ImageService.probe()` — 쓰기 점검 | 등록 화면과 임포트가 시작 전에 부른다. 긴 폼을 다 채운 뒤 500 을 보는 것을 막는다 |
| 3 | 임포트를 **비동기**로 | 동기 실행은 이미지 60장을 올리는 동안 응답을 막았고 게이트웨이 타임아웃에 먼저 걸렸다. 504 를 본 관리자가 다시 눌러 상품을 두 벌 만들었다 |
| 4 | 임포트 **중복 실행 차단**(409) | 이 잡은 멱등하지 않다 |
| 5 | 임포트 **진행 화면**(`/admin/import`) | 예전에는 화면이 없어 curl 로 쳐야 했고, 결과를 알 방법이 없었다 |
| 6 | `firstImageOf` / `isRenderable` 로 이미지 접근을 감쌌다 | 이미지 없는 상품 하나가 홈 전체를 백지로 만들던 것을 막는다 |
| 7 | `ProductCard` 의 가격·색상 접근에 널 가드 | 색상 하나에 사이즈가 없어도 같은 증상이었다 |
| 8 | 홈 로더가 **throw 하지 않는다** | 로더 예외는 페이지 전체를 에러 화면으로 바꾼다. 카탈로그가 흔들렸다고 헤더까지 사라질 이유는 없다 |
| 9 | "불러오지 못함"과 "아직 없음"을 **구분해서** 문구로 | 예전에는 둘 다 똑같이 빈 화면이었다 |

### 회귀 테스트

- `ProductImportServiceTest` — 저장소가 죽어 있으면 잡을 띄우지 않는다(그리고 잠금이
  풀린다), 진행 중 두 번째 시작은 거부, 실패 원인이 상태에 남는다.
- `ProductSkuItemProcessorTest` — 업로드 실패가 **전파된다**(예전에는 삼켰다).

둘 다 고친 코드를 되돌려 실패하는 것을 확인했다.

---

## 6. 남은 것

- **AWS 키 교체가 선행 조건이다.** docs/30 의 폐기 항목 중 AWS 만 새 키 발급이 남아 있다.
  그 전에는 §3·§4 어느 쪽도 끝까지 가지 못한다 — 다만 이제는 시작하기 전에 그 사실을
  알려 준다.
- 임포트 잠금은 **한 인스턴스 기준**이다(메모리). 단일 노드라 여기까지로 둔다. 레플리카를
  늘린다면 Postgres 어드바이저리 락으로 올려야 한다.
- 상품 **수정·삭제** 화면은 여전히 목록(`/admin/allproducts`)뿐이다.

---

## 7. 배포 후 확인에서 드러난 것 — 게이트웨이 1초 타임아웃

첫 배포 뒤 `GET /api/content/home` 이 계속 503 "내부 서버 오류"를 냈다. 확인해 보니
**서비스는 멀쩡했다.**

```
# 파드 직접 호출
$ curl -sk https://localhost:8081/api/content/home
{"sections":[]}
200 0.317647s

# 게이트웨이 경유
503 1.358464s
```

게이트웨이 로그에 원인이 있었다.

```
Fallback triggered. Exception: Did not observe any item or terminal signal
within 1000ms in 'circuitBreaker'
```

**두 가지가 겹쳐 있었다.**

### 7.1 TimeLimiter 가 1초였다

`resilience4j.timelimiter.configs.default` 에 `timeout-duration` 이 없었다. 적지 않으면
Resilience4j 기본값 **1초**다. JVM 서비스 호출에 1초는 턱없이 짧다 — Eureka 로
인스턴스를 고르고 TLS 를 맺고 JPA 쿼리까지 도는 시간이다. 단일 노드에서 파드가 여럿
경합하면 0.3초짜리 호출도 쉽게 1초를 넘는다.

`timeout-duration: ${GATEWAY_TIMEOUT:10s}` 로 명시했다. 10초인 이유는 같은 파일의
`slow-call-duration-threshold: 10s` 와 맞추기 위해서다 — 그보다 짧게 자르면 "느린 호출"이
집계되기 전에 잘려 나가 서킷이 느려짐을 영영 학습하지 못한다. 프론트 axios 기본
타임아웃(15초)보다는 짧아야 사용자가 우리 쪽 응답을 받는다.

**B-45** 가 이 값이 사라지는 것을 막는다. 지우면 아무 오류 없이 1초로 돌아가기 때문이다.

### 7.2 그리고 그 사실이 응답에 드러나지 않았다

`FallbackController` 는 `org.springframework.cloud.gateway.support.TimeoutException` 만
보고 있었는데, 실제로 던져지는 것은 `java.util.concurrent.TimeoutException` 이다.
**서로 다른 클래스라 `instanceof` 가 맞지 않았고**, 모든 타임아웃이 기본 분기로 떨어져
503 "내부 서버 오류"가 됐다. 504 "시간이 초과되었습니다"였다면 첫 화면에서 원인이
보였을 것이다.

게이트웨이 설정의 `record-exceptions` 에는 `java.util.concurrent.TimeoutException` 이
이미 적혀 있었다 — 설정은 알고 있었고 이 코드만 몰랐다.

이제 둘 다 본다. `FallbackControllerTest` 가 네 갈래(두 타임아웃 · 서킷 개방 · 그 외)를
고정한다.

### 7.3 남은 운영 문제

배포 당시 catalog-service 파드가 **3개**였고 둘은 재시작을 반복하고 있었다
(`rollout status` 가 "1 out of 2 new replicas" 에서 멈춤). 단일 노드에 JVM 셋이
동시에 기동하면 CPU 가 마르고, 그것이 1초를 넘긴 직접 원인이다.
`k8s-overlays/local/kustomization.yaml` 은 `catalog-service: 1` 로 적고 있으므로,
2로 떠 있다면 그 오버레이가 적용되지 않았거나 HPA 가 되살린 것이다.

### 7.4 그리고 그 아래 — 운영 프로필이 통째로 DEBUG 였다

멈춘 파드의 로그를 읽다 원인 하나가 더 나왔다. 기동 로그가 이렇게 끝난다.

```
Started CatalogServiceApplication in 194.864 seconds (process running for 208.811)
```

**195초.** startupProbe 예산이 300초(5초 × 60)이니 여유가 거의 없다. 노드가 붐비면
그대로 넘긴다 — 실제로 넘겨서 파드가 죽고 다시 뜨고를 반복했다.

로그 본문이 이유를 말해 준다. `SharedEntityManagerCreator - Creating new EntityManager`
같은 DEBUG 줄이 수백 개다. 확인해 보니 **12개 모듈 전부** 이랬다.

```yaml
logging:
  level:
    org.springframework.jdbc: DEBUG
    org.springframework.orm.jpa: DEBUG
    org.hibernate.SQL: DEBUG        # ← 운영에서 모든 SQL 이 한 줄씩
```

비용이 두 군데다. 기동과 처리가 느려지고(단일 노드에서는 그게 곧 롤아웃 실패다),
정작 장애가 났을 때 로그가 DEBUG 로 덮여 원인 줄을 찾기 어렵다. 이번 진단에서
게이트웨이 로그 한 줄을 찾는 데 시간이 걸린 이유이기도 하다.

기본값을 운영값으로 바꾸고, 조사할 때만 올린다:

```
LOG_LEVEL_APP=debug     우리 패키지
LOG_LEVEL_SQL=debug     JDBC·JPA·Hibernate SQL (평소 warn)
LOG_LEVEL_BATCH=debug   임포트 잡
LOG_LEVEL_EUREKA=debug  디스커버리
```

**B-46** 이 DEBUG 가 다시 박히는 것을 막는다.

### 7.5 그리고 진짜 범인 — `kubectl apply -f k8s/`

배포 로그 한복판에 이 오류가 섞여 있었다.

```
error: resource mapping not found for name: "" namespace: "" from
"k8s/kustomization.yaml": no matches for kind "Kustomization"
```

`kubectl apply -f k8s/` 의 서명이다. 그리고 **kubectl 은 그 오류에서 멈추지 않고
나머지를 전부 적용한다.** 같은 출력에 이것들이 따라 나왔다.

```
horizontalpodautoscaler.autoscaling/... unchanged   ← 10개 (70-autoscaling.yaml)
poddisruptionbudget.policy/... configured
cronjob.batch/postgres-backup unchanged             ← 85-backup-cronjobs.yaml
namespace/postgres · deployment.apps/postgres       ← 90-dev-infra.yaml
namespace/kafka   · deployment.apps/kafka
namespace/redis   · namespace/mongodb · namespace/elasticsearch
```

한 줄이 **네 가지를 동시에** 한다:

1. HPA 10개를 되살린다 → `replicas: 1` 이 15초 만에 2로 돌아간다.
2. Postgres·Kafka·Redis·Mongo·Elasticsearch 를 클러스터 안에 띄운다 → 단일 노드가 마른다.
3. 백업 CronJob 과 PVC 를 붙인다.
4. base 매니페스트를 오버레이 없이 적용한다 → 이미지가 `REGISTRY_PLACEHOLDER/...:1.0.0`
   으로 돌아간다. **방금 한 배포가 통째로 무효가 된다.**

§7.3 의 "파드가 3개", §7.1 의 "1초를 넘기는 응답", 저널의 다섯 번 연속 실패,
Deployment 가 `1.0.0` 에 고정돼 있던 것 — 전부 이 한 줄의 결과다.

**왜 그걸 쳤나.** 저장소가 그러라고 적어 뒀기 때문이다. `k8s/README.md` §3 이
개별 적용 목록 끝에 이렇게 권하고 있었다.

```
# 파일명이 순서대로 정렬되므로 아래처럼 디렉터리 전체를 한 번에 적용해도 됩니다.
kubectl apply -f k8s/
```

kustomization.yaml 과 오버레이가 도입되기 전에는 맞는 말이었다. 그 뒤로 틀린 말이
됐는데 문서만 그대로였다. `ops/23` 의 3번 단계도 같은 모양이었다 — "적용하지 마세요"
바로 다음 줄에 그 명령이 있었다.

고친 것: `k8s/README` §3 에 금지 상자를 놓고 디렉터리 적용 권고를 삭제, `ops/23` 에서
명령 줄을 제거, 그리고 `ops/28` 이 비 prod 환경에 HPA 가 남아 있으면 배포 때 경고한다.
경고는 지우지 않고 **보이게만** 한다 — 배포 스크립트가 남의 리소스를 조용히 삭제하는 것은
그 자체로 놀라운 동작이다.

> **교훈.** 이번 작업에서 시간을 가장 많이 먹은 것은 코드 결함이 아니라 **낡은 안내문**이었다.
> 저장소가 "하지 마세요" 라고 쓴 바로 다음 줄에 그 명령을 적어 두면, 복사되는 것은 명령이다.

### 7.6 정정 — `kubectl apply -f k8s/` 를 친 것은 사람이 아니라 스크립트였다

§7.5 는 그 명령을 운영자가 쳤다고 적었다. **틀렸다.** 운영자가 실행한 것은 네 줄뿐이었고
(`git pull` → `VER=` → `ops/19` → `ops/28`), 그런데도 `ops/19` 의 "완료" 직후에 그 오류와
apply 출력이 나왔다. 결정적 단서는 출력이 **안내문 한가운데에 끼어 있었다**는 것이다:

```
  ⚠️ k8s/ 에 kustomization.yaml 이 생겨서 namespace/ecommerce unchanged
configmap/ecommerce-config configured
...
service/elasticsearch unchanged 는 더 이상 쓸 수 없다.
```

`ops/19-build-images.sh` 의 안내문은 `$TAG` 를 치환하려고 **따옴표 없이** `cat <<EOF` 로
열려 있었다. 따옴표 없는 heredoc 에서 백틱은 명령 치환이다. 그 안의 이 한 줄이

```
  ⚠️ k8s/ 에 kustomization.yaml 이 생겨서 `kubectl apply -f k8s/` 는 더 이상 쓸 수 없다.
```

**"쓸 수 없다"고 말하면서 매 빌드마다 정확히 그 명령을 실행하고 있었다.** stdout 은
안내문 자리에 치환되어 들어갔고(그래서 문장이 두 동강 났다), stderr(`error: resource
mapping not found`)만 먼저 터미널에 찍혔다.

그러니 §7.5 의 네 가지 결과 — HPA 부활, dev-infra 재적용, CronJob, 그리고 base 매니페스트로
**이미지가 1.0.0 으로 되돌아가는 것** — 는 빌드를 할 때마다 일어났다. 배포 직전에.
`ops/28` 이 매번 "현재 배포 버전: 1.0.0" 이라고 읽은 이유이고, 저널의 fail 다섯 번의 뿌리다.

셸 히스토리에 그 명령이 한 줄 있던 것은 우연이었고, 그것을 근거로 사람을 의심한 것은
잘못이었다.

고친 것: 백틱을 `\`` 로 이스케이프했고(ops/22 는 이미 그렇게 하고 있었다), **B-47** 이
`ops/*.sh` 의 따옴표 없는 heredoc 안에서 이스케이프되지 않은 백틱을 잡는다. 되돌려서
실패하는 것을 확인했다.

> 교훈 하나 더. §7.5 의 교훈("낡은 안내문이 가장 비쌌다")은 맞았지만 반만 맞았다.
> 안내문은 읽히기만 하는 것이 아니다 — 셸 안에서는 **실행될 수도 있다.**

### 7.7 스모크 게이트는 **처음부터 통과할 수 없는 상태**였다

모든 롤아웃이 성공한 배포가 스모크에서 실패했다. 출력에 원인이 그대로 있었다.

```
▶ 스모크 테스트  (http://localhost:18000)
  ❌ gateway health 가 UP 이 아니다: Bad Request
This combination of host and port requires TLS.
```

`ops/28` 의 `resolve_gateway` 는 Ingress 에 host 가 없으면(이 저장소의 기본값이다)
게이트웨이로 port-forward 하고 대상을 `http://localhost:18000` 으로 잡았다. 그런데
**게이트웨이는 스스로 TLS 를 종단한다**(`server.ssl`, 포트 8000). 톰캣은 평문 요청에
400 을 돌려주므로 스모크의 모든 항목이 실패했다.

항목 1의 `000` 일곱 건도 뿌리가 같다. `ops/06` 은 `$GATEWAY` 가 localhost 면 "로컬
실행"으로 보고 개별 서비스 포트(8080·8081…)를 직접 찌르는데, 여기서 localhost 인 것은
**포트포워드 때문**이고 그 포트들은 클러스터 밖으로 나오지 않는다. 가짜 실패 7건이
진짜 문제를 덮었다.

즉 스모크 게이트는 **무엇을 배포하든 실패 판정을 내렸다.** 저널에 `ok` 가 한 줄도 없고
fail 만 쌓인 것, 그리고 그때마다 자동 복귀가 `1.0.0` 으로 되돌린 것이 전부 여기서 나온다.
§7.5 의 heredoc 버그가 배포를 **되돌렸다면**, 이쪽은 배포가 **성공으로 기록되는 것을
막았다.** 둘은 별개의 버그이고 둘 다 있어야 이 7시간이 설명된다.

고친 것:

- port-forward 대상을 `https://localhost:18000` 으로, 자가서명이므로 `INSECURE=1`.
- `SKIP_DIRECT=1` 을 명시해 개별 서비스 직접 점검을 끈다 — k8s 프로브가 이미 같은 일을 한다.
- **B-48**: 게이트웨이가 TLS 를 종단하는데 스모크 대상이 `http://` 면 실패시킨다.
  스킴이 어긋나도 오류 메시지는 "게이트웨이가 UP 이 아니다"로 나와서, **증상이 원인을
  가린다.** 되돌려서 실패하는 것을 확인했다.
