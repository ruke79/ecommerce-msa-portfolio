package com.project.userservice.security.totp;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code User.twoFactorSecret} ↔ DB 컬럼 — 쓸 때 암호화, 읽을 때 복호화(docs/70). 엔티티 코드는 평문만 본다.
 *
 * <p>Hibernate 는 변환기를 스프링 밖에서 만들 수도 있다(슬라이스 테스트 등). 그래서 생성자 주입 대신 {@link TotpSecretCipher} 가 기동할 때
 * 자신을 {@link #use} 로 등록한다. 등록 전에 비밀을 쓰거나 읽으면 <b>평문으로 흘리지 않고</b> 실패한다.
 */
// [JA] User.twoFactorSecret ↔ DB カラム — 書くとき暗号化、読むとき復号。暗号器が未登録なら平文で流さず失敗する。
@Converter
public class TotpSecretConverter implements AttributeConverter<String, String> {

    private static volatile TotpSecretCipher cipher;

    static void use(TotpSecretCipher c) {
        cipher = c;
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        return plain == null ? null : required().encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return stored == null ? null : required().decrypt(stored);
    }

    private static TotpSecretCipher required() {
        TotpSecretCipher c = cipher;
        if (c == null) {
            throw new IllegalStateException("TotpSecretCipher 가 아직 없다 — 2FA 비밀을 평문으로 쓰거나 읽지 않는다");
        }
        return c;
    }
}
