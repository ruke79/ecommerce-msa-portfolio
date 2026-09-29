package com.project.delivery_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.project.delivery_service.domain.Delivery;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);

    List<Delivery> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    /**
     * 송장을 기다리는 배송 — 상자 중 하나라도 송장 없이 {@code PENDING} 인 것. 오래된 것부터.
     *
     * <p>{@code distinct} 는 상자가 여럿일 때 같은 배송이 두 줄로 나오지 않게 한다.
     */
    @Query("""
           select distinct d from Delivery d
             join d.shipments s
            where s.trackingNumber is null
              and s.status = com.project.delivery_service.domain.DeliveryStatus.PENDING
            order by d.createdAt asc
           """)
    List<Delivery> findAwaitingTracking(Pageable pageable);

    List<Delivery> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
