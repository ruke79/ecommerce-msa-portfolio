package com.project.notification_service.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.SmsStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientIdAndDeletedFalseOrderByCreatedAtDesc(Long recipientId, Pageable pageable);

    long countByRecipientIdAndReadFalseAndDeletedFalse(Long recipientId);

    Optional<Notification> findBySourceEventId(String sourceEventId);

    /**
     * 발송 대기·재시도 대상을 <b>선점</b>한다.
     *
     * <p>아웃박스 폴러와 같은 이유로 {@code FOR UPDATE SKIP LOCKED} 다(docs/21). 잠금이 없으면
     * 레플리카 둘이 같은 알림을 집어 <b>사용자에게 같은 문자가 두 통</b> 간다. 아웃박스 때는
     * 결제가 중복됐고, 여기서는 요금과 사용자 짜증이 중복된다.
     *
     * <p>🔴 {@code sms_next_attempt_at} 조건은 {@code MailMessageRepository.claimForSend} 와
     * 같은 이유로 있다 — 상한만 있고 간격이 없으면 3회 재시도가 15초 만에 소진된다.
     * {@code is null} 을 함께 보지 않으면 새 행이 영영 선택되지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
           select n from Notification n
            where n.smsStatus in :statuses
              and n.smsAttempts < :maxAttempts
              and (n.smsNextAttemptAt is null or n.smsNextAttemptAt <= :now)
            order by n.createdAt asc
           """)
    List<Notification> claimForSms(@Param("statuses") List<SmsStatus> statuses,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("now") LocalDateTime now,
                                   Pageable pageable);
}
