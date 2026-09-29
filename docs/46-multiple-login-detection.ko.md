# 46. 다중 로그인 판정 — 이름이 Redis 였던 HashMap

[日本語](46-multiple-login-detection.md) | **한국어**

혼자 쓰는 계정으로 로그인했는데 이런 안내가 떴다:

> User attempted to log in multiply, logging out from previous logged in state

다른 데서 로그인한 적이 없다. 방금 비밀번호를 재설정하고 처음 들어온 것이다.

---

## 1. 무엇이 그 판정을 하는가

`AuthLoginFilter.successfulAuthentication` 은 로그인에 성공한 뒤 이렇게 묻는다.

```java
String previousSessionId = sessionManager.getSession(userDetails.getEmail());
if (previousSessionId != null) {
    // "이미 로그인돼 있다" → 이전 세션에 로그아웃 이벤트를 보내고 경고 문구를 붙인다
}
```

그 기억을 갖고 있는 것이 `RedisSessionManager` 다.

## 2. 그런데 Redis 가 아니었다

```java
// 고치기 전
public class RedisSessionManager {
    private static RedisSessionManager instatnce;
    private Map<String, String> sessions;

    private RedisSessionManager() { sessions = new HashMap<>(); }

    public static synchronized RedisSessionManager getInstance() { ... }

    public synchronized void addSession(String userId, String sessionId) {
        log.info("addSession : {}", sessionId);   // ← 세션 id 를 평문으로 남긴다
        sessions.put(userId, sessionId);
    }
}
```

**이름만 Redis 이고 실제로는 JVM 안의 `HashMap`** 이었다. `JwtUtils` 가
`getInstance()` 로 정적 싱글턴을 가져다 썼다. 세션 id 를 `INFO` 로 찍는 줄도 함께
없앴다 — `AuthLoginFilter` 의 `Login session Id : {}` 도 같은 값을 남기고 있었다.

그 결과 두 가지가 **동시에** 틀렸다. user-service 는 `k8s/40-user-service.yaml` 에서
`replicas: 2` 로 뜬다.

### 거짓 양성 — 혼자 쓰는데 경고가 뜬다

항목을 지우는 곳이 **명시적 로그아웃 하나뿐**이었다
(`AuthLogoutFilter:123`, 헤더 `MultiLogin: false` 경로). 그래서 다음 경우에 기록이
그대로 남는다.

| 상황 | 기록이 지워지나 |
|---|---|
| 로그아웃 버튼 | ✅ 지워짐 |
| 브라우저를 그냥 닫음 | ❌ 남음 |
| 리프레시 토큰 만료 | ❌ 남음 |
| 비밀번호 재설정 후 재로그인 | ❌ 남음 |
| 파드 재시작 | 맵이 통째로 사라짐(다른 의미의 오작동) |

보고된 증상이 정확히 네 번째 줄이다. 남아 있는 옛 기록 때문에 **처음 로그인하는데도
"이미 로그인돼 있다"** 가 됐다.

### 거짓 음성 — 정작 막으려던 것을 절반만 막는다

맵이 파드마다 따로다. 두 번째 로그인이 **다른 파드로 라우팅되면 그 파드의 맵에는
아무것도 없어** 판정 자체가 일어나지 않는다. 레플리카 2개면 대략 절반이 그냥 통과한다.

즉 이 기능은 **없는 다중 로그인은 알리고, 있는 다중 로그인은 절반 놓치고** 있었다.

## 3. 고친 방식

`RedisSessionManager` 를 이름대로 Redis 백엔드 `@Component` 로 바꿨다.

```java
private static final String KEY_PREFIX = "login-session:";

public void addSession(String userId, String sessionId) {
    redisTemplate.opsForValue().set(key(userId), sessionId, sessionTtlMs, TimeUnit.MILLISECONDS);
}
```

바뀐 점 셋:

1. **레플리카가 같은 기억을 본다.** 거짓 음성이 사라진다.
2. **TTL 이 있다.** `${spring.app.jwtRefreshExpirationMs}` 로 맞췄다 — 리프레시 토큰이
   죽으면 그 세션도 죽은 것이므로 기록도 같이 만료돼야 한다. 그보다 길면 못 쓰는 세션을
   살아 있다고 보고, 짧으면 살아 있는 세션을 놓친다.
   **지우는 코드가 빠져도 옛 기록이 영원히 남지 않는다** — 거짓 양성의 구조적 방지선이다.
3. **비밀번호 변경 시 지운다.** `UserServiceImpl.applyNewPassword` 가
   `sessionManager.removeSession(user.getEmail())` 을 호출한다. 비밀번호를 바꾸면 기존
   세션은 어차피 무효다(`RefreshTokenController` 가 `passwordChangedAt` 이전 `iat` 를
   거부한다). 기록만 남아 다음 로그인에 경고를 띄우는 것을 막는다.

`JwtUtils` 는 `getInstance()` 대신 생성자 주입으로 받는다. `AuthLoginFilter` 는
`SecurityConfig` 에서 빈을 넘겨받는다(7번째 파라미터).

### 이 기록은 권한이 아니다

세션이 실제로 유효한지를 정하는 것은 **리프레시 토큰(Redis)과 `User.passwordChangedAt`**
이다. `login-session:` 항목은 "다중 로그인 같아 보이는가" 를 판단하는 **근거일 뿐**이고,
항목이 남아 있어도 토큰이 없으면 그 세션은 이미 죽은 것이다. 이 구분이 흐려지면
TTL 을 늘리고 싶은 유혹이 생긴다.

## 4. 남은 것

- 문구가 영어다(`User attempted to log in multiply, …`). i18n 대상(docs/45).
- 이전 세션을 강제 로그아웃시키는 동작 자체가 맞는 정책인지는 별도 판단이다. 지금은
  "마지막 로그인이 이긴다" 이고, 그 판정이 이제서야 **정확하게** 동작한다.

## 검증

```
:user-service:test  tests=215 failures=0
```

- `PasswordChangeTest.clearsSessionRecord` — 비밀번호 변경이 세션 기록을 지우는지 고정.
- 파드를 늘려도 같은 기억을 보는지는 Redis 를 공유하므로 구조적으로 성립한다.
