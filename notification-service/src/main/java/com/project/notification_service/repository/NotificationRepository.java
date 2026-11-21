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

    /**
     * 받는 사람의 안 읽은(지우지 않은) 알림을 모두 읽음으로 — 한 문장. 🐛 2026-11-21 까지 서비스가 최신 500건을 읽어 하나씩 고쳤다 —
     * 읽은 것까지 섞인 500건이라 알림이 많은 사용자는 오래된 안 읽은 알림이 남았고(배지가 0 이 되지 않았다), 엔티티 N개를 읽고 썼다.
     * [JA] 受取人の未読(削除していない)通知をすべて既読に — 一文で。以前は最新 500 件を読んで一つずつ直し、通知の多いユーザーは
     *      古い未読が残った(バッジが 0 にならない)。
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.read = true where n.recipientId = :recipientId and n.read = false and n.deleted = false")
    int markAllReadOf(@Param("recipientId") Long recipientId);

    Optional<Notification> findBySourceEventId(String sourceEventId);

    /** 공개 데모 계정의 알림을 지운다 — 매일 초기화(docs/100). [JA] 公開デモアカウントの通知を消す — 毎日の初期化。 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Notification n where n.recipientId in :recipientIds")
    int deleteAllOfRecipients(@Param("recipientIds") java.util.Collection<Long> recipientIds);

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
     *
     * <p>[JA] 送信待ち・再試行対象を<b>先取り</b>する。アウトボックスポーラーと同じ理由で
     * {@code FOR UPDATE SKIP LOCKED} だ(docs/21)。ロックがないとレプリカ二つが同じ通知を拾い<b>ユーザーに同
     * じ SMS が二通</b>届く。アウトボックスのときは決済が重複し、ここでは料金とユーザーの苛立ちが重複する。
     * 🔴 {@code sms_next_attempt_at} の条件は {@code MailMessageRepository.claimForSend} と同じ理由である —
     * 上限だけで間隔がないと 3 回の再試行が 15 秒で尽きる。{@code is null}
     * を併せて見ないと新しい行が永遠に選ばれない。
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
