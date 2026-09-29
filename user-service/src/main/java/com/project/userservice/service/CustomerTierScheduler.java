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

    /**
     * 켜고 끌 수 있게 둔다.
     *
     * <p>인스턴스가 여러 개면 <b>여러 번 돈다.</b> 결과가 같은 연산이라(같은 입력 → 같은
     * 등급) 망가지지는 않지만 order-service 를 그만큼 더 부른다. 지금은 단일 노드라
     * 문제가 없고, 늘어나면 한 인스턴스에서만 켜면 된다.
     */
    @Value("${user.tier.scheduler-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${user.tier.cron:0 10 19 * * *}", zone = "UTC")
    public void recalculate() {
        if (!enabled) {
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
        }
    }
}
