package com.project.userservice.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.model.PasswordResetToken;
import com.project.userservice.model.User;

import java.util.Date;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    
    // --- 비관적 잠금 적용 ---
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByToken(String token);

    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<PasswordResetToken> findByUser(User user);

    Stream<PasswordResetToken> findAllByExpiryDateLessThan(Date now);

    void deleteByExpiryDateLessThan(Date now);

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiryDate <= ?1")
    void deleteAllExpiredSince(Date now);

    /** 회원 탈퇴 최종 처리용. 살아 있는 재설정 토큰을 남기면 지워진 계정의 비밀번호를 바꿀 수 있다. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from PasswordResetToken t where t.user.userId = :userId")
    int deleteByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}