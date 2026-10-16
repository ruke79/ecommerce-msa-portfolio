package com.project.userservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 등급 배치를 언제 돌릴 것인가.
 *
 * <p><b>왜 별도 클래스인가.</b> {@code @Scheduled} 메서드가 배치 본체와 같은 빈에 있으면
 * 테스트에서 배치를 부르려는 순간 스케줄러까지 딸려 온다. 더 중요한 이유는 catalog-service
 * 의 {@code EventScheduler} 에서 겪은 것과 같다 — 스케줄러와 트랜잭션 경계를 한 빈에
 * 두면 자기 호출(self-invocation)로 프록시가 건너뛰어진다. 시각을 정하는 쪽과 일을 하는
 * 쪽을 분리해 두면 그 실수를 할 자리가 없다.
 *
 * <p><b>왜 새벽인가.</b> 등급 배치는 전 고객을 훑는 무거운 조회다. 주문이 가장 한산한
 * 시간에 돌린다. UTC 19:10 = 한국 시간 새벽 4시 10분.
 *
 * <p><b>정각을 피한 이유.</b> 다른 배치(탈퇴 처리 등)와 겹치면 DB 부하가 한 지점에
 * 몰린다. 10분씩 밀어 둔다.
 *
 * <p>{@code @EnableScheduling} 은 {@code message.config.SchedulerConfig} 에 이미 있다.
 * 없으면 {@code @Scheduled} 는 <b>아무 오류 없이 그냥 실행되지 않는다</b> — 새 배치를
 * 추가하는 서비스에서 가장 먼저 확인할 것이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerTierScheduler {

    private final CustomerTierService customerTierService;
    private final SchedulerLease lease;

    /** 임대 이름(V17 users.scheduler_lock). */
    static final String LEASE = "customer-tier";

    /**
     * 임대 길이 — 한 회차가 이보다 오래 걸리면 다른 파드가 이어 돌 수 있다. 회차는 상한(MAX_CUSTOMERS_PER_RUN)이 있어 수 분이다.
     * 파드가 도중에 죽어도 이 시간이 지나면 다음 회차(다음 날)가 막히지 않는다.
     */
    static final java.time.Duration LEASE_FOR = java.time.Duration.ofHours(2);

    /**
     * 켜고 끌 수 있게 둔다.
     *
     * <p>🐛 예전 주석은 "인스턴스가 여러 개면 여러 번 돈다 — 결과가 같아 망가지지 않는다, 지금은 단일 노드라 괜찮다" 였다. 둘 다
     * 틀렸다 — 노드가 아니라 <b>파드</b> 수가 문제이고(replicas 2, HPA min 2), 결과도 같지 않았다: 각 회차가 자기 시작 시각으로
     * {@code tierComputedAt} 을 찍고 그보다 오래된 행을 강등하므로, 늦게 시작한 회차가 먼저 끝나면 앞 회차가 덮어쓴 VIP 를 BASIC 으로
     * 내렸다. 이제 DB 임대(SchedulerLease)로 한 회차는 한 파드만 돌고, 옛 회차는 새 회차의 값을 덮지 못한다(UserRepository.updateTier).
     */
    // [JA] 以前は「複数インスタンスなら複数回走るが結果は同じ」とあったが誤り — Pod 数が問題で、遅く始めた回が先に終わると
    //      前の回が上書きした VIP を BASIC に下げた。今は DB リースで一つの Pod だけが走り、古い回は新しい回の値を上書きできない。
    @Value("${user.tier.scheduler-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${user.tier.cron:0 10 19 * * *}", zone = "UTC")
    public void recalculate() {
        if (!enabled) {
            return;
        }
        try {
            if (!lease.tryAcquire(LEASE, LEASE_FOR)) {
                log.info("등급 배치 — 다른 파드가 이 회차를 돌고 있다. 건너뛴다.");
                return;
            }
        } catch (Exception e) {
            log.error("등급 배치 임대를 잡지 못했다 — 이번 회차는 건너뛴다: {}", e.toString());
            return;
        }
        try {
            CustomerTierService.Result result = customerTierService.recalculateAll();
            if (!result.completed()) {
                log.warn("등급 배치가 끝까지 돌지 못했다 — 강등은 건너뛰었다. 갱신={}", result.updated());
            }
        } catch (Exception e) {
            // 배치 하나가 던진 예외로 스케줄러 스레드가 죽으면 이후 회차가 전부 사라진다.
            log.error("등급 배치 실패: {}", e.toString());
        } finally {
            try {
                lease.release(LEASE);
            } catch (Exception e) {
                log.warn("등급 배치 임대를 풀지 못했다 — {} 뒤에 저절로 풀린다: {}", LEASE_FOR, e.toString());
            }
        }
    }
}
