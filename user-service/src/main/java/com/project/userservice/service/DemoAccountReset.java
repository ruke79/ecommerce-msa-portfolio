package com.project.userservice.service;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import com.project.userservice.client.NotificationServiceClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 계정을 매일 처음 상태로(docs/100 — 기본 19:00 UTC = 04:00 KST). 클러스터에서 한 파드만(SchedulerLease).
 *
 * <p>user-service 것: 찜 · 배송지 · 기기 기록(한 트랜잭션). 알림은 notification 의 내부 경로를 부른다(그 서비스는 누가 데모인지 모른다).
 * 장바구니는 cart 가 5분 뒤 스스로 비운다(목록은 {@code /api/auth/user/demo-accounts}). 주문은 지우지 않는다 — 결제 · 아웃박스 · 사가가
 * 얽혀 있다. 알림 호출이 실패해도 user 쪽 초기화는 그대로 — 다음 날 다시.
 *
 * <p>[JA] 公開デモアカウントを毎日初期状態に(既定 19:00 UTC)。クラスターで一つの Pod だけ。user-service の分: お気に入り · 配送先 ·
 *      端末記録(一トランザクション)。通知は notification の内部経路を呼ぶ。カートは cart が 5 分後に自分で空にする。注文は消さない。
 */
@Slf4j
@Component
public class DemoAccountReset {

    static final String LEASE = "demo-account-reset";

    /** 지우는 표 — 모두 user_id 로 사용자에 묶인다. [JA] 消す表 — すべて user_id で利用者に結び付く。 */
    static final List<String> TABLES = List.of("users.wishlist", "users.shipping_address", "users.known_device");

    private final DemoAccountService demo;
    private final SchedulerLease lease;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final NotificationServiceClient notifications;

    @Autowired
    public DemoAccountReset(DemoAccountService demo, SchedulerLease lease, NamedParameterJdbcTemplate jdbc,
            TransactionOperations tx, NotificationServiceClient notifications) {
        this.demo = demo;
        this.lease = lease;
        this.jdbc = jdbc;
        this.tx = tx;
        this.notifications = notifications;
    }

    /** @return 지운 줄 수(건너뛰면 -1) [JA] 消した行数(スキップなら -1) */
    @Scheduled(cron = "${demo.accounts.reset.cron:0 0 19 * * *}", zone = "UTC")
    public int run() {
        if (!demo.enabled() || !lease.tryAcquire(LEASE, Duration.ofMinutes(30))) {
            return -1;
        }
        try {
            List<Long> ids = demo.allDemoAccountIds();
            if (ids.isEmpty()) {
                return 0;
            }
            int rows = tx.execute(s -> {
                int n = 0;
                for (String table : TABLES) {
                    n += jdbc.update("DELETE FROM " + table + " WHERE user_id IN (:ids)", java.util.Map.of("ids", ids));
                }
                return n;
            });
            String notified;
            try {
                notified = String.valueOf(notifications.demoReset(new NotificationServiceClient.DemoResetRequest(ids)));
            } catch (RuntimeException e) {
                notified = "실패(" + e.getClass().getSimpleName() + ")";
            }
            log.info("데모 계정 초기화: 계정={} 지운 줄={} 알림={}", ids.size(), rows, notified);
            return rows;
        } finally {
            lease.release(LEASE);
        }
    }
}
