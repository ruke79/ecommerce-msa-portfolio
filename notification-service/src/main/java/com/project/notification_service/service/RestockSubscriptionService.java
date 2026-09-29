package com.project.notification_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.domain.RestockSubscription;
import com.project.notification_service.repository.RestockSubscriptionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재입고 알림 신청과 발송 (docs/53).
 *
 * <p>손님이 품절 옵션에 신청해 두면, catalog-service 가 그 옵션의 재고가 0 에서 다시 생겼다고 알릴 때
 * ({@code product-stock}, RESTOCKED) 아직 알리지 않은 신청마다 {@link NotificationType#RESTOCK_AVAILABLE} 알림을
 * 만들고 알렸다고 찍는다. 한 번 알린 신청은 다시 알리지 않는다 — 다음 재입고도 받고 싶으면 다시 신청한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestockSubscriptionService {

    /** 한 손님이 동시에 걸어 둘 수 있는 신청 수 — 무한히 쌓이지 않게. */
    public static final int MAX_ACTIVE_PER_USER = 100;

    /** 재입고 한 번에 한 트랜잭션으로 처리할 신청 수. */
    public static final int FANOUT_PAGE = 500;

    private final RestockSubscriptionRepository repository;
    private final NotificationService notificationService;

    public enum Result { CREATED, ALREADY, LIMIT }

    /** 옵션 하나에 신청한다. 이미 걸려 있으면 그대로, 알린 적 있는 신청이면 새로 건다. */
    @Transactional
    public Result subscribe(Long userId, Long productId, Long colorId, String size, String productName,
            String productSlug) {
        long color = RestockSubscription.colorKey(colorId);
        var existing = repository.findByUserIdAndProductIdAndColorIdAndSize(userId, productId, color, size);
        if (existing.isPresent() && existing.get().isPending()) {
            return Result.ALREADY;
        }
        if (repository.countByUserIdAndNotifiedAtIsNull(userId) >= MAX_ACTIVE_PER_USER) {
            return Result.LIMIT;
        }
        // 알린 적 있는 행은 지우고 새로 — 알림 키가 신청 id 라 새 id 여야 다음 재입고도 알린다.
        existing.ifPresent(old -> {
            repository.delete(old);
            repository.flush();
        });
        repository.save(RestockSubscription.builder()
                .userId(userId).productId(productId).colorId(color).size(size)
                .productName(truncate(productName, 255)).productSlug(truncate(productSlug, 255))
                .build());
        return Result.CREATED;
    }

    /** 신청을 거둔다. 없던 신청이면 false. */
    @Transactional
    public boolean unsubscribe(Long userId, Long productId, Long colorId, String size) {
        return repository.findByUserIdAndProductIdAndColorIdAndSize(userId, productId,
                        RestockSubscription.colorKey(colorId), size)
                .filter(RestockSubscription::isPending)
                .map(s -> {
                    repository.delete(s);
                    return true;
                })
                .orElse(false);
    }

    /** 상품 화면 — 이 손님이 이 상품에 걸어 둔(아직 알리지 않은) 옵션들. */
    @Transactional(readOnly = true)
    public List<RestockSubscription> pendingFor(Long userId, Long productId) {
        return repository.findByUserIdAndProductIdAndNotifiedAtIsNull(userId, productId);
    }

    /**
     * 재입고 — 이 옵션의 아직 알리지 않은 신청마다 알림을 만든다.
     *
     * <p>부르는 쪽(RestockEventListener)이 페이지가 덜 찰 때까지 다시 부른다 — 페이지마다 트랜잭션이 따로라 신청이
     * 많아도 한 트랜잭션이 길어지지 않는다. 각 페이지는 알린 행을
     * 찍고 끝나므로 다음 조회에는 나오지 않는다. 알림 키는 {@code restock:<신청 id>} — 재전달돼도 하나다.
     *
     * @return 이번 페이지에서 알린 수
     */
    @Transactional
    public int notifyPage(Long productId, Long colorId, String size, String name, String slug, String color) {
        List<RestockSubscription> page = repository
                .findByProductIdAndColorIdAndSizeAndNotifiedAtIsNullOrderBySubscriptionId(
                        productId, RestockSubscription.colorKey(colorId), size, PageRequest.of(0, FANOUT_PAGE));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (RestockSubscription s : page) {
            String productName = name != null && !name.isBlank() ? name
                    : s.getProductName() != null ? s.getProductName() : NotificationTexts.RESTOCK_FALLBACK_PRODUCT;
            String productSlug = slug != null && !slug.isBlank() ? slug : s.getProductSlug();
            notificationService.create(
                    s.getUserId(), NotificationType.RESTOCK_AVAILABLE,
                    NotificationTexts.RESTOCK_TITLE,
                    NotificationTexts.restockBody(productName, option(color, size)),
                    productSlug == null ? null : NotificationType.RESTOCK_AVAILABLE.path() + productSlug,
                    "restock:" + s.getSubscriptionId(),
                    Map.of("subject", productName));
            s.markNotified(now);
        }
        return page.size();
    }

    static String option(String color, String size) {
        return color == null || color.isBlank() ? size : color + " / " + size;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
