package com.project.order_service.repository.jpa;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.order.PurchaseStatDto;
import com.project.order_service.model.Order;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    
    // 비관적 잠금: 주문을 조회할 때 다른 트랜잭션이 해당 주문을 변경하지 못하도록 락을 겁니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findByOrderIdAndOrderStatus(Long orderId, OrderStatus status);

    // 낙관적 잠금: 주문 엔티티의 @Version 필드를 활용하여 동시성 문제를 해결합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<Order> findByTrackingId(String trackingId);

    // --- 관리자 목록 (고객 무관) — 환불 화면. 최신 주문이 위로 온다. ---
    Page<Order> findAllByOrderByOrderIdDesc(Pageable pageable);

    Page<Order> findByOrderStatusOrderByOrderIdDesc(OrderStatus status, Pageable pageable);

    /** 무통장입금 만료 잡 — 기한이 지난 입금 대기 주문. */
    List<Order> findByOrderStatusAndDepositDeadlineBefore(OrderStatus status, LocalDateTime deadline);

    /** 자동 구매 확정 잡 — 배달 완료 뒤 기한이 지난 주문(docs/25 §12). 한 번에 페이지 하나만. */
    List<Order> findByOrderStatusAndDeliveredCreatedAtBefore(OrderStatus status, LocalDateTime cutoff,
            Pageable pageable);

    /** 결제 응답을 기다리는 주문 — 카드는 NOT_PROCESSED, 무통장입금은 UNPAID. */
    Optional<Order> findByOrderIdAndOrderStatusIn(Long orderId, Collection<OrderStatus> statuses);

    /** 반품 대기 — 발송된 뒤 환불돼 물건이 돌아와야 하는 주문. */
    Page<Order> findByAwaitingReturnTrueOrderByOrderIdDesc(Pageable pageable);

    /**
     * 마지막 재고 복원 시각을 찍는다.
     *
     * <p>엔티티를 다시 읽어 저장하지 않고 한 문장으로 하는 이유: 호출자({@code OrderSaga})는
     * 트랜잭션 밖에서 분리된 엔티티를 들고 있고, 그것을 {@code save} 하면 버전 충돌이나
     * 다른 필드의 덮어쓰기가 생긴다. "한 번만" 은 이 시각이 아니라 품목의
     * {@code restockedQty}({@code OrderProductRepository#addRestocked}) 가 지킨다 — 부분
     * 반품(V9)부터 한 주문에 복원이 여럿이다.
     */
    @Modifying
    @Transactional
    @Query("update Order o set o.stockRestoredAt = :at where o.orderId = :orderId")
    int markStockRestored(@Param("orderId") Long orderId, @Param("at") LocalDateTime at);

    Optional<List<Order>> findByCustomerId(Long customerId);

     // --- 페이지 기반 조회 메서드 (Pageable) ---
    /**
     * 고객 ID로 주문 목록을 페이지 단위로 조회합니다.
     * @param customerId 조회할 고객의 ID
     * @param pageable 페이지 정보 (PageRequest 객체)
     * @return Order 엔티티의 페이지 객체
     */
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    // --- 커서 기반 조회 메서드 (Cursor-based Pagination) ---
    /**
     * 특정 고객의 주문 목록을 커서를 기준으로 페이지 단위로 조회합니다.
     * 커서는 마지막으로 조회한 주문 ID를 의미하며, 이 ID보다 작은 주문을 가져옵니다.
     * @param customerId 조회할 고객의 ID
     * @param cursorId 마지막으로 조회한 주문 ID
     * @param pageable 페이지 크기 정보
     * @return Order 엔티티의 페이지 객체
     */
    Page<Order> findByCustomerIdAndOrderIdLessThanOrderByOrderIdDesc(Long customerId, Long cursorId, Pageable pageable);

    /**
     * 커서를 사용한 첫 페이지 조회용 메서드 (cursorId가 없을 때 사용).
     * @param customerId 조회할 고객의 ID
     * @param pageable 페이지 크기 정보
     * @return Order 엔티티의 페이지 객체
     */
    Page<Order> findByCustomerIdOrderByOrderIdDesc(Long customerId, Pageable pageable);

    /**
     * 상태로 거른 커서 조회.
     *
     * <p>🐛 위의 커서 메서드들에는 <b>상태 조건이 없다.</b> 그런데 주문 목록 화면은 상태로
     * 거른다(All / Paid / Unpaid …). 그래서 기존 목록 조회는 주문을 전부 가져온 뒤
     * <b>자바에서</b> 한 건씩 다시 조회하며 걸렀고, 조건에 맞지 않는 주문은 {@code null} 로
     * 남아 <b>응답 목록에 null 이 그대로 섞여 나갔다</b>(OrderDataService.getOrders).
     * 화면이 `order?.id` 처럼 물음표를 붙여 가며 그리던 이유가 이것이다 — "Paid" 를 골라도
     * 행 수는 그대로이고 대부분이 빈 줄이었다.
     *
     * <p>거르기는 SQL 에서 한다. 페이지 크기만큼 <b>실제로 그 상태인</b> 주문이 온다.
     */
    Page<Order> findByCustomerIdAndOrderStatusOrderByOrderIdDesc(Long customerId, OrderStatus status,
            Pageable pageable);

    Page<Order> findByCustomerIdAndOrderStatusAndOrderIdLessThanOrderByOrderIdDesc(Long customerId,
            OrderStatus status, Long cursorId, Pageable pageable);

    /**
     * 고객별 구매 집계 — 등급 산정의 원재료.
     *
     * <p><b>왜 한 방에 GROUP BY 인가.</b> 등급 배치는 "모든 고객" 을 다뤄야 한다. 고객마다
     * 주문을 조회해 자바에서 더하면 고객 수만큼 쿼리가 나간다(N+1 의 야간 배치 버전).
     * 합계는 DB 가 가장 잘 하는 일이다.
     *
     * <p><b>기간 조건이 WHERE 가 아니라 CASE 안에 있는 이유.</b> 결과에는 두 가지가 같이
     * 필요하다 — 최근 3개월 결제액(등급)과 <b>평생 구매 여부</b>(PURCHASED 세그먼트).
     * WHERE 로 기간을 자르면 3개월 안에 주문이 없는 고객은 행 자체가 사라져서, 5년째
     * 단골이지만 최근에 잠잠한 사람이 "한 번도 안 산 사람" 과 구분되지 않는다.
     *
     * <p><b>커서 방식</b>이다. {@code afterCustomerId} 보다 큰 고객만, {@code pageable} 크기만큼.
     * OFFSET 페이징은 GROUP BY 결과 위에서 뒤로 갈수록 느려지고, 배치 도중 새 주문이
     * 들어오면 행이 밀려 건너뛰는 고객이 생긴다. 커서는 둘 다 겪지 않는다.
     *
     * <p>{@code SUM(CASE ...)} 은 {@code null} 이 될 수 없다 — 그룹에는 항상 1건 이상이
     * 있고 CASE 는 언제나 숫자를 내므로 {@code coalesce} 가 필요 없다.
     *
     * <p>{@code o.total} 은 V7 부터 {@code numeric(19,2)} 다. {@link PurchaseStatDto} 의
     * 금액은 {@code long}(상점 통화의 정수 단위)이므로 합계를 {@code Long} 으로 캐스팅한다 —
     * 등급 구간이 달러 단위라 센트는 의미가 없고, DTO 생성자 매칭도 그 형을 요구한다.
     *
     * <p>부분 환불(V9)된 주문은 상태가 그대로(결제됨)라 이 집합에 남는다 — 돌려준 돈만큼
     * 빼서 더한다({@code total − refundedAmount}). 전액 환불은 REFUNDED 라 애초에 빠진다.
     */
    @Query("""
            select new com.project.common.dto.order.PurchaseStatDto(
                o.customerId,
                count(o),
                sum(case when o.createdAt >= :since then 1 else 0 end),
                cast(sum(case when o.createdAt >= :since then o.total - o.refundedAmount else 0 end) as Long))
            from Order o
            where o.orderStatus in :paidStatuses
              and o.customerId is not null
              and o.customerId > :afterCustomerId
            group by o.customerId
            order by o.customerId asc
            """)
    List<PurchaseStatDto> aggregatePurchases(@Param("paidStatuses") Collection<OrderStatus> paidStatuses,
            @Param("since") LocalDateTime since,
            @Param("afterCustomerId") Long afterCustomerId,
            Pageable pageable);

    // ── 재고가 모자라 성립하지 않은 주문 버리기(docs/59) ────────────────────────
    //
    // 주문은 저장한 뒤에 재고를 잡는다(주문 id 가 예약의 키다). 모자라면 방금 만든 주문을 지운다 — 결제 전이고 밖으로
    // 나간 적도 없다(아웃박스·배송·환불 행이 없다). 주문이 주소를 가리키므로(orders.order_address_id, V25) 주소 id 를 읽고
    // 끊은 뒤 주소 → 품목 → 주문 순서로 지운다(V25 전에 만든 주소는 order_id 로 주문도 가리킨다 — 주문보다 먼저 지우면 된다). 모든 단계가 "결제 전·결제 참조 없음" 을 조건으로 건다 — 잘못 불러도 결제된
    // 주문은 지워지지 않는다.

    @Modifying
    @Query(value = """
            update orders.orders set order_address_id = null
             where order_id = :id and order_status = 'NOT_PROCESSED' and provider_payment_id is null
            """, nativeQuery = true)
    int detachAddressOfUnplaced(@Param("id") Long id);

    /** 결제 전 주문이 가리키는 주소 id — 끊기 전에 읽는다(V25 부터 주소는 주문을 가리키지 않는다). */
    @Query(value = """
            select order_address_id from orders.orders
             where order_id = :id and order_status = 'NOT_PROCESSED' and provider_payment_id is null
            """, nativeQuery = true)
    Long findAddressIdOfUnplaced(@Param("id") Long id);

    /** 끊어 낸 주소를 지운다 — 아직 어느 주문이 가리키면 지우지 않는다. */
    @Modifying
    @Query(value = """
            delete from orders.order_address a
             where a.order_address_id = :addressId
               and not exists (select 1 from orders.orders o where o.order_address_id = :addressId)
            """, nativeQuery = true)
    int deleteDetachedAddress(@Param("addressId") Long addressId);

    @Modifying
    @Query(value = """
            delete from orders.order_product p
             where p.order_id = :id
               and exists (select 1 from orders.orders o
                            where o.order_id = :id and o.order_status = 'NOT_PROCESSED' and o.provider_payment_id is null)
            """, nativeQuery = true)
    int deleteProductsOfUnplaced(@Param("id") Long id);

    @Modifying
    @Query(value = """
            delete from orders.orders
             where order_id = :id and order_status = 'NOT_PROCESSED' and provider_payment_id is null
            """, nativeQuery = true)
    int deleteUnplaced(@Param("id") Long id);

    /**
     * 같은 고객의 <b>다른</b> 결제 전 주문 중 재고를 아직 잡고 있는 것(docs/59 §4). 장바구니는 결제 뒤에야 비워지므로, 주문하고
     * 돌아가 다시 주문하면 앞 주문의 예약이 새 주문을 "남의 예약" 으로 막는다 — 새 주문을 만들 때 앞의 것을 풀어 준다.
     * 입금 대기(UNPAID)는 입금 기한까지 잡아 둔 약속이라 건드리지 않는다.
     */
    @Query("""
           select o.orderId from Order o
            where o.customerId = :customerId and o.orderId <> :keepOrderId
              and o.orderStatus = :status and o.providerPaymentId is null
              and o.stockHoldUntil > :now
           """)
    List<Long> findHeldUnplacedOrderIds(@Param("customerId") Long customerId, @Param("keepOrderId") Long keepOrderId,
            @Param("now") LocalDateTime now, @Param("status") OrderStatus status);
}
