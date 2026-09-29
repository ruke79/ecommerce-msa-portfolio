package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.domain.RestockSubscription;
import com.project.notification_service.repository.RestockSubscriptionRepository;

/**
 * 재입고 알림 신청과 발송 (docs/53).
 */
@DisplayName("RestockSubscriptionService — 재입고 알림")
class RestockSubscriptionServiceTest {

    private final RestockSubscriptionRepository repository = mock(RestockSubscriptionRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RestockSubscriptionService service = new RestockSubscriptionService(repository, notifications);

    private static RestockSubscription sub(long id, long userId, LocalDateTime notifiedAt) {
        return RestockSubscription.builder().subscriptionId(id).userId(userId).productId(10L).colorId(3L).size("M")
                .productName("옛 이름").productSlug("tee").notifiedAt(notifiedAt).build();
    }

    @Test
    @DisplayName("🔴 새 신청은 저장한다 — 색상 없는 상품은 색상 0 으로")
    void subscribesNew() {
        when(repository.findByUserIdAndProductIdAndColorIdAndSize(7L, 10L, 0L, "M")).thenReturn(Optional.empty());

        assertThat(service.subscribe(7L, 10L, null, "M", "티셔츠", "tee"))
                .isEqualTo(RestockSubscriptionService.Result.CREATED);

        ArgumentCaptor<RestockSubscription> saved = ArgumentCaptor.forClass(RestockSubscription.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getColorId()).isZero();
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("이미 걸려 있으면 그대로 — 두 번 눌러도 신청은 하나다")
    void alreadySubscribed() {
        when(repository.findByUserIdAndProductIdAndColorIdAndSize(7L, 10L, 3L, "M"))
                .thenReturn(Optional.of(sub(1, 7, null)));

        assertThat(service.subscribe(7L, 10L, 3L, "M", "티셔츠", "tee"))
                .isEqualTo(RestockSubscriptionService.Result.ALREADY);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("알린 적 있는 신청을 다시 걸면 지우고 새로 — 새 id 라야 다음 재입고도 알린다")
    void resubscribeAfterNotified() {
        RestockSubscription old = sub(1, 7, LocalDateTime.now());
        when(repository.findByUserIdAndProductIdAndColorIdAndSize(7L, 10L, 3L, "M")).thenReturn(Optional.of(old));

        assertThat(service.subscribe(7L, 10L, 3L, "M", "티셔츠", "tee"))
                .isEqualTo(RestockSubscriptionService.Result.CREATED);
        InOrder order = inOrder(repository);
        order.verify(repository).delete(old);
        order.verify(repository).save(any());
    }

    @Test
    @DisplayName("걸어 둔 신청이 한도면 거절한다")
    void limit() {
        when(repository.findByUserIdAndProductIdAndColorIdAndSize(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.countByUserIdAndNotifiedAtIsNull(7L)).thenReturn((long) RestockSubscriptionService.MAX_ACTIVE_PER_USER);

        assertThat(service.subscribe(7L, 10L, 3L, "M", null, null)).isEqualTo(RestockSubscriptionService.Result.LIMIT);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 재입고 — 신청마다 RESTOCK_AVAILABLE 알림(신청 id 키, 상품 링크)을 만들고 알렸다고 찍는다")
    void notifiesEachPendingSubscription() {
        RestockSubscription a = sub(1, 7, null);
        RestockSubscription b = sub(2, 8, null);
        when(repository.findByProductIdAndColorIdAndSizeAndNotifiedAtIsNullOrderBySubscriptionId(eq(10L), eq(3L), eq("M"), any()))
                .thenReturn(List.of(a, b));

        assertThat(service.notifyPage(10L, 3L, "M", "티셔츠", "tee", "black")).isEqualTo(2);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).create(eq(7L), eq(NotificationType.RESTOCK_AVAILABLE), eq("재입고 알림"), body.capture(),
                eq("product/tee"), eq("restock:1"), any());
        verify(notifications).create(eq(8L), eq(NotificationType.RESTOCK_AVAILABLE), anyString(), anyString(),
                eq("product/tee"), eq("restock:2"), any());
        assertThat(body.getValue()).contains("티셔츠").contains("black / M");
        assertThat(a.isPending()).isFalse();
        assertThat(b.isPending()).isFalse();
    }

    @Test
    @DisplayName("이벤트에 이름이 없으면 신청 때 저장한 이름으로, 색상이 없으면 사이즈만")
    void fallbacks() {
        when(repository.findByProductIdAndColorIdAndSizeAndNotifiedAtIsNullOrderBySubscriptionId(eq(10L), eq(0L), eq("M"), any()))
                .thenReturn(List.of(sub(1, 7, null)));

        service.notifyPage(10L, null, "M", null, null, null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).create(eq(7L), any(), anyString(), body.capture(), eq("product/tee"), anyString(), any());
        assertThat(body.getValue()).startsWith("옛 이름 (M)");
    }

    @Test
    @DisplayName("알린 신청은 거둘 수 없다 — 없던 것과 같다")
    void unsubscribe() {
        when(repository.findByUserIdAndProductIdAndColorIdAndSize(7L, 10L, 3L, "M")).thenReturn(Optional.of(sub(1, 7, null)));
        when(repository.findByUserIdAndProductIdAndColorIdAndSize(7L, 10L, 3L, "L"))
                .thenReturn(Optional.of(sub(2, 7, LocalDateTime.now())));

        assertThat(service.unsubscribe(7L, 10L, 3L, "M")).isTrue();
        assertThat(service.unsubscribe(7L, 10L, 3L, "L")).isFalse();
        assertThat(service.unsubscribe(7L, 10L, 3L, "XL")).isFalse();
    }
}
