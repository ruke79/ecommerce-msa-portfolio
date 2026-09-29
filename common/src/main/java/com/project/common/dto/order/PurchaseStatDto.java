package com.project.common.dto.order;

/**
 * 한 고객의 구매 집계.
 *
 * <p>order-service 가 등급 산정용으로 내보내는 <b>유일한</b> 형태다. 주문 한 건 한 건을
 * 넘기지 않는 이유는 단순하다 — 등급을 매기는 데 필요한 것은 합계이지 명세가 아니고,
 * 주문 내역은 배송지·연락처·상품까지 딸려 오는 <b>민감한 데이터</b>다. 필요 없는 것을
 * 서비스 경계 밖으로 내보내지 않는다.
 *
 * <p>금액은 {@code long} 이다 — 상점 통화의 <b>정수 단위</b>(USD 면 달러). 주문의
 * {@code total} 은 V7 부터 소수 둘째 자리까지 있지만 등급 구간은 달러 단위라 합계를 정수로
 * 자른다(쿼리의 {@code cast(... as Long)}). 수천 건을 더하면 {@code int} 범위를 넘길 수
 * 있어 {@code long} 이다.
 *
 * @param customerId        고객 ID(= user-service 의 userId)
 * @param lifetimeOrderCount 가입 이후 결제된 주문 수 — 기간 제한 없음
 * @param recentOrderCount  최근 기간 안에 결제된 주문 수
 * @param recentAmount      최근 기간 안에 결제된 금액 합계
 */
public record PurchaseStatDto(
        Long customerId,
        long lifetimeOrderCount,
        long recentOrderCount,
        long recentAmount) {
}
