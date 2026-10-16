package com.project.userservice.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.model.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // --- 비관적 잠금 적용 ---
    // 알림을 읽음 처리하는 등 상태를 변경할 때 락을 걸어 충돌을 방지합니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<List<Notification>> findByIsRead(boolean isRead);
    
    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    @Override
    Optional<Notification> findById(Long id);

    /**
     * 회원 탈퇴 최종 처리 — 그 사용자의 알림 행. {@code email} 열에는 이제 userId 가 들어가지만(AuthLoginFilter), 옛 행에는 실제 이메일이
     * 있어 둘 다 지운다.
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from Notification n where n.email in :owners")
    int deleteByOwners(@org.springframework.data.repository.query.Param("owners") java.util.Collection<String> owners);
}