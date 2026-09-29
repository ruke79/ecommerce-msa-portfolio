# 70. 2FA 비밀 노출 · 평문 저장, 결제와 아웃박스의 원자성

[日本語](70-2fa-secret-and-payment-atomicity.md) | **한국어**

> 작성일: 2026-09-28 · 상태: ✅ 적용(배포 대기)
>
> 발견: 2025-09-14 백업 대조 분석에서 "최신에도 남은 문제" 로 나온 것을 코드로 확인했다.
> 관련: [27 보안 리뷰](27-security-review.ko.md) · [49 결제 제공자 · 환불](49-payment-providers.ko.md) · [21 수평 확장(아웃박스)](21-horizontal-scaling.ko.md)

## 0. 요약

| | 전 | 후 |
|---|---|---|
| 관리자 사용자 목록 `GET /api/admin/getusers` | `User` **엔티티**를 그대로 응답 — **모든 사용자의 2FA(TOTP) 비밀** 포함 | 응답용 DTO(비밀 필드 없음). 엔티티 필드에도 `@JsonIgnore` |
| DB 의 `two_factor_secret` | **평문** | **AES-256-GCM 암호문**(`enc:k1:` 전용 키 / `enc:j1:` JWT 파생 키) |
| 결제 행 + 결제 응답 아웃박스 | **따로 커밋**(트랜잭션 없음) | **한 트랜잭션** — 둘 다 커밋되거나 둘 다 되돌려진다 |
| 가드 | 없음 | 컨트롤러가 `User` 엔티티를 돌려주면 실패 · 실제 트랜잭션(H2)으로 원자성 확인 |

## 1. 2FA 비밀

### 1.1 무엇이 문제였나

TOTP 는 서버와 인증 앱이 **같은 비밀**로 30초마다 6자리를 계산해 맞춰 보는 방식이다. 비밀을 가진 사람은 그 계정의 6자리를 **언제든** 만들 수 있다 —
2단계 인증이 비밀번호 하나로 줄어든다.

- `AdminController.getAllUsers` 가 `List<User>` 를 반환했다. `User.password` 에는 `@JsonIgnore` 가 있었지만 `twoFactorSecret` 에는 없었다 —
  응답 JSON 에 **전원의 비밀**이 실렸다. 관리자 토큰 하나 · 관리자 화면의 XSS 하나 · 프록시 로그 하나로 전원의 2FA 가 무력화된다.
- `UserProfileDto` 에도 `twoFactorSecret` 필드가 있었다(채우지는 않았지만 누군가 한 줄 더하면 샌다).
- DB 에는 평문이었다 — 백업 파일 · DB 덤프가 새면 같은 결과.

### 1.2 고친 것

1. **응답** — `getAllUsers` 는 `UserProfileDto` 목록을 돌려준다. DTO 에서 비밀 필드를 지웠다. 엔티티 필드에 `@JsonIgnore` 를 달았다(두 번째 겹).
2. **가드** — `TotpSecretExposureTest` 가 모든 `@RestController` 의 반환형을 훑어 `User` 엔티티(또는 그 목록)를 돌려주면 실패한다.
   `@JsonIgnore` 하나를 빠뜨리는 순간 새는 구조 자체를 막는다. 되돌려서(반환형을 `List<User>` 로, `@JsonIgnore` 제거) 실패하는 것을 확인했다.
3. **저장 암호화** — `TotpSecretConverter`(JPA `AttributeConverter`)가 쓸 때 암호화, 읽을 때 복호화한다. 엔티티 코드는 평문만 본다.

### 1.3 왜 해시가 아니라 암호화인가

리프레시 토큰은 해시로만 저장한다(docs/68 §10) — 받은 토큰을 해시해 **비교**만 하면 되기 때문이다. TOTP 는 다르다. 서버는 검증할 때마다 비밀 **원문**으로
코드를 **계산**해야 한다. 해시로는 계산할 수 없다. 그래서 되돌릴 수 있는 암호화(AES-256-GCM, IV 무작위, AAD 고정)를 쓴다.

### 1.4 키 — 새 비밀 없이 배포되고, 나중에 JWT 비밀을 바꿔도 잠기지 않게

| 설정 | 암호문 표시 | 비고 |
|---|---|---|
| `TOTP_ENCRYPTION_KEY`(base64 32바이트) | `enc:k1:` | **권장**. `openssl rand -base64 32` → `k8s/20-secret.yaml` |
| 없음 | `enc:j1:` | JWT 비밀에서 용도별 파생(`HMAC-SHA256(JWT 비밀, "totp-at-rest-v1")`). 새 비밀 없이 바로 배포된다 |

파생 키만 쓰면 **JWT 비밀을 교체하는 순간 2FA 를 켠 사용자가 모두 잠긴다** — docs/30 의 사고 대응에는 JWT 비밀 교체가 들어 있다. 그래서:

- 암호문마다 **어느 키로 잠갔는지**(접두사)를 적는다. 전용 키가 생겨도 `enc:j1:` 은 계속 읽는다.
- 기동할 때 `TotpSecretBackfill` 이 지금 키가 아닌 값(평문 · `enc:j1:`)을 **지금 키로 다시 잠근다**(JDBC, 멱등, 읽은 값과 같을 때만 갱신).
- 그러므로 순서는: **① `TOTP_ENCRYPTION_KEY` 를 넣고 배포 → ② 그 뒤에 JWT 비밀 교체.** 한 번 넣은 전용 키는 바꾸지 않는다.

풀 수 없는 값(키가 바뀌었거나 손상)은 **조용히 틀리지 않고 크게 실패**한다 — 틀린 비밀로 검증하면 사용자는 이유도 모른 채 2FA 에 계속 실패한다.
백필은 그 행을 건드리지 않고(사용자 id 만 로그) 나머지를 계속 처리한다.

### 1.5 배포

- 추가 작업 없이 배포해도 동작한다(평문은 그대로 읽히고, 기동 백필이 `enc:j1:` 로 암호화한다). 로그에 "TOTP_ENCRYPTION_KEY 가 없다" 경고가 한 줄 나온다.
- 권장: `k8s/20-secret.yaml` 에 `TOTP_ENCRYPTION_KEY` 를 채우고 배포(`envFrom` 이라 매니페스트 변경 없음).
- 롤백: 옛 버전은 암호문을 그대로 비밀로 써서 **2FA 를 켠 사용자가 로그인하지 못한다.** 롤백이 필요하면 `APP_TOTP_BACKFILL_ENABLED=false` 로는
  되돌릴 수 없다(이미 암호화됨) — 롤백 대신 앞으로 고쳐서 배포한다. 2FA 사용자가 없다면 영향 없음.

## 2. 결제와 아웃박스의 원자성

### 2.1 무엇이 문제였나

`PaymentService.persistPayment` 에 `@Transactional` 이 없었다. 결제 행은 리포지토리의 트랜잭션으로, 결제 응답 아웃박스 행은 `OrderOutboxHelper.saveOrderOutbox`
의 트랜잭션으로 **따로 커밋**됐다. 둘 사이에서 실패하면(직렬화 · DB 순간 장애 · 파드 종료) **결제는 기록됐는데 order-service 로 갈 응답이 없다.**

Kafka 가 같은 요청을 다시 주면 "이미 있음" 분기가 아웃박스를 다시 써서 **우연히** 복구됐다. 재전달이 오지 않으면(오프셋이 이미 커밋됨 · DLT 로 감) 주문은 결제 대기에 멈춘다.
아웃박스 패턴의 전제 — "상태 변경과 이벤트 기록은 같은 트랜잭션" — 가 깨져 있었다.

### 2.2 고친 것

`persistPayment` 에 `@Transactional`. 두 행이 함께 커밋되거나 함께 되돌려진다. 되돌려지면 리스너가 예외를 던져 Kafka 가 다시 주고, 처음부터 다시 쓴다.

### 2.3 테스트 — 실제 트랜잭션으로

모형(mock)으로는 트랜잭션 경계를 볼 수 없다. `PaymentPersistAtomicityTest` 는 H2 · 실제 JPA · 실제 스프링 트랜잭션 프록시로:

- 정상 — 두 행 모두 남는다.
- 아웃박스 쓰기를 실패시키면 **결제 행도 남지 않는다.** `@Transactional` 을 지우면 이 테스트가 실패하는 것을 확인했다.
- 되돌려진 뒤 재전달 — 두 행이 한 번씩.

## 3. 테스트

| 테스트 | 수 |
|---|---|
| `TotpSecretCipherTest` — 왕복 · 원문 없음, 파생 키 표시, 전용 키 추가 뒤에도 옛 값 읽기, 옛 평문 읽기, 실패는 크게, 키 형식, 변환기 | 7 |
| `TotpSecretBackfillTest`(H2 · 실제 SQL) — 평문 · 파생 키 → 전용 키, 멱등, 풀 수 없는 행은 그대로 | 3 |
| `TotpSecretExposureTest` — 엔티티 JSON 에 비밀 없음, DTO 에 필드 없음, 컨트롤러가 엔티티를 돌려주지 않음 | 3 |
| `PaymentPersistAtomicityTest`(H2 · 실제 트랜잭션) | 3 |
| `UserServiceApplicationTests` · `PayServiceApplicationTests`(실제 PostgreSQL, `-PwithContainers`) — 기동 · 백필이 실제 스키마에서 돈다 | 기존 |
