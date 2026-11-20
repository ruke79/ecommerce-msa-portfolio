package com.project.userservice.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.userservice.model.PasskeyCredential;

/** 패스키(docs/68 §20). 남의 것은 id 로 찾지 않는다 — 언제나 userId 와 함께 찾는다(IDOR). */
// [JA] パスキー(docs/68 §20)。他人のものを id で探さない — 常に userId と一緒に探す(IDOR)。
@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {

    Optional<PasskeyCredential> findByCredentialId(String credentialId);

    List<PasskeyCredential> findByUserIdOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    Optional<PasskeyCredential> findByPasskeyIdAndUserId(Long passkeyId, Long userId);

    /** 회원 탈퇴 최종 처리(WithdrawalService) — 그 사용자의 행을 모두 지운다. */
    // [JA] 会員退会の最終処理(WithdrawalService) — そのユーザーの行をすべて消す。
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from PasskeyCredential x where x.userId = :userId")
    int deleteByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
