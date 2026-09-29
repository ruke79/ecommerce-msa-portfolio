package com.project.order_service.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.order_service.model.OrderAddress;


@Repository
public interface OrderAddressRepository extends JpaRepository<OrderAddress, Long> {

    // 비관적 잠금: 주문 주소를 조회할 때 락을 걸어 동시 수정을 막습니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrderAddress> findByOrder_OrderId(Long orderId);

    // 낙관적 잠금: 주문 주소 엔티티의 @Version 필드를 활용합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    @Override
    Optional<OrderAddress> findById(Long id);
}