package com.project.notification_service.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.project.notification_service.domain.BroadcastNotification;

@Repository
public interface BroadcastNotificationRepository extends JpaRepository<BroadcastNotification, Long> {

    Optional<BroadcastNotification> findBySourceEventId(String sourceEventId);

    /**
     * 관리자 목록 — 예약분·만료분까지 전부.
     *
     * <p>사용자 목록({@link #visibleFor})과 달리 기간으로 거르지 않는다. 관리자는 아직
     * 시작되지 않은 안내와 이미 끝난 안내를 봐야 한다: 안 보이는 것을 고칠 수는 없다.
     */
    List<BroadcastNotification> findAllByOrderByStartsAtDesc(Pageable pageable);

    /**
     * 이 사람에게 지금 보여야 하는 전체 알림.
     *
     * <p>조건 셋을 <b>쿼리에서</b> 판정한다 — 자바로 거르면 유효하지 않은 공지까지 전부
     * 읽어 온 뒤 버리게 된다.
     *
     * <ol>
     *   <li>시작했고 아직 끝나지 않았다</li>
     *   <li>이 사람이 치우지 않았다({@code dismissed})</li>
     * </ol>
     *
     * <p>읽음 여부는 여기서 거르지 않는다 — 읽은 공지도 목록에는 남아야 한다
     * (개인 알림과 같다).
     */
    @Query("""
            select b from BroadcastNotification b
             where b.startsAt <= :now
               and (b.endsAt is null or b.endsAt >= :now)
               and not exists (
                   select 1 from BroadcastRead r
                    where r.broadcastId = b.broadcastId
                      and r.recipientId = :recipientId
                      and r.dismissed = true)
             order by b.startsAt desc
            """)
    List<BroadcastNotification> visibleFor(@Param("recipientId") Long recipientId,
                                           @Param("now") LocalDateTime now,
                                           Pageable pageable);

    /**
     * 안 읽은 전체 알림 수.
     *
     * <p>"읽음 행이 없으면 안 읽은 것"이다 — 공지를 올릴 때 사용자 수만큼 행을 만들지
     * 않기 때문에(BroadcastNotification 주석) 이렇게 센다.
     */
    @Query("""
            select count(b) from BroadcastNotification b
             where b.startsAt <= :now
               and (b.endsAt is null or b.endsAt >= :now)
               and not exists (
                   select 1 from BroadcastRead r
                    where r.broadcastId = b.broadcastId
                      and r.recipientId = :recipientId
                      and (r.readAt is not null or r.dismissed = true))
            """)
    long countUnreadFor(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);

    /** 읽음 처리 대상 — 지금 유효하고 아직 읽지 않은 것. */
    @Query("""
            select b.broadcastId from BroadcastNotification b
             where b.startsAt <= :now
               and (b.endsAt is null or b.endsAt >= :now)
               and not exists (
                   select 1 from BroadcastRead r
                    where r.broadcastId = b.broadcastId
                      and r.recipientId = :recipientId)
            """)
    List<Long> unreadIdsFor(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);
}
