package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.project.notification_service.mail.EmailAddresses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발송 대기 중인 메일 1건 — <b>메일 아웃박스</b>다.
 *
 * <p><b>왜 {@link Notification} 에 컬럼을 붙이지 않았나.</b> 가입 확인·비밀번호 재설정 메일은
 * 인앱 알림이 아니다. 받는 사람이 아직 로그인조차 하지 않았고, 알림 목록에 보여줄 것도 아니다.
 * {@code Notification} 에 얹으면 그 화면에 "알림"이 아닌 행이 섞이고, {@code recipientId} 가
 * NOT NULL 이라 가입 확인 메일을 저장할 수도 없다.
 *
 * <p><b>왜 Kafka 에서 바로 보내지 않고 DB 에 한 번 적나.</b> SMS 와 같은 이유다
 * ({@link com.project.notification_service.service.SmsDispatcher}). 컨슈머 안에서 바로 SMTP 를
 * 호출하면 (1) 메일 서버가 느릴 때 컨슈머가 붙잡혀 다른 알림까지 밀리고, (2) 전송 실패를 컨슈머
 * 재시도로 처리하면 <b>재시도할 때마다 메일이 한 통씩 더</b> 나간다. 여기에 적어 두고 별도
 * 디스패처가 상태를 보며 보내면 두 문제가 같이 사라진다.
 *
 * <p>⚠️ <b>{@code params} 는 자격증명을 담는다.</b> {@code REGISTRATION_CONFIRM} 의
 * {@code confirmationUrl} 은 그 링크만으로 계정을 활성화할 수 있다. user-service 는 토큰을
 * SHA-256 해시로만 저장하는데, 여기 원문 링크가 남아 있으면 그 방어가 무의미해진다. 그래서
 * <b>종료 상태에 도달하는 즉시 {@link #clearParams()} 로 지운다</b> — 보관 기간을 "발송까지"로
 * 좁히는 것이 이 설계에서 할 수 있는 최선이다. 그리고 {@link #toString()} 은 절대 params 를
 * 포함하지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "mail_message", schema = "notification",
       indexes = {
           // 디스패처가 매 주기 도는 질의. 이게 없으면 보낸 메일이 쌓일수록 폴링이 전체 스캔이 된다.
           @Index(name = "idx_mail_message_status", columnList = "status, created_at"),
           // 운영 조회: "이 사람에게 나간 메일" — 문의 대응에 매번 필요하다.
           @Index(name = "idx_mail_message_recipient", columnList = "to_email, created_at")
       })
public class MailMessage {

    /** 재시도해도 의미 없는 시점. 이 값을 넘긴 메일은 보내 봐야 링크가 이미 만료다. */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mail_id")
    private Long mailId;

    @Column(name = "to_email", nullable = false, length = 320)
    private String toEmail;

    /** 알 수 없으면 null (가입 확인처럼 설정이 아직 없는 경우). */
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40)
    private MailKind kind;

    /**
     * 문구에 채울 값들. JSON 문자열로 저장한다.
     *
     * <p>⚠️ 발송이 끝나면 지운다({@link #clearParams()}). 아래 필드 주석과 클래스 주석 참조.
     */
    @Column(name = "params", length = 2000)
    private String params;

    /** {@code ko} / {@code ja}. null 이면 설정의 지역, 그것도 없으면 한국어. */
    @Column(name = "locale", length = 10)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MailStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "detail", length = 300)
    private String detail;

    /**
     * 이 메일을 만든 원본 명령의 식별자. UNIQUE.
     *
     * <p>Kafka 재전달로 같은 명령이 두 번 와도 메일은 하나만 나간다. 확인 메일이 두 통 가는 것은
     * 성가신 정도지만, 비밀번호 재설정 링크가 두 개 살아 있는 것은 보안 문제다.
     */
    @Column(name = "source_event_id", length = 120, unique = true)
    private String sourceEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 종료 상태에 도달한 시각. 미발송이면 null. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 다음 발송 시도를 허용할 시각. <b>null 이면 지금 보내도 된다.</b>
     *
     * <p>이 컬럼이 없던 동안 재시도는 이름뿐이었다 — 디스패처가 5초마다 같은 행을 다시 집어
     * 5회 상한이 20초 만에 소진됐다({@link RetryBackoff} 에 경위가 적혀 있다).
     *
     * <p>null 을 "지금"으로 읽는 것은 의도적이다. 새 행과 이 컬럼이 없던 시절의 행이 모두
     * null 인데, 둘 다 즉시 보내는 것이 맞다 — 기본값을 넣어 두면 배포 순간에 밀린 메일이
     * 한꺼번에 미뤄진다.
     */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Builder
    public MailMessage(String toEmail, Long recipientUserId, MailKind kind,
                       String params, String locale, String sourceEventId) {
        this.toEmail = toEmail;
        this.recipientUserId = recipientUserId;
        this.kind = kind;
        this.params = params;
        this.locale = locale;
        this.sourceEventId = sourceEventId;
        this.status = MailStatus.PENDING;
        this.attempts = 0;
        // UTC 로 통일한다 — Notification 과 같은 이유(B-21).
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 발송 시도 결과를 반영한다. 시도 횟수는 성공·실패 무관하게 오른다.
     *
     * <p>종료 상태({@code SENT}/{@code FAILED})면 params 를 지운다 — 링크를 필요 이상으로
     * 보관하지 않는다.
     */
    public void recordAttempt(MailStatus status, String detail) {
        this.attempts += 1;
        applyStatus(status, detail);
    }

    /**
     * 재시도를 예약한다 — 시도 횟수를 올리고 <b>다음에 집어도 되는 시각</b>을 함께 정한다.
     *
     * <p>두 가지를 한 메서드에 묶은 이유: 떼어 놓으면 한쪽만 부르는 경로가 생긴다. 시각 없이
     * 횟수만 올리면 다음 주기(5초 뒤)에 곧바로 다시 집혀 백오프가 없던 때로 돌아간다.
     */
    public void scheduleRetry(String detail, long backoffBaseMs, long backoffMaxMs) {
        this.attempts += 1;
        applyStatus(MailStatus.RETRYABLE, detail);
        this.nextAttemptAt = RetryBackoff.nextAttemptAt(
                LocalDateTime.now(ZoneOffset.UTC), this.attempts, backoffBaseMs, backoffMaxMs);
    }

    /**
     * 시도 횟수를 <b>올리지 않고</b> 포기한다.
     *
     * <p>방금 실패를 이미 센 뒤 "이번이 마지막이었다"고 확정하는 자리다. 여기서
     * {@link #recordAttempt} 를 부르면 한 번의 발송이 두 번으로 기록돼, 나중에 "몇 번 시도했나"를
     * 보는 사람이 상한을 넘긴 것으로 읽는다(실제로 attempts 가 상한보다 1 큰 값으로 남아 있었다).
     */
    public void giveUp(String detail) {
        applyStatus(MailStatus.FAILED, detail);
    }

    private void applyStatus(MailStatus status, String detail) {
        this.status = status;
        this.detail = detail == null ? null
                : detail.substring(0, Math.min(detail.length(), 300));

        if (status == MailStatus.SENT || status == MailStatus.FAILED) {
            this.completedAt = LocalDateTime.now(ZoneOffset.UTC);
            // 끝난 행에 예약 시각을 남겨 두면 "언제 다시 보내지나"를 묻게 만든다. 답은 '안 보낸다'다.
            this.nextAttemptAt = null;
            clearParams();
        }
    }

    /**
     * 저장된 템플릿 값을 지운다.
     *
     * <p>확인 링크·재설정 링크는 자격증명이다. 메일이 나간 뒤에도 DB 에 남겨 둘 이유가 없고,
     * 남겨 두면 DB 덤프 한 번이 곧 계정 탈취 수단이 된다.
     */
    public void clearParams() {
        this.params = null;
    }

    /** 아직 보낼 수 있는가. 상한을 넘으면 더 시도하지 않는다. */
    public boolean canRetry(int maxAttempts) {
        return attempts < maxAttempts;
    }

    /**
     * ⚠️ params 를 <b>절대</b> 포함하지 않는다. 주소도 마스킹한다 — 로그 열람 권한이 곧
     * 고객 연락처 열람 권한이 되면 안 된다(LoggingSmsSender 와 같은 규칙).
     */
    @Override
    public String toString() {
        return "MailMessage(id=" + mailId
                + ", kind=" + kind
                + ", to=" + EmailAddresses.mask(toEmail)
                + ", status=" + status
                + ", attempts=" + attempts + ")";
    }

    /** 테스트·조회용. 저장된 JSON 을 그대로 돌려준다(파싱은 호출자 몫). */
    public String rawParams() {
        return params;
    }
}
