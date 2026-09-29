package com.project.notification_service.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxTraceContext;
import com.project.notification_service.service.RestockSubscriptionService;

/**
 * catalog 의 product-stock — 재입고면 신청자에게, 품절이면 아무것도 (docs/53).
 */
@DisplayName("RestockEventListener — product-stock")
class RestockEventListenerTest {

    private final RestockSubscriptionService service = mock(RestockSubscriptionService.class);
    private final RestockEventListener listener =
            new RestockEventListener(service, new ObjectMapper(), OutboxTraceContext.NOOP);

    private static String event(String type) {
        return """
            {"type":"%s","productId":10,"slug":"tee","name":"티셔츠","colorId":3,"color":"black","size":"M","quantity":4}
            """.formatted(type);
    }

    @Test
    @DisplayName("🔴 재입고 — 그 옵션의 신청자에게 알린다")
    void restockedNotifiesSubscribers() {
        listener.onProductStock(List.of(event("RESTOCKED")), null);

        verify(service).notifyPage(10L, 3L, "M", "티셔츠", "tee", "black");
    }

    @Test
    @DisplayName("신청이 한 페이지를 넘으면 덜 찬 페이지가 나올 때까지 다시 부른다")
    void pagesUntilDone() {
        when(service.notifyPage(eq(10L), eq(3L), eq("M"), any(), any(), any()))
                .thenReturn(RestockSubscriptionService.FANOUT_PAGE, RestockSubscriptionService.FANOUT_PAGE, 7);

        listener.onProductStock(List.of(event("RESTOCKED")), null);

        verify(service, times(3)).notifyPage(eq(10L), eq(3L), eq("M"), any(), any(), any());
    }

    @Test
    @DisplayName("품절·모르는 형식은 아무것도 하지 않는다 — 품절은 운영자 경보가 맡는다")
    void soldOutIsNotForCustomers() {
        listener.onProductStock(List.of(event("SOLD_OUT"), "{\"type\":\"RESTOCKED\",\"productId\":10}"), null);

        verify(service, never()).notifyPage(anyLong(), any(), any(), any(), any(), any());
    }
}
