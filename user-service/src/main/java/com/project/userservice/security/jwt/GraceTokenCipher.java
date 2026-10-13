package com.project.userservice.security.jwt;

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

/**
 * 동시 갱신 유예 키에 넣는 <b>새 리프레시 토큰</b>을 암호화한다(docs/68 §10).
 *
 * <h3>왜 해시가 아니라 암호화인가</h3>
 *
 * <p>리프레시 토큰은 Redis 에 SHA-256 해시로만 둔다 — Redis 가 유출돼도 해시로는 토큰을 만들 수 없다. 그런데 동시 갱신 유예(§2.5)는
 * 두 번째 탭에게 "방금 첫 번째 탭이 받은 <b>바로 그 토큰</b>" 을 돌려줘야 한다. 해시는 한 방향이라 원문으로 되돌릴 수 없다 —
 * 서버가 20초 동안은 원문을 <b>꺼낼 수 있어야</b> 한다. 원문을 그대로 두면 "해시만 저장" 이 그 20초 동안 거짓이 된다.
 *
 * <p>그래서 이 값만 AES-256-GCM 으로 암호화한다. 키는 Redis 에 없다 — 서버의 JWT 비밀에서 용도별로 파생한다(PendingTwoFactor 와 같은 방식).
 * Redis 덤프만 가진 공격자에게 이 값은 암호문일 뿐이다. JWT 비밀까지 가진 공격자는 이미 아무 토큰이나 위조할 수 있으므로 더 잃을 것이 없다.
 *
 * <p>AAD(추가 인증 데이터) = 옛 세션 id. 다른 키의 암호문을 옮겨 붙이면 복호화가 실패한다.
 */
// [JA] 同時更新の猶予キーに入れる新しいリフレッシュトークンを暗号化する。ハッシュは一方向で、猶予は 2 つ目のタブに「そのもの」を返す必要があるため
//      原文を取り出せなければならない。原文のまま置けば 20 秒間「ハッシュのみ保存」が偽になる。キーは Redis になく、JWT 秘密から用途別に派生する。
@Component
public class GraceTokenCipher {

    /** 키 파생 용도 — 다른 용도(2FA 대기 토큰 등)의 키와 섞이지 않는다. 바꾸면 진행 중인 유예 값만 못 읽는다(최대 20초). */
    static final String PURPOSE = "refresh-grace-v1";
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public GraceTokenCipher(@Value("${spring.app.jwtSecret}") String jwtSecret) {
        this.key = derive(jwtSecret);
    }

    /** 암호화 — {@code v1:} + base64(iv ‖ 암호문 ‖ 태그). */
    public String seal(String oldSessionId, String newToken) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(oldSessionId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(newToken.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("유예 토큰을 암호화하지 못했다", e);
        }
    }

    /** 복호화 — 형식이 틀렸거나 · 위변조됐거나 · 다른 세션의 값이면 null(호출자가 거절한다). */
    public String open(String oldSessionId, String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] in = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            // 🐛 IV 뒤가 태그(16바이트)보다 짧으면 JDK 17(운영 이미지)은 AEADBadTagException 대신 ProviderException(ShortBufferException)
            //    을 던진다 — RuntimeException 이라 아래 catch 를 지나 갱신 요청이 500 이 됐다. 길이로 먼저 거른다(JDK 21 은 AEADBadTag).
            // [JA] IV の後ろがタグ長より短いと JDK 17 は ProviderException を投げ、catch をすり抜けて 500 になっていた。長さで先に弾く。
            if (in.length < IV_BYTES + TAG_BITS / 8) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(oldSessionId.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
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
