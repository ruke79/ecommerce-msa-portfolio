package com.project.delivery_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.project.delivery_service.carrier.CarrierAdapter;
import com.project.delivery_service.carrier.CarrierRegistry;
import com.project.delivery_service.carrier.TrackingResult;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.DeliveryEventRepository;
import com.project.delivery_service.repository.ShipmentRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 택배사의 영구 실패(인증 · 응답 형식)는 WARN — 한 송장의 문제라 ERROR 경보(ErrorLogSurge)를 울릴 일이 아니다(docs/99 §4: 운영에서 약
 * 4시간마다 ERROR 한 줄). 실패는 그대로 백오프에 기록한다.
 * [JA] 運送会社の恒久失敗は WARN — 一つの送り状の問題で ERROR 警報を鳴らすことではない。失敗はバックオフに記録する。
 */
@DisplayName("추적 폴러 — 영구 실패는 WARN")
class TrackingPollerPermanentLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(TrackingPoller.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private TrackingPoller poller;
    private Shipment shipment;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);

        ShipmentRepository shipments = mock(ShipmentRepository.class);
        DeliveryEventRepository events = mock(DeliveryEventRepository.class);
        CarrierAdapter post = mock(CarrierAdapter.class);
        when(post.track(any(), anyBoolean())).thenReturn(TrackingResult.permanent("HTTP 400"));
        when(post.pollIntervalCap()).thenReturn(Optional.empty());
        CarrierRegistry carriers = mock(CarrierRegistry.class);
        when(carriers.find("KOREA_POST")).thenReturn(Optional.of(post));

        Delivery d = Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        shipment = d.addShipment(false);
        shipment.assignTracking("KOREA_POST", "6012345678901", true);
        ReflectionTestUtils.setField(shipment, "shipmentId", 101L);
        when(shipments.claimDueForPolling(any(), any())).thenReturn(List.of(shipment));
        when(shipments.findById(101L)).thenReturn(Optional.of(shipment));

        poller = new TrackingPoller(shipments, events, carriers, mock(DeliveryStatusPublisher.class));
        ReflectionTestUtils.setField(poller, "batchSize", 20);
        poller.setTransactionManager(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("🔴 영구 실패 한 줄은 WARN(ERROR 아님) · 실패는 백오프에 기록")
    void permanentFailureIsWarn() {
        int before = shipment.getPollFailures();
        poller.poll();

        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("추적 영구 실패") && e.getLevel() == Level.WARN);
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(shipment.getPollFailures()).isEqualTo(before + 1);
    }
}
