package com.project.notification_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.project.notification_service.domain.RestockSubscription;

public interface RestockSubscriptionRepository extends JpaRepository<RestockSubscription, Long> {

    Optional<RestockSubscription> findByUserIdAndProductIdAndColorIdAndSize(Long userId, Long productId, Long colorId,
            String size);

    /** 상품 화면 — 이 손님이 이 상품에 걸어 둔, 아직 알리지 않은 신청. */
    List<RestockSubscription> findByUserIdAndProductIdAndNotifiedAtIsNull(Long userId, Long productId);

    /** 재입고가 왔다 — 이 옵션의 아직 알리지 않은 신청(부분 인덱스 idx_restock_pending). */
    List<RestockSubscription> findByProductIdAndColorIdAndSizeAndNotifiedAtIsNullOrderBySubscriptionId(
            Long productId, Long colorId, String size, Pageable page);

    long countByUserIdAndNotifiedAtIsNull(Long userId);
}
