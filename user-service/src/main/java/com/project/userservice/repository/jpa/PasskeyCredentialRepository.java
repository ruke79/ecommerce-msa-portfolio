package com.project.userservice.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.userservice.model.PasskeyCredential;

/** 패스키(docs/68 §20). 남의 것은 id 로 찾지 않는다 — 언제나 userId 와 함께 찾는다(IDOR). */
@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {

    Optional<PasskeyCredential> findByCredentialId(String credentialId);

    List<PasskeyCredential> findByUserIdOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    Optional<PasskeyCredential> findByPasskeyIdAndUserId(Long passkeyId, Long userId);
}
