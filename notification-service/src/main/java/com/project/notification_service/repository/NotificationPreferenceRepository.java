package com.project.notification_service.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.project.notification_service.domain.NotificationPreference;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    Optional<NotificationPreference> findByUserId(Long userId);

    Optional<NotificationPreference> findByPhoneE164AndPhoneVerifiedTrue(String phoneE164);
}
