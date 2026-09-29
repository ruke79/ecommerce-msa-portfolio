package com.project.userservice.repository.redis;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Repository;

import com.project.userservice.model.redis.RefreshToken;

@Repository
public class RefreshTokenRepository {

    @Value("${spring.app.jwtRefreshExpirationMs}")
    private int jwtRefreshExpirationMs;

    /** 액세스 토큰 수명 — 로그인 해제 표시(cutoff)의 TTL. 그보다 오래 두면 의미가 없다(그 전 토큰은 모두 만료). */
    @Value("${spring.app.jwtExpirationMs:900000}")
    private int jwtExpirationMs = 900_000;

    private final StringRedisTemplate redisTemplate;
    private static final String LOCK_PREFIX = "lock:";

    public RefreshTokenRepository(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 분산 락 획득 메서드
    private boolean acquireLock(String key, String lockValue) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + key, lockValue, 30, TimeUnit.SECONDS); // 30초 TTL
        return Boolean.TRUE.equals(success);
    }

    // 분산 락 해제 메서드
    private void releaseLock(String key, String lockValue) {
        // 락을 해제할 때, 자신이 획득한 락인지 확인하는 것이 중요합니다. (A가 락을 획득했는데, B가 락을 해제하면 안됨)
        String value = redisTemplate.opsForValue().get(LOCK_PREFIX + key);
        if (lockValue.equals(value)) {
            redisTemplate.delete(LOCK_PREFIX + key);
        }
    }

    public void save(final RefreshToken refreshToken) {
        String lockKey = refreshToken.getSessionId();
        String lockValue = UUID.randomUUID().toString();
        
        if (acquireLock(lockKey, lockValue)) {
            try {
                // 🐛 예전에는 set 뒤에 expire(키, "지금 시각 + 기간", ms) 였다 — 기간 자리에 **시각**(epoch ms)을 넣어 TTL 이
                //    약 56년이었다. 로그아웃하지 않은 세션의 키는 사실상 지워지지 않고 쌓였다. 값과 TTL 을 한 명령으로
                //    넣는다 — set 과 expire 사이에 죽어 TTL 없는 키가 남는 틈도 없앤다(docs/57 §2).
                //
                // 🔒 토큰 원문이 아니라 SHA-256 해시를 둔다(docs/68 §10). 예전에는 JWT 원문이 값이었다 — Redis 덤프가 새면 그 값을
                //    쿠키에 넣어 그대로 로그인할 수 있었다. 해시로는 토큰을 만들 수 없다. 비교는 받은 토큰을 해시해서 한다(rotate).
                //    [JA] トークン原文ではなく SHA-256 ハッシュを置く。以前は JWT 原文が値で、Redis ダンプが漏れればそのまま使えた。
                redisTemplate.opsForValue().set(refreshToken.getSessionId(), digest(refreshToken.getToken()),
                        java.time.Duration.ofMillis(jwtRefreshExpirationMs));
            } finally {
                releaseLock(lockKey, lockValue);
            }
        } else {
            // 락 획득 실패 시, 재시도 로직이나 예외 처리를 추가할 수 있습니다.
            throw new IllegalStateException("Failed to acquire lock for saving RefreshToken");
        }
    }

    /**
     * 저장 형식 — 토큰의 SHA-256(hex). 토큰은 서명된 JWT 라 엔트로피가 충분하다(비밀번호가 아니므로 솔트 · 느린 해시가 필요 없다).
     *
     * <p>🗑️ 예전의 {@code registerBlacklist} 는 로그아웃할 때 <b>액세스 토큰 원문을 키로</b> 적었다 — 그런데 그 키를 읽는 곳이 어디에도
     * 없었다(게이트웨이는 서명만 본다). 막는 효과 없이 유효한 토큰만 Redis 에 쌓았다. 지웠다.
     */
    public static String digest(String token) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 모든 JVM 에 있다
        }
    }

    public Optional<RefreshToken> findByUserId(final String sessionId, final String userId) {
        ValueOperations<String, String> valueOperations = redisTemplate.opsForValue();
        String refreshToken = valueOperations.get(sessionId);

        if (Objects.isNull(refreshToken)) {
            return Optional.empty();
        }

        return Optional.of(new RefreshToken(sessionId, userId, refreshToken));
    }

    public void delete(final String key) {
        String lockKey = key;
        String lockValue = UUID.randomUUID().toString();

        if (acquireLock(lockKey, lockValue)) {
            try {
                redisTemplate.delete(key);
            } finally {
                releaseLock(lockKey, lockValue);
            }
        } else {
            // 락 획득 실패 시, 재시도 로직이나 예외 처리를 추가할 수 있습니다.
            throw new IllegalStateException("Failed to acquire lock for deleting RefreshToken");
        }
    }

    // ── 회전 · 재사용 감지 (docs/68) ────────────────────────────────────
    //
    // 키 — "<세션 id>" → 토큰의 SHA-256(§10), 곁에 셋을 더한다(모두 TTL 이 있다):
    //   rt:grace:<옛 세션 id>   회전 직후 잠깐 — "PENDING" 이었다가 새 토큰의 <b>암호문</b>(GraceTokenCipher). 동시에 갱신한 다른 탭이 같은 새 토큰을 받는다.
    //   rt:fam:<묶음 id>        그 묶음의 지금 살아 있는 세션 id(하나). 묶음을 끊을 때 지울 키를 찾는다.
    //   rt:revoked:<묶음 id>    끊긴 묶음 — 이 묶음의 어떤 토큰도 받지 않는다(토큰 수명만큼).
    // [JA] 既存の「<セッション id> → トークン値」はそのまま、横に 3 つ足す(すべて TTL あり): 猶予(同時更新の別タブへ同じ新トークン)、
    //      ファミリーの現在のセッション id、切られたファミリーの印。

    public static final String PENDING = "PENDING";

    /**
     * 비교 후 삭제를 <b>한 번에</b>(Lua — Redis 안에서 원자적). GET 과 DEL 을 따로 부르면 두 요청이 같은 토큰으로 동시에 들어와
     * 둘 다 "있다" 를 보고 둘 다 새 토큰을 받는다 — 회전이 무너진다.
     *
     * <p>1 = 지금 이 요청이 회전했다(옛 키 삭제 + 유예 키 PENDING), 0 = 없다(이미 회전됐거나 끊겼거나 만료), -1 = 값이 다르다.
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> ROTATE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    local v = redis.call('GET', KEYS[1])
                    if not v then return 0 end
                    if v ~= ARGV[1] then return -1 end
                    redis.call('DEL', KEYS[1])
                    redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
                    return 1
                    """, Long.class);

    public long rotate(String sessionId, String presentedToken, long graceMs) {
        Long r = redisTemplate.execute(ROTATE, java.util.List.of(sessionId, graceKey(sessionId)),
                digest(presentedToken), PENDING, String.valueOf(graceMs));
        return r == null ? 0L : r;
    }

    /** 회전을 끝낸 요청이 새 토큰의 <b>암호문</b>을 유예 키에 적는다 — 동시에 갱신한 다른 탭이 이것을 풀어 받는다(원문은 두지 않는다). */
    public void completeGrace(String oldSessionId, String sealedNewToken, long graceMs) {
        redisTemplate.opsForValue().set(graceKey(oldSessionId), sealedNewToken, java.time.Duration.ofMillis(graceMs));
    }

    /** 유예 키 — 없으면 null, 회전 중이면 {@link #PENDING}, 끝났으면 새 토큰의 암호문. */
    public String grace(String oldSessionId) {
        return redisTemplate.opsForValue().get(graceKey(oldSessionId));
    }

    /** 묶음의 지금 세션을 적는다(회전할 때마다 — TTL 도 새로). */
    public void setFamilyCurrent(String familyId, String sessionId) {
        redisTemplate.opsForValue().set(familyKey(familyId), sessionId, java.time.Duration.ofMillis(jwtRefreshExpirationMs));
    }

    public boolean isFamilyRevoked(String familyId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(revokedKey(familyId)));
    }

    /**
     * 묶음 전체를 끊는다 — 살아 있는 세션 키를 지우고, 끊겼다는 표시를 토큰 수명만큼 남긴다. 표시가 있어야 이미 발급된 다른 토큰
     * (예: 유예 중에 받은 것)도 거절된다. 여러 번 불러도 같다.
     */
    public void revokeFamily(String familyId) {
        String current = redisTemplate.opsForValue().get(familyKey(familyId));
        if (current != null) {
            redisTemplate.delete(current);
        }
        redisTemplate.delete(familyKey(familyId));
        redisTemplate.opsForValue().set(revokedKey(familyId), "1", java.time.Duration.ofMillis(jwtRefreshExpirationMs));
    }

    // ── 사용자 → 묶음 색인("모든 기기에서 로그아웃", docs/68 §8) ─────────────────────────────
    //
    // 세션 키는 sessionId 로만 저장돼 "이 사용자의 모든 로그인" 을 거꾸로 찾을 수 없었다. 로그인 · 갱신마다 묶음 id 를
    // 사용자 집합에 넣는다. TTL 은 토큰 수명 — 마지막 로그인 · 갱신 뒤 수명이 지나면 그 안의 토큰도 모두 만료다.
    // [JA] ユーザー → ファミリーの索引。ログイン・更新のたびにファミリー id を集合に入れる。TTL はトークン寿命。

    /** 이 사용자의 묶음으로 적는다(이미 있으면 그대로) — 수명을 다시 센다. */
    public void addUserFamily(Long userId, String familyId) {
        String key = userKey(userId);
        redisTemplate.opsForSet().add(key, familyId);
        redisTemplate.expire(key, java.time.Duration.ofMillis(jwtRefreshExpirationMs));
    }

    public java.util.Set<String> userFamilies(Long userId) {
        java.util.Set<String> members = redisTemplate.opsForSet().members(userKey(userId));
        return members == null ? java.util.Set.of() : members;
    }

    /**
     * 이미 끝난 묶음(끊겼거나 수명이 지나 {@code rt:fam} 이 없는 것)을 집합에서 뺀다 — 로그인할 때만 부른다.
     * 안 빼면 매일 로그인하는 사용자의 집합이 끝없이 자란다(TTL 이 매번 다시 세어지므로 통째로 사라지지도 않는다).
     */
    public void pruneUserFamilies(Long userId) {
        String key = userKey(userId);
        for (String familyId : userFamilies(userId)) {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(familyKey(familyId)))) {
                redisTemplate.opsForSet().remove(key, familyId);
            }
        }
    }

    public void deleteUserFamilies(Long userId) {
        redisTemplate.delete(userKey(userId));
    }

    /**
     * 긴 연결(채팅 WebSocket · 알림 SSE)도 끊는다 — "지금 이전에 발급된 이 사용자의 액세스 토큰은 긴 연결에 못 쓴다" 를 적고
     * 알린다(common {@code SessionRevocation}, docs/68 §9). HTTP 요청은 여기에 영향을 받지 않는다 — 게이트웨이는 서명만 본다.
     */
    // [JA] 長い接続も切る — 「今より前に発行されたこのユーザーのアクセストークンは長い接続に使えない」を書いて知らせる。
    public void cutoffLiveSessions(Long userId) {
        long nowSec = System.currentTimeMillis() / 1000;
        redisTemplate.opsForValue().set(com.project.common.security.SessionRevocation.cutoffKey(userId),
                String.valueOf(nowSec), java.time.Duration.ofMillis(jwtExpirationMs).plusMinutes(1));
        redisTemplate.convertAndSend(com.project.common.security.SessionRevocation.CHANNEL, String.valueOf(userId));
    }

    private static String userKey(Long userId) {
        return "rt:user:" + userId;
    }

    private static String graceKey(String sessionId) {
        return "rt:grace:" + sessionId;
    }

    private static String familyKey(String familyId) {
        return "rt:fam:" + familyId;
    }

    private static String revokedKey(String familyId) {
        return "rt:revoked:" + familyId;
    }
}
