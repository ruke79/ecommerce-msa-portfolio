package com.project.cart_service.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.common.idempotency.ProcessedEventPurger;

import lombok.extern.slf4j.Slf4j;

/**
 * 멱등 원장({@code cart.processed_event}) 정리 — 매일 보존 기간(기본 30일, 22일 아래로는 안 내려간다)이 지난 행을 지운다.
 *
 * <p>주문마다 cart-empty 한 줄이 남는데(CartService, {@code cart-empty-<orderId>}, V6) 지우는 곳이 없어 행이 영구히 쌓였다. 규칙 · 안전장치는 {@link ProcessedEventPurger}(docs/12 §2 "보존").
 *
 * <p>[JA] 冪等台帳({@code cart.processed_event})の掃除 — 毎日、保存期間(既定 30 日、22 日未満にはならない)を過ぎた行を削除する。
 */
@Slf4j
@Component
public class ProcessedEventCleanup {

    private final boolean enabled;
    private final ProcessedEventPurger purger;

    @Autowired
    public ProcessedEventCleanup(DataSource dataSource,
            @Value("${cart.processed-event.cleanup.enabled:true}") boolean enabled,
            @Value("${cart.processed-event.retention:P30D}") Duration retention,
            @Value("${cart.processed-event.cleanup.batch-size:5000}") int batchSize,
            @Value("${cart.processed-event.cleanup.max-batches:200}") int maxBatches) {
        this(enabled, new ProcessedEventPurger(dataSource, "cart.processed_event", retention, batchSize, maxBatches,
                Clock.systemDefaultZone()));
    }

    ProcessedEventCleanup(boolean enabled, ProcessedEventPurger purger) {
        this.enabled = enabled;
        this.purger = purger;
    }

    @Scheduled(cron = "${cart.processed-event.cleanup.cron:0 50 4 * * *}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            purger.purge();
        } catch (SQLException | RuntimeException e) {
            // 다음 날 다시 돈다 — 원장이 하루 더 남는 것뿐이다. [JA] 翌日また回る — 台帳が一日長く残るだけ。
            log.warn("cart.processed_event 정리 실패 — 다음 회차에 다시 한다: {}", e.toString());
        }
    }
}
