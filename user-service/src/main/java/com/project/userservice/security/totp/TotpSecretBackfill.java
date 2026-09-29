package com.project.userservice.security.totp;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동할 때 2FA 비밀을 <b>지금 키의 암호문</b>으로 맞춘다(docs/70).
 *
 * <ul>
 *   <li>이 변경 이전의 <b>평문</b> → 암호화.</li>
 *   <li>JWT 파생 키(enc:j1:)로 잠긴 값인데 지금은 전용 키가 있다 → 전용 키(enc:k1:)로 다시 잠근다. 전용 키를 넣고 한 번 배포한 뒤에는
 *       JWT 비밀을 교체해도 아무도 2FA 에 잠기지 않는다.</li>
 * </ul>
 *
 * <p>JPA 가 아니라 JDBC 로 한다 — 엔티티로 읽으면 변환기가 이미 평문으로 풀어 줘서 "바꿀 것이 없다" 로 보이고(더티 체크), 저장되지 않는다.
 * 여러 레플리카가 동시에 돌아도 해가 없다 — 같은 평문을 각자 암호화해 쓸 뿐이다. 값은 로그에 남기지 않는다(개수만).
 */
// [JA] 起動時に 2FA の秘密を現在の鍵の暗号文へそろえる。旧平文は暗号化、派生鍵(j1)の値は専用鍵(k1)があれば再暗号化。JDBC で行う。
@Slf4j
@Component
@RequiredArgsConstructor
public class TotpSecretBackfill implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TotpSecretCipher cipher;

    @Value("${app.totp.backfill-enabled:true}")
    private boolean enabled = true;

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            migrate();
        }
    }

    /** @return 다시 쓴 행 수 */
    public int migrate() {
        List<Object[]> rows = jdbc.query(
                "select user_id, two_factor_secret from users.users where two_factor_secret is not null",
                (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) });
        int changed = 0;
        int failed = 0;
        for (Object[] row : rows) {
            String stored = (String) row[1];
            if (cipher.isCurrent(stored)) {
                continue;
            }
            try {
                String sealed = cipher.encrypt(cipher.decrypt(stored));
                // 그 사이 다른 레플리카 · 사용자가 바꿨으면 덮지 않는다(읽은 값과 같을 때만)
                changed += jdbc.update("update users.users set two_factor_secret = ? where user_id = ? and two_factor_secret = ?",
                        sealed, row[0], stored);
            } catch (IllegalStateException e) {
                failed++;   // 풀 수 없는 값 — 그대로 둔다. 사용자 id 만 남긴다
                log.error("2FA 비밀을 다시 암호화하지 못했다 — userId={} ({})", row[0], e.getMessage());
            }
        }
        if (changed > 0 || failed > 0) {
            log.info("2FA 비밀 암호화 정리 — 다시 쓴 행 {}개, 실패 {}개", changed, failed);
        }
        return changed;
    }
}
