package com.project.notification_service.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.notification_service.domain.BroadcastRead;

@Repository
public interface BroadcastReadRepository
        extends JpaRepository<BroadcastRead, BroadcastRead.Key> {

    Optional<BroadcastRead> findByBroadcastIdAndRecipientId(Long broadcastId, Long recipientId);

    /** 목록을 그릴 때 "이 사람이 읽은 공지" 를 한 번에 가져온다 — 공지마다 조회하지 않는다. */
    // [JA] 一覧を描くときに「この人が読んだお知らせ」を一度に取ってくる — お知らせごとに照会しない。
    List<BroadcastRead> findByRecipientIdAndBroadcastIdIn(Long recipientId, Collection<Long> broadcastIds);

    void deleteByBroadcastId(Long broadcastId);

    /** 공개 데모 계정의 공지 읽음 · 숨김 표시를 지운다 — 매일 초기화(docs/100). [JA] 公開デモアカウントのお知らせ既読 · 非表示を消す。 */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from BroadcastRead b where b.recipientId in :recipientIds")
    int deleteAllOfRecipients(@org.springframework.data.repository.query.Param("recipientIds") Collection<Long> recipientIds);
}
