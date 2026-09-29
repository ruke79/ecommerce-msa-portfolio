package com.project.order_service.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.order_service.model.OrderProduct;

@Repository
public interface OrderProductRepository extends JpaRepository<OrderProduct, Long> {

    // 비관적 잠금: 주문 상품 목록을 조회할 때 락을 걸어 다른 트랜잭션의 접근을 막습니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OrderProduct p where p.order.orderId = :orderId")
    Optional<List<OrderProduct>> findByOrder_OrderId(Long orderId);

    // 낙관적 잠금: OrderProduct 엔티티의 @Version 필드를 활용합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    @Override
    Optional<OrderProduct> findById(Long id);

    /**
     * 재고로 되돌린 수를 더한다 — 복원 이벤트를 <b>보낸 뒤</b>({@code OrderSaga.restock}).
     *
     * <p>한 문장인 이유는 {@code OrderRepository#markStockRestored} 와 같다. {@code returnedQty}
     * 를 넘지 않게 문장 안에서 막는다 — 두 번 불려도 돌아온 수 이상으로 되돌리지 않는다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update OrderProduct p set p.restockedQty = p.restockedQty + :qty "
            + "where p.orderProductId = :id and p.restockedQty + p.discardedQty + :qty <= p.returnedQty")
    int addRestocked(@org.springframework.data.repository.query.Param("id") Long orderProductId,
            @org.springframework.data.repository.query.Param("qty") int qty);

    /**
     * 이 고객이 이 상품을 <b>받았는가</b> — 리뷰 작성 자격(docs/57 §13).
     *
     * <p>받은 주문만 센다: 배송 완료(DELIVERED)·구매 확정(COMPLETED). 결제만 하고 아직 오지 않은 상품의 리뷰는 쓸 수 없다.
     * 모자라 한 개도 배정되지 못한 품목(qty ≤ shortQty)은 받은 것이 아니다.
     */
    @Query("select count(p) > 0 from OrderProduct p where p.order.customerId = :customerId "
            + "and p.productId = :productId and p.qty > p.shortQty "
            + "and p.order.orderStatus in (com.project.common.constants.OrderStatus.DELIVERED, "
            + "com.project.common.constants.OrderStatus.COMPLETED)")
    boolean hasReceived(@org.springframework.data.repository.query.Param("customerId") Long customerId,
            @org.springframework.data.repository.query.Param("productId") Long productId);
}
