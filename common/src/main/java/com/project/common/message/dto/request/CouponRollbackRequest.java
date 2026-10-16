package com.project.common.message.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import java.io.Serializable;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponRollbackRequest implements Serializable {

    private Long userId;
    private String couponName;

    /**
     * 보상하는 주문 — coupon 은 쿠폰을 쓴 주문(used_order_id)과 같을 때만 되돌린다. 🐛 예전에는 없어서 이름 · 사용자로 찾은
     * USED 쿠폰을 무조건 되돌렸다 — 결제가 실패한 다른 주문의 보상이 이미 결제된 주문의 쿠폰을 되살려 같은 쿠폰을 또 쓸 수 있었다.
     * 옛 프로듀서(v1)의 메시지는 null — 그때는 예전처럼 되돌린다.
     */
    // [JA] 補償する注文 — クーポンを使った注文と同じときだけ戻す。以前は別注文の補償が決済済み注文のクーポンを戻していた。
    private Long orderId;

    public CouponRollbackRequest(Long userId, String couponName) {
        this(userId, couponName, null);
    }

}
