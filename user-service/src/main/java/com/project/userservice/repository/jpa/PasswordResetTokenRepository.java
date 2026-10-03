package com.project.userservice.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.model.PasswordResetToken;
import com.project.userservice.model.User;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    
    // --- 비관적 잠금 적용 ---
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByToken(String token);

    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<PasswordResetToken> findByUser(User user);

    /**
     * 만료된 재설정 토큰을 지운다.
     *
     * <p>🐛 예전 인자는 {@code java.util.Date} 였는데 필드는 {@code Instant} 라, Hibernate 6 가 바인딩에서 거절했다
     * ("Date did not match parameter type Instant") — 정리가 <b>매번</b> 실패했고, 같은 트랜잭션의 확인 토큰 정리까지
     * 되돌려졌다(경고 로그 한 줄뿐). 쓰이지 않던 같은 모양의 파생 메서드 둘도 지웠다.
     */
    // [JA] 期限切れの再設定トークンを消す。以前の引数は Date で、フィールドは Instant のため毎回失敗していた。
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiryDate <= ?1")
    void deleteAllExpiredSince(java.time.Instant now);

    /** 회원 탈퇴 최종 처리용. 살아 있는 재설정 토큰을 남기면 지워진 계정의 비밀번호를 바꿀 수 있다. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from PasswordResetToken t where t.user.userId = :userId")
    int deleteByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}