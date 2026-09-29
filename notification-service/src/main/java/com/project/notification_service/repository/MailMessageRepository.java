package com.project.notification_service.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.MailStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {

    Optional<MailMessage> findBySourceEventId(String sourceEventId);

    /**
     * 발송 대기·재시도 대상을 <b>선점</b>한다.
     *
     * <p>{@code NotificationRepository.claimForSms} 와 같은 이유로
     * {@code FOR UPDATE SKIP LOCKED} 다(docs/21). 잠금이 없으면 레플리카 둘이 같은 행을 집어
     * <b>같은 메일이 두 통</b> 간다. 확인 메일 두 통은 성가신 정도지만, 비밀번호 재설정 메일이
     * 두 통이면 유효한 재설정 링크가 두 개 살아 있게 된다.
     *
     * <p>{@code lock.timeout = -2} 는 하이버네이트에서 {@code SKIP LOCKED} 를 뜻한다. 그냥
     * {@code FOR UPDATE} 로 두면 잠금을 기다리며 두 레플리카가 직렬화되어, 잠금은 맞지만
     * 처리량이 레플리카 하나짜리로 떨어진다.
     *
     * <p>🔴 <b>{@code next_attempt_at} 조건이 이 채널의 재시도를 실제로 만든다.</b> 예전에는
     * 상한에 닿지 않은 행을 무조건 다시 집었고, 폴링 주기가 5초라 5회 재시도가 20초 만에
     * 소진됐다 — 상한을 5로 둔 이유(그레이리스팅)가 통째로 무효가 됐다. 경위는
     * {@link com.project.notification_service.domain.RetryBackoff} 에 있다.
     *
     * <p>{@code is null} 을 함께 보는 것은 생략할 수 없다. 새로 만들어진 행과 이 컬럼이 없던
     * 시절의 행은 모두 null 인데, 조건에서 빠뜨리면 <b>그 행들이 영영 선택되지 않는다.</b>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
           select m from MailMessage m
            where m.status in :statuses
              and m.attempts < :maxAttempts
              and (m.nextAttemptAt is null or m.nextAttemptAt <= :now)
            order by m.createdAt asc
           """)
    List<MailMessage> claimForSend(@Param("statuses") List<MailStatus> statuses,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("now") LocalDateTime now,
                                   Pageable pageable);

    /**
     * 오래된 미발송 메일의 파라미터를 지운다.
     *
     * <p><b>왜 필요한가.</b> {@code params} 에는 가입 확인 링크·비밀번호 재설정 링크가 들어 있다 —
     * 그 자체로 계정을 활성화하거나 비밀번호를 바꿀 수 있는 자격증명이다. 정상 경로에서는 발송이
     * 끝나는 즉시 {@code MailMessage#clearParams} 가 지우지만, <b>끝내 종료 상태에 도달하지 못한
     * 행</b>이 남을 수 있다(디스패처가 꺼져 있던 사이 쌓인 것 등).
     *
     * <p>링크 자체는 24시간이면 만료되므로 그 뒤로는 값이 남아 있을 이유가 없다. 행은 남긴다 —
     * "왜 이 사용자에게 메일이 안 갔나"를 나중에 볼 수 있어야 하기 때문이다. 지우는 것은
     * 자격증명뿐이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update MailMessage m
              set m.params = null
            where m.params is not null
              and m.createdAt < :before
           """)
    int scrubParamsOlderThan(@Param("before") LocalDateTime before);
}
