package com.project.chat_service.repository.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.constants.SupportTopic;
import com.project.chat_service.model.SupportThread;

/** 상담 스레드 (docs/58). */
public interface SupportThreadRepository extends JpaRepository<SupportThread, Long> {

    /** 고객의 열린 스레드 — 부분 유니크 인덱스라 많아야 하나다. */
    Optional<SupportThread> findFirstByCustomerIdAndStatusNot(Long customerId, SupportThreadStatus status);

    /** 고객의 지난 상담 — 최근 것부터. */
    List<SupportThread> findByCustomerIdAndStatusOrderByClosedAtDesc(Long customerId, SupportThreadStatus status,
            Pageable pageable);

    /** 데스크 목록(상태별). 대화가 없으면(방금 연 문의) 연 순서로. */
    @Query("select t from SupportThread t where t.status = :status "
            + "order by coalesce(t.lastMessageAt, t.createdAt) desc")
    List<SupportThread> findDesk(@Param("status") SupportThreadStatus status, Pageable pageable);

    /** 데스크 목록(상태 · 유형별). */
    @Query("select t from SupportThread t where t.status = :status and t.topic = :topic "
            + "order by coalesce(t.lastMessageAt, t.createdAt) desc")
    List<SupportThread> findDeskByTopic(@Param("status") SupportThreadStatus status, @Param("topic") SupportTopic topic,
            Pageable pageable);

    /** 대기 순번 — 나보다 먼저 연 대기 중 문의 수(V8 의 부분 인덱스). */
    @Query("select count(t) from SupportThread t where t.status = com.project.chat_service.constants.SupportThreadStatus.WAITING "
            + "and t.createdAt < :createdAt")
    long countWaitingBefore(@Param("createdAt") Instant createdAt);

    /** 고객의 지난 상담 수 — 데스크의 고객 정보. */
    long countByCustomerIdAndStatus(Long customerId, SupportThreadStatus status);

    /** 데스크 "내 담당" — 답변이 필요한 것부터. */
    @Query("select t from SupportThread t where t.agentId = :agentId and t.status = :status "
            + "order by t.awaitingReply desc, coalesce(t.lastMessageAt, t.createdAt) desc")
    List<SupportThread> findMine(@Param("agentId") Long agentId, @Param("status") SupportThreadStatus status,
            Pageable pageable);

    /**
     * 대기 중인 스레드를 맡는다 — <b>조건부 갱신</b>이라 두 상담원이 동시에 눌러도 한 명만 성공한다(1행/0행).
     * 읽고 → 확인하고 → 쓰는 방식이면 둘 다 "대기 중" 을 읽고 둘 다 쓴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SupportThread t set t.agentId = :agentId, t.status = com.project.chat_service.constants.SupportThreadStatus.ACTIVE, "
            + "t.assignedAt = :now where t.id = :id and t.status = com.project.chat_service.constants.SupportThreadStatus.WAITING")
    int claim(@Param("id") Long id, @Param("agentId") Long agentId, @Param("now") Instant now);
}
