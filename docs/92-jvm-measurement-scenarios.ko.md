# 92. JVM 측정 시나리오 — 성능 책 두 권을 이 시스템에

[日本語](92-jvm-measurement-scenarios.md) | **한국어**

> 📝 **상태(2026-11-17) — 준비, 측정 전.** 성능 책 두 권의 장별 측정 시나리오를 이 시스템(백엔드 13개 · k3s · JDK 17 JRE 이미지)의 서비스 · 도구 · 명령 · 판정으로 옮겼다. 부하 없이 바로 보는 사실 표 `ops/60-jvm-facts.sh`(§2)를 더했고, 나머지는 이미 있는 `ops/53` · `ops/58` · Prometheus 로 잰다(§3 · §4). **결과는 아직 하나도 없다** — §5 표는 비어 있다.
>
> ⚠️ **정직한 고지**: 이 문서를 쓰면서 측정은 하지 않았다. 원본인 두 책의 "측정 시나리오" 문서(다른 저장소 — 이름과 번호로만 가리킨다)도 측정 결과가 아니라 **계획**이다. §2 의 "예상" 은 매니페스트 한도와 JDK 규칙으로 계산한 값이고, 1 코어 · 768Mi → Serial · 384MB 와 1Gi → 512MB 만 docs/91 §4-3 에서 같은 조건으로 확인됐다. 코드에 대한 단정은 grep 으로 확인한 것만 적었고, 못 찾은 것은 "확인 못 함" 이라고 적었다. 판정은 절대 수치가 아니라 **방향 · 비율**로 한다(docs/19 §0 · docs/83 §1 — 실습 클러스터의 수치는 코어를 나눠 쓴 값이다).

## 0. 요약

**왜**: docs/91 §4-3 이 GC 측정 배선(천장 표의 GC 열 · 경보 · 대시보드)과 로드맵 ①~④ 를 세웠다. 그 로드맵은 "GC 를 바꿔 본다" 하나인데, 책 두 권의 시나리오에는 그 앞(JVM 이 무엇을 골랐나)과 옆(JIT · 스레드 · 커넥션 · 힙 밖 메모리 · Kafka 리밸런스)이 더 있다. 이 문서는 그 시나리오를 하나씩 이 시스템에 대 보고 **무엇을 어떤 명령으로 재고 무엇으로 판정하나**를 정한다. docs/91 과 겹치는 배선 · 지표 설명은 되풀이하지 않고 가리키기만 한다.

| 층 | 무엇 | 도구 | 이 문서의 행 | 상태 |
|---|---|---|---|---|
| ① 부하 없이 바로 | JVM 이 컨테이너 한도를 보고 고른 것 — GC · 힙 · 스레드 · ForkJoin 병렬도 · 코드 캐시 | `ops/60-jvm-facts.sh`(새) · Prometheus 한 번 | F-1 ~ F-6(§2) | 6 — 모두 바로 가능 |
| ② 부하 중 | 천장 계단의 지연 · CPU 냐 GC 냐 · 어디에 CPU · 할당 · 대기 · 메모리 · 리밸런스 | `ops/53 ceiling`(GC 열) · `ops/58` · `vmstat` · PromQL | L-1 ~ L-12(§3) | 12 — 모두 바로 가능(둘은 도구 동작 확인 필요) |
| ③ 비교 실험 | 변수 하나만 바꿔 같은 부하로 — GC · 힙 · CPU 한도 · 데움 · 레플리카 · 풀 · 기동 | `ops/53`(`replicas` · `pool` · `jvm` · `STAGES`) · 새 도구 | X-1 ~ X-14(§4) | 14 — 바로 가능 8 · 새 도구 필요 6 |
| 해당 없음 | 이 시스템에 그 대상이 없거나 이미 반영됨 | — | N-1 ~ N-12(§4-4) | 책 시나리오 29개 |

- 책 표기: **JP** = Scott Oaks *Java Performance* 2판의 측정 시나리오(S2-1 … S11-3, 30개), **OC** = Evans · Gough *Optimizing Cloud Native Java* 2판의 측정 시나리오(1-1 … 15-3 · A-1 … B-3, 48개). 78개 모두 아래 어느 한 행(또는 해당 없음)에 들어 있다 — 색인은 §4-5.
- 순서: ① → ② → ③. ① 이 틀리면(예: 생각과 다른 GC) ② · ③ 의 해석이 다 바뀐다.

---

## 1. 실습 클러스터 준비

측정은 운영이 아닌 실습 클러스터(k3s — 서버 하나 · 에이전트 둘 이상)에서 한다. 시작 전에 노드가 모두 Ready 이고 데이터 파드 · 앱 파드가 Running 인지, 일시정지에서 깨운 VM 이면 시계가 맞는지(`timedatectl`)를 본다. 끝나면 측정용 설정을 꼭 되돌린다: `./ops/53-load-k6.sh limits default` · `pool default` · `replicas default` · `jvm default`.

---

## 2. ① 부하 없이 — 사실 표(`ops/60-jvm-facts.sh`)

```bash
./ops/60-jvm-facts.sh                                  # 백엔드 13개 — 결과 ~/.ecommerce-msa/jvm-facts/<UTC 시각>/jvm-facts.md · raw/<서비스>.txt
./ops/60-jvm-facts.sh catalog-service gateway-server   # 고른 서비스만
ALLOW_PROD=1 ./ops/60-jvm-facts.sh catalog-service     # 운영 — 한가한 시간에 서비스 하나씩(아래)
```

**무엇을 하나**: 서비스마다 가장 오래 뜬 Running 파드 하나에서, 그 컨테이너의 한도 · 이미지 · 환경 변수 그대로 **두 번째 JVM** 을 `-XX:+PrintFlagsFinal -XshowSettings:system -version` 으로 띄운다. JRE 이미지라 `jcmd` 가 없어 돌고 있는 JVM 에 붙을 수 없기 때문이다 — 같은 입력이면 에르고노믹스도 같다. 엔트리포인트(`ops/Dockerfile.runtime`)와 같은 `-XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENTAGE}` 와 `JAVA_OPTS` 를 주되, 파일에 쓰거나 붙는 옵션(`-Xlog` · JFR · 에이전트 · 덤프 경로)은 뺀다(돌고 있는 JVM 의 gc.log 를 덮지 않게).

**표의 열 · 읽는 법**

| 열 | 뜻 | 볼 것 |
|---|---|---|
| 한도 CPU / 메모리 | 매니페스트의 `limits` | 아래 예상과 같은지 |
| 본 CPU · 본 메모리 · cgroup | `-XshowSettings:system` 의 Effective CPU Count · Memory Limit · Provider | 한도가 JVM 에 그대로 보이는지(cgroup v2) |
| GC | `Use*GC` 중 true 인 것 | **본 CPU < 2 면 Serial** — 정지 동안 한 스레드가 전부 치운다 |
| 최대 힙 · 첫 힙 | MaxHeapSize · InitialHeapSize | 메모리 한도 × `MaxRAMPercentage` |
| GC 스레드 병렬/동시 · JIT 스레드 · 코드 캐시 | ParallelGCThreads/ConcGCThreads · CICompilerCount · ReservedCodeCacheSize | 1 코어에서 컴파일 스레드가 앱과 같은 코어를 나눈다(L-8) |
| 압축 oop | UseCompressedOops | 힙이 작아 늘 true(N-3) |
| ForkJoin 병렬도 | 본 CPU − 1(최소 1) — JDK 규칙으로 계산 | 1 이면 `parallelStream` · `CompletableFuture` 기본 실행기는 병렬이 아니다(F-3) |
| JDK | 판 | `ops/Dockerfile.runtime` 의 고정 판(B-98)과 같은지 |

**예상**(매니페스트로 계산 — 측정 전):

| 서비스 | 한도 | GC | 최대 힙 | 첫 힙 | JIT 스레드 | ForkJoin |
|---|---|---|---|---|---|---|
| catalog-service | 1000m / 1024Mi | Serial | 512M | ~16M(1/64) | 2 | 1 |
| 나머지 12개(gateway · user · cart · order · pay · coupon · chat · notification · delivery · saga-projection · eureka · config) | 1000m / 768Mi | Serial | 384M | ~12M(1/64) | 2 | 1 |

- 384M · 512M · Serial 은 docs/91 §4-3 이 같은 조건으로 확인한 값이다. 첫 힙은 `InitialRAMPercentage` 기본(1.5625%)으로 계산한 값, JIT 스레드 2 는 계층형 컴파일의 하한으로 본 값이라 표로 확인한다. Serial 이면 GC 스레드 열은 뜻이 없다.
- 운영은 `ops/28 local`(→ `external` → `prod` → 베이스)이라 같은 한도다(`external` 은 CPU **요청**만 100m 으로 낮춘다). `k8s-overlays/oci`(2 OCPU 용 — 800m · 512Mi · `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC`)를 쓰면 최대 힙은 ~307M 이 된다.
- 한도가 없는 파드라면 JVM 은 노드(VM)의 vCPU 를 본다 — 그러면 G1 이 된다. 표의 "본 CPU" 가 1 이 아니면 그 파드의 한도부터 본다.

**운영에서 돌릴 때**: 두 번째 JVM 은 `-version` 만 하고 끝나지만(힙은 예약만, 쓰는 메모리는 수십 MB) 768Mi 한도의 파드 안에서 잠깐 함께 산다. `ALLOW_PROD=1` 일 때만, 한가한 시간에 서비스 하나씩. 출력에 비밀은 없다(`JAVA_TOOL_OPTIONS` 는 스토어 경로만 — B-83).

**시험**: `JvmFactsScriptTest`(5 — 1 코어면 Serial 이고 `-Xlog` 파일 옵션을 뺀다 · 4 코어면 G1 · ForkJoin 3 · 운영은 명시적 허락 · 파드 없음 · 실패해도 다른 서비스는 계속 · 서비스 이름 검사). 시험의 실제 JVM 으로 탐침을 돌린다.

### 2-1. ① 의 행

| 행 | 책 시나리오 | 무엇을 · 어떻게 | 판정 | 상태 |
|---|---|---|---|---|
| F-1 | JP S5-1 · S3-1, OC 4-1 · 8-1 · 9-2 | 한도 → GC · 힙 · 본 CPU/메모리 — `ops/60` | 위 예상과 같으면 끝. 다르면 그 서비스의 한도 · `JAVA_OPTS` · `JVM_MAX_RAM_PERCENTAGE` 를 본다 | 바로 가능 |
| F-2 | JP S3-1(컴파일러 스레드 표), OC 6-3(크기) | JIT 스레드 · 코드 캐시 크기 — `ops/60` 의 열 | 기록만(L-8 의 기준) | 바로 가능 |
| F-3 | JP S9-2, OC 13-2 | ForkJoin 병렬도 — `ops/60`. 코드: `parallelStream` · `.parallel()` · `CompletableFuture.supplyAsync/runAsync` · `commonPool` 사용 0건(grep, 테스트 밖). 비동기는 모두 자기 실행기(`SearchFilterFacets` · `AsyncMailConfig` · `StompFanoutConfig` 등) | 병렬도 1 · 사용처 0 이면 "공통 풀은 이 시스템의 병목이 아니다". 라이브러리 안의 사용은 확인 못 함 | 바로 가능 |
| F-4 | OC B-2 | 실습 ↔ 운영이 같은 결정을 하나 — 두 클러스터에서 `ops/60`(운영은 `ALLOW_PROD=1`) | GC · 힙 · 본 CPU 가 같으면 실습의 **비율**을 운영에 옮길 수 있다. 아키텍처(실습 amd64 · 운영 arm64)는 다르다 — JIT 코드가 달라 절대값은 옮기지 않는다 | 바로 가능 |
| F-5 | OC 3-1 · B-1 | 켜 둔 스위치 점검 — `raw/<서비스>.txt` 에서 출처가 `{command line}` 인 줄 | 이유를 댈 수 없는 스위치가 없어야 한다(지금 명시는 `MaxRAMPercentage` · `ErrorFile` · `-CreateCoredumpOnCrash`, oci 는 `UseSerialGC`) | 바로 가능 |
| F-6 | JP S5-1(지표로 맞춰 보기) — docs/91 §4-3 로드맵 ① | 지금 GC 종류 · 힙 · 평시 GC 부담 — `count by (service, gc) (jvm_gc_pause_seconds_count)` · `max by (service) (jvm_memory_max_bytes{area="heap"})` · `max by (service) (jvm_gc_overhead)` | `gc` 가 `Copy` · `MarkSweepCompact` 면 Serial — F-1 과 같아야 한다. 운영 값은 docs/91 §4-3 에 적는다 — 며칠의 평균 · 최대는 `PROM=http://localhost:9090 ./ops/61-gc-baseline.sh`(읽기만, 기본 7일) | 바로 가능 |

---

## 3. ② 부하 중 — `ops/53 ceiling` · `ops/58` · 지표

공통 준비(docs/83 §3 · docs/91 §4): `./ops/53-load-k6.sh smoke` → `limits load` → 측정 → `limits default`. 서버 쪽 열은 `PROM` 이 있어야 나온다(§1). 계단 · 판정 · GC 열은 docs/91 §4 · §4-3 그대로다.

```bash
./ops/53-load-k6.sh limits load
PROM=$PROM ./ops/53-load-k6.sh ceiling browse                    # 결과 load-results/ceiling-browse-<시각>/ceiling.md
PROM=$PROM CEILING_RATES=5,10,20,40 ./ops/53-load-k6.sh ceiling order
PROM=$PROM WHEN=rps:gateway-server:900 ./ops/58-cpu-profile.sh    # 다른 창 — 그 부하에 닿으면 catalog · 게이트웨이 표본
./ops/53-load-k6.sh limits default
```

| 행 | 책 시나리오 | 무엇을 · 어떻게(이 시스템) | 판정 | 상태 |
|---|---|---|---|---|
| L-1 | OC 1-1, JP S2-3 | **팔꿈치 · 평균과 꼬리** — `ceiling browse` 의 계단 표(요청/초 · p99 · 5xx)와 서비스별 GC 정지 최대 | p99 가 꺾이는 계단 = 천장(docs/91 §4). 꺾인 계단의 p99 와 그 계단의 GC 정지 최대가 같은 자릿수면 꼬리는 GC 몫(Serial 정지가 그대로 지연에 더해진다) | 바로 가능 |
| L-2 | OC 7-3 · 10-3(창 주의) — docs/91 로드맵 ② | **CPU 냐 GC 냐 호스트냐** — ceiling.md 의 CPU · GC 부담 · GC 뒤 힙 열 + 같은 계단에 노드 안 `vmstat 5` | CPU ≥ 0.8 이고 GC 부담 < 0.05 → 요청당 일(L-3). GC 부담 ≥ `CEILING_GC_OVERHEAD`(0.10) → GC(X-2 · X-4). GC 뒤 힙 ≥ 90% → 힙 부족(X-4). `vmstat` 의 `st`(steal)가 크면 호스트 코어가 모자란 것 — 그 계단의 결과는 서버 천장이 아니다. `sy` 가 높으면 GC 가 아니다(GC 는 유저 공간). GC 부담은 5분 창이라 경계 계단은 `CEILING_HOLD=5m` | 바로 가능 |
| L-3 | JP S3-3, OC 12-1 | **어디에 CPU 를 쓰나** — `ops/58`(async-profiler `itimer` — 세이프포인트 편향이 적은 쪽). 묶음 · flat · inclusive | docs/83 의 마지막 프로파일(catalog: JSON 17.6% · Tomcat 17.5% · Spring 16.2% · TLS 13.1%)과 같은 단계에서 견준다 — 묶음 순위가 바뀌었으면 그것이 결과. 도구끼리의 비교(JP S3-3)는 하지 않는다: 같은 도구로 전후만 본다 | 바로 가능 |
| L-4 | OC 1-2, JP S10-3(할당 쪽) | **할당은 어디서** — ceiling 의 파드당 할당 MB/s 열 + `PROM=$PROM EVENT=alloc ./ops/58-cpu-profile.sh catalog-service gateway-server` | 할당 MB/s 가 높은 서비스의 alloc 표본 상위가 JSON · 캐시 값 풀기면 docs/83 의 CPU 프로파일과 같은 곳. ⚠️ `EVENT` 는 async-profiler 이벤트 이름을 그대로 넘긴다(검사는 영소문자만) — `alloc` 으로 돌려 본 적은 없고 묶음 표는 CPU 기준으로 만든 것이라 **먼저 한 번 돌려 표가 나오는지 확인** | 바로 가능(확인 필요) |
| L-5 | JP S3-2 | **락 · 모니터 대기** — JFR 대신 `WHEN=now EVENT=lock ./ops/58-cpu-profile.sh <서비스>`(`jcmd` 가 없어 JFR 을 실행 중에 켤 수 없다) | 상위 대기가 Hikari 풀 · 로그 appender · 우리 코드의 `synchronized`(테스트 밖 15줄, grep) 중 어디인지. ⚠️ L-4 와 같이 `lock` 이벤트로 돌려 본 적 없음 | 바로 가능(확인 필요) |
| L-6 | JP S10-1 | **워커 수 = 동시에 블로킹된 요청 수** — `tomcat_threads_busy_threads` ÷ `tomcat_threads_config_max_threads`(기본 200, oci 50) · `hikaricp_connections_pending` · `http_server_requests_active_seconds_gcount`(docs/83 §4) | 풀(파드당 5, catalog 은 데이터소스 둘)이 먼저 차고 스레드는 남으면 막힌 곳은 DB 커넥션(→ X-8). 둘 다 차면 스레드 포화. 게이트웨이(WebFlux)에는 Tomcat 지표가 없다 | 바로 가능 |
| L-7 | JP S10-2 | **서비스 간 HTTP 연결 재사용** — Feign(JDK `HttpURLConnection`)의 쉬는 연결 64(`HttpKeepAlive`) · 응답 끝까지 읽기(`FeignResponseBody`). `ceiling order` 중 `WHEN=now ./ops/58-cpu-profile.sh cart-service order-service` 의 TLS 묶음 | docs/83 2026-10-18: 고치기 전 cart CPU 의 23% 가 새 TLS 핸드셰이크, 고친 뒤 50 호출에 연결 1. TLS 묶음이 다시 커지면 연결이 버려지고 있다 | 바로 가능 |
| L-8 | OC 6-3, JP S4-1(일부) | **JIT · 코드 캐시** — `jvm_memory_used_bytes{area="nonheap", id=~"CodeHeap.*"}` · docs/84 §4 의 C2 CPU 비율 반복문(`kill -3` 덤프의 `C2 CompilerThread0` cpu/elapsed) | 코드 캐시 사용이 F-2 의 크기에 닿지 않아야 한다. C2 비율은 정상 0.2 ~ 0.5% — 수십 % 면 컴파일 정지(2026-10-20 user-service 의 10시간, docs/83 §2.8). 1 코어 파드에서 컴파일은 요청과 같은 코어를 쓴다 | 바로 가능 |
| L-9 | JP S8-1 · S8-2, OC 8-2(관찰) | **힙 밖 메모리 · 한도까지의 여유** — `kubectl -n ecommerce top pods` 의 메모리 vs `sum by (pod) (jvm_memory_committed_bytes)` · `jvm_buffer_memory_used_bytes{id="direct"}` | (컨테이너 메모리 − JVM committed)가 스레드 스택 · 메타스페이스 밖 네이티브 몫. 한도 768Mi 에서 남는 몫이 줄어드는 서비스가 X-4 의 대상. NMT 는 `jcmd` 가 없어 쓸 수 없다. 게이트웨이(Netty)의 직접 메모리가 JDK 버퍼 지표에 다 잡히는지는 확인 못 함 | 바로 가능 |
| L-10 | JP S7-1 | **누수 추세** — 오래 돌리기(`mixed` 8시간, docs/83)에서 `jvm_memory_usage_after_gc{area="heap",pool="long-lived"}` 의 기울기 · 경보 `JvmHeapAfterGcHigh` | GC 뒤 힙이 시간에 따라 계속 오르면 누수 의심. 히스토그램 두 장의 차이(JP S7-1 의 방법)는 `jmap` · `jcmd` 가 없어 못 한다 — 의심되면 X-4 처럼 새 도구 | 바로 가능 |
| L-11 | OC 14-1 | **리밸런스 = 분산 STW** — `order` 고정 부하(`STAGES`) 중 컨슈머 서비스 하나를 `kubectl -n ecommerce rollout restart deploy/notification-service`, `max by (service, topic) (kafka_consumer_fetch_manager_records_lag_max)` | 지금 할당 전략은 `RebalanceSettings` 의 1단계(`kafka.rebalance.protocol` 기본 `eager` — yml 에 설정 없음, `[cooperative-sticky, range]` 를 알지만 그룹은 eager): 재시작 동안 그 그룹의 모든 파티션 지연이 함께 오르면 eager 대로. 2단계(cooperative)와의 비교는 설정을 바꿔야 해 새 도구(§6) | 바로 가능 |
| L-12 | OC 4-2 | **할당 버스트 · 조기 승격** — `spike`(평소 → 20초 만에 최고) 동안 `rate(jvm_gc_memory_promoted_bytes_total[1m])` · `jvm_gc_pause_seconds_count{action="end of major GC"}` | 급증 직후 승격 바이트와 풀 GC 수가 함께 튀면 조기 승격 — 젊은 세대가 384M 힙의 일부라 Serial 에서 그대로 정지. ⚠️ 승격 지표는 Micrometer `JvmGcMetrics` 가 내는 것으로 알지만 이 저장소의 규칙 · 대시보드는 읽지 않는다 — 노출되는지 먼저 확인 | 바로 가능(확인 필요) |

---

## 4. ③ 비교 실험 — 변수 하나만

### 4-1. 공통 규칙

- **부하**: 천장(§5 의 L-1)의 80% 고정 — `RATE=<r> STAGES="1m:<r>,10m:<r>,30s:0" ./ops/53-load-k6.sh browse`(RATE 는 가장 높은 단계와 같게 — docs/83 §3). 데움은 `smoke` 와 1분 단계.
- **반복**: 기준 · 대상을 **번갈아** 5회 이상(JP S2-1 · OC 2-1). 한쪽을 몰아서 재면 호스트 상태가 바뀔 때 한쪽만 손해를 본다.
- **판정**: 미리 정한다 — p99 · 파드 CPU 당 처리량(요청/초 ÷ 쓴 코어) · GC 부담 · GC 정지 최대. Welch t-검정 p < 0.1 이고 차이가 10% 이상이어야 "다르다". 아니면 "결론 없음"(같다가 아니다). 계산은 표준 라이브러리 python 몇 줄(JP S2-1 의 것)이면 되지만 저장소에 없다 → 새 도구(§6).
- **측정용 예외**: 설정을 바꾸는 것은 `ops/53` 의 모드(`limits` · `pool` · `replicas` · `jvm`)만 — 실습 클러스터 판정 · 되돌리기 · 배포 전 점검(`ops/28 check_load_overrides`)이 붙어 있다. **`JAVA_OPTS` · `JVM_MAX_RAM_PERCENTAGE` · CPU 한도를 손으로 `kubectl set env` 하지 않는다** — `ops/53 jvm`(2026-11-17)으로만 바꾼다: 원래 값(JAVA_OPTS · 힙 비율 · CPU 한도)을 Deployment 주석 `ecommerce.io/jvm-measure` 에 적고 한 번의 롤아웃으로 바꾸며, `jvm default` 가 그 값으로 되돌린다. 다시 `load` 하면 원래 값 위에 새로(겹쳐 쌓지 않는다). 남아 있으면 `ops/28` 이 배포 전에 알린다(설정은 `ops/28` 로만, 측정용 예외는 `ops/53` 의 모드로만). 바꾼 뒤 JVM 이 정말 그렇게 골랐는지는 `ops/60-jvm-facts.sh <서비스>`.

### 4-2. 바로 가능한 것

| 행 | 책 시나리오 | 대상 | 바꿀 변수 | 명령 | 판정(방향 · 비율) |
|---|---|---|---|---|---|
| X-6 | OC 9-1 · 6-1, JP S4-1 | catalog · gateway | 파드가 데워졌나(새 파드 vs 오래된 파드) | 고정 부하 중 `kubectl -n ecommerce rollout restart deploy/catalog-service` → 파드별 `histogram_quantile(0.99, sum by (pod, le) (rate(http_server_requests_seconds_bucket{service="catalog-service"}[1m])))` · 게이트웨이 5xx | 새 파드 p99 ÷ 데워진 파드 p99 를 1 · 3 · 5분에. 1.2 아래로 내려오는 시간이 "데움 시간". readiness 는 health 만 본다(게이트웨이의 `RateLimiterWarmup` 밖에 데우기 없음) — 그 시간 동안의 5xx 가 크면 readiness 전 데우기를 판단할 근거 |
| X-7 | JP S9-1(암달) | catalog · gateway | 레플리카 N | `./ops/53-load-k6.sh replicas load <N> catalog-service gateway-server` → `ceiling browse` → `replicas default` | 배수 k = N/N₀ 일 때 천장 비 S. 직렬 비율 F ≈ (k/S − 1)/(k − 1). docs/83에서는 catalog 3 → 4 에 +13% — 호스트 코어를 나눌 뿐이었다. 에이전트가 둘인 지금은 L-2 의 `st` 를 같이 적는다 |
| X-8 | JP S10-1 의 연장 | catalog | DB 풀 5 → 10 | `./ops/53-load-k6.sh pool load 10` → `ceiling browse` → `pool default`(DB 연결 예산은 스크립트가 먼저 센다) | `hikaricp_connections_pending` 이 0 으로 가고 천장이 오르면 풀이 병목. CPU 가 먼저 차면(2026-10-18 처럼) 풀은 아니다 |
| X-10 | OC B-3 | catalog | 데이터 모양 · 유입 모양 | `SKEW=0.5` · `LONG_NAMES=1` 로 `ops/44` 다시 넣기(docs/83 §2.5) / `spike` | 같은 상품 수의 기본 모양 회차와 견준다. 먼저 꺾이는 곳(개수 세기 · 필터 메뉴 · 깊은 쪽 · 응답 크기)이 바뀌는지 |
| X-2 | JP S5-2, OC 5-1 — docs/91 로드맵 ③ | gateway · catalog | 1 코어에서 GC: Serial(지금) · Parallel · G1 | `./ops/53-load-k6.sh jvm load gateway-server catalog-service gc=parallel`(다음 회차 `gc=g1`, 기준 `gc=serial`) → §4-1 고정 부하 → 끝나면 `jvm default` | 책: 1 코어 · CPU 를 다 쓰는 일은 Serial 이 가장 빠르고 G1 은 백그라운드 스레드가 계산을 빼앗는다, 응답형(CPU 50%)은 G1 의 p99 가 낮았다. 여기서는 80% 부하라 처리량은 Serial ≥ 나머지, p99 는 결론 미정 — 그것을 잰다. 힙은 고정(같은 `MaxRAMPercentage`) |
| X-3 | JP S6-3 · S6-2, OC 5-2 | gateway · catalog | CPU 한도 1 → 2 코어(G1 이 된다), 그리고 2 코어에서 ZGC · Shenandoah | `jvm load gateway-server catalog-service cpu=2`(JVM 이 스스로 G1) · `cpu=2 gc=z` · `cpu=2 gc=shenandoah`, 비교 대상 "1 코어 둘" 은 `replicas load 2`. 한도만 바꾸므로 요청 예산은 그대로 — 노드의 실제 CPU 는 `kubectl top nodes`. G1 거대 객체는 `gclog=1` 의 로그에서 | 질문은 "1 코어 파드 둘 vs 2 코어 파드 하나" — 같은 총 코어에서 천장 · p99 · 정지 최대. G1 이면 `-Xlog:gc+humongous` 로 거대 객체(리전 1MB 면 512KB 이상) · IHOP 동시 모드 실패를 함께 본다 |
| X-4 | OC 8-2, JP S7-2 | catalog(1Gi) · 768Mi 서비스 하나 | 힙 비율 50 · 60 · 70% | `jvm load catalog-service heap=60` · `heap=70`(65 이상이면 스크립트가 경고) — 메모리 한도는 그대로 | GC 부담 · GC 뒤 힙이 줄고 `OOMKilled`(`kubectl get pods` 의 lastState)가 0 이면 올릴 근거. 이미지 주석은 "65% 이상이면 768Mi 서비스는 컨테이너 한도에 먼저 닿는다" — 측정은 없다, 이것으로 확인. 데이터는 100만(2026-10-19 에 25% 로 catalog 가 힙 부족을 되풀이한 조건, docs/83 §5). 힙 OOM 은 그 스레드만 끝내고 JVM 은 산다(JP S7-2) — 지금은 `ExitOnOutOfMemoryError` 가 없어 liveness 가 죽였다 |
| X-5 | JP S5-3, OC 10-3 · 5-3 | gateway(GC 부담이 튀던 파드, docs/83) | GC 로그 켜기 | `jvm load gateway-server gclog=1` — `-Xlog:gc*,safepoint:file=/var/log/jvm/gc.log:…`(한 파일, 3개 × 10MB 회전) → `kubectl -n ecommerce cp <파드>:/var/log/jvm/gc.log ./gc.log`(emptyDir — 파드를 지우기 전에) | 지표(5분 창 · 최대)와 로그의 정지 사건을 같은 시각에 놓아, 창이 숨긴 몰림(분당 1건 vs 마지막 0.1초에 60건)이 있는지. 세이프포인트 도착 시간이 GC 정지보다 길면 GC 가 아니라 늦게 멈추는 스레드 |

### 4-3. 새 도구가 필요한 것

| 행 | 책 시나리오 | 대상 | 바꿀 변수 | 지금 없는 것 | 판정(방향 · 비율) |
|---|---|---|---|---|---|
| X-1 | JP S2-1, OC 2-1 | 모든 비교 | — (판정 방법) | 회차 묶음 비교 — `ops/53 report` 가 회차 여럿의 p99 · 처리량을 모아 Welch p 를 내는 것 | §4-1 의 규칙 |
| X-9 | OC 6-2 · 8-3 | 모든 서비스 | 기동: CDS/AppCDS · 기본 이미지 | 이미지를 바꿔 빌드(`ops/Dockerfile.runtime` 한 줄 — B-98) | 기준선은 지금도 읽힌다: `application_ready_time_seconds`(서비스별). startupProbe 예산 5분 · HPA 의 늦음(docs/83 spike)과 견줘 기동이 줄어드는 비율 |
| X-11 | OC 2-3 | browse · order | 응답 범주별 지연 | k6 요약을 상태 코드별로 — 지금은 엔드포인트(`name`)별뿐이라 격벽의 빠른 503 이 전체 p99 를 끌어내린다 | 2xx 만의 p99 와 전체 p99 를 나란히. 천장 판정은 5xx ≤ 0.1% 로 끊으니 천장 계단에서는 차이가 작아야 한다 |
| X-12 | JP S10-3 | user-service · coupon | `ObjectMapper` 를 요청마다 만들기 vs 하나 | 로그인 · 토큰 갱신 k6 시나리오(로그인은 IP 당 초당 1 — `CLIENT_IPS` 로 나눈다) | 코드 사실(grep): `AuthLoginFilter`(로그인 성공 · 실패 응답마다) · `RefreshTokenController` · `JwtAuthEntryPoint`(401 마다) · `RedisOAuth2AuthorizationRequestRepository` · `CouponService`(이메일로 발급) 가 호출마다 `new ObjectMapper()`. 바꿀지는 이 측정 뒤 — `ops/58` 에서 그 경로의 Jackson 초기화 몫이 보이면 |
| X-13 | JP S11-1 | catalog | JDBC 배치(`hibernate.jdbc.batch_size` — 설정 없음, grep) | 앱 경로의 일괄 쓰기 측정(상품 일괄 등록 `ProductImportService`) — `ops/44` 는 생성기로 넣어 앱 경로가 아니다 | 같은 건수에서 배치 유무 · 커밋 경계를 나눠 시간 비. 책: 이득의 대부분은 호출 수를 줄인 것, 커밋 수의 차이는 잡음 |
| X-14 | OC 13-1 · 3-2, JP S6-3(세대형 ZGC) — docs/91 로드맵 ④ | 전체 | JDK 21 | jdk21-build 브랜치 이미지 | 세대형 ZGC(2 코어 이상에서), 가상 스레드(지금 사용 0 — grep), `ops/58` 의 동적 부착이 JDK 21 에서 경고 한 줄을 내는지(JEP 451) |

### 4-4. 해당 없음 — 이유

| 행 | 책 시나리오 | 이유 |
|---|---|---|
| N-1 | JP S2-2 · S4-2 · S4-3, OC A-1 · A-2 · A-3 | 마이크로벤치마크 · JIT 내부(죽은 코드 · 역최적화 · 인라이닝 · 루프 카운터). 이 저장소에 JMH 가 없고 결정이 그 단위에서 나오지 않는다. 서비스에서는 데움(X-6)과 JIT 의 결과(L-8)만 본다 |
| N-2 | JP S6-1 | `GCTimeRatio` · 정지 목표 — Serial 은 이 목표를 쓰지 않는다. X-2 에 Parallel 을 넣을 때 `raw/` 의 값만 적는다 |
| N-3 | JP S7-3 | 압축 oop 32GB 경계 — 최대 힙이 384 ~ 512M |
| N-4 | JP S8-3 | `Inflater.end()` 누락 — 코드에 `Inflater` · `Deflater` · GZIP 스트림 직접 사용 없음(grep, 테스트 밖) |
| N-5 | JP S9-3 | `ThreadPoolExecutor` 큐 규칙 — 코드로 끝나는 구조적 사실(docs/19 §0). 예: 메일 실행기(`AsyncMailConfig`)는 core 2 · max 5 · 큐 100 이라 셋째 스레드는 큐 100 이 찬 뒤에야 생기고 그 뒤는 `CallerRunsPolicy`. 필터 메뉴 실행기는 고정 크기 · 큐 64 · `AbortPolicy` |
| N-6 | JP S11-2 · S11-3 | fetch size · L2 캐시 — `setFetchSize` · `defaultRowFetchSize` 설정 없음(PgJDBC 기본), 스트리밍 읽기(`Stream<…>` 반환 저장소) 없음, Hibernate 2차 캐시 안 씀(캐시는 Redis · 파드 메모리 — docs/83) |
| N-7 | OC 7-1 · 7-2(JP S9-1 의 거짓 공유 절반) | 캐시 라인 · 거짓 공유 — 하드웨어 미시 실험. 요청 경로에 스레드끼리 뜨겁게 갱신하는 변수가 없다(`AtomicLong` 등은 계수기 · 깃발 몇 곳) |
| N-8 | OC 4-3 · 15-1 · 15-2 · 15-3 | JDK 25 기능(컴팩트 헤더 · AOT 캐시 · FFM · ScopedValue) — 이미지는 JDK 17 |
| N-9 | OC 2-2 · 10-1 · 10-2 · 11-2 | 이미 반영된 측정 방법: 도착률(열린 모델) + 버린 반복 1% 판정(docs/83 §2 · docs/91 §4), 히스토그램 버킷으로 SLO(백분위를 평균 내지 않는다 — docs/91 §2), 꼬리 샘플링 · `TRACING_SAMPLING_PROBABILITY=1.0`(B-72 · B-102), 게이트웨이 타이머의 path 태그 끔 · 히스토그램 끔(docs/91 §2-7) |
| N-10 | OC 3-3 | 배포판 간 차이 — JRE 이미지는 판 + 다이제스트 하나로 고정(B-98) |
| N-11 | OC 11-1 · 12-2 · 12-3 | 자바 에이전트 없음(Micrometer Tracing + OTLP — 계측 비용은 `ops/58` 의 '관측' 묶음으로 본다), JFR 상시 기록 없음, 힙 덤프 · 히스토그램은 `jcmd` · `jmap` 이 없고 힙에 개인정보가 있어 운영 금지(이미지가 코어 덤프도 끈다) |
| N-12 | OC 13-3 · 14-2 · 14-3 | 요청 경로에 CAS 재시도 루프 없음, id 는 TSID 를 파드 안에서 만든다(DB 왕복 없음 — 10개 모듈), 하류 장애의 전파는 게임데이 런북(game-day 브랜치)의 범위 |

### 4-5. 색인 — 책 시나리오 → 이 문서의 행

| JP 장 | 시나리오 → 행 |
|---|---|
| 2 성능 테스트 | S2-1 → X-1 · S2-2 → N-1 · S2-3 → L-1 |
| 3 도구 | S3-1 → F-1 · F-2 · S3-2 → L-5 · S3-3 → L-3 |
| 4 JIT | S4-1 → X-6(· L-8) · S4-2 → N-1 · S4-3 → N-1 |
| 5 GC 입문 | S5-1 → F-1 · F-6 · S5-2 → X-2 · S5-3 → X-5 |
| 6 GC 알고리즘 | S6-1 → N-2 · S6-2 → X-3 · S6-3 → X-3(· X-14) |
| 7 힙 | S7-1 → L-10 · S7-2 → X-4 · S7-3 → N-3 |
| 8 네이티브 메모리 | S8-1 → L-9 · S8-2 → L-9 · S8-3 → N-4 |
| 9 스레드 | S9-1 → X-7(거짓 공유는 N-7) · S9-2 → F-3 · S9-3 → N-5 |
| 10 서버 | S10-1 → L-6(· X-8) · S10-2 → L-7 · S10-3 → X-12(· L-4) |
| 11 DB | S11-1 → X-13 · S11-2 → N-6 · S11-3 → N-6 |

| OC 장 | 시나리오 → 행 |
|---|---|
| 1 · 2 정의 · 방법론 | 1-1 → L-1 · 1-2 → L-4 · 2-1 → X-1 · 2-2 → N-9 · 2-3 → X-11 |
| 3 · 4 JVM · GC | 3-1 → F-5 · 3-2 → X-14 · 3-3 → N-10 · 4-1 → F-1 · 4-2 → L-12 · 4-3 → N-8 |
| 5 · 6 고급 GC · 코드 실행 | 5-1 → X-2 · 5-2 → X-3 · 5-3 → X-5 · 6-1 → X-6 · 6-2 → X-9 · 6-3 → L-8(· F-2) |
| 7 · 8 하드웨어 · 클라우드 스택 | 7-1 → N-7 · 7-2 → N-7 · 7-3 → L-2 · 8-1 → F-1 · 8-2 → X-4(· L-9) · 8-3 → X-9 |
| 9 · 10 배포 · 관측 | 9-1 → X-6 · 9-2 → F-1 · 10-1 → N-9 · 10-2 → N-9 · 10-3 → X-5(· L-2) |
| 11 · 12 계측 · 프로파일 | 11-1 → N-11 · 11-2 → N-9 · 12-1 → L-3 · 12-2 → N-11 · 12-3 → N-11 |
| 13 · 14 동시성 · 분산 | 13-1 → X-14 · 13-2 → F-3 · 13-3 → N-12 · 14-1 → L-11 · 14-2 → N-12 · 14-3 → N-12 |
| 15 · 부록 | 15-1 ~ 15-3 → N-8 · A-1 ~ A-3 → N-1 · B-1 → F-5 · B-2 → F-4 · B-3 → X-10 |

세면: JP 30 = ① 3 · ② 8 · ③ 10 · 해당 없음 9, OC 48 = ① 7 · ② 7 · ③ 14 · 해당 없음 20(괄호 안은 함께 보는 행 — 세지 않는다).

---

## 5. 결과 기록(측정하면 채운다)

환경 칸에는 클러스터(실습 클러스터 · VM 크기) · 노드 · k6 위치(호스트 / 부하 VM) · 이미지 커밋 · 데이터 양을 적는다. 원본 폴더는 `load-results/…` · `~/.ecommerce-msa/{jvm-facts,profiles}/…`.

### 5-1. ① 사실 표(`ops/60`)

| 날짜 · 커밋 · 클러스터 | 서비스 | GC | 최대 힙 | 본 CPU | JIT 스레드 | 코드 캐시 | ForkJoin | 예상과 같나 |
|---|---|---|---|---|---|---|---|---|
| — | catalog-service | — | — | — | — | — | — | (예상 Serial · 512M · 1 · 2 · 1) |
| — | gateway-server | — | — | — | — | — | — | (예상 Serial · 384M · 1 · 2 · 1) |
| — | 나머지 11개 | — | — | — | — | — | — | (예상 위와 같음) |
| — | 운영(F-4) | — | — | — | — | — | — | |

운영의 평시 GC(F-6 — GC 부담 · 정지 · 할당 · GC 뒤 힙, 며칠)는 `ops/61-gc-baseline.sh` 의 표를 docs/91 §4-3 '운영 기준값' 에 — 여기 GC 열과 같은 종류인지 본다.

### 5-2. ② 부하 중

| 행 | 날짜 · 커밋 · 환경 | 시나리오 · 계단 | 결과 | 판정 | 원본 |
|---|---|---|---|---|---|
| L-1 | — | ceiling browse | — | — | — |
| L-2 | — | 〃 | — | — | — |
| L-3 | — | ops/58 | — | — | — |
| L-4 | — | ops/58 `EVENT=alloc` | — | — | — |
| L-5 | — | ops/58 `EVENT=lock` | — | — | — |
| L-6 | — | ceiling browse · order | — | — | — |
| L-7 | — | ceiling order · ops/58 | — | — | — |
| L-8 | — | mixed 8시간 | — | — | — |
| L-9 | — | 〃 | — | — | — |
| L-10 | — | 〃 | — | — | — |
| L-11 | — | order 고정 + 재시작 | — | — | — |
| L-12 | — | spike | — | — | — |

천장 자체(서비스별 파드당 요청/초)는 docs/91 §4-1 에 적는다 — 여기에는 같은 회차의 JVM 쪽 해석만.

### 5-3. ③ 비교 실험

| 행 | 날짜 · 커밋 · 환경 | 기준 → 대상 | 회차(기준/대상) | p99 | CPU 당 처리량 | GC 부담 · 정지 최대 | p(Welch) | 판정 |
|---|---|---|---|---|---|---|---|---|
| X-6 | — | 데워진 → 새 파드 | — | — | — | — | — | — |
| X-7 | — | N₀ → N | — | — | — | — | — | — |
| X-8 | — | 풀 5 → 10 | — | — | — | — | — | — |
| X-10 | — | 기본 → SKEW/LONG_NAMES | — | — | — | — | — | — |
| X-2 | — | Serial → Parallel · G1 | — | — | — | — | — | — |
| X-3 | — | 1 코어 둘 → 2 코어 하나(G1 · Z · Shenandoah) | — | — | — | — | — | — |
| X-4 | — | 힙 50 → 60 · 70% | — | — | — | — | — | — |
| X-5 | — | GC 로그(지표 창 vs 사건) | — | — | — | — | — | — |
| X-9 · X-11 ~ X-14 | — | (새 도구 뒤) | — | — | — | — | — | — |

---

## 6. 남은 것

1. **실습 클러스터 깨우기**(§1) — agent1 의 `K3S_URL` 확인부터. 관측 스택(`PROM`)이 어디 있는지, k6 를 어디서 돌릴지 정한다. 없으면 ② 의 서버 쪽 열이 빈다.
2. **① → ② 실행** — `ops/60` 으로 §5-1, `ceiling browse` · `ceiling order` · `ops/58` 로 §5-2 와 docs/91 §4-1. `EVENT=alloc` · `lock` 은 처음 한 번 표가 나오는지부터(L-4 · L-5).
3. **새 도구**(③ 의 6행이 기다린다):
   1. ~~`ops/53` 의 JVM 측정 모드~~ → ✅ 2026-11-17 `ops/53 jvm load|default|status`(운영 거절 · 원래 값은 주석 · 한 번의 롤아웃 · `ops/28` 경고 · 시험 `LoadK6ScriptTest` +3 · `DeployLoadOverrideScriptTest` +1, 변이 확인 5가지). X-2 ~ X-5 가 §4-2 로 옮겨졌다. L-11 의 cooperative 비교(`KAFKA_REBALANCE_PROTOCOL`)는 이 모드 밖이라 남는다.
   2. 회차 묶음 비교(Welch) — X-1.
   3. k6: 상태 코드별 지연(X-11), 로그인 · 토큰 갱신 시나리오(X-12).
   4. 일괄 쓰기 측정(X-13), 이미지 비교(X-9 · X-14).
4. **판단 항목**(측정 뒤): `ExitOnOutOfMemoryError` 를 둘지(X-4), 요청마다 만드는 `ObjectMapper`(X-12), readiness 전 데우기(X-6), 1 코어 둘 vs 2 코어 하나(X-3).
5. 운영의 GC 종류 · 평시 GC 부담 기록(F-6)은 docs/91 §4-3 에.
