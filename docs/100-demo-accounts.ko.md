# 100. 공개 데모 계정 — 가입 없이 둘러보기

> 2026-11-21 · 1단계(백엔드 · 화면) · 2단계(매일 초기화, §5-1) · 3단계(주문 상한 · 관리자 표시 · 채팅 상한, §5-2) — **스키마 변경 없음**. 남은 것은 운영에서 켜기(§4).
> 일본어판: [100-demo-accounts.md](100-demo-accounts.md)

## 0. 왜

채용 담당자 · 방문자가 가입 없이 장바구니 → 주문 → 시험 결제까지 보게 한다. 운영 결제는 모두 시험 모드다(Stripe `sk_test_` · PayPal sandbox · 포트원 CBT · 무통장은 자동 결제 없음) — 실제 청구는 없다.

여러 방문자가 계정을 **돌려 쓴다**. 그래서 막을 것은 "다른 방문자를 내쫓거나 계정을 가로채는 일"과 "남에게 보이는 글"이다.

## 1. 정한 것(2026-11-21)

| 질문 | 결정 |
|---|---|
| 계정을 어떻게 나눠 주나 | 로그인 화면 버튼 + 계정 묶음(기본 5개) — 비밀번호는 아무도 모른다(공개하지 않는다) |
| 남에게 보이는 글 | 리뷰는 막고, 상담 채팅은 허용(관리자에게만 보인다) |
| 초기화 | 매일 1회(다음 단계) |
| 주문 · 시험 결제 | 허용 |
| 스키마 | 바꾸지 않는다 — 데모 표시는 이미 있는 `users.sign_up_method = 'demo'`, 차례는 Redis |

## 2. 흐름

```
로그인 화면 ── GET  /user-service/api/auth/public/demo-login  → {enabled}   (꺼져 있으면 버튼 없음)
           └─ POST /user-service/api/auth/public/demo-login  → 보통 로그인과 같은 응답(헤더 access · REFRESH 쿠키)
                                                                토큰에 demo=true
게이트웨이 JwtAuthorization ── demo=true 이고 막힌 쓰기면 403 「데모 계정에서는 사용할 수 없는 기능입니다」
화면 ── user.demo 이면 맨 위 안내 띠(DemoBanner)
```

- **계정 만들기** — `DemoAccountService.provision()`(기동 때, `DEMO_ACCOUNTS_ENABLED=true` 일 때만): `demo1 ~ N@demo.invalid`(`.invalid` 는 메일이 나가지 않는 예약 도메인), 비밀번호는 32바이트 난수, `emailVerified` · `enabled`, 역할 ROLE_USER. 가입과 같은 길로 `user-created` 아웃박스 행 — 웰컴 쿠폰 · 채팅 회원 복제본이 생긴다. 같은 이메일의 보통 계정이 있으면 건드리지 않는다.
- **내주기** — 후보는 `sign_up_method='demo'` · 활성 · 잠기지 않음 · 탈퇴 아님(60초 캐시), 차례는 Redis `INCR demo:accounts:next` — 원자적이라 동시에 누른 두 방문자는 다른 계정, 한 바퀴 돌면 가장 오래전에 내준 계정. Redis 가 실패하면 아무 계정이나(막지 않는다). 계정 수보다 방문자가 많으면 함께 쓴다.
- **하지 않는 것** — 기기 기록 · 새 기기 알림 · 로그인 위험 확인(받을 사람이 없는 도메인 · 방문자마다 새 기기).
- **토큰 갱신** — `JwtUtils.generateTokenFromUser` 가 `user.isDemo()` 면 `demo` 클레임을 싣는다. 리프레시도 같은 메서드라 표시가 이어진다.

`signUpMethod` 를 정하는 곳은 가입(`email`)과 소셜 로그인(설정의 provider 이름 — google · github)뿐이라 바깥에서 `demo` 를 만들 수 없다.

## 3. 게이트웨이가 막는 쓰기(`DemoAccessPolicy`)

읽기(GET · HEAD · OPTIONS)는 막지 않는다. 경로는 **RewritePath 전의 원래 경로**(서비스 이름까지)로 비교한다 — 필터가 재작성 뒤에 돌기 때문이다. 겹친 `/` · 끝 `/` · 퍼센트 인코딩으로 돌아가지 못한다.

| 묶음 | 경로(`/user-service` …) | 왜 |
|---|---|---|
| 계정 · 자격 | `/api/user/profile/update-password` · `/api/user/profile/me` · `/me/**` | 바꾸면 다른 방문자가 못 들어온다 |
| 세션 · 기기 | `/api/user/profile/sessions/**` · `/devices/**` | '모든 기기에서 로그아웃' 은 함께 쓰는 방문자를 모두 끊는다 |
| 2단계 인증 · 패스키 | `/api/auth/enable-2fa` · `disable-2fa` · `verify-2fa` · `2fa/**` · `sms-2fa/**` · `/api/user/passkeys/**` | 켜면 다른 방문자가 못 들어온다 |
| 탈퇴 | `/api/user/withdrawal/**` | |
| 남에게 보이는 글 | `/api/review/**` | 상품 페이지에 남는다 |
| 인증 SMS | `/notification-service/api/notifications/phone/**` | 아무 번호로나 보내게 하지 않는다(비용 · 스팸) |

허용: 둘러보기 · 장바구니 · 찜 · 주소록 · 주문 · 시험 결제 · 알림 · 상담 채팅. 지표 `gateway_demo_blocked_total`.

## 4. 켜기 · 끄기

- 켜기: ConfigMap 에 `DEMO_ACCOUNTS_ENABLED: "true"` → `ops/28 --config-only --restart user-service` — 다음 기동에 계정을 만든다. 개수 `DEMO_ACCOUNTS_COUNT`(1 ~ 50, 기본 5) · 도메인 `DEMO_ACCOUNTS_EMAIL_DOMAIN`.
- 끄기: 같은 값을 `false` — 버튼이 사라지고 데모 로그인은 404. 이미 받은 토큰은 수명(최대 15분) · 리프레시(14일)까지 살아 있다 — 바로 끊으려면 데모 계정을 잠근다(관리자 화면 잠금, 후보에서 빠진다).
- 이미지: gateway · user · frontend · cart · notification · order · chat. 마이그레이션 없음.

## 5. 시험

| 시험 | 본다 |
|---|---|
| gateway `DemoAccessPolicyTest`(4) | 막힌 쓰기 403 · 우회(`//` · 끝 `/` · `%77`) · 허용 동작 통과 · 보통 토큰은 같은 경로도 통과 — 재작성된 경로로 판정하면 실패(변이) |
| user `DemoLoginControllerTest`(5) | 꺼짐 404 · 상태 GET · 계정 없음 503 · 토큰의 `demo` · 보통 계정에는 없음 — 클레임을 빼면 실패(변이) |
| user `DemoAccountClaimTest`(컨테이너 4) | 실제 스키마의 후보 SQL(보통 · 소셜 · 비활성 · 잠금 · 탈퇴 제외) · 차례로 돌림 · Redis 실패 · 꺼짐 — `sign_up_method` 조건을 빼면 실패(변이) |
| 화면 `SignInDemo.test.jsx`(3) · `DemoBanner.test.jsx`(2) · `jwtUser.test.js`(+1) | 꺼지면 버튼 없음 · 누르면 demo 사용자로 login · 실패 토스트 · 안내 띠 |

## 5-1. 매일 초기화(2단계)

`DEMO_ACCOUNTS_ENABLED=true` 일 때만. 주문은 지우지 않는다 — 결제 · 아웃박스 · 사가가 얽혀 있다(§6 의 상한으로 다룬다).

| 시각(UTC) | 서비스 | 하는 일 |
|---|---|---|
| 19:00 | user-service `DemoAccountReset` | 클러스터에서 한 파드(`SchedulerLease` `demo-account-reset`, 30분) — 모든 데모 계정(잠김 · 비활성 포함)의 `users.wishlist` · `shipping_address` · `known_device` 를 한 트랜잭션으로 지우고, notification `POST /api/notifications/internal/demo-reset`(경로 규칙 `=user-service`)으로 알림 · 공지 읽음 표시를 지운다. 알림 호출 실패는 다음 날 다시 |
| 19:05 | cart-service `DemoCartReset` | user-service `GET /api/auth/user/demo-accounts`(경로 규칙 `=cart-service`, 토큰 없이 부르므로 그 경로만 permitAll — 막는 것은 경로 규칙 · InternalCallGuard)로 목록을 받아 장바구니를 비우고 커밋 뒤 캐시를 지운다. 멱등이라 파드마다 돈다 |

둘 다 게이트웨이를 거친 요청(신원 헤더 있음)은 관리자라도 403. 시각은 `demo.accounts.reset.cron` · `demo.accounts.reset.cart-cron`. 시험: user `DemoAccountResetTest`(5) · `DemoAccountInternalControllerTest`(2) · `DemoAccountClaimTest` +1(실제 스키마의 DELETE) · cart `DemoCartResetTest`(3) · notification `DemoNotificationResetTest`(컨테이너 3).

## 5-2. 데모만의 상한(3단계)

게이트웨이가 검증된 데모 토큰에만 `User-Demo: true` 를 싣는다(손님이 보낸 같은 헤더는 `IdentityHeaderScrubFilter` 와 JwtAuthorization 이 지운다).

| 무엇 | 어디 | 상한 |
|---|---|---|
| 결제 전 주문 | order `DemoOrderLimit`(주문 생성 앞) | 결제 시작 전(`NOT_PROCESSED`) + 입금 대기(`UNPAID`) 합이 `demo.orders.max-pending`(기본 3) 이상이면 400 「데모 계정은 결제하지 않은 주문을 3건까지…」 — 같은 멱등 키의 재요청은 이미 만든 주문을 돌려주므로 막지 않는다 |
| 상담 채팅 | chat `StompHandler` · `DemoChatLimiter` | CONNECT 가 토큰의 demo 클레임으로 세션에 표시, SEND 는 계정마다 10분 창에 `chat.demo.max-messages`(기본 20)개 — 넘으면 그 메시지만 버린다(연결은 그대로), Redis 실패는 통과 |
| 관리자 표시 | 화면 주문 관리(`Refunds.jsx`) | 손님 이메일이 `@demo.invalid` 면 '데모' 표시(`util/demoAccount` — 표시용, 권한 판단 아님) |

시험: order `DemoOrderLimitTest`(3) · gateway `DemoAccessPolicyTest` +1(User-Demo 는 검증된 클레임에서만) · chat `StompHandlerDemoLimitTest`(3) · `DemoChatLimiterTest`(2) · 화면 `Refunds.test.jsx` +1 · `demoAccount.test.js`(1).

## 6. 다음 단계

1. ~~매일 1회 초기화~~ — §5-1.
2. ~~데모 주문 상한 · 관리자 '데모' 표시~~ — §5-2.
3. ~~상담 채팅 속도 제한~~ — §5-2.
4. 운영에서 켜기(§4) — 이미지 gateway · user · frontend · cart · notification · order · chat 을 배포한 뒤 `DEMO_ACCOUNTS_ENABLED: "true"`.
