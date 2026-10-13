# 79. 운영 데모용 사용자 대량 생성 — 실제로 로그인되는 계정

[日本語](79-demo-users.md) | **한국어**

> **상태(2026-10-08)** — `ops/48-bulk-users.sh` + `ops/tools/generate-demo-users.py`. 상품 대량 등록(`ops/44`, docs/19 §5.1)과 짝이다.
> 목적은 "상품도 사용자도 많은" 상태의 운영 데모와 부하 테스트다. 만든 계정은 **앱의 로그인 판정을 그대로 통과한다**(통합 테스트로 확인, §8).

---

## 0. 요약

```bash
./ops/48-bulk-users.sh estimate            # 행 수 · 용량 · 이벤트가 다 나가기까지 걸리는 시간(기본 10만 명)
./ops/48-bulk-users.sh load                # 확인 → 비밀번호 준비 → 묶음별 적재(다시 실행하면 이어서)
./ops/48-bulk-users.sh status              # 데모 사용자 · 배송지 · user-created 상태 · 쿠폰/채팅 복제본
GATEWAY=https://<게이트웨이> ./ops/48-bulk-users.sh login-check   # 표본이 실제로 로그인되는지
./ops/48-bulk-users.sh purge               # 데모 사용자와 그 흔적만 지운다
USERS=500000 ./ops/48-bulk-users.sh load   # 인원 바꾸기(status · purge 도 같은 값으로)
```

한 사람당 만드는 것:

| 표 | 내용 |
|---|---|
| `users.users` | 로그인되는 행 — `enabled`, BCrypt(`$2a$10$`) 해시, `ROLE_USER`, 2FA 꺼짐 |
| `users.shipping_address` | 기본 배송지 하나(KR 7 : JP 3, 나라는 ISO 코드) — 바로 주문해 볼 수 있게(`NO_ADDRESSES=1` 이면 뺀다) |
| `users.outbox_event` | `user-created` 를 **같은 트랜잭션**에 — 웰컴 쿠폰(coupon) · 채팅 회원(chat) 복제본이 정상 가입과 같은 길로 생긴다(`NO_EVENTS=1` 이면 뺀다) |

---

## 1. 왜 API 가 아니라 SQL 로 바로 넣나

가입 API(`/api/auth/public/signup`)로 10만 명을 만들면 다음 셋에 부딪힌다:

- **게이트웨이의 인증 전 경로 제한**: IP 당 초당 1회(버스트 10). IP 하나로 10만 명이면 약 28시간이 걸린다.
- **유출 비밀번호 검사**: 가입마다 외부 API(HaveIBeenPwned)를 부른다.
- **BCrypt 계산**: 가입마다 한 번씩 돈다.

바로 넣으면 로컬 PG16 에서 10만 명이 **약 17초**였다(§3). 대신 앱이 가입 때 하는 일을 생성기가 같은 모양으로 한다(§2). 통합 테스트는 그 결과를 운영의 판정 코드로 확인한다.

생성기는 외부 라이브러리 없이 psql 스크립트를 stdout 으로 낸다. `ops/48` 은 그것을 PostgreSQL 파드의 psql(`kubectl exec`)로 흘려 넣는다. 그래서 DB 비밀번호도 port-forward 도 psycopg2 도 필요 없다.

---

## 2. 앱과 맞춘 규칙

| 항목 | 값 | 이유 |
|---|---|---|
| id | 예약 범위 `[ID_OFFSET, ID_OFFSET + USERS)`, 기본 70억부터 | 앱의 TSID(약 10^18)와 겹치지 않는다. DB 시퀀스는 없다(TSID 는 앱이 만든다) |
| 이메일 | `demo<번호>@demo.invalid` · 소문자 | 로그인 조회가 정확히 일치해야 한다. `.invalid`(RFC 2606)라 메일이 어디에도 가지 않는다 |
| 비밀번호 | 모두 같은 BCrypt 해시 하나(강도 10, 접두사 없음) | 앱은 `new BCryptPasswordEncoder()`(`{bcrypt}` 접두사 없음)를 쓴다. 해시는 한 번만 만든다 — 한 명씩 만들면 10만 명에 1~2시간 |
| `enabled` · `email_verified` | true | 로그인은 메일 인증 관문으로 `enabled` 를 본다 |
| 역할 | `ROLE_USER` 를 **이름으로** 찾는다 | 시드 id 는 DB 마다 다를 수 있다(V3 은 "없으면 넣기") |
| 가입일 | 8일 ~ 2년 전 사이에 흩는다 | `UserReplicationReconciler` 는 매일 "최근 7일 가입자" 의 이벤트를 다시 적는다. 지금 시각으로 넣으면 다음 날 수십만 건이 한꺼번에 다시 나간다 |
| user-created 페이로드 | `{"userId","image","nickname"}` | `UserOutboxHelper.recordUserCreated` 와 같다. 이메일은 싣지 않는다(docs/48 §6-⑥) |
| 닉네임 | 성명 + 번호 | 화면(채팅 · 리뷰)에서 구분되게 |
| 다시 실행 | 스테이징 표에 COPY → `ON CONFLICT DO NOTHING` | 중간에 끊겨도 같은 명령으로 이어서 넣는다. 이벤트는 "새로 들어간 사람" 것만 만들고, user-created 를 두 번 만들지 않는다 |

---

## 3. 용량 · 속도(실측, 로컬 PG16)

| 항목 | 값 |
|---|---|
| 한 사람당(표 + 인덱스 + 배송지 + 아웃박스) | **약 1,012 바이트** — 10만 명이면 약 0.1 GB, 100만 명이면 약 1 GB |
| 적재 속도 | 10만 명 **약 17초**(트랜잭션당 5,000명, `synchronous_commit=off`) |
| WAL | 적재 중에는 잠깐 그만큼 더 는다 |

상품 200만 개(`ops/44`, 약 4 GB)와 합쳐도 실습 환경(데이터 노드 60 GB)에 넉넉히 들어간다.

---

## 4. 이벤트 흘리기 — 가장 느린 곳

- `user-created` 는 user-service 의 폴러가 보낸다. **레플리카당 10초에 100건**이다(`app.outbox.batch-size=100`, `fixed-delay-ms=10000`).
  - 레플리카 하나면 10만 명에 약 2.8시간, 둘(`FOR UPDATE SKIP LOCKED` 로 나눠 가진다)이면 약 1.4시간.
- **실습 환경에서 빨리 흘리려면** ConfigMap 에 `APP_OUTBOX_BATCH_SIZE: "1000"` 을 넣고 `./ops/28-deploy.sh <환경> --config-only --restart user-service` 를 돌린다. 10배가 된다.
  - 끝나면 되돌린다. 운영에서는 배치를 키우면 트랜잭션 하나가 잠금을 오래 쥔다.
- 발행에 실패한 행은 `FAILED` 로 남고 **자동으로 다시 시도하지 않는다**(UserOutboxScheduler). `./ops/48-bulk-users.sh retry-failed` 가 데모 범위의 것만 `STARTED` 로 돌린다.
- `status` 에서 "쿠폰 수 · 채팅 회원 수 = 사용자 수" 가 되면 복제본까지 끝난 것이다.
- `NO_EVENTS=1` 로 넣으면 복제본이 생기지 않는다. 쿠폰이 없고, 채팅은 처음 쓸 때 임시 닉네임으로 만든다(docs/47 과 같은 상태). 데모에는 권하지 않는다.

---

## 5. 로그인 부하 주의 — "동시 접속을 최대로" 할 때

| 무엇이 | 어떻게 되나 | 대처 |
|---|---|---|
| 게이트웨이의 인증 전 경로 제한(IP 당 초당 1 · 버스트 10) | IP 하나에서 분당 약 60번 로그인하고, 그 뒤는 429. 리프레시(`/api/token/refresh`)도 같은 경로 | 로그인은 시간을 두고 늘리고(램프), 토큰은 다시 쓴다(액세스 15분, 리프레시 14일). 여러 기계(실습 호스트 · VM)에서 나눠 보낸다. 제한값은 바꾸지 않는다 |
| 다중 로그인 감지 | 같은 사용자로 두 번째 세션을 열면 첫 세션이 SSE 로 쫓겨난다 | 가상 사용자 하나에 데모 사용자 하나 |
| `LoginRiskGuard` | **실패한** 로그인을 센다. IP 하나에서 계정 10개가 실패하면 모르는 기기는 메일 코드를 요구받는다 | 맞는 비밀번호만 쓴다(`login-check` 는 401 이면 멈춘다) |
| BCrypt 비교 | 로그인마다 강도 10 비교가 한 번(수십 ms CPU) | 로그인 수는 CPU 예산으로 본다. 동시 접속 수와는 다른 지표 |
| 새 기기 알림 | 첫 기기는 알리지 않는다. 두 번째 기기부터 SECURITY_ALERT 메일 | 수신처가 `.invalid` 이고, 실습 환경은 `MAIL_PROVIDER=logging`(docs/71 §7) |

> 실습 환경(Cloudflare 없음)에서는 게이트웨이가 `CF-Connecting-IP` 헤더를 그대로 믿으므로, 위조하면 제한을 피할 수 있다. 부하를 위해 그것을 쓰지 않는다. 운영은 터널 밖에서 닿지 않는 것이 전제다(`CustomUserKeyResolver`).

---

## 6. 보안

- **데모 계정은 모두 같은 비밀번호다.** 공개 사이트(운영)에 만들면 그 비밀번호 하나로 수만 계정에 들어갈 수 있다. 운영 클러스터에서는 데모가 끝나는 대로 `purge` 한다.
- 비밀번호 다루기:
  - `PASSWORD_FILE`(기본 `~/.ecommerce-msa/demo-users.password`)은 0600 이고, 저장소 밖에만 만든다. 저장소 안이면 거절하고, 권한이 600 이 아니면 멈춘다.
  - 화면 · 명령줄 · 셸 기록에 남기지 않는다. 평문은 stdin 으로만 해시기와 `login-check` 본문으로 가고, 해시는 환경 변수로 생성기에 간다.
- `login-check` 는 응답 본문 · 헤더(ACCESS)를 버린다. 토큰은 출력하지 않는다.
- 이메일이 `.invalid` 라서 쿠폰 · 보안 알림 같은 메일이 실제 누군가에게 가지 않는다.

---

## 7. 삭제(`purge`)

1. users DB: 지울 사람을 먼저 고정한다(범위 **그리고** `@demo.invalid`). 범위를 잘못 줘도 실제 사용자는 지우지 않는다.
2. 아직 안 나간 user-created 를 지운다. 사용자가 없는데 이벤트만 나가면 쿠폰 · 채팅에 유령이 생기기 때문이다.
3. `users.users` 를 가리키는 외래 키를 카탈로그에서 따라가 아래부터 지운다. 데모 중에 생긴 기기 · 알림 · 리뷰 · 위시리스트까지 지워진다. 외래 키 없이 `user_id` 열만 있는 표(`known_device` 등)도 카탈로그에서 찾아 지운다. 모두 한 트랜잭션이다.
4. coupondb 의 `coupon.coupon`, chatdb 의 `chat.member` 를 id 범위로 지운다.
5. **주문(orderdb)은 남는다.** 주문 이력은 지우지 않는다.

---

## 8. 검증

| 테스트 | 지키는 것 |
|---|---|
| `DemoUserGeneratorIntegrationTest`(user-service, `@Tag("containers")`, 5개) | Flyway 로 올린 진짜 users 스키마에 생성기 SQL 을 psql 로 흘려 넣고, **운영의 `CustomAuthenticationProvider` · `UserDetailsServiceImpl` · Hibernate(validate)** 로 읽는다. 로그인 통과 · `ROLE_USER` · 틀린 비밀번호 거절 · 2FA 꺼짐 · 가입일은 7일 전보다 이전 · 사람마다 user-created 하나(STARTED, 소비자 DTO 로 읽힘, 이메일 없음)와 배송지 · 다시 실행해도 같음 · purge 는 범위 안의 `@demo.invalid` 만(범위 안 실제 사용자 · 범위 밖은 남김, 외래 키 없는 기기 행까지) · 형식이 다른 해시(`{bcrypt}…`)면 아무것도 내지 않음 |

**변이 확인** — 아래처럼 되돌리면 각각 테스트가 실패했다:

- `enabled=false` 로 바꾼다
- purge 의 도메인 조건을 뺀다
- `ON CONFLICT` 를 뺀다
- 외래 키 없는 표 정리를 뺀다
- 가입일을 지금으로 바꾼다
- 중복 이벤트 방지를 뺀다

**`ops/48` 리허설** — userdb · coupondb · chatdb 를 마이그레이션으로 올린 PG16 에 kubectl 대역을 끼워 돌렸다. 확인한 것:

- `estimate → load → 다시 load(이어서, 수 그대로) → retry-failed → purge` 가 끝까지 돈다
- 비밀번호 파일은 0600 으로 만들어진다
- purge 는 범위 밖(실제 사용자)의 쿠폰 · 채팅 행을 남긴다
- `login-check` 는 가짜 게이트웨이에 본문을 JSON `{email, password}` 로 보낸다. 출력에는 비밀번호도 토큰도 없다

> Gradle 메모: 통합 테스트는 모듈 밖의 생성기를 실행한다. 그래서 `build.gradle` 에서 user-service test 태스크의 입력으로 선언했다. 선언하지 않으면 생성기를 고쳐도 테스트가 UP-TO-DATE 로 건너뛴다 — 첫 변이 확인이 그래서 모두 "통과"했다.
