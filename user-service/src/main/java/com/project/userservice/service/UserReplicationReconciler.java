package com.project.userservice.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.project.userservice.message.UserOutboxHelper;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 복제 대사 — 최근 가입·변경·탈퇴한 사용자의 이벤트를 <b>다시 보낸다</b> (docs/48 §6-③, docs/57 §14).
 *
 * <p>복제본은 이제 둘이다 — chat 의 회원 행(닉네임을 화면에 쓴다)과 coupon 의 가입 쿠폰. 이벤트는 아웃박스로 나가지만
 * (§6-②), 소비자 쪽에서 실패해 DLT 로 가면 거기서 멈춘다. 특히 <b>가입 쿠폰은 못 받았다는 사실을 나중에 알 방법이 없다</b>
 * — 읽기 시점에 알아차릴 수 없는 종류의 누락이라 주기적으로 훑는 것 말고는 답이 없다.
 *
 * <p><b>왜 다시 보내기인가.</b> 받는 쪽이 모두 멱등이다: coupon 은 (쿠폰 이름, 사용자)가 이미 있으면 만들지 않고, chat 은
 * 가입이면 없을 때만 만들고, 변경은 {@code updatedAt} 이 더 새로울 때만 적용하고, 탈퇴는 여러 번 해도 결과가 같다. 그래서
 * "빠진 것을 찾아 메꾸기" 대신 "최근 것을 다시 보내기" 로 같은 결과를 얻는다 — 받는 쪽을 조회하는 API 가 필요 없다.
 *
 * <p>범위는 최근 {@code windowDays}(기본 7일 — Kafka 보존 기간과 같다). 그보다 오래된 누락(이 배치가 생기기 전의 가입자)은
 * 한 번만 넓혀 돌린다: {@code user.replication.reconcile.window-days} 를 크게 주고 재시작한 뒤 되돌린다.
 */
@Slf4j
@Component
public class UserReplicationReconciler {

    private static final int PAGE = 200;

    private final UserRepository userRepository;
    private final UserOutboxHelper outbox;
    private final TransactionTemplate tx;

    @Value("${user.replication.reconcile.enabled:true}")
    private boolean enabled = true;

    @Value("${user.replication.reconcile.window-days:7}")
    private int windowDays = 7;

    public UserReplicationReconciler(UserRepository userRepository, UserOutboxHelper outbox,
                                     PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Result(int created, int updated, int deleted) {}

    @Scheduled(cron = "${user.replication.reconcile.cron:0 25 19 * * *}", zone = "UTC")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            Result r = reconcile(LocalDateTime.now(ZoneOffset.UTC).minusDays(windowDays));
            log.info("복제 대사: user-created={} user-updated={} user-deleted={} (최근 {}일)",
                    r.created(), r.updated(), r.deleted(), windowDays);
        } catch (Exception e) {
            log.error("복제 대사 실패: {}", e.toString());
        }
    }

    /**
     * {@code since} 이후 손댄 사용자마다: 탈퇴했으면 user-deleted, 그 안에 가입했으면 user-created + user-updated, 아니면
     * user-updated. 페이지마다 한 트랜잭션 — 아웃박스 기록은 호출자의 트랜잭션을 요구한다(MANDATORY).
     */
    public Result reconcile(LocalDateTime since) {
        int created = 0, updated = 0, deleted = 0;
        long after = 0;
        while (true) {
            final long cursor = after;
            List<User> page = userRepository.findTouchedSince(since, cursor, PageRequest.of(0, PAGE));
            if (page.isEmpty()) {
                break;
            }
            int[] counts = tx.execute(status -> {
                int c = 0, u = 0, d = 0;
                for (User user : page) {
                    if (user.getWithdrawnAt() != null) {
                        outbox.recordUserDeleted(user.getUserId());
                        d++;
                        continue;
                    }
                    if (user.getCreatedAt() != null && !user.getCreatedAt().isBefore(since)) {
                        outbox.recordUserCreated(user);
                        c++;
                    }
                    outbox.recordUserUpdated(user);
                    u++;
                }
                return new int[] { c, u, d };
            });
            created += counts[0];
            updated += counts[1];
            deleted += counts[2];
            after = page.get(page.size() - 1).getUserId();
            if (page.size() < PAGE) {
                break;
            }
        }
        return new Result(created, updated, deleted);
    }
}
