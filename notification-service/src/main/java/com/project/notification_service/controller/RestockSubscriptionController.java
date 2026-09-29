package com.project.notification_service.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.notification_service.domain.RestockSubscription;
import com.project.notification_service.service.RestockSubscriptionService;

import lombok.RequiredArgsConstructor;

/**
 * 재입고 알림 신청 (docs/53). 신원은 게이트웨이가 토큰에서 뽑아 붙인 {@code User-Id} 다 — 본문의 사용자 값은
 * 받지 않는다.
 *
 * <ul>
 *   <li>{@code GET ?productId=} — 이 상품에 걸어 둔 옵션들 {@code [{colorId, size}]} (상품 화면의 버튼 상태)</li>
 *   <li>{@code POST {productId, colorId, size, productName, productSlug}} — 신청(이미 있으면 그대로 200)</li>
 *   <li>{@code DELETE ?productId=&colorId=&size=} — 거두기(없으면 404)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications/restock-subscriptions")
@RequiredArgsConstructor
public class RestockSubscriptionController {

    static final int MAX_SIZE_LENGTH = 64;

    private final RestockSubscriptionService restockService;

    @GetMapping
    public ResponseEntity<?> mine(@RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestParam("productId") Long productId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> options = restockService.pendingFor(userId, productId).stream()
                .map(s -> {
                    Map<String, Object> option = new java.util.LinkedHashMap<>();
                    option.put("colorId", colorOut(s.getColorId()));
                    option.put("size", s.getSize());
                    return option;
                })
                .toList();
        return ResponseEntity.ok(options);
    }

    @PostMapping
    public ResponseEntity<?> subscribe(@RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestBody Map<String, Object> body) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long productId = asLong(body.get("productId"));
        String size = asText(body.get("size"));
        if (productId == null || size == null || size.isBlank() || size.length() > MAX_SIZE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message", "productId 와 size 가 필요합니다."));
        }
        RestockSubscriptionService.Result result = restockService.subscribe(userId, productId,
                asLong(body.get("colorId")), size, asText(body.get("productName")), asText(body.get("productSlug")));
        return switch (result) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("subscribed", true));
            case ALREADY -> ResponseEntity.ok(Map.of("subscribed", true));
            case LIMIT -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message",
                    "재입고 알림은 " + RestockSubscriptionService.MAX_ACTIVE_PER_USER + "개까지 신청할 수 있습니다."));
        };
    }

    @DeleteMapping
    public ResponseEntity<Void> unsubscribe(@RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestParam("productId") Long productId,
            @RequestParam(value = "colorId", required = false) Long colorId,
            @RequestParam("size") String size) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return restockService.unsubscribe(userId, productId, colorId, size)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 저장은 색상 없음을 0 으로 한다 — 화면에는 null 로 돌려준다. */
    private static Long colorOut(Long colorId) {
        return colorId == null || colorId == RestockSubscription.NO_COLOR ? null : colorId;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String asText(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
