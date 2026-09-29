package com.project.order_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.order.PurchaseStatDto;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * 등급 산정을 위한 구매 집계.
 *
 * <p><b>왜 order-service 가 계산하는가.</b> 등급의 <b>기준</b>(몇 개월, 얼마)은 user-service
 * 의 정책이지만, 그 기준에 넣을 <b>숫자</b>는 주문 데이터에서만 나온다. 주문을 통째로
 * 넘기고 저쪽에서 더하게 하면 배송지·연락처까지 서비스 경계를 넘게 된다. 합계만 넘긴다.
 *
 * <p>"결제된 주문" 의 정의는 {@link OrderStatus#PAID_STATUSES} 하나뿐이다. 여기서 따로
 * 상태를 나열하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PurchaseStatsService {

    /**
     * 한 번에 내보내는 최대 고객 수.
     *
     * <p>배치가 커서로 여러 번 부르므로 이 값이 결과를 자르지는 않는다. 응답 한 개의
     * 크기를 묶어 두기 위한 상한이다 — 고객이 백만이어도 응답 하나는 500 행이다.
     */
    static final int MAX_BATCH = 500;

    /** 기본 산정 기간. user-service 가 값을 주지 않았을 때만 쓰인다. */
    static final int DEFAULT_MONTHS = 3;

    private final OrderRepository orderRepository;

    /**
     * {@code afterCustomerId} 다음 고객부터 최대 {@code size} 명의 구매 집계.
     *
     * <p>시각은 <b>UTC 로 계산한다</b>(B-21). 저장된 {@code createdAt} 도 UTC 다. 여기서
     * 시스템 기본 시간대를 쓰면 로컬 개발 장비에서만 창이 9시간 밀리는데, 3개월 창에서
     * 그 정도 오차는 눈에 띄지 않아 <b>틀린 채로 오래 간다</b>.
     */
    @Transactional(readOnly = true)
    public List<PurchaseStatDto> recentStats(Integer months, Long afterCustomerId, Integer size) {
        int window = (months == null || months <= 0) ? DEFAULT_MONTHS : months;
        int limit = (size == null || size <= 0) ? MAX_BATCH : Math.min(size, MAX_BATCH);
        long cursor = afterCustomerId == null ? Long.MIN_VALUE : afterCustomerId;

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMonths(window);

        return orderRepository.aggregatePurchases(OrderStatus.PAID_STATUSES, since, cursor,
                PageRequest.of(0, limit));
    }
}
