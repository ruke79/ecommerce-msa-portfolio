package com.project.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JWT 서명 키 링 — 키에 이름(kid)을 붙여 <b>여러 개를 동시에</b> 알고, 그중 하나로만 서명한다(docs/68 §17).
 *
 * <h3>왜 필요한가</h3>
 *
 * <p>🐛 예전: 키가 {@code JWT_SECRET_KEY} 하나였다. 키를 바꾸는 순간 그 전에 발급된 모든 액세스 · 리프레시 토큰의 서명이 맞지 않아
 * <b>전원이 한꺼번에 로그아웃</b>됐다. 그래서 키가 샌 것이 의심돼도 "바꾸면 장애" 라 미루게 된다 — 교체할 수 없는 키는 결국 영원히 쓰는 키다.
 *
 * <p>지금: 토큰 머리(header)에 {@code kid} 를 적고, 검증하는 쪽은 그 이름으로 키를 찾는다. 교체는 세 단계다(ops/46):
 * <ol>
 *   <li><b>추가</b> — 새 키를 링에 넣고 배포한다. 아직 서명은 옛 키로 한다. 모든 검증자(게이트웨이 · user · chat)가 새 키를 알게 된다.</li>
 *   <li><b>전환</b> — 서명 키를 새 키로 바꾼다. 옛 토큰은 옛 키로 계속 검증된다 — 아무도 로그아웃되지 않는다.</li>
 *   <li><b>은퇴</b> — 옛 키로 서명된 토큰이 모두 만료된 뒤(리프레시 절대 수명 30일) 링에서 뺀다.</li>
 * </ol>
 * 추가와 전환을 한 번에 하면 안 된다 — 새 키를 아직 모르는 검증자(롤아웃 중인 다른 서비스)가 새 토큰을 거절한다.
 *
 * <h3>옛 토큰(kid 없음)</h3>
 *
 * <p>{@code JWT_SECRET_KEY} 는 이름 {@value #LEGACY_KID} 로 링에 들어간다. kid 가 없는 토큰(이 변경 전에 발급된 것)은 이 키로 검증한다.
 * 은퇴시킬 때는 {@code JWT_ACCEPT_LEGACY=false} — 그 뒤로 kid 없는 토큰과 {@value #LEGACY_KID} 토큰은 거절된다.
 * {@code JWT_SECRET_KEY} 자체는 다른 키(2FA 대기 토큰 · 유예 암호)를 <b>파생</b>하는 뿌리로 계속 쓰이므로 지우지 않는다.
 *
 * <p>라이브러리와 무관하게 바이트만 다룬다 — user-service · 게이트웨이는 jjwt, chat-service 는 auth0 java-jwt 를 쓴다.
 */
// [JA] JWT 署名鍵リング — 鍵に名前(kid)を付けて複数を同時に知り、そのうち一つだけで署名する。以前は鍵が一つで、変えた瞬間に全員がログアウトした。
//      今は追加 → 切り替え → 引退の 3 段階で、誰もログアウトさせずに交換できる。kid のない旧トークンは JWT_SECRET_KEY(legacy)で検証する。
public final class JwtKeyRing {

    /** {@code JWT_SECRET_KEY} 의 이름 — kid 가 없는 옛 토큰도 이 키로 본다. */
    public static final String LEGACY_KID = "legacy";

    /** HS256 은 256비트 이상 — 코드는 문자열을 디코딩하지 않고 UTF-8 바이트 그대로 쓴다(k8s/20-secret.example.yaml). */
    static final int MIN_KEY_BYTES = 32;

    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final Map<String, byte[]> keys;
    private final String activeKid;
    private final boolean acceptLegacy;

    private JwtKeyRing(Map<String, byte[]> keys, String activeKid, boolean acceptLegacy) {
        this.keys = Collections.unmodifiableMap(keys);
        this.activeKid = activeKid;
        this.acceptLegacy = acceptLegacy;
    }

    /**
     * @param legacySecret  {@code JWT_SECRET_KEY}
     * @param signingKeys   {@code JWT_SIGNING_KEYS} — {@code "kid=비밀,kid2=비밀2"}, 비어 있으면 legacy 하나뿐
     * @param activeKid     {@code JWT_ACTIVE_KID} — 비어 있으면 {@value #LEGACY_KID}
     * @param acceptLegacy  {@code JWT_ACCEPT_LEGACY} — false 면 legacy 키를 검증에 쓰지 않는다
     * @throws IllegalStateException 설정이 틀렸다 — 기동을 멈춘다(틀린 키로 서명하느니 뜨지 않는 편이 낫다)
     */
    public static JwtKeyRing of(String legacySecret, String signingKeys, String activeKid, boolean acceptLegacy) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (legacySecret != null && !legacySecret.isBlank()) {
            keys.put(LEGACY_KID, bytes(LEGACY_KID, legacySecret));
        }
        if (signingKeys != null && !signingKeys.isBlank()) {
            for (String entry : signingKeys.split(",")) {
                String e = entry.trim();
                if (e.isEmpty()) {
                    continue;
                }
                int eq = e.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalStateException("JWT_SIGNING_KEYS 형식은 kid=비밀,kid2=비밀2 입니다 (항목 " + (keys.size() + 1) + ")");
                }
                String kid = e.substring(0, eq).trim();
                if (!KID.matcher(kid).matches() || LEGACY_KID.equals(kid)) {
                    throw new IllegalStateException("JWT_SIGNING_KEYS 의 kid 는 영문 · 숫자 · . _ - 1~32자이고 '" + LEGACY_KID + "' 가 아니어야 합니다: " + kid);
                }
                if (keys.containsKey(kid)) {
                    throw new IllegalStateException("JWT_SIGNING_KEYS 에 같은 kid 가 두 번 있습니다: " + kid);
                }
                keys.put(kid, bytes(kid, e.substring(eq + 1).trim()));
            }
        }

        String active = activeKid == null || activeKid.isBlank() ? LEGACY_KID : activeKid.trim();
        if (!keys.containsKey(active)) {
            throw new IllegalStateException("JWT_ACTIVE_KID '" + active + "' 가 키 링에 없습니다 — 먼저 추가하고 배포한 뒤 전환합니다(ops/46)");
        }
        if (!acceptLegacy && LEGACY_KID.equals(active)) {
            throw new IllegalStateException("legacy 키로 서명하면서 legacy 를 거절할 수는 없습니다 — 먼저 다른 키로 전환합니다(ops/46)");
        }
        return new JwtKeyRing(keys, active, acceptLegacy);
    }

    private static byte[] bytes(String kid, String secret) {
        byte[] b = secret.getBytes(StandardCharsets.UTF_8);
        if (b.length < MIN_KEY_BYTES) {
            // 값은 찍지 않는다 — 길이만.
            throw new IllegalStateException("JWT 키 '" + kid + "' 가 " + b.length + "바이트입니다 — HS256 은 " + MIN_KEY_BYTES + "바이트 이상(openssl rand -hex 32)");
        }
        return b;
    }

    /** 서명에 쓸 이름 — 토큰 머리의 kid 에 적는다. */
    public String activeKid() {
        return activeKid;
    }

    /** 서명 키. */
    public byte[] activeKey() {
        return keys.get(activeKid);
    }

    /**
     * 검증 키 — 토큰 머리의 kid 로 찾는다. kid 가 없으면 옛 토큰이므로 legacy.
     *
     * @return 모르는 kid · 은퇴한 legacy 면 빈 값(= 거절)
     */
    public Optional<byte[]> verificationKey(String kid) {
        String name = kid == null || kid.isBlank() ? LEGACY_KID : kid;
        if (LEGACY_KID.equals(name) && !acceptLegacy) {
            return Optional.empty();
        }
        return Optional.ofNullable(keys.get(name));
    }

    /** 운영 확인용 — 이름만(값은 절대 내보내지 않는다). */
    public java.util.Set<String> kids() {
        return keys.keySet();
    }

    public boolean acceptsLegacy() {
        return acceptLegacy;
    }
}
