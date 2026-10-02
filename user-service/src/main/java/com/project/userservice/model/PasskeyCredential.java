package com.project.userservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 패스키 하나 — 사용자의 기기가 가진 비밀 키에 짝지어진 <b>공개 키</b>(docs/68 §20, V15).
 *
 * <p>이 행이 통째로 새어도 로그인에 쓸 수 있는 값은 없다. 비밀 키는 기기를 떠나지 않는다.
 */
// [JA] パスキー一つ — ユーザーの端末が持つ秘密鍵と対になる公開鍵。この行が丸ごと漏れてもログインに使える値はない。
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "passkey_credential", schema = "users",
       indexes = { @Index(columnList = "user_id", name = "idx_passkey_credential_user") })
public class PasskeyCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "passkey_id")
    private Long passkeyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** base64url(자격증명 id) — 전역에서 하나. */
    @Column(name = "credential_id", nullable = false, unique = true, length = 1400)
    private String credentialId;

    /** base64url(난수 32바이트) — 이메일 · userId 를 기기에 남기지 않는다. */
    @Column(name = "user_handle", nullable = false, length = 64)
    private String userHandle;

    @Column(name = "attested_credential_data", nullable = false)
    private byte[] attestedCredentialData;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "uv_initialized", nullable = false)
    private boolean uvInitialized;

    @Column(name = "backup_eligible")
    private Boolean backupEligible;

    @Column(name = "backup_state")
    private Boolean backupState;

    @Column(length = 120)
    private String transports;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
