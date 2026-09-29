package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SMS 2단계 인증(및 번호 등록) 코드 1건.
 *
 * <p>이 엔티티의 설계는 대부분 <b>공격을 막기 위한 것</b>이다. 인증번호는 6자리라 경우의 수가
 * 100만뿐이고, 아무 제약이 없으면 자동화된 시도로 몇 분 안에 뚫린다.
 *
 * <ul>
 *   <li><b>코드를 평문으로 저장하지 않는다.</b> DB 를 읽을 수 있는 사람이 남의 인증을 통과할 수
 *       있으면 2단계 인증의 의미가 없다. 백업·로그·덤프까지 생각하면 더욱 그렇다.
 *       해시만 저장하고 검증은 해시 비교로 한다.</li>
 *   <li><b>시도 횟수를 센다.</b> 5회 틀리면 그 코드는 죽는다. 이게 없으면 100만 번 시도로
 *       반드시 뚫린다.</li>
 *   <li><b>만료가 있다.</b> 5분. 유출된 코드의 유효 시간을 줄인다.</li>
 *   <li><b>1회용이다.</b> 성공하면 소비 표시를 해서 재사용할 수 없게 한다 —
 *       재전송 공격(replay) 방어.</li>
 * </ul>
 *
 * <p>발급 자체의 속도 제한(같은 번호로 도배 발송)은 Redis 에서 처리한다. DB 카운터로 하면
 * 레플리카가 늘었을 때 경쟁 조건이 생기고, 무엇보다 <b>남의 번호로 문자 폭탄을 보내는 공격</b>은
 * 요금이 우리 쪽으로 청구되므로 빠르고 확실하게 막아야 한다(VerificationService).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "phone_verification", schema = "notification",
       indexes = {
           @Index(name = "idx_phone_verification_lookup", columnList = "phone_e164, consumed, expires_at")
       })
public class PhoneVerification {

    /** 6자리 코드가 유효한 시간. 짧을수록 안전하지만 사용자가 문자를 확인할 시간은 줘야 한다. */
    public static final int TTL_MINUTES = 5;

    /** 이 횟수를 틀리면 코드를 폐기한다. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long verificationId;

    /** 등록·인증 대상 번호 (E.164). */
    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    /**
     * 누가 요청했는지. 로그인 2FA 면 사용자 id, 최초 번호 등록이면 null 일 수 있다.
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 코드의 해시. <b>평문 코드는 어디에도 저장하지 않는다</b> — 문자로 나가고 나면
     * 우리 쪽에는 해시만 남는다.
     */
    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "consumed", nullable = false)
    private boolean consumed;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public PhoneVerification(String phoneE164, Long userId, String codeHash) {
        this.phoneE164 = phoneE164;
        this.userId = userId;
        this.codeHash = codeHash;
        this.attempts = 0;
        this.consumed = false;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        this.expiresAt = this.createdAt.plusMinutes(TTL_MINUTES);
    }

    public boolean isExpired() {
        return LocalDateTime.now(ZoneOffset.UTC).isAfter(expiresAt);
    }

    public boolean isExhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    /** 검증에 쓸 수 있는 상태인가. 만료·소진·소비 중 하나라도 해당되면 못 쓴다. */
    public boolean isUsable() {
        return !consumed && !isExpired() && !isExhausted();
    }

    public void recordFailedAttempt() {
        this.attempts += 1;
    }

    public void consume() {
        this.consumed = true;
    }
}
