package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 알림 설정 — 등록된 번호와 SMS 수신 여부.
 *
 * <p>번호는 <b>인증을 통과해야만</b> 저장된다({@code phoneVerified}). 인증 없이 받은 번호로
 * 문자를 보내면 두 가지가 잘못된다: 오타로 남의 번호에 문자가 가고, 남의 번호를 넣어 문자
 * 폭탄을 보내는 데 우리 서비스가 쓰인다(요금은 우리가 낸다).
 *
 * <p>{@code smsEnabled} 는 종류별 기본값({@link NotificationType#isSmsWorthyByDefault()})
 * 위에 얹히는 사용자 의사다. 끄면 어떤 종류도 SMS 로 가지 않는다 —
 * <b>단, 보안 알림은 예외</b>다(SECURITY_ALERT). 계정 탈취 시도를 사용자 설정으로 숨길 수는 없다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification_preference", schema = "notification")
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preference_id")
    private Long preferenceId;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** E.164. 인증 전에는 null 이다. */
    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    /** 문구 언어와 번호 해석 기준이 되는 국가 (KR/JP). */
    @Column(name = "region", length = 2)
    private String region;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "sms_enabled", nullable = false)
    private boolean smsEnabled;

    /**
     * 알림 메일을 받을 주소.
     *
     * <p><b>어디서 오나.</b> 게이트웨이가 JWT 에서 꺼내 붙이는 {@code User-Email} 헤더다
     * (클라이언트가 보낸 같은 이름의 헤더는 게이트웨이가 지운다 — docs/27). 즉 사용자가 알림
     * API 를 한 번이라도 호출하면 채워진다. 프론트엔드의 알림 배지가 화면마다
     * {@code /unread-count} 를 부르므로 실제로는 로그인 직후에 채워진다.
     *
     * <p><b>왜 번호처럼 따로 인증하지 않나.</b> 이 주소는 사용자가 입력한 값이 아니라 <b>로그인에
     * 쓰이는 계정 주소</b>다. user-service 가 가입 시 확인 메일로 이미 검증했고, 사용자가 임의로
     * 바꿔 넣을 수 있는 자리가 아니다. 번호는 사용자가 직접 입력하므로 오타로 남의 번호에 문자가
     * 갈 수 있어 별도 인증이 필요했지만, 여기는 그 위험이 없다.
     */
    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    /** 로그인 시 SMS 2단계 인증을 요구할지. */
    @Column(name = "two_factor_enabled", nullable = false)
    private boolean twoFactorEnabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public NotificationPreference(Long userId) {
        this.userId = userId;
        this.phoneVerified = false;
        this.smsEnabled = true;          // 번호가 인증되기 전까지는 어차피 아무것도 안 나간다
        this.emailEnabled = true;        // 주소가 채워지기 전까지는 어차피 아무것도 안 나간다
        this.twoFactorEnabled = false;   // 2FA 는 사용자가 명시적으로 켜야 한다
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 계정 주소를 기록한다. 게이트웨이가 검증한 {@code User-Email} 헤더에서만 호출해야 한다.
     *
     * <p>값이 같으면 아무것도 하지 않는다 — 알림 배지가 화면마다 호출되므로, 매번 쓰면 같은 값으로
     * UPDATE 가 나가 커넥션과 WAL 을 낭비한다.
     *
     * @return 실제로 바뀌었는지
     */
    public boolean rememberEmail(String email) {
        if (email == null || email.isBlank() || email.equals(this.email)) {
            return false;
        }
        this.email = email.trim();
        touch();
        return true;
    }

    public void setEmailEnabled(boolean enabled) {
        this.emailEnabled = enabled;
        touch();
    }

    /** 인증을 마친 번호를 등록한다. 이 경로 밖에서 번호가 설정되면 안 된다. */
    public void confirmPhone(String e164, String region) {
        this.phoneE164 = e164;
        this.region = region;
        this.phoneVerified = true;
        touch();
    }

    public void removePhone() {
        this.phoneE164 = null;
        this.phoneVerified = false;
        this.twoFactorEnabled = false;   // 번호가 없으면 2FA 를 켜 둘 수 없다 — 로그인이 막힌다
        touch();
    }

    public void setSmsEnabled(boolean enabled) {
        this.smsEnabled = enabled;
        touch();
    }

    /**
     * 2FA 를 켠다. 인증된 번호가 없으면 켤 수 없다 — 켜는 순간 본인도 로그인하지 못한다.
     *
     * @return 실제로 켜졌는지
     */
    public boolean enableTwoFactor(boolean enabled) {
        if (enabled && !phoneVerified) {
            return false;
        }
        this.twoFactorEnabled = enabled;
        touch();
        return true;
    }

    /**
     * 이 종류의 알림을 SMS 로 보낼 것인가.
     *
     * <p>보안 알림은 사용자 설정을 무시한다. 계정이 털리는 중이라는 신호를 설정으로 끌 수 있게
     * 두면, 공격자가 먼저 알림을 끄고 작업할 수 있다.
     */
    public boolean shouldSendSms(NotificationType type) {
        if (!phoneVerified || phoneE164 == null) {
            return false;
        }
        if (type == NotificationType.SECURITY_ALERT) {
            return true;
        }
        return smsEnabled && type.isSmsWorthyByDefault();
    }

    /**
     * 이 종류의 알림을 메일로도 보낼 것인가.
     *
     * <p>SMS 와 판정 기준이 다르다. SMS 는 <b>건당 과금</b>이라 기본적으로 꺼져 있고 중요한
     * 종류만 켜지지만({@code isSmsWorthyByDefault}), 메일은 비용이 사실상 없으므로 그런 선별이
     * 필요 없다. 대신 <b>보내면 안 되는 종류</b>를 뺀다 — 채팅 메시지마다 메일을 보내면
     * 메일함이 수십 통으로 차고, 그건 스팸이다({@link MailKind#forNotification}).
     *
     * <p>보안 알림이 사용자 설정을 무시하는 것은 SMS 와 같다. 계정이 털리는 중이라는 신호를
     * 설정으로 끌 수 있게 두면 공격자가 먼저 알림을 끄고 작업할 수 있다.
     */
    public boolean shouldSendEmail(NotificationType type) {
        if (email == null || email.isBlank()) {
            return false;
        }
        if (MailKind.forNotification(type).isEmpty()) {
            return false;
        }
        if (type == NotificationType.SECURITY_ALERT) {
            return true;
        }
        return emailEnabled;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
