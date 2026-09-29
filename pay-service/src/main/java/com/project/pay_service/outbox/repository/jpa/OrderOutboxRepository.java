package com.project.pay_service.outbox.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.outbox.model.OrderOutboxEvent;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;




public interface OrderOutboxRepository extends CrudRepository<OrderOutboxEvent, String> {

    Optional<List<OrderOutboxEvent>> findByOutboxStatus(OutboxStatus outboxStatus);

    Optional<OrderOutboxEvent> findByAggregateIdAndOutboxStatus(String id, OutboxStatus outboxStatus);

    /**
     * 한 주문의 응답 이벤트 <b>전부</b>. 결제 완료 응답과 환불 응답이 같은 aggregate_id(주문 id)에
     * 따로 쌓이므로 하나만 돌려주는 위 메서드로는 둘 이상일 때 터진다.
     */
    List<OrderOutboxEvent> findAllByAggregateIdAndOutboxStatus(String id, OutboxStatus outboxStatus);

    /**
     * 발행 대기 중인 아웃박스 이벤트를 <b>배타적으로 선점</b>한다 (order-service 쪽과 같은 이유).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 로 다른 레플리카가 이미 잠근 행은 건너뛴다. 이게 없으면
     * 레플리카 둘이 같은 결제 응답을 각각 발행하고, 주문 서비스는 같은 사가 단계를 두 번 받는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OrderOutboxEvent e where e.outboxStatus = :status order by e.createdAt asc")
    List<OrderOutboxEvent> claimByOutboxStatus(@Param("status") OutboxStatus status, Pageable pageable);
}
