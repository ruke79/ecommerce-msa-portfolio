# 29. 트랜잭션 메일 — 발신 파이프라인을 notification-service 로 모으다

[日本語](29-transactional-email.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** user-service 아웃박스는 이제 동작합니다(c31791a). 다만 메일 발행은 아직 아웃박스를 거치지 않습니다.

분산 이메일 서비스(별도 저장소, System Design Interview Ch.23 구현체)의 **발신 절반**을
이 프로젝트에 이식한 기록이다. 메일함·Cassandra·MinIO·Elasticsearch 는 넘어오지 않았다 —
왜 그것들을 가져오지 않았는지가 이 문서의 절반이다.

---

## 1. 무엇이 문제였나

메일을 보내는 코드가 **user-service 안에, 서로 다른 모양으로 두 벌** 있었다.

| | 회원가입 확인 메일 | 비밀번호 재설정 메일 |
|---|---|---|
| 위치 | `RegistrationListener` | `EmailService` |
| 형식 | MimeMessage, HTML + 평문, UTF-8 | `SimpleMailMessage`, 평문 한 줄 |
| 인코딩 | 지정함 | **지정 못 함** — 한국어 제목이 클라이언트에 따라 깨짐 |
| 비동기 | `@Async` + `AFTER_COMMIT` | **동기** — HTTP 요청이 SMTP 왕복을 기다림 |
| 재시도 | 인프로세스 3회, 지수 백오프 | **없음** |
| 실패하면 | ERROR 로그 한 줄, **메일은 영영 사라짐** | `MailException` 이 올라가 **요청 자체가 500** |

같은 서비스가 보내는 두 메일의 모양이 서로 달랐다는 것 자체가 신호다. 확인 메일 쪽은
[docs/27](27-security-review.ko.md) 작업 때 한 번 손봤지만, 재설정 메일은 손대지 않아 그대로
남아 있었다. **문안이 코드 안에 박혀 있으면 이런 어긋남은 반드시 생긴다.**

더 나쁜 것이 두 가지 있었다.

**(1) 재설정 메일 실패는 사용자에게 최악의 상태를 만든다.** `EmailService` 가 던진 예외가
호출자까지 올라가면 토큰 저장 트랜잭션이 롤백된다 — 이면 차라리 낫다. 실제로는 토큰을 먼저
저장하고(`passwordResetTokenRepository.save`) 그 다음에 메일을 보냈으므로, SMTP 가 실패하면
**DB 에는 토큰이 있는데 사용자에게는 링크가 없는** 상태가 됐다.

**(2) 알림 채널이 반쪽이었다.** notification-service 는 SMS 전용이었다. `NotificationType`
에 `smsWorthy` 플래그가 있고 사용자별 수신 설정이 있는데, **메일 채널 자체가 없었다.** 주문
알림은 문자로는 가는데 메일로는 안 갔고, 수신 거부 설정도 문자에만 적용됐다.

---

## 2. 이식한 것 — 그리고 이식하지 않은 것

분산 이메일 서비스에서 가져온 것은 **패턴 하나**다:

```
API 서버 → outgoing-mail 토픽 → mail-worker
                                  ├ 일시적 실패 → 재시도
                                  └ 영구적 실패 → DLQ (재시도 안 함)
```

이 구조가 정확히 이 프로젝트에 없던 것이다. 그래서 이것만 가져왔다.

### 가져오지 않은 것과 그 이유

| 가져오지 않음 | 이유 |
|---|---|
| **Cassandra** (`emails`, `folders`, `unread_emails`) | 메일함 도메인이다. 우리는 메일을 **보내기만** 하고 저장하지 않는다 |
| **MinIO** (첨부파일) | 트랜잭션 메일에 첨부파일이 없다 |
| **Elasticsearch** (메일 전문검색) | 위와 같다. 검색할 메일함이 없다 |
| **수신 SMTP / IMAP** | 우리는 발신 전용이다 |
| **WebSocket 실시간 푸시** | notification-service 가 이미 인앱 알림으로 한다 |

새로 필요해진 인프라는 **없다.** Kafka 도 PostgreSQL 도 이미 있다. 24GB 맥북에서
Cassandra(힙 제대로 주면 2~4GB)와 MinIO 를 더 띄우는 것이 실질적인 차단선이었는데,
그 비용을 하나도 치르지 않고 필요한 것만 얻었다.

### 원본과 다르게 만든 곳

원본의 `OutgoingMailConsumer` 는 Spring Kafka 의 `@RetryableTopic` 을 써서 재시도 토픽
(`outgoing-mail-retry-0/1/2`)을 자동 생성한다. **여기서는 그 방식을 쓰지 않았다.**

이유는 이 저장소가 이미 겪은 문제다([docs/21](21-horizontal-scaling.ko.md)). 컨슈머 안에서
외부 I/O 를 하고 실패를 컨슈머 재시도로 처리하면 **재시도할 때마다 부수효과가 한 번씩 더
일어난다.** 메일에서는 그게 곧 중복 발송이다. 재시도 토픽은 이 문제를 브로커 쪽으로 옮길 뿐
해결하지 않는다 — 재시도 레코드를 소비하는 순간 또 `send()` 를 부른다.

그래서 SMS 와 같은 구조를 택했다. **컨슈머는 DB 에 적기만 하고, 별도 디스패처가 상태를 보며
보낸다.**

```
user-service ──publish──▶ outgoing-mail ──▶ OutgoingMailListener
                                                  │ (저장만)
                                                  ▼
                                            mail_message (PENDING)
                                                  │
                          MailDispatcher ◀────────┘  @Scheduled
                          FOR UPDATE SKIP LOCKED
                                │
                                ├ SENT      → 종료, params 삭제
                                ├ RETRYABLE → 다음 주기
                                └ FAILED    → 종료, params 삭제
```

`FOR UPDATE SKIP LOCKED` 는 아웃박스 폴러와 같은 이유다. 잠금이 없으면 레플리카 둘이 같은
행을 집어 **같은 메일이 두 통** 간다. 확인 메일 두 통은 성가신 정도지만, **비밀번호 재설정
링크가 두 개 살아 있는 것은 보안 문제다.**

---

## 3. 만든 것

### notification-service

| 파일 | 역할 |
|---|---|
| `domain/MailKind` | 메일 종류. `forNotification(NotificationType)` 이 "이 알림을 메일로 보낼 것인가"를 판정 |
| `domain/MailStatus` | PENDING / SENT / RETRYABLE / FAILED |
| `domain/MailMessage` | 메일 아웃박스 행 |
| `mail/MailSender` | 발송 경로 인터페이스 (`SmsSender` 와 대칭) |
| `mail/MailResult` | SENT / RETRYABLE / PERMANENT |
| `mail/LoggingMailSender` | **기본값.** 실제로 보내지 않고 기록만 |
| `mail/SmtpMailSender` | JavaMailSender 위임 + 실패 분류 |
| `mail/MailTemplates` | 문구 (한국어·일본어, HTML + 평문) |
| `mail/EmailAddresses` | 주소 검증·마스킹 |
| `repository/MailMessageRepository` | `claimForSend` (SKIP LOCKED), `scrubParamsOlderThan` |
| `service/MailService` | 큐 등록 (멱등) |
| `service/MailDispatcher` | 폴러 + 링크 정리 작업 |
| `listener/OutgoingMailListener` | `outgoing-mail` 컨슈머 |
| `config/MailConfig` | 발송기 선택 |
| `db/migration/V3__mail_channel.sql` | `mail_message` 테이블 + 설정 컬럼 2개 |

### common

`message/dto/request/OutgoingMailRequest` — 두 서비스가 공유하는 계약.

### user-service

| 파일 | 변경 |
|---|---|
| `message/producer/OutgoingMailProducer` | 신규. 비트랜잭셔널·멱등 프로듀서 |
| `registration/RegistrationListener` | SMTP 호출 → Kafka 발행 |
| `service/EmailService` | SMTP 호출 → Kafka 발행 |
| `build.gradle` | **`spring-boot-starter-mail` 제거** |
| `application-local.yml` | `spring.mail.*` 블록 제거 |

의존성까지 지운 이유: 남겨 두면 "여기서도 메일을 보낼 수 있다"는 오해가 남고, JavaMailSender
자동 구성이 살아 있어 실수로 다시 직접 호출하는 코드가 들어올 수 있다.

---

## 4. 설계 판단 몇 가지

### 4.1 본문을 명령에 싣지 않는다

`OutgoingMailRequest` 는 **종류와 값만** 나른다. 제목·HTML 은 없다.

```json
{ "kind": "REGISTRATION_CONFIRM",
  "params": { "confirmationUrl": "https://..." },
  "locale": "ko-KR" }
```

발행자가 렌더링해 보내면 문안이 서비스마다 흩어진다 — **정확히 지금 고치고 있는 문제**다.
SMS 가 이미 `SmsTemplates` 한 곳에서 렌더링하고 있고, 상용 트랜잭션 메일 서비스(SES·SendGrid)
의 템플릿 방식도 같다.

### 4.2 `params` 는 자격증명이다

이게 이 설계에서 가장 신경 쓴 부분이다.

`confirmationUrl` 은 그 링크만으로 계정을 활성화할 수 있다. `resetUrl` 은 비밀번호를 바꿀 수
있다. user-service 는 토큰을 **SHA-256 해시로만** 저장하는데
([docs/27](27-security-review.ko.md)), 여기 원문 링크가 남아 있으면 그 방어가 통째로 무의미해진다.

노출 경로를 하나씩 막았다.

| 경로 | 대응 |
|---|---|
| 로그 | `OutgoingMailRequest.toString()` 이 params 를 **키 이름만** 남기고 가린다. `MailMessage.toString()` 도 같다. 발송기는 본문 길이만 찍는다 |
| Kafka 토픽 | mTLS 는 이미 켜져 있다. **토픽 보존 기간을 짧게 잡을 것** — §6 참조 |
| DB (정상 경로) | 종료 상태(`SENT`/`FAILED`)에 닿는 즉시 `clearParams()` 가 NULL 로 만든다 |
| DB (비정상 경로) | 끝내 발송되지 못한 행은 24시간 뒤 `scrubStaleParams()` 가 지운다. 링크 자체가 24시간이면 만료되므로 그 뒤로 들고 있을 이유가 없다 |

행 자체는 남긴다. "왜 이 사용자에게 메일이 안 갔나"는 나중에도 볼 수 있어야 한다. 지우는 것은
자격증명뿐이다.

**멱등 키에 토큰을 쓰지 않는 것**도 같은 이유다. 토큰을 키로 삼으면 그 값이
`mail_message.source_event_id` 컬럼과 로그에 평문으로 남는다.

### 4.3 일시 실패와 영구 실패

이 구분이 흐려지면 두 방향 모두로 망가진다.

- **전부 재시도** → 존재하지 않는 주소로 계속 보낸다. 하드 바운스가 쌓이면 수신 서버들이
  **발신 도메인을 스팸으로 학습**한다. 정상 메일까지 스팸함으로 간다.
- **전부 포기** → 메일 서버가 잠깐 흔들린 것뿐인데 사용자는 확인 메일을 영영 못 받는다.

`SmtpMailSender.classifyOne` 은 **확실한 신호부터** 본다.

1. **SMTP 응답 코드** — 있으면 가장 정확하다. 5xx 영구, 4xx 일시
2. **`SendFailedException.getInvalidAddresses()`** — 서버가 "이 주소는 유효하지 않다"고 말한 것
3. **네트워크 예외** — `ConnectException` / `SocketTimeoutException` / `UnknownHostException` 은 명백히 일시적

1번은 **리플렉션으로 읽는다.** 코드를 담고 있는 `SMTPSendFailedException` 은 jakarta.mail
*API* 가 아니라 구현체(Angus Mail)의 클래스라서, 컴파일 시점에 참조하면 구현체를 갈아끼울 수
없게 된다. 그렇다고 포기하면 4xx/5xx 를 구분할 가장 정확한 근거를 잃는다. 그래서 **있으면 쓰고
없으면 다음 근거로 넘어가는** 형태로 읽는다.

### 4.4 재시도 상한이 SMS 보다 크다 (5회 vs 3회)

SMS 는 시도할 때마다 요금이 나가므로 3회가 맞다. 메일은 그렇지 않고, 더 중요한 이유가 있다 —
**그레이리스팅**. 일부 수신 서버는 처음 온 발신자를 일부러 한 번 거절하고 재시도를 기다린다
(스팸 봇은 재시도하지 않는다는 전제). 상한이 작으면 그런 서버로는 첫 메일이 영영 안 간다.

### 4.5 수신 거부로 막을 수 없는 메일

`MailKind.isTransactional()` 이 갈라 놓는다.

가입 확인과 비밀번호 재설정은 **사용자가 방금 직접 요청한 결과**다. 이걸 수신 거부로 막으면
사용자가 스스로 계정에 못 들어간다 — 수신 거부의 취지와 정반대다. 한국 정보통신망법·일본
특정전자메일법이 요구하는 수신 동의도 **광고성** 메일에 대한 것이지, 본인이 요청한 정보성
메일에 대한 것이 아니다.

보안 알림(`SECURITY_ALERT`)이 설정을 무시하는 것은 별개의 이유다: 공격자가 먼저 알림을 꺼
두고 작업할 수 있어서는 안 된다. SMS 도 같은 규칙이다.

반대 방향도 있다. **채팅 메시지와 친구 접속은 메일로 보내지 않는다.** 활발한 대화 하나가
메일함을 수십 통으로 채우고, 그건 사용자에게 스팸이며 수신 서버가 우리를 스팸 발신자로
학습하게 만든다.

### 4.6 메일 주소를 어디서 얻나

notification-service 는 사용자 테이블이 없다. 경로가 셋이었다.

1. **user-service 에 매번 물어보기** — 알림마다 동기 호출이 붙는다
2. **사용자 생성 이벤트를 구독해 복제** — 정공법이지만 기존 사용자는 채워지지 않는다
3. **게이트웨이가 붙여 주는 `User-Email` 헤더** ← 택함

3번을 쓴다. 그 헤더는 게이트웨이가 JWT 에서 꺼내 붙이고, 클라이언트가 보낸 같은 이름의 헤더는
게이트웨이가 지운다([docs/27](27-security-review.ko.md)) — 위조할 수 없다. 사용자가 알림 목록
(`GET /api/notifications`)이나 설정 화면을 한 번이라도 열면 채워진다.

**한계는 분명하다.** 로그인 후 알림 API 를 한 번도 부르지 않은 사용자에게는 알림 메일이
나가지 않는다. 가입 확인·비밀번호 재설정은 주소가 명령에 실려 오므로 영향받지 않는다.

배지 조회(`/unread-count`)에는 이 기록을 넣지 않았다. 화면마다 불리는 경로라 조회가 하나 더
붙는다.

### 4.7 기본 발송기가 `logging` 인 이유

SMS 와 같은 형태지만 근거가 다르다. SMS 는 건당 과금이라 "모르는 사이 요금이 나가는" 것이
문제였다. 메일은 대개 공짜지만 **되돌릴 수 없고 평판이 남는다** — 설정이 덜 된 채 배포돼
테스트 데이터의 주소로 메일이 나가면 그 하드 바운스가 발신 도메인에 그대로 쌓이고, 한 번
스팸으로 분류된 도메인을 되돌리는 데는 몇 주가 걸린다.

`MailConfig` 는 `provider=smtp` 인데 `from` 이 비어 있으면 **기동을 막는다.** From 이 비면
JavaMail 이 SMTP 계정 주소로 대체하는데, 그러면 SPF/DKIM 정렬이 깨져 정상 메일이 스팸으로
분류된다.

---

## 5. 남아 있는 것 (정직하게)

### 5.1 발행 실패 창 — user-service 의 아웃박스가 죽어 있다

`OutgoingMailProducer.publish()` 가 실패하면(브로커에 닿지 못하면) 메일은 여전히 사라진다.
예전보다 창이 훨씬 좁을 뿐이다 — 같은 클러스터 안의 브로커에 쓰는 것과 외부 SMTP 공급자에
왕복하는 것은 실패 확률이 다르다.

이 창을 완전히 닫으려면 사용자 저장과 **같은 트랜잭션**에 메일 요청을 적어야 한다.
user-service 에 아웃박스가 있긴 하다:

- `message/model/OutboxEvent` — 엔티티 있음
- `message/repository/jpa/OutboxEventRepository` — 리포지토리 있음
- `message/OutboxScheduler` — **인터페이스만 있고 구현체가 없다**
- `UserServiceImpl:510` — `//outboxEventRepository.save(event);` **주석 처리됨**

즉 **껍데기만 있고 동작하지 않는다.** order-service·pay-service 에는 동작하는 릴레이가
있으므로(`PaymentOutboxScheduler`, `OrderOutboxScheduler`) 그것을 본떠 살리면 된다.
이번 작업 범위 밖이라 손대지 않았다.

### 5.2 바운스 처리가 없다

`SENT` 는 "우리 MTA 가 접수했다"까지만 뜻한다. 수신함 도달은 **바운스 메일과 피드백 루프**를
받아야 안다. SMS 의 DLR 과 같은 구조이고, 같이 로드맵에 남는다.

이게 없으면 "보냈다고 나오는데 안 왔다"를 추적할 수 없다. 상태 이름이 그 사실을 숨기지
않도록 `SENT` 의 의미를 코드 주석과 이 문서에 명시해 두었다.

### 5.3 토픽 보존 기간

`outgoing-mail` 토픽에는 살아 있는 확인 링크가 흐른다. **보존 기간을 짧게 잡아야 한다.**
토큰이 24시간이면 만료되므로 그보다 짧으면 충분하다.

```bash
kafka-configs --alter --entity-type topics --entity-name outgoing-mail \
  --add-config retention.ms=3600000   # 1시간
```

DLT(`outgoing-mail.DLT`)도 같다.

### 5.4 프론트엔드 수신 거부 UI

`PATCH /api/notifications/preferences` 가 `emailEnabled` 를 받는다.
`notificationApi.updatePreferences(patch)` 가 그대로 통과시키므로 API 는 준비돼 있지만,
설정 화면 자체가 아직 없다.

---

## 6. 배포 순서

1. **notification-service 먼저.** V3 마이그레이션이 `mail_message` 를 만들고
   `outgoing-mail` 컨슈머가 뜬다.
2. **그 다음 user-service.** 먼저 올리면 발행한 메시지를 받을 컨슈머가 없다. 토픽에 쌓여
   있다가 나중에 처리되므로 유실은 아니지만, 그사이 가입한 사용자는 확인 메일을 늦게 받는다.
3. 토픽 보존 기간 설정 (§5.3).
4. 실제 발송을 켤 때: `MAIL_PROVIDER=smtp` + `MAIL_FROM` + `MAIL_HOST`/`MAIL_USERNAME`/
   `MAIL_PASSWORD` (k8s/47, k8s/20-secret). **셋 중 하나라도 빠지면 기동이 실패한다** —
   반쯤 설정된 상태로 조용히 도는 것보다 낫다.

`MAIL_HOST` 는 user-service 의 Secret 에서 notification-service 로 **옮겨졌다.** 메일을
실제로 보내는 쪽이 접속 정보를 갖는 것이 맞다.

---

## 7. 검증

| | |
|---|---|
| 이번에 추가한 단위 테스트 | 50건 (주소·문구·아웃박스·디스패처·채널 정책) |
| 이번에 추가한 컨테이너 테스트 | 8건 (실제 PostgreSQL 위 기동 검증 + 전 구간) |
| notification-service | 89건 |
| 백엔드 전체 | **270건 실행 / 0 실패 / 0 건너뜀** (이 작업 전 212건) |
| 프론트엔드 | 136건 (변경 없음, 회귀 확인용으로 재실행) |

**보호 장치를 일부러 걷어내 테스트가 실제로 잡는지 확인했다.** `escape()`, `safeUrl()`,
종료 상태의 `clearParams()` 를 각각 무력화하자 7건이 실패했다 — 통과하는 테스트가 아니라
**막아 주는 테스트**임을 확인한 것이다.

전 구간 테스트(`MailChannelIntegrationTest`)가 검증하는 것은 로직이 아니라 **이음매**다.
user-service 가 만드는 `OutgoingMailRequest` 를 실제로 Jackson 으로 직렬화해 리스너에 넣는다 —
필드 이름이 하나라도 어긋나면 여기서 걸린다. 이 계약은 두 서비스가 **따로 배포되므로**
컴파일러가 지켜 주지 않는다.

### 작업 중 기동 테스트가 잡은 버그

`MailConfig` 의 빈 이름을 `mailSender` 로 두었더니 스프링 부트의
`MailSenderPropertiesConfiguration` 이 같은 이름으로 `JavaMailSenderImpl` 을 등록해
`BeanDefinitionOverrideException` 으로 **애플리케이션이 아예 뜨지 않았다.**
`notificationMailSender` 로 바꿔 해결했다.

같이 드러난 것: 부트의 조건은 "`spring.mail.host` 프로퍼티가 존재하는가"인데, yml 이
`${MAIL_HOST:}` 처럼 빈 기본값을 주면 **빈 문자열도 '존재'로 친다.** 그래서 JavaMailSender
빈은 만들어지지만 호스트가 없다. `MailConfig` 가 호스트를 따로 검사하도록 했다 — 아니면
`provider=smtp` 로 켰을 때 매 발송이 알 수 없는 오류로 실패하며 큐만 쌓인다.

---

## 7-1. 운영에서 드러난 것 — "성공했는데 안 온다" 의 해부

재배포 뒤 "가입은 됐는데 메일이 안 온다" 는 보고가 들어왔다. 추적해 보니 **버그가 셋**이었고,
셋 다 각각은 조용했다. 조용한 실패가 겹치면 증상이 하나로만 보인다는 것이 요점이다.

### ① 안 채운 설정이 기동 검사를 통과했다

`k8s/20-secret.example.yaml` 을 복사해 쓰면서 `MAIL_HOST` 를 안 채웠다. 그 값
`"REPLACE_ME_smtp_host"` 는 **비어 있지 않으므로** `isBlank()` 검사를 통과했고, 서비스는
이렇게 찍으며 정상 기동했다:

```
INFO  c.p.n.config.MailConfig - 메일 발송기: smtp (실제 발송) from=…
```

**비어 있는 설정은 막으면서 안 채운 설정은 통과시킨 것이 문제였다.** 후자가 훨씬 흔한
실수인데도 그랬다 — 비우려면 지워야 하지만, 안 채우는 것은 아무것도 안 하면 된다.

→ `Placeholders.rejectUnfilled` 가 `REPLACE_ME` 를 기동 시점에 잡는다. 실패 시점이 원인에
가까워야 파드가 CrashLoopBackOff 로 눈에 띄고 메시지가 어느 키인지 바로 말해 준다.
🔒 메시지에는 **키 이름만** 넣는다 — 이 검사가 보는 값에는 `MAIL_PASSWORD` 가 섞여 있고
예외 메시지는 로그로 나간다.

### ② 🔴 재시도 예산이 20초 만에 탔다 — 재시도가 이름뿐이었다

`MailDispatcher` 는 상한을 5회로 두고 그 이유를 주석에 적어 두었다:

> 그레이리스팅을 쓰는 수신 서버는 처음 온 발신자를 일부러 한 번 거절하고 재시도를 기다린다 —
> 상한이 작으면 그런 서버로는 첫 메일이 영영 안 간다.

그런데 **행별 백오프가 없었다.** `claimForSend` 는 상한에 닿지 않은 행을 5초마다 무조건 다시
집었다. 운영 로그가 그대로 보여 준다:

```
04:12:10 [MAIL:retryable] … 04:12:15 … 04:12:20 … 04:12:25 … 04:12:30 재시도 상한 도달, 포기
```

그레이리스팅은 보통 **수 분**을 기다려야 풀린다. **주석이 막겠다고 한 바로 그 실패가 그대로
일어났다.** SMS 도 같은 모양이었다(3회 × 5초 = 15초).

**상한은 "몇 번"이고 백오프는 "언제"다. 둘 중 하나만 있으면 재시도는 무의미하다** — 같은
순간에 다섯 번 때리는 것은 한 번 때리는 것과 결과가 같고, 원인이 일시적일수록 더 그렇다.

→ `V5__retry_backoff.sql` 이 `next_attempt_at`(메일)·`sms_next_attempt_at`(SMS)을 더하고,
선점 질의가 `(… is null or … <= :now)` 로 좁힌다. 간격은 시도마다 두 배가 된다 —
메일 1·2·4·8분(창 ≈15분), SMS 30초·1분. `is null` 을 함께 보는 것은 생략할 수 없다:
새 행과 이 컬럼이 없던 시절의 행이 모두 null이라, 빠뜨리면 **그 행들이 영영 선택되지 않는다.**

### ③ 포기할 때 시도 횟수를 두 번 셌다

`recordAttempt(RETRYABLE)` 뒤에 `recordAttempt(FAILED)` 를 불러 한 번의 발송이 두 번으로
기록됐다. `attempts` 가 상한보다 1 큰 값으로 남아, 나중에 보는 사람이 상한을 넘긴 것으로
읽는다. → 시도 횟수를 올리지 않는 `giveUp` / `giveUpSms` 를 따로 두었다.

### 왜 이 셋이 하나의 증상으로만 보였나

| 단계 | 그때 보인 것 | 실제 |
|---|---|---|
| 기동 | `메일 발송기: smtp (실제 발송)` | 호스트가 자리 표시자 |
| 큐 등록 | `메일 큐 등록: kind=REGISTRATION_CONFIRM` | 정상 |
| 발송 | (ERROR 아닌) WARN 5줄, 25초 | DNS 조회 실패 |
| 결과 | `mail_message.status = FAILED` | 아무도 안 봄 |

어느 단계도 거짓말을 하지 않았지만, 어느 단계도 **찾아 나서지 않으면 보이지 않았다.**
`LoggingMailSender` 가 dry-run 에도 `MailResult.sent()` 를 돌려주는 것(§5)과 같은 종류의
문제다 — 성공으로 보이는 실패가 가장 오래 산다.

---

## 7-2. 메일이 안 왔을 때 사용자가 빠져나올 길

위 셋을 고치면 이번 증상은 사라진다. 그래도 메일은 **언젠가 반드시** 안 간다 —
스팸함으로 가고, 수신 서버가 거절하고, 사용자가 주소를 잘못 친다. 그때 사용자가 스스로
빠져나올 수 있는지를 점검했더니, 백엔드는 다 되어 있는데 **화면이 없었다.**

### 무엇이 없었나

| | 상태 |
|---|---|
| `POST /api/auth/public/resend-verification` | 구현돼 있음. **프론트에서 부르는 곳 0건** |
| `/verification-failed?reason=…` 리다이렉트 | 서버가 보냄. **프론트에 라우트 없음** → 라우터 오류 화면 |
| `/signin?verified=1` | 서버가 보냄. **아무도 안 읽음** → 인증 성공을 알 수 없음 |

로그인 실패 문구는 "확인 메일 재발송을 이용해 주세요" 라고 안내하는데 이용할 화면이
없었다. 링크는 24시간이면 만료되고 같은 주소로 재가입도 막히므로, 메일을 못 받은 계정은
**막다른 길**이었다. `/forgot` 라우트가 없어 비밀번호를 되찾을 수 없던 것과 같은 구멍이다.

→ `ResendVerification` 컴포넌트를 만들어 `/verification-failed` 화면과 로그인 실패 자리
양쪽에 붙였다. `?verified=1` 도 읽어 성공을 알린다.

### 🐛 그리고 상태 코드 하나가 사용자에게 거짓말을 시키고 있었다

`POST /api/auth/public/register` 의 중복 검사가 **`200 OK`** 를 돌려줬다. 프론트의
`useMutation` 은 2xx 를 성공으로 보므로 `onSuccess` 가 실행됐고, 화면에는
**"인증 메일을 보냈습니다"** 토스트가 뜬 뒤 로그인 화면으로 넘어갔다. 본문에 담긴
`"The same user Id exists"` 는 아무도 보지 못했다.

서버는 사실을 말했지만 **그 사실이 닿을 수 없는 자리에 놓여 있었다.** 409 로 바꿨고,
문구도 영어 진단문에서 "이미 가입된 이메일입니다. 로그인해 주세요. 가입 확인 메일을 받지
못했다면 확인 메일 재발송을 이용해 주세요."로 바꿨다 — 이제 실제로 사용자가 읽는다.

### 누설에 대해

- **가입 엔드포인트는 주소의 가입 여부를 드러낸다.** 막으려면 "확인 메일을 보냈습니다"만
  답하고 기존 사용자에게 안내 메일을 보내는 흐름이 필요한데, 그러면 오타로 남의 주소를
  넣은 사람이 왜 가입이 안 되는지 영영 알 수 없다. 대가가 더 크다고 보고 드러내되,
  **인증 여부는 구분하지 않는다** — 미인증 계정인지 알려주면 그건 새로운 누설이다.
- **재발송 엔드포인트는 아무것도 드러내지 않는다.** 미가입·인증됨·미인증 모두 같은 200 과
  같은 문구다. 그래서 화면도 "보냈는지"를 추측하지 않고 서버 문구를 그대로 쓴다.
- **로그인 실패의 `code`는 비밀번호가 맞은 뒤에만 나간다.** 화면이 재발송 폼을 띄울지
  결정하려면 신호가 필요한데, 한국어 문구를 비교하면 문구를 다듬는 순간 조용히 깨진다.
  그래서 `code: "email_not_verified"` 를 내려보내되 `DisabledException` 에만 붙인다 —
  다른 실패에도 붙으면 그 값 하나로 "가입돼 있고 아직 미인증"을 알아낼 수 있게 된다.

### 메일 게이트를 켜는 순서

`APP_REQUIRE_EMAIL_VERIFICATION` 의 기본값은 **`false`** 이고, 그래서 지금은 가입 즉시
`enabled = true` 다 — 확인 메일은 나가지만 아무것도 막지 않는다. 켤 때는 순서가 중요하다:

1. `MAIL_*` 를 채우고 **실제로 메일이 도착하는 것을 확인**
2. 그 다음 `APP_REQUIRE_EMAIL_VERIFICATION=true`

뒤집으면 신규 가입자가 전부 잠긴다. 비상 탈출구는
`UPDATE users.users SET enabled = true WHERE email = '...';` 이다.

> 참고: `users.email_verified` 컬럼은 **아무도 읽지도 쓰지도 않는 죽은 컬럼**이다.
> 실제 플래그는 `enabled` 다.

---

## 7-3. 비밀번호 찾기 — 양 끝이 다 끊겨 있었다

가입 확인 경로를 고친 김에 옆 경로를 점검했더니, **비밀번호 찾기는 처음부터 끝까지
동작한 적이 없었다.** 메일이 안 나가고 있어서 가려져 있었을 뿐이다.

```
ForgotPage ──▶ /forgot-password ──▶ 메일 ──▶ /reset-password?token=… ──▶ ???
                 ✗ 요청 형식 불일치                        ✗ 그 화면이 없음
```

- **요청이 안 들어갔다.** 컨트롤러는 `@RequestParam String email` 인데 화면은
  `application/json` 바디로 보냈다. `@RequestParam` 은 쿼리스트링·폼만 읽으므로 언제나
  **400 "Required request parameter 'email' is not present"** 였다.
- **링크를 눌러도 갈 곳이 없었다.** 프론트 전체에 `reset-password` 문자열이 **0건**.
  `/forgot`·`/verification-failed` 와 같은 종류의 구멍이다.

### 그리고 보호 수준이 거꾸로였다

|  | 가입 확인 토큰 | 비밀번호 재설정 토큰 (before) |
|---|---|---|
| 저장 | SHA-256 해시 | 🔴 **평문** |
| 사용자당 | 1개 (`user_id` UNIQUE + `refresh()`) | 🔴 요청할 때마다 새 행 |
| 열거 방지 | 모든 경우 동일한 200 | 🔴 미가입 → **500**, 가입됨 → **200** |
| 할 수 있는 일 | 계정 활성화 | **계정 탈취** |

**더 강력한 토큰이 더 약하게 보호되고 있었다.** DB 덤프 한 번이면 살아 있는 재설정 링크가
전부 노출되고, 그 링크 하나로 계정을 가져갈 수 있다. 열거 문제도 `resend-verification`
에서는 공들여 막아 놓고 정작 비밀번호 찾기가 뚫려 있었다.

→ `V10__password_reset_hardening.sql` + `PasswordResetToken.hash()`. 평문 행은 마이그레이션이
지운다 — 죽은 행으로 두면 **평문 자격증명이 DB 에 남는다**(잃을 것은 없다: 그 링크들은
화면이 없어 애초에 쓸 수 없었다).

### 🐛 로그인 상태 변경은 실패를 성공으로 보고했다

```java
if (passwordEncoder.matches(currPassword, user.getPassword())) {
    user.setPassword(...);  userRepository.save(user);
}
// else 가 없다 — 그냥 빠져나간다
```

반환형이 void 라 컨트롤러는 그대로 `200 "Password updated"` 를 돌려줬다. **현재 비밀번호를
틀리게 넣으면 아무 일도 안 일어나는데 성공이라고 답했다.** 사용자는 다음 로그인에서야
알게 되고, 그때는 무엇이 잘못됐는지 알 수 없다. 응답이 맨 문자열이라 화면의
`data.message` 가 언제나 undefined 였던 것도 함께 고쳤다.

### 세션 무효화 — 왜 시각으로 거르나

비밀번호를 바꿔도 **기존 세션이 살아 있었다.** 되찾으러 오는 사람의 절반은 계정을
도둑맞아서 오는데, 공격자의 세션이 남으면 되찾은 것이 아니다.

토큰을 찾아 지우는 방법은 이 구조에서 쓸 수 없다:

- 리프레시 토큰은 Redis 에 **sessionId 로만** 저장돼 "이 사용자의 모든 세션" 을 뒤집어
  찾을 수 없다.
- `RedisSessionManager` 는 이름과 달리 **인메모리 `HashMap`** 이라 레플리카마다 따로 논다.

그래서 `users.password_changed_at` 을 두고, **그보다 먼저 발급된** 리프레시 토큰을
`RefreshTokenController` 가 거절한다 — DB 한 곳만 보므로 레플리카 수와 무관하다.

> ⚠️ 액세스 토큰은 서명만으로 검증되므로 남은 수명(기본 15분) 동안은 살아 있다. 그 뒤
> 갱신이 막혀 로그아웃된다. **즉시 차단이 아니다** — 완전한 즉시 차단은 모든 요청마다 DB 를
> 보는 구조라야 하고, 그 비용은 이 서비스에 맞지 않는다.
>
> ⚠️ 컬럼 기본값을 `now()` 로 넣지 않는다. 넣었다면 배포 순간 **전원이 로그아웃된다.**
> null 은 "한 번도 바꾼 적 없음" 이고 아무 토큰도 거절하지 않는다.

### 운영 메모

관리자 계정의 비밀번호는 화면이 아니라 `ops/30-admin-account.sh password <이메일>` 로
재설정한다 — 대화형 입력이고 argv·로그·임시파일 어디에도 평문을 남기지 않으며, 앱과 같은
BCrypt(strength 10)로 해싱한다. 이 스크립트도 `password_changed_at` 을 함께 세우도록
고쳤다: 빠뜨리면 이 경로로만 기존 세션이 살아남아 앱과 동작이 갈리는데, 계정을 도둑맞아
관리자가 대신 바꿔 주는 상황이 바로 이 스크립트를 쓰는 경우다.

---

## 8. 원본 저장소

분산 이메일 서비스 전체(메일함·Cassandra·검색·카오스 테스트)는 별도 저장소에 있다.
이 프로젝트에는 발신 파이프라인 패턴만 들어왔다.

- 원본: [distributed-email-service](https://github.com/ruke79/distributed-email-service)
- 근거: System Design Interview Vol.2, Ch.23 "Design a distributed email service"
