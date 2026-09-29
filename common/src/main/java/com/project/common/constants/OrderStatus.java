package com.project.common.constants;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {    
    
    NOT_PROCESSED("NOT_PROCESSED"), 
    PAID("PAID"),
    /**
     * 무통장입금 <b>입금 대기</b>(docs/49 §11). {@link #NOT_PROCESSED}(결제 절차를 시작하지
     * 않음)와 다르다 — 입금 안내를 받았고 기한이 있다. 입금이 확인되면 {@link #PAID},
     * 기한이 지나거나 손님이 접으면 {@link #CANCELED}. 돈이 아직 안 왔으니 매출이 아니다.
     */
    UNPAID("UNPAID"),       
    PROCESSING("PROCESSING"),
    DISPATCHED("DISPATCHED"),
    DELIVERED("DELIVERED"),
    CANCELED("CANCELED"),
    COMPLETED("COMPLETED"),
    /**
     * 결제된 뒤 돈을 돌려준 주문. {@link #CANCELED} 와 다르다 — 그쪽은 결제 <b>전</b>에
     * 접은 주문이다. 매출에서 빠진다({@link #PAID_STATUSES} 에 없다).
     */
    REFUNDED("REFUNDED");
    

    private static class Holder {
        static Map<String, OrderStatus> MAP = new HashMap<>();
    }

    /**
     * <b>돈이 실제로 들어온</b> 주문 상태.
     *
     * <p>매출 집계·구매 이력·등급 산정은 전부 이 집합을 기준으로 한다. 기준이 여러 곳에
     * 흩어지면 "주문 내역 화면에서는 결제된 주문인데 등급 계산에는 안 잡힌다" 같은,
     * 사용자가 설명을 들어도 납득하지 못하는 불일치가 생긴다.
     *
     * <p><b>결제된 쪽을 나열한다 — 결제 안 된 쪽을 빼는 것이 아니다.</b> 나중에 상태가
     * 하나 늘었을 때, 빼는 방식이면 새 상태가 <b>조용히 매출로 잡히고</b> 나열하는 방식이면
     * 조용히 매출에서 빠진다. 돈을 부풀리는 쪽이 훨씬 나쁜 실패다. 프론트엔드의
     * {@code util/orderStatus.js} 도 같은 이유로 같은 방향을 택했다.
     *
     * <p>{@link #CANCELED} 는 당연히 빠지고, {@link #NOT_PROCESSED}·{@link #UNPAID} 는
     * 아직 결제 전이라 빠진다. {@link #REFUNDED} 는 돈이 <b>돌아간</b> 주문이라 빠진다.
     */
    public static final Set<OrderStatus> PAID_STATUSES =
            Set.copyOf(EnumSet.of(PAID, PROCESSING, DISPATCHED, DELIVERED, COMPLETED));

    private OrderStatus(String status) {
        Holder.MAP.put(status, this);
    }

    public static OrderStatus getStatus(String val) {
        OrderStatus status = Holder.MAP.get(val);
        if(status == null) {
            
            throw new  IllegalStateException(String.format("Unsupported status %s.", val)); 
        }

        return status;        
    }
}
