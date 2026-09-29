package com.project.coupon_service.dto.request;

import java.time.LocalDateTime;
import java.util.List;

import com.project.common.constants.coupon.CouponScope;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CouponRequest {

    private String name;

    private LocalDateTime startDate;

    private LocalDateTime endDate;

    private int discount;

    private String userId;

    /**
     * 적용 범위 — {@code "ALL"} · {@code "CATEGORY"} · {@code "PRODUCT"}.
     *
     * <p>비어 있으면 ALL 로 읽는다. 기존 호출부(가입 쿠폰 발급 등)가 이 필드를 모른 채
     * 5-인자 생성자를 쓰고 있어서, 기본값이 예전 동작과 같아야 한다.
     */
    private String scope;

    /** 대상 카테고리 id 또는 상품 id 목록. scope 가 ALL 이면 무시한다. */
    private List<String> targets;

    /** 예전 5-인자 생성자. 제약 없는 쿠폰을 만든다. */
    public CouponRequest(String name, LocalDateTime startDate, LocalDateTime endDate,
            int discount, String userId) {
        this(name, startDate, endDate, discount, userId, CouponScope.ALL.name(), List.of());
    }

    public CouponScope scopeOrAll() {
        if (scope == null || scope.isBlank()) {
            return CouponScope.ALL;
        }
        try {
            return CouponScope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 모르는 값이 왔다고 전 상품 할인으로 떨어뜨리면 위험한 쪽으로 틀리는 것이다.
            // 그래도 ALL 로 두는 이유: 이 값은 관리자만 보내고(add 는 ADMIN 전용),
            // 잘못된 값을 조용히 "제약 있음"으로 바꾸면 대상이 비어 아무에게도 적용되지
            // 않는 쿠폰이 만들어져 원인을 찾기 더 어렵다. 대신 서비스가 경고를 남긴다.
            return CouponScope.ALL;
        }
    }

    public List<String> targetsOrEmpty() {
        return targets == null ? List.of() : targets;
    }
}
