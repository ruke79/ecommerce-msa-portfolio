package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * "이 옵션이 다시 들어오면 알려 주세요" — 품절 옵션(상품·색상·사이즈)에 대한 재입고 알림 신청 (docs/53).
 *
 * <p>색상 없는 상품은 {@code colorId = 0}({@link #NO_COLOR}) 이다 — null 이면 UNIQUE 가 같은 신청 둘을 막지 못한다.
 * 알린 뒤에는 {@code notifiedAt} 이 찍히고 더는 알리지 않는다. 다시 신청하면 그 행을 지우고 새로 만든다 —
 * 알림 키가 신청 id({@code restock:<id>}) 라 새 id 여야 다음 재입고도 알린다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "restock_subscription", schema = "notification")
public class RestockSubscription {

    public static final long NO_COLOR = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id", nullable = false)
    private Long colorId;

    @Column(nullable = false, length = 64)
    private String size;

    /** 알림 문구용 — 신청할 때 화면이 보낸 이름. 알림을 만들 때는 재입고 이벤트의 이름을 먼저 쓴다. */
    @Column(name = "product_name")
    private String productName;

    @Column(name = "product_slug")
    private String productSlug;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    public void markNotified(LocalDateTime at) {
        this.notifiedAt = at;
    }

    public boolean isPending() {
        return notifiedAt == null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (colorId == null) {
            colorId = NO_COLOR;
        }
    }

    public static long colorKey(Long colorId) {
        return colorId == null ? NO_COLOR : colorId;
    }
}
