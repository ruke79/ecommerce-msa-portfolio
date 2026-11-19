package com.project.userservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 2단계 인증 복구 코드 하나(users V21, docs/57 §22). 원문은 없다 — {@code codeHash} 는 HMAC(파생 키, 코드).
 * [JA] 二段階認証のリカバリーコード一つ。原文はない — codeHash は HMAC(派生鍵, コード)。
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "two_factor_recovery_code", schema = "users",
       uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "code_hash" }, name = "uk_two_factor_recovery_code"))
public class TwoFactorRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "code_id")
    private Long codeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public TwoFactorRecoveryCode(Long userId, String codeHash, LocalDateTime createdAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
    }
}
