package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 전체 사용자에게 보내는 알림 — 공지사항 게시, 관리자 안내.
 *
 * <p><b>왜 개인 알림({@code notification}) 행을 만들지 않는가.</b> 공지 하나에 사용자 수만큼
 * 행을 만들면 사용자가 10만이면 공지 한 건에 10만 행이다. 그것이
 *
 * <ul>
 *   <li>쓰기 폭증을 만들고(공지 하나 올릴 때마다 10만 insert),</li>
 *   <li>테이블을 사용자 수 × 공지 수로 부풀리고,</li>
 *   <li>공지 내용을 고치면 10만 행을 전부 고쳐야 한다.</li>
 * </ul>
 *
 * <p>여기는 <b>행 하나</b>다. 개인별로 필요한 것은 "읽었는가" 뿐이고 그것만
 * {@link BroadcastRead} 에 남긴다 — <b>읽은 사람만</b> 행이 생기므로 안 읽은 상태는
 * 공짜다.
 *
 * <p><b>기간.</b> {@code startsAt} 이전이거나 {@code endsAt} 이후면 목록에 나오지 않는다.
 * 점검 예고처럼 지나면 의미가 없는 안내를 사용자가 직접 지우게 하지 않기 위해서다.
 * {@code endsAt} 이 null 이면 만료 없음.
 *
 * <p>[JA] 全ユーザーに送る通知 — お知らせ掲示、管理者案内。
 * <b>なぜ個人通知({@code notification})の行を作らないか。</b> お知らせ一つにユーザー数分の行を作ると、ユーザーが 10 万なら
 * お知らせ一件で 10 万行である。それは書き込みの急増を生み(お知らせを一つ上げるたびに 10 万 insert)、テーブルをユーザー数 × お知らせ数に
 * 膨らませ、お知らせの内容を直すと 10 万行を全部直さなければならない。
 * ここは<b>行一つ</b>である。個人別に必要なのは「読んだか」だけで、それだけを {@link BroadcastRead} に残す —
 * <b>読んだ人だけ</b>行ができるので未読状態はタダである。
 * <b>期間。</b> {@code startsAt} より前か {@code endsAt} より後なら一覧に出ない。
 * メンテナンス予告のように過ぎれば意味のない案内をユーザーに自分で消させないためである。{@code endsAt} が null なら期限なし。
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "broadcast_notification", schema = "notification",
       indexes = {
           @Index(columnList = "starts_at", name = "idx_broadcast_starts"),
           @Index(columnList = "source_event_id", name = "idx_broadcast_source_event")
       })
public class BroadcastNotification {

    @Id
    @Tsid
    @Column(name = "broadcast_id")
    private Long broadcastId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String content;

    @Column(length = 512)
    private String url;

    /**
     * 같은 사건이 두 번 와도 공지가 하나만 생기게 하는 키.
     *
     * <p>개인 알림의 {@code sourceEventId} 와 같은 역할이다. 카프카는 at-least-once 라
     * 공지 게시 이벤트가 재전달될 수 있는데, 그때마다 공지가 하나씩 더 생기면 안 된다.
     * DB 의 UNIQUE 제약이 최종 방어선이다.
     *
     * <p>[JA] 同じ出来事が二度来てもお知らせが一つだけできるようにするキー。
     * 個人通知の {@code sourceEventId} と同じ役割である。Kafka は at-least-once なので
     * お知らせ掲示イベントが再配信されうるが、そのたびにお知らせが一つずつ増えてはならない。
     * DB の UNIQUE 制約が最後の防衛線である。
     */
    @Column(name = "source_event_id", unique = true)
    private String sourceEventId;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** null 이면 만료 없음. */
    // [JA] null なら期限なし。
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 시각은 UTC 로 통일한다(B-21).
     *
     * <p>여기서만 시스템 시간대를 쓰면 <b>두 군데가 조용히 깨진다.</b> 목록은 개인 알림과
     * 전체 알림을 합쳐 {@code createdAt} 으로 정렬하는데, 개인 알림은 UTC 로 찍히므로
     * 서버가 KST 면 공지가 언제나 9시간 미래로 보여 항상 맨 위에 붙는다. 그리고 화면은
     * 서버 시각을 UTC 로 보고 현지 시각으로 바꾸므로(NotificationBell 의 RelativeTime),
     * 그 공지는 영영 "방금"으로 표시된다.
     *
     * <p>[JA] 時刻は UTC に統一する(B-21)。
     * ここだけシステムのタイムゾーンを使うと<b>二か所が静かに壊れる。</b> 一覧は個人通知と全体通知を合わせて {@code createdAt} で並べるが、
     * 個人通知は UTC で記録されるので、サーバーが KST だとお知らせがいつも 9 時間未来に見えて常に一番上に付く。そして画面は
     * サーバー時刻を UTC とみなして現地時刻に変えるので(NotificationBell の RelativeTime)、そのお知らせはずっと「たった今」と表示される。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (startsAt == null) {
            startsAt = now;
        }
    }

    /** 지금 사용자에게 보여야 하는가. */
    // [JA] 今ユーザーに見えるべきか。
    public boolean isActiveAt(LocalDateTime when) {
        if (startsAt != null && when.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || !when.isAfter(endsAt);
    }
}
