package com.project.userservice.registration;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.WithdrawalService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 유예 기간이 끝난 탈퇴 신청을 최종 처리한다.
 *
 * <p><b>기본값이 켜짐인 이유.</b> 같은 저장소의 {@link UnverifiedAccountCleanup} 은 삭제가
 * 기본 꺼짐인데, 그쪽은 <b>우리가</b> 지우겠다고 판단하는 계정이라 켜기 전에 영향 범위를
 * 확인해야 한다. 여기는 <b>본인이 요청</b>했고 유예 기간까지 지난 계정이다. 꺼 두면 탈퇴
 * 신청이 영원히 처리되지 않는다 — 기능이 동작하지 않는 것과 같다.
 *
 * <p><b>계정마다 독립 트랜잭션이다.</b> {@code WithdrawalService.finalizeWithdrawal} 이
 * 자기 트랜잭션을 여므로, 한 계정이 실패해도 나머지는 진행된다. 실패한 건은 다음 주기에
 * 다시 집힌다({@code withdrawn_at} 이 아직 비어 있으므로).
 *
 * <p>⚠️ 로그에는 userId 와 건수만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalProcessor {

    private final UserRepository userRepository;
    private final WithdrawalService withdrawalService;

    @Value("${app.withdrawal.processor.enabled:true}")
    private boolean enabled;

    /** {@code WithdrawalService} 와 같은 값을 봐야 한다 — 한쪽만 바꾸면 예정일과 실제가 어긋난다. */
    @Value("${app.withdrawal.grace:P7D}")
    private Duration grace;

    @Value("${app.withdrawal.processor.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.withdrawal.processor.interval-ms:3600000}",
            initialDelayString = "${app.withdrawal.processor.initial-delay-ms:300000}")
    public void run() {
        if (!enabled) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minus(grace);
        List<Long> due = userRepository.findDueForWithdrawal(cutoff, PageRequest.of(0, batchSize));

        if (due.isEmpty()) {
            return;
        }

        int processed = 0;
        int skipped = 0;
        for (Long userId : due) {
            try {
                if (withdrawalService.finalizeWithdrawal(userId)) {
                    processed++;
                } else {
                    // 조회와 처리 사이에 취소했다. 정상적인 경로다.
                    skipped++;
                }
            } catch (RuntimeException e) {
                skipped++;
                log.warn("회원 탈퇴 최종 처리 실패 — 다음 주기에 다시 시도한다: userId={}, 원인={}",
                        userId, e.getMessage());
            }
        }

        log.warn("회원 탈퇴 최종 처리: {}건 처리, {}건 건너뜀 (대상 {}건)", processed, skipped, due.size());
    }
}
