package com.project.order_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.project.common.constants.OrderStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 공개 데모 계정의 결제 전 주문 상한(docs/100 §6) — 게이트웨이가 데모 토큰에만 싣는 {@code User-Demo: true} 로 가린다(스키마 변경 없음).
 *
 * <p>여러 방문자가 돌려 쓰는 계정이라 결제하지 않은 주문(결제 시작 전 · 입금 대기)이 쌓이고 재고를 잡는다. 그 수가 상한
 * ({@code demo.orders.max-pending}, 기본 3) 이상이면 새 주문을 거절한다 — 같은 멱등 키의 재요청은 이미 만든 주문을 돌려줄 것이므로 막지 않는다.
 *
 * <p>[JA] 公開デモアカウントの決済前注文の上限 — ゲートウェイがデモトークンにだけ載せる User-Demo: true で見分ける。決済していない注文が
 *      上限以上なら新しい注文を拒否する。同じ冪等キーの再要求は作成済みの注文を返すので止めない。
 */
@Component
public class DemoOrderLimit {

    /** 결제 전으로 세는 상태. [JA] 決済前として数える状態。 */
    static final OrderStatus[] PENDING = { OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID };

    private final OrderRepository orders;
    private final OrderCacheService cache;
    private final int maxPending;

    @Autowired
    public DemoOrderLimit(OrderRepository orders, OrderCacheService cache,
            @Value("${demo.orders.max-pending:3}") int maxPending) {
        this.orders = orders;
        this.cache = cache;
        this.maxPending = maxPending;
    }

    /** @param demoHeader 게이트웨이의 {@code User-Demo} — "true" 일 때만 센다 [JA] "true" のときだけ数える */
    public void check(String demoHeader, Long customerId, String createKey) {
        if (!"true".equals(demoHeader) || customerId == null) {
            return;
        }
        String key = OrderService.usableCreateKey(createKey);
        if (key != null && cache.findByCreateKey(customerId, key) != null) {
            return;
        }
        long pending = 0;
        for (OrderStatus s : PENDING) {
            pending += orders.countByCustomerIdAndOrderStatus(customerId, s);
        }
        if (pending >= maxPending) {
            throw new OrderDomainException("데모 계정은 결제하지 않은 주문을 " + maxPending
                    + "건까지 둘 수 있습니다. 주문 목록에서 취소하거나 결제한 뒤 다시 시도해 주세요.");
        }
    }
}
