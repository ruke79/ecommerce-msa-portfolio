package com.project.coupon_service.message.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.coupon_service.model.Coupon;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 쿠폰 발급 알림 — 브로커가 거절해도 흔적이 남는다.
 *
 * <p>🐛 예전: send 의 결과를 보지 않아 비동기 실패(거절 · 시간 초과)가 catch 에 걸리지 않고 알림만 조용히 사라졌다.
 *
 * <p>[JA] クーポン発行通知 — ブローカーが拒否しても痕跡が残る。以前は非同期の失敗が catch に掛からず通知が静かに消えた。
 */
@DisplayName("쿠폰 발급 알림 발행 — 비동기 실패")
class CouponIssuedProducerTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(CouponIssuedProducer.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("🔴 브로커가 거절하면(비동기) ERROR 로 남는다 — 쿠폰 id 와 예외 종류만")
    void asyncFailureIsLogged() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        CouponIssuedProducer producer = new CouponIssuedProducer(kafka, new ObjectMapper());
        ReflectionTestUtils.setField(producer, "topic", "coupon-issued");

        Coupon coupon = mock(Coupon.class);
        when(coupon.getCouponId()).thenReturn(42L);
        when(coupon.getUserId()).thenReturn(7L);
        producer.publish(coupon);

        String errors = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
        assertThat(errors).contains("쿠폰 발급 발행 실패").contains("couponId=42").contains("IllegalStateException");
    }
}
