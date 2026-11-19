package com.project.userservice.security.recovery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.TwoFactorRecoveryCode;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.TwoFactorRecoveryCodeRepository;
import com.project.userservice.security.totp.TotpReplayGuard;
import com.project.userservice.service.UserService.TwoFactorChange;

import lombok.extern.slf4j.Slf4j;

/**
 * 2단계 인증 복구 코드(docs/57 §22) — 인증 앱 · 휴대폰을 잃었을 때 로그인 2단계를 대신하는 1회용 코드 10개.
 *
 * <h3>왜</h3>
 *
 * <p>🐛 예전에는 복구 수단이 없었다. 인증 앱을 지운 사람, 번호를 바꾼 사람(SMS 수단은 켤 때의 번호에 묶인다 — docs/24 §10)은 비밀번호를
 * 알아도 들어올 수 없었다. 비밀번호 재설정은 2단계 인증을 풀지 않는다 — 풀면 메일함만 턴 사람이 2단계 인증을 건너뛴다.
 *
 * <h3>지키는 것</h3>
 * <ul>
 *   <li><b>원문은 한 번만</b> — 만들 때 응답으로만 준다. DB 에는 HMAC-SHA256(파생 키, 코드)만(DB 만 샌 사람은 되돌릴 수 없다).
 *       키는 {@code auth.recovery-code.pepper}(없으면 JWT 비밀)에서 용도별로 파생한다 — 그 값을 바꾸면 모든 복구 코드가 무효가 된다.</li>
 *   <li><b>한 번만 쓴다</b> — 쓰지 않은 행만 바꾸는 UPDATE 하나(동시에 두 번 보내도 하나만 통과).</li>
 *   <li><b>시도는 TOTP · SMS 와 같은 사용자별 칸</b>({@link TotpReplayGuard}) — 수단을 바꿔 가며 한도를 늘리지 못한다.
 *       코드는 31글자 × 10자리(약 50비트)라 대입은 그 한도 안에서 의미가 없다.</li>
 *   <li><b>만들기 · 쓰기는 주인에게 알린다</b>(수신 설정과 무관) — 본인이 아니면 누군가 비밀번호와 세션을 가졌다는 신호다.</li>
 *   <li><b>2단계 인증이 모두 꺼지면 지운다</b> — 나중에 다시 켰을 때 옛 코드가 되살아나지 않게.</li>
 * </ul>
 *
 * <p>[JA] 二段階認証のリカバリーコード — 認証アプリ · 携帯を失ったときにログイン二段階目を代わる使い捨てのコード 10 個。原文は作成時の
 * 応答でだけ渡し、DB には HMAC だけ。一度だけ使える。試行は TOTP · SMS と同じ枠。作成 · 使用は持ち主に知らせる。2FA が全部オフなら消す。
 */
@Slf4j
@Component
public class RecoveryCodes {

    /** 한 번에 만드는 수. [JA] 一度に作る数。 */
    public static final int COUNT = 10;
    /** 헷갈리는 글자(0 · O · 1 · I · L)를 뺀 31글자. [JA] 紛らわしい文字を除いた 31 文字。 */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int LENGTH = 10;
    static final String PURPOSE = "2fa_recovery_code";

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final TwoFactorRecoveryCodeRepository repository;
    private final TotpReplayGuard attempts;
    private final SecurityAlertProducer alerts;
    private final TransactionTemplate tx;

    public RecoveryCodes(@Value("${auth.recovery-code.pepper:${spring.app.jwtSecret}}") String pepper,
            TwoFactorRecoveryCodeRepository repository, TotpReplayGuard attempts, SecurityAlertProducer alerts,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.key = derive(pepper);
        this.repository = repository;
        this.attempts = attempts;
        this.alerts = alerts;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private static SecretKey derive(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(PURPOSE.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("복구 코드 키를 만들지 못했다", e);
        }
    }

    /**
     * 새로 만든다 — 그 사용자의 옛 코드를 모두 지우고 {@link #COUNT} 개를 만든다. 2단계 인증이 꺼져 있으면 만들지 않는다(빈 목록).
     * 부르는 쪽이 최근 로그인을 확인한다(훔친 세션으로 코드를 받아 가지 못하게).
     *
     * @return 원문(보기 쉽게 {@code XXXXX-XXXXX}) — 이 응답 밖에는 어디에도 없다
     */
    public List<String> regenerate(User user) {
        if (user == null || !user.requiresTwoFactor()) {
            return List.of();
        }
        List<String> plain = new ArrayList<>(COUNT);
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(s -> {
            repository.deleteByUserId(user.getUserId());
            List<TwoFactorRecoveryCode> rows = new ArrayList<>(COUNT);
            while (plain.size() < COUNT) {
                String code = newCode();
                if (!plain.contains(code)) {
                    plain.add(code);
                    rows.add(new TwoFactorRecoveryCode(user.getUserId(), hash(code), now));
                }
            }
            repository.saveAll(rows);
        });
        alerts.publishAccountEvent(user.getUserId(), user.getEmail(), SecurityAlertProducer.ALERT_RECOVERY_CODES_CREATED, null);
        log.info("2단계 인증 복구 코드 새로 만듦: userId={}", user.getUserId());
        return plain.stream().map(c -> c.substring(0, 5) + "-" + c.substring(5)).toList();
    }

    /** 아직 쓰지 않은 코드 수. [JA] 未使用のコード数。 */
    public long remaining(Long userId) {
        return userId == null ? 0 : repository.countByUserIdAndUsedAtIsNull(userId);
    }

    /**
     * 로그인 2단계 — 복구 코드로 통과한다. 시도는 확인 <b>전에</b> 센다. 맞으면 그 코드는 다시 쓸 수 없고 주인에게 알린다.
     * [JA] ログイン二段階目 — リカバリーコードで通る。試行は確認の前に数える。合えばそのコードは二度と使えず、持ち主に知らせる。
     */
    public TwoFactorChange verifyLogin(User user, String code) {
        if (user == null || !user.requiresTwoFactor()) {
            return TwoFactorChange.NOT_ENABLED;
        }
        Long userId = user.getUserId();
        long attempt = attempts.reserveAttempt(userId);
        if (attempt > attempts.maxFailures()) {
            return TwoFactorChange.TOO_MANY_ATTEMPTS;
        }
        String normalized = normalize(code);
        if (normalized == null || repository.consume(userId, hash(normalized), LocalDateTime.now()) != 1) {
            log.info("2단계 인증 복구 코드 확인 실패: userId={}, 실패={}", userId, attempt);
            if (attempt == attempts.maxFailures()) {
                alerts.publishAccountEvent(userId, user.getEmail(), SecurityAlertProducer.ALERT_2FA_FAILURES, null);
            }
            return TwoFactorChange.INVALID_CODE;
        }
        attempts.clearFailures(userId);
        alerts.publishAccountEvent(userId, user.getEmail(), SecurityAlertProducer.ALERT_RECOVERY_CODE_USED, null);
        log.info("2단계 인증 복구 코드로 로그인: userId={}, 남은 코드={}", userId, remaining(userId));
        return TwoFactorChange.OK;
    }

    /** 2단계 인증이 모두 꺼졌으면 코드를 지운다 — 다시 켰을 때 옛 코드가 되살아나지 않게. [JA] 2FA が全部オフならコードを消す。 */
    public void forgetIfNoTwoFactor(User user) {
        if (user != null && !user.requiresTwoFactor()) {
            int deleted = repository.deleteByUserId(user.getUserId());
            if (deleted > 0) {
                log.info("2단계 인증이 모두 꺼져 복구 코드를 지움: userId={}, {}개", user.getUserId(), deleted);
            }
        }
    }

    /** 대문자 · 하이픈 · 공백 무시 — 형식이 다르면 null(시도로는 센다). [JA] 大文字 · ハイフン · 空白を無視。 */
    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (c.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < c.length(); i++) {
            if (ALPHABET.indexOf(c.charAt(i)) < 0) {
                return null;
            }
        }
        return c;
    }

    String hash(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String newCode() {
        StringBuilder b = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }
}
