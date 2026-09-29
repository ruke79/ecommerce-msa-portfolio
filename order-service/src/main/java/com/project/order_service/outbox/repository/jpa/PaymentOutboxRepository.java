package com.project.order_service.outbox.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;



public interface PaymentOutboxRepository extends CrudRepository<PaymentOutboxEvent, String> {

    Optional<List<PaymentOutboxEvent>> findByOutboxStatus(OutboxStatus status);

    /**
     * 발행 대기 중인 아웃박스 이벤트를 <b>배타적으로 선점</b>한다.
     *
     * <p>왜 필요한가: 예전에는 스케줄러가 {@code findByOutboxStatus(STARTED)} 로 그냥 조회했다.
     * 레플리카가 하나일 때는 드러나지 않지만, <b>둘 이상이면 양쪽이 같은 행을 읽어 같은 이벤트를
     * 두 번 발행</b>한다. 결제 요청이 두 번 나가는 것이므로 돈이 걸린 문제다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 는 이미 다른 트랜잭션이 잠근 행을 <b>기다리지 않고
     * 건너뛴다.</b> 그래서 레플리카 A 와 B 가 동시에 폴링해도 겹치지 않는 행 집합을 가져간다.
     * 일반 {@code FOR UPDATE} 라면 B 가 A 를 기다렸다가 A 가 커밋한 뒤 <b>같은 행을 다시</b>
     * 집어 중복 발행이 그대로 일어난다 — 기다리지 않고 건너뛰는 것이 핵심이다.
     *
     * <p>Hibernate 는 잠금 타임아웃 힌트 {@code -2}(= {@code LockOptions.SKIP_LOCKED})를
     * 그 구문으로 번역한다.
     *
     * <p>배치 크기를 제한하는 이유: 제한이 없으면 한 레플리카가 밀린 이벤트를 전부 잠그고
     * 나머지는 할 일이 없어진다. 오래된 것부터 가져와 발행 순서도 대체로 보존한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from PaymentOutboxEvent e where e.outboxStatus = :status order by e.createdAt asc")
    List<PaymentOutboxEvent> claimByOutboxStatus(@Param("status") OutboxStatus status, Pageable pageable);

}
