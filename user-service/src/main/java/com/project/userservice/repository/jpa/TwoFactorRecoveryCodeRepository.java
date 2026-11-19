package com.project.userservice.repository.jpa;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.TwoFactorRecoveryCode;

public interface TwoFactorRecoveryCodeRepository extends JpaRepository<TwoFactorRecoveryCode, Long> {

    /** 아직 쓰지 않은 코드 수. [JA] 未使用のコード数。 */
    long countByUserIdAndUsedAtIsNull(Long userId);

    /**
     * 한 번만 쓴다 — 쓰지 않은 행만 바꾼다. 동시에 같은 코드를 두 번 보내면 하나만 1 을 받는다.
     * [JA] 一度だけ使う — 未使用の行だけを変える。同時に二度送っても 1 を受けるのは一つ。
     */
    @Modifying
    @Transactional
    @Query("update TwoFactorRecoveryCode c set c.usedAt = :now "
            + "where c.userId = :userId and c.codeHash = :hash and c.usedAt is null")
    int consume(@Param("userId") Long userId, @Param("hash") String hash, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from TwoFactorRecoveryCode c where c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
