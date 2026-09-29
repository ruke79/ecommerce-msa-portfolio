package com.project.notification_service.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.notification_service.domain.PhoneVerification;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    /**
     * 이 번호로 발급된 <b>가장 최근의</b> 살아 있는 코드.
     *
     * <p>최신 것만 보는 이유: 사용자가 "재전송"을 누르면 코드가 여러 개 살아 있게 되는데,
     * 옛 코드도 통과시키면 유효한 코드의 개수가 늘어나 무차별 대입이 그만큼 쉬워진다.
     */
    Optional<PhoneVerification> findFirstByPhoneE164AndConsumedFalseOrderByCreatedAtDesc(String phoneE164);

    /** 만료된 코드 정리. 개인정보를 필요 이상으로 들고 있지 않기 위해서이기도 하다. */
    @Modifying
    @Query("delete from PhoneVerification v where v.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
