package com.project.delivery_service.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.project.delivery_service.domain.Shipment;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /**
     * 조회할 때가 된 상자를 <b>배타적으로 선점</b>한다.
     *
     * <p>아웃박스 폴러·SMS 발송기와 같은 이유로 {@code FOR UPDATE SKIP LOCKED} 다(docs/21).
     * 잠금이 없으면 레플리카 둘이 같은 상자를 집어 <b>택배사 API 를 두 번 호출</b>한다 —
     * 공공 API 의 일일 쿼터를 두 배로 태우고, 이벤트도 중복 처리된다.
     *
     * <p>배치 크기를 제한하는 이유는 한 레플리카가 밀린 건을 전부 잠그면 나머지가 놀기
     * 때문이고, 외부 HTTP 호출이 배치 크기만큼 직렬로 붙어 트랜잭션이 길어지기 때문이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
           select s from Shipment s
            where s.nextPollAt is not null
              and s.nextPollAt <= :now
            order by s.nextPollAt asc
           """)
    List<Shipment> claimDueForPolling(@Param("now") LocalDateTime now, Pageable pageable);
}
