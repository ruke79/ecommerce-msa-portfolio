package com.project.userservice.security.totp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 2FA(TOTP) 비밀을 DB 에 <b>암호화해서</b> 둔다(docs/70).
 *
 * <h3>왜 해시가 아니라 암호화인가</h3>
 *
 * <p>TOTP 는 서버와 인증 앱이 <b>같은 비밀</b>로 30초마다 6자리를 계산해 맞춰 보는 방식이다. 서버는 검증할 때마다 비밀 원문이 필요하다 —
 * 해시로는 코드를 계산할 수 없다. 그래서 되돌릴 수 있는 암호화(AES-256-GCM)를 쓴다.
 *
 * <p>🐛 예전: 평문 컬럼이었고, 관리자 사용자 목록 API 가 {@code User} 엔티티를 그대로 내보내 <b>모든 사용자의 비밀</b>이 응답에 실렸다.
 * 비밀을 가진 사람은 그 계정의 6자리를 언제든 만들 수 있다 — 2단계 인증이 비밀번호 하나로 줄어든다.
 *
 * <h3>키</h3>
 *
 * <ul>
 *   <li><b>{@code TOTP_ENCRYPTION_KEY}</b>(base64 32바이트) — 권장. 표시 {@code enc:k1:}.</li>
 *   <li>없으면 JWT 비밀에서 용도별로 파생 — 표시 {@code enc:j1:}. 새 비밀 없이 바로 배포할 수 있지만, JWT 비밀을 교체하면 이 값들을 풀 수
 *       없게 된다. 그래서 암호문마다 어느 키로 잠갔는지 적고, 기동할 때 {@link TotpSecretBackfill} 이 지금 키로 다시 잠근다 —
 *       전용 키를 넣고 한 번 배포한 <b>뒤에</b> JWT 비밀을 교체하면 아무도 잠기지 않는다.</li>
 * </ul>
 *
 * <p>접두사가 없는 값은 이 변경 이전의 <b>평문</b>이다 — 그대로 읽고(로그인이 끊기지 않게), 백필이 암호화한다.
 */
// [JA] 2FA(TOTP)の秘密を DB に暗号化して置く。TOTP は検証のたびに秘密の原文が要るのでハッシュでは不可、AES-256-GCM で暗号化する。
//      鍵は TOTP_ENCRYPTION_KEY(推奨, enc:k1:)、なければ JWT 秘密から派生(enc:j1:)。接頭辞のない値は旧平文で、そのまま読みバックフィルが暗号化する。
@Slf4j
@Component
public class TotpSecretCipher {

    static final String DEDICATED = "enc:k1:";
    static final String DERIVED = "enc:j1:";
    private static final String PURPOSE = "totp-at-rest-v1";
    private static final byte[] AAD = "users.two_factor_secret".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey dedicated;   // 없으면 null
    private final SecretKey derived;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(@Value("${app.totp.encryption-key:}") String dedicatedKeyBase64,
            @Value("${spring.app.jwtSecret}") String jwtSecret) {
        this.dedicated = dedicatedKeyBase64 == null || dedicatedKeyBase64.isBlank() ? null : parse(dedicatedKeyBase64.trim());
        this.derived = derive(jwtSecret);
        TotpSecretConverter.use(this);
        if (dedicated == null) {
            // 값은 남기지 않는다 — 있고 없음만.
            log.warn("TOTP_ENCRYPTION_KEY 가 없다 — 2FA 비밀을 JWT 비밀에서 파생한 키로 암호화한다. JWT 비밀을 교체하기 전에 전용 키를 넣고 한 번 배포할 것(docs/70)");
        }
    }

    /** 지금 쓰는 키의 표시 — 이것이 아닌 값은 백필이 다시 잠근다. */
    public String currentPrefix() {
        return dedicated != null ? DEDICATED : DERIVED;
    }

    public boolean isCurrent(String stored) {
        return stored != null && stored.startsWith(currentPrefix());
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, dedicated != null ? dedicated : derived, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return currentPrefix() + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("2FA 비밀을 암호화하지 못했다", e);
        }
    }

    /**
     * 복호화. 접두사가 없으면 옛 평문 그대로. 풀 수 없으면(키가 바뀌었거나 값이 손상됐다) 예외 — 틀린 비밀로 조용히 검증하면
     * 사용자는 이유도 모른 채 2FA 에 계속 실패한다. 운영자가 보게 크게 실패한다.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith("enc:")) {
            return stored;
        }
        SecretKey key;
        String body;
        if (stored.startsWith(DEDICATED)) {
            if (dedicated == null) {
                throw new IllegalStateException("2FA 비밀이 전용 키로 암호화돼 있는데 TOTP_ENCRYPTION_KEY 가 없다");
            }
            key = dedicated;
            body = stored.substring(DEDICATED.length());
        } else if (stored.startsWith(DERIVED)) {
            key = derived;
            body = stored.substring(DERIVED.length());
        } else {
            throw new IllegalStateException("모르는 2FA 비밀 암호화 형식");
        }
        try {
            byte[] in = Base64.getDecoder().decode(body);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(AAD);
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("2FA 비밀을 복호화하지 못했다(키가 바뀌었거나 값이 손상됐다)", e);
        }
    }

    private static SecretKey parse(String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY 는 base64 여야 한다(openssl rand -base64 32)", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY 는 32바이트여야 한다(openssl rand -base64 32)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static SecretKey derive(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(PURPOSE.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
