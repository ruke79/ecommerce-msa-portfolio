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

    // 주문 id 로 주소를 찾는 조회는 없다 — 주소는 주문이 가리킨다(Order.getShippingAddress, V25).

    // 낙관적 잠금: 주문 주소 엔티티의 @Version 필드를 활용합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    @Override
    Optional<OrderAddress> findById(Long id);
}