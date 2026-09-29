package com.project.catalog_service.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제되지 않은 채 만료된 재고 예약을 푼다 (docs/59).
 *
 * <p>계산 자체는 만료 시각을 보므로(HELD 여도 expires_at 이 지나면 남은 수량에 세지 않는다) 이 잡이 늦어도 재고는 바로
 * 돌아온다. 잡이 하는 일은 행의 상태를 사실대로 맞추는 것 — 운영자가 "지금 잡혀 있는 것" 을 볼 때 만료된 것이 섞이지 않게.
 * 조건부 갱신이라 파드가 여럿이어도 한 번씩만 바뀐다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationExpiry {

    private final StockReservationService reservations;

    @Scheduled(fixedDelayString = "${catalog.stock-reservation.expiry-check-ms:60000}", initialDelay = 30_000L)
    public void run() {
        try {
            reservations.releaseExpired();
        } catch (RuntimeException e) {
            log.warn("재고 예약 만료 처리 실패 — 다음 주기에 다시 한다 ({})", e.toString());
        }
    }
}
