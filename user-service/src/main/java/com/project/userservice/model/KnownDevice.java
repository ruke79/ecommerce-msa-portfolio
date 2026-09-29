package com.project.userservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 이 사용자가 로그인한 적 있는 기기 하나.
 *
 * <p>있는 이유는 하나다: <b>"이 로그인이 처음 보는 기기인가"</b>에 답하기 위해서.
 * 비교할 이력이 없으면 새 기기인지 알 수 없고, 그러면 {@code SECURITY_ALERT} 를 만들
 * 수가 없다.
 *
 * <p><b>기기를 무엇으로 식별하나.</b> 브라우저에 httpOnly 쿠키로 심는 난수 하나다.
 * User-Agent 나 IP 로 지문을 만들지 않는다 — IP 는 모바일에서 수시로 바뀌고 User-Agent 는
 * 브라우저가 업데이트될 때마다 바뀐다. 둘 중 하나라도 지문에 넣으면 <b>알림이 소음이
 * 되고, 소음이 되면 사용자는 진짜 경고도 무시한다.</b>
 *
 * <p>쿠키를 지우면 새 기기로 잡힌다. 오탐이 아니다 — 쿠키를 지운 브라우저는 서버 입장에서
 * 처음 보는 브라우저와 구별되지 않는다.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "known_device", schema = "users",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = { "user_id", "token_hash" }, name = "uk_known_device")
       },
       indexes = { @Index(columnList = "user_id, last_seen_at", name = "idx_known_device_user") })
public class KnownDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 쿠키에 담긴 난수의 SHA-256(hex). <b>원문은 어디에도 저장하지 않는다.</b>
     *
     * <p>🔒 이 값은 인증 수단이 아니다 — 알고 있어도 로그인은 못 한다. 하지만 <b>보안
     * 알림을 끌 수 있는 값</b>이다: 비밀번호를 이미 아는 공격자가 이것까지 알면 알림 없이
     * 조용히 들어온다. DB 가 새더라도 그 능력이 함께 새지 않게 한다(가입 확인 토큰과 같은
     * 규칙).
     */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /**
     * "Chrome · Windows" 같은 표시용 문자열.
     *
     * <p>User-Agent 원문을 그대로 두지 않는다 — 지문 가치가 있는 값이고, 사용자에게 필요한
     * 것은 "어느 브라우저였나" 뿐이다.
     */
    @Column(length = 120)
    private String label;

    /**
     * 마스킹된 IP. <b>표시 전용</b>이고 어떤 판정에도 쓰지 않는다.
     *
     * <p>{@code X-Forwarded-For} 는 클라이언트가 위조할 수 있다 — 게이트웨이의
     * {@code CustomUserKeyResolver} 가 같은 이유로 이 헤더를 무시한다. 알림 문구를 조금 더
     * 친절하게 만들 뿐이고, 새 기기인지는 <b>쿠키가</b> 정한다.
     */
    @Column(name = "last_ip", length = 64)
    private String lastIp;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** 이번 로그인을 기록한다. */
    public void touch(String label, String maskedIp, LocalDateTime when) {
        this.lastSeenAt = when;
        // 라벨과 IP 는 있을 때만 갱신한다. 값을 못 읽은 로그인 한 번이 예전 기록을
        // 지우면, 사용자는 목록에서 "알 수 없음" 만 보게 된다.
        if (label != null && !label.isBlank()) {
            this.label = label;
        }
        if (maskedIp != null && !maskedIp.isBlank()) {
            this.lastIp = maskedIp;
        }
    }
}
