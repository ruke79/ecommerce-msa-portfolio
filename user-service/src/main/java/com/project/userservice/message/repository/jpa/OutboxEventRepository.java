package com.project.userservice.message.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.userservice.message.model.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * 아직 안 보낸 이벤트를 <b>선점</b>한다.
     *
     * <h3>왜 {@code FOR UPDATE SKIP LOCKED} 인가</h3>
     *
     * <p>user-service 는 {@code replicas: 2} 로 뜬다. 잠금 없이 조회하면 두 레플리카가
     * <b>같은 행을 읽고 같은 이벤트를 두 번 발행한다.</b> order-service 의 결제 아웃박스가
     * 정확히 그 버그를 갖고 있었고(docs/21), 여기서 되풀이하지 않는다.
     *
     * <p>{@code SKIP LOCKED} 는 "남이 잡은 행은 건너뛰어라" 라는 뜻이다. 그냥
     * {@code FOR UPDATE} 면 두 번째 레플리카가 <b>기다린다</b> — 중복은 막지만 한쪽이
     * 노는 것은 같고, 트랜잭션이 길어진다.
     *
     * <p>잠금은 <b>상태를 확정할 때까지</b> 유지돼야 한다. 그래서 호출자는 같은 트랜잭션
     * 안에서 발행을 <b>동기로</b> 기다린 뒤 status 를 바꾼다. 비동기로 두면 콜백이 커밋
     * 뒤에 돌아 그 사이 행은 여전히 STARTED 이고, 잠금이 풀린 그 틈에 다른 레플리카가
     * 집어 간다.
     *
     * <p>⚠️ 네이티브 쿼리인 이유: JPQL 에는 {@code SKIP LOCKED} 가 없다.
     * {@code @Lock(PESSIMISTIC_WRITE)} + {@code jakarta.persistence.lock.timeout=-2} 로
     * 흉내 낼 수는 있지만, 여기서는 의도가 SQL 에 그대로 보이는 편이 낫다.
     *
     * <p>{@code order by created_at} — 가입 순서대로 나간다. 같은 사용자의 이벤트가
     * 여럿일 일은 없지만, 순서가 정해져 있어야 재현이 쉽다.
     */
    @Query(value = """
            select * from users.outbox_event
            where outbox_status = 'STARTED'
            order by created_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> claimPending(@Param("limit") int limit);
}
