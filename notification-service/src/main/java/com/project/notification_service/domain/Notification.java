package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
 * 사용자에게 보여줄 알림 1건.
 *
 * <p>chat-service 의 {@code Alarm} 엔티티를 대체한다. 그쪽은 채팅 전용이었고, 무엇보다
 * <b>어디에서도 저장되지 않는 죽은 코드</b>였다 — 엔티티·매퍼·DTO 는 있는데 리포지토리도
 * 저장 호출도 없었다(B-24 채팅 메시지 저장 누락과 같은 모양이다). 알림은 주문·결제·쿠폰까지
 * 걸치는 횡단 관심사라 채팅 서비스가 소유할 것도 아니다.
 *
 * <p>[JA] ユーザーに見せる通知 1 件。
 * chat-service の {@code Alarm} エンティティを置き換える。そちらはチャット専用で、何より
 * <b>どこでも保存されないデッドコード</b>だった — エンティティ・マッパー・DTO はあるのにリポジトリも保存呼び出しもなかった
 * (B-24 チャットメッセージ保存漏れと同じ形)。通知は注文・決済・クーポンまでまたがる横断的関心事なので、チャットサービスが所有するものでもない。
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification", schema = "notification",
       indexes = {
           // 화면이 항상 "내 알림을 최신순으로"를 묻는다. 이 조합이 없으면 사용자가 늘수록
           // 알림 목록이 전체 스캔이 된다.
           // [JA] 画面は常に「自分の通知を新しい順に」を問う。この組み合わせがないとユーザーが増えるほど
           //      通知一覧が全件スキャンになる。
           @Index(name = "idx_notification_recipient", columnList = "recipient_id, created_at"),
           // 안 읽은 개수 배지는 매 화면 진입마다 호출된다 — 부분 조건을 인덱스로 받친다.
           // [JA] 未読数バッジは画面に入るたびに呼ばれる — 部分条件をインデックスで支える。
           @Index(name = "idx_notification_unread", columnList = "recipient_id, read_status"),
           // 재시도 대상 조회 (SmsDispatcher).
           // [JA] リトライ対象の照会(SmsDispatcher)。
           @Index(name = "idx_notification_sms_status", columnList = "sms_status, created_at")
       })
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    /** 앱 안에서 보여줄 본문. SMS 문구는 이것과 별개로 짧게 만든다(SmsTemplates). */
    // [JA] アプリ内で見せる本文。SMS の文言はこれとは別に短く作る(SmsTemplates)。
    @Column(name = "content", nullable = false, length = 500)
    private String content;

    /** 눌렀을 때 이동할 경로. */
    // [JA] 押したときに移動するパス。
    @Column(name = "url", length = 300)
    private String url;

    @Column(name = "read_status", nullable = false)
    private boolean read;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Enumerated(EnumType.STRING)
    @Column(name = "sms_status", nullable = false, length = 20)
    private SmsStatus smsStatus;

    /** 발송 시도 횟수. 무한 재시도를 막는다. */
    // [JA] 送信試行回数。無限リトライを防ぐ。
    @Column(name = "sms_attempts", nullable = false)
    private int smsAttempts;

    @Column(name = "sms_detail", length = 300)
    private String smsDetail;

    /**
     * 다음 발송 시도를 허용할 시각. <b>null 이면 지금 보내도 된다.</b>
     *
     * <p>{@link MailMessage#getNextAttemptAt()} 과 같은 이유로 있다 — 상한만 있고 간격이 없으면
     * 3회 재시도가 15초 만에 소진된다. 경위는 {@link RetryBackoff} 에 적혀 있다.
     *
     * <p>[JA] 次の送信試行を許す時刻。<b>null なら今送ってよい。</b>
     * {@link MailMessage#getNextAttemptAt()} と同じ理由でここにある — 上限だけあって間隔がないと
     * 3 回のリトライが 15 秒で尽きる。経緯は {@link RetryBackoff} に書いてある。
     */
    @Column(name = "sms_next_attempt_at")
    private LocalDateTime smsNextAttemptAt;

    /**
     * 이 알림을 만든 원본 이벤트의 식별자.
     *
     * <p>UNIQUE 다. Kafka 재전달로 같은 이벤트가 두 번 와도 알림은 하나만 생긴다 — 사용자에게
     * 같은 문자가 두 번 가는 것을 막는 마지막 방어선이다. 멱등 원장(processed_event)이 1차
     * 방어지만, 원장과 알림 저장이 다른 트랜잭션으로 갈리는 경우까지 여기서 막는다.
     *
     * <p>[JA] この通知を作った元イベントの識別子。
     * UNIQUE である。Kafka の再配信で同じイベントが二度来ても通知は一つしかできない — ユーザーに同じ SMS が二度行くのを
     * 防ぐ最後の防衛線である。冪等台帳(processed_event)が一次防御だが、台帳と通知の保存が別のトランザクションに分かれる場合までここで防ぐ。
     */
    @Column(name = "source_event_id", length = 120, unique = true)
    private String sourceEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Notification(Long recipientId, NotificationType type, String title, String content,
                        String url, String sourceEventId, SmsStatus smsStatus) {
        this.recipientId = recipientId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.url = url;
        this.sourceEventId = sourceEventId;
        this.smsStatus = smsStatus == null ? SmsStatus.NOT_APPLICABLE : smsStatus;
        this.read = false;
        this.deleted = false;
        this.smsAttempts = 0;
        // UTC 로 통일한다. Asia/Seoul 하드코딩이 아웃박스 파티션 키를 어긋나게 했던 것과
        // 같은 실수를 반복하지 않는다(B-21). 표시 시점의 시간대 변환은 프론트엔드가 한다.
        // [JA] UTC に統一する。Asia/Seoul のハードコードがアウトボックスのパーティションキーをずらしたのと
        //      同じ失敗を繰り返さない(B-21)。表示時のタイムゾーン変換はフロントエンドが行う。
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void markRead() {
        this.read = true;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    /** 발송 시도 결과를 반영한다. 시도 횟수는 성공·실패 무관하게 오른다. */
    // [JA] 送信試行の結果を反映する。試行回数は成功・失敗に関係なく増える。
    public void recordSmsAttempt(SmsStatus status, String detail) {
        this.smsAttempts += 1;
        applySmsStatus(status, detail);
    }

    /**
     * 재시도를 예약한다 — 시도 횟수와 <b>다음에 집어도 되는 시각</b>을 함께 정한다.
     *
     * <p>{@link MailMessage#scheduleRetry} 와 같은 이유로 둘을 묶어 둔다. 시각 없이 횟수만
     * 올리면 다음 주기(5초 뒤)에 곧바로 다시 집혀 백오프가 사라진다.
     *
     * <p>[JA] リトライを予約する — 試行回数と<b>次に拾ってよい時刻</b>を併せて決める。
     * {@link MailMessage#scheduleRetry} と同じ理由で二つをまとめておく。時刻なしで回数だけ上げると
     * 次の周期(5 秒後)にすぐ拾われてバックオフが消える。
     */
    public void scheduleSmsRetry(String detail, long backoffBaseMs, long backoffMaxMs) {
        this.smsAttempts += 1;
        applySmsStatus(SmsStatus.RETRYABLE, detail);
        this.smsNextAttemptAt = RetryBackoff.nextAttemptAt(
                LocalDateTime.now(ZoneOffset.UTC), this.smsAttempts, backoffBaseMs, backoffMaxMs);
    }

    /**
     * 시도 횟수를 <b>올리지 않고</b> 포기한다. 방금 실패를 이미 센 뒤 확정하는 자리다 —
     * 여기서 {@link #recordSmsAttempt} 를 부르면 한 번의 발송이 두 번으로 기록된다.
     *
     * <p>[JA] 試行回数を<b>上げずに</b>諦める。直前の失敗を既に数えた後で確定する場所である —
     * ここで {@link #recordSmsAttempt} を呼ぶと一回の送信が二回と記録される。
     */
    public void giveUpSms(String detail) {
        applySmsStatus(SmsStatus.FAILED, detail);
    }

    private void applySmsStatus(SmsStatus status, String detail) {
        this.smsStatus = status;
        this.smsDetail = detail == null ? null
                : detail.substring(0, Math.min(detail.length(), 300));

        if (status == SmsStatus.SENT || status == SmsStatus.FAILED
                || status == SmsStatus.NOT_APPLICABLE) {
            this.smsNextAttemptAt = null;
        }
    }
}
