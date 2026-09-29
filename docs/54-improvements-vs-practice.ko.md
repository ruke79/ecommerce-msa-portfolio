# 54. 개선 총정리 — 왜 고쳤나, 실무 기준과 비교해 무엇이 부족했고 무엇이 불필요했나

[日本語](54-improvements-vs-practice.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** §10.1 의 "서비스별 신원은 다음 단계" · "대사 배치 미착수" · "인증서 하나 공유" 는 이후 해결했습니다([57 §16·§18](57-remaining-priorities.ko.md), c13f51f). 사례별 한계와 개선점은 [docs/62](62-engineering-casebook.ko.md).

> 작성일: 2026-09-25 · 기준: 이 날짜의 브랜치 끝(사용자 복제 ⑤⑥, 2FA 서버 강제, 쿠폰 발급 권한까지 반영). 초안을 쓰며 코드에서 새로 찾은 🔴 두 건(§10.1 의 2FA·쿠폰 발급)은 같은 날 고쳤고, 해당 행은 §1 로 옮겼다.
> 이 문서는 프로젝트 전체의 개선을 **최종 상태 기준**으로 다시 묶은 것이다. 중간에 고쳤다가 다시 바뀐 것은 마지막 모양만 적고,
> 문서끼리 어긋나는 곳은 코드를 열어 확인한 쪽을 따랐다(§10.3). 재현 로그·절차·설계 근거는 `근거` 칸의 링크에 있다.
> 각 행은 **문제(전) → 왜 문제였나 → 실무 기준 → 최종 상태(후)** 순서다. 요약 색인은 [docs/00](00-before-after.ko.md), 보안 상세는 [docs/27](27-security-review.ko.md) 이다.
> 여러 릴리스에 걸친 무중단 전환(신원·결제 컬럼·메시지 스키마)의 배포 순서와 교훈은 [docs/55](55-zero-downtime-migrations.ko.md) 에 모았다.
> 심각도: 🔴 데이터 유출·돈·기동 불가 · 🟠 기능 결함·운영 위험 · 🟡 품질·위생. ✅ 는 닫힌 항목이다.

---

## 1. 보안 — 부족했던 것

가장 많이 나온 형태는 하나다. **클라이언트가 고를 수 있는 값을 신원·금액·결제 상태·레이트리밋 키로 믿었다.**
두 번째 형태는 권한 검사가 잘못된 것이 아니라 **아예 없던** 경우다.

### 1.1 인증·인가 — IDOR · 권한 검사 누락 · 내부 API

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 주문 번호만 알면 **아무 주문이나** 조회. `email` 파라미터를 받아 놓고 쓰지 않았다 | 순차 정수 id 라 1부터 세면 전 고객의 상품·금액·배송 주소·수령인 전화번호가 나왔다 | OWASP API1(BOLA). 소유권은 서버가 검증된 신원으로 판정하고, 남의 것은 403 이 아니라 404 | ✅ 데이터 계층에서 `customerId` 대조, 남의 주문과 요청자 없음 모두 404. 수정을 되돌리면 실패하는 `OrderOwnershipTest` | [docs/27 §1~4](27-security-review.ko.md) · `OrderDataService.getOrder` |
| 🔴 `?email=`·`?userId=` 로 남의 주문 목록·장바구니·보유 쿠폰 조회 | 이메일 하나로 그 사람의 이력이 전부 나왔다. 네 서비스에 같은 실수가 반복됐다 | 신원은 쿼리·본문이 아니라 게이트웨이가 검증한 토큰에서 | ✅ `User-Email`/`User-Id` 헤더로만 판정. 프론트도 신원 파라미터를 보내지 않는다(서버가 다시 믿을 여지를 없앤다) | [docs/27 §1~4](27-security-review.ko.md) |
| 🔴 쿠폰 적용이 **본문의 `userEmail`** 로 사용자를 찾았다 | 남의 쿠폰 보유 여부 확인, 그 할인액 수령, **남의 id 로 쿠폰 사용 이벤트** 발행. `/coupons` 는 고치고 `/applycoupon` 만 빠졌다 | 같은 규칙을 엔드포인트마다 기억하지 말고 테스트 패턴으로 강제 | ✅ 헤더 신원 — 지금은 `User-Id`(토큰의 uid). 본문 DTO 에서 필드째 삭제(옛 클라이언트 값은 `ignoreUnknown` 으로 버림). `CouponApplyOwnershipTest` 가 컨트롤러 시그니처까지 고정 | [docs/41 시나리오 10](41-api-security-applied.ko.md) |
| 🔴 주문 생성이 **본문 `email`** 로 장바구니·쿠폰·주문자를 정했다 | 남의 이메일을 넣으면 그 사람의 장바구니로 내 배송지에 주문이 만들어졌다 | 위와 같다 | ✅ `User-Email` 헤더에서만 읽는다 | [docs/47 §5-5](47-order-create-failure-visibility.ko.md) |
| 🔴 배송지 삭제에 소유권 검사가 없고, 기본 배송지를 **전 테이블에서** 찾았다 | 주소 id 하나로 남의 배송지 삭제. 사용자 둘 이상이면 선택이 상시 400, 하나면 남의 기본 배송지를 끈다 | "찾아서 비교"가 아니라 "내 것 중에서 찾는다" — 비교를 빠뜨릴 수 없는 쿼리 + DB 제약 | ✅ 소유자 조건 쿼리, 안전하지 않은 `findByActive` 삭제(되돌리면 컴파일 불가), V7 부분 유니크 인덱스 | 커밋 `0ed948e` · [docs/43 B1·B2](43-loading-strategy-review.ko.md) |
| 🔴 쿠폰 삭제·송장 등록에 **권한 검사가 없었다**. 송장은 "운영 전에 넣어야 한다" 주석만 있었다 | 로그인한 아무나 프로모션 쿠폰 삭제, 남의 주문을 발송 처리(주소 변경·취소가 막히고 거짓 배송 알림) | TODO 는 통제가 아니다. 헤더가 없으면 통과가 아니라 거부 | ✅ `User-Role` 관리자 필수, 헤더 없으면 거부 | [docs/27 §5~6](27-security-review.ko.md) |
| 🔴 삭제를 고친 뒤에도 **같은 컨트롤러의 쿠폰 발급(`/add`)·수정(`/update`)은 열려 있었다**(이 정리 중 발견) | 로그인한 아무나 자기 id 로 할인율을 정한 쿠폰을 발급. 서버가 그 쿠폰으로 총액을 계산하므로 **결제 금액 대조도 통과** — 금액 검증이 못 막는 종류 | 형제 엔드포인트를 전부 본다. 발급은 관리자·시스템만 | ✅ 둘 다 `InternalCallGuard.isAdmin`. 일반 사용자의 발급은 서버가 조건을 판정하는 `/events/{slug}/claim` 뿐. `CouponAdminGuardTest` | [docs/27 §5~6](27-security-review.ko.md) · 커밋 `1bcfabf` |
| 🔴 카탈로그 **쓰기 API**(상품·카테고리 등록, 배치 잡 기동)가 무인증 공개 | 게이트웨이 라우트에 JWT 없음 + 서비스에 Security 없음 + `@PreAuthorize` 주석 처리 — 세 겹이 동시에 비어 있었다 | 기본 거부, 예외만 명시 | ✅ 쓰기 메서드 전용 라우트(JWT) + `AdminWriteInterceptor`(쓰기는 전부 관리자, 예외는 별점 하나) | 커밋 `3d130d1` |
| 🔴 공개 회원가입에 `"role":["admin"]` 을 보내면 **관리자로 가입** | POST 한 번으로 권한 상승 — 방금 잠근 관리자 기능 전체가 무의미 | Mass assignment 금지, 가입은 최소 권한. 첫 관리자는 UI 가 아니라 배포 경로로 | ✅ 가입은 언제나 `ROLE_USER`(요청 역할은 경고 후 무시), 관리자는 `ops/30` 으로 | 커밋 `358891e` · [ops/README ㉚](../ops/README.ko.md) |
| 🔴 사용자 조회 내부 API 다섯 중 셋(`/user/email/{id}`·`/user/profile/{id}`·`/user/name`)이 무방비. 판정도 `User-Role` 하나만 봤다 | 로그인한 아무나 id 를 바꿔 남의 이메일·프로필 열거. role 클레임 없는 토큰은 "내부 호출"로 통과 | 내부 API 는 외부 라우팅에서 빼거나 별도 포트 + 서비스 간 인증 | ✅ 다섯 모두 `InternalCallGuard.allowed`(세 신원 헤더 중 하나라도 있으면 외부 → 관리자만), id·이름 조회는 없는 사용자와 같은 404. `UserLookupGuardTest`. 남은 격차는 §10 | [docs/27 §9·§9b](27-security-review.ko.md) · `common/.../security/InternalCallGuard.java` |
| 🔴 장바구니 저장(`/savecart`)이 신원을 **본문 `email`** 에서 읽었다. 조회 둘은 `User-Email` 을 받아 user-service 에 이메일 → id 를 다시 물었다 | 로그인만 하면 남의 이메일로 **남의 장바구니를 지우고 덮어썼다.** 장바구니는 id 로 저장되는데 신원·캐시 키만 이메일이라, 캐시를 비우는 모든 경로(Kafka 소비 포함)가 user-service 를 불러야 했다 | 신원은 토큰의 불변 id. 토큰이 이미 나르는 것을 다시 묻지 않는다 | ✅ 저장·조회 모두 `User-Id`, 본문 DTO 에서 email 필드 삭제(옛 클라이언트 값은 무시), 캐시 키 `carts::<id>`, user-service 호출·클라이언트 삭제. order·coupon 의 Feign 도 `User-Id`. 🐛 그 email 에 `@NotNull` 이 있어, 필드만 안 읽었으면 새 화면의 저장이 전부 400 이 될 뻔했다 | `CartIdentityContractTest` · `FeignContractTest` · `CartClientContractTest` |
| 🔴 coupon 이 모든 요청에서 `User-Email` 로 user-service 에 이메일 → id 를 다시 물었고, 그 Feign 호출은 **액세스 토큰을 URL 쿼리**(`?Authorization=Bearer …`)로 실었다. 주문 견적(`/internal/quote`)도 이메일로 물었다 | URL 은 액세스 로그·프록시·예외 메시지에 남는다 — 토큰 원문이 로그로 샌다. 요청마다 user-service 왕복이 하나씩 붙어, user-service 가 느리면 쿠폰 적용·주문 생성이 같이 느려졌다. 기획전 수령도 고객군을 이메일로 물었다 | 신원은 토큰의 불변 id. 토큰은 URL 에 싣지 않는다 | ✅ 적용·목록·기획전 수령이 `User-Id` 를 읽고 쿠폰을 id 로 바로 찾는다(user-service 호출 없음). 고객군은 `/user/segments?userId=`. 견적은 order 가 `userId` 를 보낸다. `findUserByEmail` 의 토큰 파라미터 삭제(남은 호출은 관리자의 이메일 발급뿐). 전환 릴리스 동안 두었던 이메일 대체 경로는 전환 정리에서 지웠다(docs/48 §6-⑥) | `CouponQuoteTest` · `EventCouponClaimTest` · `CouponApplyOwnershipTest` · order `FeignContractTest` · `UserLookupGuardTest` |
| 🟡 무중단 배포를 위한 **전환 코드**(두 헤더를 함께 싣는 호출, 이메일 대체 경로, 가짜 `Authorization` 표지, 옛 발신자 백필, 롤백용 테이블 둘)가 그대로 남을 뻔했다 | 전환 코드는 "다음 릴리스에 지운다" 는 주석과 함께 영원히 남기 쉽다. 남으면 이메일 신원·헤더 존재 검사 같은 **이미 없앤 모양이 되살아날 입구**가 된다 | expand-contract 의 contract 도 계획에 넣는다. 지우는 쪽이 **앞 단계가 끝났는지 스스로 확인**하게 한다 | ✅ 한 번에 정리하고 `User-Email` 을 싣는 Feign 이 되살아나지 못하게 테스트로 고정. chat V6 는 매핑 테이블에 행이 남아 있으면(백필 미완료) **지우지 않고 실패**한다 — PG16 에서 세 경우 확인, 그 과정에서 `IF a AND EXISTS(...)` 가 테이블이 없는 새 DB 에서 실패하는 것을 잡아 고쳤다. 전환 릴리스(`1227834`)를 먼저 배포해야 한다 | [docs/48 §6-⑥ 전환 정리](48-user-replication-debt.ko.md) · order·coupon `FeignContractTest`·`CartClientContractTest` · `Summary.test` |
| 🟠 내부 견적·집계(`/internal/quote`, `/internal/purchase-stats`)가 같은 약한 판정 | 막지 않으면 남의 쿠폰 보유 여부, **전 고객 결제 금액**을 긁을 수 있었다 | 위와 같다. 경계를 넘기는 데이터는 최소화 | ✅ `InternalCallGuard`, 집계는 합계만 넘긴다 | [docs/44 §3.3](44-notice-notification-event-gap.ko.md) · 커밋 `78803e0` |
| 🟠 결제 확인(`/payment/paypal`)에 주문 소유권 검사가 없고 고객을 본문 이메일로 해석 | 남의 주문에 결제를 붙이는 경로 | 모든 상태 변경 경로에 소유권 | ✅ `recordPayment`·환불·취소·입금 안내가 전부 요청자를 대조(관리자는 `User-Role` 로 예외) | [docs/38 §1.6](38-code-review.ko.md) · `OrderDataService` |
| 🟠 프론트 `/admin` 이 무인증·무권한, 역할 클레임을 `roles` 로 읽어 **항상 빈 배열** | 일반 회원이 관리자 화면을 정상으로 봤다. 가드를 넣는 순간 진짜 관리자도 쫓겨날 상태였다 | UI 가드는 UX 이고 최종 판정은 서버. 둘이 짝이어야 한다 | ✅ `ProtectedRoute adminOnly` + `util/jwtUser.js`(단수·복수·접두사 모두 수용). 서버 판정은 위 행들 | [docs/26 §3](26-frontend-review-and-refactor.ko.md) · [docs/27 §12](27-security-review.ko.md) |

### 1.2 신원 헤더 · 게이트웨이

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 클라이언트가 보낸 `User-Id`/`User-Email`/`User-Role` 을 지우지 않았다 | `User-Role: ROLE_ADMIN` 한 줄이 권한 상승. 레이트리밋 키도 요청마다 바꿀 수 있었다 | 신원 헤더는 엣지에서 무조건 제거하고 검증 뒤 다시 넣는다 | ✅ `IdentityHeaderScrubFilter`(GlobalFilter, `HIGHEST_PRECEDENCE`, `X-` 변형까지) + JWT 필터 안에서도 한 번 더 제거 | [docs/27 §10·§11](27-security-review.ko.md) · `gateway-server/.../IdentityHeaderScrubFilter.java` |
| 🔴 (위 수정의 회귀) 제거를 `default-filters` 에 두어, JWT 필터가 넣은 헤더를 **스스로 지웠다** | 인증 필요 엔드포인트 전부 401. "기본 필터가 먼저 돈다"는 단정이 틀렸고, 실행 순서는 파일에 보이지 않았다 | 순서처럼 파일에 안 보이는 계약은 코드 구조와 테스트로 고정 | ✅ GlobalFilter 로 이동. B-53 이 양방향(되돌림, 전역 필터 소실·순위 상실)을 막는다 | 커밋 `af776f9` · B-53 |
| 🟠 `User-Id` 헤더에 **이메일**이 들어 있었다 | 숫자 id 를 받는 배송·알림 서비스가 실제 게이트웨이 경유 시 전부 400. 이메일은 바뀌는 값이라 소유권 기준이 될 수 없다 | 소유권은 불변 id 로 | ✅ JWT `uid` 클레임 → `User-Id`(숫자)·`User-Email`·`User-Role` 셋. `uid` 없는 토큰은 게이트웨이가 401 | [docs/27 §11](27-security-review.ko.md) · `JwtAuthorizationGatewayFilterFactory` |
| 🔴 레이트리밋 버킷 키를 `X-User-Id` 와 `X-Forwarded-For` 첫 값에서 만들었다. 기존 테스트가 **그 우회 동작을 고정**하고 있었다 | 헤더를 바꿔 가며 보내면 매 요청 새 버킷 — 로그인 대입 제한이 사실상 없었다 | 클라이언트가 고를 수 있는 값은 키가 될 수 없다. 신뢰 프록시가 덮어쓴 값만 | ✅ `User-Id` → `CF-Connecting-IP`(엣지가 덮어씀) → 소켓 주소. 테스트를 뒤집어 스푸핑이 무시돼야 통과 | [docs/41 시나리오 1](41-api-security-applied.ko.md) · `CustomUserKeyResolverTest` |

### 1.3 로그인 · 세션 · 계정 보호

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🟠 로그인 경로도 전역 제한 20 req/s 뿐 | IP 하나로 분당 1,200회 비밀번호 시도 | 인증 전 경로는 훨씬 강하게 + 계정 단위 잠금 | ✅ 공개 인증 경로 1/s·버스트 10. (라우트 id 중복으로 덮일 가능성은 §10) | [docs/27 §10](27-security-review.ko.md) |
| 🟠 `account_non_locked` 컬럼은 있는데 **실패를 세는 코드가 없었다**(장식) | IP 를 바꿔 한 계정을 노리는 스터핑에 IP 버킷은 무력 | OWASP ASVS: 실패 횟수 기반 일시 잠금, 계정 존재 여부를 흘리지 않는다 | ✅ 5회 실패 → 15분 잠금·자동 해제, 비밀번호 검사 **전에** 잠금 검사, 없는 계정은 세지 않음(부채널 차단) | 커밋 `03422d0` · `LoginAttemptService` · V4 |
| 🟠 메일 인증을 켜도 미인증 계정이 로그인됐다 (`isEnabled()` 하드코딩 + 커스텀 Provider 가 사전 검사를 건너뜀) | 남의 이메일로 가입해 그대로 쓸 수 있었다 | 계정 상태 검사는 인증 경로에서 실제로 돌아야 한다 | ✅ 비밀번호 검사 **뒤에** `DisabledException`(앞이면 가입 여부 열거 도구). `DisabledAccountLoginTest` | 커밋 `5b4e643` |
| 🟠 액세스 토큰 수명 2시간, 폐기 수단 없음 | 탈취·로그아웃 뒤에도 만료까지 유효 | 짧은 액세스 토큰 + 서버가 관리하는 리프레시 | ✅ 15분 + HttpOnly·Secure 리프레시 쿠키. 리프레시·로그아웃 블랙리스트를 둔 Redis 는 PVC + AOF 로 영속화(블랙리스트 소실 = 폐기한 토큰 부활) | [docs/41 챕터 9](41-api-security-applied.ko.md) · 커밋 `95007cf` |
| 🟠 비밀번호 재설정 토큰을 **평문 저장**, 요청마다 새 행, 미가입 500·가입 200, 변경 실패도 200 | 가입 확인 토큰(해시)보다 더 강력한 토큰이 더 약하게 보호됐다. DB 덤프 한 번이면 살아 있는 재설정 링크 전부 | 토큰은 해시로 저장, 응답은 가입 여부와 무관하게 통일 | ✅ SHA-256 해시, V10 이 평문 행 삭제, 응답 통일, 토큰은 URL 이 아니라 본문으로 | 커밋 `a298a3e` |
| 🟡 소셜 로그인에 PKCE 가 없었고, 인가 요청을 **HTTP 세션**에 뒀다 | 인가 코드 주입 방어 한 겹이 비었다. 레플리카 2개라 로그인이 대략 절반 확률로 실패, `STATELESS` 선언과도 모순 | OAuth 2.1·Security BCP: 기밀 클라이언트에도 PKCE. verifier 는 서버에 | ✅ `withPkce()`, 인가 요청은 Redis(`GETDEL`, TTL 10분), 읽히지 않던 요청 캐시 끔. Redis 장애 시 fail closed | [docs/27 부록 2](27-security-review.ko.md) |
| 🔴 **2FA 를 서버가 강제하지 않았다.** 소셜 로그인은 처음에 2FA 를 아예 건너뛰었고, 고친 뒤에도 보류 쿠키 값이 **액세스 토큰과 같은 키의 보통 JWT(uid 포함)** 라 Bearer 로 보내면 게이트웨이를 통과했다. 비밀번호 로그인은 2FA 계정에도 **액세스 토큰·REFRESH 를 먼저** 줬다(이 정리 중 발견) | 코드 입력을 기다린 것은 화면뿐 — 비밀번호만 알면 응답 헤더의 토큰을 그대로 썼다. 코드 확인에 시도 제한도 없었다(6자리 = 100만) | 두 번째 요소 전에는 **어떤 자격증명도 발급하지 않는다.** 대기 토큰은 용도를 분리해 다른 검증기가 받지 못하게, 시도 횟수 제한(NIST 800-63B §5.2.2) | ✅ `PendingTwoFactor` — 액세스 키에서 **파생한 별도 키**로 서명(게이트웨이·chat 등 모든 검증기가 고치지 않고 거부), 토큰당 5회·1회용. 두 경로 모두 대기 쿠키만 주고 `/public/verify-2fa` 통과 뒤 REFRESH. 비밀번호만으로 이전 세션을 끊지 않는다. 시험도 되지 않던 `/public/verify-2fa-login`(토큰을 쿼리로 받는 코드 오라클) 삭제. `TwoFactorLoginTest` | [docs/27 §12b](27-security-review.ko.md) · 커밋 `70a0df9`·`bffa957` |
| 🟠 다중 로그인 판정이 이름만 Redis 인 JVM `HashMap`, 세션 id 를 싱글턴 빈 필드에 보관 | 거짓 양성(혼자 쓰는데 경고)·거짓 음성(파드가 다르면 판정 없음), **다른 사람을 로그아웃**시켰다 | 공유 상태는 공유 저장소에, 요청 상태는 지역 변수에 | ✅ Redis `login-session:` + TTL, 끊을 세션 id 는 인자로. `NotificationSessionRoutingTest` | [docs/46](46-multiple-login-detection.ko.md) · 커밋 `d89e029` |
| 🟠 새 기기 로그인 알림이 **발행되지 않았다**(알림 종류·문구·수신 예외는 전부 있었다) | 계정이 털려도 사용자가 알 방법이 없었다 | 새 기기 로그인은 알린다, 보안 알림은 사용자가 꺼도 간다 | ✅ httpOnly 기기 쿠키(해시 저장), 첫 기기는 알리지 않음, 기기 수 상한 | [docs/44 §4](44-notice-notification-event-gap.ko.md) |

### 1.4 사용자 열거 — 응답이 존재 여부를 말하지 않게

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🟠 이메일로 사용자를 찾는 내부 API(`/user/id`)가 외부에 열려 있었다 | 이메일 목록만 있으면 가입 여부 확인 + 이름·닉네임·이미지 수집. 전체 이메일을 찍는 로그도 있었다 | 열거에 쓰일 수 있는 조회는 내부 전용 | ✅ `InternalCallGuard`(외부는 관리자만), 로그 제거 | [docs/27 §9](27-security-review.ko.md) |
| 🟠 거절 응답이 403(있음)과 404(없음)로 갈렸다 | 403 은 "그 주문·그 id 는 있다" 를 알려 준다 | 남의 것과 없는 것은 같은 응답 | ✅ 주문·사용자 조회 모두 404 로 통일 | [docs/27 §1~4·§9b](27-security-review.ko.md) |
| 🟠 비밀번호 찾기·잠금·미인증 판정이 가입 여부를 흘렸다 | 미가입 500 / 가입 200, 없는 계정 실패도 카운트, 미인증 판정을 비밀번호 검사 앞에 둘 뻔했다 | 가입 여부와 무관한 동일 응답, 부채널 차단 | ✅ 응답 통일(재설정·재발송), 없는 계정은 세지 않음, 미인증은 비밀번호가 맞을 때만 알린다 | 커밋 `a298a3e`·`03422d0`·`5b4e643` |

### 1.5 비밀 · 자격증명 · git 이력

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 공개 저장소 이력에 AWS 액세스 키·Gmail 앱 비밀번호·Google/GitHub OAuth 시크릿·JWT 서명 키·DB 비밀번호·Cloudinary 키가 약 2년간 | 공개 저장소의 키는 자동 스크레이퍼가 수 분 안에 수집한다. 이력을 지워도 값은 살아 있다 | 유출 = 즉시 폐기·재발급이 먼저, 이력 정리는 뒷정리 | ✅ AWS·Google·GitHub·Cloudinary 폐기, JWT·DB 는 배포 때 새로 생성한 값 사용. 저장소 삭제 후 비공개 재생성, `ops/20 --remote` 로 PR 참조 0·자격증명 0 확인 | [docs/30 §3·§6](30-git-history-secret-exposure.ko.md) |
| 🔴 force-push 후에도 닫힌 PR 의 `refs/pull/*` 가 옛 커밋을 붙잡고 있었다 | 브랜치만 보는 검사는 "끝났다"는 거짓 보고가 된다. 그 참조는 클라이언트가 지울 수 없다 | 원격을 미러로 받아 PR 참조까지 검증 | ✅ 저장소 재생성만이 실제로 끝냈다. `ops/20 --remote` 가 정리 후 필수 검사 | [docs/30 §4.5](30-git-history-secret-exposure.ko.md) |
| 🔴 프론트 `REACT_APP_*` 에 클라이언트 시크릿(Cloudinary) | 빌드 시 번들에 박혀 **모든 방문자**에게 전달 — 이력 정리로도 회수 불가 | 프론트엔드에 비밀을 두지 않는다. 결제·미디어 삭제는 서버가 | ✅ Cloudinary 경로 코드 삭제 + 키 폐기. "PayPal 시크릿" 은 값을 확인해 보니 공개 client id 였다(이름만 보고 분류한 오류를 정정) | [docs/30 §1·§7](30-git-history-secret-exposure.ko.md) · 커밋 `973d0e5` |
| 🔴 Debezium 커넥터 JSON 4개에 DB 비밀번호·호스트 평문 | 저장소를 읽는 누구에게나 자격증명과 정찰 정보 | 설정 파일에는 참조만, 값은 시크릿 provider | ✅ Kafka Connect `FileConfigProvider` 참조. 유출 값은 **로그인 실측**(대조군·양성 대조 포함)으로 운영 DB·Redis·모든 Secret 어디서도 통하지 않음을 확인 | [docs/13 §1](13-secrets-dbha-es-search.ko.md) · [docs/30 §6](30-git-history-secret-exposure.ko.md) |
| 🟠 이력 스캐너가 JSON 형식(`"database.password"`)을 못 봐 "0건", 부분 치환·자기 표식에 속았다 | 거짓 초록불은 스캐너가 없는 것보다 나쁘다 | 형식별 규칙(gitleaks 류), 치환 뒤 HEAD 무결성 검사 | ✅ JSON 규칙 추가, 줄 끝 표식만 무시, HEAD 가 한 글자라도 바뀌면 중단 | [docs/30 §4·§6](30-git-history-secret-exposure.ko.md) |
| 🔴 JWT 키 플레이스홀더(36바이트)가 HS256 최소 길이를 넘어 **안 채워도 기동**됐다 | 저장소에 공개된 문자열로 토큰을 서명하는 상태로 정상 기동 — 누구나 토큰 위조 | 필수 비밀이 비면 기동 실패(fail closed) | ✅ 플레이스홀더를 10바이트로 줄여 `WeakKeyException` 으로 기동 실패. 생성법(`openssl rand -hex 32`) 문서화 | 커밋 `be3fa49` · [docs/33 §7](33-deploy-runbook.ko.md) |
| 🟠 `.gitignore` 가 `k8s/20-secret.yaml` 하나만 막아 편집기 백업본(`.save`)이 떠 있었다 | `git add -A` 한 번이면 전 자격증명이 공개 저장소로 | 비밀 파일은 접미사까지 패턴으로 제외 | ✅ `k8s/20-secret.yaml*`, `*.save`, `*.bak` | 커밋 `78ac7ab` |
| 🟠 빌드가 gitignore 된 비밀 파일에 의존(B-20), 설정에 원저자 PC 경로(B-28), 배포 호스트명 하드코딩 | 새 클론은 빌드조차 불가. 가입 메일 링크가 항상 한 호스트를 가리켰다 | 12-factor: 설정과 비밀은 환경에서 | ✅ `optional:` import + 기본값 분리, 경로·호스트는 환경변수(`app.public-url`, `db-host`), B-28 | [docs/14](14-boot-test-findings.ko.md) · [docs/18 §1·§6.1](18-certificates.ko.md) |
| 🟠 `REACT_APP_*` 를 k8s Secret 에 두었다 | nginx 정적 이미지라 아무도 읽지 않는데 "채웠으니 됐다" 고 믿게 만든다 | 빌드 시점 값은 빌드 인자로 | ✅ Secret 예시에서 제거, B-51 | 커밋 `6a485f4` |

### 1.6 결제 검증 · 금액 신뢰

돈이 걸린 정합성 전반은 §2 에 있다. 여기는 **"자기 것에 대해 거짓말할 수 있는가"** 에 해당하는 것만 적는다.
docs/27 이 "남의 것에 손이 닿는가"만 물어서 놓친 부류다.

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 브라우저가 보낸 금액·결제 상태·PayPal 주문 ID 를 **검증 없이** 아웃박스에 | 로그인만 하면 결제 없이 주문 완료. 자기 주문이라 소유권 검사로는 못 막는다 | 결제 사실은 서버가 PG 에 직접 확인. 확인 불가면 거부 | ✅ PG 조회(COMPLETED)·DB 총액 대조·통화 대조·결제 참조 부분 유니크 인덱스 넷이 함께. 검증 기본 켜짐, 자격증명 없으면 기동 실패 | [docs/35](35-payment-verification.ko.md) · `PaypalPaymentVerificationTest` |
| 🔴 주문 총액을 **브라우저가 계산**해 보냈고, 쿠폰 할인 기준도 본문 `totalPrice` 였다 | `total: 1` 을 보내고 1달러를 결제하면 결제 검증이 정상 통과. 자물쇠는 튼튼한데 열쇠를 손님이 깎았다 | 금액은 서버가 가격표로 계산 | ✅ `OrderRequest` 에서 금액 필드 **삭제**. 총액은 장바구니(카탈로그 가격), 할인은 coupon 견적. 못 읽으면 본문 값으로 대신하지 않고 거절 | 커밋 `86010e1` · [docs/40 #10](40-frontend-bug-list.ko.md) |
| 🔴 Stripe 웹훅은 인증 없이 공개여야 한다 | 서명을 안 보면 URL 에 JSON 을 POST 하는 것만으로 결제 조작. 서명만 보면 가로챈 요청을 몇 달 뒤 재전송 | 서명 + 타임스탬프 허용오차 | ✅ `StripeWebhookVerifier`(경계 13건 고정), 웹훅 라우트를 JWT 라우트보다 앞에 | [docs/49 §4](49-payment-providers.ko.md) |
| 🟠 포트원 V1 웹훅에는 서명이 없다 | 누구나 호출 가능 | 본문은 단서로만 쓰고 PG 에 재조회 | ✅ `imp_uid` 하나만 취해 재조회, `merchant_uid`·금액을 DB 와 대조. V2 는 Standard Webhooks 서명까지 | [docs/49 §5](49-payment-providers.ko.md) |
| 🟠 무통장 입금 확인에 금액을 받으면 그 값을 믿게 되고, 고객이 무통장 환불을 직접 부르면 돈 없이 REFUNDED | 확인 주체가 사람인 수단에서 신뢰 경계가 흐려진다 | 확인 행위와 금액 입력을 분리, 송금 기록은 관리자만 | ✅ 입금 확인은 금액 없이 관리자만, 무통장 환불은 관리자만. 결제를 건너뛰는 개발 경로는 **의도적으로 만들지 않았다** | [docs/49 §11](49-payment-providers.ko.md) · [docs/35 §6](35-payment-verification.ko.md) |

### 1.7 개인정보 · 로그

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 기본 SMS 발송기(로그만 남김)가 **인증번호가 든 본문**을 INFO 로 | 로그를 읽는 사람이 남의 번호로 2FA 통과. 전역 마스킹으로도 못 막는다(6자리 숫자는 주문번호와 구별 불가) | OWASP Logging: OTP·토큰·비밀번호는 어떤 레벨로도 기록하지 않는다 | ✅ 가린 수신자·글자 수·한 건 여부만. `LoggingSmsSenderTest` 가 포맷 전 인자까지 검사 | [docs/51 §1](51-logging.ko.md) |
| 🔴 액세스 토큰을 로그에 찍었다 — SSE 접속마다, Kafka 리스너 셋 중 둘. 한 줄은 이름이 "Session ID" 였다 | 로그를 읽는 사람이 곧 그 사용자. 이름이 틀려 리뷰로 못 잡았다 | 토큰은 존재 여부만 기록 | ✅ 제거. B-52 가 포맷 문자열이 아니라 **값의 출처**로 판정 | 커밋 `97225fc` · B-52 |
| 🔴 Feign 이 액세스 토큰을 **쿼리 파라미터**로 보냈다 | URL 은 액세스 로그·프록시·예외 메시지에 남는다 — 실제로 운영 로그에 평문 토큰이 찍혔다. 인터셉터가 헤더로 이미 전달하므로 필요도 없었다 | 토큰은 헤더로만 | ✅ 파라미터 삭제(헤더로 재선언도 하지 않음 — 중복 전송). `FeignContractTest` | [docs/47 §5-4](47-order-create-failure-visibility.ko.md) |
| 🟠 마스킹이 호출하는 쪽 재량 — cart·order·user 에 이메일 평문 로그 약 20곳 | "기억해서 가리는" 방식은 반드시 한 곳에서 샌다 | 출력 단계 중앙 마스킹(심층 방어) | ✅ common 의 logback 한 벌 + `SensitiveDataMasker`(Bearer·JWT·비밀 key=value·주민번호·카드·전화·이메일), 서비스별 패턴 금지 B-57, 모든 출력의 마스킹 통과 B-57b | [docs/51 §2](51-logging.ko.md) |
| 🟠 메일 링크(가입 확인·재설정)는 그 자체가 자격증명인데 params 가 로그·DB 에 남을 수 있었다 | 토큰을 해시로 저장한 방어가 원문 링크 하나로 무의미해진다 | 자격증명은 필요한 시간만 보관 | ✅ `toString` 은 키 이름만, 종료 상태에서 즉시 NULL, 미발송 행은 24시간 뒤 삭제, 토큰을 멱등 키로 쓰지 않음 | [docs/29 §4.2](29-transactional-email.ko.md) |
| 🟠 채팅의 신원이 **이메일**이었다 — 메시지마다 발신자 이메일이 방의 모든 참여자에게 전달됐고(개발자도구로 보인다), 입장·퇴장 안내는 `"<이메일> entered."`, 회원 행이 없으면 닉네임이 이메일 아이디. 가입 이벤트가 전부 DLT 로 가던 동안(§3) 사실상 모든 채팅 회원이 그랬다 | 상대에게 계정 식별자를 넘긴다. "로컬 파트만" 은 가린 것이 아니다 — 대개 아이디 그 자체다. 이메일은 바뀌는 값이라 신원 키로도 틀렸다 | 신원은 불변 id(토큰의 uid), 표시 이름은 표시용 필드에서 — 식별자에서 파생하지 않는다 | ✅ STOMP 세션·회원 조회·참여자 확인·메시지 발신자(`senderId`, 화면으로는 문자열)·프론트 판별을 전부 uid 로. 메시지·회원 행·가입 이벤트에서 이메일 필드 자체를 제거(Avro v3/v2 + 옛 스키마 이력으로 배포 중에도 읽는다, 회원 컬럼은 V5 로 삭제), 옛 몽고 기록은 기동 백필로 id 로 바꾸고 이메일 삭제(발신자를 모르면 이메일만), 탈퇴 시 대화 기록의 닉네임 사본도 지움. 프론트의 uid 는 토큰 원문에서 글자로(19자리가 JS Number 에서 뭉개진다) | [docs/48 §6-⑤·⑥](48-user-replication-debt.ko.md) · 커밋 `213ce70`·`dca017e` |
| 🟠 탈퇴해 원본을 익명화해도 **chat 복제본에 이메일·이름·사진이 남았다** | 삭제 요청을 원본에서만 이행하면 복제본이 새는 곳이 된다 | 삭제·익명화는 복제본까지 전파 | ✅ `user-deleted` 로 익명화(행은 남긴다 — 대화 기록이 가리킨다), 이후 늦게 온 변경·가입 이벤트가 되살리지 못한다 | [docs/48 §6-⑤](48-user-replication-debt.ko.md) · 커밋 `213ce70` |
| 🟠 사가 메시지에 **사용자 액세스 토큰 원문** — 결제 요청(`PaymentRequest`, 본문·헤더, 그리고 order 의 **아웃박스 행**), 결제 응답 헤더, 상품 갱신(`product-update`), cart-empty·cart-rollback 본문, coupon-use 헤더 | 토픽 보존 기간 동안 브로커에, 아웃박스 행은 DB·백업에 평문으로 남는다 — 읽을 수 있으면 곧 그 사용자. 그런데 **어디서도 검증하거나 쓰지 않았다**: pay 는 "있는가" 만 봤고, pay 가 돌려준 헤더는 스케줄러 스레드의 빈 문맥이라 사실상 `Bearer null`, catalog 는 읽지 않았다. cart 가 쓴 유일한 이유(캐시 키 이메일)는 따로 없앴다. 존재 검사는 부작용도 있었다 — 토큰이 없는 정상 경로(환불·무통장 입금 확인)가 CDC 로 오면 **결제가 DLT 로 갔다** | 토큰을 메시지에 싣지 않는다. 인증처럼 보이는 검사는 인증이 아니다 — 토픽에 쓸 주체는 브로커 ACL 로 제한한다 | ✅ 다섯 스키마(v2)에서 토큰 필드 제거 + 이력(배포 중 옛 레코드도 읽는다), 소비자의 헤더 존재 검사 제거, order 의 `recordPayment` 토큰 인자 사슬과 결제 확인 엔드포인트 셋의 쓰지 않는 `Authorization` 인자 제거, **V15 가 아웃박스 행에 남은 토큰을 지운다**(PG16 에서 확인). 전환 릴리스 동안 두었던 옛 소비자용 비밀 아닌 표지 헤더(`LegacyAuthorizationHeader`)도 전환 정리에서 지웠다 — 사가 메시지에 `Authorization` 헤더 자체가 없다 | `ChatMessageSchemaHistoryTest`(v1 을 읽고 토큰은 버린다, 지금 스키마에 토큰 필드 없음) |
| 🟡 로그 수집기(Alloy)에 저장소 루트를 마운트하는 구성 | gitignore 된 비밀 파일까지 컨테이너에 보인다 | 최소 마운트 | ✅ 모듈별 `logs` 디렉터리만 읽기 전용, B-58 | [docs/51 §4](51-logging.ko.md) |
| 🟡 config-server 로그 레벨 DEBUG | 설정 저장소를 연결하는 순간 로그가 **내보낸 설정의 사본**이 된다 | 운영 기본 INFO | ✅ INFO, 필요할 때만 환경변수로 올림 | 커밋 `9b6bc17` |

### 1.8 전송 보안 · 인증서

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 게이트웨이 `useInsecureTrustManager: true` | 다운스트림 TLS 검증 전면 무력화(MITM). README 의 mTLS 신뢰 체계를 스스로 깼다 | 진짜 트러스트스토어로 검증 | ✅ 기본 `false`(`GATEWAY_INSECURE_TRUST_MANAGER`) | [docs/18 §3](18-certificates.ko.md) |
| 🔴 인증서 발급 절차가 없고, 원저자 PC 경로, `.p12` 를 `"JKS"` 로 선언한 클래스 19개 | 새 환경에서 아무것도 못 띄움. mTLS 를 켜는 순간 전 서비스 기동 불가(잠복) | 발급·주입·검증의 자동화 | ✅ `ops/07`(로컬 CA/Let's Encrypt → PKCS12/JKS → Secret, 자체 검증 6종), 경로·형식 주입 + B-28/B-29 | [docs/18](18-certificates.ko.md) |
| 🔴 트러스트스토어에 내부 CA 1개만 넣고 JVM 전역으로 지정 | 기본 cacerts 151개를 **대체**해 S3·PayPal·SMTP·OAuth 가 전부 PKIX 실패. 자격증명 문제로 오인됐다 | 신뢰 범위는 필요한 만큼 — 외부 통신용은 공인 CA 위에 얹는다 | ✅ PKCS12 = JDK cacerts + 내부 CA, Kafka 용 JKS 는 내부 CA 만. 항목이 1개 이하면 경고 | 커밋 `4dbd338` |
| 🟠 트러스트스토어를 Spring SSL 설정에만 줬다 | Eureka 등록·Feign 은 JVM 기본 저장소를 써서 파드는 Running 인데 등록 0 | JVM 수준 신뢰 | ✅ `JAVA_TOOL_OPTIONS` 로 JVM 전역 | 커밋 `466e790` |
| 🟠 관리형 인프라에 붙을 때 **빈 스토어 경로를 무조건 로딩**(Kafka 설정 24벌 복제 포함) | 공개 CA + 비밀번호 방식에 붙는 순간에만 터진다 | 경로가 없으면 JVM 기본(공개 CA) | ✅ `KafkaSecuritySettings` 한 곳, 비면 넣지 않음, SASL 자격증명 없으면 기동 실패. B-35 가 재분산을 막는다 | [docs/32 §4](32-external-managed-infra.ko.md) |
| 🟠 외부 노출 방식 미정(인바운드 포트를 열어야 하는 구성) | 인증서 발급·갱신과 방화벽을 직접 떠안는다 | 엣지 종단, 인바운드 최소화 | ✅ Cloudflare Tunnel — 인바운드 포트 0, API 는 경로 정규식에 맞는 것만 게이트웨이로. 터널→게이트웨이 구간은 자체 서명이라 `noTLSVerify`(클러스터 내부) | [docs/31 §1](31-kustomize-oci-tunnel.ko.md) · `k8s-overlays/prod/cloudflared.yaml` |
| 🟡 www 없는 주소로 열면 API 가 전부 CORS 403 | CORS 를 넓혀 해결하면 쿠키·세션이 호스트별로 갈린다 | 정규 호스트 하나(엣지 301) | ✅ 루트→www 301 + 운영 번들의 출처 불일치 리다이렉트. CORS 허용 목록은 명시 목록 그대로 | [docs/33 §6](33-deploy-runbook.ko.md) · 커밋 `ea23016` |

### 1.9 관리 엔드포인트 · 노출 면

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 config-server actuator `include: "*"` + 무인증 | `/env`·`/configprops`·`/heapdump` 로 DB 비밀번호·JWT 시크릿. 파일에서 비밀을 빼도 HTTP 로 읽혔다 | 최소 노출, 민감 엔드포인트는 인증 뒤에만 잠시 | ✅ `health, info, prometheus` + `show-details: never` | [docs/27 §7](27-security-review.ko.md) · `config-server/.../application-local.yml` |
| 🟠 게이트웨이 actuator 에 `gateway`(POST 로 라우트 추가·삭제)·`beans` | 인증 없이 트래픽을 임의 목적지로 돌릴 수 있었다 | 위와 같다 | ✅ 둘 제거. `refresh`/`busrefresh` 는 남아 있다(§10) | [docs/27 §8](27-security-review.ko.md) |
| 🔴 운영 번들이 소스맵(`sourcesContent` 에 원본 1,084개 파일)을 서빙 | 내부 엔드포인트와 취약점 설명 주석까지 공개 | 운영에서 맵을 공개하지 않는다 | ✅ `hidden-source-map` + nginx 가 `.map` 을 404 — 두 겹. 이력의 `frontend/dist.zip` 은 여전히 추적 중(§10) | [docs/40 #14](40-frontend-bug-list.ko.md) · [docs/38 §3 #1](38-code-review.ko.md) |
| 🟠 S3 업로드가 객체마다 public ACL — `PutObjectAcl` 권한 필요, 점검은 ACL 없는 요청을 보내 거짓 초록불 | 최소 권한 위반, 점검이 실제 요청과 다른 권한을 시험 | 공개 읽기는 버킷 정책(prefix), 키는 `PutObject` 만 | ✅ 버킷 정책으로 이동, 점검과 업로드가 같은 요청 생성 함수를 쓴다. 새 AWS 키는 해당 버킷 하나로 권한 축소 | 커밋 `4c896d9` · [docs/30 §6](30-git-history-secret-exposure.ko.md) |

---

## 2. 결제 · 주문 정합성

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 결제 중복 감지가 한 번도 동작한 적 없음(`"23000" == getSQLState()`) | String 을 `==` 로, 값도 PostgreSQL 것이 아니었다 | 벤더 코드를 손으로 비교하지 않는다 | ✅ `DuplicateKeyException` 으로 포착 | [docs/21 §4](21-horizontal-scaling.ko.md) |
| 🔴 결제 확인이 한 메서드에 PayPal 전용 로직과 공통 규칙이 섞여 있었다 | 결제수단을 추가할 때마다 금액 대조가 복사되고, 하나만 빠져도 **그 수단으로만** 무료 주문 | 포트/어댑터 — 불변 규칙은 한 곳 | ✅ `PaymentGateway` 포트 + `recordPayment` 한 곳 + `PaymentGateways` 레지스트리. 새 제공자는 규칙을 자동 상속 | [docs/49 §1~2](49-payment-providers.ko.md) |
| 🔴 결제 참조 컬럼을 넓히는 중, 옛 이미지가 쓴 결제를 새 코드가 "미처리"로 보고 **이중 발행**할 뻔했다 | 롤링 배포 중 짧은 창에서만 일어나 재현조차 어렵다 | expand-contract | ✅ 새 컬럼 추가·백필, 옛 컬럼은 다음 릴리스까지, 멱등 판정이 옛 컬럼도 본다. B-54 가 되돌리기 불가 마이그레이션 표기를 강제. 정리(contract)도 **두 릴리스로** — 한 번에 컬럼을 지우면 롤링 중 옛 파드의 주문 조회가 전부 실패한다. 1단계(매핑 제거 + V17 값 이관)와 2단계(V18 DROP) 적용 — 2단계는 1단계를 건너뛴 배포면 Flyway 이력을 보고 **스스로 멈춘다**(PG16 에서 네 경우 확인) | [docs/49 §3](49-payment-providers.ko.md) |
| 🔴 결제 요청 DTO 에는 제공자 필드(`paymentProvider`·`providerPaymentId`)가 있었지만 **Avro 스키마에는 없었다** — 릴레이 경로에서 매핑이 조용히 버려, pay 는 Stripe·포트원·무통장 결제의 참조를 받은 적이 없다. pay 의 테이블도 PayPal 전용 컬럼뿐이었다 | DTO 와 스키마가 따로 놀면 컴파일도 테스트도 통과한다. 그리고 스키마에 필드를 더하는 순간 **지문이 바뀌어**, 동시에 교체되는 배포에서는 옛 소비자가 새 레코드를 못 읽는다(DLT·스트림 정지) | 읽는 쪽 먼저 — 레지스트리가 없으면 "읽기는 새 스키마, 쓰기는 옛 스키마" 로 한 릴리스를 둔다 | ✅ 스키마 v3(끝에만 추가) + 이력 v2. 이번 릴리스는 모든 소비자가 v3 를 읽고 order 는 **v2 작성 스키마로 쓴다** — v2 만 아는 옛 소비자가 읽는 것을 테스트로 고정. pay 는 V5 로 제공자 중립 컬럼(유형별 이관, PG16 확인). 다음 릴리스에서 v3 쓰기 + pay V6(재이관·옛 컬럼 삭제) — 앞 릴리스를 건너뛰면 order V18 이 기동을 막아 v3 레코드도 나가지 않는다. 필드를 **빼는** `paypalOrderId` 제거는 위치가 밀려 같은 수가 안 통해 반대로 간다 — 다음 스키마 v4 를 디코더에 먼저 넣고(C1) 다음 릴리스에 v4 로 쓴다(C2, C1 을 건너뛰면 order V20 이 막는다), v1·v2 읽기 경로는 DLT 보존 기간 뒤(C3) | [docs/49 §3](49-payment-providers.ko.md) · `PaymentRequestSchemaEvolutionTest` · `PaymentProviderRecordTest` · 재발 방지: 모든 매핑의 이름·왕복 대조 `AvroMappingContractTest`·`ChatMessageAvroContractTest` |
| 🔴 `Order.total` 이 `int` — $59.98 이 59 가 됐다 | 실제로 98센트를 덜 받았고, 검증도 같은 잘린 값으로 통과 | 금액은 decimal | ✅ V7 `numeric(19,2)`, 상점 통화 자릿수로 반올림, DTO 도 `BigDecimal`. 옛 주문의 잘린 센트는 복구 불가(기록 없음) | [docs/49 §6](49-payment-providers.ko.md) |
| 🟠 결제수단마다 청구 통화가 다르다(KRW·JPY·USD) | 자릿수가 틀리면 JPY 100배 청구, 환율 누락이 "1:1 로 조용히 청구" 가 될 수 있었다 | 통화별 최소 단위, 주문 시점 환율 고정 | ✅ 청구액·통화·환율을 주문에 고정(V6), `PaymentCurrencyPolicy` 한 곳. 실시간 환율은 선택이고 고정값 대비 편차 상한(기본 30%), 환율 없으면 주문 거절 | [docs/49 §6](49-payment-providers.ko.md) |
| 🔴 CHECK 제약이 enum 보다 낡았다 — Stripe 결제가 pay-service 저장에서 실패, 주문이 **영원히 NOT_PROCESSED** | Hibernate `validate` 는 CHECK 를 안 보고, 컨테이너 기동 테스트도 못 잡는다. 그 값이 처음 저장될 때만 터진다 | enum 과 DB 제약을 함께 변경 | ✅ pay V3·order V12·notification V6 로 확장. B-55 가 enum ↔ 마지막 CHECK 를 소스 대조 | [docs/49 §8](49-payment-providers.ko.md) |
| 🔴 Avro `PaymentType` 심볼이 PAYPAL 하나, Stripe/포트원 끈 환경에서 order-service 기동 실패 | 새 수단의 결제 이벤트가 변환에서 죽을 참이었고, 조건 없는 서비스 빈이 기본 설정에서 기동을 막았다 | 스키마·빈 조건을 기능 스위치와 함께 | ✅ 심볼 채움(enum default 로 옛 소비자 호환), `@ConditionalOnProperty` + `OptionalPaymentServicesWiringTest` | [docs/49 §8·§10](49-payment-providers.ko.md) |
| 🔴 환불에 규칙이 없었다 | 제공자가 돈을 돌려준 뒤 우리가 거부하면 "돈은 나갔는데 주문은 결제 상태" | 거부 가능한 검사는 PG 호출 전에, 금액은 서버가, 제공자 멱등 키 | ✅ `refundOrder` 한 곳: 소유권 → 멱등 → 결제 여부 → 배송 전 여부 → 호출 → 응답 대조. 부분 환불은 `RefundPricing`(합이 정확히 total), `refundKey` 를 PayPal-Request-Id·Idempotency-Key 로 | [docs/49 §10](49-payment-providers.ko.md) |
| 🔴 pay-service 가 환불(CANCELED)을 **결제의 중복**으로 오인 | 결제 행은 영원히 COMPLETED, 쿠폰·재고 복원과 환불 알림 전부 없음. 주문만 REFUNDED 라 화면은 멀쩡해 보였다 | 멱등 키에 "무엇을 하려는가"가 들어가야 한다 | ✅ 중복 판정에 요청 상태 포함, 알림은 `PAYMENT_REFUNDED` 로 분리 | [docs/49 §10](49-payment-providers.ko.md) · `PaymentIdempotencyTest` |
| 🟠 재고 복원 시점이 없었다 | 발송 뒤 환불에 재고를 바로 늘리면 **없는 물건을 판다** | 물건이 창고에 있을 때만 재고 | ✅ 발송 전 환불은 즉시, 발송 뒤는 관리자 반품 입고 때. 복원은 차감의 역(같은 토픽 `restock=true`), 멱등 키 분리 | [docs/49 §10](49-payment-providers.ko.md) |
| 🔴 `cart-empty` 가 장바구니를 **통째로** 지웠다 | 확정이 늦어지는 사이 새로 담은 물건까지 사라졌다 | 보상·후속 작업은 대상 범위를 명시 | ✅ 주문 품목만 뺀다(`CartEmptyRequest.items`, 비면 옛 동작) | [docs/49 §10](49-payment-providers.ko.md) |
| 🟠 취소된 주문에 늦게 도착한 결제를 조용히 기록 | 사가가 상태를 못 바꿔 "돈은 왔는데 주문은 취소" 가 아무 데도 안 보인다 | 돈이 걸린 이상 상태는 사람에게 | ✅ 거절 + error("수동 환불 필요") → Loki 경보. 가상계좌 취소는 PG 계좌를 먼저 닫는다 | [docs/49 §11](49-payment-providers.ko.md) |
| 🟠 주문 생성이 DB 트랜잭션 안에서 cart·coupon 을 호출 | cart 가 느리면 order 의 DB 풀이 먼저 마르고, 최악 16초 커넥션 점유 | 트랜잭션 안에서 원격 호출 금지 | ✅ 원격 호출은 `NOT_SUPPORTED` 인 상위 메서드로, 영속 단계는 인자로 받는다. `OrderDataService` 에 Feign 필드가 **없다는 것**까지 테스트로 고정 | [docs/37](37-transaction-boundary-audit.ko.md) · 커밋 `78803e0` |
| 🔴 cart 의 계약 변경(쿼리 → 헤더)을 order Feign 이 못 따라가 **배포 이후 주문 0건** | 프론트 경로는 멀쩡해 보였고 컴파일러는 모듈 간 애노테이션 계약을 모른다 | 서비스 간 계약 테스트 | ✅ Feign 시그니처 복구 + `FeignContractTest`(리플렉션) | [docs/47 §5-3](47-order-create-failure-visibility.ko.md) |
| 🟠 주문 생성 실패가 전부 **로그 없는 400**, 본문은 JVM NPE 문구 | 원인 셋이 하나로 뭉개지고 내부 구현이 사용자 화면에 샜다. cart 장애도 "네 요청이 틀렸다" | 사용자 오류 4xx, 시스템 오류 5xx + 스택, 내부 메시지 비노출 | ✅ `DomainException` → 400 + warn, 그 외 → 500 + error. `null` 반환 제거, Feign 오류 디코더가 상태·응답을 보존 | [docs/47 §4·§5-2](47-order-create-failure-visibility.ko.md) |
| 🟠 결제 확정 뒤 상세가 NOT_PROCESSED — 결제 버튼 재노출(이중 청구 유도) | 커밋 **전에** 캐시를 비워 옛 값이 다시 30분 캐시됐다 | 캐시 무효화는 커밋 뒤 | ✅ 상태 변경마다 `OrderCacheEvictor` + `RedisCacheManager.transactionAware()` | [docs/25 §11.12](25-delivery-service-design.ko.md) · `RedisCacheTransactionAwareTest` |

---

## 3. 메시징 · 사가 · 아웃박스

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 Kafka 컨슈머 에러 처리 전무 | poison message 하나가 파티션을 영원히 정지 | DLQ + 백오프 + `ErrorHandlingDeserializer` | ✅ 전 서비스 `DeadLetterPublishingRecoverer`, 배치 리스너는 인덱스 루프 + `BatchListenerFailedException`. B-41(DLT 목록 ⊇ 리스너 토픽)·B-42(모든 팩토리) | [docs/11 §3](11-observability-and-kafka-reliability.ko.md) · [docs/12 §1](12-dlq-rollout-idempotency-alerts.ko.md) |
| 🔴 재전달 시 **재고 이중 차감** | at-least-once 에서 금전 손실 | 소비 측 멱등 — 처리 원장을 업무 변경과 같은 트랜잭션에 | ✅ `processed_event` + 자연 키 `order-<id>`(사가 재실행에도 1회), 복원은 별도 키 | [docs/12 §2](12-dlq-rollout-idempotency-alerts.ko.md) |
| 🔴 아웃박스 폴러가 **레플리카마다 같은 이벤트**를 발행 | 결제 요청 이중 발행. HPA 가 부하만 오르면 저절로 터뜨릴 구조였다 | `FOR UPDATE SKIP LOCKED` 선점 | ✅ 선점 + 트랜잭션 안 동기 발행. 동시 트랜잭션 2개로 재현·검증 | [docs/21 §1](21-horizontal-scaling.ko.md) |
| 🔴 브로커가 잠깐 흔들리면 이벤트 영구 유실(FAILED 로 적고 STARTED 만 조회) · Authorization 헤더가 없으면 배치가 통째로 조용히 사라짐 | 실패가 기록되지 않거나 다시 집히지 않았다 | 일시 실패는 재시도, 거부는 보이게(DLT) | ✅ 재시도 가능한 상태 유지, 거부는 예외 → DLT | [docs/21 §1·§5](21-horizontal-scaling.ko.md) |
| 🔴 `user-created` 가 dual-write(비동기 `send` 결과를 아무도 안 봄)였고, 발행 지점이 **아무도 지나지 않는** 메일 인증 경로였다 | 토픽이 통째로 비어 주문 400·채팅 진입 실패·가입 쿠폰 미발급. 아웃박스는 만들어 놓고 주석 한 줄 차이로 안 쓰고 있었다 | transactional outbox, 발행 결과 확인 | ✅ 계정 저장과 **같은 트랜잭션**에 아웃박스 행(`MANDATORY`), `UserOutboxScheduler`(SKIP LOCKED·동기 발행·FAILED 집계). `UserCreatedOutboxTest` | [docs/48 §2·§6](48-user-replication-debt.ko.md) |
| 🔴 chat 은 `user-created` 를 **한 번도 받지 못하고 있었다** — 토픽을 Avro 로 바꾸면서(`459126e`) chat 컨슈머만 `JsonDeserializer` 로 남았다. Avro 배선 가드의 목록에도 빠져 있었다 | 모든 가입 이벤트가 역직렬화 실패로 DLT. 채팅 회원은 접속 순간 즉석으로만 생겼고 이름이 원본 값이었던 적이 없다. 즉석 행이 먼저 있으면 늦게 온 이벤트는 id 충돌로 삼켜졌다 | 형식 전환은 **소비자 전수** 목록으로 검증, 가드의 목록은 코드에서 뽑는다 | ✅ `AvroDtoDeserializer`(Avro·JSON 모두), 즉석 행은 가입 이벤트가 원본 프로필로 채운다. `AvroWiringGuardrailTest` 에 chat 추가(더 받지 않는 order 는 제거 — 이 가드는 `a8a421c` 부터 실패하고 있었다) | [docs/48 §6-⑤](48-user-replication-debt.ko.md) · 커밋 `213ce70` |
| 🟠 복제본이 가입 순간에서 멈춰 있었다 — 프로필 변경·탈퇴 이벤트가 없었다 | 바꾼 닉네임이 채팅에 안 보이고, 탈퇴가 복제본에 닿지 않는다 | 생성·변경·삭제 전부 아웃박스로, 받는 쪽은 순서 역전에 안전하게 | ✅ `user-updated`(최신 상태 전체 + `updatedAt`, 이메일 없음)·`user-deleted` 를 요청과 같은 트랜잭션의 아웃박스로. chat 은 `profile_updated_at` 보다 오래된 이벤트를 버린다(릴레이 재시도로 순서가 뒤집힐 수 있다). 닉네임 UNIQUE 제거(V4 — 동명이인·"탈퇴한 사용자" 가 실패한다) | [docs/48 §6-⑤·§7](48-user-replication-debt.ko.md) · `UserLifecycleConsumerTest` |
| 🟠 아웃박스 릴레이의 Kafka 트랜잭션, 재시작마다 바뀌는 UUID `transactional.id` | 릴레이는 DB 커밋 **뒤에** Kafka 커밋이라 보장이 아니라 유실 창이었고, UUID 는 좀비 펜싱을 무력화 | 트랜잭션은 필요한 곳에만, id 는 안정적으로 | ✅ 릴레이는 멱등 + `acks=all`, 보상 프로듀서 넷만 `<접두사>-<HOSTNAME>`. `KafkaTransactionGuardrailTest` | [docs/38 §1.17](38-code-review.ko.md) · 커밋 `935ef76` |
| 🔴 트랜잭션 프로듀서를 HTTP 요청 뒤(트랜잭션 없음)에서 사용 | 환불·반품 입고의 재고 복원·쿠폰 롤백이 매번 "No transaction is in process" 로 실패 | 트랜잭션 경계를 명시 | ✅ `common/kafka/TransactionalSends` — 없으면 한 건짜리 트랜잭션, 있으면 합류, 비트랜잭션이면 그냥 발송 | [docs/49 §10](49-payment-providers.ko.md) · `TransactionalSendsTest` |
| 🟠 발행 9곳에 파티션 키가 없고 주 토픽은 파티션 1 | 같은 주문의 이벤트 순서가 보장되지 않고 확장 여지도 없다 | 집계 id 로 키잉 | ✅ 파티션 키 + `AggregateKeyPartitioner`, CDC 커넥터도 릴레이와 같은 토픽·키 규칙, 협력적 리밸런스 | [docs/38 §1.15](38-code-review.ko.md) · 커밋 `db32bf8`·`27dd495` |
| 🟡 이벤트 페이로드에 스키마 계약이 없었다 | 필드 변경이 소비자를 조용히 깨뜨린다 | 스키마 기반 직렬화와 호환 규칙 | ✅ Avro(레지스트리 없이 단일 객체 인코딩, JSON 하위 호환). `AvroWireTest` | [docs/38 §1.16](38-code-review.ko.md) |
| 🟠 결제 확인 뒤 PAID 까지 **평균 10초, 길면 20초**(실측 약 12초) — 사가가 10초 아웃박스 폴러를 **두 번**(order 결제 요청, pay 결제 응답) 건넜다 | 결제를 마친 사용자가 "확인 중" 을 오래 보고, 화면의 30초 시한에 가까워진다. 폴링 간격을 줄이면 빈 조회만 는다 | 아웃박스 릴레이는 커밋 알림(after-commit hook·CDC)으로 즉시, 폴러는 되줍기용 안전망 | ✅ `OutboxRelayTrigger` — `STARTED` 행을 쓰면 커밋 뒤 단일 스레드가 스케줄러를 프록시로 한 번 돌린다(같은 `SKIP LOCKED` 선점). 도는 중에 온 요청은 "한 번 더" 로 합친다. 폴러 10초는 그대로, `outbox-relay-on-commit` 으로 끌 수 있다. 화면의 재조회도 2초 고정에서 0.3초부터 늘려 가는 간격으로. user-service 아웃박스(가입·변경·탈퇴)에도 같은 트리거 — 가입 직후 쿠폰·채팅 회원이 비어 있던 10초 | [docs/56](56-saga-latency.ko.md) · `OutboxRelayTriggerTest` |
| 🔴 메일 재시도가 이름뿐 — 5초 간격 5회, 25초 만에 FAILED | 그레이리스팅 도메인 사용자는 가입 메일을 영영 못 받았다 | 지수 백오프, 일시·영구 실패 구분 | ✅ 백오프·실패 분류, 안 채운 설정은 기동 검사에서 실패 | [docs/29 §7-1](29-transactional-email.ko.md) |

---

## 4. 데이터 · 마이그레이션 · 캐시

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 스키마를 Hibernate `ddl-auto` 가 소유 — catalog 는 `create-drop`(재기동마다 전 데이터 삭제), 나머지는 `update`(이력 없음, 제약을 지우지 않음) | 운영 DB 에 검토 없는 DDL, 이미 제약 포화 사고를 냈다 | 마이그레이션 도구가 스키마 소유, ORM 은 검증만 | ✅ 전 서비스 Flyway, Hibernate `validate`. 베이스라인은 Hibernate DDL 을 기계 변환(레거시 재현본에서 184개 항목 불변), B-33 | [docs/20](20-schema-ownership.ko.md) |
| 🔴 아웃박스 `created_at`(월 파티션 키)을 `Asia/Seoul` 로 저장 — 4개 서비스 | 월 경계에서 잘못된 파티션으로 들어간다 | 시각은 UTC 로 저장 | ✅ UTC 통일 + 회귀 테스트 4건. 아웃박스는 월 RANGE 파티션, 오래된 파티션은 DETACH | [docs/16 §2](16-saga-e2e-and-observability-verification.ko.md) · [docs/09](09-postgresql-partitioning-sharding.ko.md) |
| 🔴 `ProductSku.color` 가 `@OneToOne` — `color_id` 전역 UNIQUE | 시드가 제약을 포화시켜 상품 1건만 더 넣어도 적재 실패 | 공유 어휘는 `@ManyToOne` | ✅ V4 가 제약을 **정의로 찾아** 삭제, 인덱스는 엔티티로 보전 | [docs/00 §5](00-before-after.ko.md) |
| 🟠 할당 ID 엔티티 8개가 `save()` 에서 `merge` 로 | 불필요한 SELECT + 멱등성 가드 무력화 | 새 엔티티는 `persist` | ✅ 적용 완료 | [docs/38 §1.12](38-code-review.ko.md) |
| 🟠 SKU 잠금을 컬렉션 join 에 `FOR UPDATE` 로 | 사가의 다음 단계가 lock timeout | 잠금 범위를 필요한 행으로 | ✅ `exists` + `PESSIMISTIC_WRITE`(T1/T2 로 실측) | 커밋 `69ecaff` |
| 🟠 slug·기본 배송지·결제 참조의 유일성을 앱 관례에 맡겼다 | 동시 요청 둘이 함께 통과한다 | 데이터의 성질은 DB 제약으로 | ✅ 부분 유니크 인덱스(catalog V6 slug, user V7 기본 배송지, order V3 결제 참조) | 커밋 `3d130d1`·`0ed948e` · [docs/35 §2](35-payment-verification.ko.md) |
| 🟠 N+1, 커넥션 풀 무제한 가정, 무제한 목록(관리자 상품·주문) | 규모가 커지면 쿼리 수·커넥션·응답 크기가 선형 이상으로 는다 | 배치 로딩, 풀 예산 산술, 커서 페이징 | ✅ `@BatchSize` + SQL 개수 회귀 테스트, B-32(`datasource × pool × replicas ≤ max_connections`), 커서 페이징 | [docs/19](19-local-performance-tuning.ko.md) · [docs/43 D1~D3](43-loading-strategy-review.ko.md) |
| 🔴 검색 캐시가 첫 호출만 성공(`PageImpl` 역직렬화 500), 등록 상품이 최대 60분 검색 미반영, 자기호출로 무력화된 evict | 캐시가 기능을 깨뜨렸다 | 캐시 직렬화 검증, 무효화는 프록시를 타는 진입점에 | ✅ 커스텀 Jackson 모듈, 진입점 evict, 실시간 ES 색인 | [docs/00 §1·§3](00-before-after.ko.md) |
| 🟠 Redis 를 "날아가도 되는 캐시"로 분류 | 실제로는 리프레시 토큰·로그아웃 블랙리스트·OAuth 인가 요청 — 소실은 보안 회귀 | 보안 상태는 영속 저장 | ✅ PVC + `appendonly` + `Recreate`. 인프라 5종 전부 PVC | 커밋 `95007cf` |
| 🟠 SSE 엔드포인트가 클래스 레벨 `@Transactional` 에 걸려 연결 내내 DB 커넥션 점유 | 풀 3개가 SSE 3개에 물려 로그인까지 전부 실패 | 장수명 요청에 트랜잭션 금지 | ✅ 클래스 레벨 제거, DB 를 만지는 메서드에만. 테스트가 애노테이션 위치를 고정 | 커밋 `d81a49a` |
| 🟠 CSV 임포트 재실행 시 카탈로그 두 벌, ES 재색인이 요청 스레드를 붙잡고 중복 실행 | 운영자가 한 번 더 누르면 데이터가 오염된다 | 배치는 재실행 안전 + 동시 실행 가드 | ✅ 재실행 가드, 재색인 비동기 + 중복 거부 | [docs/42 §1](42-batch-review.ko.md) |

---

## 5. 관측성

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🟠 분산 추적 없음. 도입 후에는 **모든 서비스가 스스로를 `common`** 이라 보고 | 서비스 경계를 넘는 사가를 추적할 수 없었다 | 서비스 이름이 붙은 추적 | ✅ Micrometer Tracing + Zipkin, 공유 설정의 `spring.application.name` 제거 + B-22 | [docs/16 §2](16-saga-e2e-and-observability-verification.ko.md) |
| 🟠 k3s 파드의 추적이 기본값 `localhost:9411`(파드 자신)로 **오류 없이 버려짐** | 배포 환경에서만 추적이 없다 | 수집기 주소는 환경에서 | ✅ downward API `HOST_IP` → 노드의 Zipkin, B-59 | [docs/51 §4d](51-logging.ko.md) |
| 🟠 추적이 Kafka 프로듀서·아웃박스·배치 리스너에서 끊겼다. 첫 수정은 운영의 레코드 어댑터 경로에서 동작하지 않았다 | 요청 → 결제 → 확정이 서로 다른 추적으로 흩어졌다 | W3C `traceparent` 를 헤더로, 비동기 경계에서는 문맥을 저장했다 되살린다 | ✅ 모든 `KafkaTemplate` 빈에 관측, `OutboxTraceContext`(capture/resume, `trace_context` 컬럼), 리스너 전부가 레코드 헤더로 이어받음. B-60/60b, 운영 팩토리 그대로 올린 어댑터 테스트 | [docs/51 §4d~4h](51-logging.ko.md) |
| 🔴 경보 수신 채널이 주석 처리된 플레이스홀더 — **경보가 아무 데도 가지 않았다** | 규칙은 발화하고 대시보드는 초록불이라 알아채기 어렵다 | 수용이 아니라 전달까지 증명 | ✅ `ops/17` 이 테스트 경보를 쏴 도달 확인, severity 라우팅·억제, 웹훅 URL 은 커밋 안 되는 파일. 수신 미설정이면 Alertmanager 가 기동 실패(조용한 미전달보다 낫다) | [docs/22 §1](22-alerting-and-backup-automation.ko.md) |
| 🟠 "수동 확인 필요" 는 로그에만, 품절은 아무도 모름 | 메트릭이 없는 사건은 Prometheus 경보로 못 잡는다 | 로그 기반 경보 | ✅ Loki ruler → Alertmanager(`ManualActionRequired`·`ProductSoldOut`). 로그 문구가 계약이라 B-61 이 대조 | [docs/51 §4i](51-logging.ko.md) · [docs/53](53-restock-and-sold-out.ko.md) |
| 🟠 로그가 평문 패턴 11벌, 롤링 총량 무제한, 사용자 식별 불가, 수집기 없음 | 장애 때 "누구의 어떤 요청인가" 를 이메일 grep 으로 찾았다 | 구조화 로그 + 중앙 설정 + 수집 | ✅ 공통 JSON 파일·롤링 1GB·비동기(유실 금지), MDC `userId`(표시 전용, 로그 인젝션 방지)·비동기 스레드 전파, Loki + Alloy(k3s 파드 stdout 포함), Grafana 에서 로그 ↔ 추적 | [docs/51 §3~4c](51-logging.ko.md) |
| 🔴 `%mask(%msg)%n` 이 줄바꿈을 글자로 찍어 **모든 줄이 한 줄로** 붙었다 | 첫 배포에서 로그 전체가 읽을 수 없게 됐다. 테스트는 "가려졌나"만 봤다 | 로그 형식도 테스트 대상 | ✅ `{}` 로 복합 변환자를 닫고 줄 분리 테스트 추가, B-57b | [docs/51 §4b](51-logging.ko.md) |
| 🟠 12개 모듈 운영 프로필이 전부 DEBUG(SQL 한 줄씩) | 기동 195초로 startupProbe 예산을 넘겼고, 장애 때 원인 줄이 묻혔다 | 운영 기본은 WARN/INFO, 조사할 때만 올린다 | ✅ 기본을 운영값으로, `LOG_LEVEL_*` 로 올림. B-46 | 커밋 `a5065c8` |
| 🟠 게이트웨이 타임아웃이 503 "내부 서버 오류" 로 뭉개졌다 | 원인이 첫 화면에서 보이지 않았다 | 원인별 상태 코드(504) | ✅ 타임아웃 클래스 구분, `FallbackControllerTest` | [docs/39 §7](39-product-registration.ko.md) |
| 🟠 롤아웃 실패 시 `kubectl logs deploy/…` 는 멀쩡한 **옛 파드** 로그를 보여 줬다 · 강제 로그아웃이 왜 일어났는지 어디에도 없었다 | 증상과 원인 사이 거리가 멀어졌다 | 실패 지점에서 원인을 남긴다 | ✅ `ops/28` 이 새 파드의 기동 오류를 추출, 프론트가 강제 로그아웃 원인 요청을 기록 | [docs/49 §8](49-payment-providers.ko.md) · 커밋 `1e7a6ec` |
| 🟠 백업의 조용한 실패 — 빈 덤프가 초록불, CronJob 지표 이름과 경보가 달랐다 | 없는 지표의 경보는 영원히 울리지 않는다 | 크기·부재 자체를 경보 | ✅ 덤프 크기 지표 + `MongoBackupEmpty`, 지표 부재 경보, `restartPolicy: Never` 로 실패 파드 보존 | [docs/33 §13](33-deploy-runbook.ko.md) |

---

## 6. 배포 · 운영

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 새 클론에서 기동하는 서비스 0/8 (B-1~B-29) | 컴파일·리뷰는 통과하고 실행하면 죽었다 | "뜬다"까지 CI 가 본다 | ✅ 8/8(이미지 포함), 스테이징 리허설 13통과/0실패 | [docs/14](14-boot-test-findings.ko.md) · [docs/00 §0~1](00-before-after.ko.md) |
| 🟠 healthcheck·리소스 제한·재시작 정책 없음, 전 서비스 `replicas: 1` | 좀비 컨테이너, noisy neighbor, 재시작마다 다운타임 | 프로브·리소스·PDB, 상태 없는 서비스는 2 이상 | ✅ k8s 매니페스트 전체, 확장 안전 8종은 2 · 상태를 파드에 두는 chat·eureka 는 1 고정 + B-34. 숫자 UID non-root 실행 | [docs/00 §2](00-before-after.ko.md) · 커밋 `db16d18`·`1a4b970` |
| 🔴 CI 가 검증한 jar 를 버리고 개발자 PC 에서 다시 빌드, 태그 `1.0.0` 재사용 | 인수 테스트가 배포본을 보증하지 않았고, 같은 태그면 노드가 캐시된 옛 이미지를 계속 썼다 | build once, deploy many — 태그는 커밋 SHA | ✅ 커밋 스테이지가 이미지를 한 번 만들어 GHCR `:<SHA>`, 인수 스테이지가 그 이미지로 사가 검증, `ops/19` 는 같은 태그 재사용을 경고·거부 | [docs/36](36-deployment-pipeline.ko.md) |
| 🟠 배포 절차가 사람의 기억·주석, 되돌리기 절차 없음 | 순서(user-service → gateway)를 틀리면 전원 로그인 불가 | 스크립트화, 롤백 = 직전 성공 버전 재배포 | ✅ `ops/28`: 레지스트리 존재 확인 → 임시 오버레이 → 순서를 코드로 → 스모크 게이트 → 실패 시 자동 복귀. `--config-only`(B-56b) | [docs/36 §3](36-deployment-pipeline.ko.md) · [docs/27 배포 순서](27-security-review.ko.md) |
| 🔴 문서·스크립트가 `kubectl apply -f k8s/` 를 권했다(경고 바로 밑에 그 명령) | HPA·dev 인프라·백업 CronJob 이 되살아나고 base 매니페스트가 오버레이 없이 적용 — 배포 다섯 번 연속 실패의 출발점 | 배포 경로는 하나 | ✅ 안내문 제거, HPA 는 prod 만, 태그 고정 오버레이 직접 apply 금지 문구(B-56) | 커밋 `dda2692`·`abed97d`·`4e242c3` |
| 🟠 롤아웃 대기(300초)가 startupProbe 예산과 동점, 게이트웨이 TimeLimiter 기본 1초 | 단일 노드 경합에서 정상 응답이 503, 롤아웃이 거짓 실패 | 대기 > 기동 예산, 타임아웃 명시 | ✅ 600초 + B-43, `GATEWAY_TIMEOUT` 10초 + B-45 | 커밋 `f30b094`·`0f7ed83` |
| 🔴 백업 0 · 복구 절차 없음 · 스크립트는 검증했지만 어디서도 돌지 않았다 | 복제는 사고를 전파할 뿐이다 | 백업 + 정기 복원 훈련 | ✅ PG PITR·Mongo 백업, `DROP TABLE` 을 실제로 되돌려 검증, 주간 복원 훈련(CI), CronJob. 성공할 수 없는 환경(관리형 DB)에서는 `suspend: true` 로 들어오고 사람이 켠다 | [docs/15](15-backup-and-dr.ko.md) · [docs/22 §2](22-alerting-and-backup-automation.ko.md) |
| 🟠 스모크가 SPA 가 돌려준 200 을 "게이트웨이 통과"로 셌다 · Secret 과 DB 비밀번호 어긋남을 아무도 몰랐다 | 각 층이 저마다 "정상"을 보고했다. 떠 있는 파드는 기동 시점 값으로 돌아 다음 롤아웃에서 전멸 | 응답 주체를 본문으로 판별, 자격증명은 실제 로그인으로 확인 | ✅ `ops/06` 이 본문으로 판별, `ops/27` 이 클러스터 안에서 로그인해 본다(값은 로컬에 나오지 않음) | [docs/33 §10·§13](33-deploy-runbook.ko.md) |
| 🟠 Ingress `host` 플레이스홀더, cloudflared catch-all 뒤 규칙, DNS 라우트 실패를 삼키는 스크립트, 환경 값을 추적 파일에 | 배포는 성공처럼 보이고 실패는 브라우저의 404·522 로만. `git pull` 이 충돌 마커를 남겼다 | 실패는 그 자리에서, 환경 값은 추적 밖 | ✅ host 제거, catch-all 은 마지막 하나, 공개 URL 을 실제로 받아 코드별 진단, 환경 값은 gitignore 된 `local` 오버레이 | [docs/33 §13](33-deploy-runbook.ko.md) |
| 🟡 첫 관리자를 만들 경로가 없었다(가입 폼 role 주입은 보안 수정으로 막힘) | 관리자 0명이면 관리자 API 로 관리자를 못 만든다 | 공개 URL 에 관리자 등록 화면을 두지 않고 배포 경로로 | ✅ `ops/30`(대화형 비밀번호, 앱과 같은 BCrypt) | [ops/README ㉚](../ops/README.ko.md) |

---

## 7. 프론트엔드

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🟠 localStorage 토큰 잔존, 토큰 콘솔 출력, 401 큐 데드락, axios 래퍼가 config 를 버려 무한 대기 | XSS 한 번에 토큰 탈취, 요청이 영원히 안 끝남 | 액세스 토큰은 메모리, 리프레시는 HttpOnly 쿠키, 요청 타임아웃 | ✅ 메모리(`AuthContext`) + HttpOnly 쿠키, 콘솔 출력 제거, 큐·타임아웃 정상화 | [docs/04](04-frontend-issues.ko.md) → [docs/05](05-frontend-improvements.ko.md) · [docs/27 확인 항목](27-security-review.ko.md) |
| 🟠 401 하나가 앱 전체를 로그아웃 | 관리자 배너 호출 하나로 "관리자 페이지가 로그인 화면으로 튕긴다" | 인증 실패 범위를 요청 단위로 | ✅ 전역 로그아웃 조건 축소, 원인 로그 | [docs/40 #4](40-frontend-bug-list.ko.md) |
| 🔴 PayPal 결제가 주문에 반영되지 않음(캡처 2회), 주문 실패 시 스피너 무한·중복 주문, `disabled` 무시, 모든 API 오류를 삼킴 | 사용자가 결제·주문 상태를 알 수 없고 이중 청구 위험 | 실패를 보여 주고 중복 제출을 막는다 | ✅ 캡처 1회, 오류 표면화, 제출 중 잠금 | [docs/26 §1·§4·§7·§11](26-frontend-review-and-refactor.ko.md) |
| 🔴 번들 이름이 늘 `main.bundle.js` — 배포해도 옛 화면 | nginx 캐시 규칙이 해시 파일을 전제했는데 하나도 걸리지 않았다 | 내용 해시 + immutable | ✅ `/static/` 아래 해시 파일명, `output.clean` | [docs/40 #19](40-frontend-bug-list.ko.md) |
| 🔴 운영 첫 화면이 `process is not defined` 로 죽음 | `EnvironmentPlugin` 목록에 없는 `REACT_APP_*` 가 치환되지 않았고, Node 테스트는 `process` 가 있어 못 잡았다 | 빌드 변수 목록을 손으로 관리하지 않는다 | ✅ `webpack.env.js` 가 `REACT_APP_*` 전부 정의, `util/envRefs.test.js` 가 허용 형태만 통과 | [docs/49 §6](49-payment-providers.ko.md) |
| 🔴 18자리 주문 번호가 JS `Number` 로 바뀌어 송장 등록이 늘 실패 · 화면 시각 9시간 어긋남 | 정밀도 손실은 조용히 다른 주문을 가리킨다 | 64비트 id 는 문자열, 시각은 UTC + 표시 시 변환 | ✅ 문자열 처리, 시간대 변환 | [docs/25 §11.6·§11.8](25-delivery-service-design.ko.md) |
| 🟠 `drop_console: true` 가 error·warn 까지 제거 | 운영 클라이언트 진단이 0 이 됐다 | 소음(log·debug)만 제거 | ✅ log·debug 만 제거, 실패는 `console.error` 로 | [docs/40 #14](40-frontend-bug-list.ko.md) |
| 🟠 화면이 부분 환불 예상액을 흉내 낼 뻔했다 | 할인 배분을 화면이 계산하면 서버 값과 어긋나 "왜 적게 돌아왔냐" 가 된다 | 금액은 서버 하나 | ✅ 예상액을 그리지 않고 문구로만 | [docs/49 §10](49-payment-providers.ko.md) |
| 🔴 검색·홈 카드는 **정가**, 상품 화면은 **할인가** — 같은 상품이 $100 과 $70 으로 보였고(카드엔 "-30%" 배지까지), 가격 정렬·필터도 정가 기준. 화면 할인 계산은 부동소수라 서버(HALF_UP)와 1센트 어긋남. 통화 표기는 `59.98$`·`USD59.98 $`·`$59.98` 제각각 | 손님이 보는 가격이 화면마다 다르면 가격을 믿지 않는다. 필터가 보이는 가격과 다른 기준이면 "80 이하" 에 70 짜리가 없다 | 가격 계산은 한 곳(서버와 같은 규칙), 표기는 로캘 포맷터 | ✅ `discounted`(센트 정수·HALF_UP)·`skuPriceRange` 를 카드·상세·정렬이 공유, 서버 가격 필터는 SQL 로 후보를 줄이고 기획전까지 합친 할인가로 판정, 모든 금액을 `useMoney()`(Intl, 앱 언어 로캘)로. 덤: 무료 배송이 "0.00$" 로 보이던 것, 마지막 1개가 "품절" 로 보이던 것 | [docs/40 §22](40-frontend-bug-list.ko.md) |
| 🟡 프론트 테스트 0 · 공개 사이트에 Amazon 로고와 "Amazon 약관 동의" 문구 | 순수 함수 하나 테스트면 잡힐 버그가 누적. 사실이 아닌 약관 고지 | 회귀 테스트, 자기 브랜드 | ✅ 테스트 스위트(도입 즉시 버그 2건 발견), 상표 제거 | [docs/28](28-frontend-test-suite.ko.md) · 커밋 `7ed471e` |

---

## 8. 테스트 · CI · 가드레일

| 문제(전) | 왜 문제였나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🔴 테스트 47건 중 6건이 영구 빨간불 | 그 뒤에 실제 NPE 4건이 숨어 있었다. 꺼진 테스트는 없는 테스트보다 나쁘다 | 메인은 항상 초록 | ✅ 전부 초록 → 126건(07-28) → 268건(07-30) → 281건(docs/33 기준), `-PwithContainers` 로 사가·채팅·검색 전 구간 | [docs/17](17-testing.ko.md) · [docs/35 §4](35-payment-verification.ko.md) · [docs/33 §12](33-deploy-runbook.ko.md) |
| 🔴 CI 없음 — 새 클론이 빌드 불가라는 사실을 아무도 몰랐다 | 설정·빌드 결함이 배포에서야 드러났다 | 푸시마다 빌드·테스트 | ✅ 단위·통합·프론트 잡 + 주간 PITR 복원 훈련 + 이미지 파이프라인. 현재 CI 상태는 §10 | [docs/17 §4.2](17-testing.ko.md) · [docs/36](36-deployment-pipeline.ko.md) |
| 🔴 가장 비싼 버그가 설정에 있었고 리뷰는 설정을 못 본다 | 프로필·환경변수 오타·경로·필터 순서·CHECK 제약 등 | 설정을 코드처럼 테스트 | ✅ `ConfigurationGuardrailTest` 에 B 번호 가드 42개(B-1~B-61) + 가드레일 클래스 7개(Avro·CDC·엔티티·컨슈머·프로듀서 키·Kafka 보안·트랜잭션). 가드마다 버그를 되돌려 실패를 확인 | `common/src/test/java/com/project/common/config/` |
| 🟠 보안 결함은 기능 테스트로 절대 안 잡힌다(정상 경로는 원래 잘 동작) | 기존 테스트가 오히려 우회 동작을 고정한 경우도 있었다 | "남의 것을 요청하면 거절" 을 명시적으로 | ✅ `OrderOwnershipTest`·`CouponApplyOwnershipTest`·`UserLookupGuardTest`·`CustomUserKeyResolverTest`·`FeignContractTest`, B-52·B-53 | [docs/41 3부](41-api-security-applied.ko.md) |
| 🟠 조건부 빈·운영 어댑터처럼 **컨텍스트를 띄워야만** 드러나는 결함을 단위 테스트가 통과시켰다 | 단위 189개 통과 후 기동 실패, 추적 수정이 운영 경로에서 무효 | 운영 설정 그대로 올리는 테스트 | ✅ `ApplicationContextRunner` 배선 테스트, 운영 `KafkaConsumerConfig` 로 등록한 어댑터 테스트, Testcontainers 기동 테스트 | [docs/49 §8](49-payment-providers.ko.md) · [docs/51 §4g](51-logging.ko.md) |
| 🟠 매니페스트를 PyYAML 로 검증 | 중복 키를 조용히 허용해 깨진 Ingress 가 커밋됐다 | 실제로 쓰는 도구로 검증 | ✅ `kustomize build`·`cloudflared tunnel ingress validate` | [docs/33 §13](33-deploy-runbook.ko.md) |

---

## 9. 불필요했던 것 — 빼거나 줄인 것

| 불필요했던 것(전) | 왜 불필요·해로웠나 | 실무 기준 | 최종 상태(후) | 근거 |
|---|---|---|---|---|
| 🟠 order-service 의 고객 사본(`orders.customer`) + `user-created` 구독 + 주문 시점 백필 + `customers` 캐시 | 쓰는 것은 이메일 → id 변환 하나인데, 그 id 는 검증된 토큰의 `uid` 가 `User-Id` 헤더로 이미 나른다. 사본 때문에 동기 의존·드리프트·동명이인 UNIQUE 지뢰가 생겼다 | 토큰이 나르는 것을 복제하지 않는다 | ✅ 요청자 신원은 `User-Id`(소유권 검사 13곳이 `requesterId`), 사본 엔티티·리스너·구독·백필·`customers` 캐시 삭제. 관리자 목록의 이메일은 트랜잭션 밖 내부 일괄 조회. 테이블은 롤백 대비로 한 릴리스 남겼다가 전환 정리의 V16 이 지웠다(expand-contract). chat·cart·coupon 도 뒤이어 uid 로 옮겼다(§1.1·§1.7) — 이제 요청 경로에서 이메일 → id 를 묻는 서비스가 없다 | [docs/48 §6-①](48-user-replication-debt.ko.md) · 커밋 `a8a421c` |
| 🟡 `user-created` 이벤트의 이메일 | 쓰지 않는 소비자에게까지 개인정보가 퍼진다(coupon 은 id 만 쓰고, order 는 더 받지 않는다) | 데이터 최소화 | ✅ chat 신원을 uid 로 옮긴 뒤 DTO·Avro 스키마(v2)에서 필드째 제거, `chat.member.email` 컬럼도 삭제(V5). 옛 기록 변환용 매핑은 백필이 끝나면 스스로 비운다 — 한 번의 배포로 끝나도록 순서를 코드가 지킨다 | [docs/48 §6-⑥](48-user-replication-debt.ko.md) |
| 🟠 엔드포인트마다 따로 짠 내부 호출 판정 | 흩어진 판정은 형제 엔드포인트가 빠진다(§9b) | 인가 판정은 한 곳 | ✅ `InternalCallGuard` 로 통일 | [docs/27 §9b](27-security-review.ko.md) |
| 🟠 게이트웨이 `default-filters` 의 헤더 제거 세 줄 | 순서가 틀려 검증된 신원까지 지웠다 | 전역 필터 하나 | ✅ `IdentityHeaderScrubFilter` 로 대체 | 커밋 `af776f9` |
| 🔴 `useInsecureTrustManager`, actuator `*`, 게이트웨이 `gateway`·`beans` 노출 | 편의를 위한 설정이 보안 경계를 통째로 열었다 | 최소 노출·검증 기본 켜짐 | ✅ 기본 false, 노출 범위 축소 | §1.8·§1.9 |
| 🟠 아웃박스 릴레이의 Kafka 트랜잭션과 UUID `transactional.id` | 보장처럼 보였지만 DB 커밋 뒤 Kafka 커밋이라 유실 창이었다 | 필요한 곳에만 트랜잭션 | ✅ 릴레이는 멱등 프로듀서로, 트랜잭션은 보상 프로듀서 넷만 | [docs/38 §1.17](38-code-review.ko.md) |
| 🟡 `HttpSessionRequestCache`(401 마다 세션 생성) | `STATELESS` 인데 아무도 읽지 않는 세션을 만들었다 | 읽히지 않는 상태는 비용만 있다 | ✅ 요청 캐시 끔 | [docs/27 부록 2](27-security-review.ko.md) |
| 🔴 Feign 의 `@RequestParam("Authorization")` | 인터셉터가 이미 헤더로 전달 — 중복이면서 토큰을 URL 로 노출 | 토큰은 헤더 한 곳 | ✅ 삭제(헤더로 재선언도 안 함) | [docs/47 §5-4](47-order-create-failure-visibility.ko.md) |
| 🔴 `OrderRequest.total`·`totalBeforeDiscount`, 전역 `findByActive` | "읽지 않는다"는 주석은 다음 사람이 다시 쓰게 만든다 | 위험한 입구는 삭제 — 되돌리면 컴파일이 막는다 | ✅ 필드·메서드 삭제 | 커밋 `86010e1`·`0ed948e` |
| 🟡 서비스 yml 의 `logging.pattern` 11곳, eureka 의 logback XML 둘(하나는 이름 오타로 아무도 안 읽음), 운영 프로필 DEBUG | 공통 마스킹을 우회하거나 쓰이지 않거나 기동을 느리게 했다 | 설정은 한 곳 | ✅ 삭제, B-57·B-46 | [docs/51 §2](51-logging.ko.md) · 커밋 `a5065c8` |
| 🟡 Cloudinary 직접 업로드 경로, Secret 의 `REACT_APP_*` | 이미 죽은 코드가 "그 계정이 필요하다"는 착각을 남겼다 | 쓰지 않는 의존은 제거(제거 ≠ 키 폐기) | ✅ 삭제 + 키 폐기, B-51 | 커밋 `973d0e5`·`6a485f4` |
| 🟡 미사용 파생 쿼리 19개, `OrderMapper`, 항목마다의 단건 조회 루프, ES `/all` 덤프 8개 | 불리면 깨질 쿼리, 무제한 덤프, N 배 왕복 | 죽은 코드와 무제한 엔드포인트 제거 | ✅ 삭제, 배치 조회로 대체 | 커밋 `69ecaff`·`61afb50`·`98c9802` |
| 🟡 springfox(Boot 3 비호환 + 리플렉션 우회 핵) | 사장 라이브러리 | 유지되는 대안 | ✅ springdoc 으로 교체 | [docs/10 §2](10-quality-hardening.ko.md) |
| 🟡 content-service 를 별도 파드로 | 배너·공지 두 테이블에 단일 노드 자원 250m/384Mi | 규모에 맞는 경계 | ✅ catalog-service 에 합치고 스키마(`content`)로 경계 유지 | 커밋 `4d17bc1` |
| 🟡 S3 객체별 public ACL | 추가 권한 필요, 신규 버킷 기본값(ACL 비활성)과 충돌 | 버킷 정책 | ✅ 버킷 정책으로 | 커밋 `4c896d9` |
| 🟡 재고 이벤트를 **주문마다** 발행하는 안 | 알림 소음 | 상태가 0 을 건널 때만 | ✅ `SOLD_OUT`/`RESTOCKED` 만 | [docs/53 §2](53-restock-and-sold-out.ko.md) |
| 🟡 로컬(단일 노드) HPA, Ingress host 플레이스홀더, cloudflared 404 catch-all 규칙 | 각각 롤아웃 실패·조용한 404·터널 기동 거부를 만들었다 | 환경에 맞지 않는 구성은 빼는 것이 안전 | ✅ HPA 는 prod 만, host 없음, catch-all 은 traefik 하나 | 커밋 `6ccc411` · [docs/33 §13](33-deploy-runbook.ko.md) |
| 🟡 결제를 건너뛰는 개발 전용 경로 | 한번 들어오면 운영까지 따라간다 | 만들지 않는다 | ✅ 의도적으로 없음(샌드박스로 시험) | [docs/35 §6](35-payment-verification.ko.md) |

---

## 10. 아직 부족한 것 (정직하게)

### 10.1 이 정리 중 코드에서 확인한 열린 항목

`근거` 의 **기록 없음**은 기존 문서에 없고 이 문서를 쓰며 코드를 읽다 새로 확인한 것이다(실제 요청으로 재현하지는 않았다).
나머지는 처음 적은 문서를 링크했고, 지금도 코드에 그대로 있는지 확인했다.

| 문제(지금) | 왜 문제인가 | 실무 기준 | 최종 상태 | 근거 |
|---|---|---|---|---|
| 🔴 재고 초과 판매가 조용히 넘어간다 — 결제 뒤 차감이 부족하면 `null` 로 끝나고 주문은 PAID. 🐛 판매량은 수량 확인 전에 올랐다 | 돈은 받았는데 보낼 물건이 없고, 아무도 모른다 | 결제 전 재고 예약, 또는 실패 시 자동 환불 보상 + 경보 | ✅ 보상 쪽: SKU 잠금 아래 있는 만큼만 배정(판매량도), 부족분은 같은 트랜잭션의 아웃박스(V13) → `stock-shortage` → order 가 `short_qty`(V21)로 적고 자동 부분 환불(무통장은 수동). 부족분은 복원·반품·배송 수량에서 빠진다. 경보 셋. ✅ 예방 쪽: 결제 전 재고 예약 — 주문 생성 때 30분 잡고(모자라면 주문을 만들지 않는다), 무통장은 입금 기한까지 연장, 취소·만료 때 풀고, 결제 차감과 같은 트랜잭션에서 확정(catalog V14·order V22). catalog 에 닿지 못하면 fail-open 으로 보상 쪽에 맡긴다 | [docs/53 §7](53-restock-and-sold-out.ko.md) · [docs/59](59-stock-reservation.ko.md) |
| 🟠 서비스 간 인증이 없다. NetworkPolicy 0개, user-service 자체 `JwtAuthFilter` 는 만료 토큰을 유효로 취급(만료 검사 주석 처리), 게이트웨이 discovery locator 자동 라우트(`/{SERVICE-ID}/**`)에는 JWT 필터가 없다 | 클러스터 안 파드 하나만 장악해도 내부 API 가 무방비. `InternalCallGuard` 의 헤더 판정도 이 전제 위에 있다 | 내부 API 는 외부 라우팅에서 제외 + mTLS·서비스 토큰, 기본 거부 NetworkPolicy | ✅ 게이트웨이 자동 라우트를 껐다(B-64, [docs/57 §9](57-remaining-priorities.ko.md)). ✅ user-service 만료 검사·리프레시 토큰의 액세스 사용 차단([docs/57 §8](57-remaining-priorities.ko.md)). NetworkPolicy — 다른 네임스페이스에서 들어오는 연결 차단([docs/57 §11](57-remaining-priorities.ko.md), `ops/35` 로 적용). ✅ 서비스 간 mTLS — 내부 호출을 내부 CA 클라이언트 인증서로 판정(`InternalCallerFilter`, observe→enforce, [docs/57 §16](57-remaining-priorities.ko.md)). 서비스별 신원은 다음 단계 | [docs/27 §14](27-security-review.ko.md) · [docs/38 §2.1 #5·§2.6 #2](38-code-review.ko.md) |
| 🟠 공통 예외 처리기의 500 이 `ex.getLocalizedMessage()` 를 본문에 싣는다 | JDBC 예외의 테이블·컬럼·값이 클라이언트로 갈 수 있다 | 내부 메시지는 로그에만, 응답은 일반 문구 | ✅ 공통 500·400 처리기, 컨트롤러 57곳, chat 처리기 모두 `ClientMessage`(허용 목록 — 도메인 예외와 우리가 던진 표준 예외 넷만 문구를 싣는다). `CustomException` 은 ErrorCode 상태로. 가드 `ExceptionMessageGuardrailTest` | [docs/57 §1](57-remaining-priorities.ko.md) |
| 🟠 공개 catalog 라우트(`/catalog-service/**`, 로그인 불필요, 경로 전체 재작성)로 catalog 의 actuator `prometheus`·`metrics` 와 springdoc `/v3/api-docs` 가 외부에서 닿을 수 있다 | 내부 지표와 API 명세가 공개된다 | 관리·문서 엔드포인트는 외부 라우팅에서 제외 | ✅ 게이트웨이 `InternalEndpointBlockFilter` — 모든 `/<서비스>/actuator·v3/api-docs·swagger-ui/**` 404(우회 방지 포함). 서비스 안의 끄기는 다음 단계 | [docs/57 §7](57-remaining-priorities.ko.md) |
| 🟠 게이트웨이 관리 엔드포인트(설정 리로드)와 config-server 의 접근 통제 | 설정 리로드 트리거와 설정 조회는 클러스터 안에서도 보호가 필요하다 | 관리 엔드포인트와 설정 서버는 인증 필수 | 관리 엔드포인트는 외부 경로에서 제외. config-server 는 설정 저장소를 연결하지 않은 상태로 운영(연결 시 인증 필요) | [docs/27 §8·§15](27-security-review.ko.md) · 커밋 `9b6bc17` |
| 🟠 게이트웨이 라우트 id `user-service` 가 두 번 선언돼 있다 | 레이트리미터 설정이 라우트 id 로 저장돼, 로그인 경로의 1/s 제한이 다른 설정에 덮일 수 있다 | 라우트 id 유일 | ✅ `user-service-public`/`user-service-auth`, 가드 B-62 | [docs/38 §2.6 #4](38-code-review.ko.md) |
| 🟠 리프레시 토큰의 Redis TTL 에 "현재 시각 + 기간" 을 기간으로 넘기고, 쿠키 `Max-Age` 에 밀리초를 넣는다 | Redis 키가 사실상 만료되지 않고 쿠키는 약 38년 | 수명은 한 곳에서, 단위를 맞게 | ✅ 쿠키 수명을 `Duration` 으로(단위를 타입으로), Redis 는 값·TTL 한 명령. 이미 쌓인 키 정리 절차 | [docs/57 §2](57-remaining-priorities.ko.md) |
| 🟠 ~~SMS 인증 발송 제한이 번호 단위뿐~~(✅ docs/57 §10 — 계정당·전체 상한) · ~~상품 별점 덮어쓰기~~(✅ docs/57 §3 — 내부 전용, 리뷰 저장이 막혀 있던 것도) · ~~STOMP SUBSCRIBE 가 목적지와 방을 대조하지 않는다~~(✅ docs/58 §3) | 번호를 바꿔 가며 실발송(요금은 우리 몫), 별점 조작, 1:1 방이 생기면 유출 경로 | 계정 단위 제한, 쓰기의 범위·구매 검증, 구독 인가 | ✅ 셋 다 — SMS 계정·전체 상한(docs/57 §10), 받은 상품만 리뷰(docs/57 §13), 구독 인가(docs/58 §3) | [docs/38 §1.6·§1.7·§2.5 #8](38-code-review.ko.md) |
| 🟠 유출 재발 방지 장치가 푸시·PR 단계에 없다 — CI(`build.yml`)에 `ops/20 --head` 없음, Push Protection 은 미확인 | 새 유출을 커밋 뒤에야 안다. `frontend/dist.zip`(옛 맵·옛 호스트 포함)도 여전히 추적된다 | 푸시·PR 단계의 비밀 스캔 | ✅ CI `secrets` 잡(`ops/20 --head` + 새 `--range`), 실패하면 릴리스 후보 승격이 막힌다. Push Protection 은 저장소 설정 몫([docs/57 §6](57-remaining-priorities.ko.md)) | [docs/30 §5·§6](30-git-history-secret-exposure.ko.md) · [docs/38 §3 #1](38-code-review.ko.md) |
| 🟠 백업이 클러스터 안 PVC, `postgres-backup`·`restore-drill` 은 기본 `suspend: true` | 클러스터가 사라지면 백업도 함께 사라진다. 켜지 않으면 백업이 없는 것과 같다 | 백업은 클러스터 밖(오브젝트 스토리지), 켜져 있는지 경보로 확인 | 환경마다 사람이 켠다 | [docs/22 §4](22-alerting-and-backup-automation.ko.md) · `k8s/85-backup-cronjobs.yaml` |
| 🟡 클러스터 Redis 가 DB 비밀번호를 같이 쓴다(`SPRING_DATA_REDIS_PASSWORD` 없으면 폴백) | 하나가 새면 둘 다 샌다 | 시스템마다 다른 자격증명 | ✅ `ops/36-rotate-redis-password.sh` — 끊김 없이 분리(ACL 로 새 값 추가 → Secret → 서비스 재시작 → Redis 재시작), `ops/27` 이 분리된 값으로 점검. **실행은 운영자 몫** ([docs/57 §12](57-remaining-priorities.ko.md)) | [docs/00 §11.1](00-before-after.ko.md) · `k8s/20-secret.example.yaml` |
| 🟡 사용자 복제의 대사(reconciliation) 배치 없음 · DLT 에 쌓인 옛 `user-created`(chat) | 이미 놓친 가입 쿠폰은 복구 수단이 없다. chat 은 DLT 재처리 또는 프로필 변경으로 채워진다 | 드리프트는 주기적으로 훑어서 고친다 | 미착수(chat 은 배포 후 DLT 재처리 권장) | [docs/48 §6-③](48-user-replication-debt.ko.md) |
| 🟡 Prometheus 스크레이프 `insecure_skip_verify`, 추적 샘플링 1.0, 모든 서비스가 인증서 하나 공유·개인키 디스크 평문 | 지표 경로 MITM, 운영 부하, 키 하나 유출이 전 서비스 신원 유출 | 사설 CA 신뢰, 샘플링 조정, 서비스별 신원·KMS | 문서화만 | [docs/11 §1](11-observability-and-kafka-reliability.ko.md) · [docs/18 §7](18-certificates.ko.md) |
| 🟡 단일 노드 k3s, 관측 스택은 노드의 compose | 노드 하나가 곧 전체 장애 | 최소 2노드 + 관리형 DB | 비용 결정 | [docs/23](23-infrastructure-decision.ko.md) · [docs/51 §5](51-logging.ko.md) |
| 🟡 PayPal·포트원 실제 API 미검증(응답 필드 매핑은 문서 기준) — ✅ Stripe 는 운영에서 결제·환불 성공(2026-09-26) | 응답 모양이 다르면 결제 확인이 전부 거부된다(fail closed 라 돈이 새지는 않는다) | 샌드박스 E2E 한 번 | 매핑을 한 곳에 모아 둠 | [docs/35 §4](35-payment-verification.ko.md) · [docs/49 §8](49-payment-providers.ko.md) |

### 10.2 닫힌 것으로 확인한 옛 "남은 과제"

| 옛 기록 | 최종 상태 | 근거 |
|---|---|---|
| docs/27 §13 "계정 잠금 미구현 — 남음" | ✅ 5회/15분 잠금이 들어가 있다(문서가 갱신되지 않았다) | 커밋 `03422d0` · `LoginAttemptService` |
| docs/27 §15 "DB 비밀번호 회전" | ✅ 유출 값이 어디서도 통하지 않음을 로그인 실측으로 확인 | [docs/30 §6](30-git-history-secret-exposure.ko.md) |
| docs/51 §5 "사가 밖의 배치 리스너는 추적을 이어받지 않는다" | ✅ §4h 에서 모든 리스너에 적용, B-60b 가 강제 | [docs/51 §4h](51-logging.ko.md) |
| 이 문서 초안의 §10.1 "비밀번호 로그인의 2FA 를 서버가 강제하지 않는다" · "`/api/coupon/add` 관리자 검사 없음" | ✅ 같은 날 고쳤다 — §1.3·§1.1 | 커밋 `bffa957`·`1bcfabf` |
| docs/38 §2.1 #10 `verify-2fa-login` NPE·시도 제한 없음 | ✅ 엔드포인트 삭제 | [docs/27 §12b](27-security-review.ko.md) |
| docs/48 §7 chat 닉네임 추측 · `uk_member_nickname` | ✅ 고침 | [docs/48 §6-⑤](48-user-replication-debt.ko.md) |
| docs/38 §2.6 #8 "Kafka DTO 에 사용자 액세스 토큰 원문" | ✅ 사가 메시지 전부에서 제거, 아웃박스 행 스크럽(V15) | §1.7 |
| docs/00 §11.4 "CI 첫 단계 실패(cart-service 6건)" | ✅ 테스트가 `new CartService(...)` 로 프록시를 건너뛰어 트랜잭션 동기화가 없었다 — 운영 경로(클래스 `@Transactional` 프록시)는 정상. 테스트가 동기화를 열고, "커밋 전·롤백이면 캐시를 지우지 않는다" 를 새로 고정. 백엔드 전 모듈 초록 | `CartEmptyLinesTest` |
| 이 문서 초안의 §10.1 "채팅 메시지 페이로드의 발신자 `email`" · §9 "`user-created` 의 이메일 — 보류" | ✅ chat 신원을 uid 로 옮기고 둘 다 없앴다 | [docs/48 §6-⑥](48-user-replication-debt.ko.md) |
| docs/38 §1.6 의 배송지 IDOR · PayPal 결제 소유권 · `/savecart` | ✅ 셋 다 고쳐졌다. 같은 표의 별점은 아직 열려 있다(§10.1) | 커밋 `0ed948e` · `OrderDataService.recordPayment` |

### 10.3 문서 사이의 어긋남

- ~~docs/27 §13 은 계정 잠금을 "남음"으로 적고 있다.~~ 해결됨 표시로 고쳤다(요약표 13행은 발견 당시의 기록으로 둔다).
- docs/51 §5 의 첫 항목은 §4h 로 해소됐다.
- docs/38 은 "점검 결과" 문서라 상태 칸이 비어 있다. 그 뒤 커밋으로 고쳐진 것과 열린 것이 섞여 있으므로, 이 문서의 §10 을 최신 목록으로 본다.

---

## 11. 교훈

1. **클라이언트가 고를 수 있는 값은 신원도, 금액도, 결제 상태도, 레이트리밋 키도 될 수 없다.** IDOR·무료 주문·제한 우회가 전부 이 한 뿌리였다.
2. **판정은 한 곳에 모으고 "남의 것을 요청하면 거절" 을 테스트로 고정한다.** 흩어진 판정에서는 늘 형제 엔드포인트(`/user/email`, `/applycoupon`, `/savecart`, 쿠폰 `/add`)가 빠졌다.
3. **fail closed 가 기본이다.** 필수 비밀·결제 검증·경보 수신자가 비면 기동하지 않게 했고, 그 덕에 "설정을 깜빡하면 조용히 꺼지는" 경로가 사라졌다.
4. **유출은 폐기로만 끝나고, 확인은 값 비교가 아니라 실제로 통하는지로 한다.** 이력 정리·브랜치 검사는 PR 참조 앞에서 거짓 통과를 냈다.
5. **가장 흔한 결함은 "설정된 것처럼 보이는 미설정"이었다.** 경보·백업·추적·트러스트스토어·타임아웃이 초록불 속에서 동작하지 않았다 — 전달·복원·연결까지 끝까지 확인한다.
6. **보안 수정도 회귀를 만든다.** 헤더 제거를 잘못된 순서에 둬 전 서비스가 401 이 됐다 — 파일에 보이지 않는 실행 순서는 구조와 테스트로 고정한다.
7. **가드는 되돌려서 실패하는지 본다.** 기존 테스트 하나는 레이트리밋 우회를 보호하고 있었다 — 통과만 하는 테스트는 아무것도 지키지 않는다.
8. **토큰이 이미 나르는 것은 복제하지 않고, 읽히지 않는 상태는 만들지 않는다.** 고객 사본·요청 캐시·릴레이 트랜잭션은 보장 대신 결합과 유실 창을 남겼다.
9. **"기다리게 하는 것" 은 통제가 아니다.** 2FA 는 화면이 로그인을 미뤘을 뿐 서버는 토큰을 먼저 줬고, 보류 토큰은 액세스 토큰과 같은 키였다 — 자격증명의 **용도는 서명 키로 분리**해야 다른 검증기가 우연히 받아 주지 않는다.
10. **형식을 바꿀 때는 소비자를 코드에서 전수 조사한다.** 목록을 손으로 적은 가드는 빠진 한 곳(chat)을 영원히 통과시켰다.
12. **검증하지 않는 자격증명 검사는 통제가 아니라 누출이다.** 결제 사가는 "토큰이 있는가" 만 확인하려고 사용자 토큰을 아웃박스·토픽에 평문으로 남겼고, 그 검사 때문에 토큰 없는 정상 경로가 DLT 로 갔다.
11. **id 는 끝까지 글자로 나른다.** 19자리 TSID 는 JS `Number` 를 한 번만 지나도 끝자리가 뭉개진다 — 주문 번호에서 한 번, 토큰의 uid 에서 또 한 번 만났다. 비교가 "조용히 틀리는" 종류라 테스트가 뭉개진 값을 명시적으로 넣어 봐야 드러난다.
