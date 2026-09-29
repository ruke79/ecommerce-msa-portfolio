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
    List<BroadcastRead> findByRecipientIdAndBroadcastIdIn(Long recipientId, Collection<Long> broadcastIds);

    void deleteByBroadcastId(Long broadcastId);
}
