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
}