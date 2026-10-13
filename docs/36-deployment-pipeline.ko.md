# 36. 배포 파이프라인 — 테스트한 것과 배포하는 것을 같은 물건으로

[日本語](36-deployment-pipeline.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** B-54 가드(ROLLBACK-UNSAFE)와 `deploy-oci.yml` 이 들어와 §5.2 와 "배포 스테이지가 없다" 는 해소됐습니다. 2026-10-05 부터 배포는 수동 실행으로만 시작됩니다(실행 = 승인, §6 · B-75).

> 근거 문헌: Jez Humble · David Farley, *Continuous Delivery: Reliable Software Releases
> through Build, Test, and Deployment Automation* (Addison-Wesley, 2010).
> 인용은 원문 그대로 옮기고 출처 장을 밝혔다.

이 문서는 **무엇이 어떻게 깨져 있었고, 무엇을 고쳤고, 무엇을 아직 못 했는지**를 적는다.
"CI/CD 를 도입했다" 같은 문장은 쓰지 않는다 — 이 저장소에는 이미 CI 가 있었고,
문제는 CI 가 없다는 것이 아니라 **CI 가 검증한 물건이 배포되는 물건이 아니라는 것**이었다.

---

## 1. 무엇이 깨져 있었는가

### 1.1 바이너리를 두 번 만들고 있었다

책이 5장에서 가장 강하게 못박는 규칙:

> "you should only build your binaries once, during the commit stage of the build"

개편 전 흐름은 정확히 그 반대였다.

```
GitHub Actions             개발자 노트북
────────────              ──────────────
./gradlew build     →     (몇 시간 뒤)
  jar 생성                ops/19-build-images.sh
  테스트 통과 ✅            → ./gradlew bootJar   ← **jar 를 다시 만든다**
  jar 를 버림               → docker build
                          → ghcr.io/.../user-service:1.0.0
                                    ↓
                              이것이 배포된다
```

CI 가 통과시킨 jar 는 러너와 함께 사라졌다. 실제로 운영에 올라간 jar 는 **아무도
테스트하지 않은 두 번째 빌드 산출물**이었다. 같은 소스라도 두 빌드가 같은 바이트를
낸다는 보장이 없다 — 베이스 이미지가 그 사이 갱신되고, 의존성 해석 시점이 다르고,
빌드 환경이 다르다. 그래서 "인수 테스트 통과"가 배포본에 대해 보증하는 것이 없었다.

### 1.2 태그가 사람이 고른 숫자였다

`TAG="${TAG:-1.0.0}"`. 여기서 두 가지가 동시에 깨진다.

**추적 불가.** 클러스터에서 도는 `:1.0.0` 이 어느 커밋인지 알 방법이 없었다. git 태그는
0개였고, `build.gradle` 의 버전은 모든 서비스가 `0.0.1-SNAPSHOT` 으로 고정이었다.
"이 버그가 지금 배포본에 들어 있나?"에 답할 수 없다는 뜻이다.

**태그 재사용.** 고쳐서 같은 태그로 다시 push 하면 노드는 **캐시된 옛 이미지를 계속 쓴다.**
매니페스트에 `imagePullPolicy` 가 없고 태그가 `latest` 가 아니라 기본값이 `IfNotPresent`
이기 때문이다. kubelet 은 이렇게만 찍는다:

```
Container image "...:1.0.0" already present on machine
```

이번 작업의 실배포에서 정확히 이것을 겪었다 — "이미지를 고쳐 push 했는데 왜 그대로지".

### 1.3 배포 절차가 사람의 기억이었다

배포는 이런 안내문이었다:

```
kustomize edit set image REGISTRY_PLACEHOLDER/... = ghcr.io/...:1.2.3
kubectl apply -k k8s-overlays/prod
kubectl apply -f k8s/70-autoscaling.yaml
⚠️ user-service 를 gateway-server 보다 먼저 올릴 것
```

문제가 여럿이다.

- `kustomize edit` 는 **커밋된 파일을 고친다.** 배포할 때마다 작업 트리가 더러워지고,
  "지금 배포된 게 뭔지"가 커밋되지 않은 로컬 변경 안에 숨는다.
- 순서 주의사항이 주석이다. 주석은 새벽 3시에 지켜지지 않는다.
- 배포 후 검증이 없다. 파드가 Running 인데 사이트가 522 를 뱉는 상태를 실제로 겪었다
  (docs/33 §2).
- **되돌리는 방법이 아예 적혀 있지 않았다.**

### 1.4 되돌리기에 절차가 없었다

책 10장:

> "It is essential to be able to roll back a deployment in case it goes wrong.
> Debugging problems in a running production environment is almost certain to result
> in late nights, mistakes with unfortunate consequences, and angry users."

그리고:

> "On no account should you have a different process for backing out than you do
> for deploying."

별도의 롤백 절차는 정의상 거의 실행되지 않는다. 그래서 정작 필요한 날 — 장애 한복판,
새벽 — 처음으로 실행되고, 그날 처음 버그가 드러난다.

---

## 2. 파이프라인의 모양

`.github/workflows/build.yml`

```
       ┌─ 통합 (Testcontainers) ─┐
커밋 ──┤                          ├── 릴리스 후보 승격 ──▶ ops/28-deploy.sh
       └─ 인수 (사가 E2E) ────────┘        (기록)              (사람이 실행)
```

| 스테이지 | 하는 일 | 실패하면 |
| --- | --- | --- |
| **커밋** | 컴파일 · 단위 테스트(자바+프론트) · **바이너리 1회 생성** · 이미지를 `:<커밋SHA>` 로 GHCR 발행 | 아무것도 발행되지 않는다. 그 커밋은 배포 후보가 아니다 |
| **통합** | 실제 PostgreSQL/Redis/Kafka/Mongo/ES 를 띄우고 8개 서비스 기동·사가 재전달 멱등성 검증 | 후보 승격이 막힌다 |
| **인수** | 주문→결제→재고 사가 전 구간을 **발행된 그 이미지로** 실행 | 후보 승격이 막힌다 |
| **승격** | 리비전·이미지 목록·통과한 스테이지를 매니페스트로 남기고 배포 명령을 요약에 출력 | — |

책의 기준(5장)은 커밋 스테이지가 **이상적으로 5분, 넘어도 10분**이다. 여기서는
자바 빌드 + 프론트엔드 테스트 + 13개 이미지 멀티아키텍처 빌드가 들어가 그보다 길다.
줄일 여지는 §5 에 적었다.

### 왜 배포 스테이지가 파이프라인에 없는가

배포 대상(OCI Ampere)은 **인바운드가 제한된 관리 경로(SSH) 하나로만** 열려 있다
(docs/31). GitHub 호스티드 러너에서 kube-apiserver 에 닿을 방법이 아예 없다.

이것은 책의 처방과 어긋나지 않는다. 책이 요구하는 것은 "모든 환경에 **같은 절차**로
배포하라"이지 "CI 서버가 눌러라"가 아니다. 바꾸려면 §6 참조.

---

## 3. 고친 것

### 3.1 바이너리는 한 번만 — 그리고 그 사실을 도구가 강제한다

커밋 스테이지가 jar 와 프론트엔드 번들을 만들고, **같은 잡 안에서** 그것을 이미지로
감싸 레지스트리에 올린다. 레지스트리가 책이 말하는 '산출물 저장소'다.

인수 스테이지는 소스에서 다시 빌드하지 않고 **그 이미지를 받아서** 띄운다.

```java
// e2e-tests/.../SagaEndToEndTest.java
private static GenericContainer<?> newContainer(String module) {
    if (!IMAGE_PREFIX.isBlank()) {
        return new GenericContainer<>(
                DockerImageName.parse(IMAGE_PREFIX + module + ":" + IMAGE_TAG));
    }
    return new GenericContainer<>(serviceImage(module));   // 로컬 개발용 폴백
}
```

말로만 그런 것이 아니라 **빌드 그래프에서 사라진다.** `E2E_IMAGE_PREFIX` 가 있으면
`e2e-tests/build.gradle` 이 `bootJar` 의존을 걸지 않는다. 실제로 확인했다:

```
$ ./gradlew :e2e-tests:test --dry-run | grep -c bootJar
3
$ E2E_IMAGE_PREFIX=ghcr.io/x/y/ ./gradlew :e2e-tests:test --dry-run | grep -c bootJar
0
```

### 3.2 태그는 리비전이다

`ops/19-build-images.sh` 의 기본 태그가 **현재 커밋의 SHA 12자**가 됐다. 사람이 고른
숫자가 아니다. 절대 재사용되지 않으므로 §1.2 의 캐시 사고가 원천적으로 사라지고,
이미지에서 소스로 되짚을 수 있다.

작업 트리가 더러우면 경고한다 — 그 SHA 를 체크아웃해도 그 이미지를 재현할 수 없기
때문이다. 리비전으로 되짚을 수 없는 산출물은 릴리스 후보가 아니다.

### 3.3 배포는 스크립트 하나, 환경은 인자

`ops/28-deploy.sh` — 책 10장:

> "It is essential to use the same process to deploy to every environment"

```bash
./ops/28-deploy.sh dev  a1b2c3d4e5f6
./ops/28-deploy.sh prod a1b2c3d4e5f6      # 같은 코드 경로. 오버레이만 다르다
./ops/28-deploy.sh prod --rollback        # 되돌리기도 같은 코드 경로
./ops/28-deploy.sh prod <버전> --dry-run
./ops/28-deploy.sh --report               # 배포 이력 · 사이클 타임
```

하는 일을 순서대로:

**① 오버레이를 렌더링해 이미지 목록을 읽는다.** 서비스 목록을 스크립트에 박지 않는다.
그래야 "오버레이에는 있는데 배포 스크립트는 모르는 서비스"가 생기지 않는다.

이 부분에서 **실제로 버그를 하나 잡았다.** 처음에 `^\s+image:` 로만 찾았더니 12개여야
할 목록이 11개였다 — `frontend` 만 컨테이너 맵의 첫 키가 `image` 라서
`      - image: ...` 형태로 직렬화되어 빠졌다. 그대로 뒀으면 프론트엔드만 옛 버전으로
남아 "배포했는데 화면이 그대로"가 됐을 것이다. 네 오버레이(dev·prod·external·oci)
전부에서 12개가 나오는 것을 실제 kustomize 로 확인했다.

**② 레지스트리에 그 버전이 실제로 있는지 확인한다.** 클러스터를 건드리기 **전에**.
없는 태그로 apply 하면 ImagePullBackOff 로 멈추는데, 그때는 이미 옛 파드가 종료되기
시작한 뒤다 — 즉 장애가 난 다음에야 안다. docker·skopeo·crane 중 있는 것을 쓴다.

**③ 태그를 임시 오버레이에 주입한다.** 커밋된 파일은 건드리지 않는다. `.deploy/<환경>/`
(gitignore) 에 `resources: [../../k8s-overlays/<환경>]` + `images:` 만 있는 얇은
오버레이를 매번 새로 쓴다.

**④ 두 단계로 적용한다.** user-service 를 gateway-server 보다 먼저 올린다 — 게이트웨이가
uid 클레임 없는 토큰을 거부하므로, 게이트웨이가 먼저 새 버전이 되면 아직 옛 user-service
가 발급한 토큰이 전부 401 이 된다(docs/27). 1단계에서 게이트웨이만 **현재 버전에 고정**해
두고 나머지를 올린 뒤, 2단계에서 게이트웨이를 올린다. 실제 kustomize 출력으로 확인:

```
gateway-server:1.0.0            ← 1단계에서 고정
user-service:abc123def456       ← 나머지는 새 버전
catalog-service:abc123def456
...
```

**⑤ 스모크가 게이트다.** `ops/06-smoke-test.sh` 를 돌린다. 배포는 "파드가 Running" 이
아니라 "요청이 정상 응답한다"로 끝난다. 대상 주소는 `GATEWAY` → Ingress 호스트 →
port-forward 순으로 정한다. **대상을 못 찾았다고 게이트를 조용히 건너뛰지 않는다.**

**⑥ 실패하면 직전 버전으로 자동 복귀한다.** — 다음 절.

### 3.4 되돌리기에 별도 경로가 없다

실패 시 코드가 하는 일은 이게 전부다:

```bash
CURRENT_VERSION=""              # 단계 나누지 않고 한 번에
deploy_version "$ROLLBACK_TO"   # ← 평소 쓰는 그 함수, 직전 버전으로
```

책의 처방(10장, "Rolling Back by Redeploying the Previous Good Version"):

> "If you have an automated process for deploying your application, the simplest way
> to get back to a good state is to redeploy the previous good version from scratch."
>
> "It is the same process you have tested (hopefully) hundreds of times before.
> Rollbacks are performed much less frequently, and therefore are more likely to
> contain bugs."

이 경로는 따로 테스트할 필요가 없다. **매 배포가 곧 이 경로의 테스트다.**

복귀는 단계를 나누지 않고 전체를 한 번에 되돌린다. 2단계 순서는 앞으로 갈 때만
성립하는 규칙이라, 뒤로 갈 때 같은 순서를 쓰면 오히려 "옛 user-service + 새 gateway"
조합을 만든다. 반대 순서를 새로 만들면 그것이야말로 별도 절차가 된다. 직전에 잘 돌던
구성 전체를 그대로 다시 배포하면 조합의 수가 늘지 않는다.

되돌릴 대상은 배포 이력(`~/.ecommerce-msa/deploy-journal.tsv`)의 **마지막으로 스모크까지
통과한 버전**이다. 배포하고 나면 직전 버전이 클러스터 어디에도 남지 않기 때문에 파일이
필요하다. `kubectl rollout undo` 를 쓰지 않는 이유: undo 는 파드 스펙만 되돌리므로
오버레이가 그 사이에 바뀌었다면 다음 `apply` 에서 조용히 다시 앞으로 간다.

`NO_ROLLBACK=1` 로 자동 복귀를 끌 수 있다 — 원인을 살아 있는 상태에서 봐야 할 때.

### 3.5 사이클 타임을 잰다

책 1장이 유일한 전역 지표로 꼽는 것:

> the time from deciding a change should be made to having it available to users

저널에 배포 시각과 **커밋 시각**을 함께 적어 두고 뺀다.

```
$ ./ops/28-deploy.sh --report
  배포시각             환경      버전           결과      사이클 타임(커밋→배포)
  2026-08-18T09:12:00Z  prod      a1b2c3d4e5f6   ok        31분
  2026-08-19T02:30:00Z  prod      b2c3d4e5f6a7   fail      25분
  2026-08-19T02:41:00Z  prod      a1b2c3d4e5f6   rollback  18시간 0분
  2026-08-19T11:00:00Z  prod      c3d4e5f6a7b8   ok        2일 2시간
```

측정하지 않는 것은 개선되지 않는다. 그리고 이 숫자가 길 때 병목은 보통 파이프라인이
아니라 **'배포를 누르기까지'** 에 있다 — 그것을 보이게 하는 것이 목적이다.

### 3.6 첫 실행에서 드러난 것 — GHCR 패키지가 저장소에 연결돼 있지 않다

파이프라인을 처음 돌렸을 때 단위 테스트는 전부 통과했고, QEMU·buildx·GHCR 로그인까지
성공했는데 push 에서 죽었다:

```
#17 [auth] ruke79/ecommerce-msa/eureka-server:pull,push token for ghcr.io
#17 DONE 0.0s
#16 ERROR: failed to push ghcr.io/.../eureka-server:09094ec7fc5e:
    denied: permission_denied: read_package
```

**로그인은 성공했고 토큰도 발급됐다.** 그러니 자격증명 문제가 아니다. GHCR 패키지는
만든 주체의 소유이고, 사람이 PAT 로 먼저 push 해 둔 패키지는 저장소에 **연결되어 있지
않다.** 그래서 Actions 의 `GITHUB_TOKEN` 은 `packages: write` 를 갖고도 그 패키지에
쓸 수 없다. 워크플로가 만든 패키지는 자동으로 연결되지만, 이 저장소의 이미지들은
docs/33 의 절차대로 손으로 먼저 올라가 있었다.

이 실패에는 흥미로운 점이 있다. `ops/19` 의 실패 분류기가 이걸 잡아냈는데 — **틀린
분류를 내놨다.** "PAT 는 classic 이어야 하고 write:packages 스코프가 필요합니다"라고
안내했고, 그건 여기서 아무 소용이 없다. 스크립트가 피하려던 바로 그 일(헛수고를
시키는 안내)을 스스로 저지른 것이다. `permission_denied` 를 별도 분기로 떼어
연결 문제라고 말하도록 고쳤다.

**해결 ① (권장, 한 번만)** — 패키지마다 저장소를 연결한다:

```
github.com/users/<OWNER>/packages/container/ecommerce-msa%2F<서비스>/settings
  → Manage Actions access → Add Repository → ecommerce-msa → Role: Write
```

13개(서비스 12 + frontend)에 대해 한 번씩 하면 끝이고, 이후로는 수명이 긴 비밀이
필요 없다.

**해결 ② (임시)** — 저장소 시크릿 `GHCR_PAT`(classic, `write:packages`)를 넣는다.
워크플로가 `secrets.GHCR_PAT || secrets.GITHUB_TOKEN` 으로 되어 있어 있으면 그것을
쓰고 없으면 `GITHUB_TOKEN` 으로 떨어진다. 다만 이 방식은 **폐기·재발급 대상 비밀이
하나 늘어나는 것**이라(docs/30 의 교훈) ①로 옮기는 편이 낫다.

### 3.7 부수적으로 고친 것

`gradlew` 의 실행 권한이 git 인덱스에 없었다(`100644`). 워크플로가 `./gradlew` 를 직접
부르므로 이대로면 커밋 스테이지가 `Permission denied` 로 죽는다. `100755` 로 고쳤다.
(`ops/19` 가 `sh gradlew` 를 먼저 시도하는 폴백을 갖고 있는 것이 이 문제의 흔적이다.)

---

## 4. 검증한 것과 하지 않은 것

거짓말하지 않기 위해 나눠 적는다.

**실제로 확인했다**

| 대상 | 방법 | 결과 |
| --- | --- | --- |
| 이미지 목록 추출 | 실제 `kustomize build` (v5.4.3), 4개 오버레이 | dev·prod·external·oci 모두 12개 |
| 태그 주입 + 게이트웨이 고정 | 생성한 임시 오버레이를 실제로 렌더 | gateway 만 옛 태그, 나머지 새 태그 |
| 인수 스테이지의 "다시 빌드 안 함" | `--dry-run` 태스크 그래프 비교 | bootJar 3개 → 0개 |
| e2e 테스트 변경 | `./gradlew :e2e-tests:compileTestJava` | 통과 |
| 워크플로 YAML | 중복 키를 거부하는 엄격 로더로 파싱 + 잡 그래프 확인 | 5개 잡, 의존 관계 정상 |
| `--report` · 인자 오류 경로 | 가짜 저널로 실행 | 사이클 타임 계산·에러 메시지 정상 |

**실제 실행에서 확인된 것** (run 32284611563 / 32286228445)

| 단계 | 결과 | 콜드 캐시 | 웜 캐시 |
| --- | --- | --- | --- |
| 자바 단위 테스트 · bootJar | ✅ | 6분 02초 | **2분 23초** |
| 프론트엔드 단위 테스트 | ✅ | 1분 21초 | 1분 02초 |
| QEMU · buildx · GHCR 로그인 | ✅ | 13초 | 22초 |
| 릴리스 후보 이미지 발행 | ❌ | 15초 | 21초 — §3.6 |
| **발행 직전까지 누적** | | 7분 36초 | **4분 30초** |

멀티아키텍처 이미지 빌드 자체는 정상 동작했다 — amd64/arm64 매니페스트를 만들고
매니페스트 리스트까지 내보낸 뒤 **push 권한**에서 거부됐다. 즉 빌드 파이프라인이
아니라 레지스트리 접근 설정 문제다.

두 번째 실행에서 실패 메시지가 §3.6 의 고친 분류기로 바뀐 것도 확인했다 — 이제
"패키지가 저장소에 연결돼 있지 않다"와 설정 경로를 안내한다. 그리고 두 번째 실행이
같은 지점에서 죽었다는 사실 자체가 **`GHCR_PAT` 시크릿이 설정돼 있지 않다**는 뜻이다
(있었다면 그것을 썼을 것이다). 즉 §3.6 의 해결 ①이나 ②를 사람이 해야 한다.

**아직 확인하지 못했다**

- **인수 스테이지가 발행된 이미지로 사가를 완주하는지.** 이미지가 아직 레지스트리에
  올라가지 못했으므로 그 뒤 스테이지는 전부 skip 됐다. §3.6 을 해결한 뒤 확인해야 한다.
- **실제 클러스터 배포.** 당시 검증 환경에는 kubectl 도 클러스터도 없었다. `kubectl apply -k`,
  `rollout status`, port-forward 폴백, 자동 복귀는 **코드 경로로만** 검증됐다.
- 첫 배포는 반드시 `--dry-run` 으로 무엇이 바뀌는지 먼저 볼 것.

매니페스트는 실제로 쓰는 도구로 검증해야 한다는 것은 이 저장소에서 이미 비싸게 배웠다
(docs/33 §13 — PyYAML 은 중복 키를 허용하지만 kustomize 는 거부한다). 그래서 위
표의 검증은 전부 실제 도구로 했다.

---

## 5. 남은 것 — 책이 처방하지만 아직 안 한 것

### 5.1 무중단 배포와 카나리 (책 10장)

지금은 롤링 업데이트다. 블루-그린도 카나리도 없다. 롤백에 다운타임이 있다.

책이 지적하는 그대로의 제약이 이 프로젝트에도 있다:

> "It is usually not possible to [use blue-green with databases]"

관리형 PostgreSQL 한 대(Neon 무료 티어)를 공유하므로 DB 를 두 벌로 나눌 수 없다.
카나리는 서비스 수(12개)와 노드 수(1대)를 생각하면 지금 규모에서 과하다.
**규모가 커지기 전에 도입할 이유는 없지만, 필요해지는 시점을 놓치면 늦다.**

### 5.2 DB 마이그레이션과 애플리케이션 배포의 분리 (책 12장)

지금은 Flyway 가 애플리케이션 기동 시 마이그레이션을 돌린다. 즉 배포와 스키마 변경이
한 몸이라 **애플리케이션만 되돌릴 수 없다.**

책의 처방:

> "you must ensure it works with both the new version and the current version"
>
> "most changes should be additive, adding new tables or columns to our database,
> but not changing existing structures, where possible"

이 저장소의 마이그레이션은 대체로 additive 다(V6 unique slug, V7 unique sku, V8 status).
하지만 그것을 **강제하는 장치가 없다.** 파괴적 마이그레이션이 하나 들어오는 순간
`--rollback` 이 조용히 위험해진다. 다음 작업으로 적절한 것:

- 마이그레이션 리뷰 규칙(DROP/RENAME/NOT NULL 추가 금지)을 CI 검사로
- 되돌릴 수 없는 마이그레이션이 포함된 버전은 저널에 표시해 `--rollback` 이 경고

### 5.3 게이트웨이 토큰 호환성

`ops/28-deploy.sh` 가 2단계로 배포하는 이유(§3.3 ④)는 게이트웨이가 옛 토큰을 거부하기
때문이다. 순서로 푸는 것은 **증상 대응**이다. 근본 해법은 호환성이다 — 게이트웨이가
두 형태의 토큰을 모두 받으면 순서 자체가 사라지고, 롤백의 조합 문제도 없어진다.
이것은 §5.2 와 같은 원리(전·후방 호환)를 API 에 적용한 것이다.

### 5.4 커밋 스테이지 시간 — 걱정했던 것보다 낫다

파이프라인을 짤 때는 "13개 이미지 × 2 아키텍처면 책 기준(이상 5분, 최대 10분)을
넘을 것"이라고 봤다. 실측은 다르다(§4): **웜 캐시에서 발행 직전까지 4분 30초**,
자바 빌드가 2분 23초다. 콜드 캐시의 6분은 첫 실행 한 번뿐이었다.

즉 지금 급히 손댈 곳이 아니다. 발행 단계까지 포함한 진짜 총시간은 §3.6 이 풀려야
알 수 있고, 그때 10분을 넘으면 순서대로:

1. **`linux/amd64` 를 뺀다.** 배포 대상(OCI Ampere)도 개발기(Apple Silicon)도 arm64 다.
   amd64 이미지는 지금 아무도 안 쓴다. 빌드할 이미지가 절반이 된다.
2. 이미지 발행을 별도 잡으로 빼고 jar 를 아티팩트로 넘긴다. 단위 테스트 피드백은
   빨라지지만 1GB 가까운 전송 비용이 생긴다 — 그래서 1번을 먼저 한다.

**아직 하지 않았다.** 측정 없이 고치면 엉뚱한 데를 고치게 되고, 지금 측정값은
"고칠 필요 없다"고 말하고 있다.

### 5.5 자동 배포(continuous deployment)

책은 인수 테스트가 충분할 때 마지막 단계까지 자동화하는 선택지를 제시하되, 조건을
분명히 한다:

> "In order for this not to cause breakages, your automated tests have to be
> fantastic—there should be automated unit tests, component tests, and acceptance
> tests (functional and nonfunctional) covering your entire application."

지금은 그 조건을 만족하지 않는다. 사가 한 경로와 서비스 기동은 덮지만, 결제·소셜
로그인·관리자 흐름의 인수 테스트가 없다(docs/34 는 **사람이** 도는 체크리스트다).
자동 배포는 그 다음이다.

---

## 6. CD — 승인 후 자동 배포 (OCI Ampere, 셀프호스티드 러너)

지금 막고 있던 것은 도구가 아니라 **네트워크 정책**이었다. OCI 인스턴스는 인바운드가 제한된 관리 경로(SSH) 하나뿐이다.
선택지는 셋이었고, 셋 다 `ops/28-deploy.sh` 를 그대로 쓴다 — 바뀌는 것은 "누가 그 스크립트를 실행하는가"뿐이다.

| 방법 | 무엇이 바뀌나 | 대가 |
| --- | --- | --- |
| ✅ **셀프호스티드 러너**를 OCI 인스턴스에 설치 | 러너가 아웃바운드로 GitHub 에 붙는다. 인바운드 포트 추가 없음 | 러너가 클러스터 자격증명을 갖는다. 저장소가 공개면 포크 PR 이 러너에서 코드를 실행할 수 있어 **반드시 비공개이거나 승인 게이트가 필요하다** |
| Tailscale/WireGuard 로 러너를 사설망에 참여 | Actions 잡이 kube-apiserver 에 닿는다 | 네트워크 구성 요소가 하나 늘고, 그 자격증명이 새 관리 대상이 된다 |
| GitOps (Argo CD/Flux)를 클러스터 안에 | 클러스터가 git 을 **당겨** 간다. 인바운드 0 유지 | 배포 주체가 스크립트에서 컨트롤러로 바뀐다. 스모크 게이트와 자동 복귀를 컨트롤러 쪽에 다시 만들어야 한다 |

첫째를 골랐다(저장소는 비공개다). 흐름:

```
push(기본 브랜치) ─> deployment pipeline(build.yml) 초록(릴리스 후보)
운영자가 Run workflow(버전) ─> deploy (OCI Ampere)(deploy-oci.yml)
                                ├─ gate   : 대상 커밋·그 파이프라인이 초록인가·프론트 설정 Variables
                                └─ deploy : 러너가 ops/28-deploy.sh local <SHA12>
```

- 이미지는 다시 만들지 않는다 — 커밋 스테이지가 발행한 amd64·arm64 이미지(태그 = 커밋 SHA 12자리)를 그대로 쓴다.
- `ops/28` 이 하던 것이 전부 그대로다 — 이미지 존재 확인, 되돌릴 수 없는 마이그레이션 검사, 스모크 게이트, 실패 시 직전 버전 자동 복귀.
- 러너는 운영자와 **같은 사용자**(ubuntu)로 돈다 — `~/.kube/config`·`~/.docker/config.json`·배포 이력(`~/.ecommerce-msa/deploy-journal.tsv`)이
  손으로 배포할 때와 같다. 그래서 `--rollback`·`--report` 도 하나의 이력을 본다.
- gitignore 된 로컬 오버레이(`k8s-overlays/local`)는 운영자의 작업 사본(`DEPLOY_REPO_DIR`)에 있는 것을 링크한다. 비밀은 만지지 않는다.
- 실행은 **수동(Actions → deploy (OCI Ampere) → Run workflow)뿐**이다 — 기본 브랜치에서만, 그리고 **그 커밋의 파이프라인이 초록일 때만** 된다.
- 🔒 **실행 = 승인.** 파이프라인이 초록이 돼도 자동으로 배포하지 않는다(가드 B-75). 예전에는 자동 실행(workflow_run) + Environment
  `production` 의 Required reviewers 를 승인 게이트로 적었으나, 비공개 저장소는 Free · Pro · Team 요금제에서 Required reviewers 를
  쓸 수 없어 게이트가 없었다(2026-10-05, 승인 없이 대기하던 배포를 취소하고 수동 실행만 남겼다).

### 6.1 설치 (한 번)

1. **GHCR 패키지 권한** — 패키지마다 Settings → Manage Actions access → 이 저장소 **Write**. (PAT 로 먼저 올린 패키지는 저장소에
   연결돼 있지 않아 커밋 스테이지의 이미지 발행이 `permission_denied: read_package` 로 실패한다.)
2. **저장소 Variables**(Settings → Secrets and variables → Actions → Variables) — `ops/frontend.env` 의 `REACT_APP_*` 일곱 개(공개값).
   비어 있으면 gate 가 배포를 멈춘다(CI 가 만든 프론트 이미지가 깨진다). 필요하면 `DEPLOY_REPO_DIR`(기본 `/home/ubuntu/ecommerce-msa`)·
   `DEPLOY_ENV`(기본 `local`).
3. **Environment** — 따로 만들 것이 없다. `production` 은 첫 실행이 만들고 배포 이력용이다. 비공개 저장소는 요금제에 따라
   Settings 에 Environments 메뉴가 보이지 않고, Required reviewers 는 Enterprise 에서만 된다 — 승인은 수동 실행으로 대신한다.
4. **러너** — 인스턴스에서(손으로 배포하던 그 작업 사본):
   ```bash
   ./ops/40-deploy-runner.sh check      # arm64·kubectl·docker·오버레이 점검
   ./ops/40-deploy-runner.sh install    # 토큰은 Settings → Actions → Runners → New self-hosted runner 의 값(입력으로)
   ```
5. **첫 배포** — Actions → deploy (OCI Ampere) → Run workflow(버전 비움 = 기본 브랜치 최신) → 러너 로그에서 ops/28 출력 확인.

### 6.2 예전 Jenkins 스크립트(`CI-CD/`)

AWS 인스턴스 두 대에 docker compose 로 올리던 구성용이다(설정 저장소 체크아웃 → 설정 파일 복사 → 사설 레지스트리 → SSH 로 compose).
지금 구성(OCI Ampere 한 대의 k3s, GHCR, 커밋 SHA 태그, 스모크 게이트)과 맞지 않아 쓰지 않는다 — 참고용으로 남겨 둔다.

---

## 7. 실전 사용법

### 평소 배포

```bash
# 1. 파이프라인이 초록인지 확인하고 릴리스 후보 버전을 얻는다
#    (Actions 요약에 배포 명령이 그대로 나온다)

# 2. 무엇이 바뀌는지 먼저 본다
./ops/28-deploy.sh prod a1b2c3d4e5f6 --dry-run

# 3. 배포한다 — 스모크까지 통과해야 성공으로 기록된다
./ops/28-deploy.sh prod a1b2c3d4e5f6
```

### 뭔가 잘못됐을 때

스모크가 실패하면 **아무것도 하지 않아도 직전 버전으로 돌아간다.** 수동으로 돌리려면:

```bash
./ops/28-deploy.sh prod --rollback
```

원인을 살아 있는 상태에서 보고 싶다면 자동 복귀를 끄고 배포한다:

```bash
NO_ROLLBACK=1 ./ops/28-deploy.sh prod a1b2c3d4e5f6
# 조사 후
./ops/28-deploy.sh prod --rollback
```

### 파이프라인 없이 급하게 만들어야 할 때

```bash
./ops/19-build-images.sh --registry ghcr.io/<owner> --push   # 태그는 자동으로 커밋 SHA
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD)
```

책 10장의 경고를 함께 새길 것:

> "Never do them late at night, and always pair with somebody else."
>
> "Sometimes it's better to roll back to the previous version than to deploy a fix."

긴급 수정이 롤백보다 나은 경우는 생각보다 드물다.

---

## 8. 관련 문서

- [33-deploy-runbook.md](33-deploy-runbook.ko.md) — 처음부터 올리는 순서. 이 문서는 **그 다음**,
  두 번째 배포부터의 이야기다
- [34-acceptance-checklist.md](34-acceptance-checklist.ko.md) — 사람이 도는 인수 경로.
  §5.5 의 "인수 테스트가 부족하다"는 이 체크리스트가 자동화되지 않았다는 뜻이다
- [17-testing.md](17-testing.ko.md) — 파이프라인이 돌리는 테스트가 무엇을 잡는지
- [31-kustomize-oci-tunnel.md](31-kustomize-oci-tunnel.ko.md) — 오버레이 구조와 인바운드 0 구성
- [ops/README.md](../ops/README.ko.md) — 운영 스크립트 전체
