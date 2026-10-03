package com.project.userservice.registration;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 끝내 인증하지 않은 계정과 만료된 토큰을 정리한다.
 *
 * <p><b>왜 필요한가.</b> 인증 게이트를 켜면(`APP_REQUIRE_EMAIL_VERIFICATION=true`) 링크를 누르지
 * 않은 계정이 비활성 상태로 쌓인다. 그 계정은 아무 기능도 하지 않으면서 <b>그 이메일 주소를
 * 영구히 점유한다</b> — 같은 주소로 다시 가입하면 "이미 존재한다"만 돌아온다. 만료된 확인 토큰과
 * 비밀번호 재설정 토큰도 지우는 코드가 리포지토리에 있었지만 <b>아무도 호출하지 않아</b> 계속
 * 쌓이고 있었다.
 *
 * <p><b>무엇을 근거로 "미인증"이라 판단하나.</b> 인증에 성공하면 {@code validateAndGetUser} 가
 * 계정을 활성화하고 토큰을 지운다. 그래서 <b>비활성 계정 + 남아 있는 토큰</b>이면 한 번도
 * 인증하지 않은 것이다. 재발송은 같은 토큰 행의 만료를 갱신하므로, 최근에 재발송을 요청한
 * 사람은 유예 기간 계산에서 자동으로 빠진다.
 *
 * <p><b>삭제는 기본으로 꺼져 있다.</b> 되돌릴 수 없는 작업이라서다. 꺼진 상태에서는 "지웠다면
 * 몇 건이었는지"만 로그로 남긴다 — 켜기 전에 영향 범위를 숫자로 확인할 수 있다. 이 저장소가
 * 메일·SMS 발송기를 기본 logging 으로 둔 것과 같은 이유다.
 *
 * <p><b>안전장치 넷.</b>
 * <ul>
 *   <li>활성 계정은 후보에 오르지 않는다. 조건이 {@code enabled = false} 다.</li>
 *   <li>토큰 만료 뒤 <b>유예 기간</b>(기본 7일)을 더 기다린다. 만료 직후 지우면 재발송으로
 *       복구하려던 사용자를 잘라 낸다.</li>
 *   <li>행마다 <b>독립 트랜잭션</b>이다. 하나가 실패해도 나머지는 진행된다.</li>
 *   <li>주문·리뷰·배송지·위시리스트가 있는 계정은 <b>외래키가 막는다.</b> 그 제약을 코드로
 *       다시 열거하지 않고 DB 가 판단하게 둔다 — 나중에 참조가 하나 늘어도 이 코드는 그대로
 *       안전하다. 막히면 건너뛰고 로그만 남긴다.</li>
 * </ul>
 *
 * <p>⚠️ 이메일 주소는 로그에 남기지 않는다. 사용자 id 만 남긴다.
 */
@Slf4j
@Component
public class UnverifiedAccountCleanup {

    private final VerificationTokenRepository tokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate tx;

    /** 만료 토큰 정리. 해가 없으므로 기본 켜짐 — 끄면 토큰 테이블이 무한히 자란다. */
    @Value("${app.verification.cleanup.enabled:true}")
    private boolean enabled;

    /** 계정 삭제. 되돌릴 수 없으므로 기본 꺼짐 — 꺼진 동안에는 대상 건수만 로그로 남는다. */
    @Value("${app.verification.cleanup.delete-accounts:false}")
    private boolean deleteAccounts;

    /** 토큰이 만료된 뒤 이만큼 더 기다린다. 재발송으로 복구할 시간을 준다. */
    @Value("${app.verification.cleanup.grace:P7D}")
    private Duration grace;

    /** 한 번에 처리할 계정 수. 트랜잭션을 짧게 유지한다. */
    @Value("${app.verification.cleanup.batch-size:200}")
    private int batchSize;

    public UnverifiedAccountCleanup(VerificationTokenRepository tokenRepository,
                                    PasswordResetTokenRepository passwordResetTokenRepository,
                                    UserRepository userRepository,
                                    TransactionTemplate tx) {
        this.tokenRepository = tokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.verification.cleanup.interval-ms:3600000}",
            initialDelayString = "${app.verification.cleanup.initial-delay-ms:300000}")
    public void run() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        cleanUpAccounts(Date.from(now.minus(grace)));
        purgeExpiredTokens(Date.from(now));
    }

    /** @param cutoff 이 시각보다 먼저 만료된 토큰의 계정이 대상이다(= 만료 후 유예 기간이 지났다). */
    void cleanUpAccounts(Date cutoff) {
        List<VerificationToken> stale =
                tokenRepository.findStaleUnverified(cutoff, PageRequest.of(0, batchSize));
        if (stale.isEmpty()) {
            return;
        }

        if (!deleteAccounts) {
            // 켜기 전에 영향 범위를 보여 준다. 이 숫자가 예상과 다르면 켜면 안 된다는 뜻이다.
            log.info("미인증 계정 정리 대상 {}건 — app.verification.cleanup.delete-accounts=false 라 "
                    + "삭제하지 않는다", stale.size());
            return;
        }

        int deleted = 0;
        int skipped = 0;
        for (VerificationToken token : stale) {
            if (deleteOne(token.getId())) {
                deleted++;
            } else {
                skipped++;
            }
        }
        // 삭제는 되돌릴 수 없으므로 info 가 아니라 warn 으로 남긴다.
        log.warn("미인증 계정 정리: 삭제 {}건, 건너뜀 {}건 (대상 {}건)", deleted, skipped, stale.size());
    }

    /**
     * 한 계정을 독립 트랜잭션으로 지운다.
     *
     * @return 실제로 지웠으면 true. 이미 없어졌거나(다른 레플리카가 먼저 처리) 그 사이 인증됐거나
     *         외래키가 막았으면 false.
     */
    private boolean deleteOne(Long tokenId) {
        Long deletedUserId;
        try {
            deletedUserId = tx.execute(status -> {
                VerificationToken token = tokenRepository.findById(tokenId).orElse(null);
                if (token == null) {
                    return null;   // 다른 레플리카가 먼저 처리했다
                }
                User user = token.getUser();
                if (user == null || user.isEnabled()) {
                    return null;   // 조회와 삭제 사이에 인증을 마쳤다 — 건드리면 안 된다
                }
                Long userId = user.getUserId();
                tokenRepository.delete(token);
                userRepository.delete(user);
                return userId;
            });
        } catch (DataIntegrityViolationException e) {
            // 이 계정을 참조하는 데이터가 있다. 지우면 그 데이터가 깨지므로 DB 의 판단을 따른다.
            log.warn("미인증 계정 삭제를 건너뛴다 — 참조하는 데이터가 있다: tokenId={}", tokenId);
            return false;
        } catch (RuntimeException e) {
            log.warn("미인증 계정 삭제 실패: tokenId={}, 원인={}", tokenId, e.getMessage());
            return false;
        }

        if (deletedUserId == null) {
            return false;
        }
        log.info("미인증 계정 삭제: userId={}", deletedUserId);
        return true;
    }

    /**
     * 만료 토큰 정리.
     *
     * <p>확인 토큰은 <b>이미 활성화된 계정의 것만</b> 지운다 — 비활성 계정의 토큰은 위 판정의
     * 유일한 근거라서, 유예 기간이 끝나기 전에 지우면 그 계정이 영원히 후보에 오르지 않는다.
     * 비밀번호 재설정 토큰은 판정에 쓰이지 않으므로 만료된 것을 모두 지운다.
     */
    void purgeExpiredTokens(Date now) {
        try {
            tx.executeWithoutResult(status -> {
                int verification = tokenRepository.deleteExpiredOfEnabledUsers(now);
                passwordResetTokenRepository.deleteAllExpiredSince(now.toInstant());
                if (verification > 0) {
                    log.info("만료된 확인 토큰 {}건 정리", verification);
                }
            });
        } catch (RuntimeException e) {
            // 다음 주기에 다시 시도하면 된다. 정리 실패가 서비스를 막을 이유는 없다.
            log.warn("만료 토큰 정리 실패: {}", e.getMessage());
        }
    }
}
