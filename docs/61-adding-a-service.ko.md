# 61. 새 서비스 추가 체크리스트

[日本語](61-adding-a-service.md) | **한국어**

새 백엔드 서비스(예: `review-service`)를 이 저장소에 더할 때 **등록해야 하는 곳 전부**다. 가장 최근에 추가된
`delivery-service` 가 저장소 어디에 등록돼 있는지 전수로 찾아 옮겼다 — 하나라도 빠지면 대개 **조용히** 틀어진다
(이미지가 안 만들어짐·배포에서 빠짐·서비스 간 호출이 403·로그가 안 모임). 각 줄의 "빠지면" 이 그 증상이다.

> 기준 서비스를 하나 골라 복사해서 시작하는 것이 가장 빠르다 — DB·Kafka 가 있으면 `delivery-service`,
> 없으면 `saga-projection`(DB 없음) 이 가깝다. 아래 표의 모든 곳에서 기준 서비스 이름을 grep 하면 된다:
>
> ```bash
> grep -rln "delivery-service" --exclude-dir={node_modules,build,.git,frontend,docs} . | grep -v "^./delivery-service/"
> ```

---

## 1. 코드

| # | 할 일 | 어디 | 빠지면 |
|---|---|---|---|
| 1-1 | 모듈 등록 | `settings.gradle` 의 `include('…')` | 빌드·테스트에 없다 |
| 1-2 | 의존성 — `implementation project(':common')`, Boot 스타터. **버전을 적지 않는다**(Boot BOM 이 정한다) | `<서비스>/build.gradle` | 버전을 박으면 Boot 를 올려도 따라가지 않는다 — Tomcat 10.1.34 고정이 보안 패치를 막고 있었다(1f1031e) |
| 1-3 | 설정 — `application.yml`·`application-local.yml`(포트, `spring.config.import` 로 `application-common.yml`, SSL keystore, DB, Kafka) | `<서비스>/src/main/resources/` | 기동 실패 |
| 1-4 | 스키마는 Flyway — `db/migration/V1__…sql`, `ddl-auto: validate` | 같은 곳 | 되돌릴 수 없는 변경은 `-- ROLLBACK-UNSAFE:` 선언(B-54), 넓히기만 하면 `-- ROLLBACK-SAFE:` |
| 1-5 | 신원은 게이트웨이가 검증한 `User-Id` 헤더만 — 본문의 id·email 을 믿지 않는다 | 컨트롤러 | 남의 데이터 접근(IDOR) |
| 1-6 | 사람에게 보일 오류는 `ClientMessage.of(e)`·`StateConflictException` — `IllegalStateException` 메시지는 운영자용 | 컨트롤러·서비스 | 내부 메시지 노출 |
| 1-7 | 사용자에게 보이는 문구의 일본어 | `src/main/resources/i18n/client-text.ja.txt` | `ClientTextCoverageTest` 실패 |
| 1-8 | 이벤트 발행은 아웃박스(common) — 트랜잭션 안에서 행을 쓰고 발행기가 보낸다 | 서비스 | DB 는 커밋됐는데 이벤트가 없음(또는 반대) |
| 1-9 | 🔴 **실제 DB 로 도는 JPA 테스트** 하나 이상 — 요청마다 트랜잭션을 따로 열고 다음 요청에서 읽어 본다 | `src/test` (H2: `CartPersistenceJpaTest` 참조) | 목 저장소 테스트는 "저장이 안 됨"·"읽기 실패(기본 생성자 없음)"를 못 잡는다 — 둘 다 운영에서 처음 드러났다 |
| 1-10 | 엔티티에 `@NoArgsConstructor(access = PROTECTED)` | 모든 `@Entity` | 쓰기는 되고 읽기가 전부 실패(B-69) |
| 1-11 | 브라우저로 나가는 TSID(`Long` 18~19자리)는 **문자열**로 | 응답 DTO(`@JsonSerialize(using = ToStringSerializer.class)`) | JS 가 끝자리를 반올림 — 없는 id 로 요청(리뷰 좋아요 1→0) |

## 2. 게이트웨이·서비스 간 호출

| # | 할 일 | 어디 | 빠지면 |
|---|---|---|---|
| 2-1 | 외부 라우트 — `id`·`uri: lb://<서비스>`·`Path=/<서비스>/**`, 인증 필요 여부, 서킷 브레이커 `fallbackUri` | `gateway-server/src/main/resources/application-local.yml` 의 `spring.cloud.gateway.server.webflux.routes`(옛 `spring.cloud.gateway.routes` 가 아니다 — `GatewayPropertiesLocationTest`) | 404. id 가 겹치면 뒤의 것이 조용히 사라진다(B-62) |
| 2-2 | 공개하면 안 되는 경로(actuator·api-docs·`/internal/**`)가 라우트에 새지 않게 | 같은 곳 | 내부 API 외부 노출 |
| 2-3 | 내부 API 는 `InternalCallGuard.allowed(...)`·관리자는 `isAdmin(User-Role)` | 컨트롤러 | 누구나 호출 |
| 2-4 | mTLS 허용 주체에 서비스 이름 추가 | `k8s/10-configmap.yaml` 의 `INTERNAL_AUTH_ALLOWED_SUBJECTS` | 이 서비스가 부르는 내부 호출이 전부 403(enforce) |
| 2-5 | 이 서비스가 부르는·불리는 내부 경로의 허용 규칙 | 같은 파일 `INTERNAL_AUTH_PATH_RULES` (`경로=서비스\|서비스;…`) | Feign 이 부르는 내부 경로가 규칙에 없으면 B-67 실패, 규칙이 틀리면 403 |
| 2-6 | Feign 클라이언트는 `url: ${feign.<대상>-url}` 과 타임아웃(`FeignTimeoutBindingTest` 참조) | `client/*Client.java`, yml | 무한 대기 |

## 3. 쿠버네티스

| # | 할 일 | 어디 | 빠지면 |
|---|---|---|---|
| 3-1 | Deployment·Service — 기준 서비스 파일을 복사(`48-delivery-service.yaml`). 번호는 다음 빈 번호 | `k8s/4x-<서비스>.yaml` | — |
| 3-2 | 그 파일의 필수 요소: `EUREKA_INSTANCE_HOSTNAME`(인증서 SAN 과 같은 서비스 DNS), `JAVA_TOOL_OPTIONS`(truststore + `/app/identity/keystore.p12`), `SERVER_SSL_CLIENT_AUTH: want`, `tls-keystore`·`tls-id` 볼륨, Reloader 주석 `secret.reloader.stakater.com/reload: tls-id-<서비스>`, 프로브, 리소스 | 같은 파일 | mTLS 신원 없음(호출자가 `localhost` 로 찍힘), 인증서 갱신 뒤 재시작 안 됨(B-68) |
| 3-3 | 리소스 목록에 추가 | `k8s/kustomization.yaml` | 배포에서 빠진다 |
| 3-4 | 서비스별 설정값(스위치·주소) | `k8s/10-configmap.yaml` — 서비스 매니페스트의 `env` 에 박지 않는다 | 오버레이로 못 바꾼다 |
| 3-5 | 비밀 | `k8s/20-secret.example.yaml`(`REPLACE_ME`) + 운영의 `k8s/20-secret.yaml`(gitignore) | 기동 실패. 🔒 실제 값은 커밋하지 않는다 |
| 3-6 | NetworkPolicy — 지금 정책은 `default-deny-ingress` + 같은 네임스페이스 허용이라, 같은 네임스페이스의 새 서비스는 따로 항목이 필요 없다. **다른 네임스페이스에서 들어와야 하면**(예: 관측 스택) 허용 규칙을 더한다. egress 규칙은 아직 없다 | `k8s/95-network-policy.yaml` | 다른 네임스페이스에서의 연결이 막힌다 |
| 3-7 | 서비스 신원 인증서 | `k8s/96-service-identities.yaml` 에 `Certificate id-<서비스>` | B-68 실패, enforce 에서 403 |
| 3-8 | 오토스케일(수평 확장이 안전할 때만) | `k8s/70-autoscaling.yaml` | — |
| 3-9 | 오버레이의 이미지 이름 목록·리소스 패치 | `k8s-overlays/{external,oci,dev,prod}/kustomization.yaml`, 운영의 `k8s-overlays/local/`(gitignore — 직접) | 이미지 태그가 안 바뀐다(옛 이미지로 뜬다) |

## 4. 데이터·메시지

| # | 할 일 | 어디 | 빠지면 |
|---|---|---|---|
| 4-1 | DB 생성(외부 Postgres) | `ops/25-provision-external-postgres.sh` 의 `DATABASES=(…)` | 기동 실패 |
| 4-2 | 마이그레이션 점검 대상 | `ops/32-db-migration.sh` 의 서비스 목록 | `pending`·`drift` 에서 빠진다 |
| 4-3 | Kafka 토픽 — 이름·파티션·DLT, 소비자 그룹. Avro 면 스키마와 직렬화 설정 | 서비스 yml, `AvroWiringGuardrailTest` | 메시지가 안 오거나 역직렬화 실패 |
| 4-4 | 아웃박스를 월 파티션으로 만들면 그 테이블을 파티션 잡에 등록 — `outbox.partitions.tables: <스키마>.<테이블>` | 서비스 `application-local.yml` | V1 이 만든 마지막 달 뒤로는 모든 행이 DEFAULT 로 간다(B-70 이 잡는다) |

## 5. 운영 스크립트

| # | 할 일 | 어디 | 빠지면 |
|---|---|---|---|
| 5-1 | 이미지 빌드 목록 | `ops/19-build-images.sh` 의 `SERVICES` | 이미지가 안 만들어진다 |
| 5-2 | 부분 배포 순서·자바 서비스 목록 | `ops/33-deploy-some.sh` 의 `ORDER`·`JAVA_SERVICES` | `--changed deployed` 가 못 고른다 |
| 5-3 | 최초 배포 목록 | `ops/13-k3s-deploy.sh` 의 `SERVICES` | 처음 올릴 때 빠진다(notification·delivery 가 실제로 빠져 있었다) |
| 5-4 | mTLS 신원 발급·갱신·점검 | `ops/38-issue-service-identities.sh`·`ops/39-cert-manager.sh`·`ops/37-check-mtls.sh` 의 `SERVICES` | 인증서가 없다 |

## 6. CI/CD·관측

| # | 할 일 | 어디 | 빠지면 |
|---|---|---|---|
| 6-1 | 릴리스 후보 이미지 목록 | `.github/workflows/build.yml` 의 `release-candidate` → `services=` | 후보 매니페스트에서 빠진다 |
| 6-2 | GHCR 패키지 권한 — 첫 발행 뒤 패키지 Settings → Manage Actions access → 이 저장소 **Write** | GitHub | CI 이미지 발행이 `permission_denied: read_package` |
| 6-3 | 로그 수집(로컬 관측 스택) | `docker-compose-observability.yml` 의 로그 볼륨 | Loki 에 로그가 없다 |
| 6-4 | 메트릭·경보 — `/actuator/prometheus` 스크랩, 필요하면 `observability/alert-rules.yml` | 관측 설정 | 장애를 모른다 |
| 6-5 | 분산 추적 — Deployment 에 `HOST_IP`(downward API) 다음 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://$(HOST_IP):4318/v1/traces` (docs/63) | `k8s/4x-<서비스>.yaml` 의 `env` | 추적이 파드 자신(localhost)으로 가서 조용히 사라진다(B-59) |
| 6-6 | 문서 — 설계(왜·무엇을 믿고 무엇을 안 믿나), `docs/README.md` 목차 | `docs/` | — |

---

## 7. 끝났는지 확인

```bash
./gradlew :<서비스>:test :common:test          # 가드(B-54·B-67·B-68·B-69 …)가 등록 누락을 잡는다
./gradlew build -x test                           # 전 모듈 컴파일
./ops/19-build-images.sh --only <서비스> --dry-run 2>/dev/null || true
./ops/28-deploy.sh local <버전> --dry-run          # 무엇이 바뀌는지
# 배포 뒤
./ops/37-check-mtls.sh --callers                  # 이 서비스가 자기 이름(CN)으로 부르는지 — localhost 면 3-2 가 빠졌다
./ops/37-check-mtls.sh                            # 경로 규칙 위반 0건
kubectl -n ecommerce logs deploy/<서비스> | grep -E "WARN|ERROR" | tail
```

> ⚠️ 매니페스트(`k8s/`)를 바꿨으면 `ops/33` 만으로는 반영되지 않는다(이미지만 바꾼다). 전체를 `ops/28` 로 올리거나
> `./ops/28-deploy.sh local --config-only` 로 명세만 적용한다(docs/36 §6, f29a42e).
