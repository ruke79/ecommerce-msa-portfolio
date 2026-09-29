package com.project.delivery_service.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.delivery_service.domain.DeliveryEvent;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, Long> {

    /**
     * 이미 저장된 이벤트의 중복키.
     *
     * <p>폴링은 매번 전체 이력을 돌려주므로, 저장 전에 이 집합과 대조해 새 것만 넣는다.
     * UNIQUE 제약이 최종 방어선이지만, 매번 예외를 던지고 잡는 것보다 조회가 싸다.
     */
    @Query("select e.dedupKey from DeliveryEvent e where e.shipment.shipmentId = :shipmentId")
    Set<String> findDedupKeys(@Param("shipmentId") Long shipmentId);
}
